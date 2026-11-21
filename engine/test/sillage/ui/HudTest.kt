package sillage.ui

import sillage.*
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.*

/** L'interface sans fenêtre : mise en page, texte, focus, menus et pause, champs, et ce que coûte une interface immobile. */
class HudTest {
    private val dt = 1f / 60f

    /** Une scène, ses touches tenues et une source d'interface scriptée, et une image d'interface. */
    private class Banc {
        val scene = Scene()
        val hud get() = scene.hud
        val keys = Keys()
        val ui = UiScript()
        init { scene.input.source = KeySource { keys.down[it] }; scene.hud.source = ui }
        fun frame(): HudMesh {
            scene.input.poll()
            hud.interact(scene, 1f / 60f, 1280, 800, null)
            return hud.mesh(1.6f, 60, true)
        }
        /** Une touche frappée : enfoncée une image, relâchée la suivante. */
        fun tap(vararg k: Int) { for (c in k) keys.add(c); frame(); for (c in k) keys.remove(c); frame() }
        fun click(w: Widget) {
            ui.pointAt(w.left + w.laidWidth / 2, w.top + w.laidHeight / 2); frame()
            ui.down = true; frame(); ui.down = false; frame()
        }
    }

    /** Les touches tenues, sans rien allouer quand le jeu les lit. */
    class Keys { val down = BooleanArray(256); fun add(k: Int) { down[k] = true }; fun remove(k: Int) { down[k] = false } }

    // ------------------------------------------------------------------ layout

    @Test fun uneColonneSAjusteASonContenu() {
        val b = Banc()
        lateinit var a: Label; lateinit var c: Label
        val l = b.hud.column(24, 24, spacing = 6, padding = 10) {
            a = text("ABC", 2)                       // police de pixels : (3 × 6 − 1) × 2 = 34 de large, 14 de haut
            c = text("ABCDEFGH", 1)                  // (8 × 6 − 1) × 1 = 47, 7
        }
        b.frame()
        assertEquals(34f, a.laidWidth); assertEquals(14f, a.laidHeight)
        assertEquals(47f + 20f, l.root.laidWidth, "la plus large plus le rembourrage")
        assertEquals(14f + 6f + 7f + 20f, l.root.laidHeight, "hauteurs, espacement et rembourrage")
        assertEquals(34f, a.left); assertEquals(34f, a.top)
        assertEquals(34f + 14f + 6f, c.top)
        // Ancrée en bas à droite : le bord droit à 24 du bord du canevas (960 de large pour 16:10).
        l.anchor = Hud.Anchor.BOTTOM_RIGHT; b.frame()
        assertEquals(960f - 24f, l.root.left + l.root.laidWidth, .001f)
        assertEquals(600f - 24f, l.root.top + l.root.laidHeight, .001f)
    }

    @Test fun placementEnTraversEtLignes() {
        val b = Banc()
        lateinit var x: Label; lateinit var y: Label; lateinit var r: Box
        b.hud.column(0, 0, place = Place.CENTER) {
            x = text("A", 2); text("ABCDEFGHIJ", 2)
            r = row(spacing = 4) { y = text("A", 1); text("AB", 3) }
        }
        b.frame()
        val w = (10 * 6 - 1) * 2f
        assertEquals((w - 10f) / 2, x.left, .001f, "centré dans la colonne")
        assertEquals((w - r.laidWidth) / 2, r.left, .001f)
        assertEquals(r.left, y.left, .001f)
        assertEquals(r.top + (21f - 7f) / 2, y.top, .001f, "centré dans la hauteur de la ligne")
        // Étirés : tous de la largeur de la colonne.
        lateinit var s1: Button; lateinit var s2: Button
        b.hud.column(0, 0, place = Place.STRETCH) { s1 = button("OK") {}; s2 = button("Annuler la partie") {} }
        b.frame()
        assertEquals(s1.laidWidth, s2.laidWidth); assertTrue(s2.laidWidth > 0f)
    }

    @Test fun texteEnPlusieursLignesEtCoupure() {
        val t = LaidText()
        TextLayout.layout("AB CD\nEF", Font.PIXEL, 1f, 0f, 0f, t)
        assertEquals(2, t.lines); assertEquals(29f, t.width); assertEquals(11f + 7f, t.height)
        TextLayout.layout("AAAA BBBB CCCC", Font.PIXEL, 1f, 50f, 0f, t)
        assertEquals(3, t.lines, "coupé entre les mots à 50 unités")
        assertEquals(23f, t.width)
        TextLayout.layout("AAAA BBBB CCCC", Font.PIXEL, 1f, 60f, 0f, t)
        assertEquals(2, t.lines); assertEquals(53f, t.width)
        TextLayout.layout("AB", Font.PIXEL, 1f, 0f, 0f, t)
        assertEquals(listOf(0f, 6f, 12f), (0 until t.caretCount).map { t.caretX[it] }, "positions du curseur")
        // Centrées : la ligne courte au milieu de la plus longue.
        TextLayout.layout("ABCD\nAB", Font.PIXEL, 1f, 0f, .5f, t)
        assertEquals(6f, t.xs[4] * t.unit, .001f)
    }

    @Test fun lesPolicesDuSystemeEtLeursRemplacantes() {
        val noto = Font.find("noto sans", bold = true) ?: return println("Noto Sans absente : passé")
        assertEquals("Noto Sans", noto.family); assertEquals(700, noto.weight); assertTrue(noto.bold)
        assertNull(Font.find("Une police qui n'existe pas"))
        assertFalse(Font.sans().isPixel); assertTrue(Font.families().size > 10)
        // Les capitales font 7 × size de haut, quelle que soit la police : changer de police ne déplace rien.
        val t = LaidText(); TextLayout.layout("H", noto, 3f, 0f, 0f, t)
        assertEquals(21f, t.cap); assertEquals(21f, t.height)
        // Crénage : « AV » plus serré que A puis V.
        assertTrue(noto.width("AT", 2) < noto.width("A", 2) + noto.width("T", 2) - .3f, "crénage appliqué")
        // Un idéogramme vient d'une police de secours ; un caractère que personne n'a donne une boîte.
        val ideogram = TextLayout.fontFor(noto, '永'.code)
        if (ideogram != null) assertTrue(ideogram !== noto && ideogram.has('永'.code))
        assertNull(TextLayout.fontFor(noto, 0x10FFFD))
    }

    // ------------------------------------------------------------------ focus, buttons, menus

    @Test fun leFocusSeDeplaceAuClavierEtAuPad() {
        val b = Banc()
        var pressed = ""
        val m = b.hud.menu("MENU") { button("UN") { pressed += "1" }; button("DEUX") { pressed += "2" }; button("TROIS") { pressed += "3" } }
        b.frame(); m.open(); b.frame()
        val (un, deux, trois) = m.box.children.map { it as Button }
        assertSame(un, b.hud.focusedWidget, "le premier bouton a le focus à l'ouverture")
        b.tap(Key.DOWN); assertSame(deux, b.hud.focusedWidget)
        b.tap(Key.DOWN); b.tap(Key.DOWN); assertSame(un, b.hud.focusedWidget, "après le dernier, le premier")
        b.tap(Key.UP); assertSame(trois, b.hud.focusedWidget)
        b.tap(Key.TAB); assertSame(un, b.hud.focusedWidget)
        b.keys.add(Key.LEFT_SHIFT); b.tap(Key.TAB); b.keys.remove(Key.LEFT_SHIFT); assertSame(trois, b.hud.focusedWidget)
        b.tap(Key.ENTER); b.tap(Key.SPACE); assertEquals("33", pressed)
        // Les mêmes appels qu'une manette : déplacer, valider.
        b.hud.focusToward(0, -1); b.hud.activate(); assertEquals("332", pressed)
        // Maintenue, la flèche répète après un délai (0,42 s), puis 28 fois par seconde.
        b.hud.focus(un); b.keys.add(Key.DOWN); repeat(20) { b.frame() }
        assertSame(deux, b.hud.focusedWidget, "un seul pas avant le délai")
        var moves = 0; var last = b.hud.focusedWidget
        repeat(30) { b.frame(); if (b.hud.focusedWidget !== last) { moves++; last = b.hud.focusedWidget } }
        b.keys.remove(Key.DOWN); b.frame()
        assertTrue(moves in 8..16, "$moves répétitions en une demi-seconde")
        // Un bouton désactivé n'est ni atteint ni pressé.
        deux.enabled = false; b.hud.focus(un); b.tap(Key.DOWN); assertSame(trois, b.hud.focusedWidget)
    }

    @Test fun focusDansUneGrille() {
        val b = Banc()
        lateinit var cells: List<Button>
        b.hud.column(0, 0, Hud.Anchor.CENTER) {
            val list = ArrayList<Button>()
            row { list += button("A") {}; list += button("B") {} }
            row { list += button("C") {}; list += button("D") {} }
            cells = list
        }
        b.frame()
        b.hud.focus(cells[0])
        b.hud.focusToward(1, 0); assertSame(cells[1], b.hud.focusedWidget)
        b.hud.focusToward(0, 1); assertSame(cells[3], b.hud.focusedWidget)
        b.hud.focusToward(-1, 0); assertSame(cells[2], b.hud.focusedWidget)
        b.hud.focusToward(0, -1); assertSame(cells[0], b.hud.focusedWidget)
    }

    @Test fun laSourisSurvoleEtClique() {
        val b = Banc()
        var clicks = 0
        lateinit var ok: Button
        b.hud.column(100, 100) { ok = button("OK") { clicks++ }; button("NON") {} }
        b.frame()
        b.ui.pointAt(ok.left + 2, ok.top + 2); b.frame()
        assertSame(ok, b.hud.hoveredWidget); assertTrue(ok.hovered)
        b.click(ok); assertEquals(1, clicks)
        // Enfoncé sur le bouton, relâché ailleurs : pas de clic.
        b.ui.down = true; b.frame(); b.ui.pointAt(0, 0); b.frame(); b.ui.down = false; b.frame()
        assertEquals(1, clicks)
    }

    @Test fun echapOuvreLaPauseQuiArreteLeJeu() {
        val b = Banc()
        b.frame()
        assertFalse(b.hud.paused)
        b.tap(Key.ESCAPE)
        val pause = b.hud.pauseMenu!!
        assertTrue(pause.isOpen); assertTrue(b.hud.paused); assertTrue(b.hud.usesKeys)
        assertEquals("Resume", (b.hud.focusedWidget as Button).label)
        b.tap(Key.ESCAPE); assertFalse(pause.isOpen, "Échap referme le menu"); assertFalse(b.hud.paused)
        // Recommencer : la scène repart, le menu se ferme.
        var restarted = 0; b.scene.onRestart { restarted++ }
        b.tap(Key.ESCAPE); b.tap(Key.DOWN); b.tap(Key.ENTER)
        assertEquals(1, restarted); assertFalse(pause.isOpen)
        // Quitter.
        b.tap(Key.ESCAPE); repeat(3) { b.tap(Key.DOWN) }; assertFalse(b.hud.quitting); b.tap(Key.ENTER)
        assertTrue(b.hud.quitting)
        // Sans menu de pause, Échap quitte comme avant.
        val c = Banc(); c.hud.pauseMenu = null; c.frame(); c.tap(Key.ESCAPE); assertTrue(c.hud.quitting)
        // Un menu à soi à la place.
        val d = Banc(); val mine = d.hud.menu("A MOI") { button("RETOUR") { close() } }; d.hud.pauseMenu = mine
        d.frame(); d.tap(Key.ESCAPE); assertTrue(mine.isOpen); d.tap(Key.ENTER); assertFalse(mine.isOpen)
    }

    @Test fun leJeuSArreteDerriereLaPause() {
        // Le pas du jeu (Game.kt) ne passe plus le temps de la scène pendant la pause.
        val b = Banc(); b.frame()
        var t = 0f; b.scene.onUpdate { t += it }
        b.hud.pause()
        repeat(30) { b.frame(); if (!b.hud.paused) b.scene.update(dt) }
        assertEquals(0f, t)
        b.hud.pauseMenu!!.close()
        repeat(30) { b.frame(); if (!b.hud.paused) b.scene.update(dt) }
        assertEquals(.5f, t, .001f)
    }

    // ------------------------------------------------------------------ text fields

    @Test fun unChampDeTexteEnFrancais() {
        val b = Banc()
        lateinit var f: TextField
        var submitted = ""; var changes = 0
        b.hud.column(100, 100) { f = field("Ton nom") { submitted = it } }
        f.onChange = { changes++ }
        b.frame()
        b.click(f)
        assertSame(f, b.hud.focusedWidget); assertTrue(b.hud.usesKeys)
        b.ui.type("Été à Noël, ça"); b.frame()
        assertEquals("Été à Noël, ça", f.text); assertEquals(14, f.caret)
        b.tap(Key.BACKSPACE); assertEquals("Été à Noël, ç", f.text)
        b.tap(Key.HOME); b.tap(Key.DELETE); assertEquals("té à Noël, ç", f.text)
        // Majuscule et flèches : une sélection, remplacée par ce qu'on tape.
        b.keys.add(Key.LEFT_SHIFT); b.tap(Key.RIGHT); b.tap(Key.RIGHT); b.keys.remove(Key.LEFT_SHIFT)
        assertEquals(0 until 2, f.selection)
        b.ui.type("Hiv"); b.frame(); assertEquals("Hiv à Noël, ç", f.text)
        // Ctrl+A, Ctrl+C, Ctrl+V (les caractères de contrôle que donne la disposition du clavier).
        b.ui.type("\u0001\u0003"); b.frame(); b.tap(Key.END); b.ui.type(" / \u0016"); b.frame()
        assertEquals("Hiv à Noël, ç / Hiv à Noël, ç", f.text)
        b.tap(Key.ENTER); assertEquals(f.text, submitted)
        assertTrue(changes >= 5)
        // Ctrl et Retour arrière : un mot entier.
        b.keys.add(Key.LEFT_CTRL); b.tap(Key.BACKSPACE); b.keys.remove(Key.LEFT_CTRL)
        assertEquals("Hiv à Noël, ç / Hiv à Noël, ", f.text)
        // Échap quitte le champ ; les touches reviennent au jeu.
        b.tap(Key.ESCAPE); assertNull(b.hud.focusedWidget); assertFalse(b.hud.usesKeys)
    }

    @Test fun editionDuTexte() {
        val e = TextEditing()
        e.insert("abc"); e.left(); e.left(); e.insert("X"); assertEquals("aXbc", e.text); assertEquals(2, e.caret)
        e.end(); e.backspace(); assertEquals("aXb", e.text)
        e.home(); e.delete(); assertEquals("Xb", e.text)
        e.selectAll(); assertEquals("Xb", e.selected); e.insert("é"); assertEquals("é", e.text)
        // Un caractère hors du plan de base (deux unités UTF-16) se déplace et s'efface d'un bloc.
        e.set("a😀b"); e.end(); e.left(); e.left(); assertEquals(1, e.caret); e.right(); assertEquals(3, e.caret)
        e.backspace(); assertEquals("ab", e.text)
        e.set("un deux trois"); e.end(); e.left(word = true); assertEquals(8, e.caret); e.backspace(word = true); assertEquals("un trois", e.text)
        e.maxLength = 5; e.set(""); e.insert("abcdefgh"); assertEquals("abcde", e.text)
        e.set("abc"); e.home(); e.right(select = true); e.right(select = true); assertEquals("ab", e.cut()); assertEquals("c", e.text)
        assertFalse(e.insert("\u0007"), "pas de caractère de contrôle")
        e.place(1); e.place(3, select = true); assertEquals(1, e.caret); assertFalse(e.hasSelection, "bornée au texte")
    }

    // ------------------------------------------------------------------ what it costs

    @Test fun uneInterfaceImmobileNeCouteRien() {
        val b = Banc()
        b.hud.font = Font.sans()
        for (k in 0 until 80) b.hud.text("Une ligne immobile, numéro $k", 10, 10 + 7 * k, 1)
        b.hud.column(0, 0, Hud.Anchor.CENTER) { panel(Color.BLACK); text("Un panneau"); button("Un bouton") {}; field("Un champ") }
        b.hud.menu("MENU", pauses = false) { button("A") {}; button("B") {} }.open()
        repeat(30) { b.frame() }
        val first = b.frame()
        val rebuilds = b.hud.stats.rebuilds; val glyphs = b.hud.stats.glyphs
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = bean.currentThreadAllocatedBytes
        var other = 0
        repeat(300) { if (b.frame() !== first) other++ }
        val bytes = bean.currentThreadAllocatedBytes - before
        assertEquals(0, other, "le même maillage à chaque image")
        assertEquals(rebuilds, b.hud.stats.rebuilds, "aucune reconstruction")
        assertEquals(glyphs, b.hud.stats.glyphs, "aucun glyphe tramé de nouveau")
        println("interface immobile : $bytes octets alloués en 300 images")
        assertTrue(bytes < 300 * 16, "$bytes octets alloués en 300 images")
    }

    @Test fun lInterfaceSeRefaitQuandElleChange() {
        val b = Banc()
        var n = 1
        val t = b.hud.text({ "N = $n" }, 10, 10, 2, font = Font.sans())
        val a = b.frame(); assertSame(a, b.frame())
        n = 2; val c = b.frame(); assertNotSame(a, c)
        t.color = Color.RED; assertNotSame(c, b.frame())
        val d = b.frame(); t.color = Color.RED; assertSame(d, b.frame(), "la même valeur ne refait rien")
        // L'atlas : chaque glyphe une fois par taille et par phase.
        val g = b.hud.stats.glyphs
        n = 1; b.frame(); n = 2; b.frame()
        assertEquals(g, b.hud.stats.glyphs, "rien de neuf à tramer")
    }

    @Test fun lAtlasGranditPuisRecommence() {
        val font = Font.sans(); if (font.isPixel) return
        val atlas = GlyphAtlas(64, 128)
        val e1 = atlas.glyph(font, font.face!!.glyph('A'.code), 40f, 0)
        assertSame(e1, atlas.glyph(font, font.face.glyph('A'.code), 40f, 0), "gardé")
        for (c in "BCDEFGHIJK") atlas.glyph(font, font.face.glyph(c.code), 40f, 0)
        assertEquals(128, atlas.width, "doublé quand plein")
        val gen = atlas.generation
        for (c in "LMNOPQRSTUVWXYZ") atlas.glyph(font, font.face.glyph(c.code), 40f, 0)
        assertTrue(atlas.generation > gen, "vidé à la limite")
        // Les glyphes tramés à leur place : de l'encre dans la boîte de « Z ».
        val z = atlas.glyph(font, font.face.glyph('Z'.code), 40f, 0)
        var ink = 0; for (y in z.y until z.y + z.h) for (x in z.x until z.x + z.w) ink += atlas.pixels[y * atlas.width + x].toInt() and 255
        assertTrue(ink > 255 * 100)
    }

    @Test fun laPoliceDePixelsTombeAuMemeEndroitQuAvant() {
        // Les rectangles du texte en police de pixels, comme avant les polices : x + (6 i + colonne) × taille.
        val b = Banc()
        b.hud.text("I", 10, 20, 1.5)
        val v = b.frame().vertices
        // « I » : sa première rangée est un trait de 5 pixels de large.
        val x0 = (v[0] + 1f) * 480f; val y0 = (v[1] + 1f) * 300f
        assertEquals(10f, x0, .001f); assertEquals(20f, y0, .001f)
        assertEquals(10f + 5 * 1.5f, (v[HUD_FLOATS] + 1f) * 480f, .001f)
        assertTrue(abs(v[7]) < 1e-6f, "opaque")
    }
}
