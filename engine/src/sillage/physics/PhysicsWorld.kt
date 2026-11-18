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

/** Handle to a body of a [PhysicsWorld]. Two handles to the same body are equal. */
@ValueCandidate
class Body internal constructor(private val world: PhysicsWorld, val id: Int) {
    private val o get() = id * S
    val type: BodyType get() = world.types[id]
    val shape: Shape get() = world.shapes[id]!!
    var position: Vec3
        get() = Vec3(world.px[id], world.py[id], world.pz[id])
        set(v) { world.px[id] = v.x; world.py[id] = v.y; world.pz[id] = v.z; world.wake(id) }
    var rotation: Quat
        get() = Quat(world.qx[id], world.qy[id], world.qz[id], world.qw[id])
        set(q) { val u = q.normalized(); world.qx[id] = u.x; world.qy[id] = u.y; world.qz[id] = u.z; world.qw[id] = u.w; world.wake(id) }
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

    internal var count = 0
    private var capacity = 0
    internal var types = Array(0) { BodyType.STATIC }
    internal var shapes = arrayOfNulls<Shape>(0)
    internal var px = FloatArray(0); internal var py = FloatArray(0); internal var pz = FloatArray(0)
    internal var qx = FloatArray(0); internal var qy = FloatArray(0); internal var qz = FloatArray(0); internal var qw = FloatArray(0)
    internal var st = FloatArray(0)
    private var ilx = FloatArray(0); private var ily = FloatArray(0); private var ilz = FloatArray(0)   // inverse local inertia
    internal var friction = FloatArray(0); internal var restitution = FloatArray(0)
    internal var linearDamping = FloatArray(0); internal var angularDamping = FloatArray(0); internal var gravityScale = FloatArray(0)
    internal var rolling = FloatArray(0); private var rollRadius = FloatArray(0)
    internal var sleeping = BooleanArray(0); private var sleepClock = FloatArray(0)
    private var fx = FloatArray(0); private var fy = FloatArray(0); private var fz = FloatArray(0)
    private var q0 = FloatArray(0)
    private var box = FloatArray(0)
    private var poses = arrayOfNulls<Pose>(0)

    private val meshes = ArrayList<TriangleMesh>()
    private val meshFriction = ArrayList<Float>()

    val bodyCount get() = count
    /** When true, [phaseNanos] accumulates time per phase: bodies, pairs, contacts, prepare, warm start, solve, relax, sleep. */
    var profiling = false
    val phaseNanos = LongArray(8)
    /** Pairs and contact points of the last step (diagnostics). */
    val pairCount get() = pairsA.size
    val contactCount get() = previous.size

    // ------------------------------------------------------------------ bodies

    private fun grow() {
        val c = max(16, capacity * 2)
        types = Array(c) { if (it < capacity) types[it] else BodyType.STATIC }
        shapes = shapes.copyOf(c)
        px = px.copyOf(c); py = py.copyOf(c); pz = pz.copyOf(c)
        qx = qx.copyOf(c); qy = qy.copyOf(c); qz = qz.copyOf(c); qw = qw.copyOf(c)
        st = st.copyOf(S * c)
        ilx = ilx.copyOf(c); ily = ily.copyOf(c); ilz = ilz.copyOf(c)
        friction = friction.copyOf(c); restitution = restitution.copyOf(c)
        linearDamping = linearDamping.copyOf(c); angularDamping = angularDamping.copyOf(c); gravityScale = gravityScale.copyOf(c)
        rolling = rolling.copyOf(c); rollRadius = rollRadius.copyOf(c)
        sleeping = sleeping.copyOf(c); sleepClock = sleepClock.copyOf(c)
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
        if (count == capacity) grow()
        val i = count++
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
        rolling[i] = rollingResistance ?: if (rollRadius[i] > 0f) .02f else 0f
        return Body(this, i)
    }

    fun body(id: Int): Body { require(id in 0 until count); return Body(this, id) }

    /** Adds static triangles (terrain, buildings). */
    fun addMesh(mesh: TriangleMesh, friction: Float = .6f) { meshes.add(mesh); meshFriction.add(friction) }

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
    private val pairsA = IntList(); private val pairsB = IntList()
    private val found = IntList(); private val stack = IntList()
    private val scratch = ContactPoints()

    /** Advances the simulation by [dt] seconds. */
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

    private fun prepareBodies(dt: Float) {
        for (i in 0 until count) {
            val x = qx[i]; val y = qy[i]; val z = qz[i]; val w = qw[i]
            val ax = Vec3(1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w))
            val ay = Vec3(2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w))
            val az = Vec3(2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y))
            val p = Vec3(px[i], py[i], pz[i])
            poses[i] = Pose(p, ax, ay, az)
            if (types[i] == BodyType.DYNAMIC) prepareInertia(i)
            q0[4 * i] = x; q0[4 * i + 1] = y; q0[4 * i + 2] = z; q0[4 * i + 3] = w
            val o = i * S
            st[o + DPX] = 0f; st[o + DPX + 1] = 0f; st[o + DPX + 2] = 0f
            st[o + DQX] = 0f; st[o + DQX + 1] = 0f; st[o + DQX + 2] = 0f; st[o + DQW] = 1f
            // Bounds, widened by the distance travelled during the step.
            val e = when (val s = shapes[i]!!) {
                is Sphere -> Vec3(s.radius, s.radius, s.radius)
                is Box -> Vec3(abs(ax.x) * s.halfX + abs(ay.x) * s.halfY + abs(az.x) * s.halfZ,
                    abs(ax.y) * s.halfX + abs(ay.y) * s.halfY + abs(az.y) * s.halfZ,
                    abs(ax.z) * s.halfX + abs(ay.z) * s.halfY + abs(az.z) * s.halfZ)
                is Capsule -> Vec3(abs(ay.x) * s.halfHeight + s.radius, abs(ay.y) * s.halfHeight + s.radius, abs(ay.z) * s.halfHeight + s.radius)
            }
            val m = speculativeDistance + speed(i) * dt
            val b = 6 * i
            box[b] = p.x - e.x - m; box[b + 1] = p.y - e.y - m; box[b + 2] = p.z - e.z - m
            box[b + 3] = p.x + e.x + m; box[b + 4] = p.y + e.y + m; box[b + 5] = p.z + e.z + m
        }
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
        val di = types[i] == BodyType.DYNAMIC; val dj = types[j] == BodyType.DYNAMIC
        if (!di && !dj) return
        if (!moving(i) && !moving(j)) return
        // A moving body reaching a sleeping one wakes it.
        if (di && sleeping[i]) wake(i)
        if (dj && sleeping[j]) wake(j)
        pairsA.add(min(i, j)); pairsB.add(max(i, j))
    }

    private var cellMin = IntArray(0)      // per body: cell of its lowest corner (x, y, z)
    private var local = FloatArray(0)      // bounds of one cell's occupants, copied side by side
    private var localId = IntArray(0)

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
        java.util.Arrays.sort(cells, 0, entries)
        var start = 0
        while (start < entries) {
            val cell = cells[start] ushr 24
            var end = start + 1
            while (end < entries && (cells[end] ushr 24) == cell) end++
            val m = end - start
            if (m > 1) {
                val cx = (cell ushr 26).toInt(); val cy = ((cell ushr 13) and 0x1fff).toInt(); val cz = (cell and 0x1fff).toInt()
                // Copy the occupants' bounds and lowest cells side by side: the pair tests then stay in cache.
                if (localId.size < m) { local = FloatArray(9 * m * 2); localId = IntArray(m * 2) }
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
                        // Report the pair only from the cell holding the overlap's lowest corner.
                        if (max(local[lp + 6], local[lq + 6]).toInt() != cx || max(local[lp + 7], local[lq + 7]).toInt() != cy ||
                            max(local[lp + 8], local[lq + 8]).toInt() != cz) continue
                        considerPair(localId[p], localId[q])
                    }
                }
            }
            start = end
        }
        for (k in 0 until large.size) {
            val i = large[k]
            for (j in 0 until count) if (j != i && !(isLarge[j] && j < i)) considerPair(i, j)
        }
    }

    private fun collide(dt: Float) {
        points.clear()
        for (k in 0 until pairsA.size) {
            val i = pairsA[k]; val j = pairsB[k]
            val margin = min(1f, speculativeDistance + (speed(i) + speed(j)) * dt)
            scratch.clear()
            collide(shapes[i]!!, poses[i]!!, shapes[j]!!, poses[j]!!, margin, scratch)
            // A kinematic body sets the bounce of what it touches: a character is soft (0), a paddle may be bouncy.
            val e = when {
                types[i] == BodyType.KINEMATIC -> restitution[i]
                types[j] == BodyType.KINEMATIC -> restitution[j]
                else -> max(restitution[i], restitution[j])
            }
            if (scratch.count > 0) points.addAll(scratch, i, j, (i.toLong() shl 32) or j.toLong(),
                sqrt(friction[i] * friction[j]), e, poses[j]!!, previous)
        }
        for ((m, mesh) in meshes.withIndex()) {
            val v = mesh.v; val n = mesh.n
            for (b in 0 until count) {
                if (types[b] != BodyType.DYNAMIC || sleeping[b]) continue
                val o = 6 * b
                found.clear()
                mesh.query(box[o], box[o + 1], box[o + 2], box[o + 3], box[o + 4], box[o + 5], found, stack)
                if (found.size == 0) continue
                val margin = min(1f, speculativeDistance + speed(b) * dt)
                for (f in 0 until found.size) {
                    val t = found[f]
                    val tri = Tri(Vec3(v[9 * t], v[9 * t + 1], v[9 * t + 2]), Vec3(v[9 * t + 3], v[9 * t + 4], v[9 * t + 5]),
                        Vec3(v[9 * t + 6], v[9 * t + 7], v[9 * t + 8]), Vec3(n[3 * t], n[3 * t + 1], n[3 * t + 2]), mesh.edges[t])
                    scratch.clear()
                    collideTriangle(tri, shapes[b]!!, poses[b]!!, margin, scratch)
                    if (scratch.count > 0) points.addAll(scratch, -1, b,
                        Long.MIN_VALUE or (m.toLong() shl 55) or (b.toLong() shl 24) or t.toLong(),
                        sqrt(meshFriction[m] * friction[b]), restitution[b], poses[b]!!, previous)
                }
            }
        }
    }

    // ------------------------------------------------------------------ solver

    private var softBias = 0f; private var softMass = 1f; private var softImpulse = 0f
    private var staticBias = 0f; private var staticMass = 1f; private var staticImpulse = 0f

    private fun soft(hertz: Float, zeta: Float, h: Float): FloatArray {
        if (hertz == 0f) return floatArrayOf(0f, 1f, 0f)
        val omega = 2f * Math.PI.toFloat() * hertz
        val a1 = 2f * zeta + h * omega; val a2 = h * omega * a1; val a3 = 1f / (1f + a2)
        return floatArrayOf(omega / a1, a2 * a3, a3)
    }

    private fun tangent(n: Vec3): Vec3 =
        if (abs(n.x) >= .57735f) Vec3(n.y, -n.x, 0f).normalized() else Vec3(0f, n.z, -n.y).normalized()

    private fun prepareContacts(h: Float) {
        val hertz = min(contactHertz, .25f / h)
        soft(hertz, contactDampingRatio, h).let { softBias = it[0]; softMass = it[1]; softImpulse = it[2] }
        soft(2f * hertz, contactDampingRatio, h).let { staticBias = it[0]; staticMass = it[1]; staticImpulse = it[2] }
        val c = points; val d = c.d
        for (k in 0 until c.size) {
            val a = c.a[k]; val b = c.b[k]; val o = k * C
            val n = Vec3(d[o + CNX], d[o + CNX + 1], d[o + CNX + 2])
            val x = c.x[k]; val y = c.y[k]; val z = c.z[k]
            val rA = if (a >= 0) Vec3(x - px[a], y - py[a], z - pz[a]) else Vec3.ZERO
            val rB = Vec3(x - px[b], y - py[b], z - pz[b])
            d[o + CRA] = rA.x; d[o + CRA + 1] = rA.y; d[o + CRA + 2] = rA.z
            d[o + CRB] = rB.x; d[o + CRB + 1] = rB.y; d[o + CRB + 2] = rB.z
            d[o + CADJ] = c.sep[k] - ((rB - rA) dot n)
            val mA = if (a >= 0) st[a * S + IM] else 0f; val mB = st[b * S + IM]
            d[o + CSTATIC] = if (mA == 0f || mB == 0f) 1f else 0f
            fun effectiveMass(dir: Vec3): Float {
                var kk = mA + mB
                if (mA > 0f) { val ra = rA cross dir; kk += ra dot mulInvInertia(a, ra) }
                if (mB > 0f) { val rb = rB cross dir; kk += rb dot mulInvInertia(b, rb) }
                return if (kk > 0f) 1f / kk else 0f
            }
            val t1 = tangent(n); val t2 = n cross t1
            d[o + CT1] = t1.x; d[o + CT1 + 1] = t1.y; d[o + CT1 + 2] = t1.z
            d[o + CT2] = t2.x; d[o + CT2 + 1] = t2.y; d[o + CT2 + 2] = t2.z
            d[o + CNM] = effectiveMass(n); d[o + CT1M] = effectiveMass(t1); d[o + CT2M] = effectiveMass(t2)
            relative(a, b, o)
            d[o + CREL] = rvx * n.x + rvy * n.y + rvz * n.z
            d[o + CMAXN] = 0f
            // Résistance au roulement : couple borné par μr × rayon × force normale.
            val rl = max(if (a >= 0) rolling[a] * rollRadius[a] else 0f, rolling[b] * rollRadius[b])
            d[o + CRL] = rl
            if (rl > 0f) {
                var tr = 0f
                if (mA > 0f) tr += st[a * S + I0] + st[a * S + I0 + 3] + st[a * S + I0 + 5]
                if (mB > 0f) tr += st[b * S + I0] + st[b * S + I0 + 3] + st[b * S + I0 + 5]
                d[o + CRM] = if (tr > 0f) 3f / tr else 0f
            }
        }
    }

    private fun integrateVelocities(h: Float) {
        val gx = gravity.x; val gy = gravity.y; val gz = gravity.z
        for (i in 0 until count) {
            if (types[i] != BodyType.DYNAMIC || sleeping[i]) continue
            val o = i * S
            val m = st[o + IM]; val gs = gravityScale[i]
            val ld = 1f / (1f + h * linearDamping[i]); val ad = 1f / (1f + h * angularDamping[i])
            st[o] = (st[o] + h * (gx * gs + fx[i] * m)) * ld
            st[o + 1] = (st[o + 1] + h * (gy * gs + fy[i] * m)) * ld
            st[o + 2] = (st[o + 2] + h * (gz * gs + fz[i] * m)) * ld
            st[o + 3] *= ad; st[o + 4] *= ad; st[o + 5] *= ad
        }
    }

    private fun integratePositions(h: Float) {
        for (i in 0 until count) {
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

    /** Applies the impulse (px, py, pz) at contact data offset o: −P on body a, +P on body b. */
    private fun push(a: Int, b: Int, o: Int, px: Float, py: Float, pz: Float) {
        val d = points.d
        if (a >= 0) {
            val s = a * S
            val m = st[s + IM]
            if (m > 0f) {
                st[s] -= px * m; st[s + 1] -= py * m; st[s + 2] -= pz * m
                val rx = d[o + CRA]; val ry = d[o + CRA + 1]; val rz = d[o + CRA + 2]
                val cx = ry * pz - rz * py; val cy = rz * px - rx * pz; val cz = rx * py - ry * px
                val i = s + I0
                st[s + 3] -= st[i] * cx + st[i + 1] * cy + st[i + 2] * cz
                st[s + 4] -= st[i + 1] * cx + st[i + 3] * cy + st[i + 4] * cz
                st[s + 5] -= st[i + 2] * cx + st[i + 4] * cy + st[i + 5] * cz
            }
        }
        val s = b * S
        val m = st[s + IM]
        if (m > 0f) {
            st[s] += px * m; st[s + 1] += py * m; st[s + 2] += pz * m
            val rx = d[o + CRB]; val ry = d[o + CRB + 1]; val rz = d[o + CRB + 2]
            val cx = ry * pz - rz * py; val cy = rz * px - rx * pz; val cz = rx * py - ry * px
            val i = s + I0
            st[s + 3] += st[i] * cx + st[i + 1] * cy + st[i + 2] * cz
            st[s + 4] += st[i + 1] * cx + st[i + 3] * cy + st[i + 4] * cz
            st[s + 5] += st[i + 2] * cx + st[i + 4] * cy + st[i + 5] * cz
        }
    }

    /** Angular impulse (lx, ly, lz): −L on a, +L on b. */
    private fun twist(a: Int, b: Int, lx: Float, ly: Float, lz: Float) {
        if (a >= 0 && st[a * S + IM] > 0f) {
            val s = a * S; val i = s + I0
            st[s + 3] -= st[i] * lx + st[i + 1] * ly + st[i + 2] * lz
            st[s + 4] -= st[i + 1] * lx + st[i + 3] * ly + st[i + 4] * lz
            st[s + 5] -= st[i + 2] * lx + st[i + 4] * ly + st[i + 5] * lz
        }
        if (st[b * S + IM] > 0f) {
            val s = b * S; val i = s + I0
            st[s + 3] += st[i] * lx + st[i + 1] * ly + st[i + 2] * lz
            st[s + 4] += st[i + 1] * lx + st[i + 3] * ly + st[i + 4] * lz
            st[s + 5] += st[i + 2] * lx + st[i + 4] * ly + st[i + 5] * lz
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

    // Anchor at data offset r rotated by body i's rotation since the step began, added (sign ±1) to (sx, sy, sz).
    private var sx = 0f; private var sy = 0f; private var sz = 0f
    private fun addRotated(i: Int, r: Int, sign: Float) {
        val d = points.d
        val x = d[r]; val y = d[r + 1]; val z = d[r + 2]
        if (i < 0) { sx += sign * x; sy += sign * y; sz += sign * z; return }
        val q = i * S + DQX
        val qx = st[q]; val qy = st[q + 1]; val qz = st[q + 2]; val qw = st[q + 3]
        val tx = 2f * (qy * z - qz * y); val ty = 2f * (qz * x - qx * z); val tz = 2f * (qx * y - qy * x)
        sx += sign * (x + qw * tx + (qy * tz - qz * ty))
        sy += sign * (y + qw * ty + (qz * tx - qx * tz))
        sz += sign * (z + qw * tz + (qx * ty - qy * tx))
    }

    private fun warmStart() {
        val c = points; val d = c.d
        for (k in 0 until c.size) {
            val a = c.a[k]; val b = c.b[k]; val o = k * C
            val n = d[o + CLN]; val t1 = d[o + CLT1]; val t2 = d[o + CLT2]
            push(a, b, o, d[o + CNX] * n + d[o + CT1] * t1 + d[o + CT2] * t2,
                d[o + CNX + 1] * n + d[o + CT1 + 1] * t1 + d[o + CT2 + 1] * t2,
                d[o + CNX + 2] * n + d[o + CT1 + 2] * t1 + d[o + CT2 + 2] * t2)
            if (d[o + CRL] > 0f) twist(a, b, d[o + CLR], d[o + CLR + 1], d[o + CLR + 2])
        }
    }

    private fun solve(h: Float, useBias: Boolean) {
        val c = points; val d = c.d
        val invH = 1f / h
        for (k in 0 until c.size) {
            val a = c.a[k]; val b = c.b[k]; val o = k * C
            val nx = d[o + CNX]; val ny = d[o + CNX + 1]; val nz = d[o + CNX + 2]
            // Current separation from the motion since the step began.
            val sb = b * S + DPX
            sx = st[sb]; sy = st[sb + 1]; sz = st[sb + 2]
            if (a >= 0) { val sa = a * S + DPX; sx -= st[sa]; sy -= st[sa + 1]; sz -= st[sa + 2] }
            addRotated(b, o + CRB, 1f); addRotated(a, o + CRA, -1f)
            val s = sx * nx + sy * ny + sz * nz + d[o + CADJ]
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (s > 0f) bias = s * invH
            else if (useBias) {
                if (d[o + CSTATIC] != 0f) { bias = max(staticBias * s, -contactSpeed); massScale = staticMass; impulseScale = staticImpulse }
                else { bias = max(softBias * s, -contactSpeed); massScale = softMass; impulseScale = softImpulse }
            }
            relative(a, b, o)
            val vn = rvx * nx + rvy * ny + rvz * nz
            val old = d[o + CLN]
            val total = max(old - d[o + CNM] * massScale * (vn + bias) - impulseScale * old, 0f)
            val impulse = total - old
            d[o + CLN] = total
            if (impulse > d[o + CMAXN]) d[o + CMAXN] = impulse
            if (impulse != 0f) push(a, b, o, nx * impulse, ny * impulse, nz * impulse)
        }
        for (k in 0 until c.size) {
            val a = c.a[k]; val b = c.b[k]; val o = k * C
            val limit = d[o + CFR] * d[o + CLN]
            relative(a, b, o)
            val t1x = d[o + CT1]; val t1y = d[o + CT1 + 1]; val t1z = d[o + CT1 + 2]
            val old1 = d[o + CLT1]
            val new1 = (old1 - d[o + CT1M] * (rvx * t1x + rvy * t1y + rvz * t1z)).coerceIn(-limit, limit)
            d[o + CLT1] = new1
            if (new1 != old1) { val e = new1 - old1; push(a, b, o, t1x * e, t1y * e, t1z * e); relative(a, b, o) }
            val t2x = d[o + CT2]; val t2y = d[o + CT2 + 1]; val t2z = d[o + CT2 + 2]
            val old2 = d[o + CLT2]
            val new2 = (old2 - d[o + CT2M] * (rvx * t2x + rvy * t2y + rvz * t2z)).coerceIn(-limit, limit)
            d[o + CLT2] = new2
            if (new2 != old2) { val e = new2 - old2; push(a, b, o, t2x * e, t2y * e, t2z * e) }
            val rl = d[o + CRL]
            if (rl > 0f) {
                val sbw = b * S
                var dwx = st[sbw + 3]; var dwy = st[sbw + 4]; var dwz = st[sbw + 5]
                if (a >= 0) { val saw = a * S; dwx -= st[saw + 3]; dwy -= st[saw + 4]; dwz -= st[saw + 5] }
                val m = d[o + CRM]; val max = rl * d[o + CLN]
                val ox = d[o + CLR]; val oy = d[o + CLR + 1]; val oz = d[o + CLR + 2]
                var lx = ox - m * dwx; var ly = oy - m * dwy; var lz = oz - m * dwz
                val l2 = lx * lx + ly * ly + lz * lz
                if (l2 > max * max) { val f = max / sqrt(l2); lx *= f; ly *= f; lz *= f }
                d[o + CLR] = lx; d[o + CLR + 1] = ly; d[o + CLR + 2] = lz
                twist(a, b, lx - ox, ly - oy, lz - oz)
            }
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

// Solver data of one contact point, contiguous (C floats): normal, anchors, tangents, masses,
// accumulated impulses, material.
private const val C = 32
private const val CNX = 0; private const val CRA = 3; private const val CRB = 6; private const val CT1 = 9; private const val CT2 = 12
private const val CADJ = 15; private const val CNM = 16; private const val CT1M = 17; private const val CT2M = 18
private const val CLN = 19; private const val CLT1 = 20; private const val CLT2 = 21; private const val CMAXN = 22
private const val CFR = 23; private const val CSTATIC = 24; private const val CRL = 25; private const val CRM = 26
private const val CLR = 27; private const val CREL = 30; private const val CREST = 31

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
    fun addAll(src: ContactPoints, ia: Int, ib: Int, pairKey: Long, mu: Float, e: Float, poseB: Pose, prev: ContactSet) {
        ensure(size + src.count)
        val prevStart = prev.byKey.get(pairKey)
        for (i in 0 until src.count) {
            val k = size++; val o = k * C
            a[k] = ia; b[k] = ib; key[k] = pairKey; feature[k] = src.id[i]
            x[k] = src.x[i]; y[k] = src.y[i]; z[k] = src.z[i]; sep[k] = src.sep[i]
            java.util.Arrays.fill(d, o, o + C, 0f)
            d[o + CNX] = src.nx[i]; d[o + CNX + 1] = src.ny[i]; d[o + CNX + 2] = src.nz[i]
            d[o + CFR] = mu; d[o + CREST] = e
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
                    d[o + CLN] = pd[p + CLN]; d[o + CLT1] = pd[p + CLT1]; d[o + CLT2] = pd[p + CLT2]
                    d[o + CLR] = pd[p + CLR]; d[o + CLR + 1] = pd[p + CLR + 1]; d[o + CLR + 2] = pd[p + CLR + 2]
                }
            }
        }
    }

    /** Indexes this step's points by pair, so that the next step can find them. */
    fun keep() {
        byKey.clear()
        for (k in 0 until size) if (k == 0 || key[k] != key[k - 1]) byKey.putIfAbsent(key[k], k)
    }
}
