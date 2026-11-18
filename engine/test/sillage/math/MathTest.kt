package sillage.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.*

class MathTest {
    private fun assertNear(e: Vec3, a: Vec3, eps: Float = 1e-5f) =
        assertTrue(e.distanceTo(a) <= eps, "attendu $e, obtenu $a")
    private fun assertNear(e: Mat4, a: Mat4, eps: Float = 1e-5f) {
        val x = e.toFloatArray(); val y = a.toFloatArray()
        assertTrue(x.indices.all { abs(x[it] - y[it]) <= eps }, "attendu $e\nobtenu $a")
    }

    @Test fun vecteurs() {
        assertEquals(Vec3.Z, Vec3.X cross Vec3.Y)
        assertEquals(32f, Vec3(1f, 2f, 3f) dot Vec3(4f, 5f, 6f))
        assertEquals(Vec3.ZERO, Vec3.ZERO.normalized())
        assertEquals(1f, Vec3(3f, 4f, 12f).normalized().length(), 1e-6f)
        assertEquals(13f, Vec3(3f, 4f, 12f).distanceTo(Vec3.ZERO))
        assertEquals(Vec3(2f, 4f, 6f), 2f * Vec3(1f, 2f, 3f))
        assertEquals(Vec3(1f, 2f, 3f).hashCode(), Vec3(1f, 2f, 3f).hashCode())
        assertEquals(Vec3(.5f, 1f, 1.5f), Vec3.ZERO.lerp(Vec3(1f, 2f, 3f), .5f))
        assertEquals(2f, Vec3(1f, 2f, 3f)[1])
    }

    @Test fun produitDeMatricesAssociatifEtNeutre() {
        val a = Mat4.trs(Vec3(1f, 2f, 3f), Quat.axisAngle(Vec3(1f, 1f, 0f).normalized(), .7f), Vec3(2f, 1f, .5f))
        val b = Mat4.trsY(Vec3(-4f, 0f, 1f), 1.3f, 3f)
        val c = Mat4.perspective(1f, 1.6f, .1f, 100f)
        assertEquals(a, a * Mat4.IDENTITY)
        assertEquals(a, Mat4.IDENTITY * a)
        assertNear((a * b) * c, a * (b * c), 1e-4f)
        val p = Vec3(.3f, -2f, 5f)
        assertNear(a.transformPoint(b.transformPoint(p)), (a * b).transformPoint(p), 1e-4f)
    }

    @Test fun trsEgaleTranslationRotationEchelle() {
        val t = Vec3(1f, -2f, 3f); val q = Quat.axisAngle(Vec3(.2f, 1f, -.3f).normalized(), 2.1f); val s = Vec3(1f, 2f, 3f)
        assertNear(Mat4.translation(t) * Mat4.rotation(q) * Mat4.scaling(s), Mat4.trs(t, q, s))
        assertNear(Mat4.translation(t) * Mat4.rotationY(.8f) * Mat4.scaling(2f), Mat4.trsY(t, .8f, 2f))
        assertNear(Mat4.rotationY(.8f), Mat4.rotation(Quat.rotationY(.8f)))
    }

    @Test fun quaternionsEtMatricesTournentPareil() {
        val q = Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), 1.1f)
        val r = Quat.axisAngle(Vec3(-1f, 0f, 2f).normalized(), -.4f)
        val v = Vec3(3f, -1f, 2f)
        assertNear(Mat4.rotation(q).transformDirection(v), q.rotate(v))
        assertNear(q.rotate(r.rotate(v)), (q * r).rotate(v))
        assertNear(v, q.conjugate().rotate(q.rotate(v)))
        // Rotation de 90° autour de Y : X va vers −Z (repère main droite).
        assertNear(Vec3(0f, 0f, -1f), Quat.rotationY((PI / 2).toFloat()).rotate(Vec3.X))
    }

    @Test fun determinantSigneDuMiroir() {
        assertEquals(-1f, Mat4.scaling(Vec3(-1f, 1f, 1f)).determinant3x3())
        assertEquals(24f, Mat4.trs(Vec3.ZERO, Quat.rotationY(.3f), Vec3(2f, 3f, 4f)).determinant3x3(), 1e-4f)
    }

    @Test fun perspectiveVulkan() {
        val p = Mat4.perspective((PI / 2).toFloat(), 1f, .5f, 50f)
        fun clip(v: Vec3): FloatArray {
            val a = p.toFloatArray()
            return FloatArray(4) { r -> a[r] * v.x + a[4 + r] * v.y + a[8 + r] * v.z + a[12 + r] }
        }
        val near = clip(Vec3(0f, 0f, -.5f)); assertEquals(0f, near[2] / near[3], 1e-6f)
        val far = clip(Vec3(0f, 0f, -50f)); assertEquals(1f, far[2] / far[3], 1e-6f)
        // Le haut du monde est en haut de l'écran : y de clip négatif chez Vulkan.
        val haut = clip(Vec3(0f, 1f, -2f)); assertTrue(haut[1] / haut[3] < 0f)
    }

    @Test fun regardVersUneCible() {
        val eye = Vec3(3f, 4f, 5f); val target = Vec3(-1f, 0f, 2f)
        val v = Mat4.lookAt(eye, target)
        assertNear(Vec3.ZERO, v.transformPoint(eye))
        assertNear(Vec3(0f, 0f, -eye.distanceTo(target)), v.transformPoint(target), 1e-4f)
        assertTrue(v.transformDirection(Vec3.UP).y > 0f)
    }

    @Test fun ecritureColonneParColonne() {
        val m = Mat4.translation(Vec3(7f, 8f, 9f))
        val a = FloatArray(20); m.writeTo(a, 4)
        assertEquals(listOf(7f, 8f, 9f, 1f), a.slice(16..19))
        assertEquals(m, Mat4.fromColumnMajor(a, 4))
    }
}
