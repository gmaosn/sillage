package sillage.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Immutable 4×4 matrix, column-major like glTF and GLSL: `mCR` is column C, row R.
 * Points are column vectors, so `a * b` applies `b` first.
 */
@ValueCandidate
class Mat4(
    val m00: Float, val m01: Float, val m02: Float, val m03: Float,
    val m10: Float, val m11: Float, val m12: Float, val m13: Float,
    val m20: Float, val m21: Float, val m22: Float, val m23: Float,
    val m30: Float, val m31: Float, val m32: Float, val m33: Float,
) {
    operator fun times(b: Mat4) = Mat4(
        m00 * b.m00 + m10 * b.m01 + m20 * b.m02 + m30 * b.m03,
        m01 * b.m00 + m11 * b.m01 + m21 * b.m02 + m31 * b.m03,
        m02 * b.m00 + m12 * b.m01 + m22 * b.m02 + m32 * b.m03,
        m03 * b.m00 + m13 * b.m01 + m23 * b.m02 + m33 * b.m03,
        m00 * b.m10 + m10 * b.m11 + m20 * b.m12 + m30 * b.m13,
        m01 * b.m10 + m11 * b.m11 + m21 * b.m12 + m31 * b.m13,
        m02 * b.m10 + m12 * b.m11 + m22 * b.m12 + m32 * b.m13,
        m03 * b.m10 + m13 * b.m11 + m23 * b.m12 + m33 * b.m13,
        m00 * b.m20 + m10 * b.m21 + m20 * b.m22 + m30 * b.m23,
        m01 * b.m20 + m11 * b.m21 + m21 * b.m22 + m31 * b.m23,
        m02 * b.m20 + m12 * b.m21 + m22 * b.m22 + m32 * b.m23,
        m03 * b.m20 + m13 * b.m21 + m23 * b.m22 + m33 * b.m23,
        m00 * b.m30 + m10 * b.m31 + m20 * b.m32 + m30 * b.m33,
        m01 * b.m30 + m11 * b.m31 + m21 * b.m32 + m31 * b.m33,
        m02 * b.m30 + m12 * b.m31 + m22 * b.m32 + m32 * b.m33,
        m03 * b.m30 + m13 * b.m31 + m23 * b.m32 + m33 * b.m33)

    /** Transforms a point (w = 1); the matrix is assumed affine. */
    fun transformPoint(p: Vec3) = Vec3(
        m00 * p.x + m10 * p.y + m20 * p.z + m30,
        m01 * p.x + m11 * p.y + m21 * p.z + m31,
        m02 * p.x + m12 * p.y + m22 * p.z + m32)

    /** Transforms a direction (w = 0): no translation. */
    fun transformDirection(d: Vec3) = Vec3(
        m00 * d.x + m10 * d.y + m20 * d.z,
        m01 * d.x + m11 * d.y + m21 * d.z,
        m02 * d.x + m12 * d.y + m22 * d.z)

    /** Determinant of the upper-left 3×3 block; negative when the transform mirrors. */
    fun determinant3x3() =
        m00 * (m11 * m22 - m12 * m21) + m01 * (m12 * m20 - m10 * m22) + m02 * (m10 * m21 - m11 * m20)

    val translation get() = Vec3(m30, m31, m32)

    /** Writes the 16 values column by column into [dst] from [offset]. */
    fun writeTo(dst: FloatArray, offset: Int = 0) {
        dst[offset] = m00; dst[offset + 1] = m01; dst[offset + 2] = m02; dst[offset + 3] = m03
        dst[offset + 4] = m10; dst[offset + 5] = m11; dst[offset + 6] = m12; dst[offset + 7] = m13
        dst[offset + 8] = m20; dst[offset + 9] = m21; dst[offset + 10] = m22; dst[offset + 11] = m23
        dst[offset + 12] = m30; dst[offset + 13] = m31; dst[offset + 14] = m32; dst[offset + 15] = m33
    }

    fun toFloatArray() = FloatArray(16).also { writeTo(it) }

    override fun equals(other: Any?): Boolean {
        if (other !is Mat4) return false
        val a = toFloatArray(); val b = other.toFloatArray()
        return a.indices.all { a[it] == b[it] }
    }
    override fun hashCode() = toFloatArray().contentHashCode()
    override fun toString() = "Mat4" + (0..3).joinToString(prefix = "[", postfix = "]") { r ->
        (0..3).joinToString(" ") { c -> toFloatArray()[c * 4 + r].toString() }
    }

    companion object {
        val IDENTITY = Mat4(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        /** Reads 16 column-major values, as stored by glTF `matrix`. */
        fun fromColumnMajor(a: FloatArray, offset: Int = 0): Mat4 {
            require(a.size - offset >= 16) { "A 4×4 matrix needs 16 values" }
            return Mat4(a[offset], a[offset + 1], a[offset + 2], a[offset + 3],
                a[offset + 4], a[offset + 5], a[offset + 6], a[offset + 7],
                a[offset + 8], a[offset + 9], a[offset + 10], a[offset + 11],
                a[offset + 12], a[offset + 13], a[offset + 14], a[offset + 15])
        }

        fun translation(t: Vec3) = Mat4(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, t.x, t.y, t.z, 1f)
        fun scaling(s: Float) = scaling(Vec3(s, s, s))
        fun scaling(s: Vec3) = Mat4(s.x, 0f, 0f, 0f, 0f, s.y, 0f, 0f, 0f, 0f, s.z, 0f, 0f, 0f, 0f, 1f)
        fun rotationY(angle: Float): Mat4 {
            val s = sin(angle); val c = cos(angle)
            return Mat4(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
        }
        fun rotation(q: Quat) = trs(Vec3.ZERO, q, Vec3.ONE)

        /** Translation × rotation × scale in one go, the glTF node order. The quaternion must be unit. */
        fun trs(t: Vec3, r: Quat, s: Vec3): Mat4 {
            val xx = r.x * r.x; val yy = r.y * r.y; val zz = r.z * r.z
            val xy = r.x * r.y; val xz = r.x * r.z; val yz = r.y * r.z
            val wx = r.w * r.x; val wy = r.w * r.y; val wz = r.w * r.z
            return Mat4(
                (1f - 2f * (yy + zz)) * s.x, 2f * (xy + wz) * s.x, 2f * (xz - wy) * s.x, 0f,
                2f * (xy - wz) * s.y, (1f - 2f * (xx + zz)) * s.y, 2f * (yz + wx) * s.y, 0f,
                2f * (xz + wy) * s.z, 2f * (yz - wx) * s.z, (1f - 2f * (xx + yy)) * s.z, 0f,
                t.x, t.y, t.z, 1f)
        }

        /** Translation × rotation about Y × uniform scale: the transform of a scene object. */
        fun trsY(t: Vec3, angle: Float, s: Float): Mat4 {
            val sn = sin(angle) * s; val c = cos(angle) * s
            return Mat4(c, 0f, -sn, 0f, 0f, s, 0f, 0f, sn, 0f, c, 0f, t.x, t.y, t.z, 1f)
        }

        /**
         * Perspective projection for Vulkan clip space: depth 0 at [near] to 1 at [far],
         * Y pointing down in framebuffer space, so world Y up stays up on screen.
         */
        fun perspective(fovY: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val f = 1f / tan(fovY * .5f)
            return Mat4(f / aspect, 0f, 0f, 0f,
                0f, -f, 0f, 0f,
                0f, 0f, far / (near - far), -1f,
                0f, 0f, near * far / (near - far), 0f)
        }

        /** View matrix of a camera at [eye] looking at [target] (right-handed, −Z forward). */
        fun lookAt(eye: Vec3, target: Vec3, up: Vec3 = Vec3.UP): Mat4 {
            val back = (eye - target).normalized()
            val right = (up cross back).normalized()
            val top = back cross right
            return Mat4(right.x, top.x, back.x, 0f,
                right.y, top.y, back.y, 0f,
                right.z, top.z, back.z, 0f,
                -(right dot eye), -(top dot eye), -(back dot eye), 1f)
        }
    }
}
