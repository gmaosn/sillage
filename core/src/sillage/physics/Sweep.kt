package sillage.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Continuous collision on the whole shape: a body's shape swept in a straight line (at its final
// orientation) against triangles and bodies, its time of impact found by conservative advancement on the
// distance that GJK gives. Shapes are seen as a convex core (a point, a segment, a box, a hull, a
// triangle) rounded by a radius; the distance between two rounded cores is the distance between their
// cores, less the radii. Along a straight line that distance is a convex function of the way travelled,
// so each Newton step (the distance over the speed at which it closes) stays before the impact and the
// impact is found in a few steps; a closing speed of zero or less means it never comes. Doubles inside:
// the same answer on every platform.

internal const val CORE_POINT = 0
internal const val CORE_SEGMENT = 1
internal const val CORE_BOX = 2
internal const val CORE_HULL = 3
internal const val CORE_TRIANGLE = 4

/** A convex core for GJK, placed in the world: its support point in any direction. */
internal class SweepCore {
    var kind = CORE_POINT
    var radius = 0.0
    var cx = 0.0; var cy = 0.0; var cz = 0.0
    // Axes (columns): local X, Y, Z in the world.
    var xx = 1.0; var xy = 0.0; var xz = 0.0
    var yx = 0.0; var yy = 1.0; var yz = 0.0
    var zx = 0.0; var zy = 0.0; var zz = 1.0
    var hx = 0.0; var hy = 0.0; var hz = 0.0          // box half extents; a segment's half length (hy)
    var hull: Polytope? = null; var scale = 1.0       // a hull's vertices, scaled about its centre
    val tri = DoubleArray(9)
    // Support point, written by support().
    var sx = 0.0; var sy = 0.0; var sz = 0.0

    fun axes(p: Pose) {
        xx = p.xx.toDouble(); xy = p.xy.toDouble(); xz = p.xz.toDouble()
        yx = p.yx.toDouble(); yy = p.yy.toDouble(); yz = p.yz.toDouble()
        zx = p.zx.toDouble(); zy = p.zy.toDouble(); zz = p.zz.toDouble()
    }

    /**
     * The core of [shape] (not a compound) at [pose]: spheres and capsules as they are (a point, a segment and
     * their radius); boxes and hulls shrunk by [round] and rounded by it, which stays within the shape.
     */
    fun of(shape: Shape, pose: Pose, round: Float) {
        cx = pose.px.toDouble(); cy = pose.py.toDouble(); cz = pose.pz.toDouble(); axes(pose)
        when (shape) {
            is Sphere -> { kind = CORE_POINT; radius = shape.radius.toDouble() }
            is Capsule -> { kind = CORE_SEGMENT; radius = shape.radius.toDouble(); hy = shape.halfHeight.toDouble() }
            is Box -> {
                kind = CORE_BOX; radius = round.toDouble()
                hx = (shape.halfX - round).toDouble(); hy = (shape.halfY - round).toDouble(); hz = (shape.halfZ - round).toDouble()
            }
            is ConvexHull -> {
                kind = CORE_HULL; radius = round.toDouble(); hull = shape.poly
                scale = if (round > 0f) 1.0 - round.toDouble() / shape.inradius else 1.0
            }
            is Compound -> error("compound")
        }
    }

    fun triangle(t: Tri) {
        kind = CORE_TRIANGLE; radius = 0.0
        tri[0] = t.ax.toDouble(); tri[1] = t.ay.toDouble(); tri[2] = t.az.toDouble()
        tri[3] = t.bx.toDouble(); tri[4] = t.by.toDouble(); tri[5] = t.bz.toDouble()
        tri[6] = t.cx.toDouble(); tri[7] = t.cy.toDouble(); tri[8] = t.cz.toDouble()
        cx = (tri[0] + tri[3] + tri[6]) / 3.0; cy = (tri[1] + tri[4] + tri[7]) / 3.0; cz = (tri[2] + tri[5] + tri[8]) / 3.0
    }

    /** Moves the core by (dx, dy, dz). */
    fun shift(dx: Double, dy: Double, dz: Double) {
        cx += dx; cy += dy; cz += dz
        if (kind == CORE_TRIANGLE) for (k in 0..2) { tri[3 * k] += dx; tri[3 * k + 1] += dy; tri[3 * k + 2] += dz }
    }

    /** The point of the core farthest along (dx, dy, dz), into (sx, sy, sz). */
    fun support(dx: Double, dy: Double, dz: Double) {
        when (kind) {
            CORE_POINT -> { sx = cx; sy = cy; sz = cz }
            CORE_SEGMENT -> {
                val s = if (dx * yx + dy * yy + dz * yz >= 0.0) hy else -hy
                sx = cx + yx * s; sy = cy + yy * s; sz = cz + yz * s
            }
            CORE_BOX -> {
                val a = if (dx * xx + dy * xy + dz * xz >= 0.0) hx else -hx
                val b = if (dx * yx + dy * yy + dz * yz >= 0.0) hy else -hy
                val c = if (dx * zx + dy * zy + dz * zz >= 0.0) hz else -hz
                sx = cx + xx * a + yx * b + zx * c; sy = cy + xy * a + yy * b + zy * c; sz = cz + xz * a + yz * b + zz * c
            }
            CORE_HULL -> {
                val h = hull!!; val v = h.v
                val lx = dx * xx + dy * xy + dz * xz; val ly = dx * yx + dy * yy + dz * yz; val lz = dx * zx + dy * zy + dz * zz
                var best = 0; var bestDot = -Double.MAX_VALUE
                for (k in 0 until h.vertexCount) {
                    val d = v[3 * k] * lx + v[3 * k + 1] * ly + v[3 * k + 2] * lz
                    if (d > bestDot) { bestDot = d; best = k }
                }
                val px = v[3 * best] * scale; val py = v[3 * best + 1] * scale; val pz = v[3 * best + 2] * scale
                sx = cx + xx * px + yx * py + zx * pz; sy = cy + xy * px + yy * py + zy * pz; sz = cz + xz * px + yz * py + zz * pz
            }
            else -> {
                var best = 0; var bestDot = -Double.MAX_VALUE
                for (k in 0..2) {
                    val d = tri[3 * k] * dx + tri[3 * k + 1] * dy + tri[3 * k + 2] * dz
                    if (d > bestDot) { bestDot = d; best = k }
                }
                sx = tri[3 * best]; sy = tri[3 * best + 1]; sz = tri[3 * best + 2]
            }
        }
    }
}

/**
 * GJK distance between two convex cores (G. van den Bergen's version, Ericson's closest points on the
 * simplex): [distance] gives the distance between the cores (0 when they overlap) and [nx], [ny], [nz] the
 * unit direction from a's closest point to b's.
 */
internal class Gjk {
    // Simplex points: w = a − b, and the a and b points it came from.
    private val w = DoubleArray(12); private val pa = DoubleArray(12); private val pb = DoubleArray(12)
    private var n = 0
    private val lambda = DoubleArray(4)
    private val keep = IntArray(4)
    var nx = 0.0; var ny = 0.0; var nz = 0.0
    // Closest point of the simplex (v) and its barycentric weights, from closest().
    private var vx = 0.0; private var vy = 0.0; private var vz = 0.0

    fun distance(a: SweepCore, b: SweepCore): Double {
        n = 0
        // First direction: from b's centre to a's.
        var dx = a.cx - b.cx; var dy = a.cy - b.cy; var dz = a.cz - b.cz
        if (dx * dx + dy * dy + dz * dz < 1e-24) { dx = 1.0; dy = 0.0; dz = 0.0 }
        addSupport(a, b, -dx, -dy, -dz)
        vx = w[0]; vy = w[1]; vz = w[2]
        lambda[0] = 1.0
        var vv = vx * vx + vy * vy + vz * vz
        var last = Double.MAX_VALUE
        for (iteration in 0 until 48) {
            if (vv < 1e-20) return overlap()
            addSupport(a, b, -vx, -vy, -vz)
            val k = 3 * (n - 1)
            val vw = vx * w[k] + vy * w[k + 1] + vz * w[k + 2]
            // No progress: v is the closest point (to a relative precision).
            if (vv - vw <= 1e-12 * vv || duplicate()) { n--; break }
            closest()
            if (n == 4) return overlap()
            vv = vx * vx + vy * vy + vz * vz
            if (vv >= last) break
            last = vv
        }
        val d = sqrt(vv)
        if (d < 1e-10) return overlap()
        // v = a point − b point: the direction from a to b is −v.
        nx = -vx / d; ny = -vy / d; nz = -vz / d
        return d
    }

    private fun overlap(): Double { nx = 0.0; ny = 0.0; nz = 0.0; return 0.0 }

    private fun addSupport(a: SweepCore, b: SweepCore, dx: Double, dy: Double, dz: Double) {
        a.support(dx, dy, dz); b.support(-dx, -dy, -dz)
        val k = 3 * n
        pa[k] = a.sx; pa[k + 1] = a.sy; pa[k + 2] = a.sz
        pb[k] = b.sx; pb[k + 1] = b.sy; pb[k + 2] = b.sz
        w[k] = a.sx - b.sx; w[k + 1] = a.sy - b.sy; w[k + 2] = a.sz - b.sz
        n++
    }

    /** Whether the newest point repeats one of the others (a cycle in floating point). */
    private fun duplicate(): Boolean {
        val k = 3 * (n - 1)
        for (j in 0 until n - 1) {
            val o = 3 * j
            if (abs(w[o] - w[k]) + abs(w[o + 1] - w[k + 1]) + abs(w[o + 2] - w[k + 2]) < 1e-12) return true
        }
        return false
    }

    /** The point of the simplex closest to the origin into v, the simplex cut down to the points that give it. */
    private fun closest() {
        when (n) {
            2 -> segment(0, 1)
            3 -> triangle(0, 1, 2)
            4 -> tetrahedron()
        }
    }

    private val tw = DoubleArray(12); private val ta = DoubleArray(12); private val tb = DoubleArray(12)

    /** Keeps the simplex points listed in [keep] (with their weights in [lambda]), in that order. */
    private fun reduce(count: Int) {
        for (j in 0 until count) {
            val s = 3 * keep[j]; val d = 3 * j
            for (c in 0..2) { tw[d + c] = w[s + c]; ta[d + c] = pa[s + c]; tb[d + c] = pb[s + c] }
        }
        tw.copyInto(w, 0, 0, 3 * count); ta.copyInto(pa, 0, 0, 3 * count); tb.copyInto(pb, 0, 0, 3 * count)
        n = count
    }

    private fun segment(i: Int, j: Int) {
        val a = 3 * i; val b = 3 * j
        val ex = w[b] - w[a]; val ey = w[b + 1] - w[a + 1]; val ez = w[b + 2] - w[a + 2]
        val ee = ex * ex + ey * ey + ez * ez
        val t = if (ee > 0.0) -(w[a] * ex + w[a + 1] * ey + w[a + 2] * ez) / ee else 0.0
        when {
            t <= 0.0 -> { vx = w[a]; vy = w[a + 1]; vz = w[a + 2]; keep[0] = i; lambda[0] = 1.0; reduce(1) }
            t >= 1.0 -> { vx = w[b]; vy = w[b + 1]; vz = w[b + 2]; keep[0] = j; lambda[0] = 1.0; reduce(1) }
            else -> {
                vx = w[a] + ex * t; vy = w[a + 1] + ey * t; vz = w[a + 2] + ez * t
                keep[0] = i; keep[1] = j; lambda[0] = 1.0 - t; lambda[1] = t; reduce(2)
            }
        }
    }

    /** Closest point of triangle (i, j, k) to the origin (Ericson 5.1.5); returns its squared distance, writes v and the kept points. */
    private fun triangle(i: Int, j: Int, k: Int): Double {
        val a = 3 * i; val b = 3 * j; val c = 3 * k
        val abx = w[b] - w[a]; val aby = w[b + 1] - w[a + 1]; val abz = w[b + 2] - w[a + 2]
        val acx = w[c] - w[a]; val acy = w[c + 1] - w[a + 1]; val acz = w[c + 2] - w[a + 2]
        val d1 = -(abx * w[a] + aby * w[a + 1] + abz * w[a + 2]); val d2 = -(acx * w[a] + acy * w[a + 1] + acz * w[a + 2])
        if (d1 <= 0.0 && d2 <= 0.0) return point(i)
        val d3 = -(abx * w[b] + aby * w[b + 1] + abz * w[b + 2]); val d4 = -(acx * w[b] + acy * w[b + 1] + acz * w[b + 2])
        if (d3 >= 0.0 && d4 <= d3) return point(j)
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) return edge(i, j, d1 / (d1 - d3))
        val d5 = -(abx * w[c] + aby * w[c + 1] + abz * w[c + 2]); val d6 = -(acx * w[c] + acy * w[c + 1] + acz * w[c + 2])
        if (d6 >= 0.0 && d5 <= d6) return point(k)
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) return edge(i, k, d2 / (d2 - d6))
        val va = d3 * d6 - d5 * d4
        if (va <= 0.0 && d4 - d3 >= 0.0 && d5 - d6 >= 0.0) return edge(j, k, (d4 - d3) / ((d4 - d3) + (d5 - d6)))
        val denom = va + vb + vc
        if (denom <= 0.0) return point(i)
        val v = vb / denom; val u = vc / denom
        vx = w[a] + abx * v + acx * u; vy = w[a + 1] + aby * v + acy * u; vz = w[a + 2] + abz * v + acz * u
        keep[0] = i; keep[1] = j; keep[2] = k; lambda[0] = 1.0 - v - u; lambda[1] = v; lambda[2] = u
        reduce(3)
        return vx * vx + vy * vy + vz * vz
    }

    private fun point(i: Int): Double {
        val a = 3 * i
        vx = w[a]; vy = w[a + 1]; vz = w[a + 2]; keep[0] = i; lambda[0] = 1.0; reduce(1)
        return vx * vx + vy * vy + vz * vz
    }

    private fun edge(i: Int, j: Int, t: Double): Double {
        val a = 3 * i; val b = 3 * j
        vx = w[a] + (w[b] - w[a]) * t; vy = w[a + 1] + (w[b + 1] - w[a + 1]) * t; vz = w[a + 2] + (w[b + 2] - w[a + 2]) * t
        keep[0] = i; keep[1] = j; lambda[0] = 1.0 - t; lambda[1] = t; reduce(2)
        return vx * vx + vy * vy + vz * vz
    }

    // The tetrahedron's faces are tried on copies: a face that does not win must leave the simplex as it was.
    private val saveW = DoubleArray(12); private val saveA = DoubleArray(12); private val saveB = DoubleArray(12)

    private fun tetrahedron() {
        w.copyInto(saveW); pa.copyInto(saveA); pb.copyInto(saveB)
        var best = Double.MAX_VALUE; var bestFace = -1
        var inside = true
        for (f in 0 until 4) {
            // Face f leaves out point f; the origin outside it (on the side away from point f) can be closest to it.
            val i = (f + 1) % 4; val j = (f + 2) % 4; val k = (f + 3) % 4
            if (!outside(i, j, k, f)) continue
            inside = false
            saveW.copyInto(w); saveA.copyInto(pa); saveB.copyInto(pb); n = 4
            val d = triangle(i, j, k)
            if (d < best) { best = d; bestFace = f }
        }
        saveW.copyInto(w); saveA.copyInto(pa); saveB.copyInto(pb); n = 4
        if (inside) return                                   // n stays 4: the origin is inside, the cores overlap
        val f = bestFace
        triangle((f + 1) % 4, (f + 2) % 4, (f + 3) % 4)
    }

    /** Whether the origin and point [d] lie on opposite sides of the plane (i, j, k) (or the tetrahedron is flat). */
    private fun outside(i: Int, j: Int, k: Int, d: Int): Boolean {
        val a = 3 * i; val b = 3 * j; val c = 3 * k; val e = 3 * d
        val abx = saveW[b] - saveW[a]; val aby = saveW[b + 1] - saveW[a + 1]; val abz = saveW[b + 2] - saveW[a + 2]
        val acx = saveW[c] - saveW[a]; val acy = saveW[c + 1] - saveW[a + 1]; val acz = saveW[c + 2] - saveW[a + 2]
        val nx = aby * acz - abz * acy; val ny = abz * acx - abx * acz; val nz = abx * acy - aby * acx
        val sp = -(saveW[a] * nx + saveW[a + 1] * ny + saveW[a + 2] * nz)
        val sd = (saveW[e] - saveW[a]) * nx + (saveW[e + 1] - saveW[a + 1]) * ny + (saveW[e + 2] - saveW[a + 2]) * nz
        // Flat (the fourth point within a hair of the face's plane): every face is a candidate.
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        val size = sqrt(abx * abx + aby * aby + abz * abz + acx * acx + acy * acy + acz * acz)
        if (abs(sd) <= 1e-9 * nl * size) return true
        return sp * sd < 0.0
    }
}

/**
 * The time of impact of a core moving along a straight line against another, by conservative advancement:
 * [toi] gives how far along the line (0 to its length) the two rounded cores first touch, when the motion
 * then goes on deeper than a tolerance into the other; NaN when it does not (it misses, grazes, stops
 * touching or starts inside). The distance between the cores is a convex function of the way travelled, so
 * each step — the distance left over the speed at which it closes — lands before the impact.
 */
internal class ShapeCast {
    val moving = SweepCore()
    val target = SweepCore()
    private val gjk = Gjk()
    /** At the impact: the unit normal from the moving core to the target. */
    var nx = 0.0; var ny = 0.0; var nz = 0.0
    /** Steps of conservative advancement since made (diagnostics). */
    var iterations = 0L; private set

    /**
     * [moving] goes from where it is along (ux, uy, uz) (unit) over [length]; touching means a gap of 0 between
     * the rounded cores, too deep a gap of −[depth]. Returns where it touches, or NaN.
     */
    fun toi(ux: Double, uy: Double, uz: Double, length: Double, depth: Double): Double {
        val r = moving.radius + target.radius
        val bx = moving.cx; val by = moving.cy; val bz = moving.cz
        var s = 0.0
        var d = gjk.distance(moving, target)
        // Starting deep inside (or with overlapping cores): not this sweep's business.
        if (d <= 0.0 || d - r < -depth) { restore(bx, by, bz); return Double.NaN }
        // To the touch.
        var touched = d - r <= TOLERANCE
        var it = 0
        while (!touched && it < 32) {
            it++
            val closing = gjk.nx * ux + gjk.ny * uy + gjk.nz * uz
            if (closing <= 1e-9) { restore(bx, by, bz); return Double.NaN }
            s += (d - r) / closing
            if (s > length) { restore(bx, by, bz); return Double.NaN }
            place(bx, by, bz, ux, uy, uz, s)
            d = gjk.distance(moving, target)
            if (d <= 0.0) break
            touched = d - r <= TOLERANCE
        }
        if (!touched && d > 0.0) { restore(bx, by, bz); return Double.NaN }
        val at = s
        nx = gjk.nx; ny = gjk.ny; nz = gjk.nz
        // Then on: does the motion go deeper than the tolerance within its length?
        val deep = r - depth
        var t = s
        while (d > 0.0 && d - deep > TOLERANCE && it < 64) {
            it++
            val closing = gjk.nx * ux + gjk.ny * uy + gjk.nz * uz
            if (closing <= 1e-9) { restore(bx, by, bz); return Double.NaN }
            t += (d - deep) / closing
            if (t > length) { restore(bx, by, bz); return Double.NaN }
            place(bx, by, bz, ux, uy, uz, t)
            d = gjk.distance(moving, target)
        }
        iterations += it
        restore(bx, by, bz)
        return at
    }

    private fun place(bx: Double, by: Double, bz: Double, ux: Double, uy: Double, uz: Double, s: Double) {
        moving.shift(bx + ux * s - moving.cx, by + uy * s - moving.cy, bz + uz * s - moving.cz)
    }
    private fun restore(bx: Double, by: Double, bz: Double) = moving.shift(bx - moving.cx, by - moving.cy, bz - moving.cz)

    private companion object { const val TOLERANCE = 1e-4 }
}

/** The scratch of continuous collision, kept by the world (one sweep at a time, on the stepping thread). */
internal class ContinuousScratch {
    val cast = ShapeCast()
    val pose = Pose(); val part = Pose(); val targetPose = Pose(); val targetPart = Pose()
    val tri = Tri()
    val triangles = IntList(); val bodies = IntList(); val stack = IntList()
    val bounds = FloatArray(6)
    // The earliest impact found: the fraction of the relative motion, and that motion (x, y, z).
    var fraction = 0.0; var rx = 0.0; var ry = 0.0; var rz = 0.0; var length = 0.0; var at = 0.0
}

/** How far [s] (not a compound) at [p] reaches from its centre against the unit direction (nx, ny, nz): its support along −n. */
private fun reachAgainst(s: Shape, p: Pose, nx: Float, ny: Float, nz: Float): Float = when (s) {
    is Sphere -> s.radius
    is Capsule -> abs(p.yx * nx + p.yy * ny + p.yz * nz) * s.halfHeight + s.radius
    is Box -> abs(p.xx * nx + p.xy * ny + p.xz * nz) * s.halfX + abs(p.yx * nx + p.yy * ny + p.yz * nz) * s.halfY +
        abs(p.zx * nx + p.zy * ny + p.zz * nz) * s.halfZ
    is ConvexHull -> {
        val lx = -(p.xx * nx + p.xy * ny + p.xz * nz); val ly = -(p.yx * nx + p.yy * ny + p.yz * nz); val lz = -(p.zx * nx + p.zy * ny + p.zz * nz)
        val h = s.poly; var best = -Float.MAX_VALUE
        for (k in 0 until h.vertexCount) best = max(best, h.v[3 * k] * lx + h.v[3 * k + 1] * ly + h.v[3 * k + 2] * lz)
        best
    }
    is Compound -> error("compound")
}

/** Rounding of a moving box's or hull's core, for a shape whose core radius is [inner]. */
private fun roundOf(inner: Float) = min(.5f * inner, .02f)

/**
 * The whole shape of fast body [i] swept from where it was to where it is (its motion this step, (dx, dy, dz)),
 * at its present orientation, against the meshes' triangles (from their front) and against the static and
 * kinematic bodies (every body for a [bullet]), found through [trees], or in [still] and the world without
 * them. The motion relative to each body counts (a kinematic body moved too). The earliest impact that goes
 * on deeper than a small depth into what it meets is kept in [sc]: returns whether there is one.
 */
internal fun PhysicsWorld.sweepShape(i: Int, dx: Float, dy: Float, dz: Float, trees: QueryTrees?, still: IntList, bullet: Boolean,
                                     sc: ContinuousScratch): Boolean {
    val shape = shapes[i]!!
    val pose = sc.pose.set(px[i], py[i], pz[i], qx[i], qy[i], qz[i], qw[i])
    sc.fraction = Double.MAX_VALUE
    // The bounds swept: where the shape is, and where it was.
    val b = sc.bounds
    localBounds(shape, pose, b, 0)
    val x0 = min(b[0], b[0] - dx); val y0 = min(b[1], b[1] - dy); val z0 = min(b[2], b[2] - dz)
    val x1 = max(b[3], b[3] - dx); val y1 = max(b[4], b[4] - dy); val z1 = max(b[5], b[5] - dz)
    val travel = sqrt(dx * dx + dy * dy + dz * dz)
    val ux = dx / travel; val uy = dy / travel; val uz = dz / travel
    // Triangles: still, so the body's own motion; one-sided, met only from the front.
    for (m in meshes.indices) {
        val mesh = meshes[m]
        val found = sc.triangles; found.clear()
        mesh.query(x0, y0, z0, x1, y1, z1, found, sc.stack)
        for (f in 0 until found.size) {
            val t = sc.tri.load(mesh, found[f])
            if (ux * t.nx + uy * t.ny + uz * t.nz >= 0f) continue                   // not going against its front
            val endAbove = (px[i] - t.ax) * t.nx + (py[i] - t.ay) * t.ny + (pz[i] - t.az) * t.nz
            val startAbove = endAbove - (dx * t.nx + dy * t.ny + dz * t.nz)
            if (startAbove < 0f) continue                                           // came from behind it
            sweepParts(i, shape, pose, dx, dy, dz, -1, null, null, t, startAbove, endAbove, sc)
        }
    }
    // Bodies.
    val cand = sc.bodies
    val n: Int
    if (trees != null) { trees.bodiesIn(x0, y0, z0, x1, y1, z1, !bullet, cand, sc.stack); n = cand.size }
    else n = if (bullet) count else still.size
    for (k in 0 until n) {
        val j = if (trees != null) cand[k] else if (bullet) k else still[k]
        if (j == i || removed[j] || joinedApart(i, j)) continue
        // The motion relative to j (which moved too, unless it is static).
        val oj = j * S
        val mx = dx - st[oj + DPX]; val my = dy - st[oj + DPX + 1]; val mz = dz - st[oj + DPX + 2]
        if (mx * mx + my * my + mz * mz < 1e-12f) continue
        // Quick rejection: the bounding spheres along the relative motion.
        val reach = shape.boundingRadius + shapes[j]!!.boundingRadius
        val ex = px[j] - px[i]; val ey = py[j] - py[i]; val ez = pz[j] - pz[i]          // j from i's end
        val ml2 = mx * mx + my * my + mz * mz
        val along = (-(ex * mx + ey * my + ez * mz) / ml2).coerceIn(0f, 1f)             // i went from −m to 0
        val cx = ex + mx * along; val cy = ey + my * along; val cz = ez + mz * along
        if (cx * cx + cy * cy + cz * cz > reach * reach) continue
        val tp = sc.targetPose.set(px[j], py[j], pz[j], qx[j], qy[j], qz[j], qw[j])
        sweepParts(i, shape, pose, mx, my, mz, j, shapes[j], tp, null, 0f, 0f, sc)
    }
    return sc.fraction != Double.MAX_VALUE
}

/** The moving body's parts against one target (a triangle, or a body's parts), keeping the earliest impact. */
private fun sweepParts(i: Int, shape: Shape, pose: Pose, mx: Float, my: Float, mz: Float, j: Int, target: Shape?, targetPose: Pose?,
                       tri: Tri?, startAbove: Float, endAbove: Float, sc: ContinuousScratch) {
    val parts = if (shape is Compound) shape.count else 1
    for (p in 0 until parts) {
        val s = if (shape is Compound) shape.shapes[p] else shape
        val q = if (shape is Compound) sc.part.setPart(pose, shape, p) else pose
        val inner = when (s) { is Sphere -> s.radius; is Capsule -> s.radius; is Box -> min(s.halfX, min(s.halfY, s.halfZ)); is ConvexHull -> s.inradius; is Compound -> 0f }
        // An impact counts when the motion goes on until the core itself meets what it hits — the centre or the
        // segment of a ball or a capsule, the shrunk core of a box or a hull —, beyond which the contacts could push
        // it out on the wrong side: a body landing with its soft contacts a little in is left alone.
        val round = roundOf(inner); val depth = inner
        if (tri != null) {
            // Through the triangle's plane by more than the depth, at one end of the motion at least: else it cannot be.
            val r = reachAgainst(s, q, tri.nx, tri.ny, tri.nz)
            val partEnd = endAbove + ((q.px - pose.px) * tri.nx + (q.py - pose.py) * tri.ny + (q.pz - pose.pz) * tri.nz)
            val partStart = partEnd + (startAbove - endAbove)
            if (min(partEnd, partStart) - r >= -depth) continue
            sc.cast.target.triangle(tri)
            cast(s, q, round, mx, my, mz, depth, sc)
        } else {
            val tparts = if (target is Compound) target.count else 1
            for (tp in 0 until tparts) {
                val ts = if (target is Compound) target.shapes[tp] else target!!
                val tq = if (target is Compound) sc.targetPart.setPart(targetPose!!, target, tp) else targetPose!!
                sc.cast.target.of(ts, tq, 0f)
                cast(s, q, round, mx, my, mz, depth, sc)
            }
        }
    }
}

/** One moving part, already placed at its end pose, cast back from where it started relative to the target. */
private fun cast(s: Shape, q: Pose, round: Float, mx: Float, my: Float, mz: Float, depth: Float, sc: ContinuousScratch) {
    val c = sc.cast
    c.moving.of(s, q, round)
    c.moving.shift(-mx.toDouble(), -my.toDouble(), -mz.toDouble())
    val length = sqrt(mx.toDouble() * mx + my.toDouble() * my + mz.toDouble() * mz)
    val ux = mx / length; val uy = my / length; val uz = mz / length
    val at = c.toi(ux, uy, uz, length, depth.toDouble())
    if (at.isNaN()) return
    val f = at / length
    if (f < sc.fraction) { sc.fraction = f; sc.rx = mx.toDouble(); sc.ry = my.toDouble(); sc.rz = mz.toDouble(); sc.length = length; sc.at = at }
}
