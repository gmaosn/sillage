package sillage.qa

import sillage.*
import sillage.demo.island
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import javax.imageio.ImageIO

/**
 * Les outils de qui fait un jeu, sur de vraies scènes : le débogage (F5 à F8) sur l'île et sur un gros
 * tas de caisses, ce qu'il montre et ce qu'il coûte. (Le rechargement de ./sillage dev a son essai à
 * part, tools/essai_dev.sh, et ses tests unitaires, DevTest.)
 */
fun Qa.outils() {
    controle("débogage de l'île : la physique dessinée par-dessus", "image changée (≥ 2 % des pixels), coût ≤ 2 ms par image") {
        val dossier = Path.of("build/qa/outils"); Files.createDirectories(dossier)
        val sans = dossier.resolve("ile.png"); val avec = dossier.resolve("ile-debogage.png")
        val vue = "-9,7,14,0,1,0"
        val a = game("QA", arrayOf("--headless", "--mute", "--frames", "240", "--look-at", vue, "--capture", sans.toString()), island)
        val b = game("QA", arrayOf("--headless", "--mute", "--frames", "240", "--look-at", vue, "--capture", avec.toString(), "--debug", "physics"), island)
        val (part, _) = comparer(ImageIO.read(sans.toFile()), ImageIO.read(avec.toFile()))
        val surcout = b.meanMs - a.meanMs
        "pixels changés ${"%.1f".format(part * 100)} %, image ${"%.2f".format(a.meanMs)} → ${"%.2f".format(b.meanMs)} ms (images : $avec)" to
            (part >= .02 && surcout <= 2.0)
    }

    controle("débogage d'un tas de 3 000 caisses", "image ≤ 12 ms en moyenne, contacts et vitesses compris") {
        val r = game("QA", arrayOf("--headless", "--mute", "--frames", "240", "--look-at", "0,9,16,0,1,0", "--debug", "physics")) {
            ground()
            for (n in 0 until 3000) box(.5, at = vec(n % 20 * .55 - 5, .3 + n / 400 * .55, (n / 20) % 20 * .55 - 5)).dynamic()
        }
        "image ${"%.2f".format(r.meanMs)} ms (99e centile ${"%.2f".format(r.p99Ms)})" to (r.meanMs <= 12.0)
    }

    controle("ce qui est sous la croix, dans 200 directions de l'île", "jamais d'exception, toujours une réponse") {
        val m = Scene().apply(island)
        repeat(30) { m.step(1f / 60f) }
        val rnd = Random(graine + 1200)
        var nommes = 0
        repeat(200) {
            val eye = vec(rnd.f(-8f, 8f), rnd.f(1f, 6f), rnd.f(-8f, 12f))
            val lignes = m.debug.describe(eye, rnd.dir())
            check(lignes.isNotEmpty())
            if (lignes[0].startsWith("model ") || lignes[0] == "collider (invisible)") nommes++
        }
        "$nommes regards sur 200 tombent sur un modèle ou un volume" to (nommes > 20)
    }
}
