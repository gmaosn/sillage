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
}
