package sillage.qa

import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.Body
import sillage.physics.BodyType
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.Character
import sillage.physics.ConeTwistJoint
import sillage.physics.PhysicsWorld
import sillage.physics.Sphere
import sillage.physics.TriangleMesh
import sillage.physics.Vehicle
import java.util.Random
import kotlin.math.*

private const val DT = 1f / 60f

/** Une voiture de 1 200 kg (caisse 1,8 × 0,5 × 4 m, roues de 35 cm). */
private fun voiture(w: PhysicsWorld, a: Vec3, rotation: Quat = Quat.IDENTITY): Vehicle {
    val caisse = w.add(Box(.9f, .25f, 2f), a, rotation, mass = 1200f)
    return Vehicle(w, caisse).also { v ->
        for (x in floatArrayOf(-.8f, .8f)) for (z in floatArrayOf(-1.3f, 1.3f)) v.addWheel(Vec3(x, -.15f, z), radius = .35f, travel = .3f, steered = z > 0f)
    }
}

/** Un pantin de 11 corps : cônes et torsion au cou, aux épaules et aux hanches, charnières aux coudes et aux genoux. */
private fun pantin(w: PhysicsWorld, p: Vec3, tour: Quat): List<Body> {
    fun a(x: Float, y: Float) = p + tour.rotate(Vec3(x, y, 0f))
    val bas = tour.rotate(Vec3(0f, -1f, 0f)); val haut = tour.rotate(Vec3.Y); val cote = tour.rotate(Vec3.X)
    val tronc = w.add(Box(.18f, .3f, .1f), a(0f, 1.2f), tour, mass = 30f)
    val tete = w.add(Sphere(.11f), a(0f, 1.65f), tour, mass = 5f)
    w.addConeTwist(tronc, tete, a(0f, 1.52f), haut, .6f, .4f, -.5f, .5f, across = cote)
    val corps = arrayListOf(tronc, tete)
    for (sx in floatArrayOf(-1f, 1f)) {
        val bras = w.add(Capsule(.05f, .13f), a(sx * .25f, 1.32f), tour, mass = 2.5f)
        w.addConeTwist(tronc, bras, a(sx * .25f, 1.5f), bas, 1.2f, .8f, -.6f, .6f, across = cote)
        val avant = w.add(Capsule(.04f, .12f), a(sx * .25f, .96f), tour, mass = 1.5f)
        w.addHinge(bras, avant, a(sx * .25f, 1.14f), cote).limits(0f, 2.5f)
        val cuisse = w.add(Capsule(.07f, .16f), a(sx * .1f, .67f), tour, mass = 8f)
        w.addConeTwist(tronc, cuisse, a(sx * .1f, .9f), bas, 1f, .5f, -.4f, .4f, across = cote)
        val jambe = w.add(Capsule(.05f, .17f), a(sx * .1f, .22f), tour, mass = 4f)
        w.addHinge(cuisse, jambe, a(sx * .1f, .44f), cote).limits(-2.4f, 0f)
        corps += listOf(bras, avant, cuisse, jambe)
    }
    return corps
}

/** Articulations, véhicules, plateformes, collision continue : chacun contre la physique attendue, et au hasard. */
fun Qa.articulations() {
    val rnd = Random(graine + 900)

    controle("pendules : période contre le pendule pesant", "écart ≤ 1 % (longueurs 0,3 à 3 m)") {
        var pire = 0f; val details = ArrayList<String>()
        for (l in floatArrayOf(.3f, 1f, 3f)) {
            val w = PhysicsWorld(); val r = .05f; val angle = .1f
            val ancre = Vec3(0f, 5f, 0f)
            val b = w.add(Sphere(r), ancre + Vec3(sin(angle) * l, -cos(angle) * l, 0f))
            w.addBall(null, b, ancre)
            val passages = ArrayList<Float>(); var t = 0f; var avant = b.position.x
            repeat((12f / DT).toInt()) { w.step(DT); t += DT; val x = b.position.x; if (avant < 0f && x >= 0f) passages.add(t - DT * x / (x - avant)); avant = x }
            val periode = (passages.last() - passages.first()) / (passages.size - 1)
            val attendu = 2 * PI.toFloat() * sqrt((l * l + .4f * r * r) / (9.81f * l)) * (1 + angle * angle / 16)
            val e = abs(periode / attendu - 1); pire = max(pire, e); details.add("${f1(l)} m : ${pct(e)}")
        }
        details.joinToString(", ") to (pire <= .01f)
    }

    // Au repos, une articulation souple cède en proportion de ce qu'elle porte (le pas souple de Box2D v3) :
    // celle du haut d'une chaîne porte tous les maillons, d'où un seuil par maillon porté.
    controle("chaînes au hasard lâchées sur un terrain (10 s)", "valeurs finies ; écart des maillons ≤ 4 cm en mouvement, au repos ≤ 0,15 mm par maillon porté") {
        var pireMouvement = 0f; var pireRepos = 0f; var pireParMaillon = 0f; var finies = true; var n = 0
        repeat(cas(2, 6)) {
            val w = PhysicsWorld(); w.addMesh(TriangleMesh(solMaille()))
            val maillons = 5 + rnd.nextInt(26)
            val depart = Vec3(rnd.f(-2f, 2f), rnd.f(3f, 6f), rnd.f(-2f, 2f))
            val fin = depart + rnd.dir() * (maillons * rnd.f(.1f, .25f))
            if (fin.y < .5f) return@repeat
            val corps = w.addChain(depart, fin, maillons, radius = rnd.f(.02f, .05f), start = null)
            corps.last().velocity = rnd.dir() * rnd.f(0f, 6f)
            for (b in corps) { b.linearDamping = .3f; b.angularDamping = .3f }
            repeat((10f / DT).toInt()) {
                w.step(DT)
                pireMouvement = max(pireMouvement, w.jointList.maxOf { (it.anchorA - it.anchorB).length() })
            }
            repeat((10f / DT).toInt()) { w.step(DT) }
            val repos = w.jointList.maxOf { (it.anchorA - it.anchorB).length() }
            pireRepos = max(pireRepos, repos); pireParMaillon = max(pireParMaillon, repos / maillons)
            finies = finies && corps.all { it.fini() }; n++
        }
        "$n chaînes : ${mm(pireMouvement)} en mouvement, ${mm(pireRepos)} au repos (${f3(pireParMaillon * 1000)} mm par maillon)" to
            (finies && pireMouvement <= .04f && pireParMaillon <= .00015f)
    }

    controle("portes à butées sous des coups au hasard", "dépassement des butées ≤ 0,03 rad, gonds ≤ 3 mm") {
        var depasse = 0f; var gonds = 0f
        repeat(cas(4, 12)) {
            val w = PhysicsWorld()
            val porte = w.add(Box(.45f, 1f, .03f), Vec3(.45f, 1f, 0f), density = rnd.f(300f, 900f))
            val bas = rnd.f(-2.5f, -.5f); val haut = rnd.f(.2f, 2f)
            val g = w.addHinge(null, porte, Vec3(0f, 1f, 0f), Vec3.Y); g.limits(bas, haut)
            repeat(240) { i ->
                if (i % 30 == 0) porte.applyImpulse(Vec3(0f, 0f, rnd.f(-60f, 60f)), Vec3(.9f, rnd.f(.2f, 1.8f), 0f))
                w.step(DT)
                depasse = max(depasse, max(bas - g.angle, g.angle - haut))
                gonds = max(gonds, (g.anchorB - Vec3(0f, 1f, 0f)).length())
            }
        }
        "dépassement ${f3(max(0f, depasse))} rad, gonds ${mm(gonds)}" to (depasse <= .03f && gonds <= .003f)
    }

    // Comme les chaînes : en mouvement, les articulations souples cèdent un peu (un avant-bras de 1,5 kg frappé à 8 m/s,
    // un membre coincé sous le tronc : les contacts ont le dernier mot) ; au repos, un membre pressé par un autre aussi.
    controle("pantins (cônes et torsion) lâchés puis frappés au hasard", "valeurs finies ; sous les coups, écart ≤ 4 cm, butées ≤ 0,1 rad ; au repos ≤ 6 mm et 0,03 rad") {
        var finis = true; var coups = 0f; var depasseCoups = 0f; var repos = 0f; var depasseRepos = 0f; var vmax = 0f
        fun ecart(w: PhysicsWorld) = w.jointList.maxOf { (it.anchorA - it.anchorB).length() }
        fun depasse(w: PhysicsWorld) = w.jointList.filterIsInstance<ConeTwistJoint>().maxOf {
            max(it.swingAngle - max(it.swing, it.swing2), max(it.twistLower - it.twistAngle, it.twistAngle - it.twistUpper)) }
        repeat(cas(3, 10)) {
            val w = PhysicsWorld(); w.addMesh(TriangleMesh(solMaille()))
            val corps = pantin(w, Vec3(rnd.f(-1f, 1f), rnd.f(1f, 3f), rnd.f(-1f, 1f)), rnd.rotation())
            corps[0].angularVelocity = rnd.dir() * rnd.f(0f, 6f)
            repeat(180) { w.step(DT) }
            repeat(20) {
                val b = corps[rnd.nextInt(corps.size)]
                b.applyImpulse(rnd.dir() * (b.mass * rnd.f(0f, 8f)), b.position + rnd.dir() * .05f)
                repeat(18) { w.step(DT); coups = max(coups, ecart(w)); depasseCoups = max(depasseCoups, depasse(w)); vmax = max(vmax, corps.maxOf { it.velocity.length() }) }
            }
            repeat(240) { w.step(DT) }
            repos = max(repos, ecart(w)); depasseRepos = max(depasseRepos, depasse(w))
            finis = finis && corps.all { it.fini() }
        }
        "${cas(3, 10)} pantins : coups ${mm(coups)} et ${f3(max(0f, depasseCoups))} rad, repos ${mm(repos)} et ${f3(max(0f, depasseRepos))} rad, vitesse max ${f1(vmax)} m/s" to
            (finis && coups <= .04f && depasseCoups <= .1f && repos <= .006f && depasseRepos <= .03f)
    }

    controle("moteurs : charnière et glissière à leur vitesse", "écart ≤ 1 %") {
        val w = PhysicsWorld(gravity = Vec3.ZERO)
        val roue = w.add(Capsule(.4f, .05f), Vec3(0f, 1f, 0f), Quat.axisAngle(Vec3.X, PI.toFloat() / 2))
        val c = w.addHinge(null, roue, roue.position, Vec3.Z); c.motor(4f, maxTorque = 100f)
        val p = PhysicsWorld()
        val cabine = p.add(Box(.6f, .1f, .6f), Vec3.ZERO, mass = 300f)
        val s = p.addSlider(null, cabine, Vec3.Y); s.motor(.8f, maxForce = 8000f); s.limits(0f, 10f)
        repeat(120) { w.step(DT); p.step(DT) }
        val e1 = abs(c.speed / 4f - 1); val e2 = abs(s.speed / .8f - 1)
        "charnière ${pct(e1)}, glissière ${pct(e2)}" to (max(e1, e2) <= .01f)
    }

    controle("ressorts : fréquence propre, masses de 0,1 à 1000 kg", "écart ≤ 3 %") {
        var pire = 0f
        for (masse in floatArrayOf(.1f, 10f, 1000f)) for (hz in floatArrayOf(.5f, 2f, 5f)) {
            val w = PhysicsWorld(gravity = Vec3.ZERO)
            val ancre = Vec3(0f, 2f, 0f)
            val b = w.add(Sphere(.1f), ancre + Vec3(0f, -1f, 0f), mass = masse)
            w.addSpring(null, ancre, b, b.position, hertz = hz, dampingRatio = 0f)
            b.velocity = Vec3(0f, -.3f, 0f)
            val passages = ArrayList<Float>(); var t = 0f; var avant = 0f
            repeat((max(4f, 6f / hz) / DT).toInt()) { w.step(DT); t += DT; val x = (b.position - ancre).length() - 1f; if (avant < 0f && x >= 0f) passages.add(t - DT * x / (x - avant)); avant = x }
            val periode = (passages.last() - passages.first()) / (passages.size - 1)
            pire = max(pire, abs(periode * hz - 1))
        }
        "pire ${pct(pire)}" to (pire <= .03f)
    }

    controle("voiture au repos : hauteur, charge, sommeil", "enfoncement à 2 mm de g/(2πf)², charge à 2 %, endormie") {
        val w = PhysicsWorld(); w.addMesh(TriangleMesh(solMaille()))
        val v = voiture(w, Vec3(0f, 1.2f, 0f))
        repeat(240) { v.update(DT); w.step(DT) }
        val attendu = 9.81f / (2 * PI.toFloat() * v.suspensionHertz).pow(2)
        val ecart = v.wheels.maxOf { abs(it.compression - attendu) }
        val charge = v.wheels.sumOf { it.load.toDouble() } / (1200 * 9.81) - 1
        "enfoncement ${cm(v.wheels.first().compression)} (${mm(ecart)} d'écart), charge ${pct(charge)}, ${if (v.chassis.isSleeping) "endormie" else "éveillée"}" to
            (ecart <= .002f && abs(charge) <= .02 && v.chassis.isSleeping)
    }

    controle("voiture : freinage contre v²/(2 · 0,9 g)", "distance ≤ 1,2 × l'idéale, arrêt complet") {
        val w = PhysicsWorld(); w.addMesh(TriangleMesh(solMaille(demi = 300f)))
        val v = voiture(w, Vec3(0f, 1.2f, -250f))
        repeat(60) { v.update(DT); w.step(DT) }
        v.throttle = 1f
        repeat(240) { v.update(DT); w.step(DT) }
        val vitesse = v.speed; val z0 = v.chassis.position.z
        v.throttle = 0f; v.brake = 1f
        repeat(300) { v.update(DT); w.step(DT) }
        val d = v.chassis.position.z - z0; val ideal = vitesse * vitesse / (2 * .9f * 9.81f)
        "${f1(vitesse)} m/s : ${f1(d)} m pour ${f1(ideal)} m idéaux" to (d <= 1.2f * ideal && abs(v.speed) < .05f)
    }

    controle("voitures sur terrain bosselé, volant et pédales au hasard (20 s)", "valeurs finies, jamais retournée, roues au sol ≥ 90 % du temps") {
        var retournee = 0; var finies = true; var sol = 0L; var total = 0L
        repeat(cas(2, 6)) {
            val w = PhysicsWorld()
            val a = rnd.f(.2f, .5f); val b = rnd.f(.2f, .5f)
            fun h(x: Float, z: Float) = .25f * sin(a * x) * cos(b * z)
            val pts = ArrayList<Float>(); val demi = 60f; val n = 60; val pas = 2 * demi / n
            for (i in 0 until n) for (j in 0 until n) {
                val x0 = -demi + i * pas; val z0 = -demi + j * pas; val x1 = x0 + pas; val z1 = z0 + pas
                for ((x, z) in listOf(x0 to z0, x0 to z1, x1 to z1, x0 to z0, x1 to z1, x1 to z0)) { pts.add(x); pts.add(h(x, z)); pts.add(z) }
            }
            w.addMesh(TriangleMesh(pts.toFloatArray()))
            val v = voiture(w, Vec3(0f, 1.5f, 0f), Quat.rotationY(rnd.f(0f, 6f)))
            repeat((20f / DT).toInt()) { i ->
                if (i % 45 == 0) { v.throttle = rnd.f(-.5f, 1f); v.steering = rnd.f(-1f, 1f); v.brake = if (rnd.nextInt(6) == 0) 1f else 0f }
                v.update(DT); w.step(DT)
                total += 4; sol += v.wheelsOnGround
                if (abs(v.chassis.position.x) > 50f || abs(v.chassis.position.z) > 50f) { v.chassis.position = Vec3(0f, 1.5f, 0f); v.chassis.velocity = Vec3.ZERO }
            }
            if (v.chassis.rotation.rotate(Vec3.Y).y < .3f) retournee++
            finies = finies && v.chassis.fini()
        }
        "${cas(2, 6)} voitures : $retournee retournée(s), roues au sol ${pct(sol.toDouble() / total)}" to (finies && retournee == 0 && sol >= .9 * total)
    }

    controle("personnage sur une plateforme qui avance, tourne et tangue (chemins au hasard)", "glisse ≤ 3 cm") {
        var pire = 0f
        repeat(cas(3, 8)) {
            val w = PhysicsWorld(); w.addMesh(TriangleMesh(solMaille()))
            val pont = w.add(Box(2.5f, .1f, 2.5f), Vec3(0f, 1.5f, 0f), type = BodyType.KINEMATIC)
            val c = Character(w, Vec3(rnd.f(-1.5f, 1.5f), 1.65f, rnd.f(-1.5f, 1.5f)))
            repeat(30) { w.step(DT); c.update(Vec3.ZERO, 0f, DT) }
            val vx = rnd.f(-2f, 2f); val vz = rnd.f(-2f, 2f); val tour = rnd.f(-.6f, .6f); val houle = rnd.f(0f, .2f)
            fun local() = pont.rotation.conjugate().rotate(c.position - pont.position)
            val depart = local(); var t = 0f
            repeat(300) {
                t += DT
                pont.moveTo(Vec3(vx * t, 1.5f + houle * sin(1.7f * t), vz * t), DT, Quat.rotationY(tour * t) * Quat.axisAngle(Vec3.X, houle * .3f * sin(1.1f * t)))
                w.step(DT); c.update(Vec3.ZERO, 0f, DT)
                pire = max(pire, (local() - depart).length())
            }
        }
        "glisse au plus de ${cm(pire)}" to (pire <= .03f)
    }

    controle("collision continue : balles de 100 à 3000 m/s", "aucune ne traverse dalle de 2 cm, sol maillé, planche mobile (projectiles)") {
        val traverses = ArrayList<String>()
        for (v in floatArrayOf(100f, 300f, 1000f, 3000f)) {
            val d = PhysicsWorld(); d.add(Box(3f, .01f, 3f), Vec3.ZERO, type = BodyType.STATIC)
            val a = d.add(Sphere(.02f), Vec3(0f, 5f, 0f)); a.velocity = Vec3(rnd.f(-5f, 5f), -v, 0f)
            val m = PhysicsWorld(); m.addMesh(TriangleMesh(solMaille()))
            val b = m.add(Sphere(.02f), Vec3(0f, 5f, 0f)); b.velocity = Vec3(rnd.f(-5f, 5f), -v, rnd.f(-5f, 5f))
            val p = PhysicsWorld(gravity = Vec3.ZERO)
            val planche = p.add(Box(.02f, .5f, .5f), Vec3.ZERO, mass = 5f)
            val c = p.add(Sphere(.02f), Vec3(-10f, 0f, 0f), mass = .02f); c.isBullet = true; c.velocity = Vec3(v, 0f, 0f)
            repeat(60) { d.step(DT); m.step(DT); p.step(DT) }
            if (a.position.y < 0f) traverses.add("dalle à ${v.toInt()}")
            if (b.position.y < 0f) traverses.add("sol à ${v.toInt()}")
            if (c.position.x > planche.position.x) traverses.add("planche à ${v.toInt()}")
        }
        (if (traverses.isEmpty()) "aucune traversée" else traverses.joinToString()) to traverses.isEmpty()
    }

    controle("pont suspendu de 10 planches de 5 kg : le marin (70 kg) marche jusqu'au milieu", "charnières ≤ 3 mm, valeurs finies, planches calmes (≤ 0,6 m/s)") {
        // Charnières bout à bout, 5 m de planches entre deux poteaux à 4,8 m, posé en V et laissé pendre (amorti le temps de pendre).
        val w = PhysicsWorld()
        w.addMesh(TriangleMesh(floatArrayOf(-30f, -3f, -30f, -30f, -3f, 30f, 30f, -3f, 30f, -30f, -3f, -30f, 30f, -3f, 30f, 30f, -3f, -30f)))
        w.add(Box(1f, 1f, 1f), Vec3(-3.45f, -1.025f, 0f), type = BodyType.STATIC); w.add(Box(1f, 1f, 1f), Vec3(3.45f, -1.025f, 0f), type = BodyType.STATIC)
        val drop = .7f
        fun at(s: Float) = if (s <= 2.5f) Vec3(-2.4f + s * .96f, -s * drop / 2.5f, 0f) else Vec3(2.4f - (5f - s) * .96f, -(5f - s) * drop / 2.5f, 0f)
        val planches = (0 until 10).map { k -> w.add(Box(.25f, .025f, .4f), at(.25f + .5f * k), Quat.axisAngle(Vec3.Z, atan(if (k < 5) -drop / 2.4f else drop / 2.4f)), mass = 5f) }
        w.addHinge(null, planches[0], at(0f), Vec3.Z)
        for (k in 1 until 10) w.addHinge(planches[k - 1], planches[k], at(.5f * k), Vec3.Z)
        w.addHinge(null, planches[9], at(5f), Vec3.Z)
        for (p in planches) { p.linearDamping = 2f; p.angularDamping = 2f }
        repeat(360) { w.step(DT) }
        val marin = Character(w, feet = Vec3(-3.1f, .03f, 0f))
        var ecart = 0f; var vite = 0f
        for (s in 0 until 600) {
            w.step(DT)
            if (s > 300) { ecart = max(ecart, w.jointList.maxOf { (it.anchorA - it.anchorB).length() }); vite = max(vite, planches.maxOf { it.velocity.length() }) }
            marin.update(if (marin.position.x < 0f) Vec3(1.5f, 0f, 0f) else Vec3.ZERO, 0f, DT)
        }
        val finies = planches.all { it.fini() }
        "charnières ${mm(ecart)} au plus, planches à ${f2(vite)} m/s, le marin en x = ${f2(marin.position.x)}${if (marin.onGround) ", debout" else ", en l'air"}" to
            (finies && ecart <= .003f && vite <= .6f && marin.onGround && abs(marin.position.x) < .1f)
    }

    controle("chaînes de 10 maillons (7 kg) tenant 50 à 2000 kg, puis poussées", "la caisse descend de ≤ 2 mm, maillons ≤ 1 mm au repos et ≤ 5 mm en balançant") {
        var descente = 0f; var repos = 0f; var balance = 0f
        for (charge in floatArrayOf(50f, 200f, 500f, 2000f)) {
            val w = PhysicsWorld()
            val maillons = w.addChain(Vec3(0f, 5f, 0f), Vec3(0f, 3.5f, 0f), 10, radius = .03f)
            val caisse = w.add(Box(.3f, .3f, .3f), Vec3(0f, 3.2f, 0f), mass = charge)
            w.addBall(maillons.last(), caisse, Vec3(0f, 3.5f, 0f))
            repeat(240) { w.step(DT) }
            descente = max(descente, 3.2f - caisse.position.y); repos = max(repos, w.jointList.maxOf { (it.anchorA - it.anchorB).length() })
            caisse.applyImpulse(Vec3(charge * rnd.f(-3f, 3f), 0f, charge * rnd.f(-3f, 3f)))
            repeat(300) { w.step(DT); balance = max(balance, w.jointList.maxOf { (it.anchorA - it.anchorB).length() }) }
            if (!maillons.all { it.fini() }) balance = Float.MAX_VALUE
        }
        "descente ${mm(descente)}, maillons ${mm(repos)} au repos, ${mm(balance)} en balançant" to (descente <= .002f && repos <= .001f && balance <= .005f)
    }

    controle("collision continue sur la forme entière : ballon et caisse de 20 à 1500 m/s contre un mur de 5 cm",
        "aucune traversée (mur boîte et mur maillé, tournés et lancés au hasard)") {
        // Un mur fermé de triangles, 5 cm d'épaisseur, 4 m sur 4, sur le plan x = 0.
        val e = .025f
        val c = floatArrayOf(-e, -2f, -2f, e, -2f, -2f, e, 2f, -2f, -e, 2f, -2f, -e, -2f, 2f, e, -2f, 2f, e, 2f, 2f, -e, 2f, 2f)
        val faces = intArrayOf(0, 3, 2, 1, 4, 5, 6, 7, 0, 4, 7, 3, 1, 2, 6, 5, 0, 1, 5, 4, 3, 7, 6, 2)
        val murMaille = FloatArray(6 * 18).also { t -> var k = 0; for (f in 0 until 6) for (s in intArrayOf(0, 1, 2, 0, 2, 3)) for (a in 0..2) t[k++] = c[3 * faces[4 * f + s] + a] }
        val traverses = ArrayList<String>(); var essais = 0
        for ((nom, forme) in listOf("ballon" to Sphere(.22f), "caisse" to Box(.25f, .25f, .25f))) for (maille in listOf(false, true))
            for (v in floatArrayOf(20f, 60f, 150f, 400f, 1500f)) repeat(cas(3, 10)) {
                val w = PhysicsWorld(gravity = Vec3.ZERO)
                if (maille) w.addMesh(TriangleMesh(murMaille)) else w.add(Box(e, 2f, 2f), Vec3.ZERO, type = BodyType.STATIC)
                val q = Quat.axisAngle(Vec3(rnd.f(-1f, 1f), rnd.f(-1f, 1f), rnd.f(-1f, 1f) + 3f).normalized(), rnd.f(0f, 6f))
                val b = w.add(forme, Vec3(-3f, rnd.f(-1f, 1f), rnd.f(-1f, 1f)), q, density = 100f)
                b.velocity = Vec3(1f, rnd.f(-.3f, .3f), rnd.f(-.3f, .3f)).normalized() * v
                b.angularVelocity = Vec3(rnd.f(-4f, 4f), rnd.f(-4f, 4f), rnd.f(-4f, 4f))
                repeat(30) { w.step(DT) }
                essais++
                if (b.position.x > e && abs(b.position.y) < 2f && abs(b.position.z) < 2f) traverses.add("$nom ${if (maille) "maillé" else "boîte"} ${v.toInt()} m/s")
            }
        (if (traverses.isEmpty()) "aucune traversée sur $essais lancers" else "${traverses.size} sur $essais : " + traverses.take(4).joinToString()) to traverses.isEmpty()
    }
}
