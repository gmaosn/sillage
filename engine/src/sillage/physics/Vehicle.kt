package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A wheeled vehicle on a dynamic body, its chassis, in the manner of the raycast vehicles of games: each
 * wheel is a sphere swept down from its mount; a spring and a damper hold the chassis up, and the tyres
 * drive it, brake it and steer it, with a grip that gives way past its limit (it skids, it drifts).
 * The wheels are not bodies: [Wheel.center] and [Wheel.rotation] say where to draw them.
 *
 * The driver sets [throttle], [brake] and [steering]; call [update] before each [PhysicsWorld.step],
 * with the same time step. [forward] and [up] are the chassis's own axes (its model's front and top).
 */
class Vehicle(val world: PhysicsWorld, val chassis: Body, forward: Vec3 = Vec3.Z, up: Vec3 = Vec3.Y) {
    init { require(chassis.type == BodyType.DYNAMIC) { "A vehicle's chassis must be a dynamic body" } }

    private val localForward = forward.normalized()
    private val localUp = up.normalized()
    /** Right, for a driver facing [forward] with [up] above (right-handed axes: forward × up). */
    private val localRight = (localForward cross localUp).normalized()

    /** One wheel, mounted at [mount] in the chassis's frame. Its state is that of the last [update]. */
    class Wheel internal constructor(val mount: Vec3, val radius: Float, val travel: Float, val steered: Boolean, val driven: Boolean,
                                     /** Whether the handbrake holds it (the rear wheels by default). */
                                     val handbraked: Boolean) {
        /** How far the spring is pushed in (m, 0 to [travel]). */
        var compression = 0f; internal set
        var onGround = false; internal set
        /** Where the tyre touches, and the ground's normal there (when [onGround]). */
        var contact = Vec3.ZERO; internal set
        var normal = Vec3.Y; internal set
        /** What it rolls on: a body, or null for a static mesh or no ground. */
        var ground: Body? = null; internal set
        /** The force the ground holds it up with (N). */
        var load = 0f; internal set
        /** Sideways sliding speed of the tyre (m/s): above a few tenths, it skids (marks, squeal). */
        var slip = 0f; internal set
        /** Its turn about its axle (radians) and how fast it turns (rad/s). */
        var spin = 0f; internal set
        var spinSpeed = 0f; internal set
        /** Steering angle (radians, positive to the right). */
        var steer = 0f; internal set
        /** Where to draw it: its centre and its rotation in the world. */
        var center = Vec3.ZERO; internal set
        var rotation = Quat.IDENTITY; internal set
        // Worked out from the state at the start, applied together at the end.
        internal var mountPoint = Vec3.ZERO; internal var groundSpeed = Vec3.ZERO
        internal var push = Vec3.ZERO; internal var pushPoint = Vec3.ZERO; internal var braking = 0f
    }

    private val wheelList = ArrayList<Wheel>()
    val wheels: List<Wheel> get() = wheelList

    /**
     * Adds a wheel whose centre hangs at [mount] (chassis frame) when the spring is fully out, and rises by
     * up to [travel] metres. Front wheels usually steer, rear wheels are held by the handbrake.
     */
    fun addWheel(mount: Vec3, radius: Float = .35f, travel: Float = .3f, steered: Boolean = false, driven: Boolean = true,
                 handbraked: Boolean = !steered): Wheel {
        require(radius > 0f && travel > 0f) { "A wheel needs a radius and some travel" }
        return Wheel(mount, radius, travel, steered, driven, handbraked).also { wheelList.add(it) }
    }

    // ---- the driver

    /** Accelerator from −1 (reverse) to 1. Against the way it rolls, it brakes first. */
    var throttle = 0f
        set(v) { field = v.coerceIn(-1f, 1f) }
    /** Brake pedal, 0 to 1. */
    var brake = 0f
        set(v) { field = v.coerceIn(0f, 1f) }
    /** Steering wheel from −1 (left) to 1 (right). */
    var steering = 0f
        set(v) { field = v.coerceIn(-1f, 1f) }
    var handbrake = false

    // ---- its make

    /** Pull of the engine at a standstill, all driven wheels together (N); it fades to nothing at [topSpeed]. */
    var driveForce = chassis.mass * 6f
    var topSpeed = 22f
    var reverseSpeed = 6f
    /** All wheels braking together (N): about 0.9 g by default. */
    var brakeForce = chassis.mass * 9f
    /** Steering lock at a standstill (radians); less at speed, as drivers do. */
    var maxSteer = .6f
    /** How fast the wheels turn to where the steering wheel asks (rad/s). */
    var steerSpeed = 2.5f
    /** Natural frequency of the suspension (Hz) and its damping ratio (1: no bounce). */
    var suspensionHertz = 1.6f
    var suspensionDamping = .45f
    /** Tyre grip (friction coefficient), and how much of it the rolling tyres lose to rolling resistance. */
    var grip = 1.1f
    var rollingResistance = .015f
    /**
     * How much the sideways tyre forces lean the chassis (0: they push at the height of the centre of mass,
     * no roll; 1: at the road, as in reality, where a tall vehicle rolls over in a tight turn).
     */
    var rollInfluence = .3f

    /** Forward speed (m/s, negative in reverse). */
    val speed: Float get() = chassis.velocity dot chassis.rotation.rotate(localForward)
    /** How many wheels touch the ground. */
    val wheelsOnGround: Int get() = wheelList.count { it.onGround }

    private var lastBrake = 0f; private var lastHandbrake = false
    private val notMine: (Body) -> Boolean = { b -> b != chassis && b.tag !is Character }

    /**
     * Effective mass of the chassis at [lever] (from its centre) along the unit [d]: the mass a push there
     * meets, its turning included.
     */
    private fun effectiveMass(lever: Vec3, d: Vec3): Float {
        val c = lever cross d
        val k = 1f / chassis.mass + (c dot world.mulInvInertia(chassis.id, c))
        return if (k > 0f) 1f / k else 0f
    }

    /**
     * Sweeps the wheels and applies the suspension and tyre forces for a step of [dt] seconds. Every wheel
     * is worked out from the same state, then all are applied (one after the other, the first wheels
     * would steer the next ones: the car pulled to one side).
     */
    fun update(dt: Float) {
        if (dt <= 0f || wheelList.isEmpty() || chassis.isRemoved) return
        val q = chassis.rotation; val p = chassis.position
        val up = q.rotate(localUp); val fwd = q.rotate(localForward); val right = q.rotate(localRight)
        val speedNow = chassis.velocity dot fwd
        // The steering follows the wheel at its own pace, with less lock the faster it goes.
        val target = steering * maxSteer / (1f + abs(speedNow) / 12f)
        // Parked and asleep, no one touching the controls: leave it asleep (the wheels only follow it).
        val idle = chassis.isSleeping && throttle == 0f && brake == lastBrake && handbrake == lastHandbrake &&
            wheelList.all { it.steer == if (it.steered) target else 0f }
        lastBrake = brake; lastHandbrake = handbrake
        world.prepareInertia(chassis.id)
        val share = chassis.mass / wheelList.size
        val omega = 2f * PI.toFloat() * suspensionHertz
        val k = share * omega * omega
        val c = 2f * suspensionDamping * sqrt(k * share)
        val driven = max(1, wheelList.count { it.driven })
        val v0 = chassis.velocity; val w0 = chassis.angularVelocity
        // 1. Sweeps and springs, from the state at the start.
        var grounded = 0
        for (w in wheelList) {
            if (w.steered) w.steer += (target - w.steer).coerceIn(-steerSpeed * dt, steerSpeed * dt) else w.steer = 0f
            val mount = p + q.rotate(w.mount)
            val hit = world.raycast(mount, -up, w.travel, radius = w.radius, filter = notMine)
            val distance = hit?.distance ?: w.travel
            w.onGround = hit != null
            w.compression = w.travel - distance
            w.center = mount - up * distance
            w.ground = hit?.body
            w.load = 0f; w.slip = 0f
            w.mountPoint = mount
            if (hit != null) {
                grounded++
                w.contact = hit.point; w.normal = hit.normal
                val g = hit.body
                w.groundSpeed = g?.let { it.velocity + (it.angularVelocity cross (hit.point - it.position)) } ?: Vec3.ZERO
                val atMount = v0 + (w0 cross (mount - p)) - w.groundSpeed
                w.load = max(0f, k * w.compression - c * (atMount dot up))
            }
        }
        // 2. Tyres, with what the springs and gravity will do during the step foreseen (so that a brake holds on a slope).
        var lift = Vec3.ZERO; var liftTorque = Vec3.ZERO
        for (w in wheelList) if (w.onGround) { val f = up * w.load; lift += f; liftTorque += (w.mountPoint - p) cross f }
        val v1 = v0 + (world.gravity + lift / chassis.mass) * dt
        val w1 = w0 + world.mulInvInertia(chassis.id, liftTorque) * dt
        // Who brakes: the pedal (or the accelerator against the motion) all wheels, the handbrake its own.
        var brakes = 0
        for (w in wheelList) {
            var b = brake
            if (throttle != 0f && throttle * ((v1 + (w1 cross (w.contact - p))) dot fwd) < -.5f) b = max(b, abs(throttle))
            if (handbrake && w.handbraked) b = 1f
            w.braking = if (w.onGround) b else 0f
            if (w.braking > 0f) brakes++
        }
        for (w in wheelList) {
            w.push = Vec3.ZERO
            if (!w.onGround) {
                // In the air the wheel spins down, and the engine spins the driven ones.
                w.spinSpeed = w.spinSpeed * .98f + (if (w.driven) throttle * 2f else 0f)
                continue
            }
            val s = w.steer
            val heading = fwd * cos(s) + right * sin(s)
            val n = w.normal
            val along = (heading - n * (heading dot n)).let { if (it.lengthSquared() > 1e-8f) it.normalized() else fwd }
            val across = (along cross n).normalized()
            val lever = w.contact - p
            val atContact = v1 + (w1 cross lever) - w.groundSpeed
            val vf = atContact dot along; val vs = atContact dot across
            var fx = 0f
            val braking = w.braking
            if (throttle != 0f && throttle * vf >= -.5f && w.driven) {
                val limit = if (throttle > 0f) topSpeed else reverseSpeed
                fx += throttle * driveForce / driven * (1f - abs(vf) / limit).coerceIn(0f, 1f)
            }
            // Braking and rolling resistance oppose the motion, never more than what stops the braking wheels'
            // share of it (a rolling wheel, its share among all).
            val stop = abs(vf) * effectiveMass(lever, along) / (if (braking > 0f) brakes else grounded) / dt
            fx -= sign(vf) * min(braking * brakeForce / wheelList.size + rollingResistance * w.load, stop)
            // Sideways: what stops this wheel's share of the slide.
            var fy = -vs * effectiveMass(lever, across) / grounded / dt
            // Grip: the tyre holds up to grip × load; beyond, both forces shrink together and it slides.
            val most = grip * w.load
            val total = sqrt(fx * fx + fy * fy)
            if (total > most) {
                val f = if (total > 0f) most / total else 0f
                fx *= f; fy *= f
                w.slip = abs(vs) + abs(vf) * (1f - f) * .1f
            }
            if (handbrake && w.handbraked && abs(vf) > .5f) w.slip = max(w.slip, abs(vf) * .5f)
            w.push = along * fx + across * fy
            // Applied at the contact raised towards the centre of mass by (1 − rollInfluence).
            w.pushPoint = p + lever - up * ((lever dot up) * (1f - rollInfluence))
            // The wheel rolls with the ground, unless the brakes lock it.
            w.spinSpeed = if (braking >= 1f) 0f else (atContact - world.gravity * dt) dot along / w.radius
        }
        // Forces, not impulses: spread over the substeps like gravity, they balance it exactly at rest.
        if (!idle) for (w in wheelList) if (w.onGround) {
            chassis.applySteadyForce(up * w.load, w.mountPoint)
            if (w.push.lengthSquared() > 0f) chassis.applySteadyForce(w.push, w.pushPoint)
            val g = w.ground
            if (g != null && g.type == BodyType.DYNAMIC) g.applySteadyForce((w.push + up * w.load) * -1f, w.contact)
        }
        for (w in wheelList) {
            w.spin = (w.spin + w.spinSpeed * dt) % (2f * PI.toFloat())
            w.rotation = q * Quat.axisAngle(localUp, -w.steer) * Quat.axisAngle(localRight, -w.spin)
        }
    }
}
