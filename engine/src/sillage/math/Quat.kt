package sillage.math

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

    override fun equals(other: Any?) = other is Quat && x == other.x && y == other.y && z == other.z && w == other.w
    override fun hashCode() = ((x.hashCode() * 31 + y.hashCode()) * 31 + z.hashCode()) * 31 + w.hashCode()
    override fun toString() = "Quat($x, $y, $z, $w)"

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        /** Rotation of [angle] radians around the unit [axis]. */
        fun axisAngle(axis: Vec3, angle: Float): Quat {
            val s = sin(angle * .5f); val c = cos(angle * .5f)
            return Quat(axis.x * s, axis.y * s, axis.z * s, c)
        }
        fun rotationY(angle: Float) = axisAngle(Vec3.Y, angle)
    }
}
