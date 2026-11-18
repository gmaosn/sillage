package sillage.qa

import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.BodyType
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.PhysicsWorld
import sillage.physics.Shape
import sillage.physics.Sphere
import sillage.physics.TriangleMesh
import sillage.physics.overlaps
import sillage.physics.raycast
import java.util.Random
import kotlin.math.*

private class Tri(val a: Vec3, val b: Vec3, val c: Vec3) { val n = ((b - a) cross (c - a)).normalized() }

/** Une soupe de triangles au hasard dans un cube de côté 2 [demi]. */
private fun soupe(rnd: Random, n: Int, demi: Float, taille: Float): List<Tri> {
    val out = ArrayList<Tri>()
    while (out.size < n) {
        val c = Vec3(rnd.f(-demi, demi), rnd.f(-demi, demi), rnd.f(-demi, demi))
        val t = Tri(c + rnd.dir() * rnd.f(.2f, taille), c + rnd.dir() * rnd.f(.2f, taille), c + rnd.dir() * rnd.f(.2f, taille))
        if (((t.b - t.a) cross (t.c - t.a)).length() > .02f) out.add(t)
    }
    return out
}

private fun maillage(ts: List<Tri>) = TriangleMesh(FloatArray(ts.size * 9) { k -> val t = ts[k / 9]; val v = when ((k % 9) / 3) { 0 -> t.a; 1 -> t.b; else -> t.c }; v[k % 3] })

/** Rayon contre triangle à une face (Möller–Trumbore), écrit à part du moteur. */
private fun rayonTriangle(o: Vec3, d: Vec3, t: Tri): Float? {
    if ((d dot t.n) >= 0f) return null
    val e1 = t.b - t.a; val e2 = t.c - t.a
    val p = d cross e2; val det = e1 dot p
    if (abs(det) < 1e-12f) return null
    val s = o - t.a; val u = (s dot p) / det
    if (u < 0f || u > 1f) return null
    val q = s cross e1; val v = (d dot q) / det
    if (v < 0f || u + v > 1f) return null
    val h = (e2 dot q) / det
    return if (h >= 0f) h else null
}

/** Le point de touche du rayon tombe-t-il à moins de [eps] d'une arête ? (cas limite numérique) */
private fun presArete(p: Vec3, t: Tri, eps: Float): Boolean {
    fun seg(a: Vec3, b: Vec3): Float { val ab = b - a; val s = ((p - a) dot ab / (ab dot ab)).coerceIn(0f, 1f); return (p - (a + ab * s)).length() }
    return min(seg(t.a, t.b), min(seg(t.b, t.c), seg(t.c, t.a))) < eps
}

private fun Random.formeQa(): Shape = when (nextInt(3)) { 0 -> Sphere(f(.1f, .6f)); 1 -> Box(f(.1f, .6f), f(.1f, .6f), f(.1f, .6f)); else -> Capsule(f(.1f, .35f), f(.1f, .5f)) }
private fun nom(s: Shape) = when (s) { is Sphere -> "sphère"; is Box -> "boîte"; is Capsule -> "capsule" }

fun Qa.collisions() {
    val rnd = Random(graine + 100)

    controle("rayons contre maillages : arbre de boîtes = force brute", "0 écart hors arêtes") {
        var rayons = 0; var ecarts = 0; var aretes = 0; var touches = 0
        repeat(cas(3, 10)) {
            val ts = soupe(rnd, cas(1500, 4000), 6f, 1.5f)
            val w = PhysicsWorld(); w.addMesh(maillage(ts))
            repeat(cas(2000, 5000)) {
                val o = Vec3(rnd.f(-8f, 8f), rnd.f(-8f, 8f), rnd.f(-8f, 8f)); val d = rnd.dir()
                val moteur = w.raycast(o, d, 30f)?.distance
                var ref: Float? = null; var refTri: Tri? = null
                for (t in ts) { val h = rayonTriangle(o, d, t) ?: continue; if (h <= 30f && (ref == null || h < ref)) { ref = h; refTri = t } }
                rayons++; if (ref != null) touches++
                val pareil = (moteur == null && ref == null) || (moteur != null && ref != null && abs(moteur - ref) <= 1e-3f * max(1f, ref))
                if (!pareil) {
                    val pRef = ref?.let { o + d * it }; val tri = refTri
                    if (pRef != null && tri != null && presArete(pRef, tri, 1e-3f)) aretes++
                    else if (moteur != null && ts.any { t -> rayonTriangle(o, d, t)?.let { abs(it - moteur) < 1e-3f } == true || presArete(o + d * moteur, t, 1e-3f) }) aretes++
                    else ecarts++
                }
            }
        }
        "$rayons rayons ($touches touchent) : $ecarts écarts, $aretes cas d'arête à 1 mm près" to (ecarts == 0)
    }

    controle("balayage de sphère contre maillages = référence", "écart ≤ 2 mm") {
        var n = 0; var pire = 0f; var manques = 0
        repeat(cas(3, 8)) {
            val ts = soupe(rnd, 60, 3f, 1.2f)
            val w = PhysicsWorld(); w.addMesh(maillage(ts))
            var essais = 0
            while (essais < cas(150, 400)) {
                val r = rnd.f(.05f, .5f)
                val o = Vec3(rnd.f(-5f, 5f), rnd.f(-5f, 5f), rnd.f(-5f, 5f))
                if (ts.any { (o - pointTriangle(o, it.a, it.b, it.c)).length() <= r + .01f }) continue
                essais++
                val d = rnd.dir()
                // Triangles à une face : ceux dont l'origine est derrière le plan ne comptent pas, comme dans le moteur.
                val devant = ts.filter { ((o - it.a) dot it.n) >= 0f }
                val ref = marcheRayon(o, d, 12f, r) { p -> devant.minOfOrNull { (p - pointTriangle(p, it.a, it.b, it.c)).length() } ?: 1e9f }
                val moteur = w.raycast(o, d, 12f, radius = r)?.distance
                n++
                when {
                    ref == null && moteur == null -> {}
                    ref == null || moteur == null -> manques++
                    else -> pire = max(pire, abs(ref - moteur))
                }
            }
        }
        "$n balayages : écart max ${mm(pire)}, $manques touchés d'un seul côté" to (pire <= .002f && manques == 0)
    }

    controle("rayons contre corps = marche sur distance exacte", "écart ≤ 1 mm") {
        var n = 0; var pire = 0f; var manques = 0
        repeat(cas(3, 8)) {
            val w = PhysicsWorld(gravity = Vec3.ZERO)
            val solides = ArrayList<Solide>()
            repeat(25) {
                val s = rnd.formeQa(); val p = Vec3(rnd.f(-5f, 5f), rnd.f(-5f, 5f), rnd.f(-5f, 5f)); val q = rnd.rotation()
                w.add(s, p, q, type = BodyType.STATIC); solides.add(Solide(s, p, q))
            }
            repeat(cas(400, 1500)) {
                val o = Vec3(rnd.f(-7f, 7f), rnd.f(-7f, 7f), rnd.f(-7f, 7f))
                if (solides.any { it.distance(o) < .01f }) return@repeat
                val d = rnd.dir()
                val ref = marcheRayon(o, d, 20f, 0f) { p -> solides.minOf { it.distance(p) } }
                val moteur = w.raycast(o, d, 20f)?.distance
                n++
                if ((ref == null) != (moteur == null)) manques++ else if (ref != null && moteur != null) pire = max(pire, abs(ref - moteur))
            }
        }
        "$n rayons : écart max ${mm(pire)}, $manques touchés d'un seul côté" to (pire <= .001f && manques == 0)
    }

    info("balayage de sphère contre boîtes : coins pris carrés") {
        // Le moteur grossit la boîte du rayon (coins vifs) au lieu de l'arrondir : il touche un peu tôt près des coins.
        var pire = 0f; var n = 0
        val w = PhysicsWorld(gravity = Vec3.ZERO); val q = rnd.rotation(); val b = Box(.5f, .3f, .4f)
        w.add(b, Vec3.ZERO, q, type = BodyType.STATIC); val s = Solide(b, Vec3.ZERO, q)
        repeat(cas(500, 2000)) {
            val r = rnd.f(.05f, .4f); val o = rnd.dir() * rnd.f(2f, 3f); val d = (rnd.dir() * .6f - o.normalized()).normalized()
            val ref = marcheRayon(o, d, 8f, r) { s.distance(it) }; val moteur = w.raycast(o, d, 8f, radius = r)?.distance
            n++
            if (ref != null && moteur != null) pire = max(pire, ref - moteur) else if (moteur != null) pire = max(pire, r * (sqrt(3f) - 1f))
        }
        "$n balayages : touche jusqu'à ${cm(pire)} trop tôt près des coins (au plus r·(√3 − 1))"
    }

    // Contacts entre formes : la référence est la distance signée échantillonnée sur les surfaces.
    val tol = .006f; val marge = .02f
    var paires = 0; var nonVus = 0; var faux = 0; var nonResolus = 0; var pireResidu = 0f
    var profondes = 0; var profondesNonResolues = 0; var profondPireResidu = 0f
    val parCouple = HashMap<String, Int>()
    repeat(cas(120, 450)) {
        val sa = rnd.formeQa(); val sb = rnd.formeQa()
        val pa = Vec3.ZERO; val qa = rnd.rotation(); val qb = rnd.rotation()
        val dir = rnd.dir()
        val pb = dir * ((sa.boundingRadius + sb.boundingRadius) * rnd.f(.35f, 1.05f))
        val a = Solide(sa, pa, qa)
        val dref = distanceSolides(a, Solide(sb, pb, qb))
        if (dref < -.15f || dref > .06f) return@repeat
        val w = PhysicsWorld(gravity = Vec3.ZERO); w.add(sa, pa, qa, type = BodyType.STATIC)
        val cs = w.overlaps(sb, pb, qb, margin = marge)
        paires++
        val pire = cs.minByOrNull { it.separation }
        val cle = "${nom(sa)}–${nom(sb)}"
        if (dref < -tol && (pire == null || pire.separation >= 0f)) { nonVus++; parCouple.merge(cle, 1, Int::plus) }
        if (dref > marge + tol && cs.any { it.separation < 0f }) { faux++; parCouple.merge(cle, 1, Int::plus) }
        if (pire != null && pire.separation < 0f) {
            val apres = distanceSolides(a, Solide(sb, pb + pire.normal * (-pire.separation + .002f), qb))
            if (dref < -.05f) {
                // Chevauchement profond (après une téléportation) : la sortie peut prendre plusieurs pas.
                profondes++; profondPireResidu = min(profondPireResidu, apres); if (apres < -tol) profondesNonResolues++
            } else {
            pireResidu = min(pireResidu, apres)
            if (apres < -tol) {
                nonResolus++; parCouple.merge(cle, 1, Int::plus)
                if (System.getenv("QA_DETAIL") != null) println("DETAIL paire $cle a=$sa q=$qa b=$sb pb=$pb qb=$qb dref=${mm(dref)} s=${mm(pire.separation)} n=${pire.normal} apres=${mm(apres)} contacts=${cs.size}")
            }
            }
        }
    }
    val detail = if (parCouple.isEmpty()) "" else " (${parCouple.entries.joinToString { "${it.key} ${it.value}" }})"
    controle("contacts entre formes : chevauchements vus", "aucun manqué au-delà de ${mm(tol)}") { "$paires paires, $nonVus manqués$detail" to (nonVus == 0) }
    controle("contacts entre formes : pas de faux chevauchement", "aucun au-delà de la marge") { "$paires paires, $faux faux" to (faux == 0) }
    controle("contacts entre formes : normale et profondeur séparent (chevauchements ≤ 5 cm)", "reste ≤ ${mm(tol)}") {
        "$nonResolus non séparées, pire reste ${mm(-pireResidu)}" to (nonResolus == 0)
    }
    info("contacts entre formes profonds (> 5 cm)") { "$profondes paires : $profondesNonResolues non séparées d'un coup (pire reste ${mm(-profondPireResidu)}) — la sortie se fait en plusieurs pas" }

    var profondsN = 0; var profondsRestes = 0; var profondPire = 0f
    controle("contacts forme–triangle : vus et séparés (chevauchements ≤ 5 cm)", "aucun manqué, reste ≤ ${mm(tol)}") {
        var n = 0; var manques = 0; var restes = 0; var pire = 0f
        repeat(cas(150, 500)) {
            val t = Tri(Vec3(rnd.f(-1f, 1f), 0f, rnd.f(-1f, 1f)), Vec3(rnd.f(-1f, 1f), 0f, rnd.f(-1f, 1f)), Vec3(rnd.f(-1f, 1f), 0f, rnd.f(-1f, 1f)))
            val tri = if (t.n.y < 0f) Tri(t.a, t.c, t.b) else t
            if (((tri.b - tri.a) cross (tri.c - tri.a)).length() < .2f) return@repeat
            val s = rnd.formeQa(); val q = rnd.rotation()
            val centre = (tri.a + tri.b + tri.c) / 3f
            val p = centre + Vec3(rnd.f(-.4f, .4f), rnd.f(.02f, s.boundingRadius + .05f), rnd.f(-.4f, .4f))
            // Points du triangle (grille barycentrique de ~1 cm) : leur distance à la forme donne le chevauchement.
            val pts = ArrayList<Vec3>(); val k = 90
            for (i in 0..k) for (j in 0..k - i) pts.add(tri.a + (tri.b - tri.a) * (i / k.toFloat()) + (tri.c - tri.a) * (j / k.toFloat()))
            fun chevauche(pos: Vec3): Float { val so = Solide(s, pos, q); return pts.minOf { so.distance(it) } }
            val d = chevauche(p)
            val w = PhysicsWorld(gravity = Vec3.ZERO)
            w.addMesh(TriangleMesh(floatArrayOf(tri.a.x, tri.a.y, tri.a.z, tri.b.x, tri.b.y, tri.b.z, tri.c.x, tri.c.y, tri.c.z)))
            val cs = w.overlaps(s, p, q, margin = .02f)
            val pireC = cs.minByOrNull { it.separation }
            if (d < -.05f) {
                // Chevauchement profond (après une téléportation) : mesuré à part.
                profondsN++
                if (pireC != null && pireC.separation < 0f) {
                    val apres = chevauche(p + pireC.normal * (-pireC.separation + .002f)); profondPire = min(profondPire, apres); if (apres < -tol) profondsRestes++
                }
                return@repeat
            }
            n++
            if (d < -tol && (pireC == null || pireC.separation >= 0f)) manques++
            if (pireC != null && pireC.separation < 0f) {
                val apres = chevauche(p + pireC.normal * (-pireC.separation + .002f))
                pire = min(pire, apres)
                if (apres < -tol) {
                    restes++
                    if (System.getenv("QA_DETAIL") != null) println("DETAIL tri ${nom(s)} ${s.let { when (it) { is Box -> "${it.halfX},${it.halfY},${it.halfZ}"; is Capsule -> "${it.radius},${it.halfHeight}"; is Sphere -> "${it.radius}" } }} " +
                        "q=$q p=$p hauteur=${f3(((p - tri.a) dot tri.n))} avant=${mm(d)} s=${mm(pireC.separation)} n=${pireC.normal} apres=${mm(apres)} contacts=${cs.size} tri=${tri.a} ${tri.b} ${tri.c}")
                }
            }
        }
        "$n cas : $manques manqués, $restes non séparés (pire reste ${mm(-pire)})" to (manques == 0 && restes == 0)
    }
    info("contacts forme–triangle profonds (> 5 cm)") { "$profondsN cas : $profondsRestes non séparés d'un coup (pire reste ${mm(-profondPire)}) — la sortie se fait en plusieurs pas" }
}
