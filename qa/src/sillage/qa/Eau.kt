package sillage.qa

import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.Compound
import sillage.physics.ConvexHull
import sillage.physics.PhysicsWorld
import sillage.physics.Shape
import sillage.physics.Sphere
import sillage.physics.TriangleMesh
import sillage.physics.WaterEntry
import sillage.physics.addWater
import sillage.physics.waterList
import java.util.Random
import kotlin.math.*

private const val DT = 1f / 60f

/** Une forme au hasard de toutes les sortes, pour l'eau. */
private fun Random.formeEau(): Shape = when (nextInt(5)) {
    0 -> Sphere(f(.1f, .5f))
    1 -> Box(f(.1f, .5f), f(.1f, .5f), f(.1f, .5f))
    2 -> Capsule(f(.1f, .3f), f(.1f, .5f))
    3 -> ConvexHull((0 until 8 + nextInt(40)).map { val d = dir(); Vec3(d.x * f(.15f, .5f), d.y * f(.15f, .5f), d.z * f(.15f, .5f)) }, 32)
    else -> Compound((0 until 2 + nextInt(2)).map { Compound.Part(if (nextBoolean()) Sphere(f(.08f, .25f)) else Box(f(.05f, .25f), f(.05f, .25f), f(.05f, .25f)), dir() * f(0f, .3f), rotation(), f(.5f, 2f)) })
}

/** Un bassin : l'eau à y = 0 au-dessus d'un fond à −6 m. */
private fun bassin(): PhysicsWorld {
    val w = PhysicsWorld(); w.addMesh(TriangleMesh(solMaille(-6f, 80f))); w.addWater(0f)
    return w
}

/** L'eau qui porte : Archimède sur des formes au hasard, équilibre, énergie, entrées dans l'eau, bateaux, courant. */
fun Qa.eau() {
    val rnd = Random(graine + 1300)

    // Des corps de toutes formes et densités, chacun à part (rien ne se touche), lâchés au-dessus de l'eau.
    var flottants = 0; var pireFraction = 0f; var coules = 0; var maltraites = 0; var agitation = 0f; var finis = true
    repeat(cas(2, 5)) {
        val w = bassin()
        val corps = (0 until 30).map { k ->
            val s = rnd.formeEau(); val densite = if (k % 5 == 4) rnd.f(1100f, 3000f) else rnd.f(100f, 950f)
            val b = w.add(s, Vec3((k % 6) * 2.5f - 6f, rnd.f(.5f, 3f), (k / 6) * 2.5f - 5f), rnd.rotation(), density = densite)
            b to b.mass / s.volume                             // a compound's parts weigh by their own densities
        }
        repeat(1200) { w.step(DT) }
        for ((b, densite) in corps) {
            finis = finis && b.fini()
            if (densite < 1000f) {
                flottants++
                // Archimède : la fraction sous l'eau vaut le rapport des densités, quelle que soit la forme et la pose.
                val e = abs(b.submergedFraction - densite / 1000f); pireFraction = max(pireFraction, e)
                agitation = max(agitation, b.velocity.length() + b.shape.boundingRadius * b.angularVelocity.length())
                if (b.position.y < -3f) maltraites++
            } else if (b.plusBas() > -5.9f || b.submergedFraction < 1f) {
                coules++
                if (System.getenv("QA_DETAIL") != null) println("DETAIL eau dense ${b.shape} densité $densite en ${b.position} bas ${b.plusBas()} fraction ${b.submergedFraction} v=${b.velocity}")
            }
        }
    }
    controle("Archimède : fraction sous l'eau = densité relative (formes et poses au hasard, 20 s)", "écart ≤ 0,5 %") {
        "$flottants corps flottants : écart max ${pct(pireFraction)}${if (maltraites > 0) ", $maltraites trop bas" else ""}" to (finis && pireFraction <= .005f && maltraites == 0)
    }
    controle("plus denses que l'eau : au fond", "tous") { "${coules} restés en route" to (coules == 0) }
    controle("équilibre atteint sans tremblement (20 s)", "vitesse ≤ 1 cm/s") { "vitesse max ${cm(agitation)}/s" to (agitation <= .01f) }

    controle("énergie : sans traînée, un corps lâché au-dessus de l'eau n'en ressort jamais plus haut", "≤ +2 mm") {
        var pire = -1f
        repeat(cas(6, 20)) {
            val w = bassin(); val eau = w.waterList[0]
            eau.linearDrag = 0f; eau.angularDrag = 0f; eau.waveDamping = 0f; eau.dragCoefficient = 0f
            val s = rnd.formeEau(); val q = rnd.rotation()
            val b = w.add(s, Vec3.ZERO, q, density = rnd.f(200f, 800f))
            b.angularDamping = 1000f                           // pure heave: no turning energy to trade
            val y0 = rnd.f(.05f, .5f) - b.plusBas()             // its lowest point 5 to 50 cm above the water, at rest
            b.position = Vec3(0f, y0, 0f)
            repeat(240) { w.step(DT); pire = max(pire, b.position.y - y0) }
        }
        "au plus ${mm(max(0f, pire))} au-dessus du départ" to (pire <= .002f)
    }

    // Un corps très léger peut ressauter hors de l'eau après sa plongée : seules les entrées rapides (plus de 1 m/s) comptent.
    controle("entrées dans l'eau : une par chute, à la vitesse de la chute", "écart ≤ 3 %, une seule entrée à plus de 1 m/s") {
        var n = 0; var pire = 0f; var mauvais = 0
        repeat(cas(10, 30)) {
            val w = bassin(); val entrees = ArrayList<WaterEntry>(); w.waterList[0].onEnter = { entrees.add(it) }
            val s = rnd.formeEau(); val q = rnd.rotation()
            val b = w.add(s, Vec3(0f, 0f, 0f), q, density = rnd.f(200f, 800f))
            val bas = b.plusBas(); val hauteur = rnd.f(.5f, 4f)
            b.position = Vec3(0f, hauteur - bas, 0f)               // its lowest point at that height
            repeat(360) { w.step(DT) }
            n++
            if (entrees.count { it.verticalSpeed < -1f } != 1 || entrees[0].verticalSpeed >= -1f) {
                mauvais++
                if (System.getenv("QA_DETAIL") != null) println("DETAIL eau chute ${s} densité ${b.mass / s.volume} de $hauteur m : ${entrees.size} entrées ${entrees.map { it.verticalSpeed }}")
            }
            else pire = max(pire, abs(entrees[0].verticalSpeed / -sqrt(2 * 9.81f * hauteur) - 1f))
        }
        "$n chutes : $mauvais sans une seule entrée rapide, écart de vitesse max ${pct(pire)}" to (mauvais == 0 && pire <= .03f)
    }

    controle("bateaux à lest au hasard : se redressent d'une gîte jusqu'à 70°", "tous droits à 2° près en 20 s") {
        var tordus = 0; val n = cas(4, 12)
        repeat(n) {
            val l = rnd.f(1.5f, 3f); val b = rnd.f(.8f, 1.2f); val h = rnd.f(.3f, .5f)
            val coque = ConvexHull(listOf(Vec3(-b, h, -l), Vec3(b, h, -l), Vec3(-b, h, l), Vec3(b, h, l), Vec3(-b * .5f, -h, -l * .8f), Vec3(b * .5f, -h, -l * .8f),
                Vec3(-b * .5f, -h, l * .8f), Vec3(b * .5f, -h, l * .8f), Vec3(0f, h, l * 1.3f)))
            val bateau = Compound(Compound.Part(coque, coque.centre), Compound.Part(Box(.06f, .15f, l * .5f), Vec3(0f, -h - .15f, 0f), density = 30f))
            val w = bassin()
            val gite = rnd.f(-1.2f, 1.2f)
            val corps = w.add(bateau, Vec3(0f, .3f, 0f), Quat.axisAngle(Vec3.Z, gite), density = 150f)
            repeat(1200) { w.step(DT) }
            if (corps.rotation.rotate(Vec3.Y).y < cos(2f * PI.toFloat() / 180f)) {
                tordus++
                if (System.getenv("QA_DETAIL") != null) println("DETAIL eau bateau l=$l b=$b h=$h gîte $gite : haut ${corps.rotation.rotate(Vec3.Y)}, fraction ${corps.submergedFraction}")
            }
        }
        "$n bateaux : $tordus restés penchés" to (tordus == 0)
    }

    controle("courant : ce qui flotte prend la vitesse de l'eau", "écart ≤ 3 %") {
        var pire = 0f
        repeat(cas(3, 8)) {
            val w = bassin(); val courant = Vec3(rnd.f(-2f, 2f), 0f, rnd.f(-2f, 2f)); w.waterList[0].flow = courant
            val b = w.add(rnd.formeEau(), Vec3(0f, .5f, 0f), rnd.rotation(), density = rnd.f(200f, 800f))
            repeat(900) { w.step(DT) }
            pire = max(pire, (Vec3(b.velocity.x, 0f, b.velocity.z) - courant).length() / max(.1f, courant.length()))
        }
        "écart max ${pct(pire)}" to (pire <= .03f)
    }

    info("coût de la flottaison (400 corps éveillés qui flottent)") {
        val w = bassin(); w.timeToSleep = 1e9f
        val s = ConvexHull((0 until 30).map { rnd.dir() * .3f }, 24)
        val corps = (0 until 400).map { k -> w.add(s, Vec3((k % 20) * 1.5f - 15f, 0f, (k / 20) * 1.5f - 15f), density = 500f) }
        repeat(60) { w.step(DT) }
        var tAvec = 0L
        repeat(120) { k -> if (k % 30 == 0) for (b in corps) b.velocity = Vec3(0f, -.5f, 0f); val t0 = System.nanoTime(); w.step(DT); tAvec += System.nanoTime() - t0 }
        "${f2(tAvec / 1e6 / 120)} ms par pas pour 400 enveloppes de 24 sommets dans l'eau (${f2(tAvec / 1e3 / 120 / 400)} µs par corps, intégration et sommeil compris)"
    }
}
