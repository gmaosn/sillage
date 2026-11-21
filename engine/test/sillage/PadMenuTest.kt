package sillage

import kotlin.test.*

/** A gamepad drives the menus: OPTIONS, the d-pad, cross and circle, as the loop runs them. */
class PadMenuTest {
    private val dt = 1f / 60f

    /** One frame as the loop does it: input, interface, then the game unless paused. */
    private fun Scene.frame() { input.poll(); hud.interact(this, dt, 1280, 800, null); if (!hud.paused) update(dt) }

    @Test fun laManettePiloteLeMenuDePause() {
        val s = Scene().apply { ground(); player(at = vec(0, 0, 0)) }
        val pad = VirtualPad("Test pad")
        s.input.plug(pad)
        repeat(30) { s.frame() }
        assertTrue(s.player!!.onGround)

        pad.press(Pad.OPTIONS); s.frame(); pad.release(Pad.OPTIONS); s.frame()
        assertTrue(s.hud.paused, "OPTIONS opens the pause menu")
        val first = s.hud.focusedWidget as Button
        pad.press(Pad.DPAD_DOWN); s.frame(); pad.release(Pad.DPAD_DOWN); s.frame()
        val second = s.hud.focusedWidget as Button
        assertNotSame(first, second, "the d-pad moves the focus")
        // Held, the direction repeats (after 0.4 s, then every 0.12 s).
        pad.stick(Stick.LEFT, 0, 1); repeat(40) { s.frame() }; pad.stick(Stick.LEFT, 0, 0); s.frame()
        pad.press(Pad.DPAD_UP); repeat(60) { s.frame() }; pad.release(Pad.DPAD_UP); s.frame()
        assertSame(first, s.hud.focusedWidget, "back to the first button: ${(s.hud.focusedWidget as Button).label}")

        // Cross on "Resume": the menu closes, the game goes on, and the same press does not make the player jump.
        pad.press(Pad.CROSS); s.frame()
        assertFalse(s.hud.paused)
        assertTrue(s.player!!.onGround && s.player!!.velocity.y < .1f, "no jump from the cross that closed the menu: ground ${s.player!!.onGround} v ${s.player!!.velocity} wasPaused ${s.hud.paused}")
        pad.release(Pad.CROSS); s.frame()
        // A new press of cross jumps.
        pad.press(Pad.CROSS); s.frame(); s.frame()
        assertTrue(s.player!!.velocity.y > 1f, "a new press jumps")
        pad.release(Pad.CROSS)

        // Circle closes the menu too.
        pad.press(Pad.OPTIONS); s.frame(); pad.release(Pad.OPTIONS); s.frame()
        assertTrue(s.hud.paused)
        pad.press(Pad.CIRCLE); s.frame(); pad.release(Pad.CIRCLE)
        assertFalse(s.hud.paused)
    }
}
