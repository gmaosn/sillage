package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a ray met: [distance] along the ray, the [point] and surface [normal], then the [body] or the mesh [material]. */
class RayHit(val distance: Float, val point: Vec3, val normal: Vec3, val body: Body?, val material: Material?)

/** Pose of body [i] from its current position and rotation (queries run between steps). */
internal fun PhysicsWorld.poseOf(i: Int): Pose = poseOf(Vec3(px[i], py[i], pz[i]), Quat(qx[i], qy[i], qz[i], qw[i]))

/**
 * Casts a ray from [origin] along [direction] up to [maxDistance]. With [radius] > 0 the ray is a
 * sphere sweep against bodies (meshes still take the bare ray). [filter] skips bodies; [meshes] = false
 * ignores static meshes. A ray starting inside a body hits it at distance 0.
 */
fun PhysicsWorld.raycast(origin: Vec3, direction: Vec3, maxDistance: Float, radius: Float = 0f,
                         meshes: Boolean = true, filter: ((Body) -> Boolean)? = null): RayHit? {
    val d = direction.normalized()
    var best = maxDistance; var hit: RayHit? = null
    for (i in 0 until count) {
        val shape = shapes[i]!!
        val c = Vec3(px[i], py[i], pz[i])
        // Quick rejection by the bounding sphere.
        val reach = shape.boundingRadius + radius
        val m = (c - origin) dot d
        if (m < -reach || m > best + reach) continue
        if ((c - origin - d * m).lengthSquared() > reach * reach) continue
        if (filter != null && !filter(Body(this, i))) continue
        val pose = poseOf(i)
        val r = when (shape) {
            is Sphere -> raySphere(origin, d, c, shape.radius + radius)
            is Capsule -> rayCapsule(origin, d, pose.p - pose.ay * shape.halfHeight, pose.p + pose.ay * shape.halfHeight, shape.radius + radius)
            is Box -> rayBox(origin, d, pose, shape.halfX + radius, shape.halfY + radius, shape.halfZ + radius)
        } ?: continue
        if (r.first < best) { best = r.first; hit = RayHit(r.first, origin + d * r.first, r.second, Body(this, i), null) }
    }
    if (meshes) {
        val stack = IntList()
        for (mesh in this.meshes) {
            val t = mesh.raycast(origin.x, origin.y, origin.z, d.x, d.y, d.z, best, stack)
            if (t >= 0f && t <= best) {
                val k = mesh.hitTriangle
                best = t
                hit = RayHit(t, origin + d * t, Vec3(mesh.n[3 * k], mesh.n[3 * k + 1], mesh.n[3 * k + 2]), null, mesh.palette[mesh.material[k]])
            }
        }
    }
    return hit
}

/** Height of the first surface below (x, [fromY], z) within [depth] metres, or null. */
fun PhysicsWorld.groundBelow(x: Float, z: Float, fromY: Float = 1000f, depth: Float = 2000f, filter: ((Body) -> Boolean)? = null): RayHit? =
    raycast(Vec3(x, fromY, z), Vec3(0f, -1f, 0f), depth, filter = filter)

private fun raySphere(o: Vec3, d: Vec3, c: Vec3, r: Float): Pair<Float, Vec3>? {
    val m = o - c
    val c0 = (m dot m) - r * r
    if (c0 <= 0f) return 0f to (if (m.lengthSquared() > 0f) m.normalized() else -d)
    val b = m dot d
    if (b > 0f) return null
    val disc = b * b - c0
    if (disc < 0f) return null
    val t = -b - sqrt(disc)
    return t to (o + d * t - c).normalized()
}

private fun rayCapsule(o: Vec3, d: Vec3, a: Vec3, b: Vec3, r: Float): Pair<Float, Vec3>? {
    val ab = b - a; val len = ab.length()
    if (len < 1e-6f) return raySphere(o, d, a, r)
    val u = ab / len
    // Origin inside: distance to the segment below the radius.
    val s0 = ((o - a) dot u).coerceIn(0f, len)
    if ((o - (a + u * s0)).lengthSquared() <= r * r) return 0f to -d
    var best: Pair<Float, Vec3>? = null
    // Side of the cylinder.
    val w = o - a
    val dp = d - u * (d dot u); val wp = w - u * (w dot u)
    val qa = dp dot dp; val qb = 2f * (wp dot dp); val qc = (wp dot wp) - r * r
    if (qa > 1e-12f) {
        val disc = qb * qb - 4f * qa * qc
        if (disc >= 0f) {
            val t = (-qb - sqrt(disc)) / (2f * qa)
            val s = (w + d * t) dot u
            if (t >= 0f && s in 0f..len) best = t to (wp + dp * t).normalized()
        }
    }
    for (cap in listOf(raySphere(o, d, a, r), raySphere(o, d, b, r))) if (cap != null && (best == null || cap.first < best.first)) best = cap
    return best
}

private fun rayBox(o: Vec3, d: Vec3, pose: Pose, hx: Float, hy: Float, hz: Float): Pair<Float, Vec3>? {
    val lo = pose.toLocal(o)
    val ld = Vec3(d dot pose.ax, d dot pose.ay, d dot pose.az)
    val h = floatArrayOf(hx, hy, hz)
    var t0 = 0f; var t1 = Float.MAX_VALUE; var axis = -1; var sign = 0f
    for (k in 0..2) {
        val ok = lo[k]; val dk = ld[k]
        if (abs(dk) < 1e-9f) { if (ok < -h[k] || ok > h[k]) return null; continue }
        var ta = (-h[k] - ok) / dk; var tb = (h[k] - ok) / dk
        var s = -1f
        if (ta > tb) { val x = ta; ta = tb; tb = x; s = 1f }
        if (ta > t0) { t0 = ta; axis = k; sign = s }
        t1 = min(t1, tb)
        if (t0 > t1) return null
    }
    if (axis < 0) return 0f to -d                       // origin inside the box
    return t0 to pose.axis(axis) * sign
}

/** Contacts of [shape] placed at [pose] with the world, normals pointing from the obstacle to the shape. */
internal class Touches {
    val points = ArrayList<Vec3>(); val normals = ArrayList<Vec3>(); val separations = ArrayList<Float>()
    /** Body touched, −1 for a static mesh. */
    val bodies = ArrayList<Int>(); val materials = ArrayList<Material?>()
    val size get() = points.size
    fun clear() { points.clear(); normals.clear(); separations.clear(); bodies.clear(); materials.clear() }
}

internal fun PhysicsWorld.touching(shape: Shape, pose: Pose, margin: Float, exclude: Int, out: Touches, meshes: Boolean = true) {
    out.clear()
    val scratch = ContactPoints()
    val reach = shape.boundingRadius + margin
    fun take(body: Int, material: Material?) {
        for (k in 0 until scratch.count) {
            out.points.add(Vec3(scratch.x[k], scratch.y[k], scratch.z[k])); out.normals.add(Vec3(scratch.nx[k], scratch.ny[k], scratch.nz[k]))
            out.separations.add(scratch.sep[k]); out.bodies.add(body); out.materials.add(material)
        }
    }
    for (i in 0 until count) {
        if (i == exclude) continue
        val r = shapes[i]!!.boundingRadius + reach
        val dx = px[i] - pose.p.x; val dy = py[i] - pose.p.y; val dz = pz[i] - pose.p.z
        if (dx * dx + dy * dy + dz * dz > r * r) continue
        scratch.clear()
        collide(shapes[i]!!, poseOf(i), shape, pose, margin, scratch)
        take(i, null)
    }
    if (!meshes) return
    val found = IntList(); val stack = IntList()
    for (mesh in this.meshes) {
        found.clear()
        mesh.query(pose.p.x - reach, pose.p.y - reach, pose.p.z - reach, pose.p.x + reach, pose.p.y + reach, pose.p.z + reach, found, stack)
        for (f in 0 until found.size) {
            val t = found[f]; val v = mesh.v; val n = mesh.n
            val tri = Tri(Vec3(v[9 * t], v[9 * t + 1], v[9 * t + 2]), Vec3(v[9 * t + 3], v[9 * t + 4], v[9 * t + 5]),
                Vec3(v[9 * t + 6], v[9 * t + 7], v[9 * t + 8]), Vec3(n[3 * t], n[3 * t + 1], n[3 * t + 2]), mesh.edges[t])
            scratch.clear()
            collideTriangle(tri, shape, pose, margin, scratch)
            take(-1, mesh.palette[mesh.material[t]])
        }
    }
}
