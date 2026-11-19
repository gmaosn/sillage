package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

// Joints, solved with the contacts in each substep (warm start, solve with soft correction, relax),
// after Box2D v3 (E. Catto) carried to 3D. A joint ties body b to body a, or to the world when a is
// null. Positions are measured as the contacts measure them: from the start of the step, with each
// body's displacement and rotation since (Δp, Δq), so a joint stays right within a step.

/** Softness of a constraint: Box2D v3's soft step (bias rate, mass scale, impulse scale). */
internal class Softness {
    var biasRate = 0f; var massScale = 1f; var impulseScale = 0f
    fun set(hertz: Float, dampingRatio: Float, h: Float): Softness {
        if (hertz <= 0f) { biasRate = 0f; massScale = 1f; impulseScale = 0f; return this }
        val omega = 2f * PI.toFloat() * hertz
        val a1 = 2f * dampingRatio + h * omega; val a2 = h * omega * a1; val a3 = 1f / (1f + a2)
        biasRate = omega / a1; massScale = a2 * a3; impulseScale = a3
        return this
    }
}

/**
 * A joint between two bodies, or between a body and the world ([a] null). Made by the world's `add…`
 * functions ([PhysicsWorld.addBall], [PhysicsWorld.addHinge]…), removed by [PhysicsWorld.removeJoint]
 * or with one of its bodies. The anchors are given in world coordinates where the bodies are when joined.
 */
sealed class Joint(internal val world: PhysicsWorld, val a: Body?, val b: Body, anchorA: Vec3, anchorB: Vec3,
                   /** Whether the two bodies still collide with each other (false: they pass through each other). */
                   val collideConnected: Boolean) {
    internal val ia = a?.id ?: -1
    internal val ib = b.id
    /** The anchors in each body's frame (in the world for the world). */
    internal val localA = local(ia, anchorA)
    internal val localB = local(ib, anchorB)
    /** A disabled joint holds nothing (a door off its hinges, a broken rope); it can be enabled again. */
    var enabled = true
        set(v) { field = v; if (v) world.wakeJoint(this) }
    /** True once removed from the world. */
    var isRemoved = false
        internal set

    /** The anchor on a, then on b, where the bodies are now. */
    val anchorA: Vec3 get() = worldPoint(ia, localA)
    val anchorB: Vec3 get() = worldPoint(ib, localB)

    /**
     * The force the joint applied during the last step (N, its point or length part), to break it when
     * overloaded: `if (joint.force > 5000f) world.removeJoint(joint)`.
     */
    val force: Float get() = lastImpulse * invH
    internal var lastImpulse = 0f
    internal var invH = 0f

    internal fun local(i: Int, p: Vec3): Vec3 = if (i < 0) p else rot(i).conjugate().rotate(p - centre(i))
    internal fun worldPoint(i: Int, l: Vec3) = if (i < 0) l else centre(i) + rot(i).rotate(l)

    // ---- measured at the start of the step

    internal var frameA = Vec3.ZERO; internal var frameB = Vec3.ZERO       // anchors from each centre, in world axes
    internal var deltaCenter = Vec3.ZERO                                    // centre of b minus centre of a
    internal val soft = Softness()
    internal var h = 0f

    internal open fun prepare(h: Float, hertz: Float, dampingRatio: Float) {
        this.h = h; invH = 1f / h
        frameA = if (ia < 0) localA else rot(ia).rotate(localA)
        frameB = rot(ib).rotate(localB)
        deltaCenter = centre(ib) - centre(ia)
        soft.set(hertz, dampingRatio, h)
    }
    internal abstract fun warmStart()
    internal abstract fun solve(useBias: Boolean, invH: Float)

    // ---- body state in the world's solver arrays; a = −1 is the world: still, without mass

    /** Rotation now (at the step's start in prepare, as integrated so far in the substeps). */
    internal fun rot(i: Int) = if (i < 0) Quat.IDENTITY else Quat(world.qx[i], world.qy[i], world.qz[i], world.qw[i])
    internal fun centre(i: Int) = if (i < 0) Vec3.ZERO else Vec3(world.px[i], world.py[i], world.pz[i])
    internal fun im(i: Int) = if (i < 0) 0f else world.st[i * SS + 6]
    internal fun v(i: Int) = if (i < 0) Vec3.ZERO else world.st.let { s -> Vec3(s[i * SS], s[i * SS + 1], s[i * SS + 2]) }
    internal fun w(i: Int) = if (i < 0) Vec3.ZERO else world.st.let { s -> Vec3(s[i * SS + 3], s[i * SS + 4], s[i * SS + 5]) }
    /** Displacement and rotation since the step began. */
    internal fun dp(i: Int) = if (i < 0) Vec3.ZERO else world.st.let { s -> Vec3(s[i * SS + 13], s[i * SS + 14], s[i * SS + 15]) }
    internal fun dq(i: Int) = if (i < 0) Quat.IDENTITY else world.st.let { s -> Quat(s[i * SS + 16], s[i * SS + 17], s[i * SS + 18], s[i * SS + 19]) }
    /** World inverse inertia times [x] (zero for a body that does not turn). */
    internal fun ii(i: Int, x: Vec3): Vec3 {
        if (i < 0 || im(i) == 0f) return Vec3.ZERO
        val s = world.st; val k = i * SS + 7
        return Vec3(s[k] * x.x + s[k + 1] * x.y + s[k + 2] * x.z, s[k + 1] * x.x + s[k + 3] * x.y + s[k + 4] * x.z,
            s[k + 2] * x.x + s[k + 4] * x.y + s[k + 5] * x.z)
    }
    /** Applies the linear impulse [p] at the levers [ra], [rb] and the angular impulse [l]: −(p, l) on a, +(p, l) on b. */
    internal fun apply(p: Vec3, ra: Vec3, rb: Vec3, l: Vec3 = Vec3.ZERO) {
        push(ia, -1f, p, (ra cross p) + l)
        push(ib, 1f, p, (rb cross p) + l)
    }
    internal fun applyAngular(l: Vec3) { push(ia, -1f, Vec3.ZERO, l); push(ib, 1f, Vec3.ZERO, l) }
    /** Adds sign × (p, torque) to body [i]'s velocities. */
    internal fun push(i: Int, sign: Float, p: Vec3, torque: Vec3) {
        if (i < 0) return
        val m = im(i)
        if (m == 0f) return
        val s = world.st; val o = i * SS
        s[o] += sign * p.x * m; s[o + 1] += sign * p.y * m; s[o + 2] += sign * p.z * m
        val dw = ii(i, torque)
        s[o + 3] += sign * dw.x; s[o + 4] += sign * dw.y; s[o + 5] += sign * dw.z
    }

    /** Both anchors at one place (3 rows); returns the impulse applied. */
    internal fun solvePoint(useBias: Boolean, accumulated: Vec3): Vec3 {
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        val cdot = v(ib) + (w(ib) cross rb) - v(ia) - (w(ia) cross ra)
        var bias = Vec3.ZERO; var massScale = 1f; var impulseScale = 0f
        if (useBias) {
            val separation = dp(ib) - dp(ia) + (rb - ra) + deltaCenter
            bias = separation * soft.biasRate; massScale = soft.massScale; impulseScale = soft.impulseScale
        }
        val impulse = pointMass(ra, rb).solve(-(cdot + bias)) * massScale - accumulated * impulseScale
        apply(impulse, ra, rb)
        return impulse
    }

    /** K of the point constraint: (mA + mB)·1 − [rA]× IA [rA]× − [rB]× IB [rB]×. */
    private fun pointMass(ra: Vec3, rb: Vec3): Mat3 {
        val m = im(ia) + im(ib)
        fun col(e: Vec3) = e * m - (ra cross ii(ia, ra cross e)) - (rb cross ii(ib, rb cross e))
        return Mat3(col(Vec3.X), col(Vec3.Y), col(Vec3.Z))
    }

    /** K of a rotation lock: IA + IB. */
    private fun angularMass(): Mat3 = Mat3(ii(ia, Vec3.X) + ii(ib, Vec3.X), ii(ia, Vec3.Y) + ii(ib, Vec3.Y), ii(ia, Vec3.Z) + ii(ib, Vec3.Z))

    /** Inverse inertia about [axis] of the two bodies together, inverted (0: neither turns). */
    internal fun axialMass(axis: Vec3): Float = (axis dot (ii(ia, axis) + ii(ib, axis))).let { if (it > 0f) 1f / it else 0f }

    /**
     * Keeps b's rotation relative to a at [relative0] (3 rows), with the accumulated angular impulse
     * [accumulated]; returns the impulse applied.
     */
    internal fun solveLock(useBias: Boolean, relative0: Quat, accumulated: Vec3): Vec3 {
        var cdot = w(ib) - w(ia)
        var massScale = 1f; var impulseScale = 0f
        if (useBias) {
            // b = a · E · relative0: E, the error in a's frame, turned into the world.
            val qa = rot(ia)
            var e = qa.conjugate() * rot(ib) * relative0.conjugate()
            if (e.w < 0f) e = Quat(-e.x, -e.y, -e.z, -e.w)
            cdot += qa.rotate(Vec3(e.x, e.y, e.z) * 2f) * soft.biasRate
            massScale = soft.massScale; impulseScale = soft.impulseScale
        }
        val l = angularMass().solve(-cdot) * massScale - accumulated * impulseScale
        applyAngular(l)
        return l
    }
}

/** A ball joint: the two anchors stay together, the bodies turn freely about it (chains, pendulums, ragdolls). */
class BallJoint internal constructor(world: PhysicsWorld, a: Body?, b: Body, anchor: Vec3, collideConnected: Boolean) :
    Joint(world, a, b, anchor, anchor, collideConnected) {
    private var impulse = Vec3.ZERO
    override fun prepare(h: Float, hertz: Float, dampingRatio: Float) { super.prepare(h, hertz, dampingRatio); lastImpulse = impulse.length() }
    override fun warmStart() = apply(impulse, dq(ia).rotate(frameA), dq(ib).rotate(frameB))
    override fun solve(useBias: Boolean, invH: Float) { impulse += solvePoint(useBias, impulse) }
}

/**
 * A hinge: the anchors stay together and the bodies turn about one axis only (doors, wheels, levers,
 * see-saws). Optional limits ([limits]), a motor ([motor]) and a spring back to an angle ([spring]).
 */
class HingeJoint internal constructor(world: PhysicsWorld, a: Body?, b: Body, anchor: Vec3, axis: Vec3, collideConnected: Boolean) :
    Joint(world, a, b, anchor, anchor, collideConnected) {
    private val axisA = localDir(ia, axis.normalized()); private val axisB = localDir(ib, axis.normalized())
    private val refA = localDir(ia, perpendicular(axis.normalized())); private val refB = localDir(ib, perpendicular(axis.normalized()))
    var lowerAngle = 0f; private set
    var upperAngle = 0f; private set
    var limitsEnabled = false; private set
    var motorSpeed = 0f; private set
    var maxMotorTorque = 0f; private set
    var motorEnabled = false; private set
    var springHertz = 0f; private set
    var springDampingRatio = 0f; private set
    var springAngle = 0f; private set
    private var impulse = Vec3.ZERO
    private var angular = Vec3.ZERO
    private var lowerImpulse = 0f; private var upperImpulse = 0f; private var motorImpulse = 0f; private var springImpulse = 0f
    private var axisA0 = Vec3.Y; private var axisB0 = Vec3.Y; private var refA0 = Vec3.X; private var refB0 = Vec3.X
    private val spring = Softness()

    private fun localDir(i: Int, d: Vec3): Vec3 = (if (i < 0) d else rot(i).conjugate().rotate(d)).normalized()

    /** The angle of b about the axis relative to a, from where they were when joined (radians, −π..π). */
    val angle: Float get() = angleOf(rot(ia).rotate(axisA), rot(ia).rotate(refA), rot(ib).rotate(refB))
    /** Relative turning speed about the axis (rad/s). */
    val speed: Float get() = (w(ib) - w(ia)) dot rot(ia).rotate(axisA)
    /** The axis in the world now (as a sees it). */
    val axis: Vec3 get() = rot(ia).rotate(axisA)

    private fun angleOf(axis: Vec3, ra: Vec3, rb: Vec3) = atan2((ra cross rb) dot axis, ra dot rb)

    /** Keeps the angle within [lower]..[upper] radians (null, null: no limits). */
    fun limits(lower: Float?, upper: Float?) {
        require(lower == null || upper == null || lower <= upper) { "The lower limit must not exceed the upper one" }
        limitsEnabled = lower != null && upper != null; lowerAngle = lower ?: 0f; upperAngle = upper ?: 0f
        world.wakeJoint(this)
    }
    /** Drives the turning speed towards [speed] rad/s with at most [maxTorque] N·m (null: no motor). */
    fun motor(speed: Float?, maxTorque: Float = 0f) {
        motorEnabled = speed != null; motorSpeed = speed ?: 0f; maxMotorTorque = maxTorque; world.wakeJoint(this)
    }
    /** Pulls back to [angle] like a spring of [hertz] Hz (0: none), damped by [dampingRatio] (1: no overshoot). */
    fun spring(hertz: Float, dampingRatio: Float = 1f, angle: Float = 0f) {
        springHertz = hertz; springDampingRatio = dampingRatio; springAngle = angle; world.wakeJoint(this)
    }

    override fun prepare(h: Float, hertz: Float, dampingRatio: Float) {
        super.prepare(h, hertz, dampingRatio)
        axisA0 = rot(ia).rotate(axisA); axisB0 = rot(ib).rotate(axisB); refA0 = rot(ia).rotate(refA); refB0 = rot(ib).rotate(refB)
        spring.set(springHertz, springDampingRatio, h)
        if (!motorEnabled) motorImpulse = 0f
        if (!limitsEnabled) { lowerImpulse = 0f; upperImpulse = 0f }
        if (springHertz <= 0f) springImpulse = 0f
        lastImpulse = impulse.length()
    }

    override fun warmStart() {
        val axis = dq(ia).rotate(axisA0)
        apply(impulse, dq(ia).rotate(frameA), dq(ib).rotate(frameB), angular + axis * (springImpulse + motorImpulse + lowerImpulse - upperImpulse))
    }

    override fun solve(useBias: Boolean, invH: Float) {
        val qa = dq(ia); val qb = dq(ib)
        val axis = qa.rotate(axisA0)
        val mass = axialMass(axis)
        val angle = angleOf(axis, qa.rotate(refA0), qb.rotate(refB0))
        fun cdot() = (w(ib) - w(ia)) dot axis
        // Spring, then motor, then limits (which overrule both), then the axes, then the point (Box2D's order).
        if (springHertz > 0f) {
            val c = angle - springAngle
            val l = -spring.massScale * mass * (cdot() + spring.biasRate * c) - spring.impulseScale * springImpulse
            springImpulse += l; applyAngular(axis * l)
        }
        if (motorEnabled) {
            val max = maxMotorTorque * h; val old = motorImpulse
            motorImpulse = (motorImpulse - mass * (cdot() - motorSpeed)).coerceIn(-max, max)
            applyAngular(axis * (motorImpulse - old))
        }
        if (limitsEnabled) for (side in 0..1) {
            // Lower: angle − lower ≥ 0; upper: upper − angle ≥ 0, the same row turned round.
            val c = if (side == 0) angle - lowerAngle else upperAngle - angle
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (c > 0f) bias = c * invH                       // not there yet: arrive exactly, no sooner
            else if (useBias) { bias = soft.biasRate * c; massScale = soft.massScale; impulseScale = soft.impulseScale }
            val sign = if (side == 0) 1f else -1f
            val old = if (side == 0) lowerImpulse else upperImpulse
            val new = max(old - mass * massScale * (sign * cdot() + bias) - impulseScale * old, 0f)
            if (side == 0) lowerImpulse = new else upperImpulse = new
            applyAngular(axis * (sign * (new - old)))
        }
        // The axes stay aligned: two rows across a's axis.
        run {
            val j1 = perpendicular(axis); val j2 = axis cross j1
            val rel = w(ib) - w(ia)
            var b1 = rel dot j1; var b2 = rel dot j2
            var massScale = 1f; var impulseScale = 0f
            if (useBias) {
                // Turned by θ about u (across the axis), b's axis is off by θ u × axis, so axis × it is θ u.
                val err = axis cross qb.rotate(axisB0)
                b1 += soft.biasRate * (err dot j1); b2 += soft.biasRate * (err dot j2)
                massScale = soft.massScale; impulseScale = soft.impulseScale
            }
            val i1 = ii(ia, j1) + ii(ib, j1); val i2 = ii(ia, j2) + ii(ib, j2)
            val k11 = j1 dot i1; val k12 = j1 dot i2; val k22 = j2 dot i2
            val det = k11 * k22 - k12 * k12
            if (det > 1e-12f) {
                val l1 = -(k22 * b1 - k12 * b2) / det; val l2 = -(-k12 * b1 + k11 * b2) / det
                val step = j1 * (l1 * massScale - impulseScale * (angular dot j1)) + j2 * (l2 * massScale - impulseScale * (angular dot j2))
                angular += step; applyAngular(step)
            }
        }
        impulse += solvePoint(useBias, impulse)
    }
}

/**
 * A slider: b moves along one axis of a and does not turn relative to it (lifts, pistons, drawers,
 * suspensions). Optional limits ([limits], metres from where they were joined), a motor ([motor]) and a
 * spring ([spring]).
 */
class SliderJoint internal constructor(world: PhysicsWorld, a: Body?, b: Body, anchor: Vec3, axis: Vec3, collideConnected: Boolean) :
    Joint(world, a, b, anchor, anchor, collideConnected) {
    private val axisA = (if (ia < 0) axis else rot(ia).conjugate().rotate(axis)).normalized()
    private val relative0 = rot(ia).conjugate() * rot(ib)          // b's rotation relative to a when joined
    var lower = 0f; private set
    var upper = 0f; private set
    var limitsEnabled = false; private set
    var motorSpeed = 0f; private set
    var maxMotorForce = 0f; private set
    var motorEnabled = false; private set
    var springHertz = 0f; private set
    var springDampingRatio = 0f; private set
    var springPosition = 0f; private set
    private var linear = Vec3.ZERO; private var angular = Vec3.ZERO
    private var lowerImpulse = 0f; private var upperImpulse = 0f; private var motorImpulse = 0f; private var springImpulse = 0f
    private var axis0 = Vec3.X
    private val spring = Softness()

    /** How far b moved along the axis since they were joined (m). */
    val translation: Float get() = (anchorB - anchorA) dot axis
    /** Speed along the axis (m/s). */
    val speed: Float get() = (v(ib) - v(ia)) dot axis
    /** The axis in the world now (as a sees it). */
    val axis: Vec3 get() = rot(ia).rotate(axisA)

    fun limits(lower: Float?, upper: Float?) {
        require(lower == null || upper == null || lower <= upper) { "The lower limit must not exceed the upper one" }
        limitsEnabled = lower != null && upper != null; this.lower = lower ?: 0f; this.upper = upper ?: 0f; world.wakeJoint(this)
    }
    /** Drives the speed along the axis towards [speed] m/s with at most [maxForce] N (null: no motor). */
    fun motor(speed: Float?, maxForce: Float = 0f) { motorEnabled = speed != null; motorSpeed = speed ?: 0f; maxMotorForce = maxForce; world.wakeJoint(this) }
    /** Pulls back to [position] (m along the axis) like a spring of [hertz] Hz (0: none). */
    fun spring(hertz: Float, dampingRatio: Float = 1f, position: Float = 0f) {
        springHertz = hertz; springDampingRatio = dampingRatio; springPosition = position; world.wakeJoint(this)
    }

    override fun prepare(h: Float, hertz: Float, dampingRatio: Float) {
        super.prepare(h, hertz, dampingRatio)
        axis0 = rot(ia).rotate(axisA)
        spring.set(springHertz, springDampingRatio, h)
        if (!motorEnabled) motorImpulse = 0f
        if (!limitsEnabled) { lowerImpulse = 0f; upperImpulse = 0f }
        if (springHertz <= 0f) springImpulse = 0f
        lastImpulse = linear.length()
    }

    // A linear row along n: on a's turning (rA + d) × n (n turns with a), on b's rB × n.
    private var aa = Vec3.ZERO; private var bb = Vec3.ZERO
    private fun row(n: Vec3, ra: Vec3, rb: Vec3, d: Vec3): Float {
        aa = (ra + d) cross n; bb = rb cross n
        val k = im(ia) + im(ib) + (aa dot ii(ia, aa)) + (bb dot ii(ib, bb))
        return if (k > 0f) 1f / k else 0f
    }
    private fun rowSpeed(n: Vec3) = (n dot (v(ib) - v(ia))) + (bb dot w(ib)) - (aa dot w(ia))
    private fun applyRow(n: Vec3, lambda: Float) {
        val p = n * lambda
        push(ia, -1f, p, aa * lambda); push(ib, 1f, p, bb * lambda)
    }

    override fun warmStart() {
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        val d = dp(ib) - dp(ia) + (rb - ra) + deltaCenter
        val axis = dq(ia).rotate(axis0)
        val t1 = perpendicular(axis); val t2 = axis cross t1
        row(t1, ra, rb, d); applyRow(t1, linear dot t1)
        row(t2, ra, rb, d); applyRow(t2, linear dot t2)
        row(axis, ra, rb, d); applyRow(axis, springImpulse + motorImpulse + lowerImpulse - upperImpulse)
        applyAngular(angular)
    }

    override fun solve(useBias: Boolean, invH: Float) {
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        val d = dp(ib) - dp(ia) + (rb - ra) + deltaCenter
        val axis = dq(ia).rotate(axis0)
        val translation = d dot axis
        if (springHertz > 0f) {
            val m = row(axis, ra, rb, d)
            val l = -spring.massScale * m * (rowSpeed(axis) + spring.biasRate * (translation - springPosition)) - spring.impulseScale * springImpulse
            springImpulse += l; applyRow(axis, l)
        }
        if (motorEnabled) {
            val m = row(axis, ra, rb, d)
            val max = maxMotorForce * h; val old = motorImpulse
            motorImpulse = (motorImpulse - m * (rowSpeed(axis) - motorSpeed)).coerceIn(-max, max)
            applyRow(axis, motorImpulse - old)
        }
        if (limitsEnabled) for (side in 0..1) {
            val c = if (side == 0) translation - lower else upper - translation
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (c > 0f) bias = c * invH
            else if (useBias) { bias = soft.biasRate * c; massScale = soft.massScale; impulseScale = soft.impulseScale }
            val sign = if (side == 0) 1f else -1f
            val m = row(axis, ra, rb, d)
            val old = if (side == 0) lowerImpulse else upperImpulse
            val new = max(old - m * massScale * (sign * rowSpeed(axis) + bias) - impulseScale * old, 0f)
            if (side == 0) lowerImpulse = new else upperImpulse = new
            applyRow(axis, sign * (new - old))
        }
        angular += solveLock(useBias, relative0, angular)
        // Across the axis: two rows.
        val t1 = perpendicular(axis); val t2 = axis cross t1
        for (k in 0..1) {
            val n = if (k == 0) t1 else t2
            val m = row(n, ra, rb, d)
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (useBias) { bias = soft.biasRate * (d dot n); massScale = soft.massScale; impulseScale = soft.impulseScale }
            val lambda = -m * massScale * (rowSpeed(n) + bias) - impulseScale * (linear dot n)
            linear += n * lambda
            applyRow(n, lambda)
        }
    }
}

/** A fixed joint: the two bodies move as one (glued, welded); [b] keeps its place and rotation relative to [a]. */
class FixedJoint internal constructor(world: PhysicsWorld, a: Body?, b: Body, anchor: Vec3, collideConnected: Boolean) :
    Joint(world, a, b, anchor, anchor, collideConnected) {
    private val relative0 = rot(ia).conjugate() * rot(ib)
    private var impulse = Vec3.ZERO
    private var angular = Vec3.ZERO
    override fun prepare(h: Float, hertz: Float, dampingRatio: Float) { super.prepare(h, hertz, dampingRatio); lastImpulse = impulse.length() }
    override fun warmStart() = apply(impulse, dq(ia).rotate(frameA), dq(ib).rotate(frameB), angular)
    override fun solve(useBias: Boolean, invH: Float) {
        angular += solveLock(useBias, relative0, angular)
        impulse += solvePoint(useBias, impulse)
    }
}

/**
 * Keeps an anchor on each body at a distance: rigid at [length], a spring ([spring]), or a rope (slack
 * below its length, made by [PhysicsWorld.addRope]); a spring can be bounded by [limits].
 */
class DistanceJoint internal constructor(world: PhysicsWorld, a: Body?, b: Body, anchorA: Vec3, anchorB: Vec3, collideConnected: Boolean,
                                         /** A rope holds only at its length, never pushes. */
                                         val rope: Boolean) :
    Joint(world, a, b, anchorA, anchorB, collideConnected) {
    /** Rest length (m): the distance when joined, unless set. */
    var length = (anchorB - anchorA).length()
        set(v) { require(v >= 0f); field = v; world.wakeJoint(this) }
    var minLength = 0f; private set
    var maxLength = Float.POSITIVE_INFINITY; private set
    var springHertz = 0f; private set
    var springDampingRatio = 0f; private set
    private var impulse = 0f; private var lowerImpulse = 0f; private var upperImpulse = 0f
    private val spring = Softness()

    /** The distance between the two anchors now (m). */
    val currentLength: Float get() = (anchorB - anchorA).length()

    /** Makes it a spring of [hertz] Hz towards [length], damped by [dampingRatio] (0 Hz: rigid again). */
    fun spring(hertz: Float, dampingRatio: Float = .5f) {
        require(!rope) { "A rope is no spring" }
        springHertz = hertz; springDampingRatio = dampingRatio; world.wakeJoint(this)
    }
    /** A spring (or a rope) never shorter than [min], never longer than [max] (m). */
    fun limits(min: Float = 0f, max: Float = Float.POSITIVE_INFINITY) {
        require(min in 0f..max) { "Limits need 0 ≤ min ≤ max" }
        minLength = min; maxLength = max; world.wakeJoint(this)
    }

    override fun prepare(h: Float, hertz: Float, dampingRatio: Float) {
        super.prepare(h, hertz, dampingRatio)
        spring.set(springHertz, springDampingRatio, h)
        if (rope) impulse = 0f
        lastImpulse = abs(impulse + lowerImpulse - upperImpulse)
    }

    private var n = Vec3.Y
    private var ca = Vec3.ZERO; private var cb = Vec3.ZERO
    private var current = 0f
    /** Measures the axis and the distance now; returns the effective mass along it. */
    private fun measure(ra: Vec3, rb: Vec3): Float {
        val d = dp(ib) - dp(ia) + (rb - ra) + deltaCenter
        val l = d.length()
        n = if (l > 1e-6f) d / l else Vec3.Y
        current = l
        ca = ra cross n; cb = rb cross n
        val k = im(ia) + im(ib) + (ca dot ii(ia, ca)) + (cb dot ii(ib, cb))
        return if (k > 0f) 1f / k else 0f
    }
    private fun speed() = (n dot (v(ib) - v(ia))) + (cb dot w(ib)) - (ca dot w(ia))
    private fun applyAlong(ra: Vec3, rb: Vec3, lambda: Float) = apply(n * lambda, ra, rb)

    override fun warmStart() {
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        measure(ra, rb)
        applyAlong(ra, rb, impulse + lowerImpulse - upperImpulse)
    }

    override fun solve(useBias: Boolean, invH: Float) {
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        val m = measure(ra, rb)
        if (!rope) {
            // A spring pulls in both passes (softness is its nature); a rigid rod corrects only with bias.
            val c = current - length
            val s = if (springHertz > 0f) spring else soft
            val corrected = springHertz > 0f || useBias
            val bias = if (corrected) s.biasRate * c else 0f
            val massScale = if (corrected) s.massScale else 1f
            val impulseScale = if (corrected) s.impulseScale else 0f
            val l = -massScale * m * (speed() + bias) - impulseScale * impulse
            impulse += l; applyAlong(ra, rb, l)
        }
        // Limits: a rope's length, a spring's bounds (a rigid rod has nothing to bound).
        if (!rope && springHertz <= 0f) return
        val top = if (rope) min(length, maxLength) else maxLength
        for (side in 0..1) {
            val limit = if (side == 0) minLength else top
            if (side == 0 && limit <= 0f || side == 1 && limit == Float.POSITIVE_INFINITY) continue
            val c = if (side == 0) current - limit else limit - current
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (c > 0f) bias = c * invH
            else if (useBias) { bias = soft.biasRate * c; massScale = soft.massScale; impulseScale = soft.impulseScale }
            val sign = if (side == 0) 1f else -1f
            val old = if (side == 0) lowerImpulse else upperImpulse
            val new = max(old - m * massScale * (sign * speed() + bias) - impulseScale * old, 0f)
            if (side == 0) lowerImpulse = new else upperImpulse = new
            applyAlong(ra, rb, sign * (new - old))
        }
    }
}

/** A 3 × 3 matrix by columns, for the joints' effective masses. */
internal class Mat3(val c0: Vec3, val c1: Vec3, val c2: Vec3) {
    /** Solves M x = r (Cramer); zero when M is singular. */
    fun solve(r: Vec3): Vec3 {
        val det = c0 dot (c1 cross c2)
        // Relative test: a joint between heavy bodies has tiny entries, yet a well-conditioned matrix.
        if (abs(det) <= 1e-10f * c0.length() * c1.length() * c2.length() || det == 0f) return Vec3.ZERO
        val inv = 1f / det
        return Vec3((r dot (c1 cross c2)) * inv, (c0 dot (r cross c2)) * inv, (c0 dot (c1 cross r)) * inv)
    }
}

/** Some unit vector perpendicular to [n] (unit). */
internal fun perpendicular(n: Vec3): Vec3 =
    if (abs(n.x) >= .57735f) Vec3(n.y, -n.x, 0f).normalized() else Vec3(0f, n.z, -n.y).normalized()

/** Floats per body in the world's solver arrays (S in PhysicsWorld.kt). */
internal const val SS = 20
