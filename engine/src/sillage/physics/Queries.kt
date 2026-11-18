package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a ray met: [distance] along the ray, the [point] and surface [normal], then the [body] or the mesh [material]. */
/** What a ray or a sphere sweep met: [distance] travelled, [point] where it touches (on the surface), [normal] there. */
class RayHit(val distance: Float, val point: Vec3, val normal: Vec3, val body: Body?, val material: Material?)

/** Pose of body [i] from its current position and rotation (queries run between steps). */
internal fun PhysicsWorld.poseOf(i: Int): Pose = poseOf(Vec3(px[i], py[i], pz[i]), Quat(qx[i], qy[i], qz[i], qw[i]))

/**
 * Casts a ray from [origin] along [direction] up to [maxDistance]. With [radius] > 0 it sweeps a sphere
 * instead (against boxes, the box grown by the radius). [filter] skips bodies; [meshes] = false ignores
 * static meshes, whose triangles are one-sided (seen from the front). Starting inside gives distance 0.
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
        if (r.first < best) { best = r.first; hit = RayHit(r.first, origin + d * r.first - r.second * radius, r.second, Body(this, i), null) }
    }
    if (meshes && radius > 0f) {
        val stack = IntList(); val found = IntList()
        val e = origin + d * best
        for (mesh in this.meshes) {
            found.clear()
            mesh.query(min(origin.x, e.x) - radius, min(origin.y, e.y) - radius, min(origin.z, e.z) - radius,
                max(origin.x, e.x) + radius, max(origin.y, e.y) + radius, max(origin.z, e.z) + radius, found, stack)
            val v = mesh.v; val n = mesh.n
            for (f in 0 until found.size) {
                val t = found[f]; val o = 9 * t
                val r = sweepTriangle(origin, d, radius, Vec3(v[o], v[o + 1], v[o + 2]), Vec3(v[o + 3], v[o + 4], v[o + 5]),
                    Vec3(v[o + 6], v[o + 7], v[o + 8]), Vec3(n[3 * t], n[3 * t + 1], n[3 * t + 2]), best) ?: continue
                if (r.first <= best) { best = r.first; hit = RayHit(r.first, origin + d * r.first - r.second * radius, r.second, null, mesh.palette[mesh.material[t]]) }
            }
        }
    } else if (meshes) {
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

/**
 * A sphere of radius [r] swept from [o] along the unit direction [d] against the triangle (a, b, c) of
 * unit normal [n], seen from its front: distance travelled and contact normal, or null beyond [maxT].
 * The sphere meets the face (the plane raised by r) or else an edge or a corner (capsules around edges).
 */
internal fun sweepTriangle(o: Vec3, d: Vec3, r: Float, a: Vec3, b: Vec3, c: Vec3, n: Vec3, maxT: Float): Pair<Float, Vec3>? {
    val dist = (o - a) dot n
    val dn = d dot n
    if (dist < 0f || (dist >= r && dn >= 0f)) return null          // behind, or moving away
    if (dist < r) {
        if (inside(o - n * dist, a, b, c, n)) return 0f to n         // already touching the face
    } else {
        val t = (r - dist) / dn
        if (t > maxT) return null
        if (inside(o + d * t - n * r, a, b, c, n)) return t to n     // the face comes first when hit inside
    }
    var best: Pair<Float, Vec3>? = null
    for ((p, q) in arrayOf(a to b, b to c, c to a)) {
        val h = rayCapsule(o, d, p, q, r) ?: continue
        if (h.first <= maxT && (best == null || h.first < best.first)) best = h
    }
    return best
}

private fun inside(p: Vec3, a: Vec3, b: Vec3, c: Vec3, n: Vec3): Boolean {
    val s1 = ((b - a) cross (p - a)) dot n; val s2 = ((c - b) cross (p - b)) dot n; val s3 = ((a - c) cross (p - c)) dot n
    return (s1 >= 0f && s2 >= 0f && s3 >= 0f) || (s1 <= 0f && s2 <= 0f && s3 <= 0f)
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

/** What a shape placed somewhere touches: see [PhysicsWorld.overlaps]. */
class Overlap(val body: Body?, val material: Material?, val point: Vec3, val normal: Vec3, val separation: Float)

/**
 * Contacts of [shape] placed at [position] and [rotation] with the world, without adding it: bodies
 * (except [exclude]) and static meshes (null [Overlap.body]) closer than [margin]. Normals point from
 * the obstacle to the shape; a negative separation is an overlap. Mesh triangles count only from the
 * front, as for bodies.
 */
fun PhysicsWorld.overlaps(shape: Shape, position: Vec3, rotation: Quat = Quat.IDENTITY, margin: Float = 0f, exclude: Body? = null): List<Overlap> {
    val t = Touches()
    touching(shape, poseOf(position, rotation.normalized()), margin, exclude?.id ?: -1, t)
    return List(t.size) { k -> Overlap(if (t.bodies[k] >= 0) Body(this, t.bodies[k]) else null, t.materials[k], t.points[k], t.normals[k], t.separations[k]) }
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
            // Triangles are one-sided: a shape whose centre is behind one (inside a closed mesh such as a
            // pier, under a floor) does not touch it, or it would be thrown out through the far side.
            if ((pose.p.x - v[9 * t]) * n[3 * t] + (pose.p.y - v[9 * t + 1]) * n[3 * t + 1] + (pose.p.z - v[9 * t + 2]) * n[3 * t + 2] < 0f) continue
            val tri = Tri(Vec3(v[9 * t], v[9 * t + 1], v[9 * t + 2]), Vec3(v[9 * t + 3], v[9 * t + 4], v[9 * t + 5]),
                Vec3(v[9 * t + 6], v[9 * t + 7], v[9 * t + 8]), Vec3(n[3 * t], n[3 * t + 1], n[3 * t + 2]), mesh.edges[t])
            scratch.clear()
            collideTriangle(tri, shape, pose, margin, scratch)
            take(-1, mesh.palette[mesh.material[t]])
        }
    }
}
