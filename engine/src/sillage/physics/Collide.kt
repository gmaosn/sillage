package sillage.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

// The narrow phase: contact points between two shapes, computed on plain floats. Nothing is allocated
// while colliding; the scratch (clipped polygons, ranges, closest points) lives in the caller's
// [ContactPoints], one per thread. The simulation is deterministic to the bit, so the order of the
// operations is part of the result: change a formula and the contacts change.

/** Position and orientation of a shape: centre and world directions of its local axes. Reused in place. */
internal class Pose {
    var px = 0f; var py = 0f; var pz = 0f
    /** Local X axis in the world (then Y: [yx], [yy], [yz]; and Z). */
    var xx = 1f; var xy = 0f; var xz = 0f
    var yx = 0f; var yy = 1f; var yz = 0f
    var zx = 0f; var zy = 0f; var zz = 1f

    /** Centre (x, y, z), rotated by the unit quaternion (qx, qy, qz, qw). */
    fun set(x: Float, y: Float, z: Float, qx: Float, qy: Float, qz: Float, qw: Float): Pose {
        px = x; py = y; pz = z
        xx = 1 - 2 * (qy * qy + qz * qz); xy = 2 * (qx * qy + qz * qw); xz = 2 * (qx * qz - qy * qw)
        yx = 2 * (qx * qy - qz * qw); yy = 1 - 2 * (qx * qx + qz * qz); yz = 2 * (qy * qz + qx * qw)
        zx = 2 * (qx * qz + qy * qw); zy = 2 * (qy * qz - qx * qw); zz = 1 - 2 * (qx * qx + qy * qy)
        return this
    }
    /** Centre (x, y, z), axes of the world. */
    fun set(x: Float, y: Float, z: Float): Pose {
        px = x; py = y; pz = z
        xx = 1f; xy = 0f; xz = 0f; yx = 0f; yy = 1f; yz = 0f; zx = 0f; zy = 0f; zz = 1f
        return this
    }

    /** World components of local axis [k]. */
    fun ax(k: Int) = when (k) { 0 -> xx; 1 -> yx; else -> zx }
    fun ay(k: Int) = when (k) { 0 -> xy; 1 -> yy; else -> zy }
    fun az(k: Int) = when (k) { 0 -> xz; 1 -> yz; else -> zz }

    /** Local coordinates of world point (x, y, z). */
    fun localX(x: Float, y: Float, z: Float) = (x - px) * xx + (y - py) * xy + (z - pz) * xz
    fun localY(x: Float, y: Float, z: Float) = (x - px) * yx + (y - py) * yy + (z - pz) * yz
    fun localZ(x: Float, y: Float, z: Float) = (x - px) * zx + (y - py) * zy + (z - pz) * zz
    /** World coordinates of local point (x, y, z). */
    fun worldX(x: Float, y: Float, z: Float) = px + xx * x + yx * y + zx * z
    fun worldY(x: Float, y: Float, z: Float) = py + xy * x + yy * y + zy * z
    fun worldZ(x: Float, y: Float, z: Float) = pz + xz * x + yz * y + zz * z
}

/** A triangle of a static mesh, loaded from its arrays: corners a, b, c, unit front normal, active edges. */
internal class Tri {
    var ax = 0f; var ay = 0f; var az = 0f
    var bx = 0f; var by = 0f; var bz = 0f
    var cx = 0f; var cy = 0f; var cz = 0f
    var nx = 0f; var ny = 1f; var nz = 0f
    /** Active-edge bits: a→b, b→c, c→a (see [TriangleMesh.edges]). */
    var edges = 7

    fun load(mesh: TriangleMesh, t: Int): Tri {
        val v = mesh.v; val o = 9 * t
        ax = v[o]; ay = v[o + 1]; az = v[o + 2]; bx = v[o + 3]; by = v[o + 4]; bz = v[o + 5]; cx = v[o + 6]; cy = v[o + 7]; cz = v[o + 8]
        val n = mesh.n
        nx = n[3 * t]; ny = n[3 * t + 1]; nz = n[3 * t + 2]
        edges = mesh.edges[t]
        return this
    }
    fun active(j: Int) = edges and (1 shl j) != 0
    /** Corner [j]: 0 = a, 1 = b, 2 = c. */
    fun vx(j: Int) = when (j) { 0 -> ax; 1 -> bx; else -> cx }
    fun vy(j: Int) = when (j) { 0 -> ay; 1 -> by; else -> cy }
    fun vz(j: Int) = when (j) { 0 -> az; 1 -> bz; else -> cz }
}

/** A convex polygon being clipped (at most 4 corners, plus one per clipping plane). */
internal class Poly {
    val x = FloatArray(MAX); val y = FloatArray(MAX); val z = FloatArray(MAX)
    var size = 0
    fun add(px: Float, py: Float, pz: Float) { x[size] = px; y[size] = py; z[size] = pz; size++ }
    companion object { const val MAX = 16 }
}

/** The best separating axis found so far between a triangle and a box. */
internal class Sat {
    var best = 0f; var kind = 0; var axis = 0; var edge = 0
    var nx = 0f; var ny = 0f; var nz = 0f
}

/**
 * Contact points found between shape A and shape B. Each point has its own normal, pointing from A
 * to B, and a separation: negative when the shapes overlap, positive up to the speculative margin.
 * It also carries the scratch of the narrow phase: one per thread.
 */
internal class ContactPoints {
    var x = FloatArray(MAX); var y = FloatArray(MAX); var z = FloatArray(MAX)
    var nx = FloatArray(MAX); var ny = FloatArray(MAX); var nz = FloatArray(MAX)
    var sep = FloatArray(MAX); var id = IntArray(MAX)
    var count = 0

    internal val range = FloatArray(2)       // a clipped part [from, to] of a segment
    internal val near = FloatArray(3)        // a closest point on a triangle
    internal val params = FloatArray(2)      // closest parameters of two segments
    internal val poly = Poly(); internal val spare = Poly()
    internal val sat = Sat()
    internal val tri = Tri()
    internal val hull = HullScratch()
    /** Poses of the parts of compounds being collided. */
    internal val partA = Pose(); internal val partB = Pose()
    private val keep = IntArray(4)
    private val kept = FloatArray(4 * 8); private val keptId = IntArray(4)

    fun clear() { count = 0 }
    fun add(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, separation: Float, feature: Int) {
        if (count == x.size) {
            // Compounds can bring more than 16 points (up to four per pair of parts): grow, once.
            val c = 2 * count
            x = x.copyOf(c); y = y.copyOf(c); z = z.copyOf(c); this.nx = this.nx.copyOf(c); this.ny = this.ny.copyOf(c); this.nz = this.nz.copyOf(c)
            sep = sep.copyOf(c); id = id.copyOf(c)
        }
        x[count] = px; y[count] = py; z[count] = pz
        this.nx[count] = nx; this.ny[count] = ny; this.nz[count] = nz
        sep[count] = separation; id[count] = feature; count++
    }
    /** Turns the points of the last call around (B becomes A): normals flip. */
    fun flipFrom(start: Int) { for (i in start until count) { nx[i] = -nx[i]; ny[i] = -ny[i]; nz[i] = -nz[i] } }

    // Signed area of the triangle (a, b, point i) seen along n.
    private fun area(i: Int, ax: Float, ay: Float, az: Float, ex: Float, ey: Float, ez: Float, nx: Float, ny: Float, nz: Float): Float {
        val wx = x[i] - ax; val wy = y[i] - ay; val wz = z[i] - az
        return (ey * wz - ez * wy) * nx + (ez * wx - ex * wz) * ny + (ex * wy - ey * wx) * nz
    }

    /**
     * Keeps at most four points from [start]: the deepest, the farthest from it, then the two that span
     * the widest area on each side. Ties go to the first point (values compared in total order).
     */
    fun reduce(start: Int, nx: Float, ny: Float, nz: Float) {
        if (count - start <= 4) return
        var k0 = start
        for (i in start + 1 until count) if (sep[k0].compareTo(sep[i]) > 0) k0 = i
        val ax = x[k0]; val ay = y[k0]; val az = z[k0]
        var k1 = start
        var far = 0f
        for (i in start until count) {
            val dx = x[i] - ax; val dy = y[i] - ay; val dz = z[i] - az
            val d = dx * dx + dy * dy + dz * dz
            if (i == start) far = d else if (far.compareTo(d) < 0) { k1 = i; far = d }
        }
        val ex = x[k1] - ax; val ey = y[k1] - ay; val ez = z[k1] - az
        var k2 = start; var k3 = start
        var hi = area(start, ax, ay, az, ex, ey, ez, nx, ny, nz); var lo = hi
        for (i in start + 1 until count) {
            val s = area(i, ax, ay, az, ex, ey, ez, nx, ny, nz)
            if (hi.compareTo(s) < 0) { k2 = i; hi = s }
            if (lo.compareTo(s) > 0) { k3 = i; lo = s }
        }
        keep[0] = k0
        var n = 1
        if (k1 != k0) keep[n++] = k1
        if (k2 != k0 && k2 != k1) keep[n++] = k2
        if (k3 != k0 && k3 != k1 && k3 != k2) keep[n++] = k3
        for (j in 0 until n) {
            val i = keep[j]; val o = 8 * j
            kept[o] = x[i]; kept[o + 1] = y[i]; kept[o + 2] = z[i]
            kept[o + 3] = this.nx[i]; kept[o + 4] = this.ny[i]; kept[o + 5] = this.nz[i]
            kept[o + 6] = sep[i]; keptId[j] = id[i]
        }
        count = start
        for (j in 0 until n) {
            val o = 8 * j
            add(kept[o], kept[o + 1], kept[o + 2], kept[o + 3], kept[o + 4], kept[o + 5], kept[o + 6], keptId[j])
        }
    }

    /**
     * The points from [start] (a compound's, from several of its parts) grouped by normal (within 25°, in
     * order), each group kept to four with [reduceStable]. A table's four legs on a table each brought four
     * points a few centimetres apart: sixteen springs fighting over four feet, and a stack of three tables
     * shivered for ever. One point at each foot, the widest four, hold it as a box's corners do.
     */
    fun reduceGroups(start: Int) {
        if (count - start <= 4) return
        val n = count - start
        if (order.size < n) { order = IntArray(2 * n); group = IntArray(2 * n); keptX = FloatArray(16 * n) }
        for (k in 0 until n) group[k] = -1
        var groups = 0; var at = 0
        for (k in 0 until n) {
            if (group[k] >= 0) continue
            val i = start + k
            for (m in k until n) {
                val j = start + m
                if (group[m] < 0 && nx[i] * nx[j] + ny[i] * ny[j] + nz[i] * nz[j] > .9f) { group[m] = groups; order[at++] = m }
            }
            groups++
        }
        if (groups == 1 && n <= 4) return
        // Lay the points out group after group, then reduce each group in place.
        for (k in 0 until n) {
            val i = start + order[k]; val o = 8 * k
            keptX[o] = x[i]; keptX[o + 1] = y[i]; keptX[o + 2] = z[i]; keptX[o + 3] = nx[i]; keptX[o + 4] = ny[i]; keptX[o + 5] = nz[i]
            keptX[o + 6] = sep[i]; keptX[o + 7] = Float.fromBits(id[i])
        }
        count = start
        var k = 0
        while (k < n) {
            val g = group[order[k]]
            val from = count
            while (k < n && group[order[k]] == g) {
                val o = 8 * k
                add(keptX[o], keptX[o + 1], keptX[o + 2], keptX[o + 3], keptX[o + 4], keptX[o + 5], keptX[o + 6], keptX[o + 7].toRawBits())
                k++
            }
            reduceStable(from, nx[from], ny[from], nz[from])
        }
    }
    private var order = IntArray(0); private var group = IntArray(0); private var keptX = FloatArray(0)

    /**
     * Keeps at most four points from [start], for faces of polyhedra lying on each other: of the deepest points
     * (within a millimetre of the deepest), the one farthest from the touching points' centre; then the touching
     * point farthest from it; then the two touching points spanning the widest area on each side. When two faces lie flat on each other every
     * point is as deep as the next and the deepest changes from step to step with the rounding: picked this
     * way, the same four corners come back each step, the widest ones, and a stack of hulls stands as still as
     * one of boxes. A choice is changed only for a clearly better point (a millionth of the size), the first
     * one keeping ties.
     */
    fun reduceStable(start: Int, nx: Float, ny: Float, nz: Float) {
        if (count - start <= 4) return
        // The points that touch (within 5 mm of the deepest) when there are three or more: a rock also brings
        // its vertices a little above the ground, which must not take the place of those it stands on.
        var deepest = Float.MAX_VALUE
        for (i in start until count) deepest = min(deepest, sep[i])
        var touching = 0
        for (i in start until count) if (sep[i] <= deepest + .005f) touching++
        val band = if (touching >= 3) deepest + .005f else Float.MAX_VALUE
        var cx = 0f; var cy = 0f; var cz = 0f; var m = 0
        for (i in start until count) if (sep[i] <= band) { cx += x[i]; cy += y[i]; cz += z[i]; m++ }
        val inv = 1f / m; cx *= inv; cy *= inv; cz *= inv
        var size = 0f
        for (i in start until count) if (sep[i] <= band) size = max(size, abs(x[i] - cx) + abs(y[i] - cy) + abs(z[i] - cz))
        val eps2 = 1e-6f * size * size
        var k0 = -1; var best = 0f
        for (i in start until count) {
            if (sep[i] > deepest + .001f) continue
            val dx = x[i] - cx; val dy = y[i] - cy; val dz = z[i] - cz
            val d = dx * dx + dy * dy + dz * dz
            if (k0 < 0 || d > best + eps2) { best = d; k0 = i }
        }
        val ax = x[k0]; val ay = y[k0]; val az = z[k0]
        var k1 = k0; var far = 0f
        for (i in start until count) {
            if (sep[i] > band) continue
            val dx = x[i] - ax; val dy = y[i] - ay; val dz = z[i] - az
            val d = dx * dx + dy * dy + dz * dz
            if (d > far + eps2) { far = d; k1 = i }
        }
        val ex = x[k1] - ax; val ey = y[k1] - ay; val ez = z[k1] - az
        var k2 = k0; var k3 = k0; var hi = 0f; var lo = 0f
        for (i in start until count) {
            if (sep[i] > band) continue
            val s = area(i, ax, ay, az, ex, ey, ez, nx, ny, nz)
            if (s > hi + eps2) { k2 = i; hi = s }
            if (s < lo - eps2) { k3 = i; lo = s }
        }
        keep[0] = k0
        var n = 1
        if (k1 != k0) keep[n++] = k1
        if (k2 != k0 && k2 != k1) keep[n++] = k2
        if (k3 != k0 && k3 != k1 && k3 != k2) keep[n++] = k3
        for (j in 0 until n) {
            val i = keep[j]; val o = 8 * j
            kept[o] = x[i]; kept[o + 1] = y[i]; kept[o + 2] = z[i]
            kept[o + 3] = this.nx[i]; kept[o + 4] = this.ny[i]; kept[o + 5] = this.nz[i]
            kept[o + 6] = sep[i]; keptId[j] = id[i]
        }
        count = start
        for (j in 0 until n) {
            val o = 8 * j
            add(kept[o], kept[o + 1], kept[o + 2], kept[o + 3], kept[o + 4], kept[o + 5], kept[o + 6], keptId[j])
        }
    }

    companion object { const val MAX = 16 }
}

private fun rank(s: Shape) = when (s) { is Sphere -> 0; is Capsule -> 1; is Box -> 2; is ConvexHull -> 3; is Compound -> 4 }

/** Contacts between two body shapes, normal from A to B. [margin] is the speculative distance. */
internal fun collide(a: Shape, pa: Pose, b: Shape, pb: Pose, margin: Float, out: ContactPoints) {
    if (a is Compound || b is Compound) { collideParts(a, pa, b, pb, margin, out); return }
    if (rank(a) > rank(b)) {
        val start = out.count
        collideOrdered(b, pb, a, pa, margin, out)
        out.flipFrom(start)
    } else collideOrdered(a, pa, b, pb, margin, out)
}

private fun collideOrdered(a: Shape, pa: Pose, b: Shape, pb: Pose, margin: Float, out: ContactPoints) {
    when (a) {
        is Sphere -> when (b) {
            is Sphere -> sphereSphere(pa.px, pa.py, pa.pz, a.radius, pb.px, pb.py, pb.pz, b.radius, margin, out, 0)
            is Capsule -> {
                val h = b.halfHeight
                val s0x = pb.px - pb.yx * h; val s0y = pb.py - pb.yy * h; val s0z = pb.pz - pb.yz * h
                val s1x = pb.px + pb.yx * h; val s1y = pb.py + pb.yy * h; val s1z = pb.pz + pb.yz * h
                val t = closestParam(pa.px, pa.py, pa.pz, s0x, s0y, s0z, s1x, s1y, s1z)
                sphereSphere(pa.px, pa.py, pa.pz, a.radius, s0x + (s1x - s0x) * t, s0y + (s1y - s0y) * t, s0z + (s1z - s0z) * t,
                    b.radius, margin, out, 0)
            }
            is Box -> sphereBox(pa.px, pa.py, pa.pz, a.radius, b, pb, margin, out, 0, sphereIsA = true)
            is ConvexHull -> sphereHull(pa.px, pa.py, pa.pz, a.radius, b.poly, pb, margin, out, 0, sphereIsA = true)
            is Compound -> error("compound")
        }
        is Capsule -> when (b) {
            is Capsule -> capsuleCapsule(a, pa, b, pb, margin, out)
            is Box -> capsuleBox(a, pa, b, pb, margin, out)
            is ConvexHull -> capsuleHull(a, pa, b.poly, pb, margin, out)
            else -> error("unordered")
        }
        is Box -> when (b) {
            is Box -> boxBox(a, pa, b, pb, margin, out)
            is ConvexHull -> { val box = out.hull.box; box.setBox(UNIT_BOX, a.halfX, a.halfY, a.halfZ); polytopes(box, pa, b.poly, pb, margin, out) }
            else -> error("unordered")
        }
        is ConvexHull -> if (b is ConvexHull) polytopes(a.poly, pa, b.poly, pb, margin, out) else error("unordered")
        is Compound -> error("compound")
    }
}

/**
 * Contacts when one shape or both are compounds: each pair of parts (bounding spheres apart by more than the
 * margin are skipped), the parts' indices folded into the feature ids (bits 27–31 for A's, 22–26 for B's).
 */
private fun collideParts(a: Shape, pa: Pose, b: Shape, pb: Pose, margin: Float, out: ContactPoints) {
    val ca = a as? Compound; val cb = b as? Compound
    val na = ca?.count ?: 1; val nb = cb?.count ?: 1
    val first = out.count
    for (i in 0 until na) {
        val sa = if (ca != null) ca.shapes[i] else a
        val qa = if (ca != null) out.partA.setPart(pa, ca, i) else pa
        val ra = sa.boundingRadius
        for (j in 0 until nb) {
            val sb = if (cb != null) cb.shapes[j] else b
            val qb = if (cb != null) out.partB.setPart(pb, cb, j) else pb
            val reach = ra + sb.boundingRadius + margin
            val dx = qb.px - qa.px; val dy = qb.py - qa.py; val dz = qb.pz - qa.pz
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue
            val start = out.count
            collide(sa, qa, sb, qb, margin, out)
            val tag = ((i and 31) shl 27) or ((j and 31) shl 22)
            for (k in start until out.count) out.id[k] = (out.id[k] and 0x3FFFFF) or tag
        }
    }
    out.reduceGroups(first)
}

// ------------------------------------------------------------------ primitives

/** Parameter in [0, 1] of the point of segment a→b closest to p. */
internal fun closestParam(px: Float, py: Float, pz: Float, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
    val abx = bx - ax; val aby = by - ay; val abz = bz - az
    val l = abx * abx + aby * aby + abz * abz
    return if (l < 1e-12f) 0f else ((px - ax) * (abx / l) + (py - ay) * (aby / l) + (pz - az) * (abz / l)).coerceIn(0f, 1f)
}

/** Closest points between segments p1-q1 and p2-q2 (Ericson 5.1.9): their parameters (s, t) in [r]. */
internal fun segmentParams(p1x: Float, p1y: Float, p1z: Float, q1x: Float, q1y: Float, q1z: Float,
                          p2x: Float, p2y: Float, p2z: Float, q2x: Float, q2y: Float, q2z: Float, r: FloatArray) {
    val d1x = q1x - p1x; val d1y = q1y - p1y; val d1z = q1z - p1z
    val d2x = q2x - p2x; val d2y = q2y - p2y; val d2z = q2z - p2z
    val rx = p1x - p2x; val ry = p1y - p2y; val rz = p1z - p2z
    val a = d1x * d1x + d1y * d1y + d1z * d1z; val e = d2x * d2x + d2y * d2y + d2z * d2z; val f = d2x * rx + d2y * ry + d2z * rz
    if (a <= 1e-12f && e <= 1e-12f) { r[0] = 0f; r[1] = 0f; return }
    if (a <= 1e-12f) { r[0] = 0f; r[1] = (f / e).coerceIn(0f, 1f); return }
    val c = d1x * rx + d1y * ry + d1z * rz
    if (e <= 1e-12f) { r[0] = (-c / a).coerceIn(0f, 1f); r[1] = 0f; return }
    val b = d1x * d2x + d1y * d2y + d1z * d2z; val denom = a * e - b * b
    var s = if (denom != 0f) ((b * f - c * e) / denom).coerceIn(0f, 1f) else 0f
    var t = (b * s + f) / e
    if (t < 0f) { t = 0f; s = (-c / a).coerceIn(0f, 1f) }
    else if (t > 1f) { t = 1f; s = ((b - c) / a).coerceIn(0f, 1f) }
    r[0] = s; r[1] = t
}

private fun sphereSphere(ax: Float, ay: Float, az: Float, ra: Float, bx: Float, by: Float, bz: Float, rb: Float,
                         margin: Float, out: ContactPoints, feature: Int) {
    val dx = bx - ax; val dy = by - ay; val dz = bz - az
    val l2 = dx * dx + dy * dy + dz * dz; val reach = ra + rb + margin
    if (l2 > reach * reach) return
    val l = sqrt(l2)
    val nx: Float; val ny: Float; val nz: Float
    if (l > 1e-6f) { nx = dx / l; ny = dy / l; nz = dz / l } else { nx = 0f; ny = 1f; nz = 0f }
    val s = l - ra - rb
    val k = ra + s * .5f
    out.add(ax + nx * k, ay + ny * k, az + nz * k, nx, ny, nz, s, feature)
}

/** Sphere against box; the normal goes from the sphere to the box when [sphereIsA]. */
private fun sphereBox(cx: Float, cy: Float, cz: Float, r: Float, box: Box, pose: Pose, margin: Float, out: ContactPoints,
                      feature: Int, sphereIsA: Boolean) {
    val lx = pose.localX(cx, cy, cz); val ly = pose.localY(cx, cy, cz); val lz = pose.localZ(cx, cy, cz)
    val hx = box.halfX; val hy = box.halfY; val hz = box.halfZ
    val qx = lx.coerceIn(-hx, hx); val qy = ly.coerceIn(-hy, hy); val qz = lz.coerceIn(-hz, hz)
    val nx: Float; val ny: Float; val nz: Float; val s: Float
    val sx: Float; val sy: Float; val sz: Float
    if (qx == lx && qy == ly && qz == lz) {
        // Centre inside the box: leave through the nearest face.
        val d0 = hx - abs(lx); val d1 = hy - abs(ly); val d2 = hz - abs(lz)
        var k = 0; var depth = d0
        if (depth.compareTo(d1) > 0) { k = 1; depth = d1 }
        if (depth.compareTo(d2) > 0) { k = 2; depth = d2 }
        val sgn = if ((if (k == 0) lx else if (k == 1) ly else lz) >= 0f) 1f else -1f
        nx = pose.ax(k) * sgn; ny = pose.ay(k) * sgn; nz = pose.az(k) * sgn
        s = -(depth + r)
        val ux = if (k == 0) sgn * hx else lx; val uy = if (k == 1) sgn * hy else ly; val uz = if (k == 2) sgn * hz else lz
        sx = pose.worldX(ux, uy, uz); sy = pose.worldY(ux, uy, uz); sz = pose.worldZ(ux, uy, uz)
    } else {
        sx = pose.worldX(qx, qy, qz); sy = pose.worldY(qx, qy, qz); sz = pose.worldZ(qx, qy, qz)
        val dx = cx - sx; val dy = cy - sy; val dz = cz - sz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d > r + margin) return
        nx = dx / d; ny = dy / d; nz = dz / d
        s = d - r
    }
    val h = s * .5f
    if (sphereIsA) out.add(sx + nx * h, sy + ny * h, sz + nz * h, -nx, -ny, -nz, s, feature)
    else out.add(sx + nx * h, sy + ny * h, sz + nz * h, nx, ny, nz, s, feature)
}

private fun capsuleCapsule(a: Capsule, pa: Pose, b: Capsule, pb: Pose, margin: Float, out: ContactPoints) {
    val ha = a.halfHeight; val hb = b.halfHeight
    val a0x = pa.px - pa.yx * ha; val a0y = pa.py - pa.yy * ha; val a0z = pa.pz - pa.yz * ha
    val a1x = pa.px + pa.yx * ha; val a1y = pa.py + pa.yy * ha; val a1z = pa.pz + pa.yz * ha
    val b0x = pb.px - pb.yx * hb; val b0y = pb.py - pb.yy * hb; val b0z = pb.pz - pb.yz * hb
    val b1x = pb.px + pb.yx * hb; val b1y = pb.py + pb.yy * hb; val b1z = pb.pz + pb.yz * hb
    val dax = a1x - a0x; val day = a1y - a0y; val daz = a1z - a0z
    val dbx = b1x - b0x; val dby = b1y - b0y; val dbz = b1z - b0z
    val cx = day * dbz - daz * dby; val cy = daz * dbx - dax * dbz; val cz = dax * dby - day * dbx
    val la = dax * dax + day * day + daz * daz; val lb = dbx * dbx + dby * dby + dbz * dbz
    if (cx * cx + cy * cy + cz * cz < 1e-6f * la * lb && la > 1e-8f && lb > 1e-8f) {
        // Lying side by side: one contact at each end of the overlap.
        val t0 = closestParam(b0x, b0y, b0z, a0x, a0y, a0z, a1x, a1y, a1z)
        val t1 = closestParam(b1x, b1y, b1z, a0x, a0y, a0z, a1x, a1y, a1z)
        val lo = minOf(t0, t1); val hi = maxOf(t0, t1)
        if (hi - lo > 1e-3f) {
            for (k in 0..1) {
                val t = if (k == 0) lo else hi
                val px = a0x + dax * t; val py = a0y + day * t; val pz = a0z + daz * t
                val u = closestParam(px, py, pz, b0x, b0y, b0z, b1x, b1y, b1z)
                sphereSphere(px, py, pz, a.radius, b0x + (b1x - b0x) * u, b0y + (b1y - b0y) * u, b0z + (b1z - b0z) * u,
                    b.radius, margin, out, k)
            }
            return
        }
    }
    val r = out.params
    segmentParams(a0x, a0y, a0z, a1x, a1y, a1z, b0x, b0y, b0z, b1x, b1y, b1z, r)
    val s = r[0]; val t = r[1]
    sphereSphere(a0x + dax * s, a0y + day * s, a0z + daz * s, a.radius, b0x + dbx * t, b0y + dby * t, b0z + dbz * t,
        b.radius, margin, out, 0)
}

/** Minimises a convex function of t ∈ [0, 1] by golden section. */
private inline fun minimise(f: (Float) -> Float): Float {
    var lo = 0f; var hi = 1f
    val g = .618034f
    var x1 = hi - g * (hi - lo); var x2 = lo + g * (hi - lo)
    var f1 = f(x1); var f2 = f(x2)
    repeat(16) {
        if (f1 < f2) { hi = x2; x2 = x1; f2 = f1; x1 = hi - g * (hi - lo); f1 = f(x1) }
        else { lo = x1; x1 = x2; f1 = f2; x2 = lo + g * (hi - lo); f2 = f(x2) }
    }
    return (lo + hi) * .5f
}

/**
 * Parameter of the segment point closest to the box (both in box space), by alternating projections:
 * box point nearest the segment point, then segment point nearest that box point. When the segment
 * goes through the box, the deepest point along it (largest margin to the nearest face) is kept.
 */
private fun segmentBoxParam(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, box: Box): Float {
    val dx = bx - ax; val dy = by - ay; val dz = bz - az
    val len2 = dx * dx + dy * dy + dz * dz
    if (len2 < 1e-12f) return 0f
    var t = .5f
    repeat(8) {
        val px = ax + dx * t; val py = ay + dy * t; val pz = az + dz * t
        val qx = px.coerceIn(-box.halfX, box.halfX); val qy = py.coerceIn(-box.halfY, box.halfY); val qz = pz.coerceIn(-box.halfZ, box.halfZ)
        if (qx == px && qy == py && qz == pz) {
            // Inside: move towards the point of the segment deepest in the box (sampled, then kept).
            var best = t; var depth = -1f
            for (k in 0..8) {
                val u = k / 8f
                val x = ax + dx * u; val y = ay + dy * u; val z = az + dz * u
                val d = minOf(box.halfX - abs(x), box.halfY - abs(y), box.halfZ - abs(z))
                if (d > depth) { depth = d; best = u }
            }
            return best
        }
        t = (((qx - ax) * dx + (qy - ay) * dy + (qz - az) * dz) / len2).coerceIn(0f, 1f)
    }
    return t
}

/**
 * Narrows the part [r][0]..[r][1] of segment a→b to the inner side of plane (n, d): dot(x, n) ≤ d.
 * False when nothing is left ([r] is then unchanged).
 */
internal fun clipRange(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
                      nx: Float, ny: Float, nz: Float, d: Float, r: FloatArray): Boolean {
    val da = (ax * nx + ay * ny + az * nz) - d; val db = (bx * nx + by * ny + bz * nz) - d
    if (da > 0f && db > 0f) return false
    val lo = r[0]; val hi = r[1]
    var l = lo; var h = hi
    if (da > 0f || db > 0f) {
        val t = da / (da - db)
        if (da > 0f) { if (t > hi) return false; l = maxOf(lo, t) } else { if (t < lo) return false; h = minOf(hi, t) }
    }
    r[0] = l; r[1] = h
    return true
}

/** Narrows the range of segment a→b to the slab |dot(x, axis) − [centre]| ≤ [half]. */
private fun clipSlab(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
                     ux: Float, uy: Float, uz: Float, centre: Float, half: Float, r: FloatArray): Boolean =
    clipRange(ax, ay, az, bx, by, bz, ux, uy, uz, centre + half, r) &&
        clipRange(ax, ay, az, bx, by, bz, -ux, -uy, -uz, -centre + half, r)

/**
 * Capsule against box, after Box2D's capsule–polygon: the reference face is found from the closest
 * point; the segment is clipped by that face's sides, giving two steady points when the capsule lies
 * on the face. Outside a face region (edges, corners), one point at the closest approach.
 */
private fun capsuleBox(c: Capsule, pc: Pose, box: Box, pb: Pose, margin: Float, out: ContactPoints) {
    val hh = c.halfHeight
    val e0x = pc.px - pc.yx * hh; val e0y = pc.py - pc.yy * hh; val e0z = pc.pz - pc.yz * hh
    val e1x = pc.px + pc.yx * hh; val e1y = pc.py + pc.yy * hh; val e1z = pc.pz + pc.yz * hh
    val t = segmentBoxParam(pb.localX(e0x, e0y, e0z), pb.localY(e0x, e0y, e0z), pb.localZ(e0x, e0y, e0z),
        pb.localX(e1x, e1y, e1z), pb.localY(e1x, e1y, e1z), pb.localZ(e1x, e1y, e1z), box)
    val mx = e0x + (e1x - e0x) * t; val my = e0y + (e1y - e0y) * t; val mz = e0z + (e1z - e0z) * t
    val lx = pb.localX(mx, my, mz); val ly = pb.localY(mx, my, mz); val lz = pb.localZ(mx, my, mz)
    val outX = abs(lx) >= box.halfX; val outY = abs(ly) >= box.halfY; val outZ = abs(lz) >= box.halfZ
    val k: Int; val sgn: Float
    when ((if (outX) 1 else 0) + (if (outY) 1 else 0) + (if (outZ) 1 else 0)) {
        1 -> {
            k = if (outX) 0 else if (outY) 1 else 2
            sgn = if ((if (k == 0) lx else if (k == 1) ly else lz) > 0f) 1f else -1f
        }
        0 -> {
            var kk = 0; var least = box.halfX - abs(lx)
            val dy = box.halfY - abs(ly); if (least.compareTo(dy) > 0) { kk = 1; least = dy }
            val dz = box.halfZ - abs(lz); if (least.compareTo(dz) > 0) { kk = 2 }
            k = kk
            sgn = if ((if (k == 0) lx else if (k == 1) ly else lz) >= 0f) 1f else -1f
        }
        else -> { sphereBox(mx, my, mz, c.radius, box, pb, margin, out, 9, true); return }   // edge or corner: one point
    }
    // Face normal, out of the box towards the capsule.
    val fx = pb.ax(k) * sgn; val fy = pb.ay(k) * sgn; val fz = pb.az(k) * sgn
    val r = out.range; r[0] = 0f; r[1] = 1f
    val s1 = (k + 1) % 3; val s2 = (k + 2) % 3
    val inside =
        clipSlab(e0x, e0y, e0z, e1x, e1y, e1z, pb.ax(s1), pb.ay(s1), pb.az(s1),
            pb.px * pb.ax(s1) + pb.py * pb.ay(s1) + pb.pz * pb.az(s1), box.half(s1), r) &&
        clipSlab(e0x, e0y, e0z, e1x, e1y, e1z, pb.ax(s2), pb.ay(s2), pb.az(s2),
            pb.px * pb.ax(s2) + pb.py * pb.ay(s2) + pb.pz * pb.az(s2), box.half(s2), r)
    if (!inside) { sphereBox(mx, my, mz, c.radius, box, pb, margin, out, 9, true); return }
    val faceD = (pb.px * fx + pb.py * fy + pb.pz * fz) + box.half(k)
    for (i in 0..1) {
        if (i == 1 && r[1] - r[0] < 1e-4f) break
        val u = r[i]
        val px = e0x + (e1x - e0x) * u; val py = e0y + (e1y - e0y) * u; val pz = e0z + (e1z - e0z) * u
        val s = (px * fx + py * fy + pz * fz) - faceD - c.radius
        if (s <= margin) {
            val q = c.radius + s * .5f
            out.add(px - fx * q, py - fy * q, pz - fz * q, -fx, -fy, -fz, s, k * 4 + (if (sgn > 0f) 2 else 0) + i)
        }
    }
}

// ------------------------------------------------------------------ box against box

/** Half the extent of box [b] along the unit direction l. */
private fun supportRadius(b: Box, p: Pose, lx: Float, ly: Float, lz: Float) =
    b.halfX * abs(lx * p.xx + ly * p.xy + lz * p.xz) + b.halfY * abs(lx * p.yx + ly * p.yy + lz * p.yz) +
        b.halfZ * abs(lx * p.zx + ly * p.zy + lz * p.zz)

/** Sutherland–Hodgman: writes to [dst] the part of [src] where dot(x, n) ≤ d. */
private fun clip(src: Poly, dst: Poly, nx: Float, ny: Float, nz: Float, d: Float) {
    dst.size = 0
    val n = src.size
    for (i in 0 until n) {
        val j = if (i + 1 == n) 0 else i + 1
        val ax = src.x[i]; val ay = src.y[i]; val az = src.z[i]
        val bx = src.x[j]; val by = src.y[j]; val bz = src.z[j]
        val da = (ax * nx + ay * ny + az * nz) - d; val db = (bx * nx + by * ny + bz * nz) - d
        if (da <= 0f) dst.add(ax, ay, az)
        if ((da < 0f && db > 0f) || (da > 0f && db < 0f)) {
            val t = da / (da - db)
            dst.add(ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t)
        }
    }
}

/** Clips [p] by the slab |dot(x, axis) − [centre]| ≤ [half], through [q]: the result is back in [p]. */
private fun clipSlab(p: Poly, q: Poly, ux: Float, uy: Float, uz: Float, centre: Float, half: Float) {
    clip(p, q, ux, uy, uz, centre + half)
    clip(q, p, -ux, -uy, -uz, -centre + half)
}

/** Writes to [dst] the corners of the face of box [b] whose outward normal is axis k × sgn. */
private fun boxFace(b: Box, p: Pose, k: Int, sgn: Float, dst: Poly) {
    val u = (k + 1) % 3; val v = (k + 2) % 3
    val hk = sgn * b.half(k)
    val cx = p.px + p.ax(k) * hk; val cy = p.py + p.ay(k) * hk; val cz = p.pz + p.az(k) * hk
    val hu = b.half(u); val dux = p.ax(u) * hu; val duy = p.ay(u) * hu; val duz = p.az(u) * hu
    val hv = b.half(v); val dvx = p.ax(v) * hv; val dvy = p.ay(v) * hv; val dvz = p.az(v) * hv
    dst.size = 0
    dst.add(cx + dux + dvx, cy + duy + dvy, cz + duz + dvz)
    dst.add(cx - dux + dvx, cy - duy + dvy, cz - duz + dvz)
    dst.add(cx - dux - dvx, cy - duy - dvy, cz - duz - dvz)
    dst.add(cx + dux - dvx, cy + duy - dvy, cz + duz - dvz)
}

/** Index of the axis of [p] most aligned with n (largest |dot|; the first one on a tie). */
private fun mostAligned(p: Pose, nx: Float, ny: Float, nz: Float): Int {
    var m = 0; var best = abs(p.xx * nx + p.xy * ny + p.xz * nz)
    val y = abs(p.yx * nx + p.yy * ny + p.yz * nz); if (best.compareTo(y) < 0) { m = 1; best = y }
    val z = abs(p.zx * nx + p.zy * ny + p.zz * nz); if (best.compareTo(z) < 0) m = 2
    return m
}

private fun boxBox(a: Box, pa: Pose, b: Box, pb: Pose, margin: Float, out: ContactPoints) {
    val tx = pb.px - pa.px; val ty = pb.py - pa.py; val tz = pb.pz - pa.pz
    var bestA = -Float.MAX_VALUE; var axisA = 0
    for (i in 0..2) {
        val lx = pa.ax(i); val ly = pa.ay(i); val lz = pa.az(i)
        val s = abs(tx * lx + ty * ly + tz * lz) - supportRadius(a, pa, lx, ly, lz) - supportRadius(b, pb, lx, ly, lz)
        if (s > margin) return
        if (s > bestA) { bestA = s; axisA = i }
    }
    var bestB = -Float.MAX_VALUE; var axisB = 0
    for (j in 0..2) {
        val lx = pb.ax(j); val ly = pb.ay(j); val lz = pb.az(j)
        val s = abs(tx * lx + ty * ly + tz * lz) - supportRadius(a, pa, lx, ly, lz) - supportRadius(b, pb, lx, ly, lz)
        if (s > margin) return
        if (s > bestB) { bestB = s; axisB = j }
    }
    var bestE = -Float.MAX_VALUE; var edgeI = 0; var edgeJ = 0; var enx = 0f; var eny = 1f; var enz = 0f
    for (i in 0..2) for (j in 0..2) {
        val ux = pa.ax(i); val uy = pa.ay(i); val uz = pa.az(i)
        val vx = pb.ax(j); val vy = pb.ay(j); val vz = pb.az(j)
        val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
        val l = sqrt(cx * cx + cy * cy + cz * cz)
        if (l < 1e-4f) continue
        val nx = cx / l; val ny = cy / l; val nz = cz / l
        val s = abs(tx * nx + ty * ny + tz * nz) - supportRadius(a, pa, nx, ny, nz) - supportRadius(b, pb, nx, ny, nz)
        if (s > margin) return
        if (s > bestE) { bestE = s; edgeI = i; edgeJ = j; enx = nx; eny = ny; enz = nz }
    }
    if (bestE > maxOf(bestA, bestB) + .01f) {
        // Edge against edge: one point, between the two supporting edges.
        val flip = (tx * enx + ty * eny + tz * enz) < 0f
        val nx = if (flip) -enx else enx; val ny = if (flip) -eny else eny; val nz = if (flip) -enz else enz
        var oax = pa.px; var oay = pa.py; var oaz = pa.pz
        var obx = pb.px; var oby = pb.py; var obz = pb.pz
        for (k in 0..2) {
            if (k != edgeI) {
                val ux = pa.ax(k); val uy = pa.ay(k); val uz = pa.az(k)
                val f = sign(nx * ux + ny * uy + nz * uz) * a.half(k)
                oax += ux * f; oay += uy * f; oaz += uz * f
            }
            if (k != edgeJ) {
                val vx = pb.ax(k); val vy = pb.ay(k); val vz = pb.az(k)
                val f = sign(nx * vx + ny * vy + nz * vz) * b.half(k)
                obx -= vx * f; oby -= vy * f; obz -= vz * f
            }
        }
        val ha = a.half(edgeI); val eax = pa.ax(edgeI) * ha; val eay = pa.ay(edgeI) * ha; val eaz = pa.az(edgeI) * ha
        val hb = b.half(edgeJ); val ebx = pb.ax(edgeJ) * hb; val eby = pb.ay(edgeJ) * hb; val ebz = pb.az(edgeJ) * hb
        val r = out.params
        segmentParams(oax - eax, oay - eay, oaz - eaz, oax + eax, oay + eay, oaz + eaz,
            obx - ebx, oby - eby, obz - ebz, obx + ebx, oby + eby, obz + ebz, r)
        val s = 2 * r[0]; val u = 2 * r[1]
        val qax = oax - eax + eax * s; val qay = oay - eay + eay * s; val qaz = oaz - eaz + eaz * s
        val qbx = obx - ebx + ebx * u; val qby = oby - eby + eby * u; val qbz = obz - ebz + ebz * u
        out.add((qax + qbx) * .5f, (qay + qby) * .5f, (qaz + qbz) * .5f, nx, ny, nz, bestE, 100 + edgeI * 3 + edgeJ)
        return
    }
    // Face contact; prefer A's face unless B's is clearly better.
    val refIsA = bestA >= bestB - .005f
    val ref = if (refIsA) a else b; val rp = if (refIsA) pa else pb
    val inc = if (refIsA) b else a; val ip = if (refIsA) pb else pa
    val k = if (refIsA) axisA else axisB
    val gx = if (refIsA) tx else -tx; val gy = if (refIsA) ty else -ty; val gz = if (refIsA) tz else -tz
    val rkx = rp.ax(k); val rky = rp.ay(k); val rkz = rp.az(k)
    val sg = if ((gx * rkx + gy * rky + gz * rkz) >= 0f) 1f else -1f
    val nx = rkx * sg; val ny = rky * sg; val nz = rkz * sg
    val m = mostAligned(ip, nx, ny, nz)
    val p = out.poly; val q = out.spare
    boxFace(inc, ip, m, if ((ip.ax(m) * nx + ip.ay(m) * ny + ip.az(m) * nz) > 0f) -1f else 1f, p)
    for (j in 1..2) {
        val side = (k + j) % 3
        val ux = rp.ax(side); val uy = rp.ay(side); val uz = rp.az(side)
        clipSlab(p, q, ux, uy, uz, rp.px * ux + rp.py * uy + rp.pz * uz, ref.half(side))
    }
    val faceD = (rp.px * nx + rp.py * ny + rp.pz * nz) + ref.half(k)
    val onx = if (refIsA) nx else -nx; val ony = if (refIsA) ny else -ny; val onz = if (refIsA) nz else -nz
    val start = out.count
    for (i in 0 until p.size) {
        val s = (p.x[i] * nx + p.y[i] * ny + p.z[i] * nz) - faceD
        if (s <= margin) {
            val h = s * .5f
            out.add(p.x[i] - nx * h, p.y[i] - ny * h, p.z[i] - nz * h, onx, ony, onz, s, (if (refIsA) 0 else 50) + k * 10 + i)
        }
    }
    out.reduce(start, onx, ony, onz)
}

// ------------------------------------------------------------------ triangles (static mesh as A)

/** Closest point of triangle [t] to (px, py, pz), written to [closest] (Ericson 5.1.5). */
private fun closestInto(px: Float, py: Float, pz: Float, t: Tri, closest: FloatArray) {
    val abx = t.bx - t.ax; val aby = t.by - t.ay; val abz = t.bz - t.az
    val acx = t.cx - t.ax; val acy = t.cy - t.ay; val acz = t.cz - t.az
    val apx = px - t.ax; val apy = py - t.ay; val apz = pz - t.az
    val d1 = abx * apx + aby * apy + abz * apz; val d2 = acx * apx + acy * apy + acz * apz
    if (d1 <= 0f && d2 <= 0f) { closest[0] = t.ax; closest[1] = t.ay; closest[2] = t.az; return }
    val bpx = px - t.bx; val bpy = py - t.by; val bpz = pz - t.bz
    val d3 = abx * bpx + aby * bpy + abz * bpz; val d4 = acx * bpx + acy * bpy + acz * bpz
    if (d3 >= 0f && d4 <= d3) { closest[0] = t.bx; closest[1] = t.by; closest[2] = t.bz; return }
    val vc = d1 * d4 - d3 * d2
    if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
        val v = d1 / (d1 - d3)
        closest[0] = t.ax + abx * v; closest[1] = t.ay + aby * v; closest[2] = t.az + abz * v; return
    }
    val cpx = px - t.cx; val cpy = py - t.cy; val cpz = pz - t.cz
    val d5 = abx * cpx + aby * cpy + abz * cpz; val d6 = acx * cpx + acy * cpy + acz * cpz
    if (d6 >= 0f && d5 <= d6) { closest[0] = t.cx; closest[1] = t.cy; closest[2] = t.cz; return }
    val vb = d5 * d2 - d1 * d6
    if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
        val w = d2 / (d2 - d6)
        closest[0] = t.ax + acx * w; closest[1] = t.ay + acy * w; closest[2] = t.az + acz * w; return
    }
    val va = d3 * d6 - d5 * d4
    if (va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f) {
        val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
        closest[0] = t.bx + (t.cx - t.bx) * w; closest[1] = t.by + (t.cy - t.by) * w; closest[2] = t.bz + (t.cz - t.bz) * w; return
    }
    val denom = 1f / (va + vb + vc); val v = vb * denom; val w = vc * denom
    closest[0] = t.ax + abx * v + acx * w; closest[1] = t.ay + aby * v + acy * w; closest[2] = t.az + abz * v + acz * w
}

/** Edges (bit j for edge j→j+1) that point q lies on, 0 for the interior; a corner counts as both its edges. */
private fun edgeMask(t: Tri, qx: Float, qy: Float, qz: Float): Int {
    var mask = 0
    for (j in 0..2) {
        val k = if (j == 2) 0 else j + 1
        val ax = t.vx(j); val ay = t.vy(j); val az = t.vz(j)
        val bx = t.vx(k); val by = t.vy(k); val bz = t.vz(k)
        val u = closestParam(qx, qy, qz, ax, ay, az, bx, by, bz)
        val dx = ax + (bx - ax) * u - qx; val dy = ay + (by - ay) * u - qy; val dz = az + (bz - az) * u - qz
        if (dx * dx + dy * dy + dz * dz < 1e-10f) mask = mask or (1 shl j)
    }
    return mask
}

/** Sphere on a one-sided triangle; normal from the triangle to the sphere. */
private fun triangleSphere(t: Tri, cx: Float, cy: Float, cz: Float, r: Float, margin: Float, out: ContactPoints, feature: Int) {
    val q = out.near
    closestInto(cx, cy, cz, t, q)
    val qx = q[0]; val qy = q[1]; val qz = q[2]
    val height = (cx - t.ax) * t.nx + (cy - t.ay) * t.ny + (cz - t.az) * t.nz
    if (height < 0f) {
        // Behind the face: only push out when the centre is right under the triangle and not too deep.
        val bx = cx - t.nx * height; val by = cy - t.ny * height; val bz = cz - t.nz * height
        val ux = bx - qx; val uy = by - qy; val uz = bz - qz
        if (ux * ux + uy * uy + uz * uz > 1e-6f || height < -2f * r) return
        val sep = height - r
        val k = sep * .5f
        out.add(bx + t.nx * k, by + t.ny * k, bz + t.nz * k, t.nx, t.ny, t.nz, sep, feature)   // midway between the plane and the lowest point
        return
    }
    val dx = cx - qx; val dy = cy - qy; val dz = cz - qz
    val l = sqrt(dx * dx + dy * dy + dz * dz)
    if (l > r + margin) return
    val mask = edgeMask(t, qx, qy, qz)
    if (mask != 0 && (mask and t.edges) == 0) {
        // Seam between flat neighbours: take the face normal, not the one towards the edge. The point
        // must lie under the centre along that normal: an off-centre point would turn with a rolling
        // ball and fake a penetration (the ball then jumps at the seam).
        val s = height - r
        if (s <= margin) { val k = r + s * .5f; out.add(cx - t.nx * k, cy - t.ny * k, cz - t.nz * k, t.nx, t.ny, t.nz, s, feature) }
        return
    }
    val nx: Float; val ny: Float; val nz: Float
    if (l > 1e-6f) { nx = dx / l; ny = dy / l; nz = dz / l } else { nx = t.nx; ny = t.ny; nz = t.nz }
    val s = l - r
    val k = s * .5f
    out.add(qx + nx * k, qy + ny * k, qz + nz * k, nx, ny, nz, s, feature)
}

/** Signed distance to a one-sided triangle (negative right under it). */
private fun triangleDistance(t: Tri, x: Float, y: Float, z: Float, r: FloatArray): Float {
    closestInto(x, y, z, t, r)
    val qx = r[0]; val qy = r[1]; val qz = r[2]
    val h = (x - t.ax) * t.nx + (y - t.ay) * t.ny + (z - t.az) * t.nz
    if (h < 0f) {
        val ux = x - t.nx * h - qx; val uy = y - t.ny * h - qy; val uz = z - t.nz * h - qz
        if (ux * ux + uy * uy + uz * uz < 1e-6f) return h
    }
    val dx = x - qx; val dy = y - qy; val dz = z - qz
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * Narrows the range of segment a→b to the prism over triangle [t] (inside all three sides).
 * The sides are the edges crossed with the normal, as unit vectors.
 */
private fun clipToPrism(t: Tri, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: FloatArray): Boolean {
    for (j in 0..2) {
        val k = if (j == 2) 0 else j + 1
        val ex = t.vx(k) - t.vx(j); val ey = t.vy(k) - t.vy(j); val ez = t.vz(k) - t.vz(j)
        var sx = ey * t.nz - ez * t.ny; var sy = ez * t.nx - ex * t.nz; var sz = ex * t.ny - ey * t.nx
        val l = sqrt(sx * sx + sy * sy + sz * sz)
        if (l != 0f) { sx /= l; sy /= l; sz /= l }
        if (!clipRange(ax, ay, az, bx, by, bz, sx, sy, sz, t.vx(j) * sx + t.vy(j) * sy + t.vz(j) * sz, r)) return false
    }
    return true
}

/** Capsule on a triangle: the segment clipped by the triangle's sides gives up to two face points; otherwise one point at the closest approach. */
private fun triangleCapsule(t: Tri, cap: Capsule, pc: Pose, margin: Float, out: ContactPoints) {
    val hh = cap.halfHeight; val rad = cap.radius
    val e0x = pc.px - pc.yx * hh; val e0y = pc.py - pc.yy * hh; val e0z = pc.pz - pc.yz * hh
    val e1x = pc.px + pc.yx * hh; val e1y = pc.py + pc.yy * hh; val e1z = pc.pz + pc.yz * hh
    val h0 = (e0x - t.ax) * t.nx + (e0y - t.ay) * t.ny + (e0z - t.az) * t.nz
    val h1 = (e1x - t.ax) * t.nx + (e1y - t.ay) * t.ny + (e1z - t.az) * t.nz
    if (minOf(h0, h1) > rad + margin || maxOf(h0, h1) < -2f * rad) return
    val r = out.range; r[0] = 0f; r[1] = 1f
    val start = out.count
    var deepest = Float.MAX_VALUE
    if (clipToPrism(t, e0x, e0y, e0z, e1x, e1y, e1z, r)) {
        for (i in 0..1) {
            if (i == 1 && r[1] - r[0] < 1e-4f) break
            val u = r[i]
            val px = e0x + (e1x - e0x) * u; val py = e0y + (e1y - e0y) * u; val pz = e0z + (e1z - e0z) * u
            val s = ((px - t.ax) * t.nx + (py - t.ay) * t.ny + (pz - t.az) * t.nz) - rad
            if (s <= margin && s > -3f * rad) {
                val k = rad + s * .5f
                out.add(px - t.nx * k, py - t.ny * k, pz - t.nz * k, t.nx, t.ny, t.nz, s, i)
                deepest = min(deepest, s)
            }
        }
    }
    val near = out.near
    if (out.count == start) {
        val u = minimise { triangleDistance(t, e0x + (e1x - e0x) * it, e0y + (e1y - e0y) * it, e0z + (e1z - e0z) * it, near) }
        triangleSphere(t, e0x + (e1x - e0x) * u, e0y + (e1y - e0y) * u, e0z + (e1z - e0z) * u, rad, margin, out, 2)
    } else if (minOf(h0, h1) < rad * .5f) {
        // Deep: the axis can pierce the plane just outside the triangle while the capsule still covers it.
        // The part of the axis above the triangle then understates the overlap; the closest point does not.
        val u = minimise { triangleDistance(t, e0x + (e1x - e0x) * it, e0y + (e1y - e0y) * it, e0z + (e1z - e0z) * it, near) }
        val cx = e0x + (e1x - e0x) * u; val cy = e0y + (e1y - e0y) * u; val cz = e0z + (e1z - e0z) * u
        if (triangleDistance(t, cx, cy, cz, near) - rad < deepest - .01f) triangleSphere(t, cx, cy, cz, rad, margin, out, 2)
    }
}

/**
 * One candidate axis of the triangle–box test: the gap between the box and the triangle along l, or
 * false when they are apart by more than [margin]. A gap larger than the best by [tolerance] wins.
 */
private fun satAxis(sat: Sat, t: Tri, box: Box, pb: Pose, lx: Float, ly: Float, lz: Float,
                    kind: Int, axis: Int, edge: Int, tolerance: Float, margin: Float): Boolean {
    val r = supportRadius(box, pb, lx, ly, lz); val cp = pb.px * lx + pb.py * ly + pb.pz * lz
    val p0 = t.ax * lx + t.ay * ly + t.az * lz; val p1 = t.bx * lx + t.by * ly + t.bz * lz; val p2 = t.cx * lx + t.cy * ly + t.cz * lz
    val lo = minOf(p0, p1, p2); val hi = maxOf(p0, p1, p2)
    val above = (cp - r) - hi; val below = lo - (cp + r)
    val s = maxOf(above, below)
    if (s > margin) return false
    if (s > sat.best + tolerance) {
        sat.best = s; sat.kind = kind; sat.axis = axis; sat.edge = edge
        if (above >= below) { sat.nx = lx; sat.ny = ly; sat.nz = lz } else { sat.nx = -lx; sat.ny = -ly; sat.nz = -lz }
    }
    return true
}

private fun triangleBox(t: Tri, box: Box, pb: Pose, margin: Float, out: ContactPoints) {
    val cx = pb.px; val cy = pb.py; val cz = pb.pz
    // Triangle normal: one-sided, always pushes the box to the front.
    val sat = out.sat
    sat.best = (cx * t.nx + cy * t.ny + cz * t.nz) - supportRadius(box, pb, t.nx, t.ny, t.nz) - (t.ax * t.nx + t.ay * t.ny + t.az * t.nz)
    if (sat.best > margin) return
    sat.kind = 0; sat.axis = 0; sat.edge = 0; sat.nx = t.nx; sat.ny = t.ny; sat.nz = t.nz
    for (i in 0..2) if (!satAxis(sat, t, box, pb, pb.ax(i), pb.ay(i), pb.az(i), 1, i, 0, .005f, margin)) return
    for (i in 0..2) for (j in 0..2) {
        if (!t.active(j)) continue                       // inner edge: no ghost axis
        val k = if (j == 2) 0 else j + 1
        val ex = t.vx(k) - t.vx(j); val ey = t.vy(k) - t.vy(j); val ez = t.vz(k) - t.vz(j)
        val ux = pb.ax(i); val uy = pb.ay(i); val uz = pb.az(i)
        val crx = uy * ez - uz * ey; val cry = uz * ex - ux * ez; val crz = ux * ey - uy * ex
        val l = sqrt(crx * crx + cry * cry + crz * crz)
        if (l < 1e-4f * sqrt(ex * ex + ey * ey + ez * ez)) continue
        if (!satAxis(sat, t, box, pb, crx / l, cry / l, crz / l, 2, i, j, .01f, margin)) return
    }
    val start = out.count
    val p = out.poly; val q = out.spare
    when (sat.kind) {
        0 -> {  // triangle face: clip the box face that looks at it by the triangle's sides
            val m = mostAligned(pb, t.nx, t.ny, t.nz)
            boxFace(box, pb, m, if ((pb.ax(m) * t.nx + pb.ay(m) * t.ny + pb.az(m) * t.nz) > 0f) -1f else 1f, p)
            var from = p; var to = q
            for (j in 0..2) {
                val k = if (j == 2) 0 else j + 1
                val ex = t.vx(k) - t.vx(j); val ey = t.vy(k) - t.vy(j); val ez = t.vz(k) - t.vz(j)
                var sx = ey * t.nz - ez * t.ny; var sy = ez * t.nx - ex * t.nz; var sz = ex * t.ny - ey * t.nx
                val l = sqrt(sx * sx + sy * sy + sz * sz)
                if (l != 0f) { sx /= l; sy /= l; sz /= l }
                clip(from, to, sx, sy, sz, t.vx(j) * sx + t.vy(j) * sy + t.vz(j) * sz)
                val swap = from; from = to; to = swap
            }
            for (i in 0 until from.size) {
                val x = from.x[i]; val y = from.y[i]; val z = from.z[i]
                val s = (x - t.ax) * t.nx + (y - t.ay) * t.ny + (z - t.az) * t.nz
                if (s <= margin) { val h = s * .5f; out.add(x - t.nx * h, y - t.ny * h, z - t.nz * h, t.nx, t.ny, t.nz, s, i) }
            }
            out.reduce(start, t.nx, t.ny, t.nz)
        }
        1 -> {  // box face: clip the triangle by that face's sides
            val fx = -sat.nx; val fy = -sat.ny; val fz = -sat.nz    // box face normal pointing at the triangle
            val k = sat.axis
            p.size = 0; p.add(t.ax, t.ay, t.az); p.add(t.bx, t.by, t.bz); p.add(t.cx, t.cy, t.cz)
            for (j in 1..2) {
                val side = (k + j) % 3
                val ux = pb.ax(side); val uy = pb.ay(side); val uz = pb.az(side)
                clipSlab(p, q, ux, uy, uz, cx * ux + cy * uy + cz * uz, box.half(side))
            }
            val faceD = (cx * fx + cy * fy + cz * fz) + box.half(k)
            for (i in 0 until p.size) {
                val x = p.x[i]; val y = p.y[i]; val z = p.z[i]
                val s = (x * fx + y * fy + z * fz) - faceD
                if (s <= margin) { val h = s * .5f; out.add(x - fx * h, y - fy * h, z - fz * h, sat.nx, sat.ny, sat.nz, s, 20 + i) }
            }
            out.reduce(start, sat.nx, sat.ny, sat.nz)
        }
        else -> {  // box edge against triangle edge
            val nx = sat.nx; val ny = sat.ny; val nz = sat.nz
            var mx = cx; var my = cy; var mz = cz
            for (k in 0..2) if (k != sat.axis) {
                val ux = pb.ax(k); val uy = pb.ay(k); val uz = pb.az(k)
                val f = sign(nx * ux + ny * uy + nz * uz) * box.half(k)
                mx -= ux * f; my -= uy * f; mz -= uz * f
            }
            val h = box.half(sat.axis)
            val ex = pb.ax(sat.axis) * h; val ey = pb.ay(sat.axis) * h; val ez = pb.az(sat.axis) * h
            val j = sat.edge; val k = if (j == 2) 0 else j + 1
            val tax = t.vx(j); val tay = t.vy(j); val taz = t.vz(j); val tbx = t.vx(k); val tby = t.vy(k); val tbz = t.vz(k)
            val r = out.params
            segmentParams(tax, tay, taz, tbx, tby, tbz, mx - ex, my - ey, mz - ez, mx + ex, my + ey, mz + ez, r)
            val s = r[0]; val u = 2 * r[1]
            val qax = tax + (tbx - tax) * s; val qay = tay + (tby - tay) * s; val qaz = taz + (tbz - taz) * s
            val qbx = mx - ex + ex * u; val qby = my - ey + ey * u; val qbz = mz - ez + ez * u
            out.add((qax + qbx) * .5f, (qay + qby) * .5f, (qaz + qbz) * .5f, nx, ny, nz, sat.best, 40 + sat.axis * 3 + sat.edge)
        }
    }
}

/** Contacts between triangle [index] of [mesh] (as A) and a body shape (B). */
internal fun collideTriangle(mesh: TriangleMesh, index: Int, b: Shape, pb: Pose, margin: Float, out: ContactPoints) {
    val t = out.tri.load(mesh, index)
    when (b) {
        is Sphere -> triangleSphere(t, pb.px, pb.py, pb.pz, b.radius, margin, out, 0)
        is Capsule -> triangleCapsule(t, b, pb, margin, out)
        is Box -> triangleBox(t, b, pb, margin, out)
        is ConvexHull -> triangleHull(t, b.poly, pb, margin, out)
        is Compound -> {
            val first = out.count
            for (i in 0 until b.count) {
                // Each part near the triangle's plane; its index in the feature ids (bits 22–26).
                val q = out.partB.setPart(pb, b, i)
                val h = (q.px - t.ax) * t.nx + (q.py - t.ay) * t.ny + (q.pz - t.az) * t.nz
                if (h > b.radius[i] + margin || h < -2f * b.radius[i]) continue
                val start = out.count
                collideTriangle(mesh, index, b.shapes[i], q, margin, out)
                for (k in start until out.count) out.id[k] = (out.id[k] and 0x3FFFFF) or ((i and 31) shl 22)
            }
            out.reduceGroups(first)
        }
    }
}
