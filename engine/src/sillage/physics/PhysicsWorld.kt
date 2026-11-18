package sillage.physics

import sillage.math.Quat
import sillage.math.ValueCandidate
import sillage.math.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class BodyType { STATIC, DYNAMIC, KINEMATIC }

// Solver state of one body, contiguous so that an impulse touches one or two cache lines:
// velocity, angular velocity, inverse mass, world inverse inertia (xx xy xz yy yz zz),
// displacement and rotation since the step began.
private const val S = 20
private const val VX = 0; private const val VY = 1; private const val VZ = 2
private const val WX = 3; private const val WY = 4; private const val WZ = 5
private const val IM = 6; private const val I0 = 7
private const val DPX = 13; private const val DQX = 16; private const val DQW = 19

/** One contact point of the last step, see [PhysicsWorld.contacts]. */
class Contact(val a: Body?, val b: Body, val point: Vec3, val normal: Vec3, val separation: Float, val normalImpulse: Float)

/** Handle to a body of a [PhysicsWorld]. Two handles to the same body are equal. */
@ValueCandidate
class Body internal constructor(private val world: PhysicsWorld, val id: Int) {
    private val o get() = id * S
    val type: BodyType get() = world.types[id]
    val shape: Shape get() = world.shapes[id]!!
    var position: Vec3
        get() = Vec3(world.px[id], world.py[id], world.pz[id])
        set(v) { world.px[id] = v.x; world.py[id] = v.y; world.pz[id] = v.z; world.wake(id); world.queryGrid.valid = false }
    var rotation: Quat
        get() = Quat(world.qx[id], world.qy[id], world.qz[id], world.qw[id])
        set(q) { val u = q.normalized(); world.qx[id] = u.x; world.qy[id] = u.y; world.qz[id] = u.z; world.qw[id] = u.w; world.wake(id); world.queryGrid.valid = false }
    var velocity: Vec3
        get() = Vec3(world.st[o + VX], world.st[o + VY], world.st[o + VZ])
        set(v) { world.st[o + VX] = v.x; world.st[o + VY] = v.y; world.st[o + VZ] = v.z; world.wake(id) }
    var angularVelocity: Vec3
        get() = Vec3(world.st[o + WX], world.st[o + WY], world.st[o + WZ])
        set(v) { world.st[o + WX] = v.x; world.st[o + WY] = v.y; world.st[o + WZ] = v.z; world.wake(id) }
    val mass: Float get() = world.st[o + IM].let { if (it > 0f) 1f / it else Float.POSITIVE_INFINITY }
    var friction: Float
        get() = world.friction[id]
        set(v) { world.friction[id] = v }
    var restitution: Float
        get() = world.restitution[id]
        set(v) { world.restitution[id] = v }
    var linearDamping: Float
        get() = world.linearDamping[id]
        set(v) { world.linearDamping[id] = v }
    var angularDamping: Float
        get() = world.angularDamping[id]
        set(v) { world.angularDamping[id] = v }
    var gravityScale: Float
        get() = world.gravityScale[id]
        set(v) { world.gravityScale[id] = v }
    /** Torque that opposes rolling, as a fraction of the contact force times the radius. */
    var rollingResistance: Float
        get() = world.rolling[id]
        set(v) { world.rolling[id] = v }
    val isSleeping: Boolean get() = world.sleeping[id]
    /** True once [PhysicsWorld.remove] took it out (its slot may since hold another body). */
    val isRemoved: Boolean get() = world.removed[id]
    /** Free label for the game (a role, a group, the object it moves…); the engine never reads it. */
    var tag: Any?
        get() = world.tags[id]
        set(v) { world.tags[id] = v }

    /** Instant change of momentum, applied at [point] (the centre by default). */
    fun applyImpulse(impulse: Vec3, point: Vec3 = position) {
        if (world.types[id] != BodyType.DYNAMIC) return
        world.wake(id)
        val m = world.st[o + IM]
        world.st[o + VX] += impulse.x * m; world.st[o + VY] += impulse.y * m; world.st[o + VZ] += impulse.z * m
        world.prepareInertia(id)
        val w = world.mulInvInertia(id, (point - position) cross impulse)
        world.st[o + WX] += w.x; world.st[o + WY] += w.y; world.st[o + WZ] += w.z
    }

    /** For a kinematic body: sets the velocities that reach [target] (and [targetRotation]) in [dt] seconds. */
    fun moveTo(target: Vec3, dt: Float, targetRotation: Quat? = null) {
        require(dt > 0f)
        velocity = (target - position) / dt
        if (targetRotation != null) {
            var d = targetRotation * rotation.conjugate()
            if (d.w < 0f) d = Quat(-d.x, -d.y, -d.z, -d.w)
            val s = sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
            angularVelocity = if (s < 1e-6f) Vec3.ZERO else Vec3(d.x, d.y, d.z) * (2f * acos(d.w.coerceIn(-1f, 1f)) / (s * dt))
        }
    }

    override fun equals(other: Any?) = other is Body && other.id == id && other.world === world
    override fun hashCode() = id
    override fun toString() = "Body#$id($type, ${shape::class.simpleName})"
}

/**
 * Rigid-body world, entirely in Kotlin.
 *
 * Solver: soft-step sequential impulses after Box2D v3 (E. Catto): [substeps] substeps per step,
 * each integrating velocities, warm starting, solving with soft position correction, integrating
 * positions and relaxing without bias; then restitution. Contacts within the speculative margin
 * are solved before they touch, which keeps fast bodies out of thin geometry. Bodies resting
 * together fall asleep as an island.
 */
class PhysicsWorld(var gravity: Vec3 = Vec3(0f, -9.81f, 0f)) {
    var substeps = 4
    /** Stiffness of contact correction (Hz) and its damping ratio. */
    var contactHertz = 30f
    var contactDampingRatio = 10f
    /** Fastest speed at which overlapping bodies are pushed apart (m/s). */
    var contactSpeed = 3f
    /** Approach speed below which bodies do not bounce (m/s). */
    var restitutionThreshold = 1f
    /** A body may sleep when its fastest point moves slower than this (m/s). */
    var sleepLinearSpeed = .05f
    var timeToSleep = .5f
    var speculativeDistance = .02f
    /**
     * Large scenes use the engine's worker threads (one pool per process, up to 8 threads): broad
     * phase, contacts and their preparation, and the solver by graph colouring. The result is the same
     * bit for bit with or without threads, whatever their number; small scenes always run on one.
     */
    var parallel = true

    internal var count = 0
    private var capacity = 0
    internal var types = Array(0) { BodyType.STATIC }
    internal var shapes = arrayOfNulls<Shape>(0)
    internal var tags = arrayOfNulls<Any>(0)
    internal var px = FloatArray(0); internal var py = FloatArray(0); internal var pz = FloatArray(0)
    internal var qx = FloatArray(0); internal var qy = FloatArray(0); internal var qz = FloatArray(0); internal var qw = FloatArray(0)
    internal var st = FloatArray(0)
    private var ilx = FloatArray(0); private var ily = FloatArray(0); private var ilz = FloatArray(0)   // inverse local inertia
    internal var friction = FloatArray(0); internal var restitution = FloatArray(0)
    internal var linearDamping = FloatArray(0); internal var angularDamping = FloatArray(0); internal var gravityScale = FloatArray(0)
    internal var rolling = FloatArray(0); private var rollRadius = FloatArray(0)
    /** Spheres look the same however they turn: their contact anchors need not rotate. */
    private var round = BooleanArray(0)
    internal var sleeping = BooleanArray(0); private var sleepClock = FloatArray(0)
    internal var removed = BooleanArray(0)
    private val free = IntList()
    private var fx = FloatArray(0); private var fy = FloatArray(0); private var fz = FloatArray(0)
    private var q0 = FloatArray(0)
    private var box = FloatArray(0)
    private var poses = arrayOfNulls<Pose>(0)

    internal val meshes = ArrayList<TriangleMesh>()

    val bodyCount get() = count
    /** When true, [phaseNanos] accumulates time per phase: bodies, pairs, contacts, prepare, warm start, solve, relax, sleep. */
    var profiling = false
    val phaseNanos = LongArray(8)
    /** Pairs and contact points of the last step (diagnostics). */
    val pairCount get() = pairsA.size
    val contactCount get() = previous.size
    /**
     * The contact points of the last step: [Contact.a] touches [Contact.b] (a is null for a static mesh),
     * the normal points from a to b, a negative separation is an overlap (measured when the contact was
     * found, at the start of the step). For debug drawing, gameplay (who touches whom) and checks.
     */
    fun contacts(): List<Contact> = List(previous.size) { k ->
        val d = previous.d; val o = k * C
        Contact(if (previous.a[k] >= 0) Body(this, previous.a[k]) else null, Body(this, previous.b[k]),
            Vec3(previous.x[k], previous.y[k], previous.z[k]), Vec3(d[o + CNX], d[o + CNX + 1], d[o + CNX + 2]),
            previous.sep[k], d[o + CLN])
    }

    /** Sum of the normal impulses on [body] during the last substep (diagnostics; ≈ m·g·h at rest). */
    internal fun normalImpulseOn(body: Int): Float {
        var t = 0f
        for (k in 0 until previous.size) if (previous.a[k] == body || previous.b[k] == body) t += previous.d[k * C + CLN]
        return t
    }

    // ------------------------------------------------------------------ bodies

    private fun grow() {
        val c = max(16, capacity * 2)
        types = Array(c) { if (it < capacity) types[it] else BodyType.STATIC }
        shapes = shapes.copyOf(c); tags = tags.copyOf(c)
        px = px.copyOf(c); py = py.copyOf(c); pz = pz.copyOf(c)
        qx = qx.copyOf(c); qy = qy.copyOf(c); qz = qz.copyOf(c); qw = qw.copyOf(c)
        st = st.copyOf(S * c)
        ilx = ilx.copyOf(c); ily = ily.copyOf(c); ilz = ilz.copyOf(c)
        friction = friction.copyOf(c); restitution = restitution.copyOf(c)
        linearDamping = linearDamping.copyOf(c); angularDamping = angularDamping.copyOf(c); gravityScale = gravityScale.copyOf(c)
        rolling = rolling.copyOf(c); rollRadius = rollRadius.copyOf(c); round = round.copyOf(c)
        sleeping = sleeping.copyOf(c); sleepClock = sleepClock.copyOf(c); removed = removed.copyOf(c)
        fx = fx.copyOf(c); fy = fy.copyOf(c); fz = fz.copyOf(c)
        q0 = q0.copyOf(4 * c); box = box.copyOf(6 * c); poses = poses.copyOf(c)
        capacity = c
    }

    /**
     * Adds a body. Its mass comes from [density] (kg/m³) unless [mass] is given; static and
     * kinematic bodies have infinite mass. Balls and capsules get a little rolling resistance.
     */
    fun add(shape: Shape, position: Vec3, rotation: Quat = Quat.IDENTITY, type: BodyType = BodyType.DYNAMIC,
            density: Float = 1000f, mass: Float? = null, friction: Float = .6f, restitution: Float = 0f,
            rollingResistance: Float? = null): Body {
        queryGrid.valid = false
        val i = if (free.size > 0) free.pop() else { if (count == capacity) grow(); count++ }
        // A reused slot starts afresh.
        removed[i] = false; sleeping[i] = false; sleepClock[i] = 0f; tags[i] = null
        fx[i] = 0f; fy[i] = 0f; fz[i] = 0f; java.util.Arrays.fill(st, i * S, i * S + S, 0f)
        linearDamping[i] = 0f; angularDamping[i] = 0f
        types[i] = type; shapes[i] = shape
        px[i] = position.x; py[i] = position.y; pz[i] = position.z
        val q = rotation.normalized(); qx[i] = q.x; qy[i] = q.y; qz[i] = q.z; qw[i] = q.w
        st[i * S + DQW] = 1f
        if (type == BodyType.DYNAMIC) {
            val m = mass ?: (density * shape.volume)
            require(m > 0f) { "A dynamic body needs a positive mass" }
            st[i * S + IM] = 1f / m
            val inertia = shape.inertia(m)
            ilx[i] = 1f / inertia.x; ily[i] = 1f / inertia.y; ilz[i] = 1f / inertia.z
        }
        this.friction[i] = friction; this.restitution[i] = restitution; gravityScale[i] = 1f
        // Les boules et capsules finissent par s'arrêter de rouler (sinon un tas ne s'endort jamais).
        rollRadius[i] = when (shape) { is Sphere -> shape.radius; is Capsule -> shape.radius; else -> 0f }
        round[i] = shape is Sphere
        rolling[i] = rollingResistance ?: if (rollRadius[i] > 0f) .02f else 0f
        return Body(this, i)
    }

    fun body(id: Int): Body { require(id in 0 until count); return Body(this, id) }

    /**
     * Takes [body] out of the world: it no longer moves, collides or shows in queries, and its contacts
     * end with the next step. Its slot (and id) may then be given to a body added later, so forget the handle.
     */
    fun remove(body: Body) {
        val i = body.id
        require(i in 0 until count && !removed[i]) { "This body is not in the world" }
        // What rested on it or against it wakes up (a sleeping pile keeps no contacts: use the bounds).
        val o = 6 * i
        for (j in 0 until count) {
            if (j == i || removed[j] || types[j] != BodyType.DYNAMIC || !sleeping[j]) continue
            val p = 6 * j
            if (box[p] <= box[o + 3] + .05f && box[p + 3] >= box[o] - .05f && box[p + 1] <= box[o + 4] + .05f &&
                box[p + 4] >= box[o + 1] - .05f && box[p + 2] <= box[o + 5] + .05f && box[p + 5] >= box[o + 2] - .05f) wake(j)
        }
        removed[i] = true; types[i] = BodyType.STATIC; sleeping[i] = false; tags[i] = null
        queryGrid.valid = false
        java.util.Arrays.fill(st, i * S, i * S + 6, 0f)
        free.add(i)
    }

    /** Bodies in the world (removed ones excluded). */
    val liveBodyCount: Int get() = count - free.size

    /** Adds static triangles (terrain, buildings); each triangle carries its [Material]. */
    fun addMesh(mesh: TriangleMesh) { meshes.add(mesh) }

    /** Adds static triangles that all have the sliding friction [friction] (default material otherwise). */
    fun addMesh(mesh: TriangleMesh, friction: Float) {
        meshes.add(TriangleMesh(mesh.v, null, listOf(Material(friction = friction))))
    }

    internal fun wake(i: Int) { sleeping[i] = false; sleepClock[i] = 0f }

    /** Applies a force at the centre during the next step. */
    fun applyForce(body: Body, force: Vec3) { val i = body.id; fx[i] += force.x; fy[i] += force.y; fz[i] += force.z; wake(i) }

    // ------------------------------------------------------------------ inertia

    /** World inverse inertia R · diag(invI) · Rᵀ into the body's solver state. */
    internal fun prepareInertia(i: Int) {
        val x = qx[i]; val y = qy[i]; val z = qz[i]; val w = qw[i]
        val r00 = 1 - 2 * (y * y + z * z); val r01 = 2 * (x * y - z * w); val r02 = 2 * (x * z + y * w)
        val r10 = 2 * (x * y + z * w); val r11 = 1 - 2 * (x * x + z * z); val r12 = 2 * (y * z - x * w)
        val r20 = 2 * (x * z - y * w); val r21 = 2 * (y * z + x * w); val r22 = 1 - 2 * (x * x + y * y)
        val a = ilx[i]; val b = ily[i]; val c = ilz[i]
        val o = i * S + I0
        st[o] = r00 * r00 * a + r01 * r01 * b + r02 * r02 * c
        st[o + 1] = r00 * r10 * a + r01 * r11 * b + r02 * r12 * c
        st[o + 2] = r00 * r20 * a + r01 * r21 * b + r02 * r22 * c
        st[o + 3] = r10 * r10 * a + r11 * r11 * b + r12 * r12 * c
        st[o + 4] = r10 * r20 * a + r11 * r21 * b + r12 * r22 * c
        st[o + 5] = r20 * r20 * a + r21 * r21 * b + r22 * r22 * c
    }

    internal fun mulInvInertia(i: Int, v: Vec3): Vec3 {
        val o = i * S + I0
        return Vec3(st[o] * v.x + st[o + 1] * v.y + st[o + 2] * v.z,
            st[o + 1] * v.x + st[o + 3] * v.y + st[o + 4] * v.z,
            st[o + 2] * v.x + st[o + 4] * v.y + st[o + 5] * v.z)
    }

    // ------------------------------------------------------------------ step

    private var points = ContactSet()
    private var previous = ContactSet()
    private var patches = PatchSet()
    private var previousPatches = PatchSet()
    private val pairsA = IntList(); private val pairsB = IntList()
    private val found = IntList(); private val stack = IntList()
    private val scratch = ContactPoints()
    internal val triggers = ArrayList<Trigger>()
    internal val triggerScratch = Touches()
    // Scratch for the queries (single-threaded, not reentrant: a query's filter must not query again).
    internal val queryStack = IntList(); internal val queryFound = IntList(); internal val queryScratch = ContactPoints()
    internal val queryGrid = QueryGrid(); internal val queryBodies = IntList()

    /** The grid of the bodies for a query, built if bodies moved since; null for a small world (searched body by body). */
    internal var queryGridEnabled = true
    internal fun gridFor(): QueryGrid? {
        if (!queryGridEnabled || count - free.size < QueryGrid.MIN_BODIES) return null
        if (!queryGrid.valid) queryGrid.build(this)
        return queryGrid
    }
    private val events = ArrayList<() -> Unit>()

    /** Advances the simulation by [dt] seconds, then tells the [Trigger]s who came in and who left. */
    fun step(dt: Float) {
        if (dt <= 0f || count == 0) return
        val h = dt / substeps
        var t = if (profiling) System.nanoTime() else 0L
        fun lap(k: Int) { if (profiling) { val n = System.nanoTime(); phaseNanos[k] += n - t; t = n } }
        prepareBodies(dt); lap(0)
        findPairs(); lap(1)
        collide(dt); lap(2)
        prepareContacts(h); lap(3)
        repeat(substeps) {
            integrateVelocities(h)
            warmStart(); lap(4)
            solve(h, true); lap(5)
            integratePositions(h)
            solve(h, false); lap(6)
        }
        restitute()
        updateSleep(dt); lap(7)
        for (i in 0 until count) { fx[i] = 0f; fy[i] = 0f; fz[i] = 0f }
        // Les points de ce pas deviennent « précédents » ; l'autre ensemble servira au pas suivant.
        points.keep()
        val spare = previous; previous = points; points = spare
        patches.keep()
        val spareP = previousPatches; previousPatches = patches; patches = spareP
        queryGrid.valid = false
        // Zones last, and their callbacks after every zone is checked: a callback may move or add things.
        if (triggers.isNotEmpty()) {
            events.clear()
            for (k in triggers.indices) triggers[k].check(events)
            for (e in events) e()
        }
    }

    private fun moving(i: Int) = when (types[i]) {
        BodyType.DYNAMIC -> !sleeping[i]
        BodyType.KINEMATIC -> { val o = i * S; st[o] != 0f || st[o + 1] != 0f || st[o + 2] != 0f || st[o + 3] != 0f || st[o + 4] != 0f || st[o + 5] != 0f }
        BodyType.STATIC -> false
    }

    private fun speed(i: Int): Float {
        if (!moving(i)) return 0f
        val o = i * S
        return sqrt(st[o] * st[o] + st[o + 1] * st[o + 1] + st[o + 2] * st[o + 2])
    }

    private var stepDt = 0f
    private val prepareBodiesJob = Job { from, to -> for (i in from until to) prepareBody(i, stepDt) }

    private fun prepareBodies(dt: Float) {
        stepDt = dt
        forChunks(count, 512, count >= PARALLEL_BODIES, prepareBodiesJob)
    }

    /** Runs [job] in chunks: on the worker threads when [large] (and [parallel]), else here, in the same chunks. */
    private fun forChunks(n: Int, chunk: Int, large: Boolean, job: Job) {
        if (parallel && large) Workers.shared.run(n, chunk, job) else Workers.serialChunks(n, chunk, job)
    }

    private fun prepareBody(i: Int, dt: Float) {
        val x = qx[i]; val y = qy[i]; val z = qz[i]; val w = qw[i]
        // A body that has not moved since the last step keeps its pose (static, asleep, still).
        val old = poses[i]
        if (old == null || old.p.x != px[i] || old.p.y != py[i] || old.p.z != pz[i] ||
            q0[4 * i] != x || q0[4 * i + 1] != y || q0[4 * i + 2] != z || q0[4 * i + 3] != w) {
            val ax = Vec3(1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w))
            val ay = Vec3(2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w))
            val az = Vec3(2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y))
            poses[i] = Pose(Vec3(px[i], py[i], pz[i]), ax, ay, az)
        }
        val pose = poses[i]!!; val ax = pose.ax; val ay = pose.ay; val az = pose.az; val p = pose.p
        if (types[i] == BodyType.DYNAMIC) prepareInertia(i)
        q0[4 * i] = x; q0[4 * i + 1] = y; q0[4 * i + 2] = z; q0[4 * i + 3] = w
        val o = i * S
        st[o + DPX] = 0f; st[o + DPX + 1] = 0f; st[o + DPX + 2] = 0f
        st[o + DQX] = 0f; st[o + DQX + 1] = 0f; st[o + DQX + 2] = 0f; st[o + DQW] = 1f
        // Bounds, widened by the distance travelled during the step.
        var ex: Float; var ey: Float; var ez: Float
        when (val s = shapes[i]!!) {
            is Sphere -> { ex = s.radius; ey = s.radius; ez = s.radius }
            is Box -> {
                ex = abs(ax.x) * s.halfX + abs(ay.x) * s.halfY + abs(az.x) * s.halfZ
                ey = abs(ax.y) * s.halfX + abs(ay.y) * s.halfY + abs(az.y) * s.halfZ
                ez = abs(ax.z) * s.halfX + abs(ay.z) * s.halfY + abs(az.z) * s.halfZ
            }
            is Capsule -> { ex = abs(ay.x) * s.halfHeight + s.radius; ey = abs(ay.y) * s.halfHeight + s.radius; ez = abs(ay.z) * s.halfHeight + s.radius }
        }
        val m = speculativeDistance + speed(i) * dt
        ex += m; ey += m; ez += m
        val b = 6 * i
        box[b] = p.x - ex; box[b + 1] = p.y - ey; box[b + 2] = p.z - ez
        box[b + 3] = p.x + ex; box[b + 4] = p.y + ey; box[b + 5] = p.z + ez
    }

    // Broad phase: a uniform grid. Every small body is entered in each cell its bounds overlap, as one
    // Long (cell coordinates on 13 bits each, then the body on 24 bits); one primitive sort groups the
    // cells. A pair is reported only in the cell that holds the lowest corner of the two bounds'
    // overlap, so no pair comes twice and no set is needed. Large bodies (walls) are tested apart.
    private var cells = LongArray(0)
    private val large = IntList()
    private var isLarge = BooleanArray(0)
    private var cellSize = 1f

    private fun cellOf(v: Float) = floor(v / cellSize).toInt().coerceIn(-4095, 4095) + 4096

    private fun considerPair(i: Int, j: Int) {
        val oi = 6 * i; val oj = 6 * j
        if (box[oj] > box[oi + 3] || box[oj + 3] < box[oi] || box[oj + 1] > box[oi + 4] || box[oj + 4] < box[oi + 1] ||
            box[oj + 2] > box[oi + 5] || box[oj + 5] < box[oi + 2]) return
        if (mayPair(i, j)) acceptPair(i, j)
    }

    /** Whether two overlapping bodies can touch at all: one must be dynamic, one must move (read only: any thread). */
    private fun mayPair(i: Int, j: Int): Boolean {
        if (types[i] != BodyType.DYNAMIC && types[j] != BodyType.DYNAMIC) return false
        return moving(i) || moving(j)
    }

    /** Keeps a pair, after the sleep rules (one thread, in a fixed order: waking a body changes the next pairs). */
    private fun acceptPair(i: Int, j: Int) {
        val di = types[i] == BodyType.DYNAMIC; val dj = types[j] == BodyType.DYNAMIC
        // A sleeping body is woken only by something really coming at it: a moving kinematic body, or a
        // body above the sleep speed (its clock was reset). A slow neighbour resting against it does not
        // wake it — otherwise two neighbours in separate islands would wake each other forever.
        if ((di && sleeping[i]) || (dj && sleeping[j])) {
            val other = if (di && sleeping[i]) j else i
            val coming = when (types[other]) {
                BodyType.KINEMATIC -> moving(other)
                BodyType.DYNAMIC -> !sleeping[other] && sleepClock[other] == 0f
                BodyType.STATIC -> false
            }
            if (!coming) return
            if (di && sleeping[i]) wake(i)
            if (dj && sleeping[j]) wake(j)
        }
        pairsA.add(min(i, j)); pairsB.add(max(i, j))
    }

    private var cellMin = IntArray(0)      // per body: cell of its lowest corner (x, y, z)
    private val groups = IntList()         // cells holding two bodies or more: start and end in the sorted entries

    /** What one chunk of cells works with: its candidate pairs (in order) and the bounds of a cell's occupants. */
    private class CellScratch { val pairs = IntList(); var local = FloatArray(0); var localId = IntArray(0) }
    private val cellScratch = ArrayList<CellScratch>()
    private val cellsJob = Job { from, to ->
        val sc = cellScratch[from / CELL_CHUNK]
        sc.pairs.clear()
        for (g in from until to) pairsInCell(groups[2 * g], groups[2 * g + 1], sc)
    }

    private fun findPairs() {
        pairsA.clear(); pairsB.clear(); large.clear()
        if (isLarge.size < count) { isLarge = BooleanArray(capacity); cellMin = IntArray(3 * capacity) }
        // Cell size: twice the typical moving body, so most bodies touch one to eight cells.
        var sum = 0f; var n = 0
        for (i in 0 until count) {
            if (types[i] == BodyType.STATIC) continue
            val o = 6 * i
            sum += max(box[o + 3] - box[o], max(box[o + 4] - box[o + 1], box[o + 5] - box[o + 2])); n++
        }
        cellSize = if (n == 0) 2f else (2f * sum / n).coerceIn(.25f, 16f)
        var entries = 0
        for (i in 0 until count) {
            if (removed[i]) { isLarge[i] = false; continue }
            val o = 6 * i
            val x0 = cellOf(box[o]); val y0 = cellOf(box[o + 1]); val z0 = cellOf(box[o + 2])
            val x1 = cellOf(box[o + 3]); val y1 = cellOf(box[o + 4]); val z1 = cellOf(box[o + 5])
            cellMin[3 * i] = x0; cellMin[3 * i + 1] = y0; cellMin[3 * i + 2] = z0
            val span = (x1 - x0 + 1).toLong() * (y1 - y0 + 1) * (z1 - z0 + 1)
            isLarge[i] = span > 27
            if (isLarge[i]) { large.add(i); continue }
            if (cells.size < entries + span) cells = cells.copyOf(max(cells.size * 2, entries + span.toInt() + 1024))
            for (x in x0..x1) for (y in y0..y1) for (z in z0..z1)
                cells[entries++] = (x.toLong() shl 50) or (y.toLong() shl 37) or (z.toLong() shl 24) or i.toLong()
        }
        cells.sort(0, entries)
        groups.clear()
        var start = 0
        while (start < entries) {
            val cell = cells[start] ushr 24
            var end = start + 1
            while (end < entries && (cells[end] ushr 24) == cell) end++
            if (end - start > 1) { groups.add(start); groups.add(end) }
            start = end
        }
        // Candidates cell by cell (any thread), then kept in cell order on this one.
        val ng = groups.size / 2
        val chunks = (ng + CELL_CHUNK - 1) / CELL_CHUNK
        while (cellScratch.size < chunks) cellScratch.add(CellScratch())
        forChunks(ng, CELL_CHUNK, entries >= PARALLEL_BODIES, cellsJob)
        for (c in 0 until chunks) {
            val l = cellScratch[c].pairs
            var k = 0
            while (k < l.size) { acceptPair(l[k], l[k + 1]); k += 2 }
        }
        for (k in 0 until large.size) {
            val i = large[k]
            for (j in 0 until count) if (j != i && !removed[j] && !(isLarge[j] && j < i)) considerPair(i, j)
        }
    }

    /** The pairs of one cell: each pair is reported only from the cell holding the lowest corner of the two bounds' overlap. */
    private fun pairsInCell(start: Int, end: Int, sc: CellScratch) {
        val m = end - start
        val cell = cells[start] ushr 24
        val cx = (cell ushr 26).toInt(); val cy = ((cell ushr 13) and 0x1fff).toInt(); val cz = (cell and 0x1fff).toInt()
        // Copy the occupants' bounds and lowest cells side by side: the pair tests then stay in cache.
        if (sc.localId.size < m) { sc.local = FloatArray(9 * m * 2); sc.localId = IntArray(m * 2) }
        val local = sc.local; val localId = sc.localId
        for (p in 0 until m) {
            val i = (cells[start + p] and 0xffffff).toInt(); val o = 6 * i; val l = 9 * p
            localId[p] = i
            local[l] = box[o]; local[l + 1] = box[o + 1]; local[l + 2] = box[o + 2]
            local[l + 3] = box[o + 3]; local[l + 4] = box[o + 4]; local[l + 5] = box[o + 5]
            local[l + 6] = cellMin[3 * i].toFloat(); local[l + 7] = cellMin[3 * i + 1].toFloat(); local[l + 8] = cellMin[3 * i + 2].toFloat()
        }
        for (p in 0 until m) {
            val lp = 9 * p
            for (q in p + 1 until m) {
                val lq = 9 * q
                if (local[lq] > local[lp + 3] || local[lq + 3] < local[lp] || local[lq + 1] > local[lp + 4] ||
                    local[lq + 4] < local[lp + 1] || local[lq + 2] > local[lp + 5] || local[lq + 5] < local[lp + 2]) continue
                if (max(local[lp + 6], local[lq + 6]).toInt() != cx || max(local[lp + 7], local[lq + 7]).toInt() != cy ||
                    max(local[lp + 8], local[lq + 8]).toInt() != cz) continue
                val i = localId[p]; val j = localId[q]
                if (mayPair(i, j)) { sc.pairs.add(i); sc.pairs.add(j) }
            }
        }
    }

    /** One chunk of the narrow phase: its own contact points, merged in chunk order, and its own scratch. */
    private class NarrowScratch { val set = ContactSet(); val points = ContactPoints(); val found = IntList(); val stack = IntList() }
    private val narrow = ArrayList<NarrowScratch>()
    private var collideDt = 0f
    private var collideMesh = 0
    private val pairsJob = Job { from, to ->
        val n = narrow[from / PAIR_CHUNK]; n.set.clear()
        for (k in from until to) collidePair(k, collideDt, n)
    }
    private val meshJob = Job { from, to ->
        val n = narrow[from / BODY_CHUNK]; n.set.clear()
        for (b in from until to) collideMesh(collideMesh, b, collideDt, n)
    }

    private fun collide(dt: Float) {
        points.clear()
        collideDt = dt
        val np = pairsA.size
        val pairChunks = (np + PAIR_CHUNK - 1) / PAIR_CHUNK
        while (narrow.size < max(pairChunks, (count + BODY_CHUNK - 1) / BODY_CHUNK)) narrow.add(NarrowScratch())
        forChunks(np, PAIR_CHUNK, np >= PARALLEL_PAIRS, pairsJob)
        for (c in 0 until pairChunks) points.append(narrow[c].set)
        for (m in meshes.indices) {
            collideMesh = m
            forChunks(count, BODY_CHUNK, count >= PARALLEL_PAIRS, meshJob)
            for (c in 0 until (count + BODY_CHUNK - 1) / BODY_CHUNK) points.append(narrow[c].set)
        }
    }

    private fun collidePair(k: Int, dt: Float, n: NarrowScratch) {
        val i = pairsA[k]; val j = pairsB[k]
        val margin = min(1f, speculativeDistance + (speed(i) + speed(j)) * dt)
        val scratch = n.points
        scratch.clear()
        collide(shapes[i]!!, poses[i]!!, shapes[j]!!, poses[j]!!, margin, scratch)
        // A kinematic body sets the bounce of what it touches: a character is soft (0), a paddle may be bouncy.
        val e = when {
            types[i] == BodyType.KINEMATIC -> restitution[i]
            types[j] == BodyType.KINEMATIC -> restitution[j]
            else -> max(restitution[i], restitution[j])
        }
        if (scratch.count > 0) n.set.addAll(scratch, i, j, (i.toLong() shl 32) or j.toLong(),
            sqrt(friction[i] * friction[j]), e, poses[j]!!, previous)
    }

    private fun collideMesh(m: Int, b: Int, dt: Float, n: NarrowScratch) {
        if (types[b] != BodyType.DYNAMIC || sleeping[b]) return
        val mesh = meshes[m]
        val o = 6 * b
        val found = n.found
        found.clear()
        mesh.query(box[o], box[o + 1], box[o + 2], box[o + 3], box[o + 4], box[o + 5], found, n.stack)
        if (found.size == 0) return
        val margin = min(1f, speculativeDistance + speed(b) * dt)
        val scratch = n.points
        for (f in 0 until found.size) {
            val t = found[f]
            val tri = mesh.tris[t]
            scratch.clear()
            collideTriangle(tri, shapes[b]!!, poses[b]!!, margin, scratch)
            val surface = mesh.palette[mesh.material[t]]
            if (scratch.count > 0) n.set.addAll(scratch, -1, b,
                Long.MIN_VALUE or (m.toLong() shl 55) or (b.toLong() shl 24) or t.toLong(),
                sqrt(surface.friction * friction[b]), restitution[b] * surface.bounce, poses[b]!!, previous,
                surface.rollingResistance)
        }
    }

    // ------------------------------------------------------------------ solver

    /**
     * In a contact where a body rests on a much lighter one, the lighter is treated as at least
     * 1/[supportRatio] of the heavier's mass: the heavy body is then stopped as on firm ground instead of
     * sinking (soft contacts are springs scaled by the pair's mass), and the light one is not crushed
     * into the floor. The load this hides from the light body is given back to its own friction.
     */
    var supportRatio = 2f
    private var hiddenN = FloatArray(0)            // per body: normal load hidden by the scaling
    private var hiddenT = FloatArray(0)            // per body: friction impulse hidden by the scaling (x, y, z)
    private var hiddenM = FloatArray(0)            // per body: mass resting on it through scaled contacts
    private var slide = FloatArray(0)              // per body: velocity change when its support gives way (x, y, z)

    /** World inverse inertia quadratic form vᵀ I⁻¹ v of body i (0 for a body without mass). */
    private fun invInertiaQuad(i: Int, x: Float, y: Float, z: Float): Float {
        val s = i * S
        if (st[s + IM] == 0f) return 0f
        val k = s + I0
        return x * (st[k] * x + st[k + 1] * y + st[k + 2] * z) + y * (st[k + 1] * x + st[k + 3] * y + st[k + 4] * z) +
            z * (st[k + 2] * x + st[k + 4] * y + st[k + 5] * z)
    }

    /** Effective mass along (dx, dy, dz) at levers rA (on a) and rB (on b), inverse masses scaled by sa, sb. */
    private fun effectiveMass(a: Int, b: Int, rax: Float, ray: Float, raz: Float, rbx: Float, rby: Float, rbz: Float,
                              dx: Float, dy: Float, dz: Float, sa: Float = 1f, sb: Float = 1f): Float {
        var k = sb * (st[b * S + IM] + invInertiaQuad(b, rby * dz - rbz * dy, rbz * dx - rbx * dz, rbx * dy - rby * dx))
        if (a >= 0) k += sa * (st[a * S + IM] + invInertiaQuad(a, ray * dz - raz * dy, raz * dx - rax * dz, rax * dy - ray * dx))
        return if (k > 0f) 1f / k else 0f
    }

    private var prepH = 0f; private var prepHertz = 0f; private var upX = 0f; private var upY = 0f; private var upZ = 0f
    private val pointsJob = Job { from, to -> for (k in from until to) preparePoint(k) }
    private val patchesJob = Job { from, to -> for (q in from until to) preparePatch(q) }

    private fun prepareContacts(h: Float) {
        prepH = h
        prepHertz = min(contactHertz, .25f / h)
        val g = gravity.length()
        upX = if (g > 0f) -gravity.x / g else 0f; upY = if (g > 0f) -gravity.y / g else 0f; upZ = if (g > 0f) -gravity.z / g else 0f
        forChunks(points.size, 256, points.size >= PARALLEL_POINTS, pointsJob)
        preparePatches()
    }

    private fun preparePoint(k: Int) {
        val h = prepH; val hertz = prepHertz; val upx = upX; val upy = upY; val upz = upZ
        val c = points; val d = c.d
        run {
            val a = c.a[k]; val b = c.b[k]; val o = k * C
            val nx = d[o + CNX]; val ny = d[o + CNX + 1]; val nz = d[o + CNX + 2]
            val x = c.x[k]; val y = c.y[k]; val z = c.z[k]
            val rax = if (a >= 0) x - px[a] else 0f; val ray = if (a >= 0) y - py[a] else 0f; val raz = if (a >= 0) z - pz[a] else 0f
            val rbx = x - px[b]; val rby = y - py[b]; val rbz = z - pz[b]
            d[o + CRA] = rax; d[o + CRA + 1] = ray; d[o + CRA + 2] = raz
            d[o + CRB] = rbx; d[o + CRB + 1] = rby; d[o + CRB + 2] = rbz
            d[o + CADJ] = c.sep[k] - ((rbx - rax) * nx + (rby - ray) * ny + (rbz - raz) * nz)
            val mA = if (a >= 0) st[a * S + IM] else 0f; val mB = st[b * S + IM]
            val static = mA == 0f || mB == 0f
            d[o + CSTATIC] = if (static) 1f else 0f
            // Support between two dynamic bodies of very different masses: scale the lighter (lower) one.
            var sa = 1f; var sb = 1f
            if (!static) {
                val up = nx * upx + ny * upy + nz * upz
                if (up >= .5f && mA > mB) sa = min(1f, supportRatio * mB / mA)          // b rests on a, a lighter
                else if (up <= -.5f && mB > mA) sb = min(1f, supportRatio * mA / mB)    // a rests on b, b lighter
            }
            d[o + CSA] = sa; d[o + CSB] = sb
            val m = effectiveMass(a, b, rax, ray, raz, rbx, rby, rbz, nx, ny, nz, sa, sb)
            d[o + CNM] = m
            // Softness of this contact (Box2D v3's soft step): twice as stiff against what does not move.
            val hz = if (static) 2f * hertz else hertz
            val omega = 2f * Math.PI.toFloat() * hz
            val a1 = 2f * contactDampingRatio + h * omega; val a2 = h * omega * a1; val a3 = 1f / (1f + a2)
            d[o + CBIAS] = omega / a1; d[o + CMS] = a2 * a3; d[o + CIS] = a3
            // Approach speed at the start of the step (for restitution).
            val sB = b * S
            var rvx = st[sB] + st[sB + 4] * rbz - st[sB + 5] * rby
            var rvy = st[sB + 1] + st[sB + 5] * rbx - st[sB + 3] * rbz
            var rvz = st[sB + 2] + st[sB + 3] * rby - st[sB + 4] * rbx
            if (a >= 0) {
                val sA = a * S
                rvx -= st[sA] + st[sA + 4] * raz - st[sA + 5] * ray
                rvy -= st[sA + 1] + st[sA + 5] * rax - st[sA + 3] * raz
                rvz -= st[sA + 2] + st[sA + 3] * ray - st[sA + 4] * rax
            }
            d[o + CREL] = rvx * nx + rvy * ny + rvz * nz
            d[o + CMAXN] = 0f
            // Résistance au roulement : couple borné par μr × rayon × force normale.
            // Ground surface (soft grass, sand) or the body itself, whichever resists rolling more.
            val rl = max(max(if (a >= 0) rolling[a] * rollRadius[a] else 0f, rolling[b] * rollRadius[b]),
                d[o + CRL] * rollRadius[b])
            d[o + CRL] = rl
            if (rl > 0f) {
                var tr = 0f
                if (mA > 0f) tr += st[a * S + I0] + st[a * S + I0 + 3] + st[a * S + I0 + 5]
                if (mB > 0f) tr += st[b * S + I0] + st[b * S + I0 + 3] + st[b * S + I0 + 5]
                d[o + CRM] = if (tr > 0f) 3f / tr else 0f
            }
        }
    }

    /**
     * Groups the points into friction patches: consecutive points of the same pair whose normals agree.
     * Per-point friction, solved one corner after the other, left a lopsided net force on a sliding
     * box — it turned and drifted off the fall line; at the centre of the patch it cannot.
     */
    private fun preparePatches() {
        val c = points; val d = c.d; val ps = patches
        ps.clear(); scaled.clear()
        var k = 0; var ordinal = 0
        while (k < c.size) {
            val a = c.a[k]; val b = c.b[k]; val o0 = k * C
            var e = k + 1
            while (e < c.size && c.a[e] == a && c.b[e] == b &&
                d[e * C + CNX] * d[o0 + CNX] + d[e * C + CNX + 1] * d[o0 + CNX + 1] + d[e * C + CNX + 2] * d[o0 + CNX + 2] > .9f) e++
            // Key for warm starting: the pair (triangle bits cleared for meshes) and the patch's rank in it.
            ordinal = if (k > 0 && c.a[k - 1] == a && c.b[k - 1] == b) ordinal + 1 else 0
            val key = if (a < 0) (c.key[k] and 0xFFFFFFL.inv()) or ordinal.toLong() else if (ordinal == 0) c.key[k] else -1L
            val q = ps.add()
            ps.start[q] = k; ps.end[q] = e; ps.a[q] = a; ps.b[q] = b; ps.key[q] = key
            if (d[o0 + CSA] != 1f || d[o0 + CSB] != 1f) scaled.add(q)
            k = e
        }
        forChunks(ps.size, 256, ps.size >= PARALLEL_POINTS, patchesJob)
        colour()
    }

    private fun preparePatch(q: Int) {
        val c = points; val d = c.d; val ps = patches; val pd = ps.d
        val k = ps.start[q]; val e = ps.end[q]; val a = ps.a[q]; val b = ps.b[q]; val key = ps.key[q]; val o = q * P
        run {
            var nx = 0f; var ny = 0f; var nz = 0f; var cx = 0f; var cy = 0f; var cz = 0f; var mu = 0f
            for (j in k until e) {
                nx += d[j * C + CNX]; ny += d[j * C + CNX + 1]; nz += d[j * C + CNX + 2]
                cx += c.x[j]; cy += c.y[j]; cz += c.z[j]; mu += d[j * C + CFR]
            }
            val inv = 1f / (e - k); cx *= inv; cy *= inv; cz *= inv; mu *= inv
            val nl = 1f / sqrt(nx * nx + ny * ny + nz * nz); nx *= nl; ny *= nl; nz *= nl
            for (j in k until e) {
                // Distance of each point from the centre, in the contact plane: the lever of the twist.
                val dx = c.x[j] - cx; val dy = c.y[j] - cy; val dz = c.z[j] - cz
                val dn = dx * nx + dy * ny + dz * nz
                d[j * C + CDIST] = sqrt(max(0f, dx * dx + dy * dy + dz * dz - dn * dn))
            }
            val rax = if (a >= 0) cx - px[a] else 0f; val ray = if (a >= 0) cy - py[a] else 0f; val raz = if (a >= 0) cz - pz[a] else 0f
            val rbx = cx - px[b]; val rby = cy - py[b]; val rbz = cz - pz[b]
            pd[o + PN] = nx; pd[o + PN + 1] = ny; pd[o + PN + 2] = nz
            pd[o + PRA] = rax; pd[o + PRA + 1] = ray; pd[o + PRA + 2] = raz
            pd[o + PRB] = rbx; pd[o + PRB + 1] = rby; pd[o + PRB + 2] = rbz
            pd[o + PFR] = mu
            // First direction along the sliding velocity at the centre: sliding friction is then exactly μ·N.
            val sB = b * S
            var rvx = st[sB] + st[sB + 4] * rbz - st[sB + 5] * rby
            var rvy = st[sB + 1] + st[sB + 5] * rbx - st[sB + 3] * rbz
            var rvz = st[sB + 2] + st[sB + 3] * rby - st[sB + 4] * rbx
            if (a >= 0) {
                val sA = a * S
                rvx -= st[sA] + st[sA + 4] * raz - st[sA + 5] * ray
                rvy -= st[sA + 1] + st[sA + 5] * rax - st[sA + 3] * raz
                rvz -= st[sA + 2] + st[sA + 3] * ray - st[sA + 4] * rax
            }
            val vn = rvx * nx + rvy * ny + rvz * nz
            var t1x = rvx - vn * nx; var t1y = rvy - vn * ny; var t1z = rvz - vn * nz
            val tl = t1x * t1x + t1y * t1y + t1z * t1z
            if (tl > 1e-4f) { val f = 1f / sqrt(tl); t1x *= f; t1y *= f; t1z *= f }
            else if (abs(nx) >= .57735f) { val f = 1f / sqrt(ny * ny + nx * nx); t1x = ny * f; t1y = -nx * f; t1z = 0f }
            else { val f = 1f / sqrt(nz * nz + ny * ny); t1x = 0f; t1y = nz * f; t1z = -ny * f }
            val t2x = ny * t1z - nz * t1y; val t2y = nz * t1x - nx * t1z; val t2z = nx * t1y - ny * t1x
            pd[o + PT1] = t1x; pd[o + PT1 + 1] = t1y; pd[o + PT1 + 2] = t1z
            pd[o + PT2] = t2x; pd[o + PT2 + 1] = t2y; pd[o + PT2 + 2] = t2z
            // Same support scaling as the normal: the friction between a heavy body and a light support
            // would otherwise take dozens of substeps to build up, and the heavy body would slide off.
            val sa = d[k * C + CSA]; val sb = d[k * C + CSB]
            pd[o + PM1] = effectiveMass(a, b, rax, ray, raz, rbx, rby, rbz, t1x, t1y, t1z, sa, sb)
            pd[o + PM2] = effectiveMass(a, b, rax, ray, raz, rbx, rby, rbz, t2x, t2y, t2z, sa, sb)
            val kt = sb * invInertiaQuad(b, nx, ny, nz) + if (a >= 0) sa * invInertiaQuad(a, nx, ny, nz) else 0f
            pd[o + PMT] = if (kt > 0f) 1f / kt else 0f
            // Warm start: last step's friction of the same patch, as a world vector projected on the new basis.
            if (key != -1L) {
                val j = previousPatches.byKey.get(key)
                if (j >= 0) {
                    val qd = previousPatches.d; val p = j * P
                    val fx = qd[p + PT1] * qd[p + PL1] + qd[p + PT2] * qd[p + PL2]
                    val fy = qd[p + PT1 + 1] * qd[p + PL1] + qd[p + PT2 + 1] * qd[p + PL2]
                    val fz = qd[p + PT1 + 2] * qd[p + PL1] + qd[p + PT2 + 2] * qd[p + PL2]
                    pd[o + PL1] = fx * t1x + fy * t1y + fz * t1z
                    pd[o + PL2] = fx * t2x + fy * t2y + fz * t2z
                    if (qd[p + PN] * nx + qd[p + PN + 1] * ny + qd[p + PN + 2] * nz > .9f) pd[o + PLT] = qd[p + PLT]
                }
            }
        }
    }

    // ------------------------------------------------------------------ graph colouring

    // Patches of the same colour share no moving body: they can be solved at the same time, and the
    // order (colour after colour) depends only on the contacts, never on the threads. Small scenes keep
    // the natural order. Patches that find no free colour (a body with dozens of contacts) come last, alone.
    private var bodyColours = LongArray(0)
    private var colourOf = IntArray(0)
    private var ordered = IntArray(0)
    private val colourStart = IntArray(MAX_COLOURS + 2)
    private var colours = 0                          // 0: natural order, no colouring

    private fun colour() {
        val ps = patches; val n = ps.size
        if (n < PARALLEL_POINTS) { colours = 0; return }
        if (bodyColours.size < count) bodyColours = LongArray(capacity) else bodyColours.fill(0L, 0, count)
        if (colourOf.size < n) { colourOf = IntArray(n * 2); ordered = IntArray(n * 2) }
        colourStart.fill(0)
        for (q in 0 until n) {
            val a = ps.a[q]; val b = ps.b[q]
            val da = a >= 0 && st[a * S + IM] > 0f; val db = st[b * S + IM] > 0f
            val used = (if (da) bodyColours[a] else 0L) or (if (db) bodyColours[b] else 0L)
            var c = used.inv().countTrailingZeroBits()
            if (c >= MAX_COLOURS) c = MAX_COLOURS
            else { val bit = 1L shl c; if (da) bodyColours[a] = bodyColours[a] or bit; if (db) bodyColours[b] = bodyColours[b] or bit }
            colourOf[q] = c; colourStart[c + 1]++
        }
        for (c in 1..MAX_COLOURS + 1) colourStart[c] += colourStart[c - 1]
        val fill = colourStart.copyOf()
        for (q in 0 until n) ordered[fill[colourOf[q]]++] = q
        colours = MAX_COLOURS + 1
    }

    /** Runs [job] over every patch: colour after colour on the worker threads, or in order on this one. */
    private fun forPatches(job: Job) {
        val n = patches.size
        if (colours == 0) { Workers.serialChunks(n, n.coerceAtLeast(1), job); return }
        for (c in 0 until MAX_COLOURS) {
            val from = colourStart[c]; val to = colourStart[c + 1]
            if (from == to) continue
            colourBase = from
            forChunks(to - from, PATCH_CHUNK, true, colourJob)
        }
        // The overflow, alone and in order.
        val from = colourStart[MAX_COLOURS]; val to = colourStart[MAX_COLOURS + 1]
        if (to > from) { colourBase = from; colourJob.run(0, to - from) }
    }
    private var colourBase = 0
    private var patchJob: Job = Job { _, _ -> }
    private val colourJob = Job { from, to -> for (i in from until to) { val q = ordered[colourBase + i]; patchJob.run(q, q + 1) } }

    private var integrateH = 0f
    private val velocitiesJob = Job { from, to -> integrateVelocities(from, to, integrateH) }
    private val positionsJob = Job { from, to -> integratePositions(from, to, integrateH) }
    private fun integrateVelocities(h: Float) { integrateH = h; forChunks(count, 1024, count >= PARALLEL_BODIES, velocitiesJob) }
    private fun integratePositions(h: Float) { integrateH = h; forChunks(count, 1024, count >= PARALLEL_BODIES, positionsJob) }

    private fun integrateVelocities(from: Int, to: Int, h: Float) {
        val gx = gravity.x; val gy = gravity.y; val gz = gravity.z
        for (i in from until to) {
            if (types[i] != BodyType.DYNAMIC || sleeping[i]) continue
            val o = i * S
            val m = st[o + IM]; val gs = gravityScale[i]
            val ld = 1f / (1f + h * linearDamping[i]); val ad = 1f / (1f + h * angularDamping[i])
            st[o] = (st[o] + h * (gx * gs + fx[i] * m)) * ld
            st[o + 1] = (st[o + 1] + h * (gy * gs + fy[i] * m)) * ld
            st[o + 2] = (st[o + 2] + h * (gz * gs + fz[i] * m)) * ld
            st[o + 3] *= ad; st[o + 4] *= ad; st[o + 5] *= ad
            if (gyroscopic) gyroscopic(i, h)
        }
    }

    /** Includes the gyroscopic torque −ω × (Iω), so a body spinning alone keeps its angular momentum. */
    var gyroscopic = true

    /**
     * Implicit gyroscopic step (E. Catto, GDC 2015): one Newton iteration in body space,
     * ω' = ω − J⁻¹ f with f = h ω × (Iω) and J = I + h ([ω]× I − [Iω]×). Stable at any spin rate.
     */
    private fun gyroscopic(i: Int, h: Float) {
        val a = ilx[i]; val b = ily[i]; val c = ilz[i]
        if (a == b && b == c) return                                   // sphere-like: no gyroscopic torque
        val o = i * S
        val x = qx[i]; val y = qy[i]; val z = qz[i]; val w = qw[i]
        // ω in body space: Rᵀ ω.
        val wx0 = st[o + 3]; val wy0 = st[o + 4]; val wz0 = st[o + 5]
        val tx = 2f * (-y * wz0 + z * wy0); val ty = 2f * (-z * wx0 + x * wz0); val tz = 2f * (-x * wy0 + y * wx0)
        val bx = wx0 + w * tx + (-y * tz + z * ty); val by = wy0 + w * ty + (-z * tx + x * tz); val bz = wz0 + w * tz + (-x * ty + y * tx)
        val ix = 1f / a; val iy = 1f / b; val iz = 1f / c
        val lx = ix * bx; val ly = iy * by; val lz = iz * bz                // I ω
        val fx = h * (by * lz - bz * ly); val fy = h * (bz * lx - bx * lz); val fz = h * (bx * ly - by * lx)
        // J = I + h (skew(ω) I − skew(Iω)), skew(v) u = v × u.
        val j00 = ix; val j01 = h * (-bz * iy + lz); val j02 = h * (by * iz - ly)
        val j10 = h * (bz * ix - lz); val j11 = iy; val j12 = h * (-bx * iz + lx)
        val j20 = h * (-by * ix + ly); val j21 = h * (bx * iy - lx); val j22 = iz
        val det = j00 * (j11 * j22 - j12 * j21) - j01 * (j10 * j22 - j12 * j20) + j02 * (j10 * j21 - j11 * j20)
        if (abs(det) < 1e-12f) return
        val inv = 1f / det
        val dx = inv * ((j11 * j22 - j12 * j21) * fx + (j02 * j21 - j01 * j22) * fy + (j01 * j12 - j02 * j11) * fz)
        val dy = inv * ((j12 * j20 - j10 * j22) * fx + (j00 * j22 - j02 * j20) * fy + (j02 * j10 - j00 * j12) * fz)
        val dz = inv * ((j10 * j21 - j11 * j20) * fx + (j01 * j20 - j00 * j21) * fy + (j00 * j11 - j01 * j10) * fz)
        val nx = bx - dx; val ny = by - dy; val nz = bz - dz
        // Back to world space: R ω.
        val ux = 2f * (y * nz - z * ny); val uy = 2f * (z * nx - x * nz); val uz = 2f * (x * ny - y * nx)
        st[o + 3] = nx + w * ux + (y * uz - z * uy)
        st[o + 4] = ny + w * uy + (z * ux - x * uz)
        st[o + 5] = nz + w * uz + (x * uy - y * ux)
    }

    private fun integratePositions(from: Int, to: Int, h: Float) {
        for (i in from until to) {
            if (types[i] == BodyType.STATIC || (types[i] == BodyType.DYNAMIC && sleeping[i])) continue
            val o = i * S
            val vx = st[o]; val vy = st[o + 1]; val vz = st[o + 2]
            px[i] += h * vx; py[i] += h * vy; pz[i] += h * vz
            st[o + DPX] += h * vx; st[o + DPX + 1] += h * vy; st[o + DPX + 2] += h * vz
            val ax = st[o + 3]; val ay = st[o + 4]; val az = st[o + 5]
            if (ax == 0f && ay == 0f && az == 0f) continue
            val x = qx[i]; val y = qy[i]; val z = qz[i]; val w = qw[i]
            var nx = x + .5f * h * (w * ax + ay * z - az * y)
            var ny = y + .5f * h * (w * ay + az * x - ax * z)
            var nz = z + .5f * h * (w * az + ax * y - ay * x)
            var nw = w - .5f * h * (ax * x + ay * y + az * z)
            val l = 1f / sqrt(nx * nx + ny * ny + nz * nz + nw * nw)
            nx *= l; ny *= l; nz *= l; nw *= l
            qx[i] = nx; qy[i] = ny; qz[i] = nz; qw[i] = nw
            // Rotation since the step began: q · q0⁻¹.
            val cx = -q0[4 * i]; val cy = -q0[4 * i + 1]; val cz = -q0[4 * i + 2]; val cw = q0[4 * i + 3]
            st[o + DQX] = nw * cx + nx * cw + ny * cz - nz * cy
            st[o + DQX + 1] = nw * cy - nx * cz + ny * cw + nz * cx
            st[o + DQX + 2] = nw * cz + nx * cy - ny * cx + nz * cw
            st[o + DQW] = nw * cw - nx * cx - ny * cy - nz * cz
        }
    }

    // Hot loops below work on scalars only: no vector is allocated per contact and iteration.

    /** Applies the normal impulse (px, py, pz) of the contact at data offset o: −P on a, +P on b (masses as scaled there). */
    private fun push(a: Int, b: Int, o: Int, px: Float, py: Float, pz: Float) {
        val d = points.d
        if (a >= 0) {
            val s = a * S
            val m = st[s + IM]
            if (m > 0f) {
                val f = d[o + CSA]
                val qx = px * f; val qy = py * f; val qz = pz * f
                st[s] -= qx * m; st[s + 1] -= qy * m; st[s + 2] -= qz * m
                val rx = d[o + CRA]; val ry = d[o + CRA + 1]; val rz = d[o + CRA + 2]
                val cx = ry * qz - rz * qy; val cy = rz * qx - rx * qz; val cz = rx * qy - ry * qx
                val i = s + I0
                st[s + 3] -= st[i] * cx + st[i + 1] * cy + st[i + 2] * cz
                st[s + 4] -= st[i + 1] * cx + st[i + 3] * cy + st[i + 4] * cz
                st[s + 5] -= st[i + 2] * cx + st[i + 4] * cy + st[i + 5] * cz
            }
        }
        val s = b * S
        val m = st[s + IM]
        if (m > 0f) {
            val f = d[o + CSB]
            val qx = px * f; val qy = py * f; val qz = pz * f
            st[s] += qx * m; st[s + 1] += qy * m; st[s + 2] += qz * m
            val rx = d[o + CRB]; val ry = d[o + CRB + 1]; val rz = d[o + CRB + 2]
            val cx = ry * qz - rz * qy; val cy = rz * qx - rx * qz; val cz = rx * qy - ry * qx
            val i = s + I0
            st[s + 3] += st[i] * cx + st[i + 1] * cy + st[i + 2] * cz
            st[s + 4] += st[i + 1] * cx + st[i + 3] * cy + st[i + 4] * cz
            st[s + 5] += st[i + 2] * cx + st[i + 4] * cy + st[i + 5] * cz
        }
    }

    // Relative velocity at contact data offset o, written to (rvx, rvy, rvz).
    private var rvx = 0f; private var rvy = 0f; private var rvz = 0f
    private fun relative(a: Int, b: Int, o: Int) {
        val d = points.d; val s = b * S
        val bx = d[o + CRB]; val by = d[o + CRB + 1]; val bz = d[o + CRB + 2]
        rvx = st[s] + st[s + 4] * bz - st[s + 5] * by
        rvy = st[s + 1] + st[s + 5] * bx - st[s + 3] * bz
        rvz = st[s + 2] + st[s + 3] * by - st[s + 4] * bx
        if (a >= 0) {
            val t = a * S
            val ax = d[o + CRA]; val ay = d[o + CRA + 1]; val az = d[o + CRA + 2]
            rvx -= st[t] + st[t + 4] * az - st[t + 5] * ay
            rvy -= st[t + 1] + st[t + 5] * ax - st[t + 3] * az
            rvz -= st[t + 2] + st[t + 3] * ay - st[t + 4] * ax
        }
    }

    // The solver works patch by patch: the two bodies' velocities are read once into locals, every
    // point, the friction, the twist and the rolling resistance of the patch are solved on them, then
    // they are written back. One pass over memory per patch instead of one per point and per row.

    private val warmJob = Job { from, to -> for (q in from until to) warmStartPatch(q) }
    private fun warmStart() { patchJob = warmJob; forPatches(warmJob) }

    private fun warmStartPatch(q: Int) {
        val d = points.d; val ps = patches; val pd = ps.d
        val a = ps.a[q]; val b = ps.b[q]
        val sB = b * S; val mB = st[sB + IM]
        var vbx = st[sB]; var vby = st[sB + 1]; var vbz = st[sB + 2]; var wbx = st[sB + 3]; var wby = st[sB + 4]; var wbz = st[sB + 5]
        val ib0 = st[sB + I0]; val ib1 = st[sB + I0 + 1]; val ib2 = st[sB + I0 + 2]; val ib3 = st[sB + I0 + 3]; val ib4 = st[sB + I0 + 4]; val ib5 = st[sB + I0 + 5]
        val sA = if (a >= 0) a * S else 0
        val mA = if (a >= 0) st[sA + IM] else 0f
        var vax = 0f; var vay = 0f; var vaz = 0f; var wax = 0f; var way = 0f; var waz = 0f
        var ia0 = 0f; var ia1 = 0f; var ia2 = 0f; var ia3 = 0f; var ia4 = 0f; var ia5 = 0f
        if (mA > 0f) {
            vax = st[sA]; vay = st[sA + 1]; vaz = st[sA + 2]; wax = st[sA + 3]; way = st[sA + 4]; waz = st[sA + 5]
            ia0 = st[sA + I0]; ia1 = st[sA + I0 + 1]; ia2 = st[sA + I0 + 2]; ia3 = st[sA + I0 + 3]; ia4 = st[sA + I0 + 4]; ia5 = st[sA + I0 + 5]
        }
        val end = ps.end[q]
        for (j in ps.start[q] until end) {
            val o = j * C
            val n = d[o + CLN]
            val px = d[o + CNX] * n; val py = d[o + CNX + 1] * n; val pz = d[o + CNX + 2] * n
            if (mA > 0f) {
                val f = d[o + CSA]; val qx = px * f; val qy = py * f; val qz = pz * f
                vax -= qx * mA; vay -= qy * mA; vaz -= qz * mA
                val rx = d[o + CRA]; val ry = d[o + CRA + 1]; val rz = d[o + CRA + 2]
                val cx = ry * qz - rz * qy; val cy = rz * qx - rx * qz; val cz = rx * qy - ry * qx
                wax -= ia0 * cx + ia1 * cy + ia2 * cz; way -= ia1 * cx + ia3 * cy + ia4 * cz; waz -= ia2 * cx + ia4 * cy + ia5 * cz
            }
            if (mB > 0f) {
                val f = d[o + CSB]; val qx = px * f; val qy = py * f; val qz = pz * f
                vbx += qx * mB; vby += qy * mB; vbz += qz * mB
                val rx = d[o + CRB]; val ry = d[o + CRB + 1]; val rz = d[o + CRB + 2]
                val cx = ry * qz - rz * qy; val cy = rz * qx - rx * qz; val cz = rx * qy - ry * qx
                wbx += ib0 * cx + ib1 * cy + ib2 * cz; wby += ib1 * cx + ib3 * cy + ib4 * cz; wbz += ib2 * cx + ib4 * cy + ib5 * cz
            }
            if (d[o + CRL] > 0f) {
                val lx = d[o + CLR]; val ly = d[o + CLR + 1]; val lz = d[o + CLR + 2]
                if (mA > 0f) { wax -= ia0 * lx + ia1 * ly + ia2 * lz; way -= ia1 * lx + ia3 * ly + ia4 * lz; waz -= ia2 * lx + ia4 * ly + ia5 * lz }
                if (mB > 0f) { wbx += ib0 * lx + ib1 * ly + ib2 * lz; wby += ib1 * lx + ib3 * ly + ib4 * lz; wbz += ib2 * lx + ib4 * ly + ib5 * lz }
            }
        }
        val o = q * P; val l1 = pd[o + PL1]; val l2 = pd[o + PL2]; val lt = pd[o + PLT]
        val f = ps.start[q] * C; val sa = d[f + CSA]; val sb = d[f + CSB]
        val px = pd[o + PT1] * l1 + pd[o + PT2] * l2; val py = pd[o + PT1 + 1] * l1 + pd[o + PT2 + 1] * l2; val pz = pd[o + PT1 + 2] * l1 + pd[o + PT2 + 2] * l2
        val tx = pd[o + PN] * lt; val ty = pd[o + PN + 1] * lt; val tz = pd[o + PN + 2] * lt
        if (mA > 0f) {
            val m = mA * sa
            vax -= px * m; vay -= py * m; vaz -= pz * m
            val rx = pd[o + PRA]; val ry = pd[o + PRA + 1]; val rz = pd[o + PRA + 2]
            val cx = (ry * pz - rz * py) * sa + tx * sa; val cy = (rz * px - rx * pz) * sa + ty * sa; val cz = (rx * py - ry * px) * sa + tz * sa
            wax -= ia0 * cx + ia1 * cy + ia2 * cz; way -= ia1 * cx + ia3 * cy + ia4 * cz; waz -= ia2 * cx + ia4 * cy + ia5 * cz
            st[sA] = vax; st[sA + 1] = vay; st[sA + 2] = vaz; st[sA + 3] = wax; st[sA + 4] = way; st[sA + 5] = waz
        }
        if (mB > 0f) {
            val m = mB * sb
            vbx += px * m; vby += py * m; vbz += pz * m
            val rx = pd[o + PRB]; val ry = pd[o + PRB + 1]; val rz = pd[o + PRB + 2]
            val cx = (ry * pz - rz * py) * sb + tx * sb; val cy = (rz * px - rx * pz) * sb + ty * sb; val cz = (rx * py - ry * px) * sb + tz * sb
            wbx += ib0 * cx + ib1 * cy + ib2 * cz; wby += ib1 * cx + ib3 * cy + ib4 * cz; wbz += ib2 * cx + ib4 * cy + ib5 * cz
            st[sB] = vbx; st[sB + 1] = vby; st[sB + 2] = vbz; st[sB + 3] = wbx; st[sB + 4] = wby; st[sB + 5] = wbz
        }
    }

    /** Patches whose two dynamic bodies are scaled for support (a heavy body on a light one), listed per step. */
    private val scaled = IntList()

    private var solveBias = false; private var solveInvH = 0f; private var solveHidden = false; private var solveG = 0f
    private val solveJob = Job { from, to -> for (q in from until to) solvePatch(q, solveBias, solveInvH, solveHidden, solveG) }

    private fun solve(h: Float, useBias: Boolean) {
        solveInvH = 1f / h; solveBias = useBias
        solveHidden = scaled.size > 0
        if (solveHidden) hideLoads()
        solveG = gravity.length()
        patchJob = solveJob; forPatches(solveJob)
        if (solveHidden) slideStacks()
    }

    /**
     * Loads hidden from a light body by the support scaling — what it would really carry, normal and
     * friction — from the impulses as they stand: given to what it rests on, whose friction then holds
     * both or gives way. Only bodies under a scaled patch are touched; [slideStacks] clears them.
     */
    private fun hideLoads() {
        if (hiddenN.size < count) { hiddenN = FloatArray(capacity); hiddenT = FloatArray(3 * capacity); hiddenM = FloatArray(capacity); slide = FloatArray(3 * capacity) }
        val d = points.d; val ps = patches; val pd = ps.d
        for (k in 0 until scaled.size) {
            val q = scaled[k]
            val f = ps.start[q] * C; val sa = d[f + CSA]; val sb = d[f + CSB]
            val o = q * P
            val lower = if (sa < 1f) ps.a[q] else ps.b[q]; val upper = if (sa < 1f) ps.b[q] else ps.a[q]
            val hide = 1f - min(sa, sb); val sign = if (sa < 1f) -1f else 1f
            var load = 0f
            for (j in ps.start[q] until ps.end[q]) load += d[j * C + CLN]
            hiddenN[lower] += hide * load
            hiddenM[lower] += 1f / st[upper * S + IM]
            val l1 = pd[o + PL1] * hide * sign; val l2 = pd[o + PL2] * hide * sign
            hiddenT[3 * lower] += pd[o + PT1] * l1 + pd[o + PT2] * l2
            hiddenT[3 * lower + 1] += pd[o + PT1 + 1] * l1 + pd[o + PT2 + 1] * l2
            hiddenT[3 * lower + 2] += pd[o + PT1 + 2] * l1 + pd[o + PT2 + 2] * l2
        }
    }

    /** What rests on a light body that slid with it moves as much; then the hidden loads are cleared. */
    private fun slideStacks() {
        val d = points.d; val ps = patches
        for (k in 0 until scaled.size) {
            val q = scaled[k]
            val f = ps.start[q] * C; val sa = d[f + CSA]
            val lower = if (sa < 1f) ps.a[q] else ps.b[q]; val upper = if (sa < 1f) ps.b[q] else ps.a[q]
            val su = upper * S
            st[su] += slide[3 * lower]; st[su + 1] += slide[3 * lower + 1]; st[su + 2] += slide[3 * lower + 2]
        }
        for (k in 0 until scaled.size) {
            val q = scaled[k]
            val f = ps.start[q] * C; val sa = d[f + CSA]
            val x = if (sa < 1f) ps.a[q] else ps.b[q]
            hiddenN[x] = 0f; hiddenM[x] = 0f
            for (j in 0..2) { hiddenT[3 * x + j] = 0f; slide[3 * x + j] = 0f }
        }
    }

    private fun solvePatch(q: Int, useBias: Boolean, invH: Float, anyHidden: Boolean, gl: Float) {
        val d = points.d; val ps = patches; val pd = ps.d
        val a = ps.a[q]; val b = ps.b[q]
        // Body b, then a (a static mesh, a = −1, is a body without mass that does not move).
        val sB = b * S; val mB = st[sB + IM]
        var vbx = st[sB]; var vby = st[sB + 1]; var vbz = st[sB + 2]; var wbx = st[sB + 3]; var wby = st[sB + 4]; var wbz = st[sB + 5]
        val ib0 = st[sB + I0]; val ib1 = st[sB + I0 + 1]; val ib2 = st[sB + I0 + 2]; val ib3 = st[sB + I0 + 3]; val ib4 = st[sB + I0 + 4]; val ib5 = st[sB + I0 + 5]
        val dbx = st[sB + DPX]; val dby = st[sB + DPX + 1]; val dbz = st[sB + DPX + 2]
        val rotB = !round[b]
        val qbx = st[sB + DQX]; val qby = st[sB + DQX + 1]; val qbz = st[sB + DQX + 2]; val qbw = st[sB + DQW]
        val sA = if (a >= 0) a * S else 0
        val mA = if (a >= 0) st[sA + IM] else 0f
        var vax = 0f; var vay = 0f; var vaz = 0f; var wax = 0f; var way = 0f; var waz = 0f
        var ia0 = 0f; var ia1 = 0f; var ia2 = 0f; var ia3 = 0f; var ia4 = 0f; var ia5 = 0f
        var dax = 0f; var day = 0f; var daz = 0f
        var qax = 0f; var qay = 0f; var qaz = 0f; var qaw = 1f
        var rotA = false
        if (a >= 0) {
            vax = st[sA]; vay = st[sA + 1]; vaz = st[sA + 2]; wax = st[sA + 3]; way = st[sA + 4]; waz = st[sA + 5]
            ia0 = st[sA + I0]; ia1 = st[sA + I0 + 1]; ia2 = st[sA + I0 + 2]; ia3 = st[sA + I0 + 3]; ia4 = st[sA + I0 + 4]; ia5 = st[sA + I0 + 5]
            dax = st[sA + DPX]; day = st[sA + DPX + 1]; daz = st[sA + DPX + 2]
            rotA = !round[a]
            qax = st[sA + DQX]; qay = st[sA + DQX + 1]; qaz = st[sA + DQX + 2]; qaw = st[sA + DQW]
        }
        val start = ps.start[q]; val end = ps.end[q]

        // Normal points: soft non-penetration, separation from the motion since the step began.
        for (j in start until end) {
            val o = j * C
            val nx = d[o + CNX]; val ny = d[o + CNX + 1]; val nz = d[o + CNX + 2]
            val rax = d[o + CRA]; val ray = d[o + CRA + 1]; val raz = d[o + CRA + 2]
            val rbx = d[o + CRB]; val rby = d[o + CRB + 1]; val rbz = d[o + CRB + 2]
            // Anchors turned by each body's rotation since the step began (a sphere keeps its anchor).
            var cbx = rbx; var cby = rby; var cbz = rbz
            if (rotB) {
                val tx = 2f * (qby * rbz - qbz * rby); val ty = 2f * (qbz * rbx - qbx * rbz); val tz = 2f * (qbx * rby - qby * rbx)
                cbx = rbx + qbw * tx + (qby * tz - qbz * ty); cby = rby + qbw * ty + (qbz * tx - qbx * tz); cbz = rbz + qbw * tz + (qbx * ty - qby * tx)
            }
            var cax = rax; var cay = ray; var caz = raz
            if (rotA) {
                val tx = 2f * (qay * raz - qaz * ray); val ty = 2f * (qaz * rax - qax * raz); val tz = 2f * (qax * ray - qay * rax)
                cax = rax + qaw * tx + (qay * tz - qaz * ty); cay = ray + qaw * ty + (qaz * tx - qax * tz); caz = raz + qaw * tz + (qax * ty - qay * tx)
            }
            val s = (dbx - dax + cbx - cax) * nx + (dby - day + cby - cay) * ny + (dbz - daz + cbz - caz) * nz + d[o + CADJ]
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (s > 0f) bias = s * invH
            else if (useBias) { bias = max(d[o + CBIAS] * s, -contactSpeed); massScale = d[o + CMS]; impulseScale = d[o + CIS] }
            val rvx = vbx + wby * rbz - wbz * rby - (vax + way * raz - waz * ray)
            val rvy = vby + wbz * rbx - wbx * rbz - (vay + waz * rax - wax * raz)
            val rvz = vbz + wbx * rby - wby * rbx - (vaz + wax * ray - way * rax)
            val vn = rvx * nx + rvy * ny + rvz * nz
            val old = d[o + CLN]
            val total = max(old - d[o + CNM] * massScale * (vn + bias) - impulseScale * old, 0f)
            val impulse = total - old
            d[o + CLN] = total
            if (impulse > d[o + CMAXN]) d[o + CMAXN] = impulse
            if (impulse == 0f) continue
            val px = nx * impulse; val py = ny * impulse; val pz = nz * impulse
            if (mA > 0f) {
                val f = d[o + CSA]; val qx = px * f; val qy = py * f; val qz = pz * f
                vax -= qx * mA; vay -= qy * mA; vaz -= qz * mA
                val cx = ray * qz - raz * qy; val cy = raz * qx - rax * qz; val cz = rax * qy - ray * qx
                wax -= ia0 * cx + ia1 * cy + ia2 * cz; way -= ia1 * cx + ia3 * cy + ia4 * cz; waz -= ia2 * cx + ia4 * cy + ia5 * cz
            }
            if (mB > 0f) {
                val f = d[o + CSB]; val qx = px * f; val qy = py * f; val qz = pz * f
                vbx += qx * mB; vby += qy * mB; vbz += qz * mB
                val cx = rby * qz - rbz * qy; val cy = rbz * qx - rbx * qz; val cz = rbx * qy - rby * qx
                wbx += ib0 * cx + ib1 * cy + ib2 * cz; wby += ib1 * cx + ib3 * cy + ib4 * cz; wbz += ib2 * cx + ib4 * cy + ib5 * cz
            }
        }

        // Friction at the patch centre: first along the sliding direction, then across (both within the
        // circle μ·N), then the twist about the normal (within μ·Σ Nᵢ·dᵢ).
        val o = q * P
        var load = 0f; var twistCap = 0f
        for (j in start until end) { val l = d[j * C + CLN]; load += l; twistCap += l * d[j * C + CDIST] }
        val f0 = start * C; val sa = d[f0 + CSA]; val sb = d[f0 + CSB]
        val nx = pd[o + PN]; val ny = pd[o + PN + 1]; val nz = pd[o + PN + 2]
        var reserved = 0f
        if (anyHidden && gl > 0f) {
            val up = -(nx * gravity.x + ny * gravity.y + nz * gravity.z) / gl
            val x = if (up >= .5f) b else if (up <= -.5f && a >= 0) a else -1
            if (x >= 0 && hiddenN[x] > 0f) {
                twistCap *= (load + hiddenN[x]) / max(load, 1e-12f); load += hiddenN[x]
                // The hidden friction, in this patch's plane, uses up part of its grip; beyond the grip,
                // the excess pushes the light body (once per substep): the whole stack then slides.
                var hx = hiddenT[3 * x]; var hy = hiddenT[3 * x + 1]; var hz = hiddenT[3 * x + 2]
                val hn = hx * nx + hy * ny + hz * nz; hx -= hn * nx; hy -= hn * ny; hz -= hn * nz
                val hl = sqrt(hx * hx + hy * hy + hz * hz)
                val grip = pd[o + PFR] * load
                if (hl > grip && useBias) {
                    // The same velocity change for the light body and what rests on it ([slideStacks]).
                    val e = (hl - grip) / hl / (1f / st[x * S + IM] + hiddenM[x])
                    val ex = hx * e; val ey = hy * e; val ez = hz * e
                    if (x == b) { vbx += ex; vby += ey; vbz += ez } else { vax += ex; vay += ey; vaz += ez }
                    slide[3 * x] += ex; slide[3 * x + 1] += ey; slide[3 * x + 2] += ez
                }
                reserved = min(hl, grip)
            }
        }
        val mu = pd[o + PFR]; val limit = max(0f, mu * load - reserved)
        val rax = pd[o + PRA]; val ray = pd[o + PRA + 1]; val raz = pd[o + PRA + 2]
        val rbx = pd[o + PRB]; val rby = pd[o + PRB + 1]; val rbz = pd[o + PRB + 2]
        val msa = mA * sa; val msb = mB * sb
        for (row in 0..1) {
            val t = o + if (row == 0) PT1 else PT2
            val tx = pd[t]; val ty = pd[t + 1]; val tz = pd[t + 2]
            val rvx = vbx + wby * rbz - wbz * rby - (vax + way * raz - waz * ray)
            val rvy = vby + wbz * rbx - wbx * rbz - (vay + waz * rax - wax * raz)
            val rvz = vbz + wbx * rby - wby * rbx - (vaz + wax * ray - way * rax)
            val li = o + if (row == 0) PL1 else PL2
            val bound = if (row == 0) limit else { val l1 = pd[o + PL1]; sqrt(max(0f, limit * limit - l1 * l1)) }
            val old = pd[li]
            val new = (old - pd[o + if (row == 0) PM1 else PM2] * (rvx * tx + rvy * ty + rvz * tz)).coerceIn(-bound, bound)
            pd[li] = new
            if (new == old) continue
            val e = new - old
            val px = tx * e; val py = ty * e; val pz = tz * e
            if (msa > 0f) {
                vax -= px * msa; vay -= py * msa; vaz -= pz * msa
                val cx = (ray * pz - raz * py) * sa; val cy = (raz * px - rax * pz) * sa; val cz = (rax * py - ray * px) * sa
                wax -= ia0 * cx + ia1 * cy + ia2 * cz; way -= ia1 * cx + ia3 * cy + ia4 * cz; waz -= ia2 * cx + ia4 * cy + ia5 * cz
            }
            if (msb > 0f) {
                vbx += px * msb; vby += py * msb; vbz += pz * msb
                val cx = (rby * pz - rbz * py) * sb; val cy = (rbz * px - rbx * pz) * sb; val cz = (rbx * py - rby * px) * sb
                wbx += ib0 * cx + ib1 * cy + ib2 * cz; wby += ib1 * cx + ib3 * cy + ib4 * cz; wbz += ib2 * cx + ib4 * cy + ib5 * cz
            }
        }
        run {
            val wn = (wbx - wax) * nx + (wby - way) * ny + (wbz - waz) * nz
            val tcap = mu * twistCap
            val old = pd[o + PLT]
            val new = (old - pd[o + PMT] * wn).coerceIn(-tcap, tcap)
            pd[o + PLT] = new
            if (new != old) {
                val e = new - old; val lx = nx * e; val ly = ny * e; val lz = nz * e
                if (mA > 0f) { wax -= (ia0 * lx + ia1 * ly + ia2 * lz) * sa; way -= (ia1 * lx + ia3 * ly + ia4 * lz) * sa; waz -= (ia2 * lx + ia4 * ly + ia5 * lz) * sa }
                if (mB > 0f) { wbx += (ib0 * lx + ib1 * ly + ib2 * lz) * sb; wby += (ib1 * lx + ib3 * ly + ib4 * lz) * sb; wbz += (ib2 * lx + ib4 * ly + ib5 * lz) * sb }
            }
        }

        // Rolling resistance, per point: a torque against the relative spin, bounded by μr × radius × N.
        for (j in start until end) {
            val p = j * C
            val rl = d[p + CRL]
            if (rl <= 0f) continue
            val m = d[p + CRM]; val cap = rl * d[p + CLN]
            val ox = d[p + CLR]; val oy = d[p + CLR + 1]; val oz = d[p + CLR + 2]
            var lx = ox - m * (wbx - wax); var ly = oy - m * (wby - way); var lz = oz - m * (wbz - waz)
            val l2 = lx * lx + ly * ly + lz * lz
            if (l2 > cap * cap) { val f = cap / sqrt(l2); lx *= f; ly *= f; lz *= f }
            d[p + CLR] = lx; d[p + CLR + 1] = ly; d[p + CLR + 2] = lz
            val ex = lx - ox; val ey = ly - oy; val ez = lz - oz
            if (mA > 0f) { wax -= ia0 * ex + ia1 * ey + ia2 * ez; way -= ia1 * ex + ia3 * ey + ia4 * ez; waz -= ia2 * ex + ia4 * ey + ia5 * ez }
            if (mB > 0f) { wbx += ib0 * ex + ib1 * ey + ib2 * ez; wby += ib1 * ex + ib3 * ey + ib4 * ez; wbz += ib2 * ex + ib4 * ey + ib5 * ez }
        }

        if (mA > 0f) {
            st[sA] = vax; st[sA + 1] = vay; st[sA + 2] = vaz; st[sA + 3] = wax; st[sA + 4] = way; st[sA + 5] = waz
        }
        if (mB > 0f) {
            st[sB] = vbx; st[sB + 1] = vby; st[sB + 2] = vbz; st[sB + 3] = wbx; st[sB + 4] = wby; st[sB + 5] = wbz
        }
    }

    private fun restitute() {
        val c = points; val d = c.d
        for (k in 0 until c.size) {
            val o = k * C
            val e = d[o + CREST]
            if (e == 0f || d[o + CREL] > -restitutionThreshold || d[o + CMAXN] == 0f) continue
            val a = c.a[k]; val b = c.b[k]
            relative(a, b, o)
            val nx = d[o + CNX]; val ny = d[o + CNX + 1]; val nz = d[o + CNX + 2]
            val vn = rvx * nx + rvy * ny + rvz * nz
            val old = d[o + CLN]
            val total = max(old - d[o + CNM] * (vn + e * d[o + CREL]), 0f)
            d[o + CLN] = total
            val i = total - old
            if (i != 0f) push(a, b, o, nx * i, ny * i, nz * i)
        }
    }

    // ------------------------------------------------------------------ sleep

    private var parent = IntArray(0)
    private var islandClock = FloatArray(0)

    private fun root(i: Int): Int { var r = i; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }

    private fun updateSleep(dt: Float) {
        if (parent.size < count) { parent = IntArray(capacity); islandClock = FloatArray(capacity) }
        for (i in 0 until count) {
            parent[i] = i; islandClock[i] = Float.MAX_VALUE
            if (types[i] != BodyType.DYNAMIC || sleeping[i]) continue
            // Critère de Box2D v3 : vitesse du point le plus rapide du corps (centre + rotation × taille).
            val o = i * S
            val v = sqrt(st[o] * st[o] + st[o + 1] * st[o + 1] + st[o + 2] * st[o + 2])
            val w = sqrt(st[o + 3] * st[o + 3] + st[o + 4] * st[o + 4] + st[o + 5] * st[o + 5])
            sleepClock[i] = if (v + shapes[i]!!.boundingRadius * w > sleepLinearSpeed) 0f else sleepClock[i] + dt
        }
        val c = points; val d = c.d
        for (k in 0 until c.size) {
            if (d[k * C + CLN] <= 0f) continue
            val a = c.a[k]; val b = c.b[k]
            if (a < 0) continue
            // A dynamic body pushed by a moving kinematic one stays awake.
            if (types[a] == BodyType.KINEMATIC && moving(a)) { sleepClock[b] = 0f; continue }
            if (types[a] != BodyType.DYNAMIC || types[b] != BodyType.DYNAMIC) continue
            val ra = root(a); val rb = root(b)
            if (ra != rb) parent[ra] = rb
        }
        for (i in 0 until count) if (types[i] == BodyType.DYNAMIC && !sleeping[i]) {
            val r = root(i); islandClock[r] = min(islandClock[r], sleepClock[i])
        }
        for (i in 0 until count) if (types[i] == BodyType.DYNAMIC && !sleeping[i] && islandClock[root(i)] >= timeToSleep) {
            sleeping[i] = true
            val o = i * S
            for (j in 0..5) st[o + j] = 0f
        }
    }
}

// Sizes above which a phase goes to the worker threads, and the chunks it is cut into.
private const val PARALLEL_BODIES = 2048
private const val PARALLEL_PAIRS = 512
private const val PARALLEL_POINTS = 1024
private const val CELL_CHUNK = 256
private const val PAIR_CHUNK = 128
private const val BODY_CHUNK = 128
private const val PATCH_CHUNK = 64
private const val MAX_COLOURS = 32


// Solver data of one contact point, contiguous (C floats): normal, anchors, separation offset, normal
// mass and impulse, material, rolling resistance, softness. Friction lives in the patches (below).
private const val C = 28
private const val CNX = 0; private const val CRA = 3; private const val CRB = 6; private const val CADJ = 9
private const val CNM = 10; private const val CLN = 11; private const val CMAXN = 12; private const val CFR = 13
private const val CSTATIC = 14; private const val CRL = 15; private const val CRM = 16; private const val CLR = 17
private const val CREL = 20; private const val CREST = 21; private const val CDIST = 22
private const val CBIAS = 23; private const val CMS = 24; private const val CIS = 25     // softness of this contact
private const val CSA = 26; private const val CSB = 27          // inverse-mass scale of a and b in this contact

// One friction patch (P floats): the points of one pair that share a normal. Friction acts at their
// centre — two tangent directions and a twist about the normal — bounded by μ times their total load.
private const val P = 22
private const val PN = 0; private const val PRA = 3; private const val PRB = 6; private const val PT1 = 9; private const val PT2 = 12
private const val PM1 = 15; private const val PM2 = 16; private const val PMT = 17; private const val PFR = 18
private const val PL1 = 19; private const val PL2 = 20; private const val PLT = 21

/** Contact points of one step: solver data packed per point, plus what warm starting needs. */
internal class ContactSet {
    var size = 0; private set
    private var cap = 0
    var a = IntArray(0); var b = IntArray(0); var key = LongArray(0); var feature = IntArray(0)
    var x = FloatArray(0); var y = FloatArray(0); var z = FloatArray(0); var sep = FloatArray(0)
    var lx = FloatArray(0); var ly = FloatArray(0); var lz = FloatArray(0)      // point in B's local frame
    var d = FloatArray(0)
    private val byKey = LongIntMap()

    fun clear() { size = 0 }

    private fun ensure(n: Int) {
        if (n <= cap) return
        val c = max(n, max(64, cap * 2)); cap = c
        a = a.copyOf(c); b = b.copyOf(c); key = key.copyOf(c); feature = feature.copyOf(c)
        x = x.copyOf(c); y = y.copyOf(c); z = z.copyOf(c); sep = sep.copyOf(c)
        lx = lx.copyOf(c); ly = ly.copyOf(c); lz = lz.copyOf(c); d = d.copyOf(C * c)
    }

    /** Adds the points of one pair and picks up last step's impulses for matching points (warm start). */
    fun addAll(src: ContactPoints, ia: Int, ib: Int, pairKey: Long, mu: Float, e: Float, poseB: Pose, prev: ContactSet,
               surfaceRolling: Float = 0f) {
        ensure(size + src.count)
        val prevStart = prev.byKey.get(pairKey)
        for (i in 0 until src.count) {
            val k = size++; val o = k * C
            a[k] = ia; b[k] = ib; key[k] = pairKey; feature[k] = src.id[i]
            x[k] = src.x[i]; y[k] = src.y[i]; z[k] = src.z[i]; sep[k] = src.sep[i]
            java.util.Arrays.fill(d, o, o + C, 0f)
            d[o + CNX] = src.nx[i]; d[o + CNX + 1] = src.ny[i]; d[o + CNX + 2] = src.nz[i]
            d[o + CFR] = mu; d[o + CREST] = e; d[o + CRL] = surfaceRolling
            val dx0 = x[k] - poseB.p.x; val dy0 = y[k] - poseB.p.y; val dz0 = z[k] - poseB.p.z
            val lxk = dx0 * poseB.ax.x + dy0 * poseB.ax.y + dz0 * poseB.ax.z
            val lyk = dx0 * poseB.ay.x + dy0 * poseB.ay.y + dz0 * poseB.ay.z
            val lzk = dx0 * poseB.az.x + dy0 * poseB.az.y + dz0 * poseB.az.z
            lx[k] = lxk; ly[k] = lyk; lz[k] = lzk
            if (prevStart >= 0) {
                var bestJ = -1; var bestD = .05f * .05f
                var j = prevStart
                while (j < prev.size && prev.key[j] == pairKey) {
                    val dx = prev.lx[j] - lxk; val dy = prev.ly[j] - lyk; val dz = prev.lz[j] - lzk
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (prev.feature[j] == feature[k] && d2 < .1f * .1f) { bestJ = j; break }
                    if (d2 < bestD) { bestD = d2; bestJ = j }
                    j++
                }
                if (bestJ >= 0) {
                    val p = bestJ * C; val pd = prev.d
                    d[o + CLN] = pd[p + CLN]
                    d[o + CLR] = pd[p + CLR]; d[o + CLR + 1] = pd[p + CLR + 1]; d[o + CLR + 2] = pd[p + CLR + 2]
                }
            }
        }
    }

    /** Appends the points of [o] (a chunk of the narrow phase) after these. */
    fun append(o: ContactSet) {
        val n = o.size
        if (n == 0) return
        ensure(size + n)
        o.a.copyInto(a, size, 0, n); o.b.copyInto(b, size, 0, n); o.key.copyInto(key, size, 0, n); o.feature.copyInto(feature, size, 0, n)
        o.x.copyInto(x, size, 0, n); o.y.copyInto(y, size, 0, n); o.z.copyInto(z, size, 0, n); o.sep.copyInto(sep, size, 0, n)
        o.lx.copyInto(lx, size, 0, n); o.ly.copyInto(ly, size, 0, n); o.lz.copyInto(lz, size, 0, n)
        o.d.copyInto(d, C * size, 0, C * n)
        size += n
    }

    /** Indexes this step's points by pair, so that the next step can find them. */
    fun keep() {
        byKey.clear()
        for (k in 0 until size) if (k == 0 || key[k] != key[k - 1]) byKey.putIfAbsent(key[k], k)
    }
}

/** Friction patches of one step, and their impulses for warm starting the next. */
internal class PatchSet {
    var size = 0; private set
    private var cap = 0
    var start = IntArray(0); var end = IntArray(0); var a = IntArray(0); var b = IntArray(0); var key = LongArray(0)
    var d = FloatArray(0)
    val byKey = LongIntMap()

    fun clear() { size = 0 }
    fun add(): Int {
        if (size == cap) {
            val c = max(64, cap * 2); cap = c
            start = start.copyOf(c); end = end.copyOf(c); a = a.copyOf(c); b = b.copyOf(c); key = key.copyOf(c); d = d.copyOf(P * c)
        }
        java.util.Arrays.fill(d, size * P, size * P + P, 0f)
        return size++
    }
    fun keep() { byKey.clear(); for (k in 0 until size) if (key[k] != -1L) byKey.putIfAbsent(key[k], k) }
}
