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
 * instead (exactly: against a box, the box grown by the radius with rounded edges and corners). [filter] skips bodies; [meshes] = false ignores
 * static meshes, whose triangles are one-sided (seen from the front). Starting inside gives distance 0.
 */
fun PhysicsWorld.raycast(origin: Vec3, direction: Vec3, maxDistance: Float, radius: Float = 0f,
                         meshes: Boolean = true, filter: ((Body) -> Boolean)? = null): RayHit? {
    val d = direction.normalized()
    var best = maxDistance; var hit: RayHit? = null; var hitBody = Int.MAX_VALUE
    fun consider(i: Int) {
        if (removed[i]) return
        val shape = shapes[i]!!
        // Quick rejection by the bounding sphere, on scalars (no vector for the bodies far from the ray).
        val reach = shape.boundingRadius + radius
        val cx = px[i] - origin.x; val cy = py[i] - origin.y; val cz = pz[i] - origin.z
        val m = cx * d.x + cy * d.y + cz * d.z
        if (m < -reach || m > best + reach) return
        val ex = cx - d.x * m; val ey = cy - d.y * m; val ez = cz - d.z * m
        if (ex * ex + ey * ey + ez * ez > reach * reach) return
        if (filter != null && !filter(Body(this, i))) return
        val c = Vec3(px[i], py[i], pz[i])
        val pose = poseOf(i)
        val r = when (shape) {
            is Sphere -> raySphere(origin, d, c, shape.radius + radius)
            is Capsule -> rayCapsule(origin, d, pose.p - pose.ay * shape.halfHeight, pose.p + pose.ay * shape.halfHeight, shape.radius + radius)
            is Box -> if (radius > 0f) sweepBox(origin, d, pose, shape, radius) else rayBox(origin, d, pose, shape.halfX, shape.halfY, shape.halfZ)
        } ?: return
        // Equal distances: the lowest body wins, as when every body is tried in order.
        if (r.first < best || (r.first == best && hit != null && i < hitBody)) {
            best = r.first; hitBody = i; hit = RayHit(r.first, origin + d * r.first - r.second * radius, r.second, Body(this, i), null)
        }
    }
    val grid = if (radius <= QueryGrid.PAD) gridFor() else null
    if (grid == null) for (i in 0 until count) consider(i)
    else {
        // Walk the cells along the ray (Amanatides–Woo), within the grid's bounds, until no closer body can come.
        grid.begin()
        val cand = queryBodies; cand.clear()
        for (k in 0 until grid.large.size) consider(grid.large[k])
        var t0 = 0f; var t1 = best
        for (axis in 0..2) {
            val o = when (axis) { 0 -> origin.x; 1 -> origin.y; else -> origin.z }
            val v = when (axis) { 0 -> d.x; 1 -> d.y; else -> d.z }
            val lo = when (axis) { 0 -> grid.minX; 1 -> grid.minY; else -> grid.minZ }
            val hi = when (axis) { 0 -> grid.maxX; 1 -> grid.maxY; else -> grid.maxZ }
            if (abs(v) < 1e-12f) { if (o < lo || o > hi) t1 = -1f }
            else { val a = (lo - o) / v; val b = (hi - o) / v; t0 = max(t0, min(a, b)); t1 = min(t1, max(a, b)) }
        }
        if (t0 <= t1) {
            val s = grid.size
            val sx = origin.x + d.x * t0; val sy = origin.y + d.y * t0; val sz = origin.z + d.z * t0
            var x = grid.cell(sx); var y = grid.cell(sy); var z = grid.cell(sz)
            val stepX = if (d.x > 0f) 1 else -1; val stepY = if (d.y > 0f) 1 else -1; val stepZ = if (d.z > 0f) 1 else -1
            fun boundary(c: Int, step: Int) = (c - 4096 + (if (step > 0) 1 else 0)) * s
            var tx = if (abs(d.x) < 1e-12f) Float.MAX_VALUE else (boundary(x, stepX) - origin.x) / d.x
            var ty = if (abs(d.y) < 1e-12f) Float.MAX_VALUE else (boundary(y, stepY) - origin.y) / d.y
            var tz = if (abs(d.z) < 1e-12f) Float.MAX_VALUE else (boundary(z, stepZ) - origin.z) / d.z
            val dx = if (abs(d.x) < 1e-12f) Float.MAX_VALUE else s / abs(d.x)
            val dy = if (abs(d.y) < 1e-12f) Float.MAX_VALUE else s / abs(d.y)
            val dz = if (abs(d.z) < 1e-12f) Float.MAX_VALUE else s / abs(d.z)
            while (true) {
                cand.clear(); grid.collect(x, y, z, cand); cand.sort()
                for (k in 0 until cand.size) consider(cand[k])
                val next = min(tx, min(ty, tz))
                if (next > best || next > t1) break
                if (tx <= ty && tx <= tz) { x += stepX; tx += dx } else if (ty <= tz) { y += stepY; ty += dy } else { z += stepZ; tz += dz }
            }
        }
    }
    if (meshes && radius > 0f) {
        val stack = queryStack; val found = queryFound
        val e = origin + d * best
        for (mesh in this.meshes) {
            found.clear()
            mesh.query(min(origin.x, e.x) - radius, min(origin.y, e.y) - radius, min(origin.z, e.z) - radius,
                max(origin.x, e.x) + radius, max(origin.y, e.y) + radius, max(origin.z, e.z) + radius, found, stack)
            val tris = mesh.tris
            for (f in 0 until found.size) {
                val t = found[f]; val tr = tris[t]
                val r = sweepTriangle(origin, d, radius, tr.a, tr.b, tr.c, tr.n, best) ?: continue
                if (r.first <= best) { best = r.first; hit = RayHit(r.first, origin + d * r.first - r.second * radius, r.second, null, mesh.palette[mesh.material[t]]) }
            }
        }
    } else if (meshes) {
        val stack = queryStack
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

/**
 * A sphere of radius [r] swept against a box: the box grown by r, with rounded edges and corners (the
 * Minkowski sum). The grown box tells where a face is hit; off the faces, the twelve edges as capsules.
 */
private fun sweepBox(o: Vec3, d: Vec3, pose: Pose, b: Box, r: Float): Pair<Float, Vec3>? {
    val h = floatArrayOf(b.halfX, b.halfY, b.halfZ)
    val lo = pose.toLocal(o)
    // Starting inside the rounded box: distance from the box below the radius.
    val qx = max(abs(lo.x) - h[0], 0f); val qy = max(abs(lo.y) - h[1], 0f); val qz = max(abs(lo.z) - h[2], 0f)
    if (qx * qx + qy * qy + qz * qz <= r * r) return 0f to -d
    val grown = rayBox(o, d, pose, h[0] + r, h[1] + r, h[2] + r) ?: return null
    val p = pose.toLocal(o + d * grown.first)
    var outside = 0
    for (k in 0..2) if (abs(p[k]) > h[k] + 1e-5f) outside++
    if (outside <= 1 && grown.first > 0f) return grown            // on a face (only the hit axis is beyond the box)
    var best: Pair<Float, Vec3>? = null
    for (axis in 0..2) {
        val u = (axis + 1) % 3; val v = (axis + 2) % 3
        for (su in floatArrayOf(-1f, 1f)) for (sv in floatArrayOf(-1f, 1f)) {
            val e0 = FloatArray(3); val e1 = FloatArray(3)
            e0[axis] = -h[axis]; e1[axis] = h[axis]; e0[u] = su * h[u]; e1[u] = su * h[u]; e0[v] = sv * h[v]; e1[v] = sv * h[v]
            val hit = rayCapsule(o, d, pose.toWorld(Vec3(e0[0], e0[1], e0[2])), pose.toWorld(Vec3(e1[0], e1[1], e1[2])), r) ?: continue
            if (best == null || hit.first < best.first) best = hit
        }
    }
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
    return List(t.size) { k -> Overlap(if (t.body[k] >= 0) Body(this, t.body[k]) else null, t.material[k], t.point(k), t.normal(k), t.sep[k]) }
}

/** Contacts of [shape] placed at [pose] with the world, normals pointing from the obstacle to the shape. */
internal class Touches {
    var size = 0; private set
    private var cap = 16
    var x = FloatArray(cap); var y = FloatArray(cap); var z = FloatArray(cap)
    var nx = FloatArray(cap); var ny = FloatArray(cap); var nz = FloatArray(cap)
    var sep = FloatArray(cap)
    /** Body touched, −1 for a static mesh. */
    var body = IntArray(cap); var material = arrayOfNulls<Material>(cap)
    fun clear() { size = 0 }
    fun point(k: Int) = Vec3(x[k], y[k], z[k])
    fun normal(k: Int) = Vec3(nx[k], ny[k], nz[k])
    fun add(src: ContactPoints, b: Int, m: Material?) {
        for (k in 0 until src.count) {
            if (size == cap) {
                cap *= 2
                x = x.copyOf(cap); y = y.copyOf(cap); z = z.copyOf(cap); nx = nx.copyOf(cap); ny = ny.copyOf(cap); nz = nz.copyOf(cap)
                sep = sep.copyOf(cap); body = body.copyOf(cap); material = material.copyOf(cap)
            }
            x[size] = src.x[k]; y[size] = src.y[k]; z[size] = src.z[k]
            nx[size] = src.nx[k]; ny[size] = src.ny[k]; nz[size] = src.nz[k]
            sep[size] = src.sep[k]; body[size] = b; material[size] = m
            size++
        }
    }
}

internal fun PhysicsWorld.touching(shape: Shape, pose: Pose, margin: Float, exclude: Int, out: Touches, meshes: Boolean = true) {
    out.clear()
    val scratch = queryScratch
    val reach = shape.boundingRadius + margin
    val grid = gridFor()
    val cand = queryBodies
    if (grid != null) grid.bodiesIn(pose.p.x - reach, pose.p.y - reach, pose.p.z - reach, pose.p.x + reach, pose.p.y + reach, pose.p.z + reach, cand)
    val n = if (grid != null) cand.size else count
    for (k in 0 until n) {
        val i = if (grid != null) cand[k] else k
        if (i == exclude || removed[i]) continue
        val r = shapes[i]!!.boundingRadius + reach
        val dx = px[i] - pose.p.x; val dy = py[i] - pose.p.y; val dz = pz[i] - pose.p.z
        if (dx * dx + dy * dy + dz * dz > r * r) continue
        scratch.clear()
        collide(shapes[i]!!, poseOf(i), shape, pose, margin, scratch)
        out.add(scratch, i, null)
    }
    if (!meshes) return
    val found = queryFound; val stack = queryStack
    for (mesh in this.meshes) {
        found.clear()
        mesh.query(pose.p.x - reach, pose.p.y - reach, pose.p.z - reach, pose.p.x + reach, pose.p.y + reach, pose.p.z + reach, found, stack)
        for (f in 0 until found.size) {
            val t = found[f]; val v = mesh.v; val n = mesh.n
            // Triangles are one-sided: a shape whose centre is behind one (inside a closed mesh such as a
            // pier, under a floor) does not touch it, or it would be thrown out through the far side.
            if ((pose.p.x - v[9 * t]) * n[3 * t] + (pose.p.y - v[9 * t + 1]) * n[3 * t + 1] + (pose.p.z - v[9 * t + 2]) * n[3 * t + 2] < 0f) continue
            scratch.clear()
            collideTriangle(mesh.tris[t], shape, pose, margin, scratch)
            out.add(scratch, -1, mesh.palette[mesh.material[t]])
        }
    }
}
