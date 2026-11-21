package sillage.qa

import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.BodyType
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.Character
import sillage.physics.Compound
import sillage.physics.ConvexHull
import sillage.physics.PhysicsWorld
import sillage.physics.Shape
import sillage.physics.Sphere
import sillage.physics.TriangleMesh
import sillage.physics.overlaps
import sillage.physics.raycast
import java.util.Random
import kotlin.math.*

private const val DT = 1f / 60f

/** Une enveloppe au hasard : des points sur un ellipsoïde cabossé (un rocher), ou une caisse biseautée. */
private fun Random.enveloppe(): ConvexHull = if (nextInt(4) == 0) {
    val h = f(.15f, .5f); val b = h * f(.1f, .3f)
    ConvexHull((0 until 8).flatMap { k ->
        val sx = if (k and 1 == 0) -1f else 1f; val sy = if (k and 2 == 0) -1f else 1f; val sz = if (k and 4 == 0) -1f else 1f
        listOf(Vec3(sx * h, sy * (h - b), sz * (h - b)), Vec3(sx * (h - b), sy * h, sz * (h - b)), Vec3(sx * (h - b), sy * (h - b), sz * h))
    })
} else {
    val a = f(.15f, .6f); val b = f(.15f, .6f); val c = f(.15f, .6f)
    ConvexHull((0 until 8 + nextInt(50)).map { val d = dir(); Vec3(d.x * a, d.y * b, d.z * c) * f(.7f, 1f) }, if (nextBoolean()) 16 else 32)
}

/** Un corps composé au hasard : deux à quatre parties (boules, boîtes, capsules, enveloppes) serrées. */
private fun Random.compose(): Compound = Compound((0 until 2 + nextInt(3)).map {
    val s: Shape = when (nextInt(4)) { 0 -> Sphere(f(.08f, .3f)); 1 -> Box(f(.05f, .3f), f(.05f, .3f), f(.05f, .3f)); 2 -> Capsule(f(.05f, .2f), f(.05f, .3f)); else -> enveloppe().scaled(.6f) }
    Compound.Part(s, dir() * f(0f, .35f), rotation(), density = f(.5f, 3f))
})

private fun Random.formeOuEnveloppe(): Shape = when (nextInt(6)) {
    0 -> Sphere(f(.1f, .6f)); 1 -> Box(f(.1f, .6f), f(.1f, .6f), f(.1f, .6f)); 2 -> Capsule(f(.1f, .35f), f(.1f, .5f)); 3 -> compose(); else -> enveloppe()
}

private fun nomForme(s: Shape) = when (s) { is Sphere -> "sphère"; is Box -> "boîte"; is Capsule -> "capsule"; is ConvexHull -> "enveloppe"; is Compound -> "composé" }

/** Enveloppes convexes et corps composés : contre une géométrie de référence écrite à part, en tas, sous le personnage. */
fun Qa.formes() {
    val rnd = Random(graine + 1100)

    // Contacts : la référence est la distance signée échantillonnée sur les surfaces (comme pour les formes simples).
    val tol = .006f; val marge = .02f
    var paires = 0; var nonVus = 0; var faux = 0; var nonResolus = 0; var pireResidu = 0f
    val parCouple = HashMap<String, Int>()
    repeat(cas(100, 400)) {
        val sa: Shape = if (rnd.nextInt(3) == 0) rnd.compose() else rnd.enveloppe()
        val sb = rnd.formeOuEnveloppe()
        val qa = rnd.rotation(); val qb = rnd.rotation()
        val pb = rnd.dir() * ((sa.boundingRadius + sb.boundingRadius) * rnd.f(.35f, 1.05f))
        val a = Solide(sa, Vec3.ZERO, qa)
        val dref = distanceSolides(a, Solide(sb, pb, qb), 1500)
        if (dref < -.05f || dref > .06f) return@repeat
        val w = PhysicsWorld(gravity = Vec3.ZERO); w.add(sa, Vec3.ZERO, qa, type = BodyType.STATIC)
        val cs = w.overlaps(sb, pb, qb, margin = marge)
        paires++
        val cle = "${nomForme(sa)}–${nomForme(sb)}"
        val pire = cs.minByOrNull { it.separation }
        if (dref < -tol && (pire == null || pire.separation >= 0f)) { nonVus++; parCouple.merge(cle, 1, Int::plus) }
        if (dref > marge + tol && cs.any { it.separation < 0f }) { faux++; parCouple.merge(cle, 1, Int::plus) }
        if (pire != null && pire.separation < 0f) {
            val apres = distanceSolides(a, Solide(sb, pb + pire.normal * (-pire.separation + .002f), qb), 1500)
            pireResidu = min(pireResidu, apres)
            if (apres < -tol) {
                nonResolus++; parCouple.merge(cle, 1, Int::plus)
                if (System.getenv("QA_DETAIL") != null) println("DETAIL formes $cle dref=${mm(dref)} s=${mm(pire.separation)} n=${pire.normal} après=${mm(apres)}")
            }
        }
    }
    val detail = if (parCouple.isEmpty()) "" else " (${parCouple.entries.joinToString { "${it.key} ${it.value}" }})"
    controle("enveloppes et composés : chevauchements vus", "aucun manqué au-delà de ${mm(tol)}") { "$paires paires, $nonVus manqués$detail" to (nonVus == 0) }
    controle("enveloppes et composés : pas de faux chevauchement", "aucun au-delà de la marge") { "$paires paires, $faux faux" to (faux == 0) }
    controle("enveloppes et composés : normale et profondeur séparent (≤ 5 cm)", "reste ≤ ${mm(tol)}") { "$nonResolus non séparées, pire reste ${mm(-pireResidu)}" to (nonResolus == 0) }

    controle("rayons et balayages contre enveloppes et composés = marche sur distance exacte", "écart ≤ 1 mm") {
        var n = 0; var pire = 0f; var manques = 0
        repeat(cas(3, 8)) {
            val w = PhysicsWorld(gravity = Vec3.ZERO)
            val solides = ArrayList<Solide>()
            repeat(14) {
                val s: Shape = if (rnd.nextInt(3) == 0) rnd.compose() else rnd.enveloppe()
                val p = Vec3(rnd.f(-4f, 4f), rnd.f(-4f, 4f), rnd.f(-4f, 4f)); val q = rnd.rotation()
                w.add(s, p, q, type = BodyType.STATIC); solides.add(Solide(s, p, q))
            }
            repeat(cas(300, 1000)) {
                val r = if (rnd.nextBoolean()) 0f else rnd.f(.02f, .4f)
                val o = Vec3(rnd.f(-6f, 6f), rnd.f(-6f, 6f), rnd.f(-6f, 6f))
                if (solides.any { it.distance(o) < r + .01f }) return@repeat
                val d = rnd.dir()
                val ref = marcheRayon(o, d, 16f, r) { p -> solides.minOf { it.distance(p) } }
                val moteur = w.raycast(o, d, 16f, radius = r)?.distance
                n++
                if ((ref == null) != (moteur == null)) {
                    manques++
                    if (System.getenv("QA_DETAIL") != null) println("DETAIL rayon o=$o d=$d r=$r réf=$ref moteur=$moteur ${w.raycast(o, d, 16f, radius = r)?.body?.shape}")
                } else if (ref != null && moteur != null) pire = max(pire, abs(ref - moteur))
            }
        }
        "$n rayons et balayages : écart max ${mm(pire)}, $manques touchés d'un seul côté" to (pire <= .001f && manques == 0)
    }

    // Tas au repos dans une fosse : rochers, caisses biseautées et corps composés, masses au hasard.
    var enfoncement = 0f; var dormants = 0; var total = 0; var sousLeSol = 0f
    repeat(cas(2, 5)) {
        val w = PhysicsWorld(); w.add(Box(30f, .5f, 30f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
        for ((x, z) in listOf(3.5f to 0f, -3.5f to 0f, 0f to 3.5f, 0f to -3.5f))
            w.add(Box(if (x == 0f) 4f else .5f, 1.5f, if (z == 0f) 4f else .5f), Vec3(x, 1.5f, z), type = BodyType.STATIC)
        val corps = (0 until cas(40, 70)).map { k ->
            val s: Shape = if (k % 3 == 0) rnd.compose() else rnd.enveloppe()
            w.add(s, Vec3(rnd.f(-2.5f, 2.5f), 1f + k * .25f, rnd.f(-2.5f, 2.5f)), rnd.rotation(), density = rnd.f(200f, 2500f), friction = rnd.f(.3f, .9f))
        }
        // 20 s: rocks roll and slide longer than crates before they settle.
        repeat(1200) { pas ->
            w.step(DT)
            if (System.getenv("QA_DETAIL") != null && pas % 60 == 59) {
                val ev = corps.filter { !it.isSleeping }
                val vif = ev.maxByOrNull { it.velocity.length() + it.shape.boundingRadius * it.angularVelocity.length() }
                println("DETAIL fosse t=${(pas + 1) / 60} s : ${ev.size} éveillés" + (vif?.let { " ; le plus vif ${it.id} ${it.shape} v=${it.velocity.length()} ω=${it.angularVelocity.length()} p=${it.position}" } ?: ""))
                if ((pas + 1) / 60 == 10 && vif != null) for (c in w.contacts()) if (c.a == vif || c.b == vif)
                    println("DETAIL fosse     ${c.a?.id ?: "sol"} ${c.a?.shape} / ${c.b.id} n=${c.normal} s=${mm(c.separation)} N=${f3(c.normalImpulse)} en ${c.point}")
                if ((pas + 1) / 60 == 10 && vif != null) println("DETAIL fosse     ω=${vif.angularVelocity} v=${vif.velocity} masse ${vif.mass}")
            }
        }
        dormants += corps.count { it.isSleeping }; total += corps.size
        if (System.getenv("QA_DETAIL") != null) for (b in corps.filter { !it.isSleeping }.sortedByDescending { it.velocity.length() + it.angularVelocity.length() }.take(5))
            println("DETAIL formes éveillé ${b.shape} v=${b.velocity.length()} ω=${b.angularVelocity.length()} p=${b.position}")
        // The overlaps of each body with the others and the walls, measured after the step (sleeping bodies keep no contacts).
        for (b in corps) for (o in w.overlaps(b.shape, b.position, b.rotation, exclude = b)) if (o.body == null || o.body!!.type == BodyType.STATIC) enfoncement = max(enfoncement, -o.separation)
        val bas = corps.minBy { it.plusBas() }
        if (bas.plusBas() < sousLeSol) {
            sousLeSol = bas.plusBas()
            if (System.getenv("QA_DETAIL") != null) {
                println("DETAIL formes plus bas ${bas.shape} en ${bas.position} q=${bas.rotation}, ${mm(bas.plusBas())}")
                val cs = bas.shape as? Compound
                cs?.parts?.forEach { part ->
                    val q = bas.rotation * part.rotation; val c = bas.position + bas.rotation.rotate(part.position)
                    val low = when (val x = part.shape) { is ConvexHull -> (0 until x.vertexCount).minOf { (c + q.rotate(x.vertex(it))).y }; is Sphere -> c.y - x.radius; else -> c.y }
                    println("DETAIL   partie ${part.shape} en $c plus bas ${mm(low)}")
                }
                for (o in w.overlaps(bas.shape, bas.position, bas.rotation, exclude = bas)) println("DETAIL   contact ${o.body?.shape} ${o.point} n=${o.normal} s=${mm(o.separation)}")
            }
        }
    }
    controle("tas d'enveloppes et de composés dans une fosse (20 s) : enfoncement", "≤ 1 cm, rien sous le sol") { "$total corps : ${mm(enfoncement)} au plus, point le plus bas ${mm(sousLeSol)}" to (enfoncement <= .01f && sousLeSol > -.01f) }
    controle("tas d'enveloppes et de composés : sommeil", "≥ 95 % endormis") { "$dormants/$total endormis (${pct(dormants.toDouble() / total)})" to (dormants >= .95 * total) }

    controle("piles de 10 enveloppes tournées, de 10 tables", "dérive ≤ 2 cm, endormies") {
        val crate = ConvexHull((0 until 8).flatMap { k ->
            val sx = if (k and 1 == 0) -1f else 1f; val sy = if (k and 2 == 0) -1f else 1f; val sz = if (k and 4 == 0) -1f else 1f
            listOf(Vec3(sx * .5f, sy * .42f, sz * .42f), Vec3(sx * .42f, sy * .5f, sz * .42f), Vec3(sx * .42f, sy * .42f, sz * .5f))
        })
        val table = Compound(listOf(Compound.Part(Box(.6f, .03f, .4f), Vec3(0f, .73f, 0f))) +
            listOf(-1f, 1f).flatMap { x -> listOf(-1f, 1f).map { z -> Compound.Part(Box(.025f, .35f, .025f), Vec3(x * .55f, .35f, z * .35f)) } })
        val mesures = ArrayList<String>(); var ok = true
        for ((nom, forme, haut) in listOf(Triple("enveloppes", crate as Shape, 1f), Triple("tables", table as Shape, .77f))) {
            val w = PhysicsWorld(); w.addMesh(TriangleMesh(solMaille()))
            val c = (forme as? Compound)?.centre ?: Vec3.ZERO
            // Tables turn a little each (more, and a leg would miss the top below).
            val tour = if (forme is ConvexHull) .2f else .04f
            val pile = (0 until 10).map { k -> w.add(forme, Vec3(0f, k * haut + (if (forme is ConvexHull) .5f else 0f), 0f) + c, Quat.rotationY(k * tour), density = 600f) }
            repeat(600) { w.step(DT) }
            val p = pile.last().position - pile.last().rotation.rotate(c)
            val derive = hypot(p.x, p.z); val dort = pile.all { it.isSleeping }
            mesures.add("$nom ${mm(derive)}${if (dort) "" else ", éveillées"}")
            ok = ok && derive <= .02f && dort
        }
        mesures.joinToString(" ; ") to ok
    }

    controle("personnage sur un champ de rochers (enveloppes statiques), chemins au hasard", "enfoncement ≤ 3 cm, jamais sous le sol") {
        var pire = 0f; var sous = 0; var images = 0
        repeat(cas(2, 6)) {
            val w = PhysicsWorld(); w.add(Box(20f, .5f, 20f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
            repeat(30) {
                val r = rnd.enveloppe().scaled(rnd.f(.8f, 2.5f))
                w.add(r, Vec3(rnd.f(-8f, 8f), rnd.f(-.2f, .2f), rnd.f(-8f, 8f)), rnd.rotation(), type = BodyType.STATIC)
            }
            repeat(8) { w.add(rnd.enveloppe(), Vec3(rnd.f(-8f, 8f), 2f, rnd.f(-8f, 8f)), rnd.rotation(), density = rnd.f(100f, 800f)) }
            val c = Character(w, Vec3(0f, 3f, 0f))
            var cap = rnd.f(0f, 6.28f)
            repeat(cas(600, 1500)) { i ->
                if (i % 90 == 0) cap = rnd.f(0f, 6.28f)
                val saut = if (i % 150 == 75 && c.onGround) 7f else 0f
                w.step(DT); c.update(Vec3(cos(cap) * 3f, 0f, sin(cap) * 3f), saut, DT)
                if (abs(c.position.x) > 9f || abs(c.position.z) > 9f) c.teleport(Vec3(0f, 3f, 0f))
                pire = max(pire, enfoncementPersonnage(c)); if (c.position.y < -.05f) sous++; images++
            }
        }
        "$images images : enfoncement ${mm(pire)} au plus, $sous sous le sol" to (pire <= .03f && sous == 0)
    }

    controle("déterminisme : tas d'enveloppes et de composés, deux fois", "bit pour bit") {
        fun scene(): List<Float> {
            val r = Random(graine + 1200)
            val w = PhysicsWorld(); w.addMesh(TriangleMesh(solMaille()))
            val corps = (0 until 60).map { k -> w.add(if (k % 3 == 0) r.compose() else r.enveloppe(), Vec3(r.f(-2f, 2f), 1f + k * .3f, r.f(-2f, 2f)), r.rotation()) }
            repeat(300) { w.step(DT) }
            return corps.flatMap { listOf(it.position.x, it.position.y, it.position.z, it.rotation.x, it.rotation.y, it.rotation.z, it.rotation.w) }
        }
        val a = scene(); val b = scene()
        val pareil = a.indices.all { a[it].toRawBits() == b[it].toRawBits() }
        (if (pareil) "identiques" else "DIFFÉRENTS") to pareil
    }

    info("coût de la phase fine des enveloppes (tas de 300 éveillés)") {
        val w = PhysicsWorld(); w.addMesh(TriangleMesh(solMaille())); w.timeToSleep = 1e9f
        val r = Random(5)
        val rocher = ConvexHull((0 until 60).map { val d = r.dir(); Vec3(d.x * .4f, d.y * .28f, d.z * .35f) }, 32)
        for (k in 0 until 300) w.add(rocher, Vec3((k % 10) * .8f - 4f, .5f + (k / 100) * .7f, ((k / 10) % 10) * .8f - 4f), r.rotation())
        repeat(120) { w.step(DT) }
        w.profiling = true; java.util.Arrays.fill(w.phaseNanos, 0L)
        var paires = 0L; val t0 = System.nanoTime()
        repeat(120) { w.step(DT); paires += w.pairCount }
        val pas = (System.nanoTime() - t0) / 1e6 / 120
        "rochers de 32 sommets : ${"%.0f".format(w.phaseNanos[2].toDouble() / max(1L, paires))} ns par paire, ${paires / 120} paires, ${f2(pas)} ms par pas"
    }
}
