package sillage.qa

import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.BodyType
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.Character
import sillage.physics.PhysicsWorld
import sillage.physics.Sphere
import sillage.physics.TriangleMesh
import sillage.physics.overlaps
import java.util.Random
import kotlin.math.*

private const val DT = 1f / 60f

/** Enfoncement du personnage dans ce qui ne bouge pas de soi-même (statique, cinématique, maillages), en mètres. */
fun enfoncementPersonnage(c: Character): Float = enfoncementDetaille(c).first

/** L'enfoncement et ce qui le cause : corps (type, forme) ou maillage, normale. */
fun enfoncementDetaille(c: Character): Pair<Float, String> {
    val forme = Capsule(c.radius, (c.height - 2 * c.radius) / 2)
    var pire = 0f; var quoi = ""
    for (o in c.world.overlaps(forme, c.position.plus(0f, c.height / 2, 0f), exclude = c.body))
        if ((o.body == null || o.body!!.type != BodyType.DYNAMIC) && -o.separation > pire) {
            pire = -o.separation
            quoi = (o.body?.let { "${it.type} ${it.shape::class.simpleName}" } ?: "maillage ${o.material?.name}") + " n=${o.normal} au point ${o.point}"
        }
    return pire to quoi
}

/** Un parcours d'obstacles au hasard : boîtes tournées, rampes, marches et estrades maillées, caisses et ballons. */
private fun parcours(rnd: Random): PhysicsWorld {
    val w = PhysicsWorld()
    w.add(Box(30f, .5f, 30f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
    // Une arène fermée : sans murs, le robot finit par sortir du sol et tomber dans le vide.
    for ((x, z) in listOf(11f to 0f, -11f to 0f, 0f to 11f, 0f to -11f))
        w.add(Box(if (x == 0f) 11.5f else .5f, 2f, if (z == 0f) 11.5f else .5f), Vec3(x, 2f, z), type = BodyType.STATIC)
    repeat(14) {
        val h = rnd.f(.05f, 2.5f)
        w.add(Box(rnd.f(.1f, 1.5f), h / 2, rnd.f(.1f, 1.5f)), Vec3(rnd.f(-9f, 9f), h / 2, rnd.f(-9f, 9f)), Quat.rotationY(rnd.f(0f, 3.2f)), type = BodyType.STATIC)
    }
    repeat(4) {
        val a = rnd.f(5f, 60f) * PI.toFloat() / 180f
        w.add(Box(rnd.f(1f, 3f), .3f, rnd.f(.6f, 1.5f)), Vec3(rnd.f(-9f, 9f), rnd.f(-.1f, .4f), rnd.f(-9f, 9f)),
            Quat.rotationY(rnd.f(0f, 3.2f)) * Quat.axisAngle(Vec3.Z, a), type = BodyType.STATIC)
    }
    repeat(4) {
        val x = rnd.f(-9f, 9f); val z = rnd.f(-9f, 9f); val h = rnd.f(.05f, .6f)
        w.addMesh(TriangleMesh(boiteMaillee(Vec3(x, h - .2f, z), Vec3(x + rnd.f(.6f, 3f), h, z + rnd.f(.6f, 3f)))))
    }
    repeat(5) { w.add(Box(.3f, .3f, .3f), Vec3(rnd.f(-9f, 9f), .3f, rnd.f(-9f, 9f)), mass = rnd.f(5f, 60f)) }
    repeat(3) { w.add(Sphere(.3f), Vec3(rnd.f(-9f, 9f), .3f, rnd.f(-9f, 9f)), mass = .6f, restitution = .6f) }
    return w
}

fun Qa.personnage() {
    val rnd = Random(graine + 200)

    var pire = 0f; var sauts = 0; var dessous = 0; var coinces = 0; var nonFinis = 0; var images = 0; var plusGrandPas = 0f
    val parcoursN = cas(4, 16)
    repeat(parcoursN) { numero ->
        val w = parcours(rnd)
        var dejaAuSol = false
        // Départ dans un endroit dégagé.
        var depart = Vec3.ZERO
        for (essai in 0 until 100) {
            val p = Vec3(rnd.f(-9f, 9f), 0f, rnd.f(-9f, 9f))
            // Capsule d'essai 10 cm au-dessus du sol (sinon elle touche toujours le sol et rien n'est libre).
            if (w.overlaps(Capsule(.3f, .65f), p.plus(0f, 1.05f, 0f), margin = .05f).isEmpty()) { depart = p; break }
        }
        val c = Character(w, depart.plus(0f, .05f, 0f))
        var cap = rnd.dir().let { Vec3(it.x, 0f, it.z).normalized() }; var changer = 0f; var vitesse = 4.4f
        var immobileEnLAir = 0f
        repeat(cas(1200, 3600)) {
            changer -= DT
            if (changer <= 0f) {
                changer = rnd.f(.5f, 2f)
                cap = rnd.dir().let { v -> Vec3(v.x, 0f, v.z).normalized() }
                vitesse = if (rnd.nextFloat() < .25f) 1.8f else 4.4f
                if (rnd.nextFloat() < .15f) cap = Vec3.ZERO
            }
            val saut = if (c.onGround && rnd.nextFloat() < .5f * DT) 8f else 0f
            val avant = c.position
            w.step(DT); c.update(cap * vitesse, saut, DT)
            images++
            val p = c.position
            if (!p.isFinite()) { nonFinis++; return@repeat }
            pire = max(pire, enfoncementPersonnage(c))
            // Un pas d'image : la course, la chute (au plus ~25 m/s) et une marche (35 cm) ; au-delà, c'est un saut anormal.
            val pas = (p - avant).length()
            plusGrandPas = max(plusGrandPas, pas)
            if (pas > (vitesse + 25f) * DT + .4f) sauts++
            if (p.y < -.02f) {
                if (dessous == 0 && System.getenv("QA_DETAIL") != null)
                    println("DETAIL sous le sol : parcours $numero image $it, avant $avant au sol ${dejaAuSol}, après $p, cap $cap × $vitesse, saut $saut, sol ${c.groundBody?.shape ?: c.groundMaterial?.name}")
                dessous++
            }
            dejaAuSol = c.onGround
            immobileEnLAir = if (!c.onGround && abs(p.y - avant.y) < 1e-4f) immobileEnLAir + DT else 0f
            if (immobileEnLAir > 1f) { coinces++; immobileEnLAir = 0f }
        }
    }
    controle("parcours au hasard : pas d'enfoncement", "≤ 3 cm") { "$parcoursN parcours, $images images : ${mm(pire)} au plus" to (pire <= .03f) }
    controle("parcours au hasard : pas de téléportation", "aucun pas d'image anormal") { "$sauts anormaux (plus grand pas ${cm(plusGrandPas)})" to (sauts == 0) }
    controle("parcours au hasard : jamais sous le sol, jamais suspendu", "0 et 0") {
        "$dessous images sous le sol, $coinces fois suspendu en l'air plus d'1 s, $nonFinis non finis" to (dessous == 0 && coinces == 0 && nonFinis == 0)
    }

    controle("marches : montées jusqu'à 35 cm, refusées au-delà", "corps et maillages") {
        val faux = ArrayList<String>(); val montees = ArrayList<String>()
        for (maillee in listOf(false, true)) for (cmH in 5..50 step 5) {
            val h = cmH / 100f
            val w = PhysicsWorld(); w.add(Box(30f, .5f, 30f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
            if (maillee) w.addMesh(TriangleMesh(boiteMaillee(Vec3(1f, h - .3f, -3f), Vec3(5f, h, 3f))))
            else w.add(Box(2f, h / 2, 3f), Vec3(3f, h / 2, 0f), type = BodyType.STATIC)
            val c = Character(w, Vec3(0f, .02f, 0f))
            repeat(30) { w.step(DT); c.update(Vec3.ZERO, 0f, DT) }
            repeat(60) { w.step(DT); c.update(Vec3(3f, 0f, 0f), 0f, DT) }
            val monte = c.position.y > h - .02f
            if (monte) montees.add("${if (maillee) "m" else "c"}$cmH")
            if (monte != (h <= c.stepHeight + .001f)) faux.add("${if (maillee) "maillage" else "corps"} $cmH cm ${if (monte) "montée" else "refusée"}")
        }
        val (corps, mailles) = montees.partition { it.startsWith("c") }
        "corps jusqu'à ${corps.lastOrNull()?.drop(1) ?: 0} cm, maillages jusqu'à ${mailles.lastOrNull()?.drop(1) ?: 0} cm${if (faux.isEmpty()) "" else " ; faux : " + faux.joinToString()}" to faux.isEmpty()
    }

    controle("pentes : praticables jusqu'à 50°, glissantes au-delà", "rampes maillées de 10 à 70°") {
        val faux = ArrayList<String>(); val gravies = ArrayList<Int>()
        for (deg in 10..70 step 10) {
            val a = deg * PI.toFloat() / 180f
            val w = PhysicsWorld(); w.add(Box(30f, .5f, 30f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
            // Rampe qui part de x = 1 et monte de 3 m.
            val long = 3f / tan(a)
            w.addMesh(TriangleMesh(floatArrayOf(1f, 0f, -3f, 1f, 0f, 3f, 1f + long, 3f, 3f, 1f, 0f, -3f, 1f + long, 3f, 3f, 1f + long, 3f, -3f)))
            val c = Character(w, Vec3(0f, .02f, 0f)); var haut = 0f
            repeat(20) { w.step(DT); c.update(Vec3.ZERO, 0f, DT) }
            // On regarde la plus grande hauteur atteinte : en haut, la rampe s'arrête net et l'on retombe.
            repeat(180) { w.step(DT); c.update(Vec3(2.5f, 0f, 0f), 0f, DT); haut = max(haut, c.position.y) }
            val gravie = haut > 1f
            if (gravie) gravies.add(deg)
            if (gravie != (deg <= c.maxSlope)) faux.add("$deg° ${if (gravie) "gravie" else "refusée"} (hauteur atteinte ${f2(haut)} m)")
        }
        "gravies : ${gravies.joinToString("°, ")}°${if (faux.isEmpty()) "" else " ; faux : " + faux.joinToString()}" to faux.isEmpty()
    }

    controle("plafonds : bloqué dessous, tête sans enfoncement", "1,7 m bloque, 1,9 m passe, saut sous 2,2 m sans pénétrer") {
        fun essai(hauteur: Float, sauter: Boolean): Pair<Float, Float> {
            val w = PhysicsWorld(); w.add(Box(30f, .5f, 30f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
            w.add(Box(2f, .2f, 3f), Vec3(4f, hauteur + .2f, 0f), type = BodyType.STATIC)
            val c = Character(w, Vec3(0f, .02f, 0f)); var pire = 0f
            repeat(20) { w.step(DT); c.update(Vec3.ZERO, 0f, DT) }
            repeat(120) { i -> w.step(DT); c.update(Vec3(3f, 0f, 0f), if (sauter && c.onGround && i % 30 == 10) 8f else 0f, DT); pire = max(pire, enfoncementPersonnage(c)) }
            return c.position.x to pire
        }
        val (x17, p17) = essai(1.7f, false); val (x19, p19) = essai(1.9f, false); val (_, p22) = essai(2.2f, true)
        val ok = x17 < 2f - .2f && x19 > 4f && max(p17, max(p19, p22)) <= .03f
        "sous 1,7 m : arrêté à x = ${f2(x17)} ; sous 1,9 m : x = ${f2(x19)} ; enfoncement max ${mm(max(p17, max(p19, p22)))}" to ok
    }

    controle("apparaître dans un mur : on en sort sans traverser le sol", "jamais sous le sol, sortie progressive (≤ 70 cm par image)") {
        var pire = 0f; var dessous = 0; var bond = 0f
        repeat(cas(6, 20)) {
            val w = PhysicsWorld(); w.add(Box(30f, .5f, 30f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
            val h = rnd.f(.5f, 3f); val l = rnd.f(.4f, 2f)
            w.add(Box(l, h / 2, rnd.f(.4f, 2f)), Vec3(0f, h / 2, 0f), Quat.rotationY(rnd.f(0f, 3f)), type = BodyType.STATIC)
            val c = Character(w, Vec3(rnd.f(-.3f, .3f), .02f, rnd.f(-.3f, .3f)))
            repeat(180) {
                val avant = c.position
                w.step(DT); c.update(Vec3.ZERO, 0f, DT)
                bond = max(bond, (c.position - avant).length()); if (c.position.y < -.02f) dessous++
                pire = min(pire, c.position.y)
            }
        }
        "plus bas ${cm(pire)}, $dessous images sous le sol, plus grand bond ${cm(bond)}" to (dessous == 0 && bond <= .7f)
    }

    controle("plateforme mobile : on reste dessus (30 s)", "jamais traversée, enfoncement ≤ 3 cm") {
        val w = PhysicsWorld(); w.add(Box(30f, .5f, 30f), Vec3(0f, -.5f, -10f), type = BodyType.STATIC)
        val plateau = w.add(Box(1.5f, .2f, 1.5f), Vec3(0f, 1f, 0f), type = BodyType.KINEMATIC)
        val c = Character(w, Vec3(0f, 1.5f, 0f)); var pire = 0f; var tombe = false; var t = 0f
        repeat(1800) { i ->
            t += DT
            plateau.moveTo(Vec3(0f, 1f + .4f * sin(2 * PI.toFloat() * t * .7f), 0f), DT)
            val marche = if ((i / 120) % 2 == 0) Vec3(.8f, 0f, 0f) else Vec3(-.8f, 0f, 0f)
            w.step(DT); c.update(marche, 0f, DT)
            pire = max(pire, enfoncementPersonnage(c))
            if (c.position.y < plateau.position.y) tombe = true
        }
        "enfoncement max ${mm(pire)}${if (tombe) ", TRAVERSÉE" else ""}" to (!tombe && pire <= .03f)
    }
}
