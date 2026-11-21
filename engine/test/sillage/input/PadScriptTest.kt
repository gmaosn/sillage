package sillage.input

import sillage.*
import kotlin.test.*

/** La manette menée par un texte (--pad-script) : lecture, erreurs claires, déroulé dans le temps de la scène. */
class PadScriptTest {
    @Test fun lesOrdresArriventALeurHeure() {
        val script = PadScript("1 tap CROSS; 0.5 L 0,1; 2 R -1,0.5; 2 press triangle; 2.5 release TRIANGLE; 3 R2 0.7; 3 gyro 0,1,0")
        val s = Scene().apply { script.attach(this) }
        // Un ordre joué pendant une image se lit à la suivante : on regarde un peu après chaque heure.
        fun at(t: Float) { while (s.time < t) s.step(.025f) }
        val p = { s.input.pad!! }
        at(.25f); assertEquals(0f, p().leftStick.amount)
        at(.6f); assertEquals(1f, p().leftStick.y, 1e-6f, "à 0,5 s le stick gauche est poussé")
        at(1.06f); assertTrue(p().isDown(Pad.CROSS), "la croix tapée à 1 s")
        at(1.2f); assertFalse(p().isDown(Pad.CROSS), "relâchée un dixième de seconde après")
        at(2.06f); assertTrue(p().isDown(Pad.TRIANGLE)); assertEquals(-1f, p().rightStick.rawX)
        at(2.56f); assertFalse(p().isDown(Pad.TRIANGLE)); assertFalse(script.done)
        at(3.06f); assertEquals((.7f - .03f) / .97f, p().trigger(Pad.R2), 1e-5f); assertEquals(1f, p().gyro.y)
        assertTrue(script.done)
        assertNull(Scene().input.pad, "une autre scène n'a pas cette manette")
    }

    @Test fun desErreursClaires() {
        fun message(text: String) = assertFailsWith<IllegalArgumentException> { PadScript(text) }.message!!
        assertTrue("unknown order" in message("1 jump"))
        assertTrue("no button" in message("1 tap X"))
        assertTrue("takes 2 numbers" in message("1 L 0"))
        assertTrue("is not a time" in message("soon L 0,1"))
        assertTrue("needs a time and an order" in message("1"))
        assertTrue(PadScript(" ; ").done)
    }
}
