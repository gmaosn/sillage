package sillage

import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.Body
import sillage.physics.BodyType
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.Shape
import sillage.physics.Sphere
import sillage.physics.raycast
import kotlin.math.*

/**
 * Seeing what the engine does, in any game. The keys work in every game unless [keys] is false:
 *
 * - **F5** shows the physics: the bodies' shapes (static blue-grey, moving orange, asleep brown,
 *   kinematic violet), the static meshes near the camera, the trigger zones (green), the joints
 *   (magenta), the contact points with their normals (yellow), the speed of moving bodies; faint where
 *   the scenery hides them. A cross in the middle of the screen, and a panel about what is under it
 *   (node, body, mass, speed, surface, mesh), and the physics' figures at the top.
 * - **F6** a free camera: WASD or the arrows, Space up, Ctrl down, Shift faster, the mouse turns it.
 *   The player waits; F6 again comes back.
 * - **F7** pauses the game (the picture and the free camera go on), **F8** advances one frame while paused.
 */
class Debug internal constructor(private val scene: Scene) {
    /** Whether F5–F8 do anything (a shipped game may turn them off). */
    var keys = true
    /** The physics drawn over the scene (F5). */
    var showPhysics = false
        set(v) { field = v; if (!v) removeHud() }
    /** With [showPhysics]: the contact points and their normals. */
    var showContacts = true
    /** With [showPhysics]: static meshes are drawn within this distance of the camera (their triangles are many). */
    var meshRadius = 25f
    /** The game's time stands still (F7); [step] advances one frame. */
    var paused = false
        set(v) { field = v; updateBanner() }
    /** A camera flying free (F6). */
    var freeCamera = false
        set(v) {
            if (v == field) return
            field = v
            val p = scene.player
            if (v) {
                freePosition = scene.camera.position
                val f = scene.camera.forward
                freeYaw = atan2(-f.x, -f.z) * 180f / PI.toFloat()
                freePitch = asin(f.y.coerceIn(-1f, 1f)) * 180f / PI.toFloat()
                playerHadControls = p?.controls ?: true
                p?.controls = false
            } else p?.controls = playerHadControls
            updateBanner()
        }
    /** How fast the free camera flies, metres per second (four times faster with Shift). */
    var freeSpeed = 6f

    /** Where the free camera is and looks (degrees; yaw 0 looks towards −Z). */
    var freePosition = Vec3(0f, 5f, 10f)
    var freeYaw = 0f
    var freePitch = 0f
    private var playerHadControls = true

    internal var stepOnce = false

    /** Advances one frame while [paused]. */
    fun step() { stepOnce = true }

    /** The F-keys; called by the loop before the scene's update. */
    internal fun handleKeys(input: Input) {
        if (!keys) return
        if (input.pressed(Key.F5)) showPhysics = !showPhysics
        if (input.pressed(Key.F6)) freeCamera = !freeCamera
        if (input.pressed(Key.F7)) paused = !paused
        if (input.pressed(Key.F8)) { if (!paused) paused = true; step() }
    }

    /** True when the scene's update must run this frame. */
    internal fun runsThisFrame(): Boolean {
        if (!paused) return true
        if (stepOnce) { stepOnce = false; return true }
        return false
    }

    // ---------------------------------------------------------------- the free camera

    internal fun freeForward(): Vec3 {
        val y = freeYaw * PI.toFloat() / 180f; val p = freePitch * PI.toFloat() / 180f
        return Vec3(-sin(y) * cos(p), sin(p), -cos(y) * cos(p))
    }

    internal fun turnFree(dx: Float, dy: Float) {
        val k = .12f * scene.camera.mouseSensitivity
        freeYaw -= dx * k
        freePitch = (freePitch - dy * k).coerceIn(-89f, 89f)
    }

    internal fun flyFree(input: Input, dt: Float) {
        val f = freeForward()
        val right = Vec3(-f.z, 0f, f.x).let { val l = sqrt(it.x * it.x + it.z * it.z); if (l > 1e-5f) it * (1f / l) else Vec3(1f, 0f, 0f) }
        var m = Vec3.ZERO
        if (input.isDown(Key.W) || input.isDown(Key.UP)) m += f
        if (input.isDown(Key.S) || input.isDown(Key.DOWN)) m -= f
        if (input.isDown(Key.D) || input.isDown(Key.RIGHT)) m += right
        if (input.isDown(Key.A) || input.isDown(Key.LEFT)) m -= right
        if (input.isDown(Key.SPACE)) m += Vec3.UP
        if (input.isDown(Key.LEFT_CTRL) || input.isDown(Key.RIGHT_CTRL)) m -= Vec3.UP
        val l = m.length()
        if (l > 0f) freePosition += m * (freeSpeed * (if (input.isDown(Key.SHIFT)) 4f else 1f) * dt / l)
    }

    internal fun freeView(): Mat4 = Mat4.lookAt(freePosition, freePosition + freeForward())

    // ---------------------------------------------------------------- the lines

    private var lines = FloatArray(LINE_FLOATS * 4096)
    private var vertices = 0

    private fun line(a: Vec3, b: Vec3, c: Color, alpha: Float = 1f) = line(a.x, a.y, a.z, b.x, b.y, b.z, c, alpha)

    private fun line(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, c: Color, alpha: Float = 1f) {
        if ((vertices + 2) * LINE_FLOATS > lines.size) lines = lines.copyOf(lines.size * 2)
        var o = vertices * LINE_FLOATS
        lines[o++] = ax; lines[o++] = ay; lines[o++] = az; lines[o++] = c.r; lines[o++] = c.g; lines[o++] = c.b; lines[o++] = alpha
        lines[o++] = bx; lines[o++] = by; lines[o++] = bz; lines[o++] = c.r; lines[o++] = c.g; lines[o++] = c.b; lines[o] = alpha
        vertices += 2
    }

    private fun circle(centre: Vec3, u: Vec3, v: Vec3, r: Float, c: Color, from: Float = 0f, to: Float = 2 * PI.toFloat(), steps: Int = 24) {
        var prev = centre + u * (cos(from) * r) + v * (sin(from) * r)
        for (i in 1..steps) {
            val a = from + (to - from) * i / steps
            val p = centre + u * (cos(a) * r) + v * (sin(a) * r)
            line(prev, p, c); prev = p
        }
    }

    private fun shape(shape: Shape, at: Vec3, rot: Quat, c: Color) {
        val x = rot.rotate(Vec3(1f, 0f, 0f)); val y = rot.rotate(Vec3.UP); val z = rot.rotate(Vec3(0f, 0f, 1f))
        when (shape) {
            is Box -> {
                val hx = x * shape.halfX; val hy = y * shape.halfY; val hz = z * shape.halfZ
                fun corner(i: Int) = at + (if (i and 1 != 0) hx else -hx) + (if (i and 2 != 0) hy else -hy) + (if (i and 4 != 0) hz else -hz)
                for (i in 0 until 8) for (bit in intArrayOf(1, 2, 4)) if (i and bit == 0) line(corner(i), corner(i or bit), c)
            }
            is Sphere -> {
                circle(at, x, y, shape.radius, c); circle(at, y, z, shape.radius, c); circle(at, z, x, shape.radius, c)
            }
            is Capsule -> {
                val top = at + y * shape.halfHeight; val bottom = at - y * shape.halfHeight; val r = shape.radius
                circle(top, x, z, r, c); circle(bottom, x, z, r, c)
                for (d in arrayOf(x, -x, z, -z)) line(top + d * r, bottom + d * r, c)
                val half = PI.toFloat()
                circle(top, x, y, r, c, 0f, half, 12); circle(top, z, y, r, c, 0f, half, 12)
                circle(bottom, x, y, r, c, half, 2 * half, 12); circle(bottom, z, y, r, c, half, 2 * half, 12)
            }
            // Shapes this drawing does not know yet: their bounding sphere, so that nothing is invisible.
            else -> { val r = shape.boundingRadius; circle(at, x, y, r, c); circle(at, y, z, r, c); circle(at, z, x, r, c) }
        }
    }

    private val staticColor = Color(.55f, .68f, .85f)
    private val movingColor = Color(1f, .62f, .2f)
    private val asleepColor = Color(.62f, .46f, .32f)
    private val kinematicColor = Color(.78f, .52f, 1f)
    private val meshColor = Color(.42f, .55f, .72f)
    private val zoneColor = Color(.35f, 1f, .5f)
    private val jointColor = Color(1f, .35f, .85f)
    private val contactColor = Color(1f, .9f, .25f)
    private val speedColor = Color(1f, 1f, 1f)

    /** Writes this frame's lines into [extras] (none when the physics is not shown). */
    internal fun fill(extras: FrameExtras, eye: Vec3) {
        vertices = 0
        if (showPhysics) {
            val w = scene.physics
            for (id in 0 until w.bodyCount) {
                val b = w.body(id)
                if (b.isRemoved) continue
                val c = when (b.type) {
                    BodyType.STATIC -> staticColor
                    BodyType.KINEMATIC -> kinematicColor
                    BodyType.DYNAMIC -> if (b.isSleeping) asleepColor else movingColor
                }
                shape(b.shape, b.position, b.rotation, c)
                if (b.type == BodyType.DYNAMIC && !b.isSleeping) {
                    val v = b.velocity
                    if (v.length() > .05f) line(b.position, b.position + v * .25f, speedColor, .8f)
                }
            }
            val r2 = meshRadius * meshRadius
            for (m in w.meshes) {
                val v = m.v
                for (t in 0 until m.count) {
                    val o = 9 * t
                    val dx = v[o] - eye.x; val dy = v[o + 1] - eye.y; val dz = v[o + 2] - eye.z
                    if (dx * dx + dy * dy + dz * dz > r2) continue
                    line(v[o], v[o + 1], v[o + 2], v[o + 3], v[o + 4], v[o + 5], meshColor, .45f)
                    line(v[o + 3], v[o + 4], v[o + 5], v[o + 6], v[o + 7], v[o + 8], meshColor, .45f)
                    line(v[o + 6], v[o + 7], v[o + 8], v[o], v[o + 1], v[o + 2], meshColor, .45f)
                }
            }
            for (t in w.triggers) shape(t.shape, t.position, t.rotation, zoneColor)
            for (j in w.jointList) {
                val a = j.anchorA; val b = j.anchorB
                line(a, b, jointColor)
                for (p in arrayOf(a, b)) for (d in arrayOf(Vec3(.06f, 0f, 0f), Vec3(0f, .06f, 0f), Vec3(0f, 0f, .06f))) line(p - d, p + d, jointColor)
            }
            if (showContacts) for (c in w.contacts()) {
                val p = c.point
                line(p, p + c.normal * .3f, contactColor)
                for (d in arrayOf(Vec3(.04f, 0f, 0f), Vec3(0f, 0f, .04f))) line(p - d, p + d, contactColor)
            }
        }
        if (extras.lines.size < vertices * LINE_FLOATS) extras.lines = FloatArray(lines.size)
        System.arraycopy(lines, 0, extras.lines, 0, vertices * LINE_FLOATS)
        extras.lineVertexCount = vertices
    }

    // ---------------------------------------------------------------- the panels

    private val hudElements = ArrayList<Hud.Element>()
    private var banner: Hud.Text? = null
    private var bannerPanel: Hud.Panel? = null
    private var looked: List<String> = emptyList()

    /**
     * What a ray from [eye] along [direction] meets first, in a few lines: the node (its name, or the model
     * it came from), where and how far, its body (type, shape, mass, speed, friction, surface), its mesh.
     * The debug panel shows this for the cross in the middle of the screen; a game may show it too.
     */
    fun describe(eye: Vec3, direction: Vec3): List<String> {
        val forward = direction
        val skip: (Body) -> Boolean = if (freeCamera) { _ -> false } else { b -> scene.players.any { it.character.body == b } }
        val hit = scene.physics.raycast(eye, forward, 300f, filter = { !skip(it) }) ?: return listOf("nothing under the cross")
        val out = ArrayList<String>()
        val b = hit.body
        val node = b?.tag as? Node ?: b?.let { body -> scene.players.firstOrNull { it.character.body == body } }
        when {
            node != null -> out += (node.name ?: node.source?.let { "model " + it.substringAfterLast('/') } ?: node.javaClass.simpleName.lowercase()) +
                (node.tag?.let { "  tag $it" } ?: "")
            b != null -> out += "collider (invisible)"
            else -> out += "static mesh"
        }
        out += "at " + fmt(hit.point) + "  " + num("%.1f", hit.distance) + " m away"
        if (b != null) {
            out += b.type.name.lowercase() + " " + b.shape.javaClass.simpleName.lowercase() +
                (if (b.type == BodyType.DYNAMIC) "  " + num("%.1f", b.mass) + " kg  " + (if (b.isSleeping) "asleep" else "awake") else "")
            if (b.type != BodyType.STATIC) out += "speed " + num("%.2f", b.velocity.length()) + " m/s  spin " + num("%.2f", b.angularVelocity.length()) + " rad/s"
            out += "friction " + num("%.2f", b.friction) + "  bounce " + num("%.2f", b.restitution) +
                (scene.surfaces[b.id]?.let { "  surface $it" } ?: "")
        } else hit.material?.let { out += "surface " + it.name + "  friction " + num("%.2f", it.friction) }
        node?.let { n -> out += "mesh " + n.mesh.triangleCount + " triangles" + (if (n.parts.isNotEmpty()) " + " + n.parts.size + " parts" else "") }
        return out
    }

    private fun fmt(v: Vec3) = String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x + 0f, v.y + 0f, v.z + 0f)

    /** Numbers with a point whatever the system's language: "1.5, 2.0" stays readable as two numbers. */
    private fun num(pattern: String, v: Float) = String.format(java.util.Locale.ROOT, pattern, v + 0f)

    /** The panels, while the physics is shown: figures at the top, the cross, what is under it. */
    internal fun panels(eye: Vec3, forward: Vec3) {
        if (!showPhysics) return
        looked = describe(eye, forward)
        if (hudElements.isNotEmpty()) { refreshInfo(); return }
        val hud = scene.hud
        val ink = Color.hex(0x10262E)
        hudElements += hud.panel(16, 16, 640, 30, ink, opacity = .78)
        // The figures change at every frame: rebuilt four times a second, the HUD is not rebuilt at every frame.
        hudElements += hud.text({
            val now = System.nanoTime()
            if (now - statsAt < 250_000_000L && stats.isNotEmpty()) return@text stats
            statsAt = now
            val w = scene.physics
            var awake = 0; var live = 0
            for (id in 0 until w.bodyCount) { val b = w.body(id); if (b.isRemoved) continue; live++; if (b.type == BodyType.DYNAMIC && !b.isSleeping) awake++ }
            stats = String.format(java.util.Locale.ROOT, "PHYSICS %.2f MS   BODIES %d (%d AWAKE)   CONTACTS %d   NODES %d   %d FPS",
                scene.physicsMs, live, awake, w.contactCount, scene.nodes.size, scene.fps)
            stats
        }, 28, 26, 1.25, Color.hex(0xE8F4F8))
        hudElements += hud.text("+", 0, 0, 2.5, Color.hex(0xFFF6D0), Hud.Anchor.CENTER)
        // What is under the cross, at mid-height on the left: games keep their own panels at the corners.
        val panel = hud.panel(16, 0, 100, 40, ink, Hud.Anchor.LEFT, opacity = .78)
        hudElements += panel
        val texts = List(6) { i ->
            hud.text({ looked.getOrElse(i) { "" } }, 28, 0, if (i == 0) 1.5 else 1.25,
                if (i == 0) Color.hex(0xFFE6A8) else Color.hex(0xE8F4F8), Hud.Anchor.LEFT)
        }
        hudElements += texts
        infoPanel = panel; infoTexts = texts
        layoutInfo()
    }

    private var infoPanel: Hud.Panel? = null
    private var stats = ""
    private var statsAt = 0L
    private var infoTexts: List<Hud.Text> = emptyList()

    /** The panel fits its lines (LEFT centres each element on the screen's mid-height, then moves it by y). */
    private fun layoutInfo() {
        val panel = infoPanel ?: return
        val n = looked.size.coerceAtMost(infoTexts.size)
        var width = 0f
        for (i in 0 until n) width = max(width, Hud.textWidth(looked[i], infoTexts[i].size))
        panel.width = width + 24
        panel.height = 16f * n + 12
        for ((i, t) in infoTexts.withIndex()) t.y = -panel.height / 2 + 9 + 16 * i + 3.5f * t.size
    }

    private fun refreshInfo() {
        if (infoPanel == null) return
        layoutInfo()
    }

    private fun removeHud() { for (e in hudElements) scene.hud.remove(e); hudElements.clear(); infoPanel = null; infoTexts = emptyList() }

    private fun updateBanner() {
        val text = when {
            paused && freeCamera -> "PAUSED  F7 RESUME  F8 ONE FRAME    FREE CAMERA  F6 BACK"
            paused -> "PAUSED  F7 RESUME  F8 ONE FRAME"
            freeCamera -> "FREE CAMERA  WASD SPACE CTRL SHIFT  F6 BACK"
            else -> null
        }
        banner?.let { scene.hud.remove(it) }; banner = null
        bannerPanel?.let { scene.hud.remove(it) }; bannerPanel = null
        if (text != null) {
            // Under the figures' bar, on its own background: readable over the game's panels.
            bannerPanel = scene.hud.panel(0, 54, Hud.textWidth(text, 1.5) + 32, 26, Color.hex(0x3A2A10), Hud.Anchor.TOP, opacity = .85)
            banner = scene.hud.text(text, 0, 61, 1.5, Color.hex(0xFFE6A8), Hud.Anchor.TOP)
        }
    }
}
