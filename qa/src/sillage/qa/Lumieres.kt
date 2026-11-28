package sillage.qa

import sillage.Color
import sillage.ParticleStyle
import sillage.Scene
import sillage.game
import sillage.math.Mat4
import sillage.math.Vec3
import sillage.vec
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.*

private val dossier = Path.of("build/qa/images")

/** Une scène rendue sans fenêtre, [images] images, vue de [oeil] vers [cible] ; [moteur] « gl » pour OpenGL ES. */
private fun rendre(nom: String, moteur: String?, oeil: Vec3, cible: Vec3, taille: String = "480x300", images: Int = 40, scene: Scene.() -> Unit): BufferedImage {
    Files.createDirectories(dossier)
    val img = dossier.resolve("$nom${if (moteur == null) "" else "-$moteur"}.png")
    game("QA", arrayOf("--headless", "--mute", "--frames", "$images", "--size", taille, "--capture", img.toString(),
        "--look-at", "${oeil.x},${oeil.y},${oeil.z},${cible.x},${cible.y},${cible.z}") + (if (moteur == null) emptyArray() else arrayOf("--renderer", moteur)), scene)
    return ImageIO.read(img.toFile())
}

/** Où se voit le point [p] dans une image [w] × [h] prise de [oeil] vers [cible] (la caméra par défaut), première ligne en haut. */
private fun pixel(p: Vec3, oeil: Vec3, cible: Vec3, w: Int, h: Int): Pair<Int, Int> {
    val c = Scene().camera
    val vp = Mat4.perspective(sillage.math.toRadians(c.fov.toDouble()).toFloat(), w.toFloat() / h, c.near, c.far) * Mat4.lookAt(oeil, cible)
    val cw = vp.m03 * p.x + vp.m13 * p.y + vp.m23 * p.z + vp.m33
    val x = (vp.m00 * p.x + vp.m10 * p.y + vp.m20 * p.z + vp.m30) / cw; val y = (vp.m01 * p.x + vp.m11 * p.y + vp.m21 * p.z + vp.m31) / cw
    return ((x * .5f + .5f) * w).toInt().coerceIn(0, w - 1) to ((y * .5f + .5f) * h).toInt().coerceIn(0, h - 1)
}

/** Luminosité moyenne (0–255) d'un carré de 5 × 5 pixels. */
private fun lum(img: BufferedImage, x: Int, y: Int): Double {
    var s = 0.0; var n = 0
    for (j in y - 2..y + 2) for (i in x - 2..x + 2) {
        if (i !in 0 until img.width || j !in 0 until img.height) continue
        val c = img.getRGB(i, j); s += ((c shr 16 and 255) * .2126 + (c shr 8 and 255) * .7152 + (c and 255) * .0722); n++
    }
    return s / n
}

/** Une nuit sans lune, sans trait d'encre ni vignette : seules les lampes éclairent vraiment. */
private fun Scene.nuitNoire() {
    autoTextures = false
    lighting.time = 23.5f
    lighting.outlines = 0f; lighting.vignette = 0f
}

/** Lampes qui font de l'ombre, plus de 16 lampes, cartes de relief et d'émission des GLB, transparents triés avec les particules. */
fun Qa.lumieres() {
    // Une lampe à 1,2 m au-dessus d'un sol clair, une caisse à côté : derrière la caisse, l'ombre ; de l'autre côté, la pleine lumière.
    val oeil = vec(0, 6, 4.5); val cible = vec(0, 0, 0)
    fun lampe(moteur: String?, caisse: Boolean) = rendre("lampe-${if (caisse) "caisse" else "seule"}", moteur, oeil, cible) {
        nuitNoire(); ground(color = Color(.8f, .8f, .8f))
        light(Color.WHITE, intensity = 3, radius = 6, at = vec(0, 1.2, 0))
        if (caisse) box(.8, 1.0, .8, Color.STONE, at = vec(1.3, .5, 0))
    }
    val (ox, oy) = pixel(vec(2.6, 0, 0), oeil, cible, 480, 300); val (mx, my) = pixel(vec(-2.6, 0, 0), oeil, cible, 480, 300)
    for (moteur in listOf(null, "gl")) controle("lampes${if (moteur == null) "" else " ($moteur)"} : une lampe fait de l'ombre", "derrière la caisse ≤ 90 % de la lumière sans elle (la moitié de celle de la lampe y reste) ; de l'autre côté ±4") {
        val avec = lampe(moteur, true); val sans = lampe(moteur, false)
        val ombre = lum(avec, ox, oy); val plein = lum(sans, ox, oy); val autre = lum(avec, mx, my); val autreSans = lum(sans, mx, my)
        "derrière la caisse ${f2(ombre)} contre ${f2(plein)} sans elle ; de l'autre côté ${f2(autre)} et ${f2(autreSans)}" to
            (ombre <= .9 * plein && plein - ombre >= 12 && abs(autre - autreSans) <= 4)
    }

    // 48 lampes en damier au-dessus d'un sol sombre : chacune éclaire le sol sous elle (la grille de lumière au-delà de 16).
    val haut = vec(0, 13, 7); val milieu = vec(0, 0, 0)
    val places = (0 until 48).map { vec(-5.25 + (it % 8) * 1.5, .6, -3.75 + (it / 8) * 1.5) }
    fun damier(moteur: String?) = rendre("lampes-48", moteur, haut, milieu) {
        nuitNoire(); ground(color = Color(.5f, .5f, .5f))
        for ((k, p) in places.withIndex()) light(if (k % 2 == 0) Color(1f, .7f, .4f) else Color(.5f, .7f, 1f), intensity = 6, radius = 1.2, at = p)
    }
    val vk = damier(null)
    for ((moteur, img) in listOf(null to vk, "gl" to damier("gl"))) controle("lampes${if (moteur == null) "" else " ($moteur)"} : 48 lampes allumées à la fois", "le sol sous chacune ≥ 1,3 × entre elles") {
        var allumees = 0; var pire = Double.MAX_VALUE
        for (p in places) {
            val (x, y) = pixel(vec(p.x, 0, p.z), haut, milieu, 480, 300); val (bx, by) = pixel(vec(p.x + .75, 0, p.z + .75), haut, milieu, 480, 300)
            val sous = lum(img, x, y); val entre = lum(img, bx, by)
            pire = min(pire, sous / max(entre, 1.0))
            if (sous >= 1.3 * entre) allumees++
        }
        "$allumees lampes sur 48 éclairent le sol sous elles (pire rapport ${f2(pire)})" to (allumees == 48)
    }

    // Carte de relief : un panneau de seize bosses éclairé de côté ; tangentes calculées ou lues dans le fichier, même image.
    val face = vec(0, 1, 3.2); val panneau = vec(0, 1, 0)
    fun relief(fichier: String, moteur: String? = null) = rendre("relief-${fichier.substringBefore('.')}", moteur, face, panneau) {
        autoTextures = false; lighting.outlines = 0f; lighting.vignette = 0f
        lighting.sunDirection = vec(-.8, .25, .55).normalized()
        model("essais/$fichier")
    }
    val calcule = relief("relief.glb"); val lu = relief("relief_tangentes.glb")
    controle("GLB : carte de relief (normal map)", "écart de luminosité dans le panneau ≥ 40 ; tangentes calculées ≈ lues (≤ 1 % de pixels)") {
        val l = ArrayList<Double>()
        for (j in 90 until 210 step 3) for (i in 170 until 310 step 3) l.add(lum(calcule, i, j))
        l.sort()
        val etendue = l[l.size * 95 / 100] - l[l.size * 5 / 100]
        val (part, _) = comparer(calcule, lu)
        "luminosité de ${f2(l[l.size * 5 / 100])} à ${f2(l[l.size * 95 / 100])} ; tangentes calculées contre lues : ${pct(part)} de pixels différents" to
            (etendue >= 40 && part <= .01)
    }

    // Émission en image : des bandes qui luisent dans le noir (emissiveFactor × l'image).
    fun emission(moteur: String?) = rendre("emission", moteur, face, panneau) { nuitNoire(); model("essais/emission.glb") }
    val luit = emission(null)
    controle("GLB : émission en image", "dans le noir, 8 bandes claires et sombres en alternance") {
        var passages = 0; var clair = false; var min = 255.0; var max = 0.0
        for (i in 160 until 320) {
            val v = lum(luit, i, 150); min = min(min, v); max = max(max, v)
            val c = v > 150; if (c != clair && i > 160) passages++; clair = c
        }
        "$passages passages clair ↔ sombre, de ${f2(min)} à ${f2(max)}" to (passages >= 6 && max > 180 && min < 120)
    }

    // Transparents : une vitre bleutée devant un pilier rouge ; une étincelle derrière elle, une devant. Triées ensemble, celle de
    // derrière est voilée par la vitre ; dessinées après elle, elles seraient pareilles.
    val devant = vec(0, 1.1, 3); val vitre = vec(0, 1.1, 0)
    val point = ParticleStyle(Color(1f, .9f, .6f), size = .09f, life = 30f, lifeSpread = 0f, speed = 0f, speedSpread = 0f, glow = true, brightness = .5f)
    fun verre(moteur: String?) = rendre("verre", moteur, devant, vitre) {
        autoTextures = false; lighting.outlines = 0f; lighting.vignette = 0f
        model("essais/verre.glb")
        burst(point, vec(-.6, 1.4, .3), 1); burst(point, vec(.6, 1.4, .9), 1)
    }
    val vu = verre(null)
    controle("transparents : une vitre triée avec les particules", "le pilier se voit à travers ; l'étincelle de derrière ≤ 85 % de celle de devant") {
        val (px, py) = pixel(vec(0, .8, -.1), devant, vitre, 480, 300); val (fx, fy) = pixel(vec(.7, .8, .6), devant, vitre, 480, 300)
        val pilier = vu.getRGB(px, py); val fond = vu.getRGB(fx, fy)
        val vert = (fond shr 8 and 255) - (pilier shr 8 and 255)
        val (ax, ay) = pixel(vec(-.6, 1.4, .3), devant, vitre, 480, 300); val (bx, by) = pixel(vec(.6, 1.4, .9), devant, vitre, 480, 300)
        fun eclat(x: Int, y: Int): Double { var m = 0.0; for (j in y - 4..y + 4) for (i in x - 4..x + 4) m = max(m, lum(vu, i, j)); return m }
        val derriere = eclat(ax, ay) - lum(vu, ax, ay - 12); val avant = eclat(bx, by) - lum(vu, bx, by - 12)
        "pilier à travers la vitre : vert −$vert sous le fond ; étincelles ${f2(derriere)} derrière la vitre, ${f2(avant)} devant" to
            (vert >= 40 && derriere <= .85 * avant && avant > 10)
    }

    // Les deux moteurs de rendu : les mêmes images.
    for ((nom, deux) in listOf("relief" to { relief("relief.glb", "gl") }, "émission" to { emission("gl") }, "vitre et particules" to { verre("gl") },
            "48 lampes" to { damier("gl") })) controle("OpenGL ES : $nom comme Vulkan", "≤ 0,5 % de pixels, écart moyen ≤ 0,3") {
        val gl = deux()
        val ref = when (nom) { "relief" -> calcule; "émission" -> luit; "vitre et particules" -> vu; else -> vk }
        val (part, moyen) = comparer(gl, ref)
        "${pct(part)} de pixels différents, écart moyen ${f3(moyen)}" to (part <= .005 && moyen <= .3)
    }
}
