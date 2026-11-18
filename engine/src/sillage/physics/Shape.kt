package sillage.physics

import sillage.math.ValueCandidate
import sillage.math.Vec3
import kotlin.math.PI

/** Collision shape of a body, centred on the body's position (its centre of mass). */
sealed class Shape {
    /** Volume in m³, used to turn a density into a mass. */
    abstract val volume: Float
    /** Principal moments of inertia about the local axes for [mass] (the shapes are symmetric). */
    abstract fun inertia(mass: Float): Vec3
    /** Radius of a sphere around the centre that holds the whole shape. */
    abstract val boundingRadius: Float
}

/** A ball. */
@ValueCandidate
class Sphere(val radius: Float) : Shape() {
    init { require(radius > 0f) { "Sphere radius must be positive" } }
    override val volume get() = (4.0 / 3.0 * PI * radius * radius * radius).toFloat()
    override fun inertia(mass: Float): Vec3 { val i = .4f * mass * radius * radius; return Vec3(i, i, i) }
    override val boundingRadius get() = radius
}

/** A box given by its half extents along the local X, Y, Z axes. */
@ValueCandidate
class Box(val halfX: Float, val halfY: Float, val halfZ: Float) : Shape() {
    init { require(halfX > 0f && halfY > 0f && halfZ > 0f) { "Box half extents must be positive" } }
    override val volume get() = 8f * halfX * halfY * halfZ
    override fun inertia(mass: Float) = Vec3(
        mass / 3f * (halfY * halfY + halfZ * halfZ),
        mass / 3f * (halfX * halfX + halfZ * halfZ),
        mass / 3f * (halfX * halfX + halfY * halfY))
    override val boundingRadius get() = kotlin.math.sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ)
    fun half(axis: Int) = when (axis) { 0 -> halfX; 1 -> halfY; else -> halfZ }
}

/** A capsule: a segment along local Y from -[halfHeight] to +[halfHeight], swept by a ball of [radius]. */
@ValueCandidate
class Capsule(val radius: Float, val halfHeight: Float) : Shape() {
    init { require(radius > 0f && halfHeight >= 0f) { "Capsule radius must be positive and half height non-negative" } }
    override val volume get() = (PI * radius * radius * (2 * halfHeight) + 4.0 / 3.0 * PI * radius * radius * radius).toFloat()
    override fun inertia(mass: Float): Vec3 {
        val cyl = (PI * radius * radius * 2 * halfHeight).toFloat()
        val sph = (4.0 / 3.0 * PI * radius * radius * radius).toFloat()
        val mc = mass * cyl / (cyl + sph); val ms = mass - mc
        val h = 2 * halfHeight; val r2 = radius * radius
        val axial = mc * r2 / 2 + ms * 2 * r2 / 5
        val side = mc * (h * h / 12 + r2 / 4) + ms * (2 * r2 / 5 + h * h / 4 + 3 * h * radius / 8)
        return Vec3(side, axial, side)
    }
    override val boundingRadius get() = radius + halfHeight
}
