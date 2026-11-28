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

/** Rend l'île sans fenêtre et sans son ; rend le bilan, l'image est écrite dans [capture]. [moteur] : « gl » pour le moteur OpenGL ES (Vulkan sinon). */
fun rendreIle(capture: Path?, images: Int, vue: String? = null, taille: String? = null, heure: Float? = null, moteur: String? = null, debogage: String? = null): RunReport {
    val args = ArrayList(listOf("--headless", "--mute", "--frames", images.toString()))
    if (moteur != null) args += listOf("--renderer", moteur)
    if (debogage != null) args += listOf("--debug", debogage)
    if (heure != null) args += listOf("--hour", heure.toString())
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
        "phare" to "8,4,2,4,4,-3", "marin" to "0.9,2.75,1.7,-1,2.35,4", "nuit" to "12.5,3.4,8.5,2,1.6,-1")
    // La nuit : le feu de camp (flammes, étincelles, fumée, lumière), la lampe du phare, les fenêtres, les lucioles.
    val heures = mapOf("nuit" to 21.5f)
    for ((nom, vue) in vues) {
        val ref = refs.resolve("vue-$nom.png"); val img = dossier.resolve("vue-$nom.png")
        if (refaire || !Files.exists(ref)) {
            info("vue « $nom » : image de référence") {
                rendreIle(img, 60, vue, heure = heures[nom]); Files.createDirectories(refs); Files.copy(img, ref, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                "référence enregistrée dans $ref"
            }
        } else controle("vue « $nom » comparée à la référence", "≤ 0,2 % de pixels, écart moyen ≤ 0,5") {
            rendreIle(img, 60, vue, heure = heures[nom])
            val (part, moyen) = comparer(ImageIO.read(img.toFile()), ImageIO.read(ref.toFile()))
            "${pct(part)} de pixels différents, écart moyen ${f2(moyen)}" to (part <= .002 && moyen <= .5)
        }
    }

    // Une plaque à 1,5 m du sol, moitié peinte, moitié découpée (alpha 0) : son ombre n'est que celle de la moitié
    // peinte. La scène commence sans ombres portées et les allume en route (les cartes sont faites à ce moment),
    // puis une autre les éteint en route.
    fun ombresDecoupees(moteur: String?) = controle("ombres${if (moteur == null) "" else " ($moteur)"} : découpe alpha respectée, allumées et éteintes en cours de jeu", "sous la moitié peinte sombre, sous la découpe et sans ombres clair") {
        val plaque = dossier.resolve("plaque.glb"); Files.createDirectories(dossier); Files.write(plaque, plaqueDecoupee())
        fun centre(nom: String, x: Float, ombres: Boolean): Int {
            val img = dossier.resolve("decoupe-$nom${if (moteur == null) "" else "-$moteur"}.png")
            game("QA", arrayOf("--headless", "--mute", "--frames", "40", "--size", "160x120", "--capture", img.toString(),
                "--look-at", "$x,1.2,2.5,$x,0,-0.75") + (if (moteur == null) emptyArray() else arrayOf("--renderer", moteur))) {     // de côté, sous la plaque
                ground(color = sillage.Color(.8f, .8f, .8f)); autoTextures = false
                lighting.shadows = !ombres; lighting.outlines = 0f; lighting.vignette = 0f
                model(plaque.toString())
                after(.2) { lighting.shadows = ombres }
            }
            val c = ImageIO.read(img.toFile()).getRGB(80, 60)
            return ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3
        }
        // Le soleil par défaut vient de (−0,45 ; 0,8 ; 0,4) : l'ombre d'un point à 1,5 m tombe à (+0,84 ; −0,75).
        val peinte = centre("peinte", 1.34f, true); val decoupe = centre("decoupe", .34f, true)
        val loin = centre("loin", -4f, true); val eteinte = centre("eteinte", 1.34f, false)
        "peinte $peinte, découpe $decoupe, plein soleil $loin, ombres éteintes $eteinte (0–255)" to
            (peinte < loin - 25 && abs(decoupe - loin) <= 4 && abs(eteinte - loin) <= 4)
    }
    ombresDecoupees(null)

    controle("rendu reproductible (même vue deux fois)", "identique au pixel") {
        val a = dossier.resolve("repro-a.png"); val b = dossier.resolve("repro-b.png")
        rendreIle(a, 45, vues["maison"]); rendreIle(b, 45, vues["maison"])
        val (part, moyen) = comparer(ImageIO.read(a.toFile()), ImageIO.read(b.toFile()))
        "${pct(part)} de pixels différents, écart moyen ${f3(moyen)}" to (part == 0.0 && moyen == 0.0)
    }

    // Les commandes d'une image enregistrées sur plusieurs fils (VulkanRenderer, RecordPool) : les mêmes, dans le même
    // ordre, que sur un seul fil (sillage.recordThreads=1), donc la même image à l'octet ; de jour, et de nuit (lampes
    // et leurs ombres, particules). Avec 4, chaque image est faite sur plusieurs fils, même courte (la scène de l'île,
    // plus de 40 maillages, est alors coupée en tampons secondaires).
    controle("enregistrement sur plusieurs fils : même image que sur un seul", "fichiers identiques à l'octet") {
        val faux = ArrayList<String>()
        fun rendre(nom: String, fils: String): ByteArray {
            val img = dossier.resolve("fils-$nom-$fils.png")
            System.setProperty("sillage.recordThreads", fils)
            try { rendreIle(img, 90, vues[nom], heure = heures[nom]) } finally { System.clearProperty("sillage.recordThreads") }
            return Files.readAllBytes(img)
        }
        for (nom in listOf("ile", "nuit")) if (!rendre(nom, "1").contentEquals(rendre(nom, "4"))) faux += nom
        (if (faux.isEmpty()) "vues « ile » et « nuit » identiques" else "différentes : ${faux.joinToString()}") to faux.isEmpty()
    }

    // La mémoire résidente oscille de ±25 Mo d'un moteur à l'autre (tas, pilote) : on compare la médiane de quatre
    // mesures au début (après deux pour s'installer) à celle des quatre dernières. Une fuite d'un moteur de rendu,
    // quelques Mo à chaque fois, monte de plus de 40 Mo en dix moteurs.
    controle("pas de fuite (16 moteurs de rendu ouverts et fermés)", "mémoire résidente +≤ 40 Mo (médianes), descripteurs stables") {
        val rss = ArrayList<Long>(); val fds = ArrayList<Int>()
        repeat(16) { rendreIle(null, 2, taille = "320x200"); System.gc(); Thread.sleep(50); rss.add(statut("VmRSS:")); fds.add(descripteurs()) }
        fun median(xs: List<Long>) = xs.sorted().let { (it[1] + it[2]) / 2 }
        val debut = median(rss.subList(2, 6)); val fin = median(rss.subList(12, 16))
        val croissance = (fin - debut) / 1024.0
        "mémoire ${debut / 1024} → ${fin / 1024} Mo (${"%+.1f".format(croissance)}), descripteurs ${fds[2]} → ${fds.last()}" to
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

    // Le second moteur de rendu (OpenGL ES 3.0 / WebGL 2, ici par EGL sans fenêtre) : les mêmes shaders, les mêmes
    // passes ; ses images sont comparées à celles de Vulkan. Les écarts attendus sont ceux de l'anticrénelage (place
    // des échantillons) et du filtrage, sur quelques pixels de bord : mesurés le 22 novembre 2026 sur Arc (Mesa 26.3),
    // au plus 0,0025 % de pixels à plus de 16 et un écart moyen de 0,004. Une ombre absente, une eau fausse, une image
    // retournée ou une couleur décalée dépassent ces seuils de très loin.
    fun fin(x: Double) = "%.4f %%".format(x * 100)
    for ((nom, vue) in vues) controle("OpenGL ES : vue « $nom » comparée à la référence de Vulkan", "≤ 0,02 % de pixels, écart moyen ≤ 0,05") {
        val img = dossier.resolve("gl-vue-$nom.png")
        rendreIle(img, 60, vue, heure = heures[nom], moteur = "gl")
        val (part, moyen) = comparer(ImageIO.read(img.toFile()), ImageIO.read(refs.resolve("vue-$nom.png").toFile()))
        "${fin(part)} de pixels différents, écart moyen ${f3(moyen)}" to (part <= .0002 && moyen <= .05)
    }
    ombresDecoupees("gl")
    // Les lignes ne sont pas tracées de la même façon par OpenGL ES et par Vulkan dans une image à plusieurs échantillons
    // (les deux normes diffèrent là-dessus) : des points épars le long des traits, 0,34 % de pixels et 0,10 d'écart moyen
    // mesurés, avec un nombre du panneau de débogage qui dépend du temps de calcul. Des lignes absentes changent 22 % de l'image.
    controle("OpenGL ES : lignes de débogage (F5) comme Vulkan", "≤ 1 % de pixels, écart moyen ≤ 0,3 ; les lignes y sont") {
        val a = dossier.resolve("lignes-vulkan.png"); val b = dossier.resolve("lignes-gl.png")
        rendreIle(a, 30, vues["maison"], debogage = "physics"); rendreIle(b, 30, vues["maison"], debogage = "physics", moteur = "gl")
        val sans = dossier.resolve("lignes-sans.png"); rendreIle(sans, 30, vues["maison"], moteur = "gl")
        val (part, moyen) = comparer(ImageIO.read(a.toFile()), ImageIO.read(b.toFile()))
        val (lignes, _) = comparer(ImageIO.read(sans.toFile()), ImageIO.read(b.toFile()))
        "${fin(part)} de pixels différents, écart moyen ${f3(moyen)} ; les lignes changent ${fin(lignes)} de l'image" to (part <= .01 && moyen <= .3 && lignes > .05)
    }
    controle("OpenGL ES : rendu reproductible, tailles extrêmes", "identique au pixel d'une fois à l'autre ; 17×13 à 3840×2160 comme Vulkan (≤ 0,5 % de pixels)") {
        val a = dossier.resolve("gl-repro-a.png"); val b = dossier.resolve("gl-repro-b.png")
        rendreIle(a, 45, vues["maison"], moteur = "gl"); rendreIle(b, 45, vues["maison"], moteur = "gl")
        val (part, moyen) = comparer(ImageIO.read(a.toFile()), ImageIO.read(b.toFile()))
        val faux = ArrayList<String>(); val mesures = ArrayList<String>()
        if (part != 0.0 || moyen != 0.0) faux.add("deux rendus diffèrent (${fin(part)})")
        for (t in listOf("17x13", "333x211", "1920x1080", "3840x2160")) {
            val f = dossier.resolve("gl-taille-$t.png")
            rendreIle(f, 3, vues["ile"], t, moteur = "gl")
            val img = ImageIO.read(f.toFile()); val vulkan = ImageIO.read(dossier.resolve("taille-$t.png").toFile())
            val (l, h) = t.split('x').map { it.toInt() }
            if (img.width != l || img.height != h) { faux.add("$t rendu en ${img.width}×${img.height}"); continue }
            val (p, m) = comparer(img, vulkan)
            mesures.add("$t ${fin(p)}")
            if (p > .005 || m > .2) faux.add("$t : ${fin(p)} de pixels, écart moyen ${f2(m)}")
        }
        (if (faux.isEmpty()) "reproductible ; écarts avec Vulkan : " + mesures.joinToString() else faux.joinToString()) to faux.isEmpty()
    }
    controle("OpenGL ES : pas de fuite (16 moteurs de rendu ouverts et fermés)", "mémoire résidente +≤ 40 Mo (médianes), descripteurs stables") {
        val rss = ArrayList<Long>(); val fds = ArrayList<Int>()
        repeat(16) { rendreIle(null, 2, taille = "320x200", moteur = "gl"); System.gc(); Thread.sleep(50); rss.add(statut("VmRSS:")); fds.add(descripteurs()) }
        fun median(xs: List<Long>) = xs.sorted().let { (it[1] + it[2]) / 2 }
        val debut = median(rss.subList(2, 6)); val fin = median(rss.subList(12, 16))
        val croissance = (fin - debut) / 1024.0
        "mémoire ${debut / 1024} → ${fin / 1024} Mo (${"%+.1f".format(croissance)}), descripteurs ${fds[2]} → ${fds.last()}" to
            (croissance <= 40 && fds.last() <= fds[2])
    }

    // Géométrie des modèles de la démo.
    val modeles = listOf("terrain", "village", "marin", "palmiers", "ciel", "port", "caisse", "ballon", "enseigne", "drapeau")
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


/** Un GLB d'une plaque horizontale de 2 m à 1,5 m du sol : une image dont la moitié gauche (x < 0) est découpée (alpha 0, MASK). */
private fun plaqueDecoupee(): ByteArray {
    val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until 8) for (x in 0 until 8) img.setRGB(x, y, if (x < 4) 0x00C04020 else 0xFFC04020.toInt())
    val png = java.io.ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    val v = floatArrayOf(-1f, 1.5f, -1f, -1f, 1.5f, 1f, 1f, 1.5f, 1f, -1f, 1.5f, -1f, 1f, 1.5f, 1f, 1f, 1.5f, -1f)
    val uv = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
    val bin = java.nio.ByteBuffer.allocate(72 + 48 + png.size + (4 - png.size % 4) % 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    v.forEach { bin.putFloat(it) }; uv.forEach { bin.putFloat(it) }; bin.put(png)
    val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"name":"plaque","mesh":0}],
        "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"material":0}]}],
        "materials":[{"name":"plaque","pbrMetallicRoughness":{"baseColorTexture":{"index":0}},"alphaMode":"MASK","alphaCutoff":0.5}],
        "textures":[{"source":0}],"images":[{"bufferView":2,"mimeType":"image/png"}],
        "accessors":[{"bufferView":0,"componentType":5126,"count":6,"type":"VEC3","min":[-1,1.5,-1],"max":[1,1.5,1]},{"bufferView":1,"componentType":5126,"count":6,"type":"VEC2"}],
        "bufferViews":[{"buffer":0,"byteLength":72},{"buffer":0,"byteOffset":72,"byteLength":48},{"buffer":0,"byteOffset":120,"byteLength":${png.size}}],
        "buffers":[{"byteLength":${bin.capacity()}}]}""".toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
    val out = java.nio.ByteBuffer.allocate(12 + 8 + json.size + 8 + bin.capacity()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    out.putInt(0x46546c67).putInt(2).putInt(out.capacity()).putInt(json.size).putInt(0x4e4f534a).put(json)
    out.putInt(bin.capacity()).putInt(0x004e4942).put(bin.array())
    return out.array()
}
