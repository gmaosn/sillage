package sillage.physics

import sillage.math.Vec3
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/** Position and orientation of a shape: centre and the world directions of its local axes. */
internal class Pose(val p: Vec3, val ax: Vec3, val ay: Vec3, val az: Vec3) {
    fun axis(i: Int) = when (i) { 0 -> ax; 1 -> ay; else -> az }
    fun toLocal(w: Vec3): Vec3 { val d = w - p; return Vec3(d dot ax, d dot ay, d dot az) }
    fun toWorld(l: Vec3) = p + ax * l.x + ay * l.y + az * l.z
}

/**
 * Contact points found between shape A and shape B. Each point has its own normal, pointing from A
 * to B, and a separation: negative when the shapes overlap, positive up to the speculative margin.
 */
internal class ContactPoints {
    val x = FloatArray(MAX); val y = FloatArray(MAX); val z = FloatArray(MAX)
    val nx = FloatArray(MAX); val ny = FloatArray(MAX); val nz = FloatArray(MAX)
    val sep = FloatArray(MAX); val id = IntArray(MAX)
    var count = 0

    fun clear() { count = 0 }
    fun add(point: Vec3, normal: Vec3, separation: Float, feature: Int) {
        if (count == MAX) return
        x[count] = point.x; y[count] = point.y; z[count] = point.z
        nx[count] = normal.x; ny[count] = normal.y; nz[count] = normal.z
        sep[count] = separation; id[count] = feature; count++
    }
    /** Turns the points of the last call around (B becomes A): normals flip. */
    fun flipFrom(start: Int) { for (i in start until count) { nx[i] = -nx[i]; ny[i] = -ny[i]; nz[i] = -nz[i] } }
    fun point(i: Int) = Vec3(x[i], y[i], z[i])

    /** Keeps at most four points from [start]: the deepest, the farthest from it, then the widest area. */
    fun reduce(start: Int, normal: Vec3) {
        val n = count - start
        if (n <= 4) return
        val keep = IntArray(4)
        keep[0] = (start until count).minBy { sep[it] }
        keep[1] = (start until count).maxBy { (point(it) - point(keep[0])).lengthSquared() }
        val a = point(keep[0]); val b = point(keep[1])
        fun area(i: Int) = ((b - a) cross (point(i) - a)) dot normal
        keep[2] = (start until count).maxBy { area(it) }
        keep[3] = (start until count).minBy { area(it) }
        val chosen = keep.distinct()
        val tmp = chosen.map { arrayOf(x[it], y[it], z[it], nx[it], ny[it], nz[it], sep[it], id[it].toFloat()) }
        count = start
        for (t in tmp) {
            x[count] = t[0]; y[count] = t[1]; z[count] = t[2]; nx[count] = t[3]; ny[count] = t[4]; nz[count] = t[5]
            sep[count] = t[6]; id[count] = t[7].toInt(); count++
        }
    }

    companion object { const val MAX = 16 }
}

private val UP = Vec3(0f, 1f, 0f)

private fun rank(s: Shape) = when (s) { is Sphere -> 0; is Capsule -> 1; is Box -> 2 }

/** Contacts between two body shapes, normal from A to B. [margin] is the speculative distance. */
internal fun collide(a: Shape, pa: Pose, b: Shape, pb: Pose, margin: Float, out: ContactPoints) {
    if (rank(a) > rank(b)) {
        val start = out.count
        collideOrdered(b, pb, a, pa, margin, out)
        out.flipFrom(start)
    } else collideOrdered(a, pa, b, pb, margin, out)
}

private fun collideOrdered(a: Shape, pa: Pose, b: Shape, pb: Pose, margin: Float, out: ContactPoints) {
    when (a) {
        is Sphere -> when (b) {
            is Sphere -> sphereSphere(pa.p, a.radius, pb.p, b.radius, margin, out, 0)
            is Capsule -> { val q = closestOnSegment(pa.p, segStart(b, pb), segEnd(b, pb)); sphereSphere(pa.p, a.radius, q, b.radius, margin, out, 0) }
            is Box -> sphereBox(pa.p, a.radius, b, pb, margin, out, 0, sphereIsA = true)
        }
        is Capsule -> when (b) {
            is Capsule -> capsuleCapsule(a, pa, b, pb, margin, out)
            is Box -> capsuleBox(a, pa, b, pb, margin, out)
            else -> error("unordered")
        }
        is Box -> if (b is Box) boxBox(a, pa, b, pb, margin, out) else error("unordered")
    }
}

// ------------------------------------------------------------------ primitives

private fun segStart(c: Capsule, p: Pose) = p.p - p.ay * c.halfHeight
private fun segEnd(c: Capsule, p: Pose) = p.p + p.ay * c.halfHeight

private fun closestParam(p: Vec3, a: Vec3, b: Vec3): Float {
    val ab = b - a; val l = ab.lengthSquared()
    return if (l < 1e-12f) 0f else ((p - a) dot ab / l).coerceIn(0f, 1f)
}
private fun closestOnSegment(p: Vec3, a: Vec3, b: Vec3) = a + (b - a) * closestParam(p, a, b)

/** Closest points between segments p1-q1 and p2-q2 (Ericson 5.1.9): returns (s, t). */
private fun segmentParams(p1: Vec3, q1: Vec3, p2: Vec3, q2: Vec3): Pair<Float, Float> {
    val d1 = q1 - p1; val d2 = q2 - p2; val r = p1 - p2
    val a = d1.lengthSquared(); val e = d2.lengthSquared(); val f = d2 dot r
    if (a <= 1e-12f && e <= 1e-12f) return 0f to 0f
    if (a <= 1e-12f) return 0f to (f / e).coerceIn(0f, 1f)
    val c = d1 dot r
    if (e <= 1e-12f) return (-c / a).coerceIn(0f, 1f) to 0f
    val b = d1 dot d2; val denom = a * e - b * b
    var s = if (denom != 0f) ((b * f - c * e) / denom).coerceIn(0f, 1f) else 0f
    var t = (b * s + f) / e
    if (t < 0f) { t = 0f; s = (-c / a).coerceIn(0f, 1f) }
    else if (t > 1f) { t = 1f; s = ((b - c) / a).coerceIn(0f, 1f) }
    return s to t
}

private fun sphereSphere(ca: Vec3, ra: Float, cb: Vec3, rb: Float, margin: Float, out: ContactPoints, feature: Int) {
    val d = cb - ca
    val l2 = d.lengthSquared(); val reach = ra + rb + margin
    if (l2 > reach * reach) return
    val l = sqrt(l2)
    val n = if (l > 1e-6f) d / l else UP
    val s = l - ra - rb
    out.add(ca + n * (ra + s * .5f), n, s, feature)
}

/** Sphere against box; the normal goes from the sphere to the box when [sphereIsA]. */
private fun sphereBox(c: Vec3, r: Float, box: Box, pose: Pose, margin: Float, out: ContactPoints, feature: Int, sphereIsA: Boolean) {
    val l = pose.toLocal(c)
    val q = Vec3(l.x.coerceIn(-box.halfX, box.halfX), l.y.coerceIn(-box.halfY, box.halfY), l.z.coerceIn(-box.halfZ, box.halfZ))
    val nBox: Vec3; val s: Float; val surface: Vec3
    if (q.x == l.x && q.y == l.y && q.z == l.z) {
        // Centre inside the box: leave through the nearest face.
        val depth = FloatArray(3) { box.half(it) - abs(l[it]) }
        val k = (0..2).minBy { depth[it] }
        val sgn = if (l[k] >= 0f) 1f else -1f
        nBox = pose.axis(k) * sgn
        s = -(depth[k] + r)
        surface = pose.toWorld(Vec3(if (k == 0) sgn * box.halfX else l.x, if (k == 1) sgn * box.halfY else l.y, if (k == 2) sgn * box.halfZ else l.z))
    } else {
        val dw = c - pose.toWorld(q)
        val d = dw.length()
        if (d > r + margin) return
        nBox = dw / d
        s = d - r
        surface = pose.toWorld(q)
    }
    val point = surface + nBox * (s * .5f)
    out.add(point, if (sphereIsA) -nBox else nBox, s, feature)
}

private fun capsuleCapsule(a: Capsule, pa: Pose, b: Capsule, pb: Pose, margin: Float, out: ContactPoints) {
    val a0 = segStart(a, pa); val a1 = segEnd(a, pa); val b0 = segStart(b, pb); val b1 = segEnd(b, pb)
    val da = a1 - a0; val db = b1 - b0
    val parallel = (da cross db).lengthSquared() < 1e-6f * da.lengthSquared() * db.lengthSquared()
    if (parallel && da.lengthSquared() > 1e-8f && db.lengthSquared() > 1e-8f) {
        // Lying side by side: one contact at each end of the overlap.
        val t0 = closestParam(b0, a0, a1); val t1 = closestParam(b1, a0, a1)
        val lo = minOf(t0, t1); val hi = maxOf(t0, t1)
        if (hi - lo > 1e-3f) {
            for ((k, t) in listOf(lo, hi).withIndex()) {
                val pA = a0 + da * t
                sphereSphere(pA, a.radius, closestOnSegment(pA, b0, b1), b.radius, margin, out, k)
            }
            return
        }
    }
    val (s, t) = segmentParams(a0, a1, b0, b1)
    sphereSphere(a0 + da * s, a.radius, b0 + db * t, b.radius, margin, out, 0)
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

private fun boxDistance(p: Vec3, box: Box, pose: Pose): Float {
    val l = pose.toLocal(p)
    val dx = maxOf(abs(l.x) - box.halfX, 0f); val dy = maxOf(abs(l.y) - box.halfY, 0f); val dz = maxOf(abs(l.z) - box.halfZ, 0f)
    // Inside: negative depth to the nearest face keeps the function convex.
    val inside = minOf(box.halfX - abs(l.x), box.halfY - abs(l.y), box.halfZ - abs(l.z))
    return if (dx == 0f && dy == 0f && dz == 0f) -inside else sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * Parameter of the segment point closest to the box (both in box space), by alternating projections:
 * box point nearest the segment point, then segment point nearest that box point. When the segment
 * goes through the box, the deepest point along it (largest margin to the nearest face) is kept.
 */
private fun segmentBoxParam(a: Vec3, b: Vec3, box: Box): Float {
    val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
    val len2 = dx * dx + dy * dy + dz * dz
    if (len2 < 1e-12f) return 0f
    var t = .5f
    repeat(8) {
        val px = a.x + dx * t; val py = a.y + dy * t; val pz = a.z + dz * t
        val qx = px.coerceIn(-box.halfX, box.halfX); val qy = py.coerceIn(-box.halfY, box.halfY); val qz = pz.coerceIn(-box.halfZ, box.halfZ)
        if (qx == px && qy == py && qz == pz) {
            // Inside: move towards the point of the segment deepest in the box (sampled, then kept).
            var best = t; var depth = -1f
            for (k in 0..8) {
                val u = k / 8f
                val x = a.x + dx * u; val y = a.y + dy * u; val z = a.z + dz * u
                val dpt = minOf(box.halfX - abs(x), box.halfY - abs(y), box.halfZ - abs(z))
                if (dpt > depth) { depth = dpt; best = u }
            }
            return best
        }
        t = (((qx - a.x) * dx + (qy - a.y) * dy + (qz - a.z) * dz) / len2).coerceIn(0f, 1f)
    }
    return t
}

/** Range of t ∈ [lo, hi] where a + (b − a)·t satisfies dot(x, n) ≤ d, or null when empty. */
private fun clipRange(a: Vec3, b: Vec3, n: Vec3, d: Float, lo: Float, hi: Float): FloatArray? {
    val da = (a dot n) - d; val db = (b dot n) - d
    if (da <= 0f && db <= 0f) return floatArrayOf(lo, hi)
    if (da > 0f && db > 0f) return null
    val t = da / (da - db)
    return if (da > 0f) (if (t > hi) null else floatArrayOf(maxOf(lo, t), hi)) else (if (t < lo) null else floatArrayOf(lo, minOf(hi, t)))
}

/**
 * Capsule against box, after Box2D's capsule–polygon: the reference face is found from the closest
 * point; the segment is clipped by that face's sides, giving two steady points when the capsule lies
 * on the face. Outside a face region (edges, corners), one point at the closest approach.
 */
private fun capsuleBox(c: Capsule, pc: Pose, box: Box, pb: Pose, margin: Float, out: ContactPoints) {
    val e0 = segStart(c, pc); val e1 = segEnd(c, pc)
    val t = segmentBoxParam(pb.toLocal(e0), pb.toLocal(e1), box)
    val m = e0 + (e1 - e0) * t
    val l = pb.toLocal(m)
    val saturated = (0..2).count { abs(l[it]) >= box.half(it) }
    val k: Int; val sgn: Float
    when {
        saturated == 1 -> { k = (0..2).first { abs(l[it]) >= box.half(it) }; sgn = if (l[k] > 0f) 1f else -1f }
        saturated == 0 -> { k = (0..2).minBy { box.half(it) - abs(l[it]) }; sgn = if (l[k] >= 0f) 1f else -1f }
        else -> { sphereBox(m, c.radius, box, pb, margin, out, 9, true); return }   // arête ou coin : un seul point
    }
    val f = pb.axis(k) * sgn                                   // face normal, out of the box towards the capsule
    var range: FloatArray? = floatArrayOf(0f, 1f)
    for (side in listOf((k + 1) % 3, (k + 2) % 3)) {
        val ax = pb.axis(side); val h = box.half(side)
        range = range?.let { clipRange(e0, e1, ax, (pb.p dot ax) + h, it[0], it[1]) }
        range = range?.let { clipRange(e0, e1, -ax, -(pb.p dot ax) + h, it[0], it[1]) }
    }
    if (range == null) { sphereBox(m, c.radius, box, pb, margin, out, 9, true); return }
    val faceD = (pb.p dot f) + box.half(k)
    for ((i, u) in range.withIndex()) {
        if (i == 1 && range[1] - range[0] < 1e-4f) break
        val p = e0 + (e1 - e0) * u
        val s = (p dot f) - faceD - c.radius
        if (s <= margin) out.add(p - f * (c.radius + s * .5f), -f, s, k * 4 + (if (sgn > 0f) 2 else 0) + i)
    }
}

// ------------------------------------------------------------------ box against box

private fun boxSupportRadius(b: Box, p: Pose, axis: Vec3) =
    b.halfX * abs(axis dot p.ax) + b.halfY * abs(axis dot p.ay) + b.halfZ * abs(axis dot p.az)

/** Sutherland–Hodgman: keeps the part of [poly] where dot(x, n) ≤ d. */
private fun clip(poly: List<Vec3>, n: Vec3, d: Float): List<Vec3> {
    if (poly.isEmpty()) return poly
    val out = ArrayList<Vec3>(poly.size + 2)
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        val da = (a dot n) - d; val db = (b dot n) - d
        if (da <= 0f) out.add(a)
        if ((da < 0f && db > 0f) || (da > 0f && db < 0f)) out.add(a + (b - a) * (da / (da - db)))
    }
    return out
}

/** Corners of the face of box [b] with outward normal axis k × sign. */
private fun boxFace(b: Box, p: Pose, k: Int, sgn: Float): List<Vec3> {
    val u = (k + 1) % 3; val v = (k + 2) % 3
    val c = p.p + p.axis(k) * (sgn * b.half(k))
    val du = p.axis(u) * b.half(u); val dv = p.axis(v) * b.half(v)
    return listOf(c + du + dv, c - du + dv, c - du - dv, c + du - dv)
}

private fun boxBox(a: Box, pa: Pose, b: Box, pb: Pose, margin: Float, out: ContactPoints) {
    val t = pb.p - pa.p
    var bestA = -Float.MAX_VALUE; var axisA = 0
    var bestB = -Float.MAX_VALUE; var axisB = 0
    var bestE = -Float.MAX_VALUE; var edgeI = 0; var edgeJ = 0; var edgeN = UP
    fun separation(l: Vec3) = abs(t dot l) - boxSupportRadius(a, pa, l) - boxSupportRadius(b, pb, l)
    for (i in 0..2) {
        val s = separation(pa.axis(i)); if (s > margin) return
        if (s > bestA) { bestA = s; axisA = i }
    }
    for (j in 0..2) {
        val s = separation(pb.axis(j)); if (s > margin) return
        if (s > bestB) { bestB = s; axisB = j }
    }
    for (i in 0..2) for (j in 0..2) {
        val c = pa.axis(i) cross pb.axis(j)
        val l = c.length()
        if (l < 1e-4f) continue
        val n = c / l
        val s = separation(n); if (s > margin) return
        if (s > bestE) { bestE = s; edgeI = i; edgeJ = j; edgeN = n }
    }
    val bestFace = maxOf(bestA, bestB)
    if (bestE > bestFace + .01f) {
        // Edge against edge: one point, between the two supporting edges.
        val n = if ((t dot edgeN) < 0f) -edgeN else edgeN
        var ma = pa.p; var mb = pb.p
        for (k in 0..2) {
            if (k != edgeI) ma += pa.axis(k) * (sign(n dot pa.axis(k)) * a.half(k))
            if (k != edgeJ) mb -= pb.axis(k) * (sign(n dot pb.axis(k)) * b.half(k))
        }
        val ea = pa.axis(edgeI) * a.half(edgeI); val eb = pb.axis(edgeJ) * b.half(edgeJ)
        val (s, u) = segmentParams(ma - ea, ma + ea, mb - eb, mb + eb)
        val qa = ma - ea + ea * (2 * s); val qb = mb - eb + eb * (2 * u)
        out.add((qa + qb) * .5f, n, bestE, 100 + edgeI * 3 + edgeJ)
        return
    }
    // Face contact; prefer A's face unless B's is clearly better.
    val refIsA = bestA >= bestB - .005f
    val ref = if (refIsA) a else b; val rp = if (refIsA) pa else pb
    val inc = if (refIsA) b else a; val ip = if (refIsA) pb else pa
    val k = if (refIsA) axisA else axisB
    val toInc = if (refIsA) t else -t
    val nRef = rp.axis(k) * (if ((toInc dot rp.axis(k)) >= 0f) 1f else -1f)
    val m = (0..2).maxBy { abs(ip.axis(it) dot nRef) }
    var poly = boxFace(inc, ip, m, if ((ip.axis(m) dot nRef) > 0f) -1f else 1f)
    for (side in listOf((k + 1) % 3, (k + 2) % 3)) {
        val ax = rp.axis(side); val h = ref.half(side)
        poly = clip(poly, ax, (rp.p dot ax) + h)
        poly = clip(poly, -ax, -(rp.p dot ax) + h)
    }
    val faceD = (rp.p dot nRef) + ref.half(k)
    val normal = if (refIsA) nRef else -nRef
    val start = out.count
    for ((i, x) in poly.withIndex()) {
        val s = (x dot nRef) - faceD
        if (s <= margin) out.add(x - nRef * (s * .5f), normal, s, (if (refIsA) 0 else 50) + k * 10 + i)
    }
    out.reduce(start, normal)
}

// ------------------------------------------------------------------ triangles (static mesh as A)

/** Closest point on triangle abc to p (Ericson 5.1.5). */
internal fun closestOnTriangle(p: Vec3, a: Vec3, b: Vec3, c: Vec3): Vec3 {
    val ab = b - a; val ac = c - a; val ap = p - a
    val d1 = ab dot ap; val d2 = ac dot ap
    if (d1 <= 0f && d2 <= 0f) return a
    val bp = p - b; val d3 = ab dot bp; val d4 = ac dot bp
    if (d3 >= 0f && d4 <= d3) return b
    val vc = d1 * d4 - d3 * d2
    if (vc <= 0f && d1 >= 0f && d3 <= 0f) return a + ab * (d1 / (d1 - d3))
    val cp = p - c; val d5 = ab dot cp; val d6 = ac dot cp
    if (d6 >= 0f && d5 <= d6) return c
    val vb = d5 * d2 - d1 * d6
    if (vb <= 0f && d2 >= 0f && d6 <= 0f) return a + ac * (d2 / (d2 - d6))
    val va = d3 * d6 - d5 * d4
    if (va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f) return b + (c - b) * ((d4 - d3) / ((d4 - d3) + (d5 - d6)))
    val denom = 1f / (va + vb + vc)
    return a + ab * (vb * denom) + ac * (vc * denom)
}

/** One triangle of a static mesh; [edges] holds the active-edge bits (a→b, b→c, c→a). */
internal class Tri(val a: Vec3, val b: Vec3, val c: Vec3, val n: Vec3, val edges: Int = 7) {
    fun active(j: Int) = edges and (1 shl j) != 0
}

/** Edge (0, 1, 2) that point q lies on, −1 for the interior; a vertex counts as both its edges. */
private fun edgeMask(t: Tri, q: Vec3): Int {
    var mask = 0
    val v = arrayOf(t.a, t.b, t.c)
    for (j in 0..2) if ((closestOnSegment(q, v[j], v[(j + 1) % 3]) - q).lengthSquared() < 1e-10f) mask = mask or (1 shl j)
    return mask
}

/** Sphere on a one-sided triangle; normal from the triangle to the sphere. */
private fun triangleSphere(t: Tri, c: Vec3, r: Float, margin: Float, out: ContactPoints, feature: Int) {
    val q = closestOnTriangle(c, t.a, t.b, t.c)
    val height = (c - t.a) dot t.n
    if (height < 0f) {
        // Behind the face: only push out when the centre is right under the triangle and not too deep.
        if ((c - t.n * height - q).lengthSquared() > 1e-6f || height < -2f * r) return
        val sep = height - r
        out.add(c - t.n * height + t.n * (sep * .5f), t.n, sep, feature)   // milieu entre le plan et le point le plus bas
        return
    }
    val d = c - q; val l = d.length()
    if (l > r + margin) return
    val mask = edgeMask(t, q)
    if (mask != 0 && (mask and t.edges) == 0) {
        // Seam between flat neighbours: take the face normal, not the one towards the edge. The point
        // must lie under the centre along that normal: an off-centre point would turn with a rolling
        // ball and fake a penetration (the ball then jumps at the seam).
        val s = height - r
        if (s <= margin) out.add(c - t.n * (r + s * .5f), t.n, s, feature)
        return
    }
    val n = if (l > 1e-6f) d / l else t.n
    val s = l - r
    out.add(q + n * (s * .5f), n, s, feature)
}

private fun triangleDistance(t: Tri, p: Vec3): Float {
    val q = closestOnTriangle(p, t.a, t.b, t.c)
    val h = (p - t.a) dot t.n
    return if (h < 0f && (p - t.n * h - q).lengthSquared() < 1e-6f) h else (p - q).length()
}

/** Capsule on a triangle: the segment clipped by the triangle's sides gives up to two face points; otherwise one point at the closest approach. */
private fun triangleCapsule(t: Tri, cap: Capsule, pc: Pose, margin: Float, out: ContactPoints) {
    val e0 = segStart(cap, pc); val e1 = segEnd(cap, pc)
    val h0 = (e0 - t.a) dot t.n; val h1 = (e1 - t.a) dot t.n
    if (minOf(h0, h1) > cap.radius + margin || maxOf(h0, h1) < -2f * cap.radius) return
    var range: FloatArray? = floatArrayOf(0f, 1f)
    val v = arrayOf(t.a, t.b, t.c)
    for (j in 0..2) {
        val side = ((v[(j + 1) % 3] - v[j]) cross t.n).normalized()
        range = range?.let { clipRange(e0, e1, side, v[j] dot side, it[0], it[1]) }
    }
    val start = out.count
    if (range != null) {
        for ((i, u) in range.withIndex()) {
            if (i == 1 && range[1] - range[0] < 1e-4f) break
            val p = e0 + (e1 - e0) * u
            val s = ((p - t.a) dot t.n) - cap.radius
            if (s <= margin && s > -3f * cap.radius) out.add(p - t.n * (cap.radius + s * .5f), t.n, s, i)
        }
    }
    if (out.count == start) {
        val u = minimise { triangleDistance(t, e0 + (e1 - e0) * it) }
        triangleSphere(t, e0 + (e1 - e0) * u, cap.radius, margin, out, 2)
    }
}

private fun triangleBox(t: Tri, box: Box, pb: Pose, margin: Float, out: ContactPoints) {
    val v = arrayOf(t.a, t.b, t.c)
    val c = pb.p
    // Triangle normal: one-sided, always pushes the box to the front.
    val r0 = boxSupportRadius(box, pb, t.n)
    var best = (c dot t.n) - r0 - (t.a dot t.n)
    if (best > margin) return
    var kind = 0; var bestAxis = 0; var bestEdge = 0; var bestN = t.n
    fun test(l: Vec3, k: Int, axis: Int, edge: Int, tolerance: Float): Boolean {
        val r = boxSupportRadius(box, pb, l); val cp = c dot l
        val p0 = v[0] dot l; val p1 = v[1] dot l; val p2 = v[2] dot l
        val lo = minOf(p0, p1, p2); val hi = maxOf(p0, p1, p2)
        val above = (cp - r) - hi; val below = lo - (cp + r)
        val s = maxOf(above, below)
        if (s > margin) return false
        if (s > best + tolerance) { best = s; kind = k; bestAxis = axis; bestEdge = edge; bestN = if (above >= below) l else -l }
        return true
    }
    for (i in 0..2) if (!test(pb.axis(i), 1, i, 0, .005f)) return
    for (i in 0..2) for (j in 0..2) {
        if (!t.active(j)) continue                       // arête intérieure : pas d'axe fantôme
        val e = v[(j + 1) % 3] - v[j]
        val cr = pb.axis(i) cross e; val l = cr.length()
        if (l < 1e-4f * e.length()) continue
        if (!test(cr / l, 2, i, j, .01f)) return
    }
    val start = out.count
    when (kind) {
        0 -> {  // triangle face: clip the box face that looks at it by the triangle's sides
            val m = (0..2).maxBy { abs(pb.axis(it) dot t.n) }
            var poly = boxFace(box, pb, m, if ((pb.axis(m) dot t.n) > 0f) -1f else 1f)
            for (j in 0..2) {
                val side = ((v[(j + 1) % 3] - v[j]) cross t.n).normalized()
                poly = clip(poly, side, v[j] dot side)
            }
            for ((i, x) in poly.withIndex()) {
                val s = (x - t.a) dot t.n
                if (s <= margin) out.add(x - t.n * (s * .5f), t.n, s, i)
            }
            out.reduce(start, t.n)
        }
        1 -> {  // box face: clip the triangle by that face's sides
            val f = -bestN                               // box face normal pointing at the triangle
            val k = bestAxis
            var poly: List<Vec3> = v.toList()
            for (side in listOf((k + 1) % 3, (k + 2) % 3)) {
                val ax = pb.axis(side); val h = box.half(side)
                poly = clip(poly, ax, (c dot ax) + h)
                poly = clip(poly, -ax, -(c dot ax) + h)
            }
            val faceD = (c dot f) + box.half(k)
            for ((i, x) in poly.withIndex()) {
                val s = (x dot f) - faceD
                if (s <= margin) out.add(x - f * (s * .5f), bestN, s, 20 + i)
            }
            out.reduce(start, bestN)
        }
        else -> {  // box edge against triangle edge
            val n = bestN
            var mid = c
            for (k in 0..2) if (k != bestAxis) mid -= pb.axis(k) * (sign(n dot pb.axis(k)) * box.half(k))
            val e = pb.axis(bestAxis) * box.half(bestAxis)
            val ta = v[bestEdge]; val tb = v[(bestEdge + 1) % 3]
            val (s, u) = segmentParams(ta, tb, mid - e, mid + e)
            val qa = ta + (tb - ta) * s; val qb = mid - e + e * (2 * u)
            out.add((qa + qb) * .5f, n, best, 40 + bestAxis * 3 + bestEdge)
        }
    }
}

/** Contacts between triangle [t] of a static mesh (A) and a body shape (B). */
internal fun collideTriangle(t: Tri, b: Shape, pb: Pose, margin: Float, out: ContactPoints) {
    when (b) {
        is Sphere -> triangleSphere(t, pb.p, b.radius, margin, out, 0)
        is Capsule -> triangleCapsule(t, b, pb, margin, out)
        is Box -> triangleBox(t, b, pb, margin, out)
    }
}
