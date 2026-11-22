package sillage

import sillage.physics.Box
import kotlin.test.*

/** F5–F8 without a window: the keys, the lines, the panel, the free camera, the pause. */
class DebugTest {
    private val held = HashSet<Int>()
    private fun scene(setup: Scene.() -> Unit = {}) = Scene().apply { input.source = KeySource { it in held }; setup() }

    /** One frame with [keys] held (pressed this frame), as the loop does before the update. */
    private fun Scene.press(vararg keys: Int) {
        held.addAll(keys.toList()); input.poll(); debug.handleKeys(input)
        held.removeAll(keys.toList().toSet()); input.poll()
    }

    @Test fun lesTouchesBasculentLesModes() {
        val s = scene { ground(); player(at = vec(0, 0, 0)) }
        s.press(Key.F5); assertTrue(s.debug.showPhysics)
        s.press(Key.F6); assertTrue(s.debug.freeCamera); assertFalse(s.player!!.controls, "the player waits while the camera flies")
        s.press(Key.F6); assertFalse(s.debug.freeCamera); assertTrue(s.player!!.controls)
        s.press(Key.F7); assertTrue(s.debug.paused)
        assertFalse(s.debug.runsThisFrame())
        s.press(Key.F8); assertTrue(s.debug.runsThisFrame()); assertFalse(s.debug.runsThisFrame(), "F8: one frame, no more")
        s.press(Key.F7); assertFalse(s.debug.paused); assertTrue(s.debug.runsThisFrame())
        s.debug.keys = false
        s.press(Key.F5); assertTrue(s.debug.showPhysics, "keys off: F5 changes nothing")
    }

    @Test fun lesFormesSontDessinéesEtSeulementQuandOnLeDemande() {
        val s = scene {
            box(1, at = vec(0, 3, -4)).dynamic()
            sphere(.5, at = vec(3, 3, -4)).dynamic()
            capsule(.3, 1.6, at = vec(-3, 3, -4)).dynamic()
            zone(Box(1f, 1f, 1f), at = vec(0, 1, 4))
        }
        val extras = FrameExtras()
        s.debug.fill(extras, vec(0, 2, 6))
        assertEquals(0, extras.lineVertexCount)
        s.debug.showPhysics = true
        s.debug.fill(extras, vec(0, 2, 6))
        // A box: 12 edges; a sphere: 3 circles of 24; a capsule: 2 circles, 4 sides, 4 half circles of 12; the zone: 12.
        // Moving bodies also get a line for their speed once they move.
        assertEquals(2 * (12 + 72 + 48 + 4 + 48 + 12), extras.lineVertexCount)
        // Every vertex of the box's edges is a corner of the box.
        for (v in 0 until 24) {
            val o = v * LINE_FLOATS
            assertEquals(.5f, kotlin.math.abs(extras.lines[o]), 1e-4f)
            assertEquals(.5f, kotlin.math.abs(extras.lines[o + 1] - 3f), 1e-4f)
            assertEquals(.5f, kotlin.math.abs(extras.lines[o + 2] + 4f), 1e-4f)
        }
        repeat(30) { s.update(1f / 60f) }
        s.debug.fill(extras, vec(0, 2, 6))
        assertTrue(extras.lineVertexCount > 2 * (12 + 72 + 48 + 4 + 48 + 12), "falling bodies show their speed")
    }

    @Test fun lePanneauDitCeQuiEstSousLaCroix() {
        val s = scene { box(1, at = vec(0, .5, -5)).dynamic(mass = 12).also { it.name = "crate" } }
        val lines = s.debug.describe(vec(0, .5, 0), vec(0, 0, -1))
        assertEquals("crate", lines[0])
        assertTrue(lines[1].startsWith("at (0.00, 0.50, -4.50)"), lines[1])
        assertTrue(lines.any { "12.0 kg" in it }, lines.toString())
        assertEquals("nothing under the cross", s.debug.describe(vec(0, .5, 0), vec(0, 0, 1))[0])
    }

    @Test fun laCaméraLibreVoleEtTourne() {
        val s = scene { ground(); player(at = vec(0, 0, 0)) }
        s.debug.freeCamera = true
        s.debug.freePosition = vec(0, 2, 0); s.debug.freeYaw = 0f; s.debug.freePitch = 0f
        held += Key.W; s.input.poll()
        s.debug.flyFree(s.input, .5f)
        assertEquals(-3f, s.debug.freePosition.z, 1e-4f)          // 6 m/s × 0.5 s towards −Z
        held += Key.LEFT_SHIFT; s.input.poll()
        s.debug.flyFree(s.input, .5f)
        assertEquals(-15f, s.debug.freePosition.z, 1e-4f)         // four times faster
        held.clear(); s.input.poll()
        s.debug.turnFree(100f, 0f)
        assertTrue(s.debug.freeYaw < 0f, "the mouse to the right turns right")
        val f = s.debug.freeForward()
        assertTrue(f.x > 0f, "looking right of −Z: $f")
    }
}
