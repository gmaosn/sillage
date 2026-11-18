package sillage

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.*

data class Couleur(val r: Float, val g: Float, val b: Float) {
    companion object {
        fun hex(rgb: Int) = Couleur((rgb shr 16 and 255) / 255f, (rgb shr 8 and 255) / 255f, (rgb and 255) / 255f)
    }
}

data class Triangle(val a: Vector3f, val b: Vector3f, val c: Vector3f) {
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
            fun p(o: Int) = Vector3f(sommets[i + o], sommets[i + o + 1], sommets[i + o + 2])
            Triangle(p(0), p(9), p(18))
        }
    }
}

class Geometrie {
    private val data = ArrayList<Float>()
    fun triangle(a: Vector3f, b: Vector3f, c: Vector3f, couleur: Couleur) {
        val n = Vector3f(b).sub(a).cross(Vector3f(c).sub(a))
        if (n.lengthSquared() < 1e-12f) return
        n.normalize()
        for (p in listOf(a, b, c)) data.addAll(listOf(p.x, p.y, p.z, n.x, n.y, n.z, couleur.r, couleur.g, couleur.b))
    }
    fun quad(a: Vector3f, b: Vector3f, c: Vector3f, d: Vector3f, couleur: Couleur) {
        triangle(a,b,c,couleur); triangle(a,c,d,couleur)
    }
    fun boite(x: Float, y: Float, z: Float, largeur: Float, hauteur: Float, profondeur: Float, couleur: Couleur) {
        val p = Array(8) { i -> Vector3f(x + (if ((i and 1) == 0) -1f else 1f) * largeur / 2,
            y + (if ((i and 2) == 0) 0f else hauteur), z + (if ((i and 4) == 0) -1f else 1f) * profondeur / 2) }
        for (f in arrayOf(intArrayOf(0,4,6,2), intArrayOf(1,3,7,5), intArrayOf(0,1,5,4),
            intArrayOf(2,6,7,3), intArrayOf(0,2,3,1), intArrayOf(4,5,7,6))) quad(p[f[0]],p[f[1]],p[f[2]],p[f[3]],couleur)
    }
    fun disque(x: Float, y: Float, z: Float, rayon: Float, couleur: Couleur, segments: Int = 48) {
        for (i in 0 until segments) {
            val a = i * 2 * PI.toFloat() / segments; val b = (i+1) * 2 * PI.toFloat() / segments
            triangle(Vector3f(x,y,z), Vector3f(x+cos(b)*rayon,y,z+sin(b)*rayon), Vector3f(x+cos(a)*rayon,y,z+sin(a)*rayon),couleur)
        }
    }
    fun construire() = Maillage(data.toFloatArray())
}

class Objet(val maillage: Maillage) {
    val position = Vector3f()
    var rotation = 0f
    var echelle = 1f
    var visible = true
    fun matrice() = Matrix4f().translate(position).rotateY(rotation).scale(echelle)
}

data class Boite(val min: Vector3f, val max: Vector3f)
