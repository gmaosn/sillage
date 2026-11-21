package sillage.physics

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorMask
import jdk.incubator.vector.VectorOperators
import jdk.incubator.vector.VectorSpecies
import sillage.math.Quat
import sillage.math.ValueCandidate
import sillage.math.Vec3
import kotlin.math.PI
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
/** How far before the surface continuous collision puts a body back (m). */
private const val CCD_SLOP = .005f

/** One contact point of the last step, see [PhysicsWorld.contacts]. */
class Contact(val a: Body?, val b: Body, val point: Vec3, val normal: Vec3, val separation: Float, val normalImpulse: Float)

/** Handle to a body of a [PhysicsWorld]. Two handles to the same body are equal. */
@ValueCandidate
class Body internal constructor(internal val world: PhysicsWorld, val id: Int) {
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
    /**
     * A projectile: swept against every body each step, not only against what does not move (any fast
     * body is swept against those). Costs a sweep per step while it is fast.
     */
    var isBullet: Boolean
        get() = world.bullet[id]
        set(v) { world.bullet[id] = v }
    /** How much of it is under water (0 to 1), in the last substep: see [WaterVolume]. */
    val submergedFraction: Float get() = world.wet[id]
    /** Free label for the game (a role, a group, the object it moves…); the engine never reads it. */
    var tag: Any?
        get() = world.tags[id]
        set(v) { world.tags[id] = v }

    /** Instant change of momentum, applied at [point] (the centre by default). */
    fun applyImpulse(impulse: Vec3, point: Vec3 = position) {
        if (world.types[id] != BodyType.DYNAMIC) return
        world.wake(id)
        nudge(impulse, point)
    }

    /** A force (N) at [point] (the centre by default) during the next step: an engine, a thruster, the wind. */
    fun applyForce(force: Vec3, point: Vec3 = position) = world.addForce(id, force, point, steady = false)

    /** [applyForce] for a force applied every step (a suspension, a float): an awake body still falls asleep. */
    internal fun applySteadyForce(force: Vec3, point: Vec3) = world.addForce(id, force, point, steady = true)

    private fun nudge(impulse: Vec3, point: Vec3) {
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
    /** Bodies whose inertia is diagonal about turned axes (hulls, compounds): their [Shape.principalAxes], x y z w. */
    private var principal = BooleanArray(0); private var pq = FloatArray(0)
    internal var sleeping = BooleanArray(0); private var sleepClock = FloatArray(0)
    internal var removed = BooleanArray(0)
    internal var bullet = BooleanArray(0)
    private val free = IntList()
    private var fx = FloatArray(0); private var fy = FloatArray(0); private var fz = FloatArray(0)
    private var tx = FloatArray(0); private var ty = FloatArray(0); private var tz = FloatArray(0)      // torques
    private var torqued = false
    private var q0 = FloatArray(0)
    private var box = FloatArray(0)
    private var poses = Array(0) { Pose() }

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
        principal = principal.copyOf(c); pq = pq.copyOf(4 * c)
        sleeping = sleeping.copyOf(c); sleepClock = sleepClock.copyOf(c); removed = removed.copyOf(c); bullet = bullet.copyOf(c)
        wet = wet.copyOf(c); wetBefore = wetBefore.copyOf(c); wetIn = wetIn.copyOf(c); entered = entered.copyOf(c); lastWater = lastWater.copyOf(c)
        wetRest = wetRest.copyOf(c); touched = touched.copyOf(c)
        fx = fx.copyOf(c); fy = fy.copyOf(c); fz = fz.copyOf(c)
        tx = tx.copyOf(c); ty = ty.copyOf(c); tz = tz.copyOf(c)
        q0 = q0.copyOf(4 * c); box = box.copyOf(6 * c)
        poses = Array(c) { if (it < capacity) poses[it] else Pose() }
        capacity = c
    }

    /**
     * Adds a body. Its mass comes from [density] (kg/m³) unless [mass] is given; static and
     * kinematic bodies have infinite mass. Balls, capsules and compounds with round parts get a little rolling resistance.
     */
    fun add(shape: Shape, position: Vec3, rotation: Quat = Quat.IDENTITY, type: BodyType = BodyType.DYNAMIC,
            density: Float = 1000f, mass: Float? = null, friction: Float = .6f, restitution: Float = 0f,
            rollingResistance: Float? = null): Body {
        queryGrid.valid = false
        val i = if (free.size > 0) free.pop() else { if (count == capacity) grow(); count++ }
        // A reused slot starts afresh.
        removed[i] = false; sleeping[i] = false; sleepClock[i] = 0f; tags[i] = null; poses[i].px = Float.NaN; bullet[i] = false
        wet[i] = 0f; wetBefore[i] = 0f; wetIn[i] = null; lastWater[i] = null
        fx[i] = 0f; fy[i] = 0f; fz[i] = 0f; tx[i] = 0f; ty[i] = 0f; tz[i] = 0f; java.util.Arrays.fill(st, i * S, i * S + S, 0f)
        linearDamping[i] = 0f; angularDamping[i] = 0f
        types[i] = type; shapes[i] = shape
        px[i] = position.x; py[i] = position.y; pz[i] = position.z
        val q = rotation.normalized(); qx[i] = q.x; qy[i] = q.y; qz[i] = q.z; qw[i] = q.w
        st[i * S + DQW] = 1f
        if (type == BodyType.DYNAMIC) {
            val m = mass ?: (density * shape.massVolume)
            require(m > 0f) { "A dynamic body needs a positive mass" }
            st[i * S + IM] = 1f / m
            val inertia = shape.inertia(m)
            ilx[i] = 1f / inertia.x; ily[i] = 1f / inertia.y; ilz[i] = 1f / inertia.z
        }
        this.friction[i] = friction; this.restitution[i] = restitution; gravityScale[i] = 1f
        // Les boules et capsules finissent par s'arrêter de rouler (sinon un tas ne s'endort jamais).
        rollRadius[i] = when (shape) {
            is Sphere -> shape.radius; is Capsule -> shape.radius
            // A compound with round parts rolls on them (a dumbbell rolled for ever): its largest round part's radius.
            is Compound -> { var r = 0f; for (p in shape.shapes) r = max(r, when (p) { is Sphere -> p.radius; is Capsule -> p.radius; else -> 0f }); r }
            else -> 0f
        }
        round[i] = shape is Sphere
        val axes = shape.principalAxes
        principal[i] = axes != Quat.IDENTITY && type == BodyType.DYNAMIC
        pq[4 * i] = axes.x; pq[4 * i + 1] = axes.y; pq[4 * i + 2] = axes.z; pq[4 * i + 3] = axes.w
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
        // Its joints go with it (what they held wakes up).
        if (joints.isNotEmpty()) for (j in joints.filter { it.ia == i || it.ib == i }) removeJoint(j)
        removed[i] = true; types[i] = BodyType.STATIC; sleeping[i] = false; tags[i] = null
        wet[i] = 0f; wetBefore[i] = 0f; wetIn[i] = null; lastWater[i] = null
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

    // ------------------------------------------------------------------ joints

    /**
     * Stiffness of the joints' correction (Hz) and its damping ratio (Box2D v3's). It is capped at a quarter
     * of the substep rate (60 Hz with 4 substeps at 60 steps a second): stiffer, the correction overshoots
     * and the joints explode. A light body holding a heavy one gives a little (a 2 m beam of four welded
     * boxes sags 5 cm); more [substeps] with a higher [jointHertz] make it stiffer (8 and 120 Hz: 1.2 cm).
     */
    var jointHertz = 60f
    var jointDampingRatio = 2f
    private val joints = ArrayList<Joint>()
    /** The joints in the world, in the order they were added. */
    val jointList: List<Joint> get() = joints
    private var noCollide = LongArray(0); private var noCollideCount = 0
    private var noCollideDirty = false
    private var jointed = BooleanArray(0)                // per body: in a joint whose bodies do not collide

    private fun checkJoint(a: Body?, b: Body) {
        require(b.world === this && (a == null || a.world === this)) { "Both bodies must be in this world" }
        require(!b.isRemoved && (a == null || !a.isRemoved)) { "A removed body cannot be joined" }
        require(a != b) { "A body cannot be joined to itself" }
        require(b.type == BodyType.DYNAMIC || a?.type == BodyType.DYNAMIC) { "A joint needs a dynamic body" }
    }
    private fun <J : Joint> keep(j: J): J { joints.add(j); noCollideDirty = true; wakeJoint(j); return j }

    /** A ball joint at [anchor] (world): [b] hangs from [a], or from the world when [a] is null. */
    fun addBall(a: Body?, b: Body, anchor: Vec3, collideConnected: Boolean = false): BallJoint {
        checkJoint(a, b); return keep(BallJoint(this, a, b, anchor, collideConnected))
    }
    /**
     * A ball joint at [anchor] whose swing is held in a cone and whose twist is limited (a ragdoll's shoulder,
     * hip, neck): [axis] (world) is b's bone; b's axis may lean from a's by [swing] radians towards ±[across]
     * (default: some direction across the axis) and by [swing2] across that, and turn about itself between
     * [twistLower] and [twistUpper]. See [ConeTwistJoint].
     */
    fun addConeTwist(a: Body?, b: Body, anchor: Vec3, axis: Vec3, swing: Float, swing2: Float = swing,
                     twistLower: Float = -.3f, twistUpper: Float = .3f, across: Vec3? = null, collideConnected: Boolean = false): ConeTwistJoint {
        checkJoint(a, b); require(axis.lengthSquared() > 1e-12f) { "A cone joint needs an axis" }
        val j = ConeTwistJoint(this, a, b, anchor, axis, across ?: perpendicular(axis.normalized()), collideConnected)
        j.limits(swing, swing2, twistLower, twistUpper)
        return keep(j)
    }
    /** A hinge at [anchor] turning about [axis] (world): a door, a wheel, a lever. */
    fun addHinge(a: Body?, b: Body, anchor: Vec3, axis: Vec3, collideConnected: Boolean = false): HingeJoint {
        checkJoint(a, b); require(axis.lengthSquared() > 1e-12f) { "A hinge needs an axis" }
        return keep(HingeJoint(this, a, b, anchor, axis, collideConnected))
    }
    /** A slider along [axis] (world): [b] moves along it relative to [a] without turning. */
    fun addSlider(a: Body?, b: Body, axis: Vec3, anchor: Vec3 = b.position, collideConnected: Boolean = false): SliderJoint {
        checkJoint(a, b); require(axis.lengthSquared() > 1e-12f) { "A slider needs an axis" }
        return keep(SliderJoint(this, a, b, anchor, axis, collideConnected))
    }
    /** Glues [b] to [a] (or to the world) as they are now. */
    fun addFixed(a: Body?, b: Body, anchor: Vec3 = b.position, collideConnected: Boolean = false): FixedJoint {
        checkJoint(a, b); return keep(FixedJoint(this, a, b, anchor, collideConnected))
    }
    /** Keeps [anchorA] on [a] and [anchorB] on [b] at their present distance (or [length]); see [DistanceJoint.spring]. */
    fun addDistance(a: Body?, anchorA: Vec3, b: Body, anchorB: Vec3, length: Float? = null, collideConnected: Boolean = true): DistanceJoint {
        checkJoint(a, b)
        return keep(DistanceJoint(this, a, b, anchorA, anchorB, collideConnected, rope = false).also { j -> length?.let { j.length = it } })
    }
    /** A spring between [anchorA] on [a] and [anchorB] on [b], resting at [length] (default: as they are now). */
    fun addSpring(a: Body?, anchorA: Vec3, b: Body, anchorB: Vec3, hertz: Float, dampingRatio: Float = .5f, length: Float? = null): DistanceJoint =
        addDistance(a, anchorA, b, anchorB, length).also { it.spring(hertz, dampingRatio) }
    /** A rope: [b] can come closer than [length] (default: as they are now), never farther. */
    fun addRope(a: Body?, anchorA: Vec3, b: Body, anchorB: Vec3, length: Float? = null): DistanceJoint {
        checkJoint(a, b)
        return keep(DistanceJoint(this, a, b, anchorA, anchorB, collideConnected = true, rope = true).also { j -> length?.let { j.length = it } })
    }

    /**
     * A chain of [links] capsules from [from] to [to], joined end to end by ball joints; the first link
     * hangs from [start] (or the world) at [from], the last from [end] at [to] when given. Returns the links.
     */
    fun addChain(from: Vec3, to: Vec3, links: Int, radius: Float = .03f, start: Body? = null, end: Body? = null,
                 density: Float = 2000f, friction: Float = .6f): List<Body> {
        require(links >= 1) { "A chain needs a link" }
        val span = to - from; val length = span.length()
        require(length > 2 * radius * links) { "The links would be shorter than they are wide" }
        val dir = span / length; val step = length / links
        val turn = Quat.fromTo(Vec3.Y, dir)
        val bodies = ArrayList<Body>()
        var previous = start
        for (k in 0 until links) {
            val centre = from + dir * (step * (k + .5f))
            val link = add(Capsule(radius, max(step / 2 - radius, .001f)), centre, turn, density = density, friction = friction)
            if (previous != null || k == 0) addBall(previous, link, from + dir * (step * k))
            bodies.add(link); previous = link
        }
        if (end != null) addBall(end, bodies.last(), to)
        return bodies
    }

    /** Takes [joint] out of the world: its bodies are free again (and collide again if they did not). */
    fun removeJoint(joint: Joint) {
        require(joint.world === this) { "This joint is not in this world" }
        if (joint.isRemoved) return
        wakeJoint(joint)
        joint.isRemoved = true
        joints.remove(joint); noCollideDirty = true
    }

    /** Wakes the bodies of [j] (a setting changed: a motor started, a limit moved). */
    internal fun wakeJoint(j: Joint) {
        noCollideDirty = true
        if (j.ia >= 0 && types[j.ia] == BodyType.DYNAMIC && !removed[j.ia]) wake(j.ia)
        if (types[j.ib] == BodyType.DYNAMIC && !removed[j.ib]) wake(j.ib)
    }

    /** The pairs of bodies a joint keeps from colliding, sorted for a binary search. */
    private fun rebuildNoCollide() {
        noCollideDirty = false
        if (jointed.size < capacity) jointed = BooleanArray(capacity) else jointed.fill(false)
        if (noCollide.size < joints.size) noCollide = LongArray(joints.size * 2)
        var n = 0
        for (j in joints) {
            if (j.collideConnected || !j.enabled || j.ia < 0) continue
            noCollide[n++] = pairKey(j.ia, j.ib); jointed[j.ia] = true; jointed[j.ib] = true
        }
        java.util.Arrays.sort(noCollide, 0, n)
        noCollideCount = n
    }
    private fun pairKey(i: Int, j: Int) = (min(i, j).toLong() shl 32) or max(i, j).toLong()
    internal fun joinedApart(i: Int, j: Int) = noCollideCount > 0 && jointed[i] && jointed[j] &&
        java.util.Arrays.binarySearch(noCollide, 0, noCollideCount, pairKey(i, j)) >= 0

    private var jointParent = IntArray(0); private var jointAwake = BooleanArray(0)
    private fun jointRoot(i: Int): Int { var r = i; while (jointParent[r] != r) { jointParent[r] = jointParent[jointParent[r]]; r = jointParent[r] }; return r }

    /** A body awake (or a kinematic body moving) wakes everything joined to it, however long the chain. */
    private fun wakeJoined() {
        if (jointParent.size < capacity) { jointParent = IntArray(capacity); jointAwake = BooleanArray(capacity) }
        for (j in joints) { if (j.ia >= 0) { jointParent[j.ia] = j.ia; jointAwake[j.ia] = false }; jointParent[j.ib] = j.ib; jointAwake[j.ib] = false }
        for (j in joints) if (j.enabled && j.ia >= 0 && types[j.ia] == BodyType.DYNAMIC && types[j.ib] == BodyType.DYNAMIC) {
            val ra = jointRoot(j.ia); val rb = jointRoot(j.ib); if (ra != rb) jointParent[ra] = rb
        }
        var any = false
        for (j in joints) {
            if (!j.enabled) continue
            for (end in 0..1) {
                val i = if (end == 0) j.ia else j.ib; val other = if (end == 0) j.ib else j.ia
                if (i < 0 || types[i] != BodyType.DYNAMIC) continue
                if (!sleeping[i] || (other >= 0 && types[other] == BodyType.KINEMATIC && moving(other))) { jointAwake[jointRoot(i)] = true; any = true }
            }
        }
        if (!any) return
        for (j in joints) {
            if (j.ia >= 0 && types[j.ia] == BodyType.DYNAMIC && sleeping[j.ia] && jointAwake[jointRoot(j.ia)]) wake(j.ia)
            if (types[j.ib] == BodyType.DYNAMIC && sleeping[j.ib] && jointAwake[jointRoot(j.ib)]) wake(j.ib)
        }
    }

    private val activeJoints = ArrayList<Joint>()
    private fun prepareJoints(h: Float) {
        activeJoints.clear()
        val hertz = min(jointHertz, .25f / h)
        for (j in joints) {
            if (!j.enabled) continue
            val awake = (j.ia >= 0 && types[j.ia] == BodyType.DYNAMIC && !sleeping[j.ia]) || (types[j.ib] == BodyType.DYNAMIC && !sleeping[j.ib])
            if (!awake) continue
            j.prepare(h, hertz, jointDampingRatio)
            activeJoints.add(j)
        }
    }
    private fun warmStartJoints() { for (k in activeJoints.indices) activeJoints[k].warmStart() }
    private fun solveJoints(useBias: Boolean, h: Float) { val invH = 1f / h; for (k in activeJoints.indices) activeJoints[k].solve(useBias, invH) }
    /**
     * The joints' rigid velocity pass, before the soft one in each substep: what the bodies' velocities do to
     * the joints (an impulse, a hit, a contact) is stopped at once, as a real pin would, instead of leaking
     * through the soft step for a substep. The soft pass then corrects the positions as before; springs and
     * motors sit this pass out. A door hit hard enough to spin at 24 rad/s left its hinge by 3.9 mm, now 2.1 mm.
     */
    private fun rigidJoints(h: Float) {
        val invH = 1f / h
        for (k in activeJoints.indices) { val j = activeJoints[k]; j.rigid = true; j.solve(false, invH); j.rigid = false }
    }

    /** Applies a force at the centre during the next step. */
    fun applyForce(body: Body, force: Vec3) { val i = body.id; fx[i] += force.x; fy[i] += force.y; fz[i] += force.z; wake(i) }

    /**
     * Adds a force (N) at [point] for the next step, spread over its substeps like gravity. [steady]: a
     * force applied every step (a suspension, a float) wakes a sleeping body, yet an awake one keeps
     * counting towards sleep.
     */
    internal fun addForce(i: Int, force: Vec3, point: Vec3, steady: Boolean) {
        if (types[i] != BodyType.DYNAMIC) return
        if (!steady || sleeping[i]) wake(i)
        fx[i] += force.x; fy[i] += force.y; fz[i] += force.z
        val rx = point.x - px[i]; val ry = point.y - py[i]; val rz = point.z - pz[i]
        val ax = ry * force.z - rz * force.y; val ay = rz * force.x - rx * force.z; val az = rx * force.y - ry * force.x
        if (ax != 0f || ay != 0f || az != 0f) { tx[i] += ax; ty[i] += ay; tz[i] += az; torqued = true }
    }

    // ------------------------------------------------------------------ inertia

    /** World inverse inertia R · diag(invI) · Rᵀ into the body's solver state. */
    internal fun prepareInertia(i: Int) {
        var x = qx[i]; var y = qy[i]; var z = qz[i]; var w = qw[i]
        if (principal[i]) {
            // The inertia's own axes: the body's rotation times the shape's principal axes.
            val ax = pq[4 * i]; val ay = pq[4 * i + 1]; val az = pq[4 * i + 2]; val aw = pq[4 * i + 3]
            val nx = w * ax + x * aw + y * az - z * ay; val ny = w * ay - x * az + y * aw + z * ax
            val nz = w * az + x * ay - y * ax + z * aw; val nw = w * aw - x * ax - y * ay - z * az
            x = nx; y = ny; z = nz; w = nw
        }
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

    // ------------------------------------------------------------------ water

    internal val waters = ArrayList<WaterVolume>()
    /** Per body: submerged fraction in the last substep, at the end of the step before, its water (−1: none), whether it entered this step. */
    internal var wet = FloatArray(0); private var wetBefore = FloatArray(0); private var entered = BooleanArray(0)
    private var wetRest = BooleanArray(0); private var touched = BooleanArray(0)
    private var wetIn = arrayOfNulls<WaterVolume>(0); private var lastWater = arrayOfNulls<WaterVolume>(0)
    private val wetList = IntList()                  // this step's bodies in water: body, water
    private val buoyancyScratch = BuoyancyScratch()
    private val entries = ArrayList<WaterEntry>()
    private val exits = ArrayList<Body>(); private val exitWater = ArrayList<WaterVolume>()
    /** The bodies that touched a water during the last step, coming from out of it (see [WaterEntry]). */
    val waterEntries: List<WaterEntry> get() = entries

    /** Wakes the sleeping bodies that may be in [w] (its level, bounds or density changed). */
    internal fun wakeWater(w: WaterVolume) {
        for (i in 0 until count) if (types[i] == BodyType.DYNAMIC && !removed[i] && sleeping[i] &&
            (wetIn[i] === w || (box[6 * i + 1] <= w.level + speculativeDistance && w.covers(px[i], pz[i])))) wake(i)
    }

    /** The awake dynamic bodies reaching below a water's level with their centre in its bounds, for this step's substeps. */
    private fun findWet() {
        wetList.clear()
        for (i in 0 until count) {
            if (types[i] != BodyType.DYNAMIC || removed[i] || sleeping[i]) continue
            entered[i] = false
            var water = -1
            for (k in waters.indices) {
                val w = waters[k]
                if (w.enabled && box[6 * i + 1] < w.level && w.covers(px[i], pz[i])) { water = k; break }
            }
            if (water >= 0) { wetList.add(i); wetList.add(water) } else wet[i] = 0f
            wetIn[i] = if (water >= 0) waters[water] else null
        }
    }

    /**
     * Archimedes in one substep: each body in water pushed up by the weight of the water it displaces, at
     * the centre of what it displaces, then its velocity relative to the water and its turning damped by the
     * drags times its submerged fraction (implicitly, as the bodies' own damping: stable at any drag).
     */
    private fun buoyancy(h: Float) {
        val sc = buoyancyScratch; val acc = sc.acc
        val gx = gravity.x; val gy = gravity.y; val gz = gravity.z
        var k = 0
        while (k < wetList.size) {
            val i = wetList[k]; val w = waters[wetList[k + 1]]; k += 2
            val shape = shapes[i]!!
            val pose = sc.pose.set(px[i], py[i], pz[i], qx[i], qy[i], qz[i], qw[i])
            acc[0] = 0f; acc[1] = 0f; acc[2] = 0f; acc[3] = 0f
            submerged(shape, pose, w.level, sc, acc)
            val v = acc[0]
            val f = fractionOf(v, shape.volume)
            val o = i * S
            if (f > 0f && wetBefore[i] == 0f && !entered[i]) {
                entered[i] = true
                entries.add(WaterEntry(Body(this, i), w, Vec3(acc[1] / v, w.level, acc[3] / v), st[o + 1]))
            }
            wet[i] = f
            if (v <= 0f) { wetRest[i] = false; continue }
            // Buoyant force −ρ V g at the centre of buoyancy: an impulse for this substep.
            val m = st[o + IM]
            val s = -w.density * v * h
            val jx = s * gx; val jy = s * gy; val jz = s * gz
            st[o] += jx * m; st[o + 1] += jy * m; st[o + 2] += jz * m
            val rx = acc[1] / v - px[i]; val ry = acc[2] / v - py[i]; val rz = acc[3] / v - pz[i]
            // At rest in the water only when the water carries its weight and holds it upright (centre of
            // buoyancy over the centre of mass): else it may not sleep (it slept at the top of a slow bob).
            val gl = sqrt(gx * gx + gy * gy + gz * gz)
            run {
                val along = if (gl > 0f) (rx * gx + ry * gy + rz * gz) / gl else 0f
                val ux = if (gl > 0f) rx - along * gx / gl else rx; val uy = if (gl > 0f) ry - along * gy / gl else ry; val uz = if (gl > 0f) rz - along * gz / gl else rz
                wetRest[i] = abs(w.density * v * m - 1f) <= 1e-3f && ux * ux + uy * uy + uz * uz <= 1e-6f * shape.boundingRadius * shape.boundingRadius
            }
            val tx = ry * jz - rz * jy; val ty = rz * jx - rx * jz; val tz = rx * jy - ry * jx
            val ii = o + I0
            st[o + 3] += st[ii] * tx + st[ii + 1] * ty + st[ii + 2] * tz
            st[o + 4] += st[ii + 1] * tx + st[ii + 3] * ty + st[ii + 4] * tz
            st[o + 5] += st[ii + 2] * tx + st[ii + 4] * ty + st[ii + 5] * tz
            // Drags, relative to the water's flow: linear (slow motions, the waves a body makes) and quadratic,
            // ½ ρ C_d A |v| v on the cross-section of a ball of the same volume (a dropped ball stops within a
            // few tens of centimetres instead of bouncing out of the water), both implicit.
            val fl = w.flow
            val ux = st[o] - fl.x; val uy = st[o + 1] - fl.y; val uz = st[o + 2] - fl.z
            val speed = sqrt(ux * ux + uy * uy + uz * uz)
            val re = Math.cbrt(.75 * shape.volume / PI).toFloat()
            val quad = .5f * w.density * w.dragCoefficient * PI.toFloat() * re * re * m * f
            val ld = 1f / (1f + h * (w.linearDrag * f + quad * speed)); val ad = 1f / (1f + h * w.angularDrag * f)
            st[o] = fl.x + ux * ld; st[o + 1] = fl.y + uy * ld; st[o + 2] = fl.z + uz * ld
            st[o + 3] *= ad; st[o + 4] *= ad; st[o + 5] *= ad
            // The waves a body makes bobbing and rolling at the surface: on its vertical speed and its turning about
            // horizontal axes, most when half under (as the waterline is widest), nothing once it is all under.
            if (gl > 0f && w.waveDamping > 0f) {
                val wd = 1f / (1f + h * w.waveDamping * 4f * f * (1f - f))
                val nx = gx / gl; val ny = gy / gl; val nz = gz / gl
                val vn = (st[o] - fl.x) * nx + (st[o + 1] - fl.y) * ny + (st[o + 2] - fl.z) * nz
                val dv = vn * (wd - 1f)
                st[o] += dv * nx; st[o + 1] += dv * ny; st[o + 2] += dv * nz
                val wn = st[o + 3] * nx + st[o + 4] * ny + st[o + 5] * nz
                st[o + 3] = wn * nx + (st[o + 3] - wn * nx) * wd; st[o + 4] = wn * ny + (st[o + 4] - wn * ny) * wd; st[o + 5] = wn * nz + (st[o + 5] - wn * nz) * wd
            }
        }
    }

    /** End of a step with water: who left the water altogether, and what each body had under water, for the next step. */
    private fun finishWet() {
        for (i in 0 until count) {
            if (types[i] != BodyType.DYNAMIC || removed[i] || sleeping[i]) continue
            if (wet[i] > 0f) lastWater[i] = wetIn[i]
            else if (wetBefore[i] > 0f) { val w = lastWater[i]; if (w != null && w in waters) { exits.add(Body(this, i)); exitWater.add(w) } }
            wetBefore[i] = wet[i]
        }
    }

    internal val triggerScratch = Touches()
    // Scratch for the queries (single-threaded, not reentrant: a query's filter must not query again).
    internal val queryStack = IntList(); internal val queryFound = IntList(); internal val queryScratch = ContactPoints()
    internal val queryPose = Pose(); internal val ray = RayQuery()
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
        if (noCollideDirty) rebuildNoCollide()
        prepareBodies(dt)
        if (joints.isNotEmpty()) wakeJoined()
        entries.clear(); exits.clear(); exitWater.clear()
        if (waters.isNotEmpty()) findWet() else if (wetList.size > 0) { for (k in 0 until wetList.size step 2) wet[wetList[k]] = 0f; wetList.clear() }
        lap(0)
        findPairs(); lap(1)
        collide(dt); lap(2)
        prepareContacts(h)
        prepareJoints(h); lap(3)
        wideActive = vectorized && wideSolver()?.prepare() == true
        // Joints before contacts in each pass: the contacts have the last word, nothing sinks into the ground.
        repeat(substeps) {
            integrateVelocities(h)
            if (wetList.size > 0) buoyancy(h)
            warmStartJoints(); warmStart(); lap(4)
            rigidJoints(h)
            solveJoints(true, h); solve(h, true); lap(5)
            integratePositions(h)
            solveJoints(false, h); solve(h, false); lap(6)
        }
        if (wideActive) wide!!.finish()
        restitute()
        if (continuous) continuous()
        updateSleep(dt); lap(7)
        if (waters.isNotEmpty() || wetList.size > 0) finishWet()
        for (i in 0 until count) { fx[i] = 0f; fy[i] = 0f; fz[i] = 0f }
        if (torqued) { java.util.Arrays.fill(tx, 0, count, 0f); java.util.Arrays.fill(ty, 0, count, 0f); java.util.Arrays.fill(tz, 0, count, 0f); torqued = false }
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
        if (entries.isNotEmpty() || exits.isNotEmpty()) {
            for (e in entries.toList()) e.water.onEnter?.invoke(e)
            for (k in exits.indices) exitWater[k].onExit?.invoke(exits[k])
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
        val pose = poses[i]
        if (pose.px != px[i] || pose.py != py[i] || pose.pz != pz[i] ||
            q0[4 * i] != x || q0[4 * i + 1] != y || q0[4 * i + 2] != z || q0[4 * i + 3] != w) pose.set(px[i], py[i], pz[i], x, y, z, w)
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
                ex = abs(pose.xx) * s.halfX + abs(pose.yx) * s.halfY + abs(pose.zx) * s.halfZ
                ey = abs(pose.xy) * s.halfX + abs(pose.yy) * s.halfY + abs(pose.zy) * s.halfZ
                ez = abs(pose.xz) * s.halfX + abs(pose.yz) * s.halfY + abs(pose.zz) * s.halfZ
            }
            is Capsule -> { ex = abs(pose.yx) * s.halfHeight + s.radius; ey = abs(pose.yy) * s.halfHeight + s.radius; ez = abs(pose.yz) * s.halfHeight + s.radius }
            is ConvexHull, is Compound -> {
                // The turned box of the shape's own bounds (they need not be centred on the centre of mass).
                val b = 6 * i
                localBounds(s, pose, box, b)
                val m = speculativeDistance + speed(i) * dt
                box[b] -= m; box[b + 1] -= m; box[b + 2] -= m; box[b + 3] += m; box[b + 4] += m; box[b + 5] += m
                return
            }
        }
        val m = speculativeDistance + speed(i) * dt
        ex += m; ey += m; ez += m
        val b = 6 * i
        box[b] = pose.px - ex; box[b + 1] = pose.py - ey; box[b + 2] = pose.pz - ez
        box[b + 3] = pose.px + ex; box[b + 4] = pose.py + ey; box[b + 5] = pose.pz + ez
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
        return (moving(i) || moving(j)) && !joinedApart(i, j)
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
        collide(shapes[i]!!, poses[i], shapes[j]!!, poses[j], margin, scratch)
        // A kinematic body sets the bounce of what it touches: a character is soft (0), a paddle may be bouncy.
        val e = when {
            types[i] == BodyType.KINEMATIC -> restitution[i]
            types[j] == BodyType.KINEMATIC -> restitution[j]
            else -> max(restitution[i], restitution[j])
        }
        if (scratch.count > 0) n.set.addAll(scratch, i, j, (i.toLong() shl 32) or j.toLong(),
            sqrt(friction[i] * friction[j]), e, poses[j], previous)
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
            scratch.clear()
            collideTriangle(mesh, t, shapes[b]!!, poses[b], margin, scratch)
            val surface = mesh.palette[mesh.material[t]]
            if (scratch.count > 0) n.set.addAll(scratch, -1, b,
                Long.MIN_VALUE or (m.toLong() shl 55) or (b.toLong() shl 24) or t.toLong(),
                sqrt(surface.friction * friction[b]), restitution[b] * surface.bounce, poses[b], previous,
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
            // The parts of a compound share the pair's patches: a table's four legs on the floor are one patch,
            // its friction at the middle of the four (as a box's at the middle of its face).
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
            if (torqued) {
                val k = o + I0; val ax = tx[i]; val ay = ty[i]; val az = tz[i]
                st[o + 3] += h * (st[k] * ax + st[k + 1] * ay + st[k + 2] * az)
                st[o + 4] += h * (st[k + 1] * ax + st[k + 3] * ay + st[k + 4] * az)
                st[o + 5] += h * (st[k + 2] * ax + st[k + 4] * ay + st[k + 5] * az)
            }
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
        var x = qx[i]; var y = qy[i]; var z = qz[i]; var w = qw[i]
        if (principal[i]) {
            val ax = pq[4 * i]; val ay = pq[4 * i + 1]; val az = pq[4 * i + 2]; val aw = pq[4 * i + 3]
            val nx = w * ax + x * aw + y * az - z * ay; val ny = w * ay - x * az + y * aw + z * ax
            val nz = w * az + x * ay - y * ax + z * aw; val nw = w * aw - x * ax - y * ay - z * az
            x = nx; y = ny; z = nz; w = nw
        }
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
    private fun warmStart() { if (wideActive) wide!!.warmStart() else { patchJob = warmJob; forPatches(warmJob) } }

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
        if (wideActive) wide!!.solve(useBias, solveInvH, solveHidden, solveG)
        else { patchJob = solveJob; forPatches(solveJob) }
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

    // ------------------------------------------------------------------ continuous collision

    /**
     * Continuous collision for fast bodies (on by default): sweeps put back a body that went through
     * something during the step. Off, only the speculative contacts stop fast bodies.
     */
    var continuous = true
    private val fast = IntList()
    private val still = IntList()                 // static and kinematic bodies: what a fast body is swept against

    /**
     * Continuous collision, the safety net under the speculative contacts: a body that moved more than its
     * core (half its smallest half-size) during the step is swept from where it was to where it is, its
     * core as a sphere, against what does not move (and against every body for a [Body.isBullet]). If it
     * went through something, it is put back just before it, its velocity kept: the next step's contacts
     * then stop it (Box2D v3's way). A sweep that starts inside something is ignored.
     */
    private fun continuous() {
        fast.clear(); still.clear()
        val hitsBefore = continuousHits
        for (i in 0 until count) {
            if (removed[i]) continue
            if (types[i] != BodyType.DYNAMIC) { still.add(i); continue }
            if (sleeping[i]) continue
            val o = i * S
            val dx = st[o + DPX]; val dy = st[o + DPX + 1]; val dz = st[o + DPX + 2]
            val core = core(i)
            if (dx * dx + dy * dy + dz * dz > core * core) fast.add(i)
        }
        val anythingStill = still.size > 0 || meshes.isNotEmpty()
        for (k in 0 until fast.size) {
            val i = fast[k]; val o = i * S
            if (!bullet[i] && !anythingStill) continue            // nothing that stays put to go through
            val dx = st[o + DPX]; val dy = st[o + DPX + 1]; val dz = st[o + DPX + 2]
            val travel = sqrt(dx * dx + dy * dy + dz * dz)
            val ux = dx / travel; val uy = dy / travel; val uz = dz / travel
            val fx = px[i] - dx; val fy = py[i] - dy; val fz = pz[i] - dz
            val targets = if (bullet[i]) null else still
            val shape = shapes[i]!!
            var t: Float
            if (shape is Compound) {
                // Each part's core from where it was (moved along, not turned), the first hit for the whole body.
                t = Float.NaN
                val pose = ccdPose.set(px[i], py[i], pz[i], qx[i], qy[i], qz[i], qw[i])
                for (j in 0 until shape.count) {
                    val part = ccdPart.setPart(pose, shape, j)
                    val h = sweepCore(part.px - dx, part.py - dy, part.pz - dz, ux, uy, uz, travel, .5f * coreOf(shape.shapes[j]), i, targets)
                    if (!h.isNaN() && (t.isNaN() || h < t)) t = h
                }
            } else t = sweepCore(fx, fy, fz, ux, uy, uz, travel, core(i), i, targets)
            if (t.isNaN() || t <= 0f || t >= travel) continue
            val back = max(0f, t - CCD_SLOP)
            px[i] = fx + ux * back; py[i] = fy + uy * back; pz[i] = fz + uz * back
            continuousHits++
        }
        if (continuousHits != hitsBefore) queryGrid.valid = false
    }

    /** Sweeps that put a body back since the world was made (diagnostics). */
    var continuousHits = 0L
        private set

    private fun core(i: Int): Float = .5f * coreOf(shapes[i]!!)
    private val ccdPose = Pose(); private val ccdPart = Pose()

    /** The radius of the largest ball about the centre inside the shape (a compound: its smallest part's). */
    private fun coreOf(s: Shape): Float = when (s) {
        is Sphere -> s.radius
        is Capsule -> s.radius
        is Box -> min(s.halfX, min(s.halfY, s.halfZ))
        is ConvexHull -> s.inradius
        is Compound -> { var c = Float.MAX_VALUE; for (j in 0 until s.count) c = min(c, coreOf(s.shapes[j])); c }
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
        if (wetList.size > 0) {
            // A body floating free sleeps only at rest in the water (its weight carried, upright).
            for (k in 0 until c.size) if (d[k * C + CLN] > 0f) { if (c.a[k] >= 0) touched[c.a[k]] = true; touched[c.b[k]] = true }
            for (k in 0 until wetList.size step 2) { val i = wetList[k]; if (wet[i] > 0f && !touched[i] && !wetRest[i]) sleepClock[i] = 0f }
            for (k in 0 until c.size) { if (c.a[k] >= 0) touched[c.a[k]] = false; touched[c.b[k]] = false }
        }
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
        // Joined bodies sleep together; a moving kinematic body keeps what it holds awake.
        for (j in joints) {
            if (!j.enabled || j.ia < 0) continue
            val a = j.ia; val b = j.ib
            if (types[a] == BodyType.DYNAMIC && types[b] == BodyType.DYNAMIC) { val ra = root(a); val rb = root(b); if (ra != rb) parent[ra] = rb }
            else if (types[a] == BodyType.KINEMATIC && moving(a)) sleepClock[b] = 0f
            else if (types[b] == BodyType.KINEMATIC && moving(b)) sleepClock[a] = 0f
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

    // ------------------------------------------------------------------ the solver on SIMD lanes

    /**
     * Solves large scenes on SIMD lanes when the JDK's vector module is loaded (`--add-modules
     * jdk.incubator.vector`): the patches of one colour share no moving body, so a batch of them — 8 with
     * AVX2 — is solved at once, one patch per lane. Each lane does the scalar solver's very operations in
     * the same order, so the result is the same to the bit with or without the module, with or without
     * threads. Without the module, or set to false (`-Dsillage.vector=false`), the scalar solver runs.
     */
    var vectorized = System.getProperty("sillage.vector") != "false"

    private var wide: WideContacts? = null
    private var wideTried = false
    private var wideActive = false                // this step's coloured patches are in the wide batches

    /** The wide solver, made by name the first time (its class uses the vector module: never loaded without it). */
    private fun wideSolver(): WideContacts? {
        if (!wideTried) {
            wideTried = true
            if (ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent) wide = try {
                Class.forName("sillage.physics.PhysicsWorld\$Wide").getDeclaredConstructor(PhysicsWorld::class.java).newInstance(this) as WideContacts
            } catch (e: Throwable) { System.err.println("Sillage: no vector solver (" + e + ")"); null }
        }
        return wide
    }

    /** Whether the last step solved its contacts on SIMD lanes. */
    val lastStepVectorized: Boolean get() = wideActive

    /**
     * Batches of the patches of each colour, their data laid out lane by lane (structure of arrays): the
     * patch rows, the points slot by slot (a batch has as many slots as its longest patch has points; the
     * shorter ones are masked), the two bodies' state. Built once per step, read by the 12 passes of its
     * substeps; the impulses go back to the contact sets at the end ([finish]).
     */
    internal inner class Wide : WideContacts {
        // The species must be a constant (a static final field) for C2 to turn the vector code into SIMD
        // instructions: read from an instance field, every operation fell back to Java code, 30 times slower.
        private inline val sp: VectorSpecies<Float> get() = WideLanes.SPECIES
        private val n = sp.length()
        private var batches = 0
        private val colourBatch = IntArray(MAX_COLOURS + 1)  // first batch of each colour, then the end
        private var first = IntArray(0)          // per batch: its first patch in [ordered]
        private var lanes = IntArray(0)          // per batch: its patches (n at most)
        private var slots = IntArray(0)          // per batch: points of its longest patch
        private var slotBase = IntArray(0)       // per batch: its first slot
        private var aOff = IntArray(0); private var bOff = IntArray(0)   // per lane: body offsets in st (−1: none)
        private var rotA = BooleanArray(0); private var rotB = BooleanArray(0)   // per lane: anchors turn with the body
        private var live = BooleanArray(0)       // per slot and lane: a real point
        private var wp = FloatArray(0)           // per batch: PW vectors
        private var wc = FloatArray(0)           // per slot: CW vectors
        private var bb = FloatArray(0)           // per batch: 2 × S vectors (body a, then b)
        private var sorted = IntArray(0)
        private val buckets = IntArray(SORT_BUCKETS + 1)
        private val scalarStart = IntArray(MAX_COLOURS)      // per colour: its patches scaled for support, at the end of its run, scalar
        private var isScaled = BooleanArray(0)
        private var hs = FloatArray(0)                       // per batch: load, twist cap, reserved grip of each lane (hidden loads)

        override fun prepare(): Boolean {
            if (colours == 0) return false
            val ps = patches
            if (isScaled.size < ps.size) isScaled = BooleanArray(ps.size * 2) else isScaled.fill(false, 0, ps.size)
            for (k in 0 until scaled.size) isScaled[scaled[k]] = true
            var g = 0; var slotsTotal = 0
            for (c in 0 until MAX_COLOURS) {
                colourBatch[c] = g
                val from = colourStart[c]; val to = colourStart[c + 1]
                val wideTo = sortByPoints(from, to)
                scalarStart[c] = wideTo
                var i = from
                while (i < wideTo) {
                    val k = min(n, wideTo - i)
                    var most = 0
                    for (l in 0 until k) { val q = ordered[i + l]; most = max(most, ps.end[q] - ps.start[q]) }
                    if (g == first.size) grow(g + 1)
                    first[g] = i; lanes[g] = k; slots[g] = most; slotBase[g] = slotsTotal
                    slotsTotal += most; g++; i += k
                }
            }
            colourBatch[MAX_COLOURS] = g
            batches = g
            if (wp.size < g * PW * n) wp = FloatArray(g * PW * n * 3 / 2)
            if (bb.size < g * 2 * S * n) bb = FloatArray(g * 2 * S * n * 3 / 2)
            if (aOff.size < g * n) { aOff = IntArray(g * n * 3 / 2); bOff = IntArray(aOff.size); rotA = BooleanArray(aOff.size); rotB = BooleanArray(aOff.size) }
            if (wc.size < slotsTotal * CW * n) wc = FloatArray(slotsTotal * CW * n * 3 / 2)
            if (live.size < slotsTotal * n) live = BooleanArray(slotsTotal * n * 3 / 2)
            if (hs.size < g * 3 * n) hs = FloatArray(g * 3 * n * 3 / 2)
            forChunks(g, 8, g >= 16, fillJob)
            return true
        }

        private fun grow(size: Int) {
            val c = max(64, size * 2)
            first = first.copyOf(c); lanes = lanes.copyOf(c); slots = slots.copyOf(c); slotBase = slotBase.copyOf(c)
        }

        /**
         * Orders the patches of one colour by their number of points (fewer masked slots in each batch), those
         * scaled for support last: they stay scalar. Returns where they start. The order within a colour does not
         * change the result: its patches share no moving body.
         */
        private fun sortByPoints(from: Int, to: Int): Int {
            val ps = patches
            if (to - from < 1) return to
            buckets.fill(0)
            fun bucket(q: Int) = if (isScaled[q]) SORT_BUCKETS else min(ps.end[q] - ps.start[q], SORT_BUCKETS - 1)
            for (i in from until to) buckets[bucket(ordered[i])]++
            val scaledCount = buckets[SORT_BUCKETS]
            var at = 0
            for (k in 0..SORT_BUCKETS) { val c = buckets[k]; buckets[k] = at; at += c }
            if (sorted.size < to - from) sorted = IntArray((to - from) * 2)
            for (i in from until to) { val q = ordered[i]; sorted[buckets[bucket(q)]++] = q }
            System.arraycopy(sorted, 0, ordered, from, to - from)
            return to - scaledCount
        }

        private val fillJob = Job { from, to -> for (g in from until to) fill(g) }

        private fun fill(g: Int) {
            val ps = patches; val pd = ps.d; val d = points.d; val st = st
            val n = n; val k = lanes[g]; val sb = slotBase[g]; val slotCount = slots[g]
            for (l in 0 until n) {
                val lane = g * n + l
                if (l < k) {
                    val q = ordered[first[g] + l]; val a = ps.a[q]; val b = ps.b[q]
                    val k0 = ps.start[q]; val points = ps.end[q] - k0
                    aOff[lane] = if (a >= 0) a * S else -1; bOff[lane] = b * S
                    rotA[lane] = a >= 0 && !round[a]; rotB[lane] = !round[b]
                    for (f in 0 until PW) {
                        val src = PATCH_SOURCE[f]
                        wp[(g * PW + f) * n + l] = if (src >= 0) pd[q * P + src] else d[k0 * C + (if (src == -1) CSA else CSB)]
                    }
                    for (j in 0 until slotCount) {
                        val s = sb + j
                        if (j < points) {
                            val o = (k0 + j) * C
                            for (f in 0 until CW) wc[(s * CW + f) * n + l] = d[o + POINT_SOURCE[f]]
                            live[s * n + l] = true
                        } else {
                            for (f in 0 until CW) wc[(s * CW + f) * n + l] = 0f
                            live[s * n + l] = false
                        }
                    }
                    // Mass and inverse inertia: they do not change during the step.
                    for (f in IM..I0 + 5) {
                        bb[((g * 2) * S + f) * n + l] = if (a >= 0) st[a * S + f] else 0f
                        bb[((g * 2 + 1) * S + f) * n + l] = st[b * S + f]
                    }
                } else {
                    aOff[lane] = -1; bOff[lane] = -1; rotA[lane] = false; rotB[lane] = false
                    for (f in 0 until PW) wp[(g * PW + f) * n + l] = 0f
                    for (j in 0 until slotCount) { val s = sb + j; for (f in 0 until CW) wc[(s * CW + f) * n + l] = 0f; live[s * n + l] = false }
                    for (f in 0 until 2 * S) bb[(g * 2 * S + f) * n + l] = 0f
                }
            }
        }

        /** The two bodies' velocities (and, for the solve, their motion since the step began) into the batch. */
        private fun gather(g: Int, pose: Boolean) {
            val st = st; val n = n; val k = lanes[g]
            for (l in 0 until k) {
                val lane = g * n + l
                val a = aOff[lane]; val b = bOff[lane]
                val ba = (g * 2) * S * n + l; val bbo = (g * 2 + 1) * S * n + l
                for (f in 0..5) { bb[ba + f * n] = if (a >= 0) st[a + f] else 0f; bb[bbo + f * n] = st[b + f] }
                if (pose) for (f in DPX..DQW) { bb[ba + f * n] = if (a >= 0) st[a + f] else if (f == DQW) 1f else 0f; bb[bbo + f * n] = st[b + f] }
            }
        }

        /** Back to the bodies, those with mass only (as the scalar solver). */
        private fun scatter(g: Int) {
            val st = st; val n = n; val k = lanes[g]
            for (l in 0 until k) {
                val lane = g * n + l
                val a = aOff[lane]; val b = bOff[lane]
                val ba = (g * 2) * S * n + l; val bbo = (g * 2 + 1) * S * n + l
                if (a >= 0 && bb[ba + IM * n] > 0f) for (f in 0..5) st[a + f] = bb[ba + f * n]
                if (bb[bbo + IM * n] > 0f) for (f in 0..5) st[b + f] = bb[bbo + f * n]
            }
        }

        // ---------------------------------------------------------------- warm start

        override fun warmStart() {
            for (c in 0 until MAX_COLOURS) {
                val from = colourBatch[c]; val to = colourBatch[c + 1]
                if (from < to) { batchBase = from; forChunks(to - from, WIDE_CHUNK, true, warmJob) }
                for (i in scalarStart[c] until colourStart[c + 1]) warmStartPatch(ordered[i])
            }
            val from = colourStart[MAX_COLOURS]; val to = colourStart[MAX_COLOURS + 1]
            for (i in from until to) warmStartPatch(ordered[i])
        }
        @Volatile private var batchBase = 0
        // Each pass is cut in methods small enough for C2 (under 8000 bytes of bytecode, else it is never
        // compiled and the vector operations stay on their slow Java path); they hand the velocities to each
        // other through the batch's array [bb], which stays in the L1 cache.

        private val warmJob = Job { from, to -> val b0 = batchBase; for (g in from until to) { gather(b0 + g, false); warmSlots(b0 + g); warmPatch(b0 + g); scatter(b0 + g) } }

        /** The point slots of the warm start: last step's normal impulses and rolling torques. */
        private fun warmSlots(g: Int) {
            val sp = sp; val n = n; val bb = bb; val wc = wc
            val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
            var vax = vec(bb, oa); var vay = vec(bb, oa + n); var vaz = vec(bb, oa + 2 * n)
            var wax = vec(bb, oa + 3 * n); var way = vec(bb, oa + 4 * n); var waz = vec(bb, oa + 5 * n)
            val mA = vec(bb, oa + IM * n)
            val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
            val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
            var vbx = vec(bb, ob); var vby = vec(bb, ob + n); var vbz = vec(bb, ob + 2 * n)
            var wbx = vec(bb, ob + 3 * n); var wby = vec(bb, ob + 4 * n); var wbz = vec(bb, ob + 5 * n)
            val mB = vec(bb, ob + IM * n)
            val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
            val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
            val zero = FloatVector.zero(sp)
            val heavyA = mA.compare(VectorOperators.GT, zero); val heavyB = mB.compare(VectorOperators.GT, zero)
            for (j in 0 until slots[g]) {
            val s = slotBase[g] + j; val o = s * CW * n
            val here = VectorMask.fromArray(sp, live, s * n)
            val massA = here.and(heavyA); val massB = here.and(heavyB)
            val ln = vec(wc, o + WC_LN * n)
            val px = vec(wc, o + WC_N * n) * ln; val py = vec(wc, o + (WC_N + 1) * n) * ln; val pz = vec(wc, o + (WC_N + 2) * n) * ln
            run {
                val f = vec(wc, o + WC_SA * n); val qx = px * f; val qy = py * f; val qz = pz * f
                vax = vax.blend(vax - qx * mA, massA); vay = vay.blend(vay - qy * mA, massA); vaz = vaz.blend(vaz - qz * mA, massA)
                val rx = vec(wc, o + WC_RA * n); val ry = vec(wc, o + (WC_RA + 1) * n); val rz = vec(wc, o + (WC_RA + 2) * n)
                val cx = ry * qz - rz * qy; val cy = rz * qx - rx * qz; val cz = rx * qy - ry * qx
                wax = wax.blend(wax - (ia0 * cx + ia1 * cy + ia2 * cz), massA)
                way = way.blend(way - (ia1 * cx + ia3 * cy + ia4 * cz), massA)
                waz = waz.blend(waz - (ia2 * cx + ia4 * cy + ia5 * cz), massA)
            }
            run {
                val f = vec(wc, o + WC_SB * n); val qx = px * f; val qy = py * f; val qz = pz * f
                vbx = vbx.blend(vbx + qx * mB, massB); vby = vby.blend(vby + qy * mB, massB); vbz = vbz.blend(vbz + qz * mB, massB)
                val rx = vec(wc, o + WC_RB * n); val ry = vec(wc, o + (WC_RB + 1) * n); val rz = vec(wc, o + (WC_RB + 2) * n)
                val cx = ry * qz - rz * qy; val cy = rz * qx - rx * qz; val cz = rx * qy - ry * qx
                wbx = wbx.blend(wbx + (ib0 * cx + ib1 * cy + ib2 * cz), massB)
                wby = wby.blend(wby + (ib1 * cx + ib3 * cy + ib4 * cz), massB)
                wbz = wbz.blend(wbz + (ib2 * cx + ib4 * cy + ib5 * cz), massB)
            }
            val roll = here.and(vec(wc, o + WC_RL * n).compare(VectorOperators.GT, zero))
            if (roll.anyTrue()) {
                val lx = vec(wc, o + WC_LR * n); val ly = vec(wc, o + (WC_LR + 1) * n); val lz = vec(wc, o + (WC_LR + 2) * n)
                val ma = roll.and(massA); val mb = roll.and(massB)
                wax = wax.blend(wax - (ia0 * lx + ia1 * ly + ia2 * lz), ma); way = way.blend(way - (ia1 * lx + ia3 * ly + ia4 * lz), ma)
                waz = waz.blend(waz - (ia2 * lx + ia4 * ly + ia5 * lz), ma)
                wbx = wbx.blend(wbx + (ib0 * lx + ib1 * ly + ib2 * lz), mb); wby = wby.blend(wby + (ib1 * lx + ib3 * ly + ib4 * lz), mb)
                wbz = wbz.blend(wbz + (ib2 * lx + ib4 * ly + ib5 * lz), mb)
            }
            }
            vax.intoArray(bb, oa); vay.intoArray(bb, oa + n); vaz.intoArray(bb, oa + 2 * n)
            wax.intoArray(bb, oa + 3 * n); way.intoArray(bb, oa + 4 * n); waz.intoArray(bb, oa + 5 * n)
            vbx.intoArray(bb, ob); vby.intoArray(bb, ob + n); vbz.intoArray(bb, ob + 2 * n)
            wbx.intoArray(bb, ob + 3 * n); wby.intoArray(bb, ob + 4 * n); wbz.intoArray(bb, ob + 5 * n)
        }

        /** The friction and twist of the patch, from the last step. */
        private fun warmPatch(g: Int) {
            val sp = sp; val n = n; val bb = bb; val wp = wp
            val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
            var vax = vec(bb, oa); var vay = vec(bb, oa + n); var vaz = vec(bb, oa + 2 * n)
            var wax = vec(bb, oa + 3 * n); var way = vec(bb, oa + 4 * n); var waz = vec(bb, oa + 5 * n)
            val mA = vec(bb, oa + IM * n)
            val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
            val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
            var vbx = vec(bb, ob); var vby = vec(bb, ob + n); var vbz = vec(bb, ob + 2 * n)
            var wbx = vec(bb, ob + 3 * n); var wby = vec(bb, ob + 4 * n); var wbz = vec(bb, ob + 5 * n)
            val mB = vec(bb, ob + IM * n)
            val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
            val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
            val zero = FloatVector.zero(sp)
            val massA = mA.compare(VectorOperators.GT, zero); val massB = mB.compare(VectorOperators.GT, zero)
            val p = g * PW * n
            val l1 = vec(wp, p + WP_L1 * n); val l2 = vec(wp, p + WP_L2 * n); val lt = vec(wp, p + WP_LT * n)
            val sa = vec(wp, p + WP_SA * n); val sb = vec(wp, p + WP_SB * n)
            val px = vec(wp, p + WP_T1 * n) * l1 + vec(wp, p + WP_T2 * n) * l2
            val py = vec(wp, p + (WP_T1 + 1) * n) * l1 + vec(wp, p + (WP_T2 + 1) * n) * l2
            val pz = vec(wp, p + (WP_T1 + 2) * n) * l1 + vec(wp, p + (WP_T2 + 2) * n) * l2
            val tx = vec(wp, p + WP_N * n) * lt; val ty = vec(wp, p + (WP_N + 1) * n) * lt; val tz = vec(wp, p + (WP_N + 2) * n) * lt
            run {
                val m = mA * sa
                vax = vax.blend(vax - px * m, massA); vay = vay.blend(vay - py * m, massA); vaz = vaz.blend(vaz - pz * m, massA)
                val rx = vec(wp, p + WP_RA * n); val ry = vec(wp, p + (WP_RA + 1) * n); val rz = vec(wp, p + (WP_RA + 2) * n)
                val cx = (ry * pz - rz * py) * sa + tx * sa; val cy = (rz * px - rx * pz) * sa + ty * sa; val cz = (rx * py - ry * px) * sa + tz * sa
                wax = wax.blend(wax - (ia0 * cx + ia1 * cy + ia2 * cz), massA)
                way = way.blend(way - (ia1 * cx + ia3 * cy + ia4 * cz), massA)
                waz = waz.blend(waz - (ia2 * cx + ia4 * cy + ia5 * cz), massA)
            }
            run {
                val m = mB * sb
                vbx = vbx.blend(vbx + px * m, massB); vby = vby.blend(vby + py * m, massB); vbz = vbz.blend(vbz + pz * m, massB)
                val rx = vec(wp, p + WP_RB * n); val ry = vec(wp, p + (WP_RB + 1) * n); val rz = vec(wp, p + (WP_RB + 2) * n)
                val cx = (ry * pz - rz * py) * sb + tx * sb; val cy = (rz * px - rx * pz) * sb + ty * sb; val cz = (rx * py - ry * px) * sb + tz * sb
                wbx = wbx.blend(wbx + (ib0 * cx + ib1 * cy + ib2 * cz), massB)
                wby = wby.blend(wby + (ib1 * cx + ib3 * cy + ib4 * cz), massB)
                wbz = wbz.blend(wbz + (ib2 * cx + ib4 * cy + ib5 * cz), massB)
            }
            vax.intoArray(bb, oa); vay.intoArray(bb, oa + n); vaz.intoArray(bb, oa + 2 * n)
            wax.intoArray(bb, oa + 3 * n); way.intoArray(bb, oa + 4 * n); waz.intoArray(bb, oa + 5 * n)
            vbx.intoArray(bb, ob); vby.intoArray(bb, ob + n); vbz.intoArray(bb, ob + 2 * n)
            wbx.intoArray(bb, ob + 3 * n); wby.intoArray(bb, ob + 4 * n); wbz.intoArray(bb, ob + 5 * n)
        }

        // ---------------------------------------------------------------- solve

        @Volatile private var useBias = false
        @Volatile private var invH = 0f
        @Volatile private var anyHidden = false
        @Volatile private var gl = 0f
        private val solveJob = Job { from, to ->
            val b0 = batchBase
            for (i in from until to) {
                val g = b0 + i
                gather(g, true)
                normalSlots(g)
                frictionStart(g); frictionRow(g, 0); frictionRow(g, 1); twist(g)
                rolling(g)
                scatter(g)
            }
        }

        override fun solve(useBias: Boolean, invH: Float, anyHidden: Boolean, gl: Float) {
            this.useBias = useBias; this.invH = invH; this.anyHidden = anyHidden; this.gl = gl
            for (c in 0 until MAX_COLOURS) {
                val from = colourBatch[c]; val to = colourBatch[c + 1]
                if (from < to) { batchBase = from; forChunks(to - from, WIDE_CHUNK, true, solveJob) }
                for (i in scalarStart[c] until colourStart[c + 1]) solvePatch(ordered[i], useBias, invH, anyHidden, gl)
            }
            val from = colourStart[MAX_COLOURS]; val to = colourStart[MAX_COLOURS + 1]
            for (i in from until to) solvePatch(ordered[i], useBias, invH, anyHidden, gl)
        }

        /** The normal points, slot after slot: soft non-penetration, separation from the motion since the step began. */
        private fun normalSlots(g: Int) {
            val sp = sp; val n = n; val bb = bb; val wc = wc
            val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
            val dax = vec(bb, oa + DPX * n); val day = vec(bb, oa + (DPX + 1) * n); val daz = vec(bb, oa + (DPX + 2) * n)
            val qax = vec(bb, oa + DQX * n); val qay = vec(bb, oa + (DQX + 1) * n); val qaz = vec(bb, oa + (DQX + 2) * n); val qaw = vec(bb, oa + DQW * n)
            val dbx = vec(bb, ob + DPX * n); val dby = vec(bb, ob + (DPX + 1) * n); val dbz = vec(bb, ob + (DPX + 2) * n)
            val qbx = vec(bb, ob + DQX * n); val qby = vec(bb, ob + (DQX + 1) * n); val qbz = vec(bb, ob + (DQX + 2) * n); val qbw = vec(bb, ob + DQW * n)
            val zero = FloatVector.zero(sp); val one = FloatVector.broadcast(sp, 1f); val two = FloatVector.broadcast(sp, 2f)
            val turnA = VectorMask.fromArray(sp, rotA, g * n); val turnB = VectorMask.fromArray(sp, rotB, g * n)
            val anyTurnA = turnA.anyTrue(); val anyTurnB = turnB.anyTrue()
            for (j in 0 until slots[g]) {
            val s = slotBase[g] + j; val o = s * CW * n
            val here = VectorMask.fromArray(sp, live, s * n)
            // Velocities read again at each point: the last one's push changed them (in [bb]).
            val vax = vec(bb, oa); val vay = vec(bb, oa + n); val vaz = vec(bb, oa + 2 * n)
            val wax = vec(bb, oa + 3 * n); val way = vec(bb, oa + 4 * n); val waz = vec(bb, oa + 5 * n)
            val vbx = vec(bb, ob); val vby = vec(bb, ob + n); val vbz = vec(bb, ob + 2 * n)
            val wbx = vec(bb, ob + 3 * n); val wby = vec(bb, ob + 4 * n); val wbz = vec(bb, ob + 5 * n)
            val nx = vec(wc, o + WC_N * n); val ny = vec(wc, o + (WC_N + 1) * n); val nz = vec(wc, o + (WC_N + 2) * n)
            val rax = vec(wc, o + WC_RA * n); val ray = vec(wc, o + (WC_RA + 1) * n); val raz = vec(wc, o + (WC_RA + 2) * n)
            val rbx = vec(wc, o + WC_RB * n); val rby = vec(wc, o + (WC_RB + 1) * n); val rbz = vec(wc, o + (WC_RB + 2) * n)
            // Anchors turned by each body's rotation since the step began (a sphere keeps its anchor).
            var cbx = rbx; var cby = rby; var cbz = rbz
            if (anyTurnB) {
                val tx = two * (qby * rbz - qbz * rby); val ty = two * (qbz * rbx - qbx * rbz); val tz = two * (qbx * rby - qby * rbx)
                cbx = rbx.blend(rbx + qbw * tx + (qby * tz - qbz * ty), turnB)
                cby = rby.blend(rby + qbw * ty + (qbz * tx - qbx * tz), turnB)
                cbz = rbz.blend(rbz + qbw * tz + (qbx * ty - qby * tx), turnB)
            }
            var cax = rax; var cay = ray; var caz = raz
            if (anyTurnA) {
                val tx = two * (qay * raz - qaz * ray); val ty = two * (qaz * rax - qax * raz); val tz = two * (qax * ray - qay * rax)
                cax = rax.blend(rax + qaw * tx + (qay * tz - qaz * ty), turnA)
                cay = ray.blend(ray + qaw * ty + (qaz * tx - qax * tz), turnA)
                caz = raz.blend(raz + qaw * tz + (qax * ty - qay * tx), turnA)
            }
            val sep = (dbx - dax + cbx - cax) * nx + (dby - day + cby - cay) * ny + (dbz - daz + cbz - caz) * nz + vec(wc, o + WC_ADJ * n)
            val apart = sep.compare(VectorOperators.GT, zero)
            val toward = sep * FloatVector.broadcast(sp, invH)
            val bias = if (useBias) (vec(wc, o + WC_BIAS * n) * sep).max(FloatVector.broadcast(sp, -contactSpeed)).blend(toward, apart) else zero.blend(toward, apart)
            val massScale = if (useBias) vec(wc, o + WC_MS * n).blend(one, apart) else one
            val impulseScale = if (useBias) vec(wc, o + WC_IS * n).blend(zero, apart) else zero
            val rvx = vbx + wby * rbz - wbz * rby - (vax + way * raz - waz * ray)
            val rvy = vby + wbz * rbx - wbx * rbz - (vay + waz * rax - wax * raz)
            val rvz = vbz + wbx * rby - wby * rbx - (vaz + wax * ray - way * rax)
            val vn = rvx * nx + rvy * ny + rvz * nz
            val old = vec(wc, o + WC_LN * n)
            val total = (old - vec(wc, o + WC_NM * n) * massScale * (vn + bias) - impulseScale * old).max(zero)
            val impulse = total - old
            old.blend(total, here).intoArray(wc, o + WC_LN * n)
            val maxn = vec(wc, o + WC_MAXN * n)
            maxn.blend(impulse, here.and(impulse.compare(VectorOperators.GT, maxn))).intoArray(wc, o + WC_MAXN * n)
            val push = here.and(impulse.compare(VectorOperators.NE, zero))
            if (!push.anyTrue()) continue
            // Through the batch's arrays: vectors given to a method C2 does not inline would be allocated.
            zero.blend(impulse, push).intoArray(hs, g * 3 * n + 2 * n)
            pushApart(g, o)
            }
        }

        /** Applies the slot's normal impulse (in [hs], zero where nothing pushes) to both bodies, where they have mass. */
        private fun pushApart(g: Int, o: Int) {
            val sp = sp; val n = n; val bb = bb; val wc = wc
            val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
            val zero = FloatVector.zero(sp)
            val impulse = vec(hs, g * 3 * n + 2 * n)
            val push = impulse.compare(VectorOperators.NE, zero)
            val px = vec(wc, o + WC_N * n) * impulse; val py = vec(wc, o + (WC_N + 1) * n) * impulse; val pz = vec(wc, o + (WC_N + 2) * n) * impulse
            run {
                val mA = vec(bb, oa + IM * n)
                val m = push.and(mA.compare(VectorOperators.GT, zero))
                val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
                val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
                val rax = vec(wc, o + WC_RA * n); val ray = vec(wc, o + (WC_RA + 1) * n); val raz = vec(wc, o + (WC_RA + 2) * n)
                val f = vec(wc, o + WC_SA * n); val qx = px * f; val qy = py * f; val qz = pz * f
                val vax = vec(bb, oa); val vay = vec(bb, oa + n); val vaz = vec(bb, oa + 2 * n)
                vax.blend(vax - qx * mA, m).intoArray(bb, oa); vay.blend(vay - qy * mA, m).intoArray(bb, oa + n); vaz.blend(vaz - qz * mA, m).intoArray(bb, oa + 2 * n)
                val cx = ray * qz - raz * qy; val cy = raz * qx - rax * qz; val cz = rax * qy - ray * qx
                val wax = vec(bb, oa + 3 * n); val way = vec(bb, oa + 4 * n); val waz = vec(bb, oa + 5 * n)
                wax.blend(wax - (ia0 * cx + ia1 * cy + ia2 * cz), m).intoArray(bb, oa + 3 * n)
                way.blend(way - (ia1 * cx + ia3 * cy + ia4 * cz), m).intoArray(bb, oa + 4 * n)
                waz.blend(waz - (ia2 * cx + ia4 * cy + ia5 * cz), m).intoArray(bb, oa + 5 * n)
            }
            run {
                val mB = vec(bb, ob + IM * n)
                val m = push.and(mB.compare(VectorOperators.GT, zero))
                val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
                val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
                val rbx = vec(wc, o + WC_RB * n); val rby = vec(wc, o + (WC_RB + 1) * n); val rbz = vec(wc, o + (WC_RB + 2) * n)
                val f = vec(wc, o + WC_SB * n); val qx = px * f; val qy = py * f; val qz = pz * f
                val vbx = vec(bb, ob); val vby = vec(bb, ob + n); val vbz = vec(bb, ob + 2 * n)
                vbx.blend(vbx + qx * mB, m).intoArray(bb, ob); vby.blend(vby + qy * mB, m).intoArray(bb, ob + n); vbz.blend(vbz + qz * mB, m).intoArray(bb, ob + 2 * n)
                val cx = rby * qz - rbz * qy; val cy = rbz * qx - rbx * qz; val cz = rbx * qy - rby * qx
                val wbx = vec(bb, ob + 3 * n); val wby = vec(bb, ob + 4 * n); val wbz = vec(bb, ob + 5 * n)
                wbx.blend(wbx + (ib0 * cx + ib1 * cy + ib2 * cz), m).intoArray(bb, ob + 3 * n)
                wby.blend(wby + (ib1 * cx + ib3 * cy + ib4 * cz), m).intoArray(bb, ob + 4 * n)
                wbz.blend(wbz + (ib2 * cx + ib4 * cy + ib5 * cz), m).intoArray(bb, ob + 5 * n)
            }
        }

        /**
         * The patch's normal load and twist cap (with the loads hidden by the support scaling), then the
         * friction limit μ·N and the twist cap μ·Σ Nᵢ·dᵢ, kept in [hs] for the rows and the twist.
         */
        private fun frictionStart(g: Int) {
            val sp = sp; val n = n; val wc = wc; val wp = wp
            val zero = FloatVector.zero(sp)
            var load = zero; var twistCap = zero
            for (j in 0 until slots[g]) {
                val s = slotBase[g] + j; val o = s * CW * n
                val here = VectorMask.fromArray(sp, live, s * n)
                val l = vec(wc, o + WC_LN * n)
                load = load.blend(load + l, here); twistCap = twistCap.blend(twistCap + l * vec(wc, o + WC_DIST * n), here)
            }
            val h = g * 3 * n
            var reserved = zero
            if (anyHidden && gl > 0f) {
                // Loads hidden by the support scaling, lane by lane as the scalar solver does (rare: heavy on light).
                load.intoArray(hs, h); twistCap.intoArray(hs, h + n); zero.intoArray(hs, h + 2 * n)
                if (hidden(g)) { load = vec(hs, h); twistCap = vec(hs, h + n); reserved = vec(hs, h + 2 * n) }
            }
            val mu = vec(wp, g * PW * n + WP_FR * n)
            zero.max(mu * load - reserved).intoArray(hs, h)
            (mu * twistCap).intoArray(hs, h + n)
        }

        /** One friction row at the patch centre: first along the sliding direction, then across, within the circle μ·N. */
        private fun frictionRow(g: Int, row: Int) {
            val sp = sp; val n = n; val bb = bb; val wp = wp
            val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
            val p = g * PW * n; val h = g * 3 * n
            val vax = vec(bb, oa); val vay = vec(bb, oa + n); val vaz = vec(bb, oa + 2 * n)
            val wax = vec(bb, oa + 3 * n); val way = vec(bb, oa + 4 * n); val waz = vec(bb, oa + 5 * n)
            val vbx = vec(bb, ob); val vby = vec(bb, ob + n); val vbz = vec(bb, ob + 2 * n)
            val wbx = vec(bb, ob + 3 * n); val wby = vec(bb, ob + 4 * n); val wbz = vec(bb, ob + 5 * n)
            val zero = FloatVector.zero(sp)
            val limit = vec(hs, h)
            val rax = vec(wp, p + WP_RA * n); val ray = vec(wp, p + (WP_RA + 1) * n); val raz = vec(wp, p + (WP_RA + 2) * n)
            val rbx = vec(wp, p + WP_RB * n); val rby = vec(wp, p + (WP_RB + 1) * n); val rbz = vec(wp, p + (WP_RB + 2) * n)
            val t = p + (if (row == 0) WP_T1 else WP_T2) * n
            val tx = vec(wp, t); val ty = vec(wp, t + n); val tz = vec(wp, t + 2 * n)
            val rvx = vbx + wby * rbz - wbz * rby - (vax + way * raz - waz * ray)
            val rvy = vby + wbz * rbx - wbx * rbz - (vay + waz * rax - wax * raz)
            val rvz = vbz + wbx * rby - wby * rbx - (vaz + wax * ray - way * rax)
            val li = p + (if (row == 0) WP_L1 else WP_L2) * n
            val bound = if (row == 0) limit else { val l1 = vec(wp, p + WP_L1 * n); zero.max(limit * limit - l1 * l1).sqrt() }
            val old = vec(wp, li)
            val new = clamp(old - vec(wp, p + (if (row == 0) WP_M1 else WP_M2) * n) * (rvx * tx + rvy * ty + rvz * tz), bound)
            new.intoArray(wp, li)
            val moved = new.compare(VectorOperators.NE, old)
            if (!moved.anyTrue()) return
            val e = new - old
            val px = tx * e; val py = ty * e; val pz = tz * e
            val sa = vec(wp, p + WP_SA * n); val sb = vec(wp, p + WP_SB * n)
            run {
                val msa = vec(bb, oa + IM * n) * sa
                val m = moved.and(msa.compare(VectorOperators.GT, zero))
                val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
                val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
                vax.blend(vax - px * msa, m).intoArray(bb, oa); vay.blend(vay - py * msa, m).intoArray(bb, oa + n); vaz.blend(vaz - pz * msa, m).intoArray(bb, oa + 2 * n)
                val cx = (ray * pz - raz * py) * sa; val cy = (raz * px - rax * pz) * sa; val cz = (rax * py - ray * px) * sa
                wax.blend(wax - (ia0 * cx + ia1 * cy + ia2 * cz), m).intoArray(bb, oa + 3 * n)
                way.blend(way - (ia1 * cx + ia3 * cy + ia4 * cz), m).intoArray(bb, oa + 4 * n)
                waz.blend(waz - (ia2 * cx + ia4 * cy + ia5 * cz), m).intoArray(bb, oa + 5 * n)
            }
            run {
                val msb = vec(bb, ob + IM * n) * sb
                val m = moved.and(msb.compare(VectorOperators.GT, zero))
                val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
                val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
                vbx.blend(vbx + px * msb, m).intoArray(bb, ob); vby.blend(vby + py * msb, m).intoArray(bb, ob + n); vbz.blend(vbz + pz * msb, m).intoArray(bb, ob + 2 * n)
                val cx = (rby * pz - rbz * py) * sb; val cy = (rbz * px - rbx * pz) * sb; val cz = (rbx * py - rby * px) * sb
                wbx.blend(wbx + (ib0 * cx + ib1 * cy + ib2 * cz), m).intoArray(bb, ob + 3 * n)
                wby.blend(wby + (ib1 * cx + ib3 * cy + ib4 * cz), m).intoArray(bb, ob + 4 * n)
                wbz.blend(wbz + (ib2 * cx + ib4 * cy + ib5 * cz), m).intoArray(bb, ob + 5 * n)
            }
        }

        /** The twist about the normal, within μ·Σ Nᵢ·dᵢ. */
        private fun twist(g: Int) {
            val sp = sp; val n = n; val bb = bb; val wp = wp
            val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
            val p = g * PW * n; val h = g * 3 * n
            val wax = vec(bb, oa + 3 * n); val way = vec(bb, oa + 4 * n); val waz = vec(bb, oa + 5 * n)
            val wbx = vec(bb, ob + 3 * n); val wby = vec(bb, ob + 4 * n); val wbz = vec(bb, ob + 5 * n)
            val nx = vec(wp, p + WP_N * n); val ny = vec(wp, p + (WP_N + 1) * n); val nz = vec(wp, p + (WP_N + 2) * n)
            val wn = (wbx - wax) * nx + (wby - way) * ny + (wbz - waz) * nz
            val old = vec(wp, p + WP_LT * n)
            val new = clamp(old - vec(wp, p + WP_MT * n) * wn, vec(hs, h + n))
            new.intoArray(wp, p + WP_LT * n)
            val moved = new.compare(VectorOperators.NE, old)
            if (!moved.anyTrue()) return
            val zero = FloatVector.zero(sp)
            val e = new - old; val lx = nx * e; val ly = ny * e; val lz = nz * e
            val sa = vec(wp, p + WP_SA * n); val sb = vec(wp, p + WP_SB * n)
            run {
                val ma = moved.and(vec(bb, oa + IM * n).compare(VectorOperators.GT, zero))
                val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
                val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
                wax.blend(wax - (ia0 * lx + ia1 * ly + ia2 * lz) * sa, ma).intoArray(bb, oa + 3 * n)
                way.blend(way - (ia1 * lx + ia3 * ly + ia4 * lz) * sa, ma).intoArray(bb, oa + 4 * n)
                waz.blend(waz - (ia2 * lx + ia4 * ly + ia5 * lz) * sa, ma).intoArray(bb, oa + 5 * n)
            }
            run {
                val mb = moved.and(vec(bb, ob + IM * n).compare(VectorOperators.GT, zero))
                val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
                val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
                wbx.blend(wbx + (ib0 * lx + ib1 * ly + ib2 * lz) * sb, mb).intoArray(bb, ob + 3 * n)
                wby.blend(wby + (ib1 * lx + ib3 * ly + ib4 * lz) * sb, mb).intoArray(bb, ob + 4 * n)
                wbz.blend(wbz + (ib2 * lx + ib4 * ly + ib5 * lz) * sb, mb).intoArray(bb, ob + 5 * n)
            }
        }

        /**
         * The scalar solver's hidden loads, lane by lane on the batch's arrays (load, twist cap, reserved grip in
         * [hs], velocities in [bb]): the same operations in the same order. False if no lane carries one.
         */
        private fun hidden(g: Int): Boolean {
            val ps = patches; val n = n; val h = g * 3 * n; val p = g * PW * n
            var any = false
            for (l in 0 until lanes[g]) {
                val q = ordered[first[g] + l]; val a = ps.a[q]; val b = ps.b[q]
                val nx = wp[p + WP_N * n + l]; val ny = wp[p + (WP_N + 1) * n + l]; val nz = wp[p + (WP_N + 2) * n + l]
                val up = -(nx * gravity.x + ny * gravity.y + nz * gravity.z) / gl
                val x = if (up >= .5f) b else if (up <= -.5f && a >= 0) a else -1
                if (x < 0 || hiddenN[x] <= 0f) continue
                any = true
                var load = hs[h + l]; var twistCap = hs[h + n + l]
                twistCap *= (load + hiddenN[x]) / max(load, 1e-12f); load += hiddenN[x]
                var hx = hiddenT[3 * x]; var hy = hiddenT[3 * x + 1]; var hz = hiddenT[3 * x + 2]
                val hn = hx * nx + hy * ny + hz * nz; hx -= hn * nx; hy -= hn * ny; hz -= hn * nz
                val hl = sqrt(hx * hx + hy * hy + hz * hz)
                val grip = wp[p + WP_FR * n + l] * load
                if (hl > grip && useBias) {
                    val e = (hl - grip) / hl / (1f / st[x * S + IM] + hiddenM[x])
                    val ex = hx * e; val ey = hy * e; val ez = hz * e
                    val o = (if (x == b) (g * 2 + 1) else (g * 2)) * S * n + l
                    bb[o] += ex; bb[o + n] += ey; bb[o + 2 * n] += ez
                    slide[3 * x] += ex; slide[3 * x + 1] += ey; slide[3 * x + 2] += ez
                }
                hs[h + l] = load; hs[h + n + l] = twistCap; hs[h + 2 * n + l] = min(hl, grip)
            }
            return any
        }

        /** Rolling resistance, per point: a torque against the relative spin, bounded by μr × radius × N. */
        private fun rolling(g: Int) {
            val sp = sp; val n = n; val bb = bb; val wc = wc
            val zero = FloatVector.zero(sp)
            var any = false
            for (j in 0 until slots[g]) {
                val s = slotBase[g] + j
                if (VectorMask.fromArray(sp, live, s * n).and(vec(wc, (s * CW + WC_RL) * n).compare(VectorOperators.GT, zero)).anyTrue()) { any = true; break }
            }
            if (!any) return
            val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
            var wax = vec(bb, oa + 3 * n); var way = vec(bb, oa + 4 * n); var waz = vec(bb, oa + 5 * n)
            val mA = vec(bb, oa + IM * n)
            val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
            val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
            var wbx = vec(bb, ob + 3 * n); var wby = vec(bb, ob + 4 * n); var wbz = vec(bb, ob + 5 * n)
            val mB = vec(bb, ob + IM * n)
            val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
            val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
            val massA = mA.compare(VectorOperators.GT, zero); val massB = mB.compare(VectorOperators.GT, zero)
            for (j in 0 until slots[g]) {
                val s = slotBase[g] + j; val o = s * CW * n
                val rl = vec(wc, o + WC_RL * n)
                val roll = VectorMask.fromArray(sp, live, s * n).and(rl.compare(VectorOperators.GT, zero))
                if (!roll.anyTrue()) continue
                val m = vec(wc, o + WC_RM * n); val cap = rl * vec(wc, o + WC_LN * n)
                val ox = vec(wc, o + WC_LR * n); val oy = vec(wc, o + (WC_LR + 1) * n); val oz = vec(wc, o + (WC_LR + 2) * n)
                var lx = ox - m * (wbx - wax); var ly = oy - m * (wby - way); var lz = oz - m * (wbz - waz)
                val l2 = lx * lx + ly * ly + lz * lz
                val over = l2.compare(VectorOperators.GT, cap * cap)
                if (over.anyTrue()) { val f = cap / l2.sqrt(); lx = lx.blend(lx * f, over); ly = ly.blend(ly * f, over); lz = lz.blend(lz * f, over) }
                ox.blend(lx, roll).intoArray(wc, o + WC_LR * n); oy.blend(ly, roll).intoArray(wc, o + (WC_LR + 1) * n); oz.blend(lz, roll).intoArray(wc, o + (WC_LR + 2) * n)
                val ex = lx - ox; val ey = ly - oy; val ez = lz - oz
                val ma = roll.and(massA); val mb = roll.and(massB)
                wax = wax.blend(wax - (ia0 * ex + ia1 * ey + ia2 * ez), ma); way = way.blend(way - (ia1 * ex + ia3 * ey + ia4 * ez), ma)
                waz = waz.blend(waz - (ia2 * ex + ia4 * ey + ia5 * ez), ma)
                wbx = wbx.blend(wbx + (ib0 * ex + ib1 * ey + ib2 * ez), mb); wby = wby.blend(wby + (ib1 * ex + ib3 * ey + ib4 * ez), mb)
                wbz = wbz.blend(wbz + (ib2 * ex + ib4 * ey + ib5 * ez), mb)
            }
            wax.intoArray(bb, oa + 3 * n); way.intoArray(bb, oa + 4 * n); waz.intoArray(bb, oa + 5 * n)
            wbx.intoArray(bb, ob + 3 * n); wby.intoArray(bb, ob + 4 * n); wbz.intoArray(bb, ob + 5 * n)
        }

        /** The impulses back into the contact sets: the restitution, sleep and next step's warm start read them there. */
        override fun finish() { forChunks(batches, 8, batches >= 16, finishJob) }
        private val finishJob = Job { from, to -> for (g in from until to) back(g) }

        private fun back(g: Int) {
            val ps = patches; val pd = ps.d; val d = points.d; val n = n
            for (l in 0 until lanes[g]) {
                val q = ordered[first[g] + l]
                pd[q * P + PL1] = wp[(g * PW + WP_L1) * n + l]; pd[q * P + PL2] = wp[(g * PW + WP_L2) * n + l]; pd[q * P + PLT] = wp[(g * PW + WP_LT) * n + l]
                val k0 = ps.start[q]
                for (j in 0 until ps.end[q] - k0) {
                    val s = slotBase[g] + j; val o = (k0 + j) * C
                    d[o + CLN] = wc[(s * CW + WC_LN) * n + l]; d[o + CMAXN] = wc[(s * CW + WC_MAXN) * n + l]
                    d[o + CLR] = wc[(s * CW + WC_LR) * n + l]; d[o + CLR + 1] = wc[(s * CW + WC_LR + 1) * n + l]; d[o + CLR + 2] = wc[(s * CW + WC_LR + 2) * n + l]
                }
            }
        }

        // Operators and helpers inlined where they are used: a vector never crosses a call the JIT might not inline.
        // Their results keep Java's platform types (no declared type): a value given a Kotlin non-null type gets a
        // null check, a call the vector escapes into, and C2 then allocates it (3.5 times slower).
        private inline operator fun FloatVector.plus(o: FloatVector) = add(o)
        private inline operator fun FloatVector.minus(o: FloatVector) = sub(o)
        private inline operator fun FloatVector.times(o: FloatVector) = mul(o)
        private inline fun vec(a: FloatArray, at: Int) = FloatVector.fromArray(sp, a, at)

        /** Kotlin's coerceIn(−bound, bound), lane by lane: below → −bound, above → bound, else as it is. */
        private inline fun clamp(x: FloatVector, bound: FloatVector) =
            x.blend(bound.neg(), x.compare(VectorOperators.LT, bound.neg())).blend(bound, x.compare(VectorOperators.GT, bound))
    }
}

/** The wide solver's vector species as a static final field, loaded only by [PhysicsWorld.Wide]. */
private object WideLanes { @JvmField val SPECIES: VectorSpecies<Float> = FloatVector.SPECIES_PREFERRED }

/** The solver on SIMD lanes, seen from [PhysicsWorld] without naming the vector module ([PhysicsWorld.Wide]). */
internal interface WideContacts {
    /** Lays out this step's coloured patches in batches (those scaled for support stay scalar); false: the scalar solver runs this step. */
    fun prepare(): Boolean
    fun warmStart()
    fun solve(useBias: Boolean, invH: Float, anyHidden: Boolean, gl: Float)
    /** Writes the impulses back into the contact sets. */
    fun finish()
}

// The wide solver's layout: per point slot, CW vectors (one float per lane each) taken from C offsets
// (POINT_SOURCE); per patch, PW vectors from P offsets (PATCH_SOURCE; −1 and −2: the first point's CSA, CSB).
private const val WC_N = 0; private const val WC_RA = 3; private const val WC_RB = 6; private const val WC_ADJ = 9
private const val WC_BIAS = 10; private const val WC_MS = 11; private const val WC_IS = 12; private const val WC_NM = 13
private const val WC_LN = 14; private const val WC_MAXN = 15; private const val WC_SA = 16; private const val WC_SB = 17
private const val WC_RL = 18; private const val WC_RM = 19; private const val WC_LR = 20; private const val WC_DIST = 23
private const val CW = 24
private val POINT_SOURCE = intArrayOf(CNX, CNX + 1, CNX + 2, CRA, CRA + 1, CRA + 2, CRB, CRB + 1, CRB + 2, CADJ,
    CBIAS, CMS, CIS, CNM, CLN, CMAXN, CSA, CSB, CRL, CRM, CLR, CLR + 1, CLR + 2, CDIST)
private const val WP_N = 0; private const val WP_RA = 3; private const val WP_RB = 6; private const val WP_T1 = 9; private const val WP_T2 = 12
private const val WP_M1 = 15; private const val WP_M2 = 16; private const val WP_MT = 17; private const val WP_FR = 18
private const val WP_L1 = 19; private const val WP_L2 = 20; private const val WP_LT = 21; private const val WP_SA = 22; private const val WP_SB = 23
private const val PW = 24
private val PATCH_SOURCE = intArrayOf(PN, PN + 1, PN + 2, PRA, PRA + 1, PRA + 2, PRB, PRB + 1, PRB + 2, PT1, PT1 + 1, PT1 + 2,
    PT2, PT2 + 1, PT2 + 2, PM1, PM2, PMT, PFR, PL1, PL2, PLT, -1, -2)
private const val SORT_BUCKETS = 16
private const val WIDE_CHUNK = 8

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
            val lxk = poseB.localX(x[k], y[k], z[k]); val lyk = poseB.localY(x[k], y[k], z[k]); val lzk = poseB.localZ(x[k], y[k], z[k])
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
