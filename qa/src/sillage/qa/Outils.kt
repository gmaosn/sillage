package sillage.qa

import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.Body
import sillage.physics.BodyType
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.PhysicsWorld
import sillage.physics.Shape
import sillage.physics.Sphere
import java.util.Random
import kotlin.math.*

fun Random.f(a: Float, b: Float) = a + nextFloat() * (b - a)
fun Random.dir(): Vec3 { while (true) { val v = Vec3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)); val l = v.length(); if (l in .1f..1f) return v / l } }
fun Random.rotation(): Quat = Quat.axisAngle(dir(), f(0f, 2 * PI.toFloat()))

/** Une forme au hasard, de 10 cm à 1,2 m. */
fun Random.forme(): Shape = when (nextInt(3)) {
    0 -> Sphere(f(.1f, .6f))
    1 -> Box(f(.1f, .6f), f(.1f, .6f), f(.1f, .6f))
    else -> Capsule(f(.1f, .35f), f(.1f, .5f))
}

/** Des corps au hasard dans une zone, sans se chevaucher au départ (sphères englobantes), masses de 1 à 3000 kg/m³. */
fun ajouterCorps(w: PhysicsWorld, rnd: Random, n: Int, x: Float, y0: Float, y1: Float, vitesse: Float = 0f,
                 friction: () -> Float = { rnd.f(.2f, .9f) }, rebond: () -> Float = { rnd.f(0f, .6f) }): List<Body> {
    val places = ArrayList<Pair<Vec3, Float>>()
    val corps = ArrayList<Body>()
    repeat(n) {
        val s = rnd.forme()
        for (essai in 0 until 60) {
            val p = Vec3(rnd.f(-x, x), rnd.f(y0, y1), rnd.f(-x, x))
            if (places.any { (q, r) -> (q - p).length() < r + s.boundingRadius + .02f }) continue
            places.add(p to s.boundingRadius)
            val b = w.add(s, p, rnd.rotation(), density = rnd.f(100f, 3000f), friction = friction(), restitution = rebond())
            if (vitesse > 0f) { b.velocity = rnd.dir() * rnd.f(0f, vitesse); b.angularVelocity = rnd.dir() * rnd.f(0f, 5f) }
            corps.add(b); break
        }
    }
    return corps
}

/** Axes du corps dans le monde. */
fun Body.axes(): Array<Vec3> = rotation.let { q -> arrayOf(q.rotate(Vec3.X), q.rotate(Vec3.Y), q.rotate(Vec3.Z)) }

/** Énergie cinétique (translation et rotation) plus potentielle de pesanteur, en joules. */
fun energie(corps: List<Body>, g: Vec3): Double {
    var e = 0.0
    for (b in corps) {
        if (b.type != BodyType.DYNAMIC) continue
        val m = b.mass.toDouble(); val v = b.velocity
        e += .5 * m * (v dot v) - m * (g dot b.position)
        val i = b.shape.inertia(b.mass); val w = b.angularVelocity; val a = b.axes()
        e += .5 * (i.x * (w dot a[0]).toDouble().pow(2) + i.y * (w dot a[1]).toDouble().pow(2) + i.z * (w dot a[2]).toDouble().pow(2))
    }
    return e
}

/** Quantité de mouvement totale. */
fun impulsion(corps: List<Body>): DoubleArray {
    val p = DoubleArray(3)
    for (b in corps) { val v = b.velocity; p[0] += b.mass * v.x.toDouble(); p[1] += b.mass * v.y.toDouble(); p[2] += b.mass * v.z.toDouble() }
    return p
}

/** Moment cinétique total autour de l'origine. */
fun momentCinetique(corps: List<Body>): DoubleArray {
    val l = DoubleArray(3)
    for (b in corps) {
        val r = b.position; val pv = b.velocity * b.mass
        val orbital = r cross pv
        val i = b.shape.inertia(b.mass); val w = b.angularVelocity; val a = b.axes()
        val propre = a[0] * (i.x * (w dot a[0])) + a[1] * (i.y * (w dot a[1])) + a[2] * (i.z * (w dot a[2]))
        l[0] += (orbital.x + propre.x).toDouble(); l[1] += (orbital.y + propre.y).toDouble(); l[2] += (orbital.z + propre.z).toDouble()
    }
    return l
}

fun norme(v: DoubleArray) = sqrt(v.sumOf { it * it })
fun ecart(a: DoubleArray, b: DoubleArray) = sqrt(a.indices.sumOf { (a[it] - b[it]).pow(2) })

// ------------------------------------------------------------------ référence géométrique indépendante

/**
 * Forme placée, décrite par sa fonction de distance signée exacte (négative dedans) : la référence
 * des contrôles de collision, écrite sans rien emprunter au code du moteur.
 */
class Solide(val forme: Shape, val p: Vec3, val q: Quat) {
    private val inv = q.conjugate()
    fun local(w: Vec3) = inv.rotate(w - p)
    fun monde(l: Vec3) = q.rotate(l) + p

    fun distance(w: Vec3): Float {
        val l = local(w)
        return when (val s = forme) {
            is Sphere -> l.length() - s.radius
            is Capsule -> Vec3(l.x, l.y - l.y.coerceIn(-s.halfHeight, s.halfHeight), l.z).length() - s.radius
            is Box -> {
                val dx = abs(l.x) - s.halfX; val dy = abs(l.y) - s.halfY; val dz = abs(l.z) - s.halfZ
                Vec3(max(dx, 0f), max(dy, 0f), max(dz, 0f)).length() + min(max(dx, max(dy, dz)), 0f)
            }
        }
    }

    /** Points de la surface, à peu près également espacés (environ [n]). */
    fun surface(n: Int): List<Vec3> {
        val out = ArrayList<Vec3>()
        when (val s = forme) {
            is Sphere -> for (k in 0 until n) out.add(monde(fibonacci(k, n) * s.radius))
            is Capsule -> {
                val aire = 4 * PI * s.radius * s.radius + 2 * PI * s.radius * 2 * s.halfHeight
                val nb = (n * 4 * PI * s.radius * s.radius / aire).toInt().coerceAtLeast(20)
                for (k in 0 until nb) { val d = fibonacci(k, nb); out.add(monde(d * s.radius + Vec3(0f, if (d.y >= 0) s.halfHeight else -s.halfHeight, 0f))) }
                val rest = n - nb
                val anneaux = max(2, sqrt(rest * s.halfHeight / (PI * s.radius)).toInt()); val tours = max(8, rest / anneaux)
                for (i in 0..anneaux) for (j in 0 until tours) {
                    val a = 2 * PI.toFloat() * j / tours
                    out.add(monde(Vec3(cos(a) * s.radius, -s.halfHeight + 2 * s.halfHeight * i / anneaux, sin(a) * s.radius)))
                }
            }
            is Box -> {
                val h = floatArrayOf(s.halfX, s.halfY, s.halfZ)
                val aire = 8 * (h[0] * h[1] + h[1] * h[2] + h[0] * h[2])
                for (axe in 0..2) for (signe in floatArrayOf(-1f, 1f)) {
                    val u = (axe + 1) % 3; val v = (axe + 2) % 3
                    val k = max(2, sqrt(n * 4 * h[u] * h[v] / aire).toInt())
                    val ku = max(2, (k * sqrt(h[u] / h[v])).toInt()); val kv = max(2, (k * sqrt(h[v] / h[u])).toInt())
                    for (i in 0..ku) for (j in 0..kv) {
                        val c = FloatArray(3); c[axe] = signe * h[axe]; c[u] = -h[u] + 2 * h[u] * i / ku; c[v] = -h[v] + 2 * h[v] * j / kv
                        out.add(monde(Vec3(c[0], c[1], c[2])))
                    }
                }
                // Arêtes tous les centimètres : deux arêtes qui se croisent se touchent entre deux points de la grille.
                for (axe in 0..2) {
                    val u = (axe + 1) % 3; val v = (axe + 2) % 3
                    val k = max(2, (2 * h[axe] / .01f).toInt())
                    for (su in floatArrayOf(-1f, 1f)) for (sv in floatArrayOf(-1f, 1f)) for (i in 0..k) {
                        val c = FloatArray(3); c[axe] = -h[axe] + 2 * h[axe] * i / k; c[u] = su * h[u]; c[v] = sv * h[v]
                        out.add(monde(Vec3(c[0], c[1], c[2])))
                    }
                }
            }
        }
        return out
    }
}

fun fibonacci(k: Int, n: Int): Vec3 {
    val y = 1f - 2f * (k + .5f) / n; val r = sqrt(max(0f, 1f - y * y)); val a = k * 2.399963f
    return Vec3(cos(a) * r, y, sin(a) * r)
}

/** Distance signée entre deux solides, par échantillonnage de leurs surfaces (négative : chevauchement). */
fun distanceSolides(a: Solide, b: Solide, n: Int = 2500): Float {
    var d = Float.MAX_VALUE
    for (p in b.surface(n)) d = min(d, a.distance(p))
    for (p in a.surface(n)) d = min(d, b.distance(p))
    return d
}

/** Distance du point [p] au triangle (a, b, c) (Ericson, Real-Time Collision Detection 5.1.5). */
fun pointTriangle(p: Vec3, a: Vec3, b: Vec3, c: Vec3): Vec3 {
    val ab = b - a; val ac = c - a; val ap = p - a
    val d1 = ab dot ap; val d2 = ac dot ap
    if (d1 <= 0f && d2 <= 0f) return a
    val bp = p - b; val d3 = ab dot bp; val d4 = ac dot bp
    if (d3 >= 0f && d4 <= d3) return b
    val vc = d1 * d4 - d3 * d2
    if (vc <= 0f && d1 >= 0f && d3 <= 0f) return a + ab * (d1 / (d1 - d3))
    val cp = p - c; val d5 = ab dot cp; val d6 = ac dot cp
    if (d6 >= 0f && d5 <= d6) return c
    val vb = d5 * d2 - d1 * d6
    if (vb <= 0f && d2 >= 0f && d6 <= 0f) return a + ac * (d2 / (d2 - d6))
    val va = d3 * d6 - d5 * d4
    if (va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f) return b + (c - b) * ((d4 - d3) / ((d4 - d3) + (d5 - d6)))
    val den = 1f / (va + vb + vc)
    return a + ab * (vb * den) + ac * (vc * den)
}

/** Lancer de rayon par marche sur la distance signée (sphere tracing) : exact pour une distance euclidienne. */
fun marcheRayon(o: Vec3, d: Vec3, maxT: Float, rayon: Float, dist: (Vec3) -> Float): Float? {
    var t = 0f
    repeat(4000) {
        val s = dist(o + d * t) - rayon
        if (s < 1e-5f) return t
        t += s
        if (t > maxT) return null
    }
    return null
}

/** Triangles d'une boîte alignée sur les axes, tournés vers l'extérieur. */
fun boiteMaillee(lo: Vec3, hi: Vec3): FloatArray {
    val out = ArrayList<Float>(); val c = (lo + hi) / 2f
    fun v(x: Int, y: Int, z: Int) = Vec3(if (x == 0) lo.x else hi.x, if (y == 0) lo.y else hi.y, if (z == 0) lo.z else hi.z)
    val faces = listOf(listOf(v(0, 1, 0), v(0, 1, 1), v(1, 1, 1), v(1, 1, 0)), listOf(v(0, 0, 0), v(1, 0, 0), v(1, 0, 1), v(0, 0, 1)),
        listOf(v(0, 0, 0), v(0, 1, 0), v(1, 1, 0), v(1, 0, 0)), listOf(v(0, 0, 1), v(1, 0, 1), v(1, 1, 1), v(0, 1, 1)),
        listOf(v(0, 0, 0), v(0, 0, 1), v(0, 1, 1), v(0, 1, 0)), listOf(v(1, 0, 0), v(1, 1, 0), v(1, 1, 1), v(1, 0, 1)))
    for (f in faces) {
        val centre = (f[0] + f[1] + f[2] + f[3]) / 4f
        for ((a, b, cc) in listOf(Triple(f[0], f[1], f[2]), Triple(f[0], f[2], f[3]))) {
            val retourne = (((b - a) cross (cc - a)) dot (centre - c)) < 0f
            for (p in if (retourne) listOf(a, cc, b) else listOf(a, b, cc)) { out.add(p.x); out.add(p.y); out.add(p.z) }
        }
    }
    return out.toFloatArray()
}

/** Un grand sol plat maillé (deux triangles vers le haut) à la hauteur [y]. */
fun solMaille(y: Float = 0f, demi: Float = 60f) = floatArrayOf(-demi, y, -demi, -demi, y, demi, demi, y, demi, -demi, y, -demi, demi, y, demi, demi, y, -demi)

/** Point le plus bas d'un corps (sa forme, tournée). */
fun Body.plusBas(): Float {
    val p = position
    return when (val s = shape) {
        is Sphere -> p.y - s.radius
        is Capsule -> p.y - abs(rotation.rotate(Vec3.Y).y) * s.halfHeight - s.radius
        is Box -> { val a = axes(); p.y - abs(a[0].y) * s.halfX - abs(a[1].y) * s.halfY - abs(a[2].y) * s.halfZ }
    }
}

fun Body.fini() = position.isFinite() && velocity.isFinite() && angularVelocity.isFinite() &&
    rotation.let { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.w.isFinite() }

fun Vec3.isFiniteVec() = x.isFinite() && y.isFinite() && z.isFinite()
