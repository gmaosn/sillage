package sillage.math

import kotlin.math.sqrt

/** Immutable 3D vector, right-handed, Y up. */
@ValueCandidate
class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    /** Component by axis index: 0 = x, 1 = y, 2 = z. */
    operator fun get(axis: Int): Float = when (axis) {
        0 -> x; 1 -> y; 2 -> z
        else -> throw IndexOutOfBoundsException("axis $axis")
    }

    fun plus(dx: Float, dy: Float, dz: Float) = Vec3(x + dx, y + dy, z + dz)
    fun withX(v: Float) = Vec3(v, y, z)
    fun withY(v: Float) = Vec3(x, v, z)
    fun withZ(v: Float) = Vec3(x, y, v)

    /** Component-wise product. */
    fun scaled(o: Vec3) = Vec3(x * o.x, y * o.y, z * o.z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun lengthSquared() = x * x + y * y + z * z
    fun length() = sqrt(lengthSquared())
    fun distanceTo(o: Vec3): Float {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Unit vector in the same direction; the zero vector stays zero. */
    fun normalized(): Vec3 {
        val l = length()
        return if (l == 0f) this else Vec3(x / l, y / l, z / l)
    }

    fun lerp(to: Vec3, t: Float) = Vec3(x + (to.x - x) * t, y + (to.y - y) * t, z + (to.z - z) * t)
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

    override fun equals(other: Any?) = other is Vec3 && x == other.x && y == other.y && z == other.z
    override fun hashCode() = (x.hashCode() * 31 + y.hashCode()) * 31 + z.hashCode()
    override fun toString() = "($x, $y, $z)"

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val ONE = Vec3(1f, 1f, 1f)
        val X = Vec3(1f, 0f, 0f)
        val Y = Vec3(0f, 1f, 0f)
        val Z = Vec3(0f, 0f, 1f)
        val UP = Y
    }
}

operator fun Float.times(v: Vec3) = Vec3(this * v.x, this * v.y, this * v.z)
