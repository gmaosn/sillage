package sillage

import sillage.math.Mat4
import sillage.math.Vec3
import sillage.physics.Body
import sillage.physics.BodyType
import sillage.physics.raycast
import kotlin.math.*

/** How the camera looks at the scene. */
enum class CameraMode {
    /** Behind and above the followed node, turned with the mouse; it keeps out of walls and hills. */
    THIRD_PERSON,
    /** From the followed node's eyes; the mouse turns the head, the node walks where it looks. */
    FIRST_PERSON,
    /** Where [Camera.lookAt] put it. */
    FIXED,
}

/**
 * The scene's camera. By default it follows the player in [CameraMode.THIRD_PERSON]; switch with
 * [mode] (or let the player press [switchKey]), point it yourself with [lookAt].
 */
class Camera internal constructor(private val scene: Scene) {
    var mode = CameraMode.THIRD_PERSON
    /** Pressing this key switches between third and first person (null: no key). */
    var switchKey: Int? = null
    /** The node followed (the first player by default). */
    var target: Node? = null
        get() = field ?: scene.player
    /** Third person: distance to the target (the wheel changes it within [minDistance]..[maxDistance]). */
    var distance = 7f
    var minDistance = 2.5f
    var maxDistance = 12f
    /** Third person: the point looked at, this high above the target's feet. */
    var targetHeight = 1.1f
    /** First person: the eyes, this high above the feet (from the player's height if not set). */
    var eyeHeight: Float? = null
    /** Turn of the view about the vertical, in degrees (0 looks towards −Z), shared by both modes. */
    var yaw: Float
        get() = Math.toDegrees(azimuth.toDouble()).toFloat()
        set(v) { azimuth = v * PI.toFloat() / 180f }
    /** Third person: height of the camera above the target, as an angle in degrees. */
    var elevation: Float
        get() = Math.toDegrees(elev.toDouble()).toFloat()
        set(v) { elev = (v * PI.toFloat() / 180f).coerceIn(.08f, 1.25f) }
    /** First person: looking up (positive) or down, in degrees. */
    var pitch = 0f
    /** Vertical field of view in degrees, and the near and far clipping distances in metres. */
    var fov = 58f
    var near = .08f
    var far = 200f
    var mouseSensitivity = 1f
    /** The pad stick that turns the view (null: none), and how fast at a full tilt, in degrees per second (up and down: two thirds). */
    var lookStick: Stick? = Stick.RIGHT
    var stickSpeed = 160f
    /** Which pad turns it, by slot; −1 (the default) for any. */
    var pad = -1
    /** Up on the stick or the mouse looks down instead. */
    var invertY = false
    /**
     * First person: turning a pad with motion sensors (a DualSense) turns the view, [gyroSensitivity] times
     * as much (1: the view turns as far as the pad). Turning about the vertical works however the pad is held.
     */
    var gyroAim = false
    var gyroSensitivity = 1f
    /** Where the camera is (updated every frame). */
    var position = Vec3(0f, 5f, 10f)
        private set
    /** Unit vector of where the camera looks. */
    var forward = Vec3(0f, 0f, -1f)
    /** The point the view is about (the target, or ahead of the eyes in first person): the cast shadows are drawn around it. */
    internal var focus = Vec3.ZERO
        private set

    private var azimuth = 0f
    private var elev = .38f
    private var lift = 0f                 // elevation added to clear the ground behind the target, smoothed
    private var mouseCalm = 10f           // seconds since the mouse moved: the camera only swings by itself then
    private var fixedEye = Vec3(0f, 5f, 10f); private var fixedTarget = Vec3.ZERO

    /** Puts the camera at [eye], looking at [at]; switches to [CameraMode.FIXED]. */
    fun lookAt(eye: Vec3, at: Vec3) { fixedEye = eye; fixedTarget = at; mode = CameraMode.FIXED }

    /** Follows [node] in [mode] (third person by default). */
    fun follow(node: Node, mode: CameraMode = CameraMode.THIRD_PERSON) { target = node; this.mode = mode }

    /** A horizontal direction from the view: x to the right, z forward (−1..1 each), for moving things. */
    fun moveDirection(x: Float, z: Float): Vec3 {
        val m = Vec3(cos(azimuth) * x - sin(azimuth) * z, 0f, -sin(azimuth) * x - cos(azimuth) * z)
        return if (m.lengthSquared() > 1f) m.normalized() else m
    }

    internal fun turn(dx: Float, dy0: Float) {
        val k = mouseSensitivity
        val dy = if (invertY) -dy0 else dy0
        azimuth -= dx * .0035f * k
        if (mode == CameraMode.FIRST_PERSON) pitch = (pitch - dy * .2f * k).coerceIn(-85f, 85f)
        else elev = (elev + dy * .003f * k).coerceIn(.08f, 1.25f)
        mouseCalm = 0f
    }

    /** Turns with the pad: its stick (speed growing with the tilt, for fine aiming), and in first person its gyroscope. */
    private fun steer(dt: Float) {
        val input = scene.input
        lookStick?.let { which ->
            val s = input.stick(which, pad)
            if (s != null && s.amount > 0f) {
                val k = s.amount * stickSpeed * dt * PI.toFloat() / 180f
                azimuth -= s.x * k
                val up = if (invertY) -s.y else s.y
                if (mode == CameraMode.FIRST_PERSON) pitch = (pitch + up * k * 180f / PI.toFloat() * .66f).coerceIn(-85f, 85f)
                else elev = (elev - up * k * .66f).coerceIn(.08f, 1.25f)
                mouseCalm = 0f
            }
        }
        if (gyroAim && mode == CameraMode.FIRST_PERSON) {
            val p = input.motionPad(pad) ?: return
            val tx = p.turnedAbout(0, dt); val ty = p.turnedAbout(1, dt); val tz = p.turnedAbout(2, dt)
            // About the vertical: the turn about the direction the pad feels gravity from (its accelerometer).
            val ax = p.state.accel[0]; val ay = p.state.accel[1]; val az = p.state.accel[2]
            val g = sqrt(ax * ax + ay * ay + az * az)
            val yaw = if (g > 4f) (tx * ax + ty * ay + tz * az) / g else ty
            azimuth += yaw * gyroSensitivity
            pitch = (pitch + tx * 180f / PI.toFloat() * gyroSensitivity * (if (invertY) -1f else 1f)).coerceIn(-85f, 85f)
            if (tx != 0f || yaw != 0f) mouseCalm = 0f
        }
    }

    internal fun zoom(wheel: Float) { if (mode == CameraMode.THIRD_PERSON) distance = (distance - wheel * .6f).coerceIn(minDistance, maxDistance) }

    private fun direction(az: Float, el: Float) = Vec3(sin(az) * cos(el), sin(el), cos(az) * cos(el))

    /** The view matrix for this frame. */
    internal fun view(dt: Float): Mat4 {
        mouseCalm += dt
        if (mode != CameraMode.FIXED) steer(dt)
        val t = target
        when {
            mode == CameraMode.FIXED || t == null -> {
                position = fixedEye; forward = (fixedTarget - fixedEye).normalized(); focus = fixedTarget
                return Mat4.lookAt(fixedEye, fixedTarget, Vec3.UP)
            }
            mode == CameraMode.FIRST_PERSON -> {
                val eye = t.position.plus(0f, eyeHeight ?: ((t as? Player)?.character?.height?.minus(.15f) ?: 1.6f), 0f)
                val p = pitch * PI.toFloat() / 180f
                forward = Vec3(-sin(azimuth) * cos(p), sin(p), -cos(azimuth) * cos(p))
                position = eye
                focus = eye + Vec3(forward.x, 0f, forward.z).normalized() * (scene.lighting.shadowRange * .4f)
                return Mat4.lookAt(eye, eye + forward, Vec3.UP)
            }
        }
        val physics = scene.physics
        val aim = t.position.plus(0f, targetHeight, 0f)
        // Ground behind the target (a beach, a hill): raise the camera rather than bring it closer.
        var wanted = 0f
        if (clearOfGround(aim, direction(azimuth, elev), distance) < distance * .95f)
            wanted = (1..9).map { it * .12f }.firstOrNull { l ->
                clearOfGround(aim, direction(azimuth, (elev + l).coerceAtMost(1.35f)), distance) >= distance * .95f } ?: 1.08f
        lift += (wanted - lift) * (1 - exp(-6f * dt))
        val el = (elev + lift).coerceAtMost(1.35f)
        // A building between the target and the camera: swing gently towards the nearest clear view, only
        // while the target moves and the mouse is still.
        val moving = (t as? Player)?.let { it.character.velocity.let { v -> v.x * v.x + v.z * v.z } > .16f } ?: false
        if (moving && mouseCalm > .4f && clearOfBuildings(aim, direction(azimuth, el), distance) < distance * .6f) {
            floatArrayOf(.15f, -.15f, .3f, -.3f, .45f, -.45f, .6f, -.6f, .8f, -.8f, 1f, -1f)
                .firstOrNull { clearOfBuildings(aim, direction(azimuth + it, el), distance) >= distance * .9f }
                ?.let { azimuth += sign(it) * min(abs(it), 2.5f * dt) }
        }
        val dir = direction(azimuth, el)
        val free = cameraDistance(aim, dir, distance)
        val desired = dir * free + aim
        // Cut in at once when something is in the way, smooth only the way back.
        position = if (position.distanceTo(aim) > free) desired else position.lerp(desired, 1 - exp(-12f * dt))
        forward = (aim - position).normalized()
        focus = aim
        return Mat4.lookAt(position, aim, Vec3.UP)
    }

    private fun isThin(b: Body) = b.id in scene.cameraIgnores

    /** Free distance to the ground (static meshes). */
    internal fun clearOfGround(from: Vec3, dir: Vec3, distance: Float): Float {
        val hit = scene.physics.raycast(from, dir, distance, filter = { false }) ?: return distance
        return max(.35f, hit.distance - .3f)
    }

    /** Free distance to buildings (static bodies the camera does not pass through), with the camera's own size. */
    internal fun clearOfBuildings(from: Vec3, dir: Vec3, distance: Float): Float {
        val hit = scene.physics.raycast(from, dir, distance, radius = .18f, meshes = false,
            filter = { it.type == BodyType.STATIC && !isThin(it) }) ?: return distance
        return max(.35f, hit.distance - .12f)
    }

    /**
     * Distance of the camera: buildings and the ground bring it closer; a thin thing only matters if the
     * camera would end inside it — it then steps back just behind it (at most a metre further).
     */
    internal fun cameraDistance(from: Vec3, dir: Vec3, distance: Float): Float {
        var wanted = distance
        val thin: (Body) -> Boolean = { isThin(it) }
        val end = from + dir * distance
        if (scene.physics.raycast(end, dir, .01f, radius = .18f, meshes = false, filter = thin)?.distance == 0f) {
            // The spot is inside a trunk: find its way out, coming back from a metre further.
            val out = scene.physics.raycast(end + dir, -dir, 1f, radius = .18f, meshes = false, filter = thin)
            if (out != null && out.distance > 0f) wanted = distance + (1f - out.distance) + .12f
        }
        return minOf(clearOfBuildings(from, dir, wanted), clearOfGround(from, dir, wanted))
    }
}
