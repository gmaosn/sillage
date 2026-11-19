package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.*
import kotlin.test.*

class CharacterTest {
    private val dt = 1f / 60f

    private fun world(): PhysicsWorld = PhysicsWorld().also {
        it.addMesh(TriangleMesh(floatArrayOf(-30f, 0f, -30f, -30f, 0f, 30f, 30f, 0f, 30f, -30f, 0f, -30f, 30f, 0f, 30f, 30f, 0f, -30f)))
    }

    /** Runs [seconds] of walking at [walk] (m/s) with the world stepping too. */
    private fun run(w: PhysicsWorld, c: Character, seconds: Float, walk: Vec3 = Vec3.ZERO, jumpAt: Int = -1, each: () -> Unit = {}) {
        repeat((seconds / dt).roundToInt()) { i ->
            c.update(walk, if (i == jumpAt) 8f else 0f, dt); w.step(dt); each()
        }
    }

    /** Comme la scène : le monde avance d'abord (la plateforme bouge), puis le personnage. */
    @Test fun unPontQuiAvanceTourneEtTangueEmporteLePersonnage() {
        val w = world()
        val deck = w.add(Box(2.5f, .1f, 2.5f), Vec3(0f, 1f, 0f), type = BodyType.KINEMATIC)
        val c = Character(w, Vec3(1.2f, 1.15f, .6f))
        repeat(30) { w.step(dt); c.update(Vec3.ZERO, 0f, dt) }
        assertTrue(c.onGround && c.groundBody == deck)
        fun local() = deck.rotation.conjugate().rotate(c.position - deck.position)
        val start = local()
        var t = 0f; var worst = 0f; var turned = 0f; var steps = 0f
        repeat(240) {
            t += dt
            deck.moveTo(Vec3(1.5f * t, 1f + .15f * sin(2f * t), 0f), dt, Quat.rotationY(.4f * t))
            w.step(dt); c.update(Vec3.ZERO, 0f, dt)
            turned += c.platformTurn
            worst = max(worst, (local() - start).length())
            steps = max(steps, c.walkVelocity.length())
        }
        assertTrue(worst < .03f, "le personnage glisse sur le pont de ${worst * 100} cm")
        assertEquals(.4f * 4f, turned, .02f, "il tourne avec le pont")
        assertTrue(steps < .05f, "debout, il ne marche pas : ${c.walkVelocity}")
        assertTrue(c.onGround)
        // Il saute du pont qui file en ligne droite : en l'air il garde sa vitesse, et retombe à sa place.
        val turn = deck.rotation
        repeat(10) { t += dt; deck.moveTo(Vec3(1.5f * t, 1f, 0f), dt, turn); w.step(dt); c.update(Vec3.ZERO, 0f, dt) }
        val before = local()
        var jumped = false; var airborne = 0
        repeat(120) { i ->
            t += dt; deck.moveTo(Vec3(1.5f * t, 1f, 0f), dt, turn); w.step(dt)
            c.update(Vec3.ZERO, if (i == 0) 6f else 0f, dt)
            if (!c.onGround) { jumped = true; airborne++; assertEquals(1.5f, c.velocity.x, .05f, "en l'air, la vitesse du pont") }
        }
        assertTrue(jumped && airborne > 20, "le saut a eu lieu ($airborne images en l'air)")
        assertTrue(c.onGround && c.groundBody == deck, "il retombe sur le pont")
        assertTrue((local() - before).length() < .05f, "et à sa place : écart ${(local() - before).length() * 100} cm")
    }

    @Test fun tombeEtSePose() {
        val w = world(); val c = Character(w, Vec3(0f, 3f, 0f))
        run(w, c, 2f)
        assertEquals(0f, c.position.y, .02f)
        assertTrue(c.onGround)
    }

    @Test fun hauteurDuSaut() {
        val w = world(); val c = Character(w, Vec3(0f, 0f, 0f))
        run(w, c, .5f)
        var top = 0f
        run(w, c, 1.5f, jumpAt = 0) { top = max(top, c.position.y) }
        assertEquals(64f / 44f, top, .05f, "v² / 2g avec v = 8 et g = 22")
        assertTrue(c.onGround)
    }

    @Test fun unMurArreteEtOnGlisseLeLong() {
        val w = world(); w.add(Box(.5f, 1.5f, 20f), Vec3(2.5f, 1.5f, 0f), type = BodyType.STATIC)
        val c = Character(w, Vec3(0f, 0f, 0f))
        run(w, c, 2f, Vec3(3f, 0f, 3f).normalized() * 4f)
        assertTrue(c.position.x <= 2f - c.radius + .02f, "traverse le mur : ${c.position}")
        assertTrue(c.position.z > 4f, "ne glisse pas le long : ${c.position}")
    }

    @Test fun uneMarcheBasseSeMonteUneHauteNon() {
        fun after(step: Float): Vec3 {
            val w = world(); w.add(Box(2f, step / 2, 3f), Vec3(3f, step / 2, 0f), type = BodyType.STATIC)
            val c = Character(w, Vec3(0f, 0f, 0f)); run(w, c, .3f)
            run(w, c, 1.2f, Vec3(3f, 0f, 0f)); return c.position
        }
        val low = after(.3f); val high = after(.6f)
        assertTrue(low.x > 2f && abs(low.y - .3f) < .03f, "marche de 30 cm non montée : $low")
        assertTrue(high.x < 1f + .02f - .28f + .3f && high.y < .05f, "marche de 60 cm montée : $high")
    }

    private fun slopeWorld(degrees: Float): Pair<PhysicsWorld, Quat> {
        val w = PhysicsWorld()
        val q = Quat.axisAngle(Vec3.Z, Math.toRadians(degrees.toDouble()).toFloat())
        val c = listOf(Vec3(0f, 0f, -10f), Vec3(0f, 0f, 10f), Vec3(20f, 0f, 10f), Vec3(20f, 0f, -10f)).map { q.rotate(it) }
        w.addMesh(TriangleMesh(floatArrayOf(-20f, 0f, -10f, -20f, 0f, 10f, 0f, 0f, 10f, -20f, 0f, -10f, 0f, 0f, 10f, 0f, 0f, -10f,
            c[0].x, c[0].y, c[0].z, c[1].x, c[1].y, c[1].z, c[2].x, c[2].y, c[2].z, c[0].x, c[0].y, c[0].z, c[2].x, c[2].y, c[2].z, c[3].x, c[3].y, c[3].z)))
        return w to q
    }

    @Test fun pentesDoucesEtRaides() {
        val (w1, _) = slopeWorld(30f)
        val c1 = Character(w1, Vec3(-2f, 0f, 0f)); run(w1, c1, .3f)
        run(w1, c1, 2.5f, Vec3(3f, 0f, 0f))
        assertTrue(c1.position.y > 2f && c1.onGround, "ne monte pas une pente de 30° : ${c1.position}")
        val (w2, q2) = slopeWorld(60f)
        val c2 = Character(w2, q2.rotate(Vec3(3f, 0f, 0f)).plus(0f, .5f, 0f))
        val y0 = c2.position.y
        run(w2, c2, 2f)
        assertTrue(c2.position.y < y0 - 1f, "tient sur une pente de 60° : ${c2.position}")
    }

    @Test fun onResteColleAuSolEnDescendant() {
        val (w, q) = slopeWorld(20f)
        val c = Character(w, q.rotate(Vec3(8f, 0f, 0f))); run(w, c, .3f)
        var airborne = 0
        run(w, c, 1.5f, Vec3(-4f, 0f, 0f)) { if (!c.onGround) airborne++ }
        assertTrue(airborne <= 2, "décolle en descendant : $airborne images en l'air")
    }

    @Test fun onMonteSurUneCaisseEtOnPeseDessus() {
        val w = world()
        val crate = w.add(Box(.5f, .4f, .5f), Vec3(2f, .4f, 0f), mass = 15f)
        val c = Character(w, Vec3(0f, 0f, 0f)); run(w, c, .3f)
        run(w, c, .75f, Vec3(2.5f, 0f, 0f), jumpAt = 5)
        run(w, c, 1f)
        assertTrue(c.onGround && c.groundBody == crate, "pas sur la caisse : ${c.position}, sol=${c.groundBody}")
        assertEquals(.8f, c.position.y, .03f)
    }

    @Test fun pousserSelonLaMasse() {
        fun push(mass: Float, shape: Shape): Pair<Float, Float> {
            val w = world()
            val b = w.add(shape, Vec3(1.2f, if (shape is Box) .4f else .3f, 0f), mass = mass)
            val c = Character(w, Vec3(0f, 0f, 0f)); run(w, c, .3f)
            val x0 = b.position.x; var vmax = 0f
            run(w, c, 2f, Vec3(3f, 0f, 0f)) { vmax = max(vmax, b.velocity.length()) }
            return (b.position.x - x0) to vmax
        }
        val (ball, vBall) = push(.6f, Sphere(.3f))
        val (crate, vCrate) = push(50f, Box(.4f, .4f, .4f))
        val (heavy, _) = push(140f, Box(.4f, .4f, .4f))
        println("POUSSEE ballon : ${"%.2f".format(ball)} m (vmax ${"%.2f".format(vBall)}), caisse de 50 kg : ${"%.2f".format(crate)} m (vmax ${"%.2f".format(vCrate)}), caisse de 140 kg : ${"%.2f".format(heavy)} m")
        assertTrue(vBall < 3f * 1.15f, "le ballon gicle : $vBall m/s")
        assertTrue(ball > crate && crate > .5f, "le ballon doit aller plus loin que la caisse, qui doit avancer")
        assertTrue(vCrate < 3f, "la caisse de 50 kg suit le marin trop facilement")
        assertTrue(heavy < .05f, "une caisse de 140 kg (frottement 824 N > 400 N) ne devrait pas bouger : $heavy m")
    }

    /** Triangles of an axis-aligned box from [lo] to [hi], facing out (as a level would model a pier). */
    private fun boxTriangles(lo: Vec3, hi: Vec3): FloatArray {
        val out = ArrayList<Float>(); val c = (lo + hi) / 2f
        fun v(x: Int, y: Int, z: Int) = Vec3(if (x == 0) lo.x else hi.x, if (y == 0) lo.y else hi.y, if (z == 0) lo.z else hi.z)
        val faces = listOf(listOf(v(0,1,0), v(0,1,1), v(1,1,1), v(1,1,0)), listOf(v(0,0,0), v(1,0,0), v(1,0,1), v(0,0,1)),
            listOf(v(0,0,0), v(0,1,0), v(1,1,0), v(1,0,0)), listOf(v(0,0,1), v(1,0,1), v(1,1,1), v(0,1,1)),
            listOf(v(0,0,0), v(0,0,1), v(0,1,1), v(0,1,0)), listOf(v(1,0,0), v(1,1,0), v(1,1,1), v(1,0,1)))
        for (f in faces) {
            val centre = (f[0] + f[1] + f[2] + f[3]) / 4f
            for ((a, b, cc) in listOf(Triple(f[0], f[1], f[2]), Triple(f[0], f[2], f[3]))) {
                val flip = (((b - a) cross (cc - a)) dot (centre - c)) < 0f
                for (p in if (flip) listOf(a, cc, b) else listOf(a, b, cc)) { out.add(p.x); out.add(p.y); out.add(p.z) }
            }
        }
        return out.toFloatArray()
    }

    /** Le défaut trouvé sur le ponton de la démo : un plancher maillé de 27 cm, abordé par le côté. */
    @Test fun onMonteSurUnPontonMailleParLeCoteEtOnEnDescend() {
        val w = world()
        w.addMesh(TriangleMesh(boxTriangles(Vec3(1.5f, -.2f, -3f), Vec3(4f, .27f, 3f))))
        val c = Character(w, Vec3(0f, 0f, .4f)); run(w, c, .3f)
        run(w, c, 1.2f, Vec3(3f, 0f, 0f))
        assertEquals(.27f, c.position.y, .01f, "pas monté sur le ponton : ${c.position}")
        assertTrue(c.onGround && c.position.x > 2.5f)
        // Puis on avance jusqu'au bord opposé et on en descend.
        run(w, c, 1.2f, Vec3(3f, 0f, 0f))
        assertEquals(0f, c.position.y, .01f, "redescendu au sol : ${c.position}")
    }

    /** Second défaut du ponton : en retombant sur un plancher maillé fermé, ses faces arrière éjectaient le marin. */
    @Test fun retomberSurUnPlancherFermeNEjectePas() {
        val w = world()
        w.addMesh(TriangleMesh(boxTriangles(Vec3(-2f, .91f, -3f), Vec3(2f, 1.09f, 3f))))
        val c = Character(w, Vec3(0f, 2.5f, 0f))
        var ecart = 0f; var avant = c.position
        // Chute de 1,4 m, puis un saut (0,73 s en l'air) en marchant à 1 m/s : on reste sur le plancher (z < 3).
        run(w, c, 2.2f, Vec3(0f, 0f, 1f), jumpAt = 50) { ecart = maxOf(ecart, (c.position - avant).length()); avant = c.position }
        assertTrue(ecart < .25f, "saut de $ecart m en une image")
        assertEquals(1.09f, c.position.y, .01f); assertTrue(c.onGround)
    }

    @Test fun lePiedReposeSurLArretDUnPlancherSansFlotter() {
        val w = world()
        w.addMesh(TriangleMesh(boxTriangles(Vec3(1.5f, -.2f, -3f), Vec3(4f, .27f, 3f))))
        // Le centre 10 cm au-delà du bord : la sphère du bas repose sur l'arête, un peu plus bas que le plancher.
        val c = Character(w, Vec3(1.4f, .5f, 0f)); run(w, c, .6f)
        val r = c.radius
        assertEquals(.27f - (r - sqrt(r * r - .1f * .1f)), c.position.y, .015f, "posé sur l'arête : ${c.position}")
    }

    /** Trouvé par le contrôle qualité : apparu dans un bloc, le marin en était éjecté de 2,85 m vers le bas, à travers le sol. */
    @Test fun apparaitreDansUnMurNEjectePasATraversLeSol() {
        val w = world()
        w.add(Box(1.5f, 1.25f, 1.5f), Vec3(0f, 1.25f, 0f), type = BodyType.STATIC)
        val c = Character(w, Vec3(0f, .05f, 0f))
        var bas = 9f; var bond = 0f
        run(w, c, 3f) { bas = minOf(bas, c.position.y) }
        var avant = c.position
        run(w, c, 1f) { bond = maxOf(bond, (c.position - avant).length()); avant = c.position }
        assertTrue(bas > -.02f, "passé sous le sol : $bas")
        assertTrue(bond <= .7f, "bond de $bond m en une image")
    }

    /** Soixante blocs au hasard autour du marin à son apparition : il en sort, jamais par le dessous du sol. */
    @Test fun apparaitreDansDesBlocsAuHasard() {
        val rnd = java.util.Random(7)
        fun f(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        repeat(60) { essai ->
            val w = world()
            val h = f(.5f, 3f)
            w.add(Box(f(.4f, 2f), h / 2, f(.4f, 2f)), Vec3(0f, h / 2, 0f), sillage.math.Quat.rotationY(f(0f, 3f)), type = BodyType.STATIC)
            val c = Character(w, Vec3(f(-.3f, .3f), .02f, f(-.3f, .3f)))
            var bas = 0f
            repeat(180) { w.step(dt); c.update(Vec3.ZERO, 0f, dt); bas = minOf(bas, c.position.y) }
            assertTrue(bas > -.02f, "essai $essai : passé sous le sol ($bas)")
        }
    }
}
