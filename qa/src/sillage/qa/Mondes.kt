package sillage.qa

import sillage.*
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Les grands mondes : un kilomètre carré de 40 000 blocs (une forêt de piliers et de caisses), vu de
 * l'intérieur. Ce que la caméra ne voit pas (hors du champ, au-delà de la brume) n'est pas dessiné, et
 * l'image doit rester la même qu'en dessinant tout (--no-cull).
 */
fun Qa.mondes() {
    val monde: Scene.() -> Unit = {
        ground(size = 1200)
        lighting.fogStart = 40f; lighting.fogEnd = 140f
        val pilier = Mesh.box(1f, 6f, 1f, Color.STONE)
        val caisse = Mesh.box(1.2f, 1.2f, 1.2f, Color.WOOD)
        var n = 0
        for (i in 0 until 200) for (j in 0 until 200) {
            val x = -500f + i * 5f + (j % 3); val z = -500f + j * 5f + (i % 4)
            if ((i - 100) * (i - 100) + (j - 100) * (j - 100) < 9) continue       // une clairière au milieu
            mesh(if ((i + j) % 3 == 0) caisse else pilier, at = vec(x, if ((i + j) % 3 == 0) .6 else 3, z)); n++
        }
    }
    val dossier = Path.of("build/qa/mondes"); Files.createDirectories(dossier)
    val vue = "0,4,8,30,2,-60"
    fun partie(cull: Boolean): Pair<RunReport, Path> {
        val image = dossier.resolve(if (cull) "foret.png" else "foret-tout.png")
        val args = arrayListOf("--headless", "--mute", "--frames", "${cas(300, 600)}", "--look-at", vue, "--capture", image.toString())
        if (!cull) args += "--no-cull"
        return game("QA", args.toTypedArray(), monde) to image
    }
    controle("forêt de 40 000 blocs : même image en dessinant moins", "image identique (≤ 0,05 % de pixels), GPU au moins 3 fois moindre, CPU 1,5 fois") {
        val (tout, imgTout) = partie(cull = false)
        val (vu, imgVu) = partie(cull = true)
        val (part, _) = comparer(ImageIO.read(imgTout.toFile()), ImageIO.read(imgVu.toFile()))
        val cpu = tout.meanMs / vu.meanMs; val gpu = if (vu.gpuMs > 0) tout.gpuMs / vu.gpuMs else 0.0
        ("pixels différents ${"%.3f".format(part * 100)} % ; tout dessiner : CPU ${"%.2f".format(tout.meanMs)} ms, GPU ${"%.2f".format(tout.gpuMs)} ms ; " +
            "en ne dessinant que le visible : CPU ${"%.2f".format(vu.meanMs)} ms, GPU ${"%.2f".format(vu.gpuMs)} ms (× ${"%.1f".format(cpu)} et × ${"%.1f".format(gpu)})") to
            (part <= .0005 && cpu >= 1.5 && gpu >= 3.0)
    }
}
