package sillage

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.*

/** Meshes made lighter for far away. */
class SimplifyTest {
    private fun edges(m: Mesh): Map<Long, Int> {
        val ids = HashMap<Triple<Long, Long, Long>, Int>()
        fun r(x: Float) = Math.round(x / 1e-5)
        fun id(o: Int) = ids.getOrPut(Triple(r(m.vertices[o]), r(m.vertices[o + 1]), r(m.vertices[o + 2]))) { ids.size }
        val count = HashMap<Long, Int>()
        for (t in 0 until m.triangleCount) for (j in 0..2) {
            val a = id(27 * t + 9 * j); val b = id(27 * t + 9 * ((j + 1) % 3))
            val k = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
            count[k] = (count[k] ?: 0) + 1
        }
        return count
    }

    @Test fun uneSphèreAllégéeResteUneSphèreFermée() {
        val sphere = Mesh.sphere(1f, Color.RED, 48, 24)
        assertTrue(edges(sphere).values.all { it == 2 }, "the original is closed")
        val light = sphere.simplified(.2)
        assertTrue(light.triangleCount <= sphere.triangleCount * .2 + 2, "${sphere.triangleCount} → ${light.triangleCount}")
        assertTrue(light.triangleCount >= sphere.triangleCount * .15, "${light.triangleCount}")
        assertTrue(edges(light).values.all { it == 2 }, "still closed: no hole, no flap")
        var worst = 0f
        for (i in light.vertices.indices step 9) {
            val r = sqrt(light.vertices[i] * light.vertices[i] + light.vertices[i + 1] * light.vertices[i + 1] + light.vertices[i + 2] * light.vertices[i + 2])
            worst = maxOf(worst, abs(r - 1f))
        }
        assertTrue(worst < .06f, "every kept vertex near the sphere: $worst")
        // Faces turned outward, normals soft and pointing out.
        for (t in 0 until light.triangleCount) {
            val o = 27 * t
            val cx = (light.vertices[o] + light.vertices[o + 9] + light.vertices[o + 18]) / 3
            val cy = (light.vertices[o + 1] + light.vertices[o + 10] + light.vertices[o + 19]) / 3
            val cz = (light.vertices[o + 2] + light.vertices[o + 11] + light.vertices[o + 20]) / 3
            assertTrue(cx * light.vertices[o + 3] + cy * light.vertices[o + 4] + cz * light.vertices[o + 5] > 0f, "face $t turned out")
        }
    }

    @Test fun unPlanAuBordDroitGardeSonContour() {
        // A flat grid 10 × 10: it can lose almost all inner vertices, never its outline.
        val b = MeshBuilder()
        for (i in 0 until 10) for (j in 0 until 10) {
            val a = sillage.math.Vec3(i.toFloat(), 0f, -j.toFloat()); val c = sillage.math.Vec3(i + 1f, 0f, -j.toFloat())
            val d = sillage.math.Vec3(i + 1f, 0f, -j - 1f); val e = sillage.math.Vec3(i.toFloat(), 0f, -j - 1f)
            b.triangle(a, c, d, Color.GRASS); b.triangle(a, d, e, Color.GRASS)
        }
        val grid = b.build()
        val light = grid.simplified(.1)
        assertTrue(light.triangleCount <= 20, "${light.triangleCount}")
        val (lo, hi) = light.bounds
        assertEquals(0f, lo.x, 1e-5f); assertEquals(10f, hi.x, 1e-5f); assertEquals(-10f, lo.z, 1e-5f); assertEquals(0f, hi.z, 1e-5f)
        var area = 0f
        for (t in 0 until light.triangleCount) {
            val o = 27 * t; val v = light.vertices
            val ux = v[o + 9] - v[o]; val uz = v[o + 11] - v[o + 2]; val wx = v[o + 18] - v[o]; val wz = v[o + 20] - v[o + 2]
            area += abs(ux * wz - uz * wx) / 2
        }
        assertEquals(100f, area, .01f, "the same surface, no fold")
    }

    @Test fun lesMatériauxEtLaCouleurSuivent() {
        val crate = Mesh.box(1f, 1f, 1f, Color.WOOD, round = .1f)
        val light = crate.simplified(.3)
        assertTrue(light.triangleCount < crate.triangleCount)
        for (i in light.vertices.indices step 9) assertEquals(Color.WOOD.r, light.vertices[i + 6], 1e-6f)
        assertSame(crate, crate.simplified(1))
        assertFailsWith<IllegalArgumentException> { crate.simplified(0) }
    }
}
