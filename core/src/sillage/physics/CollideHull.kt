package sillage.physics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// The narrow phase of convex polyhedra (hulls, and boxes against hulls): the separating axis test over the
// faces of both and the edge pairs that make a face of their Minkowski difference (Gauss map pruning, after
// D. Gregorius, GDC 2013), then the incident face clipped against the reference face's sides, as box–box does,
// into at most four points. Spheres and capsules against a hull use the hull's closest features. Work is done
// in one shape's frame; nothing is allocated.

// Choosing the axis: an edge pair beats the faces, and the second shape's face the first's, only when clearly
// better (Gregorius's tolerances: relative, so that small and large shapes behave alike; absolute below 2.5 mm).
private const val REL_EDGE = .9f
private const val REL_FACE = .98f
private const val ABS_TOL = .0025f

/** A polygon being clipped, each corner with a feature key (where it came from). */
internal class ClipPoly {
    var x = FloatArray(16); var y = FloatArray(16); var z = FloatArray(16); var key = IntArray(16)
    var size = 0
    fun add(px: Float, py: Float, pz: Float, k: Int) {
        if (size == x.size) { val c = size * 2; x = x.copyOf(c); y = y.copyOf(c); z = z.copyOf(c); key = key.copyOf(c) }
        x[size] = px; y[size] = py; z[size] = pz; key[size] = k; size++
    }
}

/** The scratch of the polytope routines (one per [ContactPoints], so one per thread). */
internal class HullScratch {
    val box = boxPolytope()
    var bv = FloatArray(3 * 32); var bn = FloatArray(4 * 32); var be = FloatArray(3 * 64); var bg = FloatArray(3 * 64)
    var near2 = IntArray(64)                 // B's edges that may beat the faces
    var sides = FloatArray(4 * 16)           // the reference face's side planes, in the working frame
    val p = ClipPoly(); val q = ClipPoly()
    /** Closest feature of [pointHull]: the point (x, y, z), then the outward normal there. */
    val near = FloatArray(6)
    // B's frame seen from A's: rotation (rows) and translation.
    var r00 = 1f; var r01 = 0f; var r02 = 0f; var r10 = 0f; var r11 = 1f; var r12 = 0f; var r20 = 0f; var r21 = 0f; var r22 = 1f
    var tx = 0f; var ty = 0f; var tz = 0f

    fun ensure(vertices: Int, faces: Int, edges: Int) {
        if (bv.size < 3 * vertices) bv = FloatArray(3 * vertices)
        if (bn.size < 4 * faces) bn = FloatArray(4 * faces)
        if (be.size < 3 * edges) { be = FloatArray(3 * edges); bg = FloatArray(3 * edges); near2 = IntArray(edges) }
    }

    /** B's pose in A's frame: R = Raᵀ Rb, t = Raᵀ (pb − pa). */
    fun relate(pa: Pose, pb: Pose) {
        r00 = pa.xx * pb.xx + pa.xy * pb.xy + pa.xz * pb.xz; r01 = pa.xx * pb.yx + pa.xy * pb.yy + pa.xz * pb.yz; r02 = pa.xx * pb.zx + pa.xy * pb.zy + pa.xz * pb.zz
        r10 = pa.yx * pb.xx + pa.yy * pb.xy + pa.yz * pb.xz; r11 = pa.yx * pb.yx + pa.yy * pb.yy + pa.yz * pb.yz; r12 = pa.yx * pb.zx + pa.yy * pb.zy + pa.yz * pb.zz
        r20 = pa.zx * pb.xx + pa.zy * pb.xy + pa.zz * pb.xz; r21 = pa.zx * pb.yx + pa.zy * pb.yy + pa.zz * pb.yz; r22 = pa.zx * pb.zx + pa.zy * pb.zy + pa.zz * pb.zz
        tx = pa.localX(pb.px, pb.py, pb.pz); ty = pa.localY(pb.px, pb.py, pb.pz); tz = pa.localZ(pb.px, pb.py, pb.pz)
    }
}

// ------------------------------------------------------------------ helpers

/** Sutherland–Hodgman with feature keys: the part of [src] where dot(x, n) ≤ d, into [dst]. A new corner is keyed by the plane and the edge it cut. */
private fun clipKeyed(src: ClipPoly, dst: ClipPoly, nx: Float, ny: Float, nz: Float, d: Float, plane: Int) {
    dst.size = 0
    val n = src.size
    for (i in 0 until n) {
        val j = if (i + 1 == n) 0 else i + 1
        val ax = src.x[i]; val ay = src.y[i]; val az = src.z[i]
        val bx = src.x[j]; val by = src.y[j]; val bz = src.z[j]
        val da = (ax * nx + ay * ny + az * nz) - d; val db = (bx * nx + by * ny + bz * nz) - d
        if (da <= 0f) dst.add(ax, ay, az, src.key[i])
        if ((da < 0f && db > 0f) || (da > 0f && db < 0f)) {
            val t = da / (da - db)
            dst.add(ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, 4096 or ((plane and 63) shl 6) or (src.key[i] and 63))
        }
    }
}

/**
 * Signed distance from the point (x, y, z) to polytope [h] (its own frame): positive outside, the depth
 * below the nearest face (negative) inside. The closest point of the surface and the outward normal there
 * go to [near]. Exact: the face it is farthest in front of when its projection falls inside that face,
 * else the nearest edge of the faces it is in front of.
 */
internal fun pointHull(h: Polytope, x: Float, y: Float, z: Float, near: FloatArray): Float {
    val pl = h.plane
    var best = -Float.MAX_VALUE; var bf = 0
    for (f in 0 until h.faceCount) {
        val o = 4 * f
        val s = pl[o] * x + pl[o + 1] * y + pl[o + 2] * z - pl[o + 3]
        if (s > best) { best = s; bf = f }
    }
    if (best <= 0f) {
        val o = 4 * bf
        near[0] = x - pl[o] * best; near[1] = y - pl[o + 1] * best; near[2] = z - pl[o + 2] * best
        near[3] = pl[o]; near[4] = pl[o + 1]; near[5] = pl[o + 2]
        return best
    }
    // Outside: the face the point is farthest in front of, if its polygon holds the point's projection (the
    // distance is at least that; another face's projection may fall inside its polygon and be nearer, wrongly).
    run {
        val f = bf; val o = 4 * f; val s = best
        val qx = x - pl[o] * s; val qy = y - pl[o + 1] * s; val qz = z - pl[o + 2] * s
        var inside = true
        for (k in h.faceStart[f] until h.faceStart[f + 1]) {
            val so = 4 * k
            if (h.side[so] * qx + h.side[so + 1] * qy + h.side[so + 2] * qz - h.side[so + 3] > 0f) { inside = false; break }
        }
        if (inside) { near[0] = qx; near[1] = qy; near[2] = qz; near[3] = pl[o]; near[4] = pl[o + 1]; near[5] = pl[o + 2]; return s }
    }
    // Else an edge (or a corner) of a face it is in front of.
    var d2 = Float.MAX_VALUE
    val v = h.v
    for (f in 0 until h.faceCount) {
        val o = 4 * f
        if (pl[o] * x + pl[o + 1] * y + pl[o + 2] * z - pl[o + 3] <= 0f) continue
        val s0 = h.faceStart[f]; val s1 = h.faceStart[f + 1]
        for (k in s0 until s1) {
            val a = h.loop[k]; val b = h.loop[if (k + 1 == s1) s0 else k + 1]
            val ax = v[3 * a]; val ay = v[3 * a + 1]; val az = v[3 * a + 2]
            val t = closestParam(x, y, z, ax, ay, az, v[3 * b], v[3 * b + 1], v[3 * b + 2])
            val qx = ax + (v[3 * b] - ax) * t; val qy = ay + (v[3 * b + 1] - ay) * t; val qz = az + (v[3 * b + 2] - az) * t
            val dx = x - qx; val dy = y - qy; val dz = z - qz
            val e = dx * dx + dy * dy + dz * dz
            if (e < d2) { d2 = e; near[0] = qx; near[1] = qy; near[2] = qz }
        }
    }
    val d = sqrt(d2)
    near[3] = (x - near[0]) / d; near[4] = (y - near[1]) / d; near[5] = (z - near[2]) / d
    return d
}

/**
 * The most that n·(wx, wy, wz) reaches for a unit n in the cone of edge [e] of [h] (its outward normals),
 * the cone turned by the rows (r0.., r1.., r2..) and negated when [flip]: the separation an edge pair can
 * reach is bounded by it, so edges that cannot beat the faces are skipped (with a hair of slack).
 */
private fun coneReach(h: Polytope, e: Int, r00: Float, r01: Float, r02: Float, r10: Float, r11: Float, r12: Float, r20: Float, r21: Float, r22: Float,
                      flip: Boolean, wx: Float, wy: Float, wz: Float): Float {
    val c = h.edgeCone; val o = 5 * e
    var mx = r00 * c[o] + r01 * c[o + 1] + r02 * c[o + 2]; var my = r10 * c[o] + r11 * c[o + 1] + r12 * c[o + 2]; var mz = r20 * c[o] + r21 * c[o + 1] + r22 * c[o + 2]
    if (flip) { mx = -mx; my = -my; mz = -mz }
    val mw = mx * wx + my * wy + mz * wz; val w2 = wx * wx + wy * wy + wz * wz
    val ca = c[o + 3]; val sa = c[o + 4]
    val reach = if (mw * mw >= ca * ca * w2 && mw >= 0f) sqrt(w2) else mw * ca + sqrt(max(0f, w2 - mw * mw)) * sa
    return reach + 1e-5f * (1f + sqrt(w2))
}

/**
 * The incident face of [h] for a reference normal (nx, ny, nz): of the faces around the vertex deepest along
 * it ([v] holds h's vertices in the frame of the normal, [pl] its face normals every 4 floats), the most
 * opposed. The face most opposed of all may miss that vertex (a pointed rock on the floor then met nothing).
 */
private fun incidentFace(h: Polytope, v: FloatArray, pl: FloatArray, nx: Float, ny: Float, nz: Float): Int {
    var low = 0; var least = Float.MAX_VALUE
    for (i in 0 until h.vertexCount) { val d = nx * v[3 * i] + ny * v[3 * i + 1] + nz * v[3 * i + 2]; if (d < least) { least = d; low = i } }
    var g = 0; var best = Float.MAX_VALUE
    for (f in 0 until h.faceCount) {
        var has = false
        for (k in h.faceStart[f] until h.faceStart[f + 1]) if (h.loop[k] == low) { has = true; break }
        if (!has) continue
        val c = pl[4 * f] * nx + pl[4 * f + 1] * ny + pl[4 * f + 2] * nz
        if (c < best) { best = c; g = f }
    }
    return g
}

/**
 * Clips the incident face [g] of [h] (vertices [v] in the working frame) by the [m] side planes in the
 * scratch's [HullScratch.sides], then adds the other vertices of [h] within [margin] of the reference plane
 * (n, d), and within 5 mm of its lowest, inside the sides: a rock resting on several facets of its rounded bottom stands on all of them,
 * not only on the facet around its lowest vertex (it rocked from one to the next for ever). Returns the poly.
 */
private fun supportPoints(sc: HullScratch, h: Polytope, v: FloatArray, g: Int, m: Int, nx: Float, ny: Float, nz: Float, d: Float, margin: Float): ClipPoly {
    var from = sc.p; var to = sc.q
    from.size = 0
    for (k in h.faceStart[g] until h.faceStart[g + 1]) { val vi = h.loop[k]; from.add(v[3 * vi], v[3 * vi + 1], v[3 * vi + 2], k - h.faceStart[g]) }
    val sd = sc.sides
    for (j in 0 until m) { clipKeyed(from, to, sd[4 * j], sd[4 * j + 1], sd[4 * j + 2], sd[4 * j + 3], j); val x = from; from = to; to = x }
    // Within 5 mm of the lowest vertex (and the margin): those it stands on, not its far side; points held higher
    // would pull the friction, which acts at the middle of the points, away from where the load is.
    var low = Float.MAX_VALUE
    for (i in 0 until h.vertexCount) low = min(low, v[3 * i] * nx + v[3 * i + 1] * ny + v[3 * i + 2] * nz)
    val band = min(margin, low - d + .005f)
    for (i in 0 until h.vertexCount) {
        val x = v[3 * i]; val y = v[3 * i + 1]; val z = v[3 * i + 2]
        if (x * nx + y * ny + z * nz - d > band) continue
        var onFace = false
        for (k in h.faceStart[g] until h.faceStart[g + 1]) if (h.loop[k] == i) { onFace = true; break }
        if (onFace) continue
        var inside = true
        for (j in 0 until m) if (sd[4 * j] * x + sd[4 * j + 1] * y + sd[4 * j + 2] * z > sd[4 * j + 3]) { inside = false; break }
        if (inside) from.add(x, y, z, 2048 or (i and 0x7FF))
    }
    return from
}

/** Room for [m] side planes in [sc]. */
private fun sidesFor(sc: HullScratch, m: Int): FloatArray { if (sc.sides.size < 4 * m) sc.sides = FloatArray(8 * m); return sc.sides }

/** Whether the point (x, y, z) projects inside face [f] of [h] (within its side planes). */
private fun insideFace(h: Polytope, f: Int, x: Float, y: Float, z: Float): Boolean {
    for (k in h.faceStart[f] until h.faceStart[f + 1]) {
        val o = 4 * k
        if (h.side[o] * x + h.side[o + 1] * y + h.side[o + 2] * z - h.side[o + 3] > 1e-6f) return false
    }
    return true
}

/**
 * Gregorius's test: do the Gauss map arcs a→b (an edge of the first shape: its two face normals) and c→d
 * (an edge of the second, its normals negated) cross? Then the edges' cross product is a face normal of
 * the Minkowski difference, a candidate separating axis. [bxa] = b × a, [dxc] = d × c.
 */
private fun minkowskiFace(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, bxaX: Float, bxaY: Float, bxaZ: Float,
                          cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, dxcX: Float, dxcY: Float, dxcZ: Float): Boolean {
    val cba = cx * bxaX + cy * bxaY + cz * bxaZ; val dba = dx * bxaX + dy * bxaY + dz * bxaZ
    if (cba * dba >= 0f) return false
    val adc = ax * dxcX + ay * dxcY + az * dxcZ; val bdc = bx * dxcX + by * dxcY + bz * dxcZ
    return adc * bdc < 0f && cba * bdc > 0f
}

// ------------------------------------------------------------------ polytope against polytope

/** Contacts between polytopes [a] (pose [pa]) and [b] (pose [pb]), normal from A to B. */
internal fun polytopes(a: Polytope, pa: Pose, b: Polytope, pb: Pose, margin: Float, out: ContactPoints) {
    val sc = out.hull
    sc.relate(pa, pb)
    sc.ensure(b.vertexCount, b.faceCount, b.edgeCount)
    val r00 = sc.r00; val r01 = sc.r01; val r02 = sc.r02; val r10 = sc.r10; val r11 = sc.r11; val r12 = sc.r12
    val r20 = sc.r20; val r21 = sc.r21; val r22 = sc.r22; val tx = sc.tx; val ty = sc.ty; val tz = sc.tz
    // B's vertices and planes in A's frame.
    val bv = sc.bv; val bn = sc.bn; val vb = b.v
    for (j in 0 until b.vertexCount) {
        val x = vb[3 * j]; val y = vb[3 * j + 1]; val z = vb[3 * j + 2]
        bv[3 * j] = r00 * x + r01 * y + r02 * z + tx; bv[3 * j + 1] = r10 * x + r11 * y + r12 * z + ty; bv[3 * j + 2] = r20 * x + r21 * y + r22 * z + tz
    }
    for (g in 0 until b.faceCount) {
        val o = 4 * g; val x = b.plane[o]; val y = b.plane[o + 1]; val z = b.plane[o + 2]
        val nx = r00 * x + r01 * y + r02 * z; val ny = r10 * x + r11 * y + r12 * z; val nz = r20 * x + r21 * y + r22 * z
        bn[o] = nx; bn[o + 1] = ny; bn[o + 2] = nz; bn[o + 3] = b.plane[o + 3] + (nx * tx + ny * ty + nz * tz)
    }
    // A's faces.
    val va = a.v; val pl = a.plane
    var sepA = -Float.MAX_VALUE; var fa = 0
    for (f in 0 until a.faceCount) {
        val o = 4 * f; val nx = pl[o]; val ny = pl[o + 1]; val nz = pl[o + 2]
        var m = Float.MAX_VALUE
        for (j in 0 until b.vertexCount) m = min(m, nx * bv[3 * j] + ny * bv[3 * j + 1] + nz * bv[3 * j + 2])
        val s = m - pl[o + 3]
        if (s > margin) return
        if (s > sepA) { sepA = s; fa = f }
    }
    // B's faces.
    var sepB = -Float.MAX_VALUE; var fb = 0
    for (g in 0 until b.faceCount) {
        val o = 4 * g; val nx = bn[o]; val ny = bn[o + 1]; val nz = bn[o + 2]
        var m = Float.MAX_VALUE
        for (i in 0 until a.vertexCount) m = min(m, nx * va[3 * i] + ny * va[3 * i + 1] + nz * va[3 * i + 2])
        val s = m - bn[o + 3]
        if (s > margin) return
        if (s > sepB) { sepB = s; fb = g }
    }
    // Edge pairs: B's edges (directions, and d × c of their negated face normals) in A's frame first.
    val be = sc.be; val bg = sc.bg
    for (e in 0 until b.edgeCount) {
        val p = b.edgeV[2 * e]; val q = b.edgeV[2 * e + 1]
        be[3 * e] = bv[3 * q] - bv[3 * p]; be[3 * e + 1] = bv[3 * q + 1] - bv[3 * p + 1]; be[3 * e + 2] = bv[3 * q + 2] - bv[3 * p + 2]
        val g1 = 4 * b.edgeF[2 * e]; val g2 = 4 * b.edgeF[2 * e + 1]
        // d × c with c = −n1, d = −n2: n2 × n1.
        bg[3 * e] = bn[g2 + 1] * bn[g1 + 2] - bn[g2 + 2] * bn[g1 + 1]
        bg[3 * e + 1] = bn[g2 + 2] * bn[g1] - bn[g2] * bn[g1 + 2]
        bg[3 * e + 2] = bn[g2] * bn[g1 + 1] - bn[g2 + 1] * bn[g1]
    }
    // Only pairs that could beat the faces matter: an edge pair's separation is at most n·(B's centre − A's edge)
    // and n·(B's edge − A's centre) for n in the edges' cones, so the edges facing away are skipped.
    val beat = REL_EDGE * max(sepA, sepB) + ABS_TOL
    val skip = min(beat, margin)           // below both, a pair neither separates the shapes nor wins
    val near = sc.near2; var nearCount = 0
    for (j in 0 until b.edgeCount) {
        val bp = b.edgeV[2 * j]
        if (coneReach(b, j, r00, r01, r02, r10, r11, r12, r20, r21, r22, true, bv[3 * bp], bv[3 * bp + 1], bv[3 * bp + 2]) > skip) near[nearCount++] = j
    }
    var sepE = -Float.MAX_VALUE; var ea = -1; var eb = -1; var ex = 0f; var ey = 0f; var ez = 0f
    for (i in 0 until a.edgeCount) {
        val p = a.edgeV[2 * i]; val q = a.edgeV[2 * i + 1]
        if (coneReach(a, i, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, false, tx - va[3 * p], ty - va[3 * p + 1], tz - va[3 * p + 2]) <= skip) continue
        val f1 = 4 * a.edgeF[2 * i]; val f2 = 4 * a.edgeF[2 * i + 1]
        val ax = pl[f1]; val ay = pl[f1 + 1]; val az = pl[f1 + 2]; val bx = pl[f2]; val by = pl[f2 + 1]; val bz = pl[f2 + 2]
        val bxaX = by * az - bz * ay; val bxaY = bz * ax - bx * az; val bxaZ = bx * ay - by * ax
        val px = va[3 * p]; val py = va[3 * p + 1]; val pz = va[3 * p + 2]
        val uax = va[3 * q] - px; val uay = va[3 * q + 1] - py; val uaz = va[3 * q + 2] - pz
        val la = uax * uax + uay * uay + uaz * uaz
        for (jj in 0 until nearCount) {
            val j = near[jj]
            val g1 = 4 * b.edgeF[2 * j]; val g2 = 4 * b.edgeF[2 * j + 1]
            if (!minkowskiFace(ax, ay, az, bx, by, bz, bxaX, bxaY, bxaZ, -bn[g1], -bn[g1 + 1], -bn[g1 + 2], -bn[g2], -bn[g2 + 1], -bn[g2 + 2],
                    bg[3 * j], bg[3 * j + 1], bg[3 * j + 2])) continue
            val ubx = be[3 * j]; val uby = be[3 * j + 1]; val ubz = be[3 * j + 2]
            var nx = uay * ubz - uaz * uby; var ny = uaz * ubx - uax * ubz; var nz = uax * uby - uay * ubx
            val l2 = nx * nx + ny * ny + nz * nz
            if (l2 < 1e-10f * la * (ubx * ubx + uby * uby + ubz * ubz)) continue        // parallel edges: a face axis covers them
            val l = sqrt(l2); nx /= l; ny /= l; nz /= l
            if (nx * px + ny * py + nz * pz < 0f) { nx = -nx; ny = -ny; nz = -nz }      // away from A's centre
            val bp = b.edgeV[2 * j]
            val s = nx * (bv[3 * bp] - px) + ny * (bv[3 * bp + 1] - py) + nz * (bv[3 * bp + 2] - pz)
            if (s > margin) return
            if (s > sepE) { sepE = s; ea = i; eb = j; ex = nx; ey = ny; ez = nz }
        }
    }
    val start = out.count
    if (ea >= 0 && sepE > beat) {
        // Edge against edge: one point, between the two edges' closest points.
        val p = a.edgeV[2 * ea]; val q = a.edgeV[2 * ea + 1]; val bp = b.edgeV[2 * eb]; val bq = b.edgeV[2 * eb + 1]
        val r = out.params
        segmentParams(va[3 * p], va[3 * p + 1], va[3 * p + 2], va[3 * q], va[3 * q + 1], va[3 * q + 2],
            bv[3 * bp], bv[3 * bp + 1], bv[3 * bp + 2], bv[3 * bq], bv[3 * bq + 1], bv[3 * bq + 2], r)
        val s = r[0]; val t = r[1]
        val mx = (va[3 * p] + (va[3 * q] - va[3 * p]) * s + bv[3 * bp] + (bv[3 * bq] - bv[3 * bp]) * t) * .5f
        val my = (va[3 * p + 1] + (va[3 * q + 1] - va[3 * p + 1]) * s + bv[3 * bp + 1] + (bv[3 * bq + 1] - bv[3 * bp + 1]) * t) * .5f
        val mz = (va[3 * p + 2] + (va[3 * q + 2] - va[3 * p + 2]) * s + bv[3 * bp + 2] + (bv[3 * bq + 2] - bv[3 * bp + 2]) * t) * .5f
        out.add(pa.worldX(mx, my, mz), pa.worldY(mx, my, mz), pa.worldZ(mx, my, mz),
            pa.xx * ex + pa.yx * ey + pa.zx * ez, pa.xy * ex + pa.yy * ey + pa.zy * ez, pa.xz * ex + pa.yz * ey + pa.zz * ez,
            sepE, (3 shl 20) or ((ea and 0x3FF) shl 10) or (eb and 0x3FF))
        return
    }
    val refIsA = !(sepB > REL_FACE * sepA + ABS_TOL)
    var nx: Float; var ny: Float; var nz: Float; val d: Float
    if (refIsA) {
        // A's face: the most opposed face of B, clipped by its sides.
        val o = 4 * fa; nx = pl[o]; ny = pl[o + 1]; nz = pl[o + 2]; d = pl[o + 3]
        val g = incidentFace(b, bv, bn, nx, ny, nz)
        val m = a.faceStart[fa + 1] - a.faceStart[fa]
        val sd = sidesFor(sc, m)
        a.side.copyInto(sd, 0, 4 * a.faceStart[fa], 4 * a.faceStart[fa + 1])
        val from = supportPoints(sc, b, bv, g, m, nx, ny, nz, d, margin)
        addFacePoints(from, nx, ny, nz, d, nx, ny, nz, (fa and 0xFF) shl 13, pa, margin, out)
        val wx = pa.xx * nx + pa.yx * ny + pa.zx * nz; val wy = pa.xy * nx + pa.yy * ny + pa.zy * nz; val wz = pa.xz * nx + pa.yz * ny + pa.zz * nz
        out.reduceStable(start, wx, wy, wz)
    } else {
        // B's face: the most opposed face of A, clipped by B's sides (turned into A's frame).
        val o = 4 * fb; nx = bn[o]; ny = bn[o + 1]; nz = bn[o + 2]; d = bn[o + 3]
        val f = incidentFace(a, va, pl, nx, ny, nz)
        val m = b.faceStart[fb + 1] - b.faceStart[fb]
        val sd = sidesFor(sc, m)
        for (k in b.faceStart[fb] until b.faceStart[fb + 1]) {
            val so = 4 * k; val x = b.side[so]; val y = b.side[so + 1]; val z = b.side[so + 2]
            val sx = r00 * x + r01 * y + r02 * z; val sy = r10 * x + r11 * y + r12 * z; val sz = r20 * x + r21 * y + r22 * z
            val j = 4 * (k - b.faceStart[fb])
            sd[j] = sx; sd[j + 1] = sy; sd[j + 2] = sz; sd[j + 3] = b.side[so + 3] + (sx * tx + sy * ty + sz * tz)
        }
        val from = supportPoints(sc, a, va, f, m, nx, ny, nz, d, margin)
        addFacePoints(from, nx, ny, nz, d, -nx, -ny, -nz, (1 shl 21) or ((fb and 0xFF) shl 13), pa, margin, out)
        val wx = -(pa.xx * nx + pa.yx * ny + pa.zx * nz); val wy = -(pa.xy * nx + pa.yy * ny + pa.zy * nz); val wz = -(pa.xz * nx + pa.yz * ny + pa.zz * nz)
        out.reduceStable(start, wx, wy, wz)
    }
}

/**
 * The clipped incident points below the reference plane (n, d) by at most [margin], halfway to it, into
 * [out] in the world ([frame] turns the shape frame into the world); the contact normal (from A to B) is
 * (cx, cy, cz) in the shape frame, the feature id [base] plus each point's key.
 */
private fun addFacePoints(p: ClipPoly, nx: Float, ny: Float, nz: Float, d: Float, cx: Float, cy: Float, cz: Float, base: Int,
                          frame: Pose, margin: Float, out: ContactPoints) {
    val wx = frame.xx * cx + frame.yx * cy + frame.zx * cz; val wy = frame.xy * cx + frame.yy * cy + frame.zy * cz; val wz = frame.xz * cx + frame.yz * cy + frame.zz * cz
    for (i in 0 until p.size) {
        val x = p.x[i]; val y = p.y[i]; val z = p.z[i]
        val s = (x * nx + y * ny + z * nz) - d
        if (s > margin) continue
        val h = s * .5f
        val qx = x - nx * h; val qy = y - ny * h; val qz = z - nz * h
        out.add(frame.worldX(qx, qy, qz), frame.worldY(qx, qy, qz), frame.worldZ(qx, qy, qz), wx, wy, wz, s, base or (p.key[i] and 0x1FFF))
    }
}

// ------------------------------------------------------------------ round shapes against a hull

/** Sphere against hull [h] at [ph]; normal from the sphere to the hull when [sphereIsA]. */
internal fun sphereHull(cx: Float, cy: Float, cz: Float, r: Float, h: Polytope, ph: Pose, margin: Float, out: ContactPoints, feature: Int, sphereIsA: Boolean) {
    val near = out.hull.near
    val lx = ph.localX(cx, cy, cz); val ly = ph.localY(cx, cy, cz); val lz = ph.localZ(cx, cy, cz)
    val dist = pointHull(h, lx, ly, lz, near)
    if (dist > r + margin) return
    val s = dist - r
    val nx = ph.xx * near[3] + ph.yx * near[4] + ph.zx * near[5]
    val ny = ph.xy * near[3] + ph.yy * near[4] + ph.zy * near[5]
    val nz = ph.xz * near[3] + ph.yz * near[4] + ph.zz * near[5]
    val sx = ph.worldX(near[0], near[1], near[2]); val sy = ph.worldY(near[0], near[1], near[2]); val sz = ph.worldZ(near[0], near[1], near[2])
    val k = s * .5f
    if (sphereIsA) out.add(sx + nx * k, sy + ny * k, sz + nz * k, -nx, -ny, -nz, s, feature)
    else out.add(sx + nx * k, sy + ny * k, sz + nz * k, nx, ny, nz, s, feature)
}

/**
 * Capsule against hull, after capsule–box: when the segment goes into the hull, the separating axis (the
 * hull's faces, the segment across its edges) chooses a face or an edge; else its closest features do.
 * On a face, the segment is clipped by the face's sides: two steady points when the capsule lies on it.
 */
internal fun capsuleHull(c: Capsule, pc: Pose, h: Polytope, ph: Pose, margin: Float, out: ContactPoints) {
    val hh = c.halfHeight; val rad = c.radius
    val wx0 = pc.px - pc.yx * hh; val wy0 = pc.py - pc.yy * hh; val wz0 = pc.pz - pc.yz * hh
    val wx1 = pc.px + pc.yx * hh; val wy1 = pc.py + pc.yy * hh; val wz1 = pc.pz + pc.yz * hh
    val ax = ph.localX(wx0, wy0, wz0); val ay = ph.localY(wx0, wy0, wz0); val az = ph.localZ(wx0, wy0, wz0)
    val bx = ph.localX(wx1, wy1, wz1); val by = ph.localY(wx1, wy1, wz1); val bz = ph.localZ(wx1, wy1, wz1)
    val pl = h.plane; val v = h.v
    // Does the segment go into the hull (Cyrus–Beck over the faces)?
    var t0 = 0f; var t1 = 1f; var crosses = true
    for (f in 0 until h.faceCount) {
        val o = 4 * f
        val da = pl[o] * ax + pl[o + 1] * ay + pl[o + 2] * az - pl[o + 3]; val db = pl[o] * bx + pl[o + 1] * by + pl[o + 2] * bz - pl[o + 3]
        if (da > 0f && db > 0f) { crosses = false; break }
        if (da > 0f) t0 = max(t0, da / (da - db)) else if (db > 0f) t1 = min(t1, da / (da - db))
        if (t0 > t1) { crosses = false; break }
    }
    val start = out.count
    var face = -1
    if (crosses) {
        var sepF = -Float.MAX_VALUE
        for (f in 0 until h.faceCount) {
            val o = 4 * f
            val s = min(pl[o] * ax + pl[o + 1] * ay + pl[o + 2] * az, pl[o] * bx + pl[o + 1] * by + pl[o + 2] * bz) - pl[o + 3]
            if (s > sepF) { sepF = s; face = f }
        }
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        var sepE = -Float.MAX_VALUE; var edge = -1; var enx = 0f; var eny = 0f; var enz = 0f
        for (e in 0 until h.edgeCount) {
            val f1 = 4 * h.edgeF[2 * e]; val f2 = 4 * h.edgeF[2 * e + 1]
            // The edge's arc must cross the great circle across the segment.
            if ((pl[f1] * dx + pl[f1 + 1] * dy + pl[f1 + 2] * dz) * (pl[f2] * dx + pl[f2 + 1] * dy + pl[f2 + 2] * dz) >= 0f) continue
            val p = h.edgeV[2 * e]; val q = h.edgeV[2 * e + 1]
            val ux = v[3 * q] - v[3 * p]; val uy = v[3 * q + 1] - v[3 * p + 1]; val uz = v[3 * q + 2] - v[3 * p + 2]
            var nx = dy * uz - dz * uy; var ny = dz * ux - dx * uz; var nz = dx * uy - dy * ux
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-6f) continue
            nx /= l; ny /= l; nz /= l
            if (nx * v[3 * p] + ny * v[3 * p + 1] + nz * v[3 * p + 2] < 0f) { nx = -nx; ny = -ny; nz = -nz }
            val s = nx * (ax - v[3 * p]) + ny * (ay - v[3 * p + 1]) + nz * (az - v[3 * p + 2])
            if (s > sepE) { sepE = s; edge = e; enx = nx; eny = ny; enz = nz }
        }
        if (edge >= 0 && sepE > REL_EDGE * sepF + ABS_TOL) {
            val p = h.edgeV[2 * edge]; val q = h.edgeV[2 * edge + 1]
            val r = out.params
            segmentParams(ax, ay, az, bx, by, bz, v[3 * p], v[3 * p + 1], v[3 * p + 2], v[3 * q], v[3 * q + 1], v[3 * q + 2], r)
            val qx = v[3 * p] + (v[3 * q] - v[3 * p]) * r[1]; val qy = v[3 * p + 1] + (v[3 * q + 1] - v[3 * p + 1]) * r[1]; val qz = v[3 * p + 2] + (v[3 * q + 2] - v[3 * p + 2]) * r[1]
            val s = sepE - rad
            if (s <= margin) {
                val k = s * .5f
                val mx = qx + enx * k; val my = qy + eny * k; val mz = qz + enz * k
                out.add(ph.worldX(mx, my, mz), ph.worldY(mx, my, mz), ph.worldZ(mx, my, mz),
                    -(ph.xx * enx + ph.yx * eny + ph.zx * enz), -(ph.xy * enx + ph.yy * eny + ph.zy * enz), -(ph.xz * enx + ph.yz * eny + ph.zz * enz),
                    s, (3 shl 20) or edge)
            }
            return
        }
    } else {
        // Apart: the closest points of segment and hull, from the two ends and the edges in front of it.
        val near = out.hull.near
        var best = Float.MAX_VALUE; var bt = 0f
        for (end in 0..1) {
            val x = if (end == 0) ax else bx; val y = if (end == 0) ay else by; val z = if (end == 0) az else bz
            val d = pointHull(h, x, y, z, near)
            if (d < best) { best = d; bt = end.toFloat() }
        }
        val r = out.params
        for (e in 0 until h.edgeCount) {
            val f1 = 4 * h.edgeF[2 * e]; val f2 = 4 * h.edgeF[2 * e + 1]
            if (pl[f1] * ax + pl[f1 + 1] * ay + pl[f1 + 2] * az <= pl[f1 + 3] && pl[f1] * bx + pl[f1 + 1] * by + pl[f1 + 2] * bz <= pl[f1 + 3] &&
                pl[f2] * ax + pl[f2 + 1] * ay + pl[f2 + 2] * az <= pl[f2 + 3] && pl[f2] * bx + pl[f2 + 1] * by + pl[f2 + 2] * bz <= pl[f2 + 3]) continue
            val p = h.edgeV[2 * e]; val q = h.edgeV[2 * e + 1]
            segmentParams(ax, ay, az, bx, by, bz, v[3 * p], v[3 * p + 1], v[3 * p + 2], v[3 * q], v[3 * q + 1], v[3 * q + 2], r)
            val sx = ax + (bx - ax) * r[0]; val sy = ay + (by - ay) * r[0]; val sz = az + (bz - az) * r[0]
            val qx = v[3 * p] + (v[3 * q] - v[3 * p]) * r[1]; val qy = v[3 * p + 1] + (v[3 * q + 1] - v[3 * p + 1]) * r[1]; val qz = v[3 * p + 2] + (v[3 * q + 2] - v[3 * p + 2]) * r[1]
            val d = sqrt((sx - qx) * (sx - qx) + (sy - qy) * (sy - qy) + (sz - qz) * (sz - qz))
            if (d < best) { best = d; bt = r[0] }
        }
        if (best > rad + margin) return
        // In a face's region (the closest point projects inside its polygon)? Then that face is the reference.
        val x = ax + (bx - ax) * bt; val y = ay + (by - ay) * bt; val z = az + (bz - az) * bt
        var sf = -Float.MAX_VALUE
        for (f in 0 until h.faceCount) {
            val o = 4 * f
            val s = pl[o] * x + pl[o + 1] * y + pl[o + 2] * z - pl[o + 3]
            if (s > sf) { sf = s; face = f }
        }
        val o = 4 * face
        if (sf <= 0f || !insideFace(h, face, x - pl[o] * sf, y - pl[o + 1] * sf, z - pl[o + 2] * sf)) {
            sphereHull(ph.worldX(x, y, z), ph.worldY(x, y, z), ph.worldZ(x, y, z), rad, h, ph, margin, out, 9, sphereIsA = true)
            return
        }
    }
    // Face [face]: the segment clipped by its sides, a point at each end.
    val o = 4 * face; val nx = pl[o]; val ny = pl[o + 1]; val nz = pl[o + 2]; val d = pl[o + 3]
    val r = out.range; r[0] = 0f; r[1] = 1f
    var inside = true
    for (k in h.faceStart[face] until h.faceStart[face + 1]) {
        val so = 4 * k
        if (!clipRange(ax, ay, az, bx, by, bz, h.side[so], h.side[so + 1], h.side[so + 2], h.side[so + 3], r)) { inside = false; break }
    }
    val wnx = ph.xx * nx + ph.yx * ny + ph.zx * nz; val wny = ph.xy * nx + ph.yy * ny + ph.zy * nz; val wnz = ph.xz * nx + ph.yz * ny + ph.zz * nz
    if (inside) for (i in 0..1) {
        if (i == 1 && r[1] - r[0] < 1e-4f) break
        val u = r[i]
        val x = ax + (bx - ax) * u; val y = ay + (by - ay) * u; val z = az + (bz - az) * u
        val s = (x * nx + y * ny + z * nz) - d - rad
        if (s <= margin) {
            val q = rad + s * .5f
            val mx = x - nx * q; val my = y - ny * q; val mz = z - nz * q
            out.add(ph.worldX(mx, my, mz), ph.worldY(mx, my, mz), ph.worldZ(mx, my, mz), -wnx, -wny, -wnz, s, ((face and 0xFF) shl 2) + i)
        }
    }
    if (out.count == start) {
        // The deepest end of the segment against that face.
        val da = ax * nx + ay * ny + az * nz; val db = bx * nx + by * ny + bz * nz
        val x = if (da <= db) ax else bx; val y = if (da <= db) ay else by; val z = if (da <= db) az else bz
        val s = min(da, db) - d - rad
        if (s <= margin) {
            val q = rad + s * .5f
            val mx = x - nx * q; val my = y - ny * q; val mz = z - nz * q
            out.add(ph.worldX(mx, my, mz), ph.worldY(mx, my, mz), ph.worldZ(mx, my, mz), -wnx, -wny, -wnz, s, ((face and 0xFF) shl 2) + 2)
        }
    }
}

// ------------------------------------------------------------------ triangle against hull

/**
 * A one-sided triangle (A) against a hull (B), after triangle–box: the separating axis over the triangle's
 * normal (front only), the hull's faces and the active triangle edges against the hull's edges (inner edges
 * give no axis: no ghost collisions on seams), then a clipped face or one edge point.
 */
internal fun triangleHull(t: Tri, h: Polytope, ph: Pose, margin: Float, out: ContactPoints) {
    val sc = out.hull; val pl = h.plane; val v = h.v
    // The triangle in the hull's frame.
    val ax = ph.localX(t.ax, t.ay, t.az); val ay = ph.localY(t.ax, t.ay, t.az); val az = ph.localZ(t.ax, t.ay, t.az)
    val bx = ph.localX(t.bx, t.by, t.bz); val by = ph.localY(t.bx, t.by, t.bz); val bz = ph.localZ(t.bx, t.by, t.bz)
    val cx = ph.localX(t.cx, t.cy, t.cz); val cy = ph.localY(t.cx, t.cy, t.cz); val cz = ph.localZ(t.cx, t.cy, t.cz)
    val nx = ph.xx * t.nx + ph.xy * t.ny + ph.xz * t.nz; val ny = ph.yx * t.nx + ph.yy * t.ny + ph.yz * t.nz; val nz = ph.zx * t.nx + ph.zy * t.ny + ph.zz * t.nz
    // The triangle's front.
    var m = Float.MAX_VALUE
    for (i in 0 until h.vertexCount) m = min(m, nx * v[3 * i] + ny * v[3 * i + 1] + nz * v[3 * i + 2])
    val sepT = m - (nx * ax + ny * ay + nz * az)
    if (sepT > margin) return
    // The hull's faces.
    var sepH = -Float.MAX_VALUE; var fh = 0
    for (f in 0 until h.faceCount) {
        val o = 4 * f
        val s = min(pl[o] * ax + pl[o + 1] * ay + pl[o + 2] * az, min(pl[o] * bx + pl[o + 1] * by + pl[o + 2] * bz, pl[o] * cx + pl[o + 1] * cy + pl[o + 2] * cz)) - pl[o + 3]
        if (s > margin) return
        if (s > sepH) { sepH = s; fh = f }
    }
    // Active triangle edges against the hull's edges whose arcs cross the edge's half circle (n → outward → −n, as two quarter arcs).
    var sepE = -Float.MAX_VALUE; var et = -1; var eh = -1; var enx = 0f; var eny = 0f; var enz = 0f
    for (j in 0..2) {
        if (!t.active(j)) continue
        val k = if (j == 2) 0 else j + 1
        val tx0 = if (j == 0) ax else if (j == 1) bx else cx; val ty0 = if (j == 0) ay else if (j == 1) by else cy; val tz0 = if (j == 0) az else if (j == 1) bz else cz
        val tx1 = if (k == 0) ax else if (k == 1) bx else cx; val ty1 = if (k == 0) ay else if (k == 1) by else cy; val tz1 = if (k == 0) az else if (k == 1) bz else cz
        val ux = tx1 - tx0; val uy = ty1 - ty0; val uz = tz1 - tz0
        var ox = uy * nz - uz * ny; var oy = uz * nx - ux * nz; var oz = ux * ny - uy * nx
        val ol = sqrt(ox * ox + oy * oy + oz * oz)
        if (ol < 1e-9f) continue
        ox /= ol; oy /= ol; oz /= ol
        // As for two hulls: the hull's edges that face away from the triangle cannot beat the faces.
        val skip = min(REL_EDGE * max(sepT, sepH) + ABS_TOL, margin)
        val gx = (ax + bx + cx) / 3f; val gy = (ay + by + cy) / 3f; val gz = (az + bz + cz) / 3f
        for (e in 0 until h.edgeCount) {
            val pe = h.edgeV[2 * e]
            if (coneReach(h, e, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, false, gx - v[3 * pe], gy - v[3 * pe + 1], gz - v[3 * pe + 2]) <= skip) continue
            val f1 = 4 * h.edgeF[2 * e]; val f2 = 4 * h.edgeF[2 * e + 1]
            val hax = pl[f1]; val hay = pl[f1 + 1]; val haz = pl[f1 + 2]; val hbx = pl[f2]; val hby = pl[f2 + 1]; val hbz = pl[f2 + 2]
            val bxaX = hby * haz - hbz * hay; val bxaY = hbz * hax - hbx * haz; val bxaZ = hbx * hay - hby * hax
            // Arcs of the triangle negated: −n → −o, then −o → n.
            var hit = false
            for (quarter in 0..1) {
                val c0x = if (quarter == 0) -nx else -ox; val c0y = if (quarter == 0) -ny else -oy; val c0z = if (quarter == 0) -nz else -oz
                val d0x = if (quarter == 0) -ox else nx; val d0y = if (quarter == 0) -oy else ny; val d0z = if (quarter == 0) -oz else nz
                val dxcX = d0y * c0z - d0z * c0y; val dxcY = d0z * c0x - d0x * c0z; val dxcZ = d0x * c0y - d0y * c0x
                if (minkowskiFace(hax, hay, haz, hbx, hby, hbz, bxaX, bxaY, bxaZ, c0x, c0y, c0z, d0x, d0y, d0z, dxcX, dxcY, dxcZ)) { hit = true; break }
            }
            if (!hit) continue
            val p = h.edgeV[2 * e]; val q = h.edgeV[2 * e + 1]
            val px = v[3 * p]; val py = v[3 * p + 1]; val pz = v[3 * p + 2]
            val wx = v[3 * q] - px; val wy = v[3 * q + 1] - py; val wz = v[3 * q + 2] - pz
            var lx = wy * uz - wz * uy; var ly = wz * ux - wx * uz; var lz = wx * uy - wy * ux
            val l = sqrt(lx * lx + ly * ly + lz * lz)
            if (l < 1e-4f * sqrt(ux * ux + uy * uy + uz * uz) * sqrt(wx * wx + wy * wy + wz * wz)) continue
            lx /= l; ly /= l; lz /= l
            if (lx * px + ly * py + lz * pz < 0f) { lx = -lx; ly = -ly; lz = -lz }        // from the hull towards the triangle
            val s = lx * (tx0 - px) + ly * (ty0 - py) + lz * (tz0 - pz)
            if (s > margin) return
            if (s > sepE) { sepE = s; et = j; eh = e; enx = lx; eny = ly; enz = lz }
        }
    }
    val start = out.count
    if (et >= 0 && sepE > REL_EDGE * max(sepT, sepH) + ABS_TOL) {
        val k = if (et == 2) 0 else et + 1
        val tx0 = if (et == 0) ax else if (et == 1) bx else cx; val ty0 = if (et == 0) ay else if (et == 1) by else cy; val tz0 = if (et == 0) az else if (et == 1) bz else cz
        val tx1 = if (k == 0) ax else if (k == 1) bx else cx; val ty1 = if (k == 0) ay else if (k == 1) by else cy; val tz1 = if (k == 0) az else if (k == 1) bz else cz
        val p = h.edgeV[2 * eh]; val q = h.edgeV[2 * eh + 1]
        val r = out.params
        segmentParams(tx0, ty0, tz0, tx1, ty1, tz1, v[3 * p], v[3 * p + 1], v[3 * p + 2], v[3 * q], v[3 * q + 1], v[3 * q + 2], r)
        val mx = (tx0 + (tx1 - tx0) * r[0] + v[3 * p] + (v[3 * q] - v[3 * p]) * r[1]) * .5f
        val my = (ty0 + (ty1 - ty0) * r[0] + v[3 * p + 1] + (v[3 * q + 1] - v[3 * p + 1]) * r[1]) * .5f
        val mz = (tz0 + (tz1 - tz0) * r[0] + v[3 * p + 2] + (v[3 * q + 2] - v[3 * p + 2]) * r[1]) * .5f
        out.add(ph.worldX(mx, my, mz), ph.worldY(mx, my, mz), ph.worldZ(mx, my, mz),
            -(ph.xx * enx + ph.yx * eny + ph.zx * enz), -(ph.xy * enx + ph.yy * eny + ph.zy * enz), -(ph.xz * enx + ph.yz * eny + ph.zz * enz),
            sepE, (3 shl 20) or (et shl 18) or (eh and 0x3FFFF))
        return
    }
    val poly = sc.p; val spare = sc.q
    if (!(sepH > REL_FACE * sepT + ABS_TOL)) {
        // The triangle's face: the hull's most opposed face, clipped by the triangle's sides.
        val g = incidentFace(h, v, pl, nx, ny, nz)
        val sd = sidesFor(sc, 3)
        for (j in 0..2) {
            val k = if (j == 2) 0 else j + 1
            val x0 = if (j == 0) ax else if (j == 1) bx else cx; val y0 = if (j == 0) ay else if (j == 1) by else cy; val z0 = if (j == 0) az else if (j == 1) bz else cz
            val ux = (if (k == 0) ax else if (k == 1) bx else cx) - x0; val uy = (if (k == 0) ay else if (k == 1) by else cy) - y0; val uz = (if (k == 0) az else if (k == 1) bz else cz) - z0
            var sx = uy * nz - uz * ny; var sy = uz * nx - ux * nz; var sz = ux * ny - uy * nx
            val l = sqrt(sx * sx + sy * sy + sz * sz)
            if (l != 0f) { sx /= l; sy /= l; sz /= l }
            sd[4 * j] = sx; sd[4 * j + 1] = sy; sd[4 * j + 2] = sz; sd[4 * j + 3] = sx * x0 + sy * y0 + sz * z0
        }
        val dd = nx * ax + ny * ay + nz * az
        val from = supportPoints(sc, h, v, g, 3, nx, ny, nz, dd, margin)
        addFacePoints(from, nx, ny, nz, dd, nx, ny, nz, 0, ph, margin, out)
        out.reduceStable(start, t.nx, t.ny, t.nz)
    } else {
        // The hull's face: the triangle clipped by its sides.
        val o = 4 * fh; val fx = pl[o]; val fy = pl[o + 1]; val fz = pl[o + 2]
        poly.size = 0; poly.add(ax, ay, az, 0); poly.add(bx, by, bz, 1); poly.add(cx, cy, cz, 2)
        var from = poly; var to = spare
        for (k in h.faceStart[fh] until h.faceStart[fh + 1]) {
            val so = 4 * k
            clipKeyed(from, to, h.side[so], h.side[so + 1], h.side[so + 2], h.side[so + 3], k - h.faceStart[fh])
            val sw = from; from = to; to = sw
        }
        addFacePoints(from, fx, fy, fz, pl[o + 3], -fx, -fy, -fz, (1 shl 21) or ((fh and 0xFF) shl 13), ph, margin, out)
        val wx = -(ph.xx * fx + ph.yx * fy + ph.zx * fz); val wy = -(ph.xy * fx + ph.yy * fy + ph.zy * fz); val wz = -(ph.xz * fx + ph.yz * fy + ph.zz * fz)
        out.reduceStable(start, wx, wy, wz)
    }
}
