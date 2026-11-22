package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import java.util.Random
import kotlin.test.*

/** The query grid gives exactly what a body-by-body search gives: same body, distance, point and normal. */
class QueryGridTest {
    private fun world(): PhysicsWorld {
        val rnd = Random(7)
        val w = PhysicsWorld()
        w.addMesh(TriangleMesh(floatArrayOf(-60f, 0f, -60f, -60f, 0f, 60f, 60f, 0f, 60f, -60f, 0f, -60f, 60f, 0f, 60f, 60f, 0f, -60f)))
        fun f(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        for (i in 0 until 600) {
            val p = Vec3(f(-25f, 25f), f(0f, 12f), f(-25f, 25f))
            val q = Quat.axisAngle(Vec3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f) + 2f).normalized(), f(0f, 6f))
            when (i % 3) {
                0 -> w.add(Box(f(.1f, .8f), f(.1f, .8f), f(.1f, .8f)), p, q)
                1 -> w.add(Sphere(f(.1f, .7f)), p)
                else -> w.add(Capsule(f(.1f, .4f), f(.1f, .6f)), p, q)
            }
        }
        w.add(Box(20f, .5f, .5f), Vec3(0f, 3f, 0f), type = BodyType.STATIC)     // a long wall, in many cells
        return w
    }

    @Test fun rayonsEtBalayagesIdentiques() {
        val w = world(); val rnd = Random(11)
        fun f(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        var hits = 0
        repeat(4000) { k ->
            val o = Vec3(f(-35f, 35f), f(-2f, 20f), f(-35f, 35f))
            val d = Vec3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)).let { if (it.lengthSquared() < 1e-4f) Vec3(0f, -1f, 0f) else it }
            val len = if (k % 4 == 0) f(50f, 120f) else f(.5f, 15f)
            val radius = if (k % 3 == 0) 0f else f(.01f, .7f)
            val filter: ((Body) -> Boolean)? = if (k % 5 == 0) { b -> b.id % 2 == 0 } else null
            w.queryGridEnabled = true
            val a = w.raycast(o, d, len, radius, filter = filter)
            w.queryGridEnabled = false
            val b = w.raycast(o, d, len, radius, filter = filter)
            assertEquals(b?.body, a?.body, "rayon $k")
            assertEquals(b?.distance, a?.distance, "rayon $k")
            assertEquals(b?.point, a?.point, "rayon $k"); assertEquals(b?.normal, a?.normal, "rayon $k")
            if (a?.body != null) hits++
        }
        assertTrue(hits > 200, "$hits rayons ont touché un corps")
    }

    @Test fun contactsDUneFormeIdentiques() {
        val w = world(); val rnd = Random(13)
        fun f(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        var touched = 0
        repeat(3000) { k ->
            val shape = when (k % 3) { 0 -> Sphere(f(.2f, 1.2f)); 1 -> Capsule(f(.2f, .5f), f(.2f, 1f)); else -> Box(f(.2f, 1f), f(.2f, 1f), f(.2f, 1f)) }
            val pos = Vec3(f(-26f, 26f), f(0f, 12f), f(-26f, 26f)); val margin = f(0f, .3f)
            val (a, b) = listOf(true, false).map { on ->
                w.queryGridEnabled = on
                w.overlaps(shape, pos, margin = margin).map { "${it.body?.id} ${it.point} ${it.normal} ${it.separation}" }
            }
            assertEquals(b, a, "forme $k")
            if (a.isNotEmpty()) touched++
        }
        assertTrue(touched > 300, "$touched formes touchaient quelque chose")
    }
}
