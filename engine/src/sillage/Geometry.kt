package sillage

import sillage.math.Mat4
import sillage.math.Vec3
import kotlin.math.*

data class Couleur(val r: Float, val g: Float, val b: Float) {
    companion object {
        fun hex(rgb: Int) = Couleur((rgb shr 16 and 255) / 255f, (rgb shr 8 and 255) / 255f, (rgb and 255) / 255f)
    }
}

class Triangle(val a: Vec3, val b: Vec3, val c: Vec3) {
    fun hauteur(x: Float, z: Float): Float? {
        val den = (b.z - c.z) * (a.x - c.x) + (c.x - b.x) * (a.z - c.z)
        if (abs(den) < 1e-7f) return null
        val u = ((b.z - c.z) * (x - c.x) + (c.x - b.x) * (z - c.z)) / den
        val v = ((c.z - a.z) * (x - c.x) + (a.x - c.x) * (z - c.z)) / den
        if (u < -0.0001f || v < -0.0001f || u + v > 1.0001f) return null
        return u * a.y + v * b.y + (1 - u - v) * c.y
    }
}

/** Sommets non indexés : position, normale, couleur (9 floats). */
class Maillage(val sommets: FloatArray) {
    init { require(sommets.size % 27 == 0) { "Le maillage doit contenir des triangles complets" } }
    val triangles: List<Triangle> by lazy {
        sommets.indices.step(27).map { i ->
            fun p(o: Int) = Vec3(sommets[i + o], sommets[i + o + 1], sommets[i + o + 2])
            Triangle(p(0), p(9), p(18))
        }
    }
}

class Geometrie {
    private val data = ArrayList<Float>()
    fun triangle(a: Vec3, b: Vec3, c: Vec3, couleur: Couleur) {
        val cross = (b - a) cross (c - a)
        if (cross.lengthSquared() < 1e-12f) return
        val n = cross.normalized()
        for (p in arrayOf(a, b, c)) data.addAll(listOf(p.x, p.y, p.z, n.x, n.y, n.z, couleur.r, couleur.g, couleur.b))
    }
    fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3, couleur: Couleur) {
        triangle(a,b,c,couleur); triangle(a,c,d,couleur)
    }
    fun boite(x: Float, y: Float, z: Float, largeur: Float, hauteur: Float, profondeur: Float, couleur: Couleur) {
        val p = Array(8) { i -> Vec3(x + (if ((i and 1) == 0) -1f else 1f) * largeur / 2,
            y + (if ((i and 2) == 0) 0f else hauteur), z + (if ((i and 4) == 0) -1f else 1f) * profondeur / 2) }
        for (f in arrayOf(intArrayOf(0,4,6,2), intArrayOf(1,3,7,5), intArrayOf(0,1,5,4),
            intArrayOf(2,6,7,3), intArrayOf(0,2,3,1), intArrayOf(4,5,7,6))) quad(p[f[0]],p[f[1]],p[f[2]],p[f[3]],couleur)
    }
    fun disque(x: Float, y: Float, z: Float, rayon: Float, couleur: Couleur, segments: Int = 48) {
        for (i in 0 until segments) {
            val a = i * 2 * PI.toFloat() / segments; val b = (i+1) * 2 * PI.toFloat() / segments
            triangle(Vec3(x,y,z), Vec3(x+cos(b)*rayon,y,z+sin(b)*rayon), Vec3(x+cos(a)*rayon,y,z+sin(a)*rayon),couleur)
        }
    }
    fun construire() = Maillage(data.toFloatArray())
}

class Objet(val maillage: Maillage) {
    var position = Vec3.ZERO
    var rotation = 0f
    var echelle = 1f
    var visible = true
    /** Pièces d'un modèle animé, dessinées avec matrice() × pose de la pièce. */
    var parties: List<Partie> = emptyList()
        internal set
    var animateur: Animateur? = null
        internal set
    fun matrice() = Mat4.trsY(position, rotation, echelle)

    /** Joue une animation du modèle GLB (nom donné dans Blender). */
    fun animer(nom: String, boucle: Boolean = true, vitesse: Float = 1f): Objet {
        requireNotNull(animateur) { "Ce modèle n'a pas d'animation" }.jouer(nom, boucle, vitesse = vitesse)
        return this
    }
}

/** Volume de collision. [camera] = faux pour les objets fins (troncs, tonneaux) : ils n'arrêtent pas la caméra. */
class Boite(val min: Vec3, val max: Vec3, val camera: Boolean = true)
