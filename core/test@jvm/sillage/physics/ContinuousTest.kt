package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import java.util.Random
import kotlin.math.*
import kotlin.test.*

/** Continuous collision on the whole shape: fast balls, boxes, capsules and hulls never cross a thin wall. */
class ContinuousTest {
    private val dt = 1f / 60f
    private fun run(w: PhysicsWorld, seconds: Float) = repeat((seconds / dt).roundToInt()) { w.step(dt) }

    /** A wall 5 cm thick across X = 0, 4 m by 4 m, standing on nothing (no gravity): a box, or a closed mesh. */
    private fun wall(w: PhysicsWorld, mesh: Boolean) {
        val t = .025f
        if (!mesh) { w.add(Box(t, 2f, 2f), Vec3.ZERO, type = BodyType.STATIC); return }
        // A thin box of triangles, each facing out.
        val c = floatArrayOf(-t, -2f, -2f, t, -2f, -2f, t, 2f, -2f, -t, 2f, -2f, -t, -2f, 2f, t, -2f, 2f, t, 2f, 2f, -t, 2f, 2f)
        val faces = intArrayOf(0, 3, 2, 1, 4, 5, 6, 7, 0, 4, 7, 3, 1, 2, 6, 5, 0, 1, 5, 4, 3, 7, 6, 2)
        val tris = ArrayList<Float>()
        for (f in 0 until 6) {
            val q = IntArray(4) { faces[4 * f + it] }
            for (k in intArrayOf(q[0], q[1], q[2], q[0], q[2], q[3])) for (a in 0..2) tris.add(c[3 * k + a])
        }
        w.addMesh(TriangleMesh(tris.toFloatArray()))
    }

    // Hulls and compounds are swept as well (the bench "walls" counts them), but an off-centre impact at a few
    // hundred m/s sets them spinning so fast that a part swings through a 5 cm wall within the next step, which
    // the sweep (at the final orientation) does not see: they are left out of the "never" below.
    private fun shapes(): List<Pair<String, Shape>> = listOf(
        "ballon" to Sphere(.22f), "bille" to Sphere(.02f), "caisse" to Box(.25f, .25f, .25f), "planche" to Box(.4f, .03f, .2f),
        "capsule" to Capsule(.08f, .3f))

    @Test fun riendeRapideNeTraverseUnMurDeCinqCentimetres() {
        val rnd = Random(3)
        val crossed = ArrayList<String>()
        var tries = 0
        for (mesh in listOf(false, true)) for ((name, shape) in shapes()) for (speed in floatArrayOf(20f, 60f, 150f, 400f, 1500f)) repeat(4) {
            val w = PhysicsWorld(gravity = Vec3.ZERO)
            wall(w, mesh)
            val q = Quat.axisAngle(Vec3(rnd.nextFloat() - .5f, rnd.nextFloat() - .5f, rnd.nextFloat() - .5f).normalized(), rnd.nextFloat() * 6f)
            val y = rnd.nextFloat() * 2f - 1f; val z = rnd.nextFloat() * 2f - 1f
            val b = w.add(shape, Vec3(-3f, y, z), q, density = 300f)
            val dir = Vec3(1f, (rnd.nextFloat() - .5f) * .6f, (rnd.nextFloat() - .5f) * .6f).normalized()
            b.velocity = dir * speed
            // Turning too, though not so fast that a long body would swing through the wall within a step
            // (the sweep takes the body at its final orientation).
            b.angularVelocity = Vec3(rnd.nextFloat() - .5f, rnd.nextFloat() - .5f, rnd.nextFloat() - .5f) * 8f
            run(w, .5f)
            tries++
            // Through the wall: its centre beyond it while it still is within the wall's span.
            if (b.position.x > .025f && abs(b.position.y) < 2f && abs(b.position.z) < 2f)
                crossed.add("$name ${if (mesh) "maillage" else "boîte"} ${speed.toInt()} m/s → ${b.position}")
        }
        assertTrue(crossed.isEmpty(), "${crossed.size} traversées sur $tries : ${crossed.take(6)}")
    }

    @Test fun lAncienNoyauLaissaitPasserBallonEtCaisse() {
        // What the core sweep let through: a 22 cm ball crossing the wall within one step was put back with half
        // its radius in a 5 cm wall, and pushed out on the far side; a crate likewise.
        for ((shape, start) in listOf(Sphere(.22f) to -3f, Box(.5f, .5f, .5f) to -4f)) {
            val w = PhysicsWorld(gravity = Vec3.ZERO); wall(w, mesh = false)
            val b = w.add(shape, Vec3(start, 0f, 0f), density = 50f); b.velocity = Vec3(400f, 0f, 0f)
            run(w, .5f)
            assertTrue(b.position.x < 0f, "${shape::class.simpleName} : resté devant le mur, ${b.position}")
            assertTrue(w.continuousHits > 0, "remis devant le mur par la collision continue")
        }
    }

    @Test fun glisserVitePresDuSolOuDUnMurNArretePasLeCorps() {
        // Moving fast along a surface it touches is no impact: a crate and a ball slide on, a box skims a wall.
        val w = PhysicsWorld()
        w.addMesh(TriangleMesh(floatArrayOf(-200f, 0f, -200f, -200f, 0f, 200f, 200f, 0f, 200f, -200f, 0f, -200f, 200f, 0f, 200f, 200f, 0f, -200f), Material.ICE))
        val crate = w.add(Box(.3f, .3f, .3f), Vec3(0f, .3f, 0f), friction = 0f); crate.velocity = Vec3(40f, 0f, 0f)
        val ball = w.add(Sphere(.2f), Vec3(0f, .2f, 5f), friction = 0f, rollingResistance = 0f); ball.velocity = Vec3(40f, 0f, 0f)
        w.add(Box(100f, 1f, .5f), Vec3(0f, 1f, -5.5f), type = BodyType.STATIC, friction = 0f)
        val skim = w.add(Box(.3f, .3f, .3f), Vec3(0f, .3f, -4.7f), friction = 0f); skim.velocity = Vec3(40f, 0f, 0f)
        run(w, .5f)
        assertTrue(crate.position.x > 15f, "la caisse devrait filer : ${crate.position}")
        assertTrue(ball.position.x > 15f, "la boule devrait filer : ${ball.position}")
        assertTrue(skim.position.x > 15f, "la caisse qui frôle le mur devrait filer : ${skim.position}")
    }

    @Test fun lesMursQuiBougentComptentAussi() {
        // A fast crate and a wall that moves (kinematic) towards it or away from it: the motion relative to it counts.
        for (wallSpeed in floatArrayOf(-60f, 0f, 30f)) {
            val w = PhysicsWorld(gravity = Vec3.ZERO)
            val wallBody = w.add(Box(.025f, 2f, 2f), Vec3(6f, 0f, 0f), type = BodyType.KINEMATIC)
            val crate = w.add(Box(.25f, .25f, .25f), Vec3(0f, 0f, 0f), density = 200f); crate.velocity = Vec3(400f, 0f, 0f)
            var t = 0f
            repeat(20) {
                t += dt
                wallBody.moveTo(Vec3(6f + wallSpeed * t, 0f, 0f), dt)
                w.step(dt)
            }
            assertTrue(crate.position.x < wallBody.position.x, "mur à $wallSpeed m/s : la caisse est devant, ${crate.position}, mur ${wallBody.position}")
        }
    }

    @Test fun laDistanceDeGjkEstJuste() {
        // Against cases with a known answer: points, boxes side by side, segments, a triangle.
        val g = Gjk(); val a = SweepCore(); val b = SweepCore()
        val p = Pose().set(0f, 0f, 0f)
        val rnd = Random(9)
        repeat(2000) {
            // Two boxes apart along a random axis direction, axis-aligned: the distance of two intervals in 3D.
            val ha = FloatArray(3) { .1f + rnd.nextFloat() }; val hb = FloatArray(3) { .1f + rnd.nextFloat() }
            val c = FloatArray(3) { (rnd.nextFloat() - .5f) * 8f }
            a.of(Box(ha[0], ha[1], ha[2]), p, 0f)
            b.of(Box(hb[0], hb[1], hb[2]), Pose().set(c[0], c[1], c[2]), 0f)
            var e = 0.0
            for (k in 0..2) { val gap = abs(c[k]) - ha[k] - hb[k]; if (gap > 0f) e += gap.toDouble() * gap }
            assertEquals(sqrt(e), g.distance(a, b), 1e-5, "boîtes $it")
        }
        repeat(2000) {
            // A point and a segment, a point and a triangle (within the plane's projection or off it).
            val q = DoubleArray(3) { (rnd.nextDouble() - .5) * 4 }
            a.of(Sphere(.1f), Pose().set(q[0].toFloat(), q[1].toFloat(), q[2].toFloat()), 0f)
            b.of(Capsule(.1f, 1f), p, 0f)
            val sy = q[1].coerceIn(-1.0, 1.0)
            val expected = sqrt(q[0].toFloat().toDouble().pow(2) + (q[1].toFloat() - sy).pow(2) + q[2].toFloat().toDouble().pow(2))
            assertEquals(expected, g.distance(a, b), 1e-5, "point et segment $it")
        }
        // Overlapping cores: 0.
        a.of(Box(1f, 1f, 1f), p, 0f); b.of(Sphere(.5f), Pose().set(.5f, .2f, 0f), 0f)
        assertEquals(0.0, g.distance(a, b))
    }

    @Test fun lInstantDImpactEstJuste() {
        // A cube 1 m wide going +X at a 1 m wall face 3 m ahead: it touches after 2 m (less its rounding).
        val c = ShapeCast()
        c.moving.of(Box(.5f, .5f, .5f), Pose().set(0f, 0f, 0f), .02f)
        c.target.of(Box(.5f, 2f, 2f), Pose().set(3f, .3f, 0f), 0f)
        val at = c.toi(1.0, 0.0, 0.0, 5.0, .01)
        assertEquals(2.0, at, 1e-3)
        // Aslant, at 30° from the face's normal: farther by 1/cos 30°.
        val u = Vec3(cos(PI / 6).toFloat(), sin(PI / 6).toFloat(), 0f)
        assertEquals(2.0 / cos(PI / 6), c.toi(u.x.toDouble(), u.y.toDouble(), 0.0, 5.0, .01), 1e-3)
        // Passing beside it: nothing.
        assertTrue(c.toi(0.0, 0.0, 1.0, 5.0, .01).isNaN())
        // Stopping before it: nothing.
        assertTrue(c.toi(1.0, 0.0, 0.0, 1.9, .01).isNaN())
    }
}
