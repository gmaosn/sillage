package sillage

import kotlin.math.sqrt

/**
 * A touch screen as a game pad, with nothing to set up: a finger on the left half is the left stick (it
 * appears where the finger lands), a finger dragged on the right half turns the camera, a short tap there
 * or the round button at the bottom right jumps ([Pad.CROSS]), two fingers pinching change the camera's
 * distance. The scene sees a pad ([VirtualPad]): players walk with it as with any other.
 * The platform's window reports fingers with [down], [move] and [up], on the game's thread.
 */
internal class Touches {
    private class Finger(val id: Int, var x: Float, var y: Float) { var startX = x; var startY = y; var age = 0f; var role = 0; var travelled = 0f }
    private val fingers = ArrayList<Finger>()
    private val pad = VirtualPad("Touch screen")
    private var plugged: Input? = null
    private var width = 1f; private var height = 1f
    private var lookX = 0f; private var lookY = 0f
    private var tap = 0                                   // frames the jump button stays down after a tap
    private var base: Hud.Panel? = null; private var knob: Hud.Panel? = null; private var button: Hud.Panel? = null
    private var hudOf: Hud? = null

    /** True once a finger has touched the screen. */
    var used = false
        private set

    private fun jumpCentreX() = width - height * .16f
    private fun jumpCentreY() = height * .80f
    private fun onJump(x: Float, y: Float): Boolean { val dx = x - jumpCentreX(); val dy = y - jumpCentreY(); return dx * dx + dy * dy < height * .11f * height * .11f }

    fun down(id: Int, x: Float, y: Float) {
        used = true
        val f = Finger(id, x, y)
        f.role = when {
            onJump(x, y) -> JUMP
            x < width * .45f && fingers.none { it.role == STICK } -> STICK
            else -> LOOK
        }
        fingers.add(f)
    }

    fun move(id: Int, x: Float, y: Float) {
        val f = fingers.firstOrNull { it.id == id } ?: return
        val dx = x - f.x; val dy = y - f.y
        f.travelled += sqrt(dx * dx + dy * dy)
        if (f.role == LOOK) {
            val others = fingers.filter { it !== f && it.role == LOOK }
            if (others.isEmpty()) { lookX += dx; lookY += dy }
            else {                                          // two fingers: their distance is the zoom
                val o = others[0]
                fun gap(ax: Float, ay: Float) = sqrt((ax - o.x) * (ax - o.x) + (ay - o.y) * (ay - o.y))
                pinch += (gap(x, y) - gap(f.x, f.y)) / height
            }
        }
        f.x = x; f.y = y
    }

    fun up(id: Int) {
        val f = fingers.firstOrNull { it.id == id } ?: return
        fingers.remove(f)
        if (f.role == LOOK && f.age < .25f && f.travelled < height * .02f) tap = 3
    }

    /** Every finger lifted (the application lost the screen). */
    fun cancel() { fingers.clear(); tap = 0 }

    private var pinch = 0f

    /** Once a frame, before the scene reads its pads: the fingers become the pad's stick and button, the drags turn the camera. */
    fun apply(scene: Scene, widthPixels: Int, heightPixels: Int, dt: Float) {
        width = widthPixels.toFloat(); height = heightPixels.toFloat()
        if (!used) return
        if (plugged !== scene.input) { scene.input.plug(pad); plugged = scene.input }
        for (f in fingers) f.age += dt
        val stick = fingers.firstOrNull { it.role == STICK }
        val reach = height * .13f
        if (stick != null) {
            var x = (stick.x - stick.startX) / reach; var y = (stick.startY - stick.y) / reach
            val l = sqrt(x * x + y * y)
            if (l > 1f) { x /= l; y /= l; stick.startX = stick.x - x * reach; stick.startY = stick.y + y * reach }   // the base follows a finger that goes far
            pad.stick(Stick.LEFT, x, y)
        } else pad.stick(Stick.LEFT, 0, 0)
        pad.set(Pad.CROSS, tap > 0 || fingers.any { it.role == JUMP })
        if (tap > 0) tap--
        if (lookX != 0f || lookY != 0f) {
            val k = 900f / height                           // a drag across the screen's height: about a turn and a half
            if (scene.debug.freeCamera) scene.debug.turnFree(lookX * k, lookY * k) else scene.camera.turn(lookX * k, lookY * k)
            lookX = 0f; lookY = 0f
        }
        if (pinch != 0f) { scene.camera.zoom(pinch * 12f); pinch = 0f }
        show(scene.hud, stick, reach)
    }

    // The stick under the finger and the jump button, drawn in the interface (600 units high).
    private fun show(hud: Hud, stick: Finger?, reach: Float) {
        if (hudOf !== hud) { hudOf = hud; base = null; knob = null; button = null }
        val u = 600f / height
        val white = Color(1f, 1f, 1f)
        val b = base ?: hud.panel(0, 0, 10, 10, white, opacity = .16).also { base = it }
        val k = knob ?: hud.panel(0, 0, 10, 10, white, opacity = .38).also { knob = it }
        val j = button ?: hud.panel(0, 0, 10, 10, white, opacity = .2).also { button = it }
        b.visible = stick != null; k.visible = stick != null
        if (stick != null) {
            val r = reach * 1.25f * u
            b.width = 2 * r; b.height = 2 * r; b.radius = r; b.x = stick.startX * u - r; b.y = stick.startY * u - r
            val kr = reach * .5f * u
            k.width = 2 * kr; k.height = 2 * kr; k.radius = kr; k.x = stick.x * u - kr; k.y = stick.y * u - kr
        }
        val jr = height * .085f * u
        j.width = 2 * jr; j.height = 2 * jr; j.radius = jr; j.x = jumpCentreX() * u - jr; j.y = jumpCentreY() * u - jr
        j.opacity = if (fingers.any { it.role == JUMP }) .42f else .2f
    }

    private companion object { const val STICK = 1; const val LOOK = 2; const val JUMP = 3 }
}
