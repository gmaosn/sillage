package sillage.qa

import sillage.*
import sillage.demo.island
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs

/** La vue de l'île des contrôles de rendu, avec le titre et l'aide encore affichés. */
private const val VUE_ILE = "0,14,20,0,1,0"

/**
 * Une source d'interface qui joue un scénario image après image : le scénario reçoit le numéro de l'image et
 * peut ouvrir des menus, déplacer le pointeur, taper. Elle est lue à chaque image, même quand le jeu est en pause.
 */
private class Scenario(private val etapes: UiScript.(Int) -> Unit) : UiSource {
    val script = UiScript()
    private var image = 0
    override fun pointerX() = script.pointerX()
    override fun pointerY() = script.pointerY()
    override fun pointerDown() = script.pointerDown()
    override fun typed(): String { script.etapes(image++); return script.typed() }
}

/** Rend une scène sans fenêtre ; l'image va dans build/qa/images/interface-[nom].png. */
private fun capture(nom: String, taille: String = "1280x800", images: Int = 60, vue: String? = VUE_ILE, setup: Scene.() -> Unit): Path {
    val f = Path.of("build/qa/images/interface-$nom.png")
    Files.createDirectories(f.parent)
    val args = arrayListOf("--headless", "--mute", "--frames", "$images", "--capture", "$f", "--size", taille)
    if (vue != null) args += listOf("--look-at", vue)
    game("QA", args.toTypedArray(), setup)
    return f
}

/** Part des pixels d'une zone (en fractions de l'image) qui diffèrent entre deux images. */
private fun ecart(a: Path, b: Path, x0: Double = 0.0, y0: Double = 0.0, x1: Double = 1.0, y1: Double = 1.0): Double {
    val ia = ImageIO.read(a.toFile()); val ib = ImageIO.read(b.toFile())
    var n = 0; var d = 0
    for (y in (y0 * ia.height).toInt() until (y1 * ia.height).toInt()) for (x in (x0 * ia.width).toInt() until (x1 * ia.width).toInt()) {
        n++; val p = ia.getRGB(x, y); val q = ib.getRGB(x, y)
        if ((0..16 step 8).any { s -> abs(((p shr s) and 255) - ((q shr s) and 255)) > 24 }) d++
    }
    return d.toDouble() / n
}

/** Le menu de démonstration : un titre, quatre boutons dont un désactivé, un texte. */
private fun Scene.menuDeTitre(): Hud.Menu {
    hud.font = Font.sans()
    hud.theme.font = Font.sans(bold = true)
    return hud.menu("Les Alizés", pauses = false) {
        button("Jouer") {}
        button("Continuer la partie") {}
        button("Réglages") {}.enabled = false
        button("Quitter") {}
        text("Une île, six coquillages", 1.5, Color.hex(0xBFD8D8)).align = Hud.Align.CENTER
    }
}

/** L'interface : polices du système, mise en page, menus, champ de texte ; captures à regarder, et ce qu'elle coûte. */
fun Qa.interfaceUi() {
    val sans = Font.sans()
    info("polices du système trouvées") {
        val t = System.nanoTime(); val familles = Font.families()
        "${familles.size} familles lisibles en ${f1((System.nanoTime() - t) / 1e6)} ms ; sans : ${sans.family} ${sans.style}, " +
            "serif : ${Font.serif().family}, mono : ${Font.mono().family}"
    }

    // ------------------------------------------------------------------ captures
    val pixel = capture("pixel") { island() }
    controle("capture : l'interface de l'île (police de pixels, panneaux ajustés)", "titre, compteur et aide dessinés") {
        val vide = capture("sans-interface") { island(); hud.clear(); hud.mouseHint = null }
        val haut = ecart(pixel, vide, .3, 0.0, .7, .2); val bas = ecart(pixel, vide, 0.0, .85, .8, 1.0)
        "titre ${pct(haut)}, aide ${pct(bas)} des pixels changés ($pixel)" to (haut > .03 && bas > .1)
    }
    for ((nom, taille) in listOf("police-x1" to "1280x800", "police-x2" to "2560x1600")) {
        val f = capture(nom, taille) { island(); hud.font = Font.sans(); hud.theme.font = Font.sans(bold = true) }
        controle("capture : l'interface de l'île en ${sans.family}, ${taille.replace('x', '×')}", "lettres pleines dans l'aide : ≥ 2000 pixels, ×4 à l'échelle 2") {
            val img = ImageIO.read(f.toFile())
            // Des lettres nettes ont un cœur de la couleur pleine du texte (crème) ; floues, elles n'en ont pas.
            var pleins = 0
            for (y in (img.height * .86).toInt() until img.height) for (x in 0 until (img.width * .7).toInt()) {
                val p = img.getRGB(x, y)
                if (abs((p shr 16 and 255) - 255) + abs((p shr 8 and 255) - 237) + abs((p and 255) - 190) < 45) pleins++
            }
            val seuil = if (taille.startsWith("2560")) 8000 else 2000
            "$pleins pixels de texte plein dans l'aide ($f)" to (pleins >= seuil)
        }
    }

    val menu = capture("menu", vue = VUE_ILE) {
        island()
        val m = menuDeTitre()
        hud.source = Scenario { i ->
            if (i == 1) m.open()
            val quitter = m.box.children[3]
            if (i == 20) pointAt(quitter.left + quitter.laidWidth * .7f, quitter.top + quitter.laidHeight / 2)   // le pointeur sur le dernier
            if (i == 25) { hud.focus(m.box.children[0]); hud.focusToward(0, 1) }   // puis le focus, au clavier, sur le deuxième
        }
    }
    controle("capture : un menu, survol et focus", "panneau, boutons et voile dessinés") {
        val d = ecart(menu, pixel, .3, .25, .7, .75)
        "${pct(d)} des pixels du centre changés ($menu)" to (d > .3)
    }

    val pause = capture("pause", images = 70) {
        island()
        hud.source = Scenario { i -> if (i == 30) hud.pause(); if (i == 45) hud.focusToward(0, 1) }
    }
    controle("capture : le menu de pause sur l'île", "menu dessiné, jeu arrêté derrière") {
        val d = ecart(pause, pixel, .35, .3, .65, .7)
        "${pct(d)} des pixels du centre changés ($pause)" to (d > .3)
    }

    val champ = capture("champ", images = 50) {
        island()
        hud.font = Font.sans(); hud.theme.font = Font.sans(bold = true)
        lateinit var nom: TextField
        hud.column(0, -40, Hud.Anchor.CENTER, spacing = 12) {
            panel(Color.hex(0x153D48), opacity = .9, radius = 14, padding = 24)
            text("Le nom de ton île", 2.2, Color.hex(0xFFCE6E))
            nom = field("Écris un nom…", width = 420)
            field("Ta devise (facultatif)", width = 420)
            text("Entrée pour valider, Échap pour quitter le champ.", 1.3, Color.hex(0xBFD8D8))
        }
        hud.source = Scenario { i ->
            if (i == 2) hud.focus(nom)
            if (i == 5) type("Île aux trésors : « Été à Noël, ça œuvre ! »")
            if (i == 30) nom.select(nom.text.indexOf("Été"), nom.text.indexOf("Été") + 3)
        }
    }
    controle("capture : un champ de texte en français", "texte, sélection et curseur dessinés") {
        val d = ecart(champ, pixel, .3, .3, .7, .6)
        "${pct(d)} des pixels du champ changés ($champ)" to (d > .2)
    }

    // ------------------------------------------------------------------ what a still interface costs
    val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    class Mesure { var refaites = IntArray(0); var glyphes = IntArray(0); var envois = IntArray(0); var octets = LongArray(0) }
    fun partie(textes: Int, changeant: Boolean): Mesure {
        val m = Mesure(); val n = 240
        m.refaites = IntArray(n); m.glyphes = IntArray(n); m.envois = IntArray(n); m.octets = LongArray(n)
        var image = 0
        game("QA", arrayOf("--headless", "--mute", "--frames", "$n", "--size", "640x400")) {
            ground(); player()
            hud.font = Font.sans()
            for (k in 0 until textes) hud.text("Texte immobile numéro $k, à l'écran", 10 + (k % 4) * 230, 10 + (k / 4) * 14, 1.1)
            hud.text("Une ligne", 10, 10, 1.1, anchor = Hud.Anchor.BOTTOM_LEFT)
            if (textes > 0) {
                val fixe = "Score : 7"
                hud.column(20, 20, Hud.Anchor.BOTTOM_RIGHT) { panel(Color.BLACK, .6); text({ if (changeant) "Score : ${image / 10}" else fixe }, 2); button("Un bouton") {} }
                hud.menu("Menu ouvert", pauses = false) { button("Reprendre") {}; field("Un champ") }.open()
            }
            onUpdate {
                val i = image++
                if (i < n) { m.refaites[i] = hud.stats.rebuilds; m.glyphes[i] = hud.stats.glyphs; m.envois[i] = hud.stats.atlasUploads; m.octets[i] = bean.currentThreadAllocatedBytes }
            }
        }
        return m
    }
    controle("interface immobile : rien n'est refait", "aucune reconstruction, aucun glyphe ni envoi d'atlas après la 30e image") {
        val m = partie(120, false)
        val r = m.refaites.last() - m.refaites[30]; val g = m.glyphes.last() - m.glyphes[30]; val e = m.envois.last() - m.envois[30]
        "$r reconstructions, $g glyphes, $e envois en 210 images (${m.glyphes.last()} glyphes tramés en tout, ${m.refaites[30]} reconstructions au début)" to (r == 0 && g == 0 && e == 0)
    }
    controle("un texte qui change : refait à chaque changement, seuls les glyphes neufs tramés", "une reconstruction par changement, ≤ 40 glyphes neufs (10 chiffres × 4 phases)") {
        val m = partie(120, true)
        val r = m.refaites.last() - m.refaites[30]; val g = m.glyphes.last() - m.glyphes[30]; val e = m.envois.last() - m.envois[30]
        val changements = (239 / 10) - (30 / 10)
        "$r reconstructions pour $changements changements, $g glyphes neufs, $e envois d'atlas" to (r == changements && g <= 40 && e <= g)
    }
    controle("aucune allocation par image pour une interface immobile", "≤ 64 octets par image de plus qu'avec une seule ligne de texte") {
        val avec = partie(120, false); val sans = partie(0, false)
        fun parImage(m: Mesure) = (m.octets[230] - m.octets[100]) / 130.0
        val d = parImage(avec) - parImage(sans)
        "${f1(parImage(avec))} octets par image avec 120 textes, un menu et un champ, ${f1(parImage(sans))} avec une ligne : ${f1(d)} de plus" to (d <= 64.0)
    }
}
