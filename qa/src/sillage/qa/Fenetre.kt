package sillage.qa

import sillage.*
import sillage.demo.island
import java.nio.file.Files
import java.nio.file.Path

/**
 * Essai fenêtré de l'interface, dans un KWin isolé (jamais sur le bureau) :
 *
 *   ./kotlin run -m qa --main-class sillage.qa.FenetreKt --jvm-args=--enable-native-access=ALL-UNNAMED -- sortie.png
 *
 * L'île avec un champ de texte qui prend le focus : tools/kwin_clavier.py y tape de vraies touches AZERTY par le
 * compositeur (touches mortes comprises : la table du compositeur et libxkbcommon), Échap quitte le champ puis
 * ouvre la pause, le pointeur clique « Reprendre », Échap rouvre la pause, le pointeur survole « Recommencer »
 * ; la dernière image est enregistrée. L'état est écrit quatre fois par seconde.
 *
 * Arguments : l'image de sortie, le nombre d'images (1500), et « --echap » pour simuler Échap aux images 60 et 75
 * (quitter le champ, ouvrir la pause) là où rien ne peut taper de vraies touches (Xvfb).
 */
fun main(args: Array<String>) {
    val sortie = Path.of(args.getOrElse(0) { "build/tmp/fenetre-pause.png" })
    Files.createDirectories(sortie.toAbsolutePath().parent)
    val images = args.getOrElse(1) { "1500" }
    val echap = "--echap" in args
    lateinit var champ: TextField
    var image = 0
    val report = game("Sillage - essai fenêtré", arrayOf("--mute", "--frames", images, "--capture", sortie.toString())) {
        island()
        hud.font = Font.sans(); hud.theme.font = Font.sans(bold = true)
        hud.column(0, -150, Hud.Anchor.CENTER, spacing = 10) {
            panel(Color.hex(0x153D48), opacity = .9, radius = 14, padding = 20)
            text("Écris le nom de ton île", 2)
            champ = field("…", width = 420)
        }
        // --echap : une seconde après le focus, Échap enfoncé deux lectures, relâché, puis de nouveau (le jeu et
        // l'interface lisent chacun les touches, et l'interface seule quand elle a le clavier).
        var focusAt = 0L; var lectures = 0
        onUpdate {
            if (image == 0 && echap) {
                val fenetre = input.source
                input.source = KeySource { k -> fenetre.isDown(k) || (k == Key.ESCAPE && focusAt > 0 && System.nanoTime() - focusAt > 1_000_000_000L &&
                    lectures++.let { it in 0..1 || it in 6..7 }) }
            }
            if (image++ == 30) { hud.focus(champ); focusAt = System.nanoTime(); println("CHAMP_PRET"); System.out.flush() }
        }
        // L'état quatre fois par seconde, même en pause : le texte du champ, le pointeur, les boutons du menu de pause.
        Thread {
            while (true) {
                Thread.sleep(250)
                val m = hud.pauseMenu
                val boutons = if (m != null && m.isOpen) m.box.children.filterIsInstance<Button>()
                    .joinToString(" ") { "BOUTON ${it.label}=${it.left + it.laidWidth / 2},${it.top + it.laidHeight / 2}" } else ""
                println("ETAT champ=« ${champ.text} » pause=${hud.paused} focus=${(hud.focusedWidget as? Button)?.label ?: hud.focusedWidget?.javaClass?.simpleName} " +
                    "survol=${(hud.hoveredWidget as? Button)?.label} souris=${input.mouseCaptured} pointeur=${hud.pointerX},${hud.pointerY} $boutons")
            }
        }.also { it.isDaemon = true }.start()
    }
    println("FENETRE_FIN images=${report.frames} champ=« ${champ.text} »")
}
