package sillage.physics

import sillage.fmt
import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.*
import kotlin.test.*

/** Propre à la JVM : le solveur vectoriel (jdk.incubator.vector) contre le solveur scalaire. */
class VectorSolverTest {
    /**
     * Le solveur sur voies SIMD (module jdk.incubator.vector, chargé pour les tests) donne le même résultat,
     * au bit près, que le solveur scalaire : boîtes, boules qui roulent, capsules, une palette cinématique
     * qui balaie le tas, avec et sans fils.
     */
    @Test fun leSolveurVectorielDonneLeMemeResultat() {
        fun heap(vector: Boolean, parallel: Boolean, heavy: Boolean): Pair<PhysicsWorld, Body> {
            val w = PhysicsWorld(); w.vectorized = vector; w.parallel = parallel
            w.addMesh(TriangleMesh(floatArrayOf(-40f, 0f, -40f, -40f, 0f, 40f, 40f, 0f, 40f, -40f, 0f, -40f, 40f, 0f, 40f, 40f, 0f, -40f)))
            for (i in 0 until 2400) {
                val p = Vec3((i % 20) * 1.1f - 11f, 1f + (i / 400) * 1.2f, ((i / 20) % 20) * 1.1f - 11f)
                when (i % 3) {
                    // Heavy: crates ten times denser, on balls and capsules (the support scaling and its hidden loads).
                    0 -> w.add(Box(.4f, .3f, .35f), p, Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), i * .3f), density = if (heavy) 4000f else 400f)
                    1 -> w.add(Sphere(.35f), p)
                    else -> w.add(Capsule(.25f, .3f), p, Quat.axisAngle(Vec3.X, i * .5f))
                }
            }
            val paddle = w.add(Box(.3f, .6f, 6f), Vec3(-14f, .6f, 0f), type = BodyType.KINEMATIC)
            return w to paddle
        }
        fun bits(v: Vec3) = listOf(v.x.toRawBits(), v.y.toRawBits(), v.z.toRawBits())
        fun bits(q: Quat) = listOf(q.x.toRawBits(), q.y.toRawBits(), q.z.toRawBits(), q.w.toRawBits())
        for ((parallel, heavy) in listOf(false to false, true to false, true to true)) {
            val (a, pa) = heap(false, parallel, heavy); val (b, pb) = heap(true, parallel, heavy)
            var wide = 0
            for (k in 0 until 150) {
                val at = Vec3(-14f + k * .12f, .6f, 0f)
                pa.moveTo(at, 1f / 60f); pb.moveTo(at, 1f / 60f)
                a.step(1f / 60f); b.step(1f / 60f)
                assertFalse(a.lastStepVectorized)
                if (b.lastStepVectorized) wide++
            }
            assertTrue(wide > 100, "le solveur vectoriel n'a servi qu'à $wide pas sur 150")
            for (i in 0 until a.bodyCount) {
                val x = a.body(i); val y = b.body(i)
                val what = "(fils : $parallel, caisses lourdes : $heavy)"
                assertEquals(bits(x.position), bits(y.position), "corps $i, position $what")
                assertEquals(bits(x.rotation), bits(y.rotation), "corps $i, rotation $what")
                assertEquals(bits(x.velocity), bits(y.velocity), "corps $i, vitesse $what")
                assertEquals(bits(x.angularVelocity), bits(y.angularVelocity), "corps $i, vitesse angulaire $what")
            }
        }
    }
}
