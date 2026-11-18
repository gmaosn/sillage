package sillage.qa

import sillage.RunReport
import sillage.Glb
import sillage.demo.island
import sillage.game
import sillage.math.Vec3
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.*

/** Rend l'île sans fenêtre et sans son ; rend le bilan, l'image est écrite dans [capture]. */
fun rendreIle(capture: Path?, images: Int, vue: String? = null, taille: String? = null): RunReport {
    val args = ArrayList(listOf("--headless", "--mute", "--frames", images.toString()))
    if (capture != null) { Files.createDirectories(capture.parent); args += listOf("--capture", capture.toString()) }
    if (vue != null) args += listOf("--look-at", vue)
    if (taille != null) args += listOf("--size", taille)
    return game("QA", args.toTypedArray(), island)
}

/** Écart entre deux images : part des pixels dont une composante diffère de plus de 16, et écart moyen (0–255). */
fun comparer(a: BufferedImage, b: BufferedImage): Pair<Double, Double> {
    require(a.width == b.width && a.height == b.height) { "tailles ${a.width}×${a.height} et ${b.width}×${b.height}" }
    var differents = 0L; var somme = 0L
    for (y in 0 until a.height) for (x in 0 until a.width) {
        val p = a.getRGB(x, y); val q = b.getRGB(x, y)
        var m = 0
        for (s in intArrayOf(0, 8, 16)) { val d = abs(((p shr s) and 255) - ((q shr s) and 255)); somme += d; m = max(m, d) }
        if (m > 16) differents++
    }
    val n = a.width.toLong() * a.height
    return differents.toDouble() / n to somme.toDouble() / (3 * n)
}

private fun statut(cle: String): Long = Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith(cle) }.split(Regex("\\s+"))[1].toLong()
private fun descripteurs(): Int = Files.list(Path.of("/proc/self/fd")).use { it.count().toInt() }

fun Qa.rendu() {
    val dossier = Path.of("build/qa/images"); val refs = Path.of("qa/reference")
    val vues = linkedMapOf(
        "ile" to "0,14,20,0,1,0", "maison" to "-0.5,3.2,3.5,-3,2.2,-2", "ponton" to "1,3,15,-2.5,0.8,10",
        "phare" to "8,4,2,4,4,-3", "marin" to "0.9,2.75,1.7,-1,2.35,4")
    for ((nom, vue) in vues) {
        val ref = refs.resolve("vue-$nom.png"); val img = dossier.resolve("vue-$nom.png")
        if (refaire || !Files.exists(ref)) {
            info("vue « $nom » : image de référence") {
                rendreIle(img, 60, vue); Files.createDirectories(refs); Files.copy(img, ref, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                "référence enregistrée dans $ref"
            }
        } else controle("vue « $nom » comparée à la référence", "≤ 0,2 % de pixels, écart moyen ≤ 0,5") {
            rendreIle(img, 60, vue)
            val (part, moyen) = comparer(ImageIO.read(img.toFile()), ImageIO.read(ref.toFile()))
            "${pct(part)} de pixels différents, écart moyen ${f2(moyen)}" to (part <= .002 && moyen <= .5)
        }
    }

    controle("rendu reproductible (même vue deux fois)", "identique au pixel") {
        val a = dossier.resolve("repro-a.png"); val b = dossier.resolve("repro-b.png")
        rendreIle(a, 45, vues["maison"]); rendreIle(b, 45, vues["maison"])
        val (part, moyen) = comparer(ImageIO.read(a.toFile()), ImageIO.read(b.toFile()))
        "${pct(part)} de pixels différents, écart moyen ${f3(moyen)}" to (part == 0.0 && moyen == 0.0)
    }

    controle("pas de fuite (12 moteurs de rendu ouverts et fermés)", "mémoire résidente +≤ 40 Mo, descripteurs stables") {
        val rss = ArrayList<Long>(); val fds = ArrayList<Int>()
        repeat(12) { rendreIle(null, 2, taille = "320x200"); System.gc(); Thread.sleep(50); rss.add(statut("VmRSS:")); fds.add(descripteurs()) }
        val croissance = (rss.last() - rss[2]) / 1024.0
        "mémoire ${rss[2] / 1024} → ${rss.last() / 1024} Mo (${"%+.1f".format(croissance)}), descripteurs ${fds[2]} → ${fds.last()}" to
            (croissance <= 40 && fds.last() <= fds[2])
    }

    controle("tailles d'image extrêmes", "17×13 à 3840×2160, image juste et non vide") {
        val faux = ArrayList<String>()
        for (t in listOf("17x13", "333x211", "1920x1080", "3840x2160")) {
            val f = dossier.resolve("taille-$t.png")
            rendreIle(f, 3, vues["ile"], t)
            val img = ImageIO.read(f.toFile())
            val (l, h) = t.split('x').map { it.toInt() }
            val couleurs = HashSet<Int>(); for (y in 0 until img.height step max(1, img.height / 40)) for (x in 0 until img.width step max(1, img.width / 40)) couleurs.add(img.getRGB(x, y))
            if (img.width != l || img.height != h) faux.add("$t rendu en ${img.width}×${img.height}")
            if (couleurs.size < 4) faux.add("$t presque uni (${couleurs.size} couleurs)")
        }
        (if (faux.isEmpty()) "4 tailles justes" else faux.joinToString()) to faux.isEmpty()
    }

    // Géométrie des modèles de la démo.
    val modeles = listOf("terrain", "village", "marin", "palmiers", "ciel", "port", "caisse", "ballon")
    controle("modèles de la démo : triangles valides", "aucune valeur non finie, aucun triangle dégénéré dans les collisions") {
        val lignes = ArrayList<String>(); var ok = true
        for (nom in modeles) {
            val m = Glb.loadModel("models/$nom.glb"); val (pos, mats) = m.surfaces()
            var nonFinis = 0; var degeneres = 0
            for (t in mats.indices) {
                val a = Vec3(pos[9 * t], pos[9 * t + 1], pos[9 * t + 2]); val b = Vec3(pos[9 * t + 3], pos[9 * t + 4], pos[9 * t + 5]); val c = Vec3(pos[9 * t + 6], pos[9 * t + 7], pos[9 * t + 8])
                if (!a.isFinite() || !b.isFinite() || !c.isFinite()) nonFinis++
                else if (((b - a) cross (c - a)).length() < 1e-9f) degeneres++
            }
            if (nonFinis > 0 || (degeneres > 0 && nom == "terrain")) ok = false
            if (nonFinis > 0 || degeneres > 0) lignes.add("$nom : $nonFinis non finis, $degeneres dégénérés")
        }
        (if (lignes.isEmpty()) "${modeles.size} modèles sains" else lignes.joinToString(" ; ")) to ok
    }

    controle("solides de collision tournés vers l'extérieur", "terrain, ponton et toit") {
        val faux = ArrayList<String>(); val vus = ArrayList<String>()
        for ((nom, materiaux) in listOf("terrain" to listOf("herbe", "sable", "bois"), "village" to listOf("toit"))) {
            val m = Glb.loadModel("models/$nom.glb"); val (pos, mats) = m.surfaces()
            for (mat in materiaux) {
                val id = m.materials.indexOf(mat)
                val tris = mats.indices.filter { mats[it] == id }.map { t -> Triple(Vec3(pos[9 * t], pos[9 * t + 1], pos[9 * t + 2]), Vec3(pos[9 * t + 3], pos[9 * t + 4], pos[9 * t + 5]), Vec3(pos[9 * t + 6], pos[9 * t + 7], pos[9 * t + 8])) }
                for ((k, comp) in composantes(tris).withIndex()) {
                    val verdict = orientation(comp, sol = mat == "herbe" || mat == "sable")
                    vus.add("$nom/$mat#$k ${verdict.first}")
                    if (!verdict.second) faux.add("$nom/$mat#$k ${verdict.first}")
                }
            }
        }
        (if (faux.isEmpty()) "${vus.size} parties justes" else "à l'envers : " + faux.joinToString()) to faux.isEmpty()
    }
}

/** Parties connexes d'un ensemble de triangles (sommets partagés à 0,1 mm près). */
private fun composantes(tris: List<Triple<Vec3, Vec3, Vec3>>): List<List<Triple<Vec3, Vec3, Vec3>>> {
    val parent = IntArray(tris.size) { it }
    fun racine(i: Int): Int { var r = i; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
    val parSommet = HashMap<Triple<Long, Long, Long>, Int>()
    fun cle(v: Vec3) = Triple(Math.round(v.x * 1e4), Math.round(v.y * 1e4), Math.round(v.z * 1e4))
    for ((i, t) in tris.withIndex()) for (v in listOf(t.first, t.second, t.third)) {
        val j = parSommet.putIfAbsent(cle(v), i) ?: continue
        parent[racine(i)] = racine(j)
    }
    return tris.indices.groupBy { racine(it) }.values.map { ids -> ids.map { tris[it] } }
}

/**
 * Orientation d'une partie : fermée, son volume signé doit être positif (faces vers l'extérieur) ; un sol
 * doit regarder vers le haut ; sinon (un toit ouvert dessous), chaque face doit s'éloigner du centre.
 */
private fun orientation(tris: List<Triple<Vec3, Vec3, Vec3>>, sol: Boolean): Pair<String, Boolean> {
    fun n(t: Triple<Vec3, Vec3, Vec3>) = (t.second - t.first) cross (t.third - t.first)
    // Comme le moteur (TriangleMesh), on ignore les triangles sans aire : ils n'ont pas d'orientation.
    @Suppress("NAME_SHADOWING") val tris = tris.filter { n(it).length() >= 1e-9f }
    val aretes = HashMap<Pair<Triple<Long, Long, Long>, Triple<Long, Long, Long>>, Int>()
    fun cle(v: Vec3) = Triple(Math.round(v.x * 1e4), Math.round(v.y * 1e4), Math.round(v.z * 1e4))
    for (t in tris) for ((a, b) in listOf(t.first to t.second, t.second to t.third, t.third to t.first)) aretes.merge(cle(a) to cle(b), 1, Int::plus)
    val fermee = aretes.all { (k, v) -> v == 1 && aretes[k.second to k.first] == 1 }
    if (fermee) {
        val volume = tris.sumOf { (it.first dot (it.second cross it.third)).toDouble() } / 6
        return "fermée, volume ${"%.3f".format(volume)} m³" to (volume > 0)
    }
    if (sol) {
        val bas = tris.count { n(it).y <= 0f }
        return "sol, $bas faces vers le bas" to (bas == 0)
    }
    val centre = tris.fold(Vec3.ZERO) { s, t -> s + t.first + t.second + t.third } / (3f * tris.size)
    val dedans = tris.count { (n(it) dot ((it.first + it.second + it.third) / 3f - centre)) <= 0f }
    return "ouverte, $dedans faces vers le centre" to (dedans == 0)
}
