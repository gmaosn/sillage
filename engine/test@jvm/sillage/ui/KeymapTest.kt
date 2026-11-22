package sillage.ui

import sillage.Key
import sillage.platform.KeyboardText
import sillage.platform.Xkb
import kotlin.test.*

/**
 * Ce que tapent les touches, à travers la disposition du clavier : un clavier français (AZERTY) compilé par
 * libxkbcommon depuis ses noms, avec la table de composition de la langue (touches mortes).
 */
class KeymapTest {
    private fun azerty(): KeyboardText? {
        if (!Xkb.available) { println("libxkbcommon absente : passé"); return null }
        return KeyboardText().also { it.keymapNames(layout = "fr", model = "pc105"); it.repeats = false }
    }
    private var t = 0L
    private fun KeyboardText.tap(code: Int) { key(code, true, t, tracksModifiers = true); key(code, false, t, tracksModifiers = true); t += 1_000_000 }
    private fun KeyboardText.with(modifier: Int, code: Int) { key(modifier, true, t, true); tap(code); key(modifier, false, t, true) }

    @Test fun lesAccentsDuClavierFrancais() {
        val k = azerty() ?: return
        assertTrue(k.usesXkb)
        k.tap(Key.DIGIT_2); k.tap(Key.DIGIT_7); k.tap(Key.DIGIT_9); k.tap(Key.DIGIT_0)
        assertEquals("éèçà", k.take())
        // Les lettres à leur place AZERTY : la touche A du QWERTY donne q, celle du Q donne a.
        k.tap(Key.A); k.tap(Key.Q); k.tap(Key.W); k.tap(Key.SEMICOLON)
        assertEquals("qazm", k.take())
        k.with(Key.LEFT_SHIFT, Key.DIGIT_2); k.with(Key.LEFT_SHIFT, Key.Q); k.with(Key.RIGHT_ALT, Key.DIGIT_0)
        assertEquals("2A@", k.take(), "Maj et AltGr")
        k.close()
    }

    @Test fun lesTouchesMortes() {
        val k = azerty() ?: return
        // ^ puis e : ê ; ¨ (Maj + ^) puis e : ë ; ^ puis espace : ^ seul.
        k.tap(Key.LEFT_BRACKET); assertEquals("", k.take(), "rien tant que la séquence n'est pas finie")
        k.tap(Key.E); assertEquals("ê", k.take())
        k.with(Key.LEFT_SHIFT, Key.LEFT_BRACKET); k.tap(Key.E); k.tap(Key.LEFT_BRACKET); k.tap(Key.I); k.tap(Key.LEFT_BRACKET); k.with(Key.LEFT_SHIFT, Key.E)
        assertEquals("ëîÊ", k.take())
        k.tap(Key.LEFT_BRACKET); k.tap(Key.SPACE); assertEquals("^", k.take())
        k.close()
    }

    @Test fun ctrlEtLesTouchesDeCommande() {
        val k = azerty() ?: return
        // Ctrl + la touche marquée A (celle du Q en QWERTY) : le caractère de contrôle 1, que l'interface lit comme « tout sélectionner ».
        k.with(Key.LEFT_CTRL, Key.Q); k.with(Key.LEFT_CTRL, Key.C); k.with(Key.LEFT_CTRL, Key.V)
        assertEquals("\u0001\u0003\u0016", k.take())
        // Entrée, Retour arrière, Tab, Échap ne tapent rien : l'interface les lit comme des touches.
        k.tap(Key.ENTER); k.tap(Key.BACKSPACE); k.tap(Key.TAB); k.tap(Key.ESCAPE); k.tap(Key.DELETE)
        assertEquals("", k.take())
        k.close()
    }

    @Test fun uneToucheTenueSeRepete() {
        val k = azerty() ?: return
        k.repeats = true; k.repeatRate = 25; k.repeatDelay = 600
        k.key(Key.DIGIT_2, true, 0L)
        k.repeat(500_000_000L); assertEquals("é", k.take(), "pas avant le délai")
        k.repeat(600_000_000L + 100_000_000L); assertEquals("éééé".take(3), k.take(), "à 25 par seconde après 0,6 s")
        k.key(Key.DIGIT_2, false, 700_000_000L); k.repeat(900_000_000L); assertEquals("", k.take())
        k.close()
    }

    @Test fun sansLaBibliothequeUnClavierAmericain() {
        val k = KeyboardText()
        // Sans table chargée, la table américaine sert.
        if (k.usesXkb) return
        k.tap(Key.Q); k.with(Key.LEFT_SHIFT, Key.DIGIT_2); k.tap(Key.SPACE)
        assertEquals("q@ ", k.take())
        k.close()
    }
}
