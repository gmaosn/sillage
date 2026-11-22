package sillage.physics

import sillage.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A body of water: below [level], within its horizontal bounds, dynamic bodies are held up by the weight of
 * the water they displace (Archimedes), applied at the centre of what they displace, so that a boat with a
 * low centre of mass rights itself; they are slowed in proportion to how
 * much of them is under water ([linearDrag], [dragCoefficient], [angularDrag]) and by the waves they make
 * ([waveDamping]), and carried by [flow]. Made by [PhysicsWorld.addWater]. A body floating free
 * falls asleep only at rest in the water: its weight carried and its centre of buoyancy over its centre of mass.
 *
 * The forces act in every substep, like gravity. The displaced volume is exact for spheres (a spherical
 * cap), boxes and hulls (the polyhedron cut by the surface); a capsule is summed over 24 slices across its
 * axis, each an exact disc segment, scaled so that the whole capsule is exact (within 0.1 % when partly
 * under); a compound adds up its parts. A body is in the water whose bounds hold its centre (the first such
 * water); the surface is the plane y = [level] and the buoyant force is −[density] · volume · gravity.
 */
class WaterVolume internal constructor(val world: PhysicsWorld, level: Float, minX: Float, minZ: Float, maxX: Float, maxZ: Float) {
    /** Height of the surface (m). Changing it wakes the bodies around it. */
    var level: Float = level
        set(v) { field = v; world.wakeWater(this) }
    var minX = minX; private set
    var minZ = minZ; private set
    var maxX = maxX; private set
    var maxZ = maxZ; private set
    /** kg/m³: 1000 for fresh water, 1025 for the sea. */
    var density = 1000f
        set(v) { require(v >= 0f) { "Water's density must not be negative" }; field = v; world.wakeWater(this) }
    /** How fast the water slows a body fully under it at low speed (1/s): its velocity relative to the water shrinks as e^(−drag·t). */
    var linearDrag = .5f
        set(v) { require(v >= 0f) { "Drag must not be negative" }; field = v }
    /**
     * The drag coefficient C_d of the quadratic drag ½ ρ C_d A v², A being the cross-section of a ball of the
     * body's volume (1 for blunt bodies, 0.47 for a ball): what stops a fast body going in.
     */
    var dragCoefficient = 1f
        set(v) { require(v >= 0f) { "Drag must not be negative" }; field = v }
    /** How fast the water slows a body's turning, fully under it (1/s). */
    var angularDrag = .5f
        set(v) { require(v >= 0f) { "Drag must not be negative" }; field = v }
    /**
     * The waves a body makes bobbing and rolling (1/s): they damp its vertical speed and its turning about
     * horizontal axes, most when it is half under (the waterline at its widest) and not at all once it is all
     * under. A floating crate stops bobbing within a few seconds; sliding along the water is not slowed by it.
     */
    var waveDamping = 6f
        set(v) { require(v >= 0f) { "Damping must not be negative" }; field = v }
    /** The water's own velocity (a river, a current): drag pulls bodies towards it. */
    var flow: Vec3 = Vec3.ZERO
        set(v) { field = v; world.wakeWater(this) }
    /** A disabled water holds nothing up. */
    var enabled = true
        set(v) { field = v; world.wakeWater(this) }
    /** Called after the step during which a body touched the water, coming from out of it. */
    var onEnter: ((WaterEntry) -> Unit)? = null
    /** Called after the step during which a body left the water altogether. */
    var onExit: ((Body) -> Unit)? = null

    /** Sets the horizontal bounds (infinite by default). */
    fun bounds(minX: Float, minZ: Float, maxX: Float, maxZ: Float) {
        require(minX <= maxX && minZ <= maxZ) { "A water's bounds need min ≤ max" }
        this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ
        world.wakeWater(this)
    }

    /** Whether (x, z) is within the bounds. */
    fun covers(x: Float, z: Float) = x >= minX && x <= maxX && z >= minZ && z <= maxZ
}

/**
 * A body that touched a water during the last step, coming from out of it: [point] is where (on the surface,
 * above the centre of what first went under), [verticalSpeed] how fast it was going up (negative: down) just
 * before. For splashes, sounds and ripples.
 */
class WaterEntry(val body: Body, val water: WaterVolume, val point: Vec3, val verticalSpeed: Float)

/** Adds a water whose surface is at [level], within the given horizontal bounds (everywhere by default). */
fun PhysicsWorld.addWater(level: Float, minX: Float = Float.NEGATIVE_INFINITY, minZ: Float = Float.NEGATIVE_INFINITY,
                          maxX: Float = Float.POSITIVE_INFINITY, maxZ: Float = Float.POSITIVE_INFINITY, density: Float = 1000f): WaterVolume {
    require(minX <= maxX && minZ <= maxZ) { "A water's bounds need min ≤ max" }
    return WaterVolume(this, level, minX, minZ, maxX, maxZ).also { it.density = density; waters.add(it) }
}

/** Takes [water] out of the world: what floated in it falls. */
fun PhysicsWorld.removeWater(water: WaterVolume) { if (waters.remove(water)) wakeWater(water) }

/** The waters of the world. */
val PhysicsWorld.waterList: List<WaterVolume> get() = waters

/**
 * The volume of [shape] at [pose] below the plane y = [level], and that volume times its centroid, added to
 * [acc] (volume, then Σ v·x, Σ v·y, Σ v·z). Nothing allocated: [sc] holds the scratch.
 */
internal fun submerged(shape: Shape, pose: Pose, level: Float, sc: BuoyancyScratch, acc: FloatArray) {
    when (shape) {
        is Sphere -> {
            val r = shape.radius
            val h = (level - (pose.py - r)).coerceIn(0f, 2f * r)          // depth of the cap
            if (h <= 0f) return
            val v = (PI.toFloat() * h * h * (3f * r - h) / 3f)
            // The cap's centroid lies 3(2r − h)² / (4(3r − h)) below the centre.
            val below = 3f * (2f * r - h) * (2f * r - h) / (4f * (3f * r - h))
            acc[0] += v; acc[1] += v * pose.px; acc[2] += v * (pose.py - below); acc[3] += v * pose.pz
        }
        is Box -> { sc.box.setBox(UNIT_BOX, shape.halfX, shape.halfY, shape.halfZ); polytopeBelow(sc.box, shape.volume, pose, level, sc, acc) }
        is ConvexHull -> polytopeBelow(shape.poly, shape.volume, pose, level, sc, acc)
        is Capsule -> capsuleBelow(shape, pose, level, acc)
        is Compound -> for (i in 0 until shape.count) submerged(shape.shapes[i], sc.part.setPart(pose, shape, i), level, sc, acc)
    }
}

/** The part of a polytope below the plane: each face clipped to it, as tetrahedra from a point on the plane (the cut face then adds nothing). */
private fun polytopeBelow(h: Polytope, volume: Float, pose: Pose, level: Float, sc: BuoyancyScratch, acc: FloatArray) {
    val n = h.vertexCount
    if (sc.v.size < 3 * n) sc.v = FloatArray(3 * n)
    val w = sc.v
    // Vertices about the point (px, level, pz) on the surface, in world axes.
    val dy = pose.py - level
    var above = 0; var below = 0
    for (i in 0 until n) {
        val x = h.v[3 * i]; val y = h.v[3 * i + 1]; val z = h.v[3 * i + 2]
        w[3 * i] = pose.xx * x + pose.yx * y + pose.zx * z
        w[3 * i + 1] = dy + pose.xy * x + pose.yy * y + pose.zy * z
        w[3 * i + 2] = pose.xz * x + pose.yz * y + pose.zz * z
        if (w[3 * i + 1] > 0f) above++ else below++
    }
    if (below == 0) return
    if (above == 0) { acc[0] += volume; acc[1] += volume * pose.px; acc[2] += volume * pose.py; acc[3] += volume * pose.pz; return }
    var vol = 0f; var mx = 0f; var my = 0f; var mz = 0f
    val p = sc.poly
    for (f in 0 until h.faceCount) {
        // The face clipped to y ≤ 0 (Sutherland–Hodgman).
        p.size = 0
        val s = h.faceStart[f]; val e = h.faceStart[f + 1]
        for (k in s until e) {
            val a = h.loop[k]; val b = h.loop[if (k + 1 == e) s else k + 1]
            val ax = w[3 * a]; val ay = w[3 * a + 1]; val az = w[3 * a + 2]
            val bx = w[3 * b]; val by = w[3 * b + 1]; val bz = w[3 * b + 2]
            if (ay <= 0f) p.add(ax, ay, az)
            if ((ay < 0f && by > 0f) || (ay > 0f && by < 0f)) { val t = ay / (ay - by); p.add(ax + (bx - ax) * t, 0f, az + (bz - az) * t) }
        }
        if (p.size < 3) continue
        val x0 = p.x[0]; val y0 = p.y[0]; val z0 = p.z[0]
        for (k in 1 until p.size - 1) {
            val x1 = p.x[k]; val y1 = p.y[k]; val z1 = p.z[k]; val x2 = p.x[k + 1]; val y2 = p.y[k + 1]; val z2 = p.z[k + 1]
            val d = (x0 * (y1 * z2 - z1 * y2) - y0 * (x1 * z2 - z1 * x2) + z0 * (x1 * y2 - y1 * x2)) / 6f
            vol += d; mx += d * (x0 + x1 + x2) * .25f; my += d * (y0 + y1 + y2) * .25f; mz += d * (z0 + z1 + z2) * .25f
        }
    }
    if (vol <= 0f) return
    acc[0] += vol; acc[1] += mx + vol * pose.px; acc[2] += my + vol * level; acc[3] += mz + vol * pose.pz
}

private const val SLICES = 24

/**
 * A capsule below the plane, slice by slice across its axis: each slice a disc (narrower over the rounded
 * ends) cut by the surface, an exact circular segment (area and centroid), at the slice's middle; the sum is
 * scaled so that the capsule under water is exactly its volume. Smooth in the pose, so it floats still.
 */
private fun capsuleBelow(c: Capsule, pose: Pose, level: Float, acc: FloatArray) {
    val r = c.radius; val hh = c.halfHeight
    val ax = pose.yx; val ay = pose.yy; val az = pose.yz              // the axis
    // Fully above or below: nothing to cut.
    val reach = abs(ay) * hh + r
    if (pose.py - reach >= level) return
    if (pose.py + reach <= level) { val v = c.volume; acc[0] += v; acc[1] += v * pose.px; acc[2] += v * pose.py; acc[3] += v * pose.pz; return }
    // The direction in each disc's plane that climbs fastest, and how fast.
    var ex = -ay * ax; var ey = 1f - ay * ay; var ez = -ay * az
    val k = sqrt(max(0f, ey))
    if (k > 1e-6f) { ex /= k; ey /= k; ez /= k }
    val total = hh + r; val ds = 2f * total / SLICES
    var full = 0f; var vol = 0f; var mx = 0f; var my = 0f; var mz = 0f
    for (j in 0 until SLICES) {
        val s = -total + (j + .5f) * ds
        val out = abs(s) - hh
        val rho2 = if (out <= 0f) r * r else max(0f, r * r - out * out)
        if (rho2 <= 0f) continue
        val rho = sqrt(rho2)
        full += PI.toFloat() * rho2
        val cx = pose.px + ax * s; val cy = pose.py + ay * s; val cz = pose.pz + az * s
        val depth = level - cy                                        // how far the disc's centre is under water
        val area: Float; val off: Float                               // the segment's area, its centroid along e
        if (k <= 1e-6f) { if (depth > 0f) { area = PI.toFloat() * rho2; off = 0f } else continue }
        else {
            val d = depth / k
            if (d <= -rho) continue
            if (d >= rho) { area = PI.toFloat() * rho2; off = 0f }
            else {
                val root = sqrt(rho2 - d * d)
                area = rho2 * acos((-d / rho).coerceIn(-1f, 1f)) + d * root
                off = if (area > 0f) -(2f / 3f) * root * root * root / area else 0f
            }
        }
        vol += area; mx += area * (cx + ex * off); my += area * (cy + ey * off); mz += area * (cz + ez * off)
    }
    if (vol <= 0f || full <= 0f) return
    val v = vol / full * c.volume
    acc[0] += v; acc[1] += v * mx / vol; acc[2] += v * my / vol; acc[3] += v * mz / vol
}

/** Scratch of the buoyancy (one per world: it runs on the stepping thread). */
internal class BuoyancyScratch {
    val box = boxPolytope()
    var v = FloatArray(3 * 32)
    val poly = Poly3()
    val part = Pose()
    val pose = Pose()
    val acc = FloatArray(4)
}

/** A clipped face, growable. */
internal class Poly3 {
    var x = FloatArray(16); var y = FloatArray(16); var z = FloatArray(16)
    var size = 0
    fun add(px: Float, py: Float, pz: Float) {
        if (size == x.size) { x = x.copyOf(2 * size); y = y.copyOf(2 * size); z = z.copyOf(2 * size) }
        x[size] = px; y[size] = py; z[size] = pz; size++
    }
}

/** The submerged fraction from a volume computed in floats: all under reads 1 exactly (not 0.99999994). */
internal fun fractionOf(v: Float, volume: Float) = if (v >= volume * .99999f) 1f else max(0f, v / volume)
