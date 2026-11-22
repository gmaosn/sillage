package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One body made of several shapes: a table (a top and four legs), a boat (a hull and a keel), a hammer.
 * Each [Part] is a sphere, a box, a capsule or a [ConvexHull], placed in the compound's frame, with a
 * density relative to the others (a lead keel of density 10 in a wooden boat of density 1). The body's mass
 * is its density (or the mass given) times the parts' volumes weighted by those densities; its centre of
 * mass and inertia are those of all the parts together (parallel axes), its inertia diagonal about
 * [principalAxes].
 *
 * The compound is recentred on its centre of mass: its [parts] are where they were given minus [centre].
 * Put a body at `position + rotation.rotate(compound.centre)` to have the parts where they were given. A
 * hull part sits on its own centre of mass: to keep a hull's points where they were given, place it at
 * `hull.centre`. Parts should not overlap much: their volumes and masses add up.
 */
class Compound(parts: List<Part>) : Shape() {
    /** [parts], as arguments. */
    constructor(vararg parts: Part) : this(parts.toList())

    /** A part: [shape] at [position], turned by [rotation], in the compound's frame; [density] relative to the other parts. */
    class Part(val shape: Shape, val position: Vec3 = Vec3.ZERO, val rotation: Quat = Quat.IDENTITY, val density: Float = 1f) {
        init {
            require(shape !is Compound) { "A compound's parts cannot be compounds themselves" }
            require(density > 0f && density.isFinite()) { "A part's density must be positive" }
            require(position.isFinite()) { "A part's position must be finite" }
        }
    }

    /** The parts, about the centre of mass. */
    val parts: List<Part>
    /** Where the centre of mass lies in the frame the parts were given in; the compound is centred on it. */
    val centre: Vec3
    override val volume: Float
    override val massVolume: Float
    override val boundingRadius: Float
    override val principalAxes: Quat
    private val moments: Vec3                       // principal moments per unit mass

    internal val count: Int
    internal val shapes: Array<Shape>
    /** Each part's position (x, y, z) and rotation as a matrix by columns (its axes in the compound's frame). */
    internal val at: FloatArray
    internal val axes: FloatArray
    internal val radius: FloatArray
    // Bounds in the compound's frame (about its centre of mass).
    internal val minX: Float; internal val minY: Float; internal val minZ: Float
    internal val maxX: Float; internal val maxY: Float; internal val maxZ: Float

    init {
        require(parts.isNotEmpty()) { "A compound needs at least one part" }
        count = parts.size
        // Centre of mass, in double: the parts weighted by volume × density.
        var w = 0.0; var cx = 0.0; var cy = 0.0; var cz = 0.0; var vol = 0.0
        for (p in parts) {
            val m = p.shape.volume.toDouble() * p.density
            w += m; vol += p.shape.volume
            cx += m * p.position.x; cy += m * p.position.y; cz += m * p.position.z
        }
        cx /= w; cy /= w; cz /= w
        centre = Vec3(cx.toFloat(), cy.toFloat(), cz.toFloat())
        volume = vol.toFloat(); massVolume = w.toFloat()
        // Inertia per unit mass about the centre: each part's own, turned into the compound's frame, plus m·(|d|² 1 − d dᵀ).
        val t = DoubleArray(9)
        for (p in parts) {
            val f = p.shape.volume.toDouble() * p.density / w
            val q = p.rotation.normalized() * p.shape.principalAxes
            val mom = p.shape.inertia(1f)
            val r = rotation(q)
            for (i in 0..2) for (j in 0..2) {
                var s = 0.0
                for (k in 0..2) s += r[3 * i + k] * mom[k] * r[3 * j + k]
                t[3 * i + j] += f * s
            }
            val dx = p.position.x - cx; val dy = p.position.y - cy; val dz = p.position.z - cz
            val d2 = dx * dx + dy * dy + dz * dz
            t[0] += f * (d2 - dx * dx); t[4] += f * (d2 - dy * dy); t[8] += f * (d2 - dz * dz)
            t[1] -= f * dx * dy; t[3] -= f * dx * dy; t[2] -= f * dx * dz; t[6] -= f * dx * dz; t[5] -= f * dy * dz; t[7] -= f * dy * dz
        }
        val (axesQ, mom) = principal(t)
        principalAxes = axesQ; moments = mom
        this.parts = parts.map { Part(it.shape, it.position - centre, it.rotation.normalized(), it.density) }
        shapes = Array(count) { this.parts[it].shape }
        at = FloatArray(3 * count); axes = FloatArray(9 * count); radius = FloatArray(count)
        var br = 0f
        var lx = Float.MAX_VALUE; var ly = Float.MAX_VALUE; var lz = Float.MAX_VALUE
        var hx = -Float.MAX_VALUE; var hy = -Float.MAX_VALUE; var hz = -Float.MAX_VALUE
        for ((i, p) in this.parts.withIndex()) {
            at[3 * i] = p.position.x; at[3 * i + 1] = p.position.y; at[3 * i + 2] = p.position.z
            val r = rotation(p.rotation)
            // By columns: axes[9i + 3k + j] is component j of the part's axis k.
            for (k in 0..2) for (j in 0..2) axes[9 * i + 3 * k + j] = r[3 * j + k].toFloat()
            radius[i] = p.shape.boundingRadius
            br = max(br, p.position.length() + radius[i])
            val pose = Pose(); pose.set(p.position.x, p.position.y, p.position.z, p.rotation.x, p.rotation.y, p.rotation.z, p.rotation.w)
            val b = FloatArray(6); localBounds(p.shape, pose, b, 0)
            lx = min(lx, b[0]); ly = min(ly, b[1]); lz = min(lz, b[2]); hx = max(hx, b[3]); hy = max(hy, b[4]); hz = max(hz, b[5])
        }
        boundingRadius = br; minX = lx; minY = ly; minZ = lz; maxX = hx; maxY = hy; maxZ = hz
    }

    override fun inertia(mass: Float) = Vec3(moments.x * mass, moments.y * mass, moments.z * mass)

    /** Whether [point] (in the compound's frame, about its centre of mass) is inside one of its parts. */
    fun contains(point: Vec3): Boolean = parts.any { p ->
        val l = p.rotation.conjugate().rotate(point - p.position)
        when (val s = p.shape) {
            is Sphere -> l.length() <= s.radius
            is Box -> abs(l.x) <= s.halfX && abs(l.y) <= s.halfY && abs(l.z) <= s.halfZ
            is Capsule -> Vec3(l.x, l.y - l.y.coerceIn(-s.halfHeight, s.halfHeight), l.z).length() <= s.radius
            is ConvexHull -> s.contains(l)
            is Compound -> false
        }
    }

    /** The same compound [factor] times larger (parts, places and [centre]). */
    override fun scaled(factor: Float): Compound {
        require(factor > 0f) { "A scale must be positive" }
        return Compound(parts.map { Part(it.shape.scaled(factor), (it.position + centre) * factor, it.rotation, it.density) })
    }

    override fun toString() = "Compound(${parts.joinToString { it.shape.toString() }})"

    private companion object {
        /** A unit quaternion as a rotation matrix, row by row, in double. */
        fun rotation(q: Quat): DoubleArray {
            val x = q.x.toDouble(); val y = q.y.toDouble(); val z = q.z.toDouble(); val w = q.w.toDouble()
            return doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y))
        }
    }
}

/** Sets this pose to part [i] of [c] in the world, [parent] being the compound's pose. */
internal fun Pose.setPart(parent: Pose, c: Compound, i: Int): Pose {
    val x = c.at[3 * i]; val y = c.at[3 * i + 1]; val z = c.at[3 * i + 2]
    px = parent.worldX(x, y, z); py = parent.worldY(x, y, z); pz = parent.worldZ(x, y, z)
    val a = c.axes; val o = 9 * i
    xx = parent.xx * a[o] + parent.yx * a[o + 1] + parent.zx * a[o + 2]
    xy = parent.xy * a[o] + parent.yy * a[o + 1] + parent.zy * a[o + 2]
    xz = parent.xz * a[o] + parent.yz * a[o + 1] + parent.zz * a[o + 2]
    yx = parent.xx * a[o + 3] + parent.yx * a[o + 4] + parent.zx * a[o + 5]
    yy = parent.xy * a[o + 3] + parent.yy * a[o + 4] + parent.zy * a[o + 5]
    yz = parent.xz * a[o + 3] + parent.yz * a[o + 4] + parent.zz * a[o + 5]
    zx = parent.xx * a[o + 6] + parent.yx * a[o + 7] + parent.zx * a[o + 8]
    zy = parent.xy * a[o + 6] + parent.yy * a[o + 7] + parent.zy * a[o + 8]
    zz = parent.xz * a[o + 6] + parent.yz * a[o + 7] + parent.zz * a[o + 8]
    return this
}

/**
 * Bounds of [shape] at [pose] (min x, y, z, then max, into [out] from [at]), in the pose's frame of reference: exact for spheres,
 * boxes and capsules, the turned box of its own bounds for a hull or a compound.
 */
internal fun localBounds(shape: Shape, pose: Pose, out: FloatArray, at: Int) {
    var cx = pose.px; var cy = pose.py; var cz = pose.pz
    val ex: Float; val ey: Float; val ez: Float
    when (shape) {
        is Sphere -> { ex = shape.radius; ey = shape.radius; ez = shape.radius }
        is Box -> {
            ex = abs(pose.xx) * shape.halfX + abs(pose.yx) * shape.halfY + abs(pose.zx) * shape.halfZ
            ey = abs(pose.xy) * shape.halfX + abs(pose.yy) * shape.halfY + abs(pose.zy) * shape.halfZ
            ez = abs(pose.xz) * shape.halfX + abs(pose.yz) * shape.halfY + abs(pose.zz) * shape.halfZ
        }
        is Capsule -> { ex = abs(pose.yx) * shape.halfHeight + shape.radius; ey = abs(pose.yy) * shape.halfHeight + shape.radius; ez = abs(pose.yz) * shape.halfHeight + shape.radius }
        is ConvexHull -> {
            val mx = (shape.minX + shape.maxX) * .5f; val my = (shape.minY + shape.maxY) * .5f; val mz = (shape.minZ + shape.maxZ) * .5f
            val hx = (shape.maxX - shape.minX) * .5f; val hy = (shape.maxY - shape.minY) * .5f; val hz = (shape.maxZ - shape.minZ) * .5f
            cx = pose.worldX(mx, my, mz); cy = pose.worldY(mx, my, mz); cz = pose.worldZ(mx, my, mz)
            ex = abs(pose.xx) * hx + abs(pose.yx) * hy + abs(pose.zx) * hz
            ey = abs(pose.xy) * hx + abs(pose.yy) * hy + abs(pose.zy) * hz
            ez = abs(pose.xz) * hx + abs(pose.yz) * hy + abs(pose.zz) * hz
        }
        is Compound -> {
            val mx = (shape.minX + shape.maxX) * .5f; val my = (shape.minY + shape.maxY) * .5f; val mz = (shape.minZ + shape.maxZ) * .5f
            val hx = (shape.maxX - shape.minX) * .5f; val hy = (shape.maxY - shape.minY) * .5f; val hz = (shape.maxZ - shape.minZ) * .5f
            cx = pose.worldX(mx, my, mz); cy = pose.worldY(mx, my, mz); cz = pose.worldZ(mx, my, mz)
            ex = abs(pose.xx) * hx + abs(pose.yx) * hy + abs(pose.zx) * hz
            ey = abs(pose.xy) * hx + abs(pose.yy) * hy + abs(pose.zy) * hz
            ez = abs(pose.xz) * hx + abs(pose.yz) * hy + abs(pose.zz) * hz
        }
    }
    out[at] = cx - ex; out[at + 1] = cy - ey; out[at + 2] = cz - ez; out[at + 3] = cx + ex; out[at + 4] = cy + ey; out[at + 5] = cz + ez
}
