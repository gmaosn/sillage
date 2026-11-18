package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a ray or a sphere sweep met: [distance] travelled, [point] where it touches (on the surface), [normal] there, then the [body] or the mesh [material]. */
class RayHit(val distance: Float, val point: Vec3, val normal: Vec3, val body: Body?, val material: Material?)

/** Pose of body [i] from its current position and rotation (queries run between steps), written to [into]. */
internal fun PhysicsWorld.poseOf(i: Int, into: Pose): Pose = into.set(px[i], py[i], pz[i], qx[i], qy[i], qz[i], qw[i])

/**
 * The state of one ray or sphere sweep, kept by the world (queries are not reentrant): the ray, the
 * nearest hit so far, and the scratch of the shape tests. Those return the distance of their hit, or
 * NaN for none, and write its unit normal to [n] at a given offset; the three floats after it are
 * theirs to use for what they call in turn.
 */
internal class RayQuery {
    var ox = 0f; var oy = 0f; var oz = 0f
    var dx = 0f; var dy = 0f; var dz = 0f
    var radius = 0f
    var best = 0f; var hit = false; var body = -1; var material: Material? = null
    var hx = 0f; var hy = 0f; var hz = 0f; var nx = 0f; var ny = 0f; var nz = 0f
    val n = FloatArray(12)
    val pose = Pose()
    val tri = Tri()

    fun keep(t: Float, at: Int, body: Int, material: Material?) {
        best = t; hit = true; this.body = body; this.material = material
        nx = n[at]; ny = n[at + 1]; nz = n[at + 2]
        hx = ox + dx * t - nx * radius; hy = oy + dy * t - ny * radius; hz = oz + dz * t - nz * radius
    }
}

/**
 * Casts a ray from [origin] along [direction] up to [maxDistance]. With [radius] > 0 it sweeps a sphere
 * instead (exactly: against a box, the box grown by the radius with rounded edges and corners). [filter] skips bodies; [meshes] = false ignores
 * static meshes, whose triangles are one-sided (seen from the front). Starting inside gives distance 0.
 */
fun PhysicsWorld.raycast(origin: Vec3, direction: Vec3, maxDistance: Float, radius: Float = 0f,
                         meshes: Boolean = true, filter: ((Body) -> Boolean)? = null): RayHit? {
    val r = ray
    r.ox = origin.x; r.oy = origin.y; r.oz = origin.z
    val dl = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
    if (dl == 0f) { r.dx = direction.x; r.dy = direction.y; r.dz = direction.z }
    else { r.dx = direction.x / dl; r.dy = direction.y / dl; r.dz = direction.z / dl }
    r.radius = radius; r.best = maxDistance; r.hit = false; r.body = -1; r.material = null
    val ox = r.ox; val oy = r.oy; val oz = r.oz; val dx = r.dx; val dy = r.dy; val dz = r.dz
    val grid = if (radius <= QueryGrid.PAD) gridFor() else null
    if (grid == null) for (i in 0 until count) consider(i, r, filter)
    else {
        // Walk the cells along the ray (Amanatides–Woo), within the grid's bounds, until no closer body can come.
        grid.begin()
        val cand = queryBodies; cand.clear()
        for (k in 0 until grid.large.size) consider(grid.large[k], r, filter)
        var t0 = 0f; var t1 = r.best
        for (axis in 0..2) {
            val o = when (axis) { 0 -> ox; 1 -> oy; else -> oz }
            val v = when (axis) { 0 -> dx; 1 -> dy; else -> dz }
            val lo = when (axis) { 0 -> grid.minX; 1 -> grid.minY; else -> grid.minZ }
            val hi = when (axis) { 0 -> grid.maxX; 1 -> grid.maxY; else -> grid.maxZ }
            if (abs(v) < 1e-12f) { if (o < lo || o > hi) t1 = -1f }
            else { val a = (lo - o) / v; val b = (hi - o) / v; t0 = max(t0, min(a, b)); t1 = min(t1, max(a, b)) }
        }
        if (t0 <= t1) {
            val s = grid.size
            var x = grid.cell(ox + dx * t0); var y = grid.cell(oy + dy * t0); var z = grid.cell(oz + dz * t0)
            val stepX = if (dx > 0f) 1 else -1; val stepY = if (dy > 0f) 1 else -1; val stepZ = if (dz > 0f) 1 else -1
            var tx = if (abs(dx) < 1e-12f) Float.MAX_VALUE else (boundary(x, stepX, s) - ox) / dx
            var ty = if (abs(dy) < 1e-12f) Float.MAX_VALUE else (boundary(y, stepY, s) - oy) / dy
            var tz = if (abs(dz) < 1e-12f) Float.MAX_VALUE else (boundary(z, stepZ, s) - oz) / dz
            val ddx = if (abs(dx) < 1e-12f) Float.MAX_VALUE else s / abs(dx)
            val ddy = if (abs(dy) < 1e-12f) Float.MAX_VALUE else s / abs(dy)
            val ddz = if (abs(dz) < 1e-12f) Float.MAX_VALUE else s / abs(dz)
            while (true) {
                cand.clear(); grid.collect(x, y, z, cand); cand.sort()
                for (k in 0 until cand.size) consider(cand[k], r, filter)
                val next = min(tx, min(ty, tz))
                if (next > r.best || next > t1) break
                if (tx <= ty && tx <= tz) { x += stepX; tx += ddx } else if (ty <= tz) { y += stepY; ty += ddy } else { z += stepZ; tz += ddz }
            }
        }
    }
    if (meshes && radius > 0f) {
        val stack = queryStack; val found = queryFound
        val ex = ox + dx * r.best; val ey = oy + dy * r.best; val ez = oz + dz * r.best
        for (mesh in this.meshes) {
            found.clear()
            mesh.query(min(ox, ex) - radius, min(oy, ey) - radius, min(oz, ez) - radius,
                max(ox, ex) + radius, max(oy, ey) + radius, max(oz, ez) + radius, found, stack)
            for (f in 0 until found.size) {
                val t = found[f]
                val h = sweepTriangle(r, r.tri.load(mesh, t), r.best, 0)
                if (h <= r.best) r.keep(h, 0, -1, mesh.palette[mesh.material[t]])
            }
        }
    } else if (meshes) {
        val stack = queryStack
        for (mesh in this.meshes) {
            val t = mesh.raycast(ox, oy, oz, dx, dy, dz, r.best, stack)
            if (t >= 0f && t <= r.best) {
                val k = mesh.hitTriangle
                r.best = t; r.hit = true; r.body = -1; r.material = mesh.palette[mesh.material[k]]
                r.hx = ox + dx * t; r.hy = oy + dy * t; r.hz = oz + dz * t
                r.nx = mesh.n[3 * k]; r.ny = mesh.n[3 * k + 1]; r.nz = mesh.n[3 * k + 2]
            }
        }
    }
    if (!r.hit) return null
    return RayHit(r.best, Vec3(r.hx, r.hy, r.hz), Vec3(r.nx, r.ny, r.nz), if (r.body >= 0) Body(this, r.body) else null, r.material)
}

private fun boundary(c: Int, step: Int, size: Float) = (c - 4096 + (if (step > 0) 1 else 0)) * size

/** Tests body [i] against the ray, keeping it if it is the nearest hit so far. */
private fun PhysicsWorld.consider(i: Int, r: RayQuery, filter: ((Body) -> Boolean)?) {
    if (removed[i]) return
    val shape = shapes[i]!!
    // Quick rejection by the bounding sphere.
    val reach = shape.boundingRadius + r.radius
    val cx = px[i] - r.ox; val cy = py[i] - r.oy; val cz = pz[i] - r.oz
    val m = cx * r.dx + cy * r.dy + cz * r.dz
    if (m < -reach || m > r.best + reach) return
    val ex = cx - r.dx * m; val ey = cy - r.dy * m; val ez = cz - r.dz * m
    if (ex * ex + ey * ey + ez * ez > reach * reach) return
    if (filter != null && !filter(Body(this, i))) return
    val pose = poseOf(i, r.pose)
    val t = when (shape) {
        is Sphere -> raySphere(r, pose.px, pose.py, pose.pz, shape.radius + r.radius, 0)
        is Capsule -> {
            val h = shape.halfHeight
            rayCapsule(r, pose.px - pose.yx * h, pose.py - pose.yy * h, pose.pz - pose.yz * h,
                pose.px + pose.yx * h, pose.py + pose.yy * h, pose.pz + pose.yz * h, shape.radius + r.radius, 0)
        }
        is Box -> if (r.radius > 0f) sweepBox(r, pose, shape, r.radius, 0) else rayBox(r, pose, shape.halfX, shape.halfY, shape.halfZ, 0)
    }
    // Equal distances: the lowest body wins, as when every body is tried in order.
    if (t < r.best || (t == r.best && r.hit && i < r.body)) r.keep(t, 0, i, null)
}

/**
 * A sphere of radius r.radius swept along the ray against the triangle [t], seen from its front: the
 * distance travelled (NaN if none within [maxT]), normal at [at]. The sphere meets the face (the plane
 * raised by the radius) or else an edge or a corner (capsules around the edges).
 */
private fun sweepTriangle(q: RayQuery, t: Tri, maxT: Float, at: Int): Float {
    val r = q.radius; val n = q.n
    val dist = (q.ox - t.ax) * t.nx + (q.oy - t.ay) * t.ny + (q.oz - t.az) * t.nz
    val dn = q.dx * t.nx + q.dy * t.ny + q.dz * t.nz
    if (dist < 0f || (dist >= r && dn >= 0f)) return Float.NaN          // behind, or moving away
    if (dist < r) {
        if (inside(q.ox - t.nx * dist, q.oy - t.ny * dist, q.oz - t.nz * dist, t)) {   // already touching the face
            n[at] = t.nx; n[at + 1] = t.ny; n[at + 2] = t.nz; return 0f
        }
    } else {
        val h = (r - dist) / dn
        if (h > maxT) return Float.NaN
        if (inside(q.ox + q.dx * h - t.nx * r, q.oy + q.dy * h - t.ny * r, q.oz + q.dz * h - t.nz * r, t)) {   // the face comes first when hit inside
            n[at] = t.nx; n[at + 1] = t.ny; n[at + 2] = t.nz; return h
        }
    }
    var best = Float.NaN
    for (j in 0..2) {
        val k = if (j == 2) 0 else j + 1
        val h = rayCapsule(q, t.vx(j), t.vy(j), t.vz(j), t.vx(k), t.vy(k), t.vz(k), r, at + 3)
        if (h <= maxT && (best.isNaN() || h < best)) { best = h; n[at] = n[at + 3]; n[at + 1] = n[at + 4]; n[at + 2] = n[at + 5] }
    }
    return best
}

private fun inside(px: Float, py: Float, pz: Float, t: Tri): Boolean {
    val s1 = side(t.ax, t.ay, t.az, t.bx, t.by, t.bz, px, py, pz, t)
    val s2 = side(t.bx, t.by, t.bz, t.cx, t.cy, t.cz, px, py, pz, t)
    val s3 = side(t.cx, t.cy, t.cz, t.ax, t.ay, t.az, px, py, pz, t)
    return (s1 >= 0f && s2 >= 0f && s3 >= 0f) || (s1 <= 0f && s2 <= 0f && s3 <= 0f)
}

/** ((b − a) × (p − a)) · n: on which side of edge a→b point p lies. */
private fun side(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, px: Float, py: Float, pz: Float, t: Tri): Float {
    val ex = bx - ax; val ey = by - ay; val ez = bz - az
    val wx = px - ax; val wy = py - ay; val wz = pz - az
    return (ey * wz - ez * wy) * t.nx + (ez * wx - ex * wz) * t.ny + (ex * wy - ey * wx) * t.nz
}

/** Height of the first surface below (x, [fromY], z) within [depth] metres, or null. */
fun PhysicsWorld.groundBelow(x: Float, z: Float, fromY: Float = 1000f, depth: Float = 2000f, filter: ((Body) -> Boolean)? = null): RayHit? =
    raycast(Vec3(x, fromY, z), Vec3(0f, -1f, 0f), depth, filter = filter)

/** Writes the unit vector of (x, y, z) to n[at] (the zero vector stays zero). */
private fun unit(x: Float, y: Float, z: Float, n: FloatArray, at: Int) {
    val l = sqrt(x * x + y * y + z * z)
    if (l == 0f) { n[at] = x; n[at + 1] = y; n[at + 2] = z } else { n[at] = x / l; n[at + 1] = y / l; n[at + 2] = z / l }
}

private fun backwards(q: RayQuery, at: Int): Float { q.n[at] = -q.dx; q.n[at + 1] = -q.dy; q.n[at + 2] = -q.dz; return 0f }

private fun raySphere(q: RayQuery, cx: Float, cy: Float, cz: Float, r: Float, at: Int): Float {
    val mx = q.ox - cx; val my = q.oy - cy; val mz = q.oz - cz
    val m2 = mx * mx + my * my + mz * mz
    val c0 = m2 - r * r
    if (c0 <= 0f) return if (m2 > 0f) { unit(mx, my, mz, q.n, at); 0f } else backwards(q, at)
    val b = mx * q.dx + my * q.dy + mz * q.dz
    if (b > 0f) return Float.NaN
    val disc = b * b - c0
    if (disc < 0f) return Float.NaN
    val t = -b - sqrt(disc)
    unit(q.ox + q.dx * t - cx, q.oy + q.dy * t - cy, q.oz + q.dz * t - cz, q.n, at)
    return t
}

private fun rayCapsule(q: RayQuery, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float, at: Int): Float {
    val abx = bx - ax; val aby = by - ay; val abz = bz - az
    val len = sqrt(abx * abx + aby * aby + abz * abz)
    if (len < 1e-6f) return raySphere(q, ax, ay, az, r, at)
    val ux = abx / len; val uy = aby / len; val uz = abz / len
    val ox = q.ox; val oy = q.oy; val oz = q.oz; val dx = q.dx; val dy = q.dy; val dz = q.dz; val n = q.n
    // Origin inside: distance to the segment below the radius.
    val s0 = ((ox - ax) * ux + (oy - ay) * uy + (oz - az) * uz).coerceIn(0f, len)
    val gx = ox - (ax + ux * s0); val gy = oy - (ay + uy * s0); val gz = oz - (az + uz * s0)
    if (gx * gx + gy * gy + gz * gz <= r * r) return backwards(q, at)
    var best = Float.NaN
    // Side of the cylinder.
    val wx = ox - ax; val wy = oy - ay; val wz = oz - az
    val du = dx * ux + dy * uy + dz * uz; val dpx = dx - ux * du; val dpy = dy - uy * du; val dpz = dz - uz * du
    val wu = wx * ux + wy * uy + wz * uz; val wpx = wx - ux * wu; val wpy = wy - uy * wu; val wpz = wz - uz * wu
    val qa = dpx * dpx + dpy * dpy + dpz * dpz; val qb = 2f * (wpx * dpx + wpy * dpy + wpz * dpz)
    val qc = (wpx * wpx + wpy * wpy + wpz * wpz) - r * r
    if (qa > 1e-12f) {
        val disc = qb * qb - 4f * qa * qc
        if (disc >= 0f) {
            val t = (-qb - sqrt(disc)) / (2f * qa)
            val s = (wx + dx * t) * ux + (wy + dy * t) * uy + (wz + dz * t) * uz
            if (t >= 0f && s in 0f..len) { best = t; unit(wpx + dpx * t, wpy + dpy * t, wpz + dpz * t, n, at) }
        }
    }
    // The two end caps.
    val ta = raySphere(q, ax, ay, az, r, at + 3)
    if (best.isNaN() || ta < best) if (!ta.isNaN()) { best = ta; n[at] = n[at + 3]; n[at + 1] = n[at + 4]; n[at + 2] = n[at + 5] }
    val tb = raySphere(q, bx, by, bz, r, at + 3)
    if (best.isNaN() || tb < best) if (!tb.isNaN()) { best = tb; n[at] = n[at + 3]; n[at + 1] = n[at + 4]; n[at + 2] = n[at + 5] }
    return best
}

/**
 * A sphere of radius [r] swept against a box: the box grown by r, with rounded edges and corners (the
 * Minkowski sum). The grown box tells where a face is hit; off the faces, the twelve edges as capsules.
 */
private fun sweepBox(q: RayQuery, pose: Pose, b: Box, r: Float, at: Int): Float {
    val hx = b.halfX; val hy = b.halfY; val hz = b.halfZ
    val lx = pose.localX(q.ox, q.oy, q.oz); val ly = pose.localY(q.ox, q.oy, q.oz); val lz = pose.localZ(q.ox, q.oy, q.oz)
    // Starting inside the rounded box: distance from the box below the radius.
    val gx = max(abs(lx) - hx, 0f); val gy = max(abs(ly) - hy, 0f); val gz = max(abs(lz) - hz, 0f)
    if (gx * gx + gy * gy + gz * gz <= r * r) return backwards(q, at)
    val grown = rayBox(q, pose, hx + r, hy + r, hz + r, at)
    if (grown.isNaN()) return Float.NaN
    val wx = q.ox + q.dx * grown; val wy = q.oy + q.dy * grown; val wz = q.oz + q.dz * grown
    var outside = 0
    if (abs(pose.localX(wx, wy, wz)) > hx + 1e-5f) outside++
    if (abs(pose.localY(wx, wy, wz)) > hy + 1e-5f) outside++
    if (abs(pose.localZ(wx, wy, wz)) > hz + 1e-5f) outside++
    if (outside <= 1 && grown > 0f) return grown            // on a face (only the hit axis is beyond the box)
    var best = Float.NaN
    val n = q.n
    for (axis in 0..2) {
        val u = (axis + 1) % 3; val v = (axis + 2) % 3
        val ha = b.half(axis)
        for (iu in 0..1) for (iv in 0..1) {
            val cu = (if (iu == 0) -1f else 1f) * b.half(u); val cv = (if (iv == 0) -1f else 1f) * b.half(v)
            // Edge along [axis], at (cu, cv) on the two other axes, in box space.
            val x0 = corner(0, axis, u, -ha, cu, cv); val y0 = corner(1, axis, u, -ha, cu, cv); val z0 = corner(2, axis, u, -ha, cu, cv)
            val x1 = corner(0, axis, u, ha, cu, cv); val y1 = corner(1, axis, u, ha, cu, cv); val z1 = corner(2, axis, u, ha, cu, cv)
            val hit = rayCapsule(q, pose.worldX(x0, y0, z0), pose.worldY(x0, y0, z0), pose.worldZ(x0, y0, z0),
                pose.worldX(x1, y1, z1), pose.worldY(x1, y1, z1), pose.worldZ(x1, y1, z1), r, at + 3)
            if (!hit.isNaN() && (best.isNaN() || hit < best)) { best = hit; n[at] = n[at + 3]; n[at + 1] = n[at + 4]; n[at + 2] = n[at + 5] }
        }
    }
    return best
}

/** Component [c] of a box-space point whose coordinate on [axis] is [a], on [u] is [cu], on the third is [cv]. */
private fun corner(c: Int, axis: Int, u: Int, a: Float, cu: Float, cv: Float) = if (c == axis) a else if (c == u) cu else cv

private fun rayBox(q: RayQuery, pose: Pose, hx: Float, hy: Float, hz: Float, at: Int): Float {
    val lox = pose.localX(q.ox, q.oy, q.oz); val loy = pose.localY(q.ox, q.oy, q.oz); val loz = pose.localZ(q.ox, q.oy, q.oz)
    val dx = q.dx; val dy = q.dy; val dz = q.dz
    var t0 = 0f; var t1 = Float.MAX_VALUE; var axis = -1; var sign = 0f
    for (k in 0..2) {
        val ok = when (k) { 0 -> lox; 1 -> loy; else -> loz }
        val dk = when (k) { 0 -> dx * pose.xx + dy * pose.xy + dz * pose.xz; 1 -> dx * pose.yx + dy * pose.yy + dz * pose.yz; else -> dx * pose.zx + dy * pose.zy + dz * pose.zz }
        val h = when (k) { 0 -> hx; 1 -> hy; else -> hz }
        if (abs(dk) < 1e-9f) { if (ok < -h || ok > h) return Float.NaN; continue }
        var ta = (-h - ok) / dk; var tb = (h - ok) / dk
        var s = -1f
        if (ta > tb) { val x = ta; ta = tb; tb = x; s = 1f }
        if (ta > t0) { t0 = ta; axis = k; sign = s }
        t1 = min(t1, tb)
        if (t0 > t1) return Float.NaN
    }
    if (axis < 0) return backwards(q, at)                     // origin inside the box
    q.n[at] = pose.ax(axis) * sign; q.n[at + 1] = pose.ay(axis) * sign; q.n[at + 2] = pose.az(axis) * sign
    return t0
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
    val q = rotation.normalized()
    touching(shape, Pose().set(position.x, position.y, position.z, q.x, q.y, q.z, q.w), margin, exclude?.id ?: -1, t)
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
    if (grid != null) grid.bodiesIn(pose.px - reach, pose.py - reach, pose.pz - reach, pose.px + reach, pose.py + reach, pose.pz + reach, cand)
    val n = if (grid != null) cand.size else count
    for (k in 0 until n) {
        val i = if (grid != null) cand[k] else k
        if (i == exclude || removed[i]) continue
        val r = shapes[i]!!.boundingRadius + reach
        val dx = px[i] - pose.px; val dy = py[i] - pose.py; val dz = pz[i] - pose.pz
        if (dx * dx + dy * dy + dz * dz > r * r) continue
        scratch.clear()
        collide(shapes[i]!!, poseOf(i, queryPose), shape, pose, margin, scratch)
        out.add(scratch, i, null)
    }
    if (!meshes) return
    val found = queryFound; val stack = queryStack
    for (mesh in this.meshes) {
        found.clear()
        mesh.query(pose.px - reach, pose.py - reach, pose.pz - reach, pose.px + reach, pose.py + reach, pose.pz + reach, found, stack)
        for (f in 0 until found.size) {
            val t = found[f]; val v = mesh.v; val n = mesh.n
            // Triangles are one-sided: a shape whose centre is behind one (inside a closed mesh such as a
            // pier, under a floor) does not touch it, or it would be thrown out through the far side.
            if ((pose.px - v[9 * t]) * n[3 * t] + (pose.py - v[9 * t + 1]) * n[3 * t + 1] + (pose.pz - v[9 * t + 2]) * n[3 * t + 2] < 0f) continue
            scratch.clear()
            collideTriangle(mesh, t, shape, pose, margin, scratch)
            out.add(scratch, -1, mesh.palette[mesh.material[t]])
        }
    }
}
