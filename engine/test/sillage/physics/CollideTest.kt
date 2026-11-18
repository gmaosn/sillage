package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fingerprints of the narrow phase and of the rays: every contact (point, normal, separation, feature)
 * and every hit of thousands of random cases, folded into one number. The physics is deterministic to
 * the bit, so a refactoring must leave these numbers alone. A change made on purpose updates them
 * (and says so in its commit). The cases use no trigonometry, whose last bit may differ between CPUs.
 */
class CollideTest {
    private fun Random.f(a: Float, b: Float) = a + nextFloat() * (b - a)
    private fun Random.dir(): Vec3 {
        while (true) {
            val v = Vec3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f))
            val l = v.length(); if (l in .1f..1f) return v / l
        }
    }
    /** A rotation: none, a quarter turn about Y, a turn about Y, a slight tilt, or anything. */
    private fun Random.rot(): Quat = when (nextInt(5)) {
        0 -> Quat.IDENTITY
        1 -> listOf(Quat(0f, 0f, 0f, 1f), Quat(0f, .70710677f, 0f, .70710677f), Quat(0f, 1f, 0f, 0f), Quat(0f, -.70710677f, 0f, .70710677f))[nextInt(4)]
        2 -> Quat(0f, f(-1f, 1f), 0f, f(-1f, 1f))
        3 -> Quat(f(-.03f, .03f), f(-.03f, .03f), f(-.03f, .03f), 1f)
        else -> Quat(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f), f(-1f, 1f))
    }.normalized()
    private fun Random.shape(): Shape = when (nextInt(3)) {
        0 -> Sphere(f(.1f, 1.2f))
        1 -> Capsule(f(.1f, .8f), if (nextInt(6) == 0) 0f else f(.05f, 1.2f))
        else -> Box(f(.05f, 1.5f), f(.05f, 1.5f), f(.05f, 1.5f))
    }
    private class Digest {
        var h = 1125899906842597L; var n = 0
        fun add(v: Int) { h = 31 * h + v; n++ }
        fun add(v: Float) = add(v.toRawBits())
        fun add(p: ContactPoints) {
            add(p.count)
            for (i in 0 until p.count) { add(p.x[i]); add(p.y[i]); add(p.z[i]); add(p.nx[i]); add(p.ny[i]); add(p.nz[i]); add(p.sep[i]); add(p.id[i]) }
        }
    }

    /** A bumpy grid (inactive seams, active folds) and loose triangles (all edges active). */
    private fun Random.mesh(): TriangleMesh {
        val v = ArrayList<Float>()
        val h = Array(6) { FloatArray(6) { if (nextInt(3) == 0) f(-.6f, .6f) else 0f } }
        for (i in 0 until 5) for (j in 0 until 5) {
            fun p(a: Int, b: Int) = listOf(a - 2.5f, h[a][b], b - 2.5f)
            v += p(i, j); v += p(i, j + 1); v += p(i + 1, j + 1)
            v += p(i, j); v += p(i + 1, j + 1); v += p(i + 1, j)
        }
        repeat(8) {
            val c = Vec3(f(-3f, 3f), f(-1f, 2f), f(-3f, 3f))
            repeat(3) { val q = c + dir() * f(.3f, 2f); v += q.x; v += q.y; v += q.z }
        }
        return TriangleMesh(v.toFloatArray())
    }

    @Test fun lesContactsEntreCorpsNeChangentPas() {
        val rnd = Random(7); val out = ContactPoints(); val d = Digest()
        var touching = 0
        repeat(30_000) {
            val a = rnd.shape(); val b = rnd.shape()
            val qa = rnd.rot(); val qb = if (rnd.nextInt(4) == 0) qa else rnd.rot()
            val pa = Vec3(rnd.f(-3f, 3f), rnd.f(-3f, 3f), rnd.f(-3f, 3f))
            val pb = pa + rnd.dir() * ((a.boundingRadius + b.boundingRadius) * rnd.f(.05f, 1.1f))
            val margin = if (rnd.nextInt(3) == 0) 0f else rnd.f(0f, .4f)
            out.clear()
            collide(a, Pose().set(pa.x, pa.y, pa.z, qa.x, qa.y, qa.z, qa.w), b, Pose().set(pb.x, pb.y, pb.z, qb.x, qb.y, qb.z, qb.w), margin, out)
            d.add(out)
            if (out.count > 0) touching++
        }
        assertEquals(true, touching > 10_000, "too few contacts for the fingerprint to mean anything ($touching)")
        assertEquals(-2307191465966286567L, d.h, "the contacts between bodies changed")
    }

    @Test fun lesContactsAvecLesTrianglesNeChangentPas() {
        val rnd = Random(11); val out = ContactPoints(); val d = Digest()
        repeat(20) {
            val mesh = rnd.mesh()
            repeat(1500) {
                val s = rnd.shape(); val q = rnd.rot()
                val t = rnd.nextInt(mesh.count); val v = mesh.v
                val c = Vec3((v[9 * t] + v[9 * t + 3] + v[9 * t + 6]) / 3, (v[9 * t + 1] + v[9 * t + 4] + v[9 * t + 7]) / 3, (v[9 * t + 2] + v[9 * t + 5] + v[9 * t + 8]) / 3)
                val p = c + rnd.dir() * (s.boundingRadius * rnd.f(0f, 1.3f))
                val margin = if (rnd.nextInt(3) == 0) 0f else rnd.f(0f, .4f)
                out.clear()
                collideTriangle(mesh, t, s, Pose().set(p.x, p.y, p.z, q.x, q.y, q.z, q.w), margin, out)
                d.add(out)
            }
        }
        assertEquals(534126528740741422L, d.h, "the contacts with triangles changed")
    }

    @Test fun lesRayonsNeChangentPas() {
        val rnd = Random(13); val d = Digest()
        repeat(8) { k ->
            val w = PhysicsWorld()
            w.addMesh(rnd.mesh())
            // Enough bodies for the query grid one world in two.
            repeat(if (k % 2 == 0) 40 else 160) {
                w.add(rnd.shape(), Vec3(rnd.f(-6f, 6f), rnd.f(-1f, 4f), rnd.f(-6f, 6f)), rnd.rot(),
                    type = if (rnd.nextBoolean()) BodyType.STATIC else BodyType.DYNAMIC)
            }
            repeat(2000) {
                val origin = Vec3(rnd.f(-7f, 7f), rnd.f(-1f, 6f), rnd.f(-7f, 7f))
                val dir = if (rnd.nextInt(10) == 0) Vec3(0f, -1f, 0f) else rnd.dir() * rnd.f(.5f, 3f)
                val radius = when (rnd.nextInt(3)) { 0 -> 0f; 1 -> rnd.f(.05f, .5f); else -> rnd.f(.5f, 1f) }
                val hit = w.raycast(origin, dir, rnd.f(.5f, 15f), radius, meshes = rnd.nextInt(4) != 0)
                if (hit == null) d.add(-1) else {
                    d.add(hit.distance); d.add(hit.point.x); d.add(hit.point.y); d.add(hit.point.z)
                    d.add(hit.normal.x); d.add(hit.normal.y); d.add(hit.normal.z); d.add(hit.body?.id ?: -1)
                }
            }
        }
        assertEquals(5344848001148416849L, d.h, "the rays changed")
    }
}
