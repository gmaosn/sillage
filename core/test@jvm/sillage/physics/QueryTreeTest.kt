package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import java.util.Random
import kotlin.test.*

/** The query trees give exactly what a body-by-body search gives: same body, distance, point and normal. */
class QueryTreeTest {
    private fun world(): PhysicsWorld {
        val rnd = Random(7)
        val w = PhysicsWorld()
        w.addMesh(TriangleMesh(floatArrayOf(-60f, 0f, -60f, -60f, 0f, 60f, 60f, 0f, 60f, -60f, 0f, -60f, 60f, 0f, 60f, 60f, 0f, -60f)))
        fun f(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        for (i in 0 until 600) {
            val p = Vec3(f(-25f, 25f), f(0f, 12f), f(-25f, 25f))
            val q = Quat.axisAngle(Vec3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f) + 2f).normalized(), f(0f, 6f))
            val type = if (i % 7 == 0) BodyType.STATIC else if (i % 11 == 0) BodyType.KINEMATIC else BodyType.DYNAMIC
            when (i % 3) {
                0 -> w.add(Box(f(.1f, .8f), f(.1f, .8f), f(.1f, .8f)), p, q, type)
                1 -> w.add(Sphere(f(.1f, .7f)), p, type = type)
                else -> w.add(Capsule(f(.1f, .4f), f(.1f, .6f)), p, q, type)
            }
        }
        w.add(Box(20f, .5f, .5f), Vec3(0f, 3f, 0f), type = BodyType.STATIC)     // a long wall
        w.add(ConvexHull(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)), Vec3(4f, 6f, -3f))
        return w
    }

    private fun sameRays(w: PhysicsWorld, seed: Long, count: Int, maxRadius: Float): Int {
        val rnd = Random(seed)
        fun f(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        var hits = 0
        repeat(count) { k ->
            val o = Vec3(f(-35f, 35f), f(-2f, 20f), f(-35f, 35f))
            val d = Vec3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)).let { if (it.lengthSquared() < 1e-4f) Vec3(0f, -1f, 0f) else it }
            val len = if (k % 4 == 0) f(50f, 120f) else f(.5f, 15f)
            val radius = if (k % 3 == 0) 0f else f(.01f, maxRadius)
            val filter: ((Body) -> Boolean)? = if (k % 5 == 0) { b -> b.id % 2 == 0 } else null
            w.queryTreesEnabled = true
            val a = w.raycast(o, d, len, radius, filter = filter)
            w.queryTreesEnabled = false
            val b = w.raycast(o, d, len, radius, filter = filter)
            w.queryTreesEnabled = true
            assertEquals(b?.body, a?.body, "rayon $k")
            assertEquals(b?.distance, a?.distance, "rayon $k")
            assertEquals(b?.point, a?.point, "rayon $k"); assertEquals(b?.normal, a?.normal, "rayon $k")
            if (a?.body != null) hits++
        }
        return hits
    }

    private fun sameShapes(w: PhysicsWorld, seed: Long, count: Int): Int {
        val rnd = Random(seed)
        fun f(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        var touched = 0
        repeat(count) { k ->
            val shape = when (k % 3) { 0 -> Sphere(f(.2f, 1.2f)); 1 -> Capsule(f(.2f, .5f), f(.2f, 1f)); else -> Box(f(.2f, 1f), f(.2f, 1f), f(.2f, 1f)) }
            val pos = Vec3(f(-26f, 26f), f(0f, 12f), f(-26f, 26f)); val margin = f(0f, .3f)
            val (a, b) = listOf(true, false).map { on ->
                w.queryTreesEnabled = on
                w.overlaps(shape, pos, margin = margin).map { "${it.body?.id} ${it.point} ${it.normal} ${it.separation}" }
            }
            w.queryTreesEnabled = true
            assertEquals(b, a, "forme $k")
            if (a.isNotEmpty()) touched++
        }
        return touched
    }

    @Test fun rayonsEtBalayagesIdentiques() {
        val hits = sameRays(world(), 11, 4000, .7f)
        assertTrue(hits > 200, "$hits rayons ont touché un corps")
    }

    @Test fun largesBalayagesIdentiques() {
        // Wider than the old grid's pad (0.5 m): the trees serve every radius.
        val hits = sameRays(world(), 17, 2000, 3f)
        assertTrue(hits > 300, "$hits balayages ont touché un corps")
    }

    @Test fun contactsDUneFormeIdentiques() {
        val touched = sameShapes(world(), 13, 3000)
        assertTrue(touched > 300, "$touched formes touchaient quelque chose")
    }

    @Test fun lesArbresSuiventLeMonde() {
        // Between queries the world steps, bodies are moved by hand, removed and added: the trees follow.
        val w = world(); val rnd = Random(23)
        val bodies = (0 until w.bodyCount).map { w.body(it) }
        repeat(12) { round ->
            repeat(20) { w.step(1f / 60f) }
            for (k in 0 until 15) {
                val b = bodies[rnd.nextInt(bodies.size)]
                if (b.isRemoved) continue
                when (rnd.nextInt(4)) {
                    0 -> b.position = Vec3(rnd.nextFloat() * 40f - 20f, rnd.nextFloat() * 8f, rnd.nextFloat() * 40f - 20f)
                    1 -> b.rotation = Quat.axisAngle(Vec3.Y, rnd.nextFloat() * 6f)
                    2 -> w.remove(b)
                    else -> w.add(Sphere(.3f + rnd.nextFloat()), Vec3(rnd.nextFloat() * 40f - 20f, 4f, rnd.nextFloat() * 40f - 20f),
                        type = if (rnd.nextBoolean()) BodyType.STATIC else BodyType.DYNAMIC)
                }
            }
            sameRays(w, 100L + round, 300, 2f)
            sameShapes(w, 200L + round, 200)
            w.queryTrees.still.validate(); w.queryTrees.moving.validate()
        }
        assertTrue(w.queryTrees.moves > 0, "des feuilles ont été déplacées")
    }

    @Test fun arbreEquilibreEtJuste() {
        // Many inserts, moves and removals: the tree stays valid and shallow.
        val t = BoxTree(); val rnd = Random(3)
        val leaves = IntArray(5000) { k ->
            val x = rnd.nextFloat() * 100f; val y = rnd.nextFloat() * 10f; val z = rnd.nextFloat() * 100f
            t.insert(k, x, y, z, x + 1f, y + 1f, z + 1f, BoxTree.MARGIN)
        }
        repeat(20000) {
            val k = rnd.nextInt(leaves.size)
            val x = rnd.nextFloat() * 100f; val y = rnd.nextFloat() * 10f; val z = rnd.nextFloat() * 100f
            t.move(leaves[k], x, y, z, x + .5f, y + .5f, z + .5f, BoxTree.MARGIN)
        }
        t.validate()
        assertTrue(t.depth < 40, "profondeur ${t.depth} pour 5000 feuilles")
        for (k in 0 until 2500) t.remove(leaves[k])
        t.validate()
        assertEquals(2500, t.leaves)
        // A query finds exactly the boxes it overlaps.
        val out = IntList(); val stack = IntList()
        t.query(40f, 0f, 40f, 60f, 10f, 60f, out, stack)
        val found = (0 until out.size).map { out[it] }.toSet()
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it >= 2500 })
    }
}
