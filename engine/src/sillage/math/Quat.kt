package sillage.math

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable rotation quaternion (x, y, z, w), glTF component order. */
@ValueCandidate
class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(q: Quat) = Quat(
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
        w * q.w - x * q.x - y * q.y - z * q.z)

    /** Rotates [v]; the quaternion must be unit length. */
    fun rotate(v: Vec3): Vec3 {
        // t = 2 (q × v), v' = v + w t + q × t
        val tx = 2f * (y * v.z - z * v.y)
        val ty = 2f * (z * v.x - x * v.z)
        val tz = 2f * (x * v.y - y * v.x)
        return Vec3(v.x + w * tx + (y * tz - z * ty),
                    v.y + w * ty + (z * tx - x * tz),
                    v.z + w * tz + (x * ty - y * tx))
    }

    fun lengthSquared() = x * x + y * y + z * z + w * w
    fun normalized(): Quat {
        val l = sqrt(lengthSquared())
        return if (l == 0f) IDENTITY else Quat(x / l, y / l, z / l, w / l)
    }
    fun conjugate() = Quat(-x, -y, -z, w)
    infix fun dot(q: Quat) = x * q.x + y * q.y + z * q.z + w * q.w

    /** Spherical interpolation along the shortest arc, as glTF requires for LINEAR rotations. */
    fun slerp(to: Quat, t: Float): Quat {
        val out = FloatArray(4)
        slerpInto(floatArrayOf(x, y, z, w), 0, floatArrayOf(to.x, to.y, to.z, to.w), 0, t, out, 0)
        return Quat(out[0], out[1], out[2], out[3])
    }

    override fun equals(other: Any?) = other is Quat && x == other.x && y == other.y && z == other.z && w == other.w
    override fun hashCode() = ((x.hashCode() * 31 + y.hashCode()) * 31 + z.hashCode()) * 31 + w.hashCode()
    override fun toString() = "Quat($x, $y, $z, $w)"

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        /** The rotation that takes X, Y, Z onto the orthonormal axes [ax], [ay], [az] (Shepperd's method). */
        fun fromAxes(ax: Vec3, ay: Vec3, az: Vec3): Quat {
            val r00 = ax.x; val r10 = ax.y; val r20 = ax.z
            val r01 = ay.x; val r11 = ay.y; val r21 = ay.z
            val r02 = az.x; val r12 = az.y; val r22 = az.z
            val trace = r00 + r11 + r22
            return when {
                trace > 0f -> { val s = kotlin.math.sqrt(trace + 1f) * 2f; Quat((r21 - r12) / s, (r02 - r20) / s, (r10 - r01) / s, .25f * s) }
                r00 > r11 && r00 > r22 -> { val s = kotlin.math.sqrt(1f + r00 - r11 - r22) * 2f; Quat(.25f * s, (r01 + r10) / s, (r02 + r20) / s, (r21 - r12) / s) }
                r11 > r22 -> { val s = kotlin.math.sqrt(1f + r11 - r00 - r22) * 2f; Quat((r01 + r10) / s, .25f * s, (r12 + r21) / s, (r02 - r20) / s) }
                else -> { val s = kotlin.math.sqrt(1f + r22 - r00 - r11) * 2f; Quat((r02 + r20) / s, (r12 + r21) / s, .25f * s, (r10 - r01) / s) }
            }.normalized()
        }

        /** Rotation of [angle] radians around the unit [axis]. */
        fun axisAngle(axis: Vec3, angle: Float): Quat {
            val s = sin(angle * .5f); val c = cos(angle * .5f)
            return Quat(axis.x * s, axis.y * s, axis.z * s, c)
        }
        fun rotationY(angle: Float) = axisAngle(Vec3.Y, angle)
    }
}

/**
 * Slerp between quaternions stored in arrays (x, y, z, w at the given offsets), written to [out].
 * Shortest arc; falls back to a normalised lerp when the two are almost equal. Allocation-free,
 * for the animation loop.
 */
fun slerpInto(a: FloatArray, ai: Int, b: FloatArray, bi: Int, t: Float, out: FloatArray, oi: Int) {
    var bx = b[bi]; var by = b[bi + 1]; var bz = b[bi + 2]; var bw = b[bi + 3]
    var d = a[ai] * bx + a[ai + 1] * by + a[ai + 2] * bz + a[ai + 3] * bw
    if (d < 0f) { bx = -bx; by = -by; bz = -bz; bw = -bw; d = -d }
    val wa: Float; val wb: Float
    if (d > .9995f) { wa = 1f - t; wb = t }
    else {
        val theta = acos(d); val s = sin(theta)
        wa = sin((1f - t) * theta) / s; wb = sin(t * theta) / s
    }
    var x = wa * a[ai] + wb * bx; var y = wa * a[ai + 1] + wb * by
    var z = wa * a[ai + 2] + wb * bz; var w = wa * a[ai + 3] + wb * bw
    val l = sqrt(x * x + y * y + z * z + w * w)
    if (l > 0f) { x /= l; y /= l; z /= l; w /= l }
    out[oi] = x; out[oi + 1] = y; out[oi + 2] = z; out[oi + 3] = w
}
