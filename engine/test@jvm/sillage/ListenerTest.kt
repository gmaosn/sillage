package sillage

import kotlin.test.*

/** Where the sounds are heard from. */
class ListenerTest {
    @Test fun lesOreillesSontSurLaTêteDuJoueurSuivi() {
        val s = Scene().apply { ground(); player(at = vec(2, 0, 3)) }
        repeat(10) { s.step(1f / 60f) }
        val (ears, forward) = s.sounds.listenerPlace()
        assertEquals(2f, ears.x, 1e-3f); assertEquals(3f, ears.z, 1e-3f); assertEquals(1.65f, ears.y, .01f)
        assertTrue((forward - s.camera.forward).length() < 1e-6f, "turned as the camera looks")
        assertTrue((s.camera.position - ears).length() > 2f, "the camera is behind, a few metres away")
        s.listener = Listener.CAMERA
        assertEquals(s.camera.position, s.sounds.listenerPlace().first)
    }

    @Test fun sansJoueurCEstLaCaméra() {
        val s = Scene().apply { ground() }
        s.step(1f / 60f)
        assertEquals(s.camera.position, s.sounds.listenerPlace().first)
    }
}
