package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A convex polyhedron as the narrow phase reads it: vertices, face planes (n·x ≤ d inside), each face's
 * vertex loop (counter-clockwise about its normal) with one side plane per loop edge (pointing out of the
 * face, in its plane), and the edges with their two faces. A [ConvexHull] keeps one; a box is turned into
 * one on the fly (see [boxPolytope]).
 */
internal class Polytope(vertices: Int, faces: Int, loops: Int, edges: Int) {
    var vertexCount = 0; var faceCount = 0; var edgeCount = 0
    val v = FloatArray(3 * vertices)
    val plane = FloatArray(4 * faces)
    val faceStart = IntArray(faces + 1)
    val loop = IntArray(loops)
    /** Side plane of the loop edge leaving loop[k]: outward normal (in the face's plane) and offset. */
    val side = FloatArray(4 * loops)
    /** The two vertices of each edge, and its two faces: it runs edgeV[2e] → edgeV[2e+1] in face edgeF[2e]. */
    val edgeV = IntArray(2 * edges)
    val edgeF = IntArray(2 * edges)
    /**
     * Each edge's cone of outward normals (the arc between its two faces' normals): its middle direction
     * (x, y, z) and the cosine and sine of its half-angle. A bound of what the edge can do in the separating
     * axis test lets the far side of a hull skip it.
     */
    val edgeCone = FloatArray(5 * edges)

    fun copyFrom(o: Polytope) {
        vertexCount = o.vertexCount; faceCount = o.faceCount; edgeCount = o.edgeCount
        o.v.copyInto(v); o.plane.copyInto(plane); o.faceStart.copyInto(faceStart); o.loop.copyInto(loop)
        o.side.copyInto(side); o.edgeV.copyInto(edgeV); o.edgeF.copyInto(edgeF); o.edgeCone.copyInto(edgeCone)
    }

    /**
     * Writes into this polytope (a copy of the unit box's, [unit]) the box of half extents (hx, hy, hz): its
     * normals stay those of the unit box (they are the axes), only the vertices and offsets scale.
     */
    fun setBox(unit: Polytope, hx: Float, hy: Float, hz: Float) {
        val u = unit.v
        for (i in 0 until unit.vertexCount) { v[3 * i] = u[3 * i] * hx; v[3 * i + 1] = u[3 * i + 1] * hy; v[3 * i + 2] = u[3 * i + 2] * hz }
        for (f in 0 until unit.faceCount) {
            val o = 4 * f
            plane[o + 3] = abs(unit.plane[o]) * hx + abs(unit.plane[o + 1]) * hy + abs(unit.plane[o + 2]) * hz
        }
        for (k in 0 until unit.faceStart[unit.faceCount]) {
            val o = 4 * k
            side[o + 3] = abs(unit.side[o]) * hx + abs(unit.side[o + 1]) * hy + abs(unit.side[o + 2]) * hz
        }
    }
}

/** The unit box (half extents 1) as a polytope: the template of [Polytope.setBox]. */
internal val UNIT_BOX: Polytope = HullBuild(floatArrayOf(
    -1f, -1f, -1f, 1f, -1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, -1f, 1f, 1f, -1f, 1f, -1f, 1f, 1f, 1f, 1f, 1f), 8).poly

/** A box polytope sized for scratch use (8 vertices, 6 faces, 24 loop entries, 12 edges). */
internal fun boxPolytope() = Polytope(8, 6, 24, 12).also { it.copyFrom(UNIT_BOX) }

/**
 * A convex polyhedron made from points: the smallest convex shape holding them all (quickhull), its
 * coplanar triangles merged into polygons. Rocks, crates with bevels, hulls of boats, the collision
 * volumes of models (Blender's `col_*` objects).
 *
 * The hull is recentred on its centre of mass: its vertices are the points given minus [centre]. Put a body
 * at `position + rotation.rotate(hull.centre)` to have the points where they were given. Mass, centre of
 * mass and inertia are those of the solid polyhedron, exactly; the inertia is diagonal about
 * [principalAxes], which the engine handles.
 *
 * At most [maxVertices] vertices (4 to 255) are kept: quickhull adds the point farthest from the hull so far
 * at each round, so when it stops early it has kept the points that matter most, and every point left out
 * lies within [simplificationError] metres outside the hull. 32 is plenty for a collision volume and keeps
 * the collisions fast (they cost about the product of the two hulls' edge counts).
 *
 * Points that are all at one place, on a line or in a plane are refused (a hull needs a volume), as are
 * fewer than four points and values that are not finite.
 */
class ConvexHull private constructor(build: HullBuild) : Shape() {
    /** The hull of [points] (x, y, z, x, y, z…), with at most [maxVertices] vertices. */
    constructor(points: FloatArray, maxVertices: Int = 32) : this(HullBuild(points, maxVertices))
    /** The hull of [points], with at most [maxVertices] vertices. */
    constructor(points: List<Vec3>, maxVertices: Int = 32) :
        this(FloatArray(3 * points.size) { k -> points[k / 3][k % 3] }, maxVertices)

    internal val poly: Polytope = build.poly
    /** Where the centre of mass lies in the frame the points were given in; the hull is centred on it. */
    val centre: Vec3 = build.centre
    /** How far outside the hull the farthest point left out by the vertex limit lies (m); 0 when none was. */
    val simplificationError: Float = build.simplificationError
    override val volume: Float = build.volume
    override val boundingRadius: Float
    override val principalAxes: Quat = build.axes
    private val moments = build.moments              // principal moments per unit mass
    /** Distance from the centre to the nearest face plane: the largest ball about the centre inside the hull. */
    internal val inradius: Float
    // Bounds in the hull's frame (about its centre of mass).
    internal val minX: Float; internal val minY: Float; internal val minZ: Float
    internal val maxX: Float; internal val maxY: Float; internal val maxZ: Float

    init {
        val v = poly.v
        var r = 0f; var lx = Float.MAX_VALUE; var ly = Float.MAX_VALUE; var lz = Float.MAX_VALUE
        var hx = -Float.MAX_VALUE; var hy = -Float.MAX_VALUE; var hz = -Float.MAX_VALUE
        for (i in 0 until poly.vertexCount) {
            val x = v[3 * i]; val y = v[3 * i + 1]; val z = v[3 * i + 2]
            r = max(r, sqrt(x * x + y * y + z * z))
            lx = min(lx, x); ly = min(ly, y); lz = min(lz, z); hx = max(hx, x); hy = max(hy, y); hz = max(hz, z)
        }
        boundingRadius = r; minX = lx; minY = ly; minZ = lz; maxX = hx; maxY = hy; maxZ = hz
        var d = Float.MAX_VALUE
        for (f in 0 until poly.faceCount) d = min(d, poly.plane[4 * f + 3])
        inradius = d
    }

    val vertexCount: Int get() = poly.vertexCount
    val faceCount: Int get() = poly.faceCount
    val edgeCount: Int get() = poly.edgeCount

    /** Vertex [i], about the centre of mass. */
    fun vertex(i: Int): Vec3 { require(i in 0 until poly.vertexCount) { "No vertex $i" }; return Vec3(poly.v[3 * i], poly.v[3 * i + 1], poly.v[3 * i + 2]) }
    /** The vertices of face [f], counter-clockwise seen from outside. */
    fun face(f: Int): IntArray { require(f in 0 until poly.faceCount) { "No face $f" }; return poly.loop.copyOfRange(poly.faceStart[f], poly.faceStart[f + 1]) }
    /** Outward unit normal of face [f]. */
    fun faceNormal(f: Int): Vec3 { require(f in 0 until poly.faceCount) { "No face $f" }; return Vec3(poly.plane[4 * f], poly.plane[4 * f + 1], poly.plane[4 * f + 2]) }
    /** The faces as triangles (9 floats each, counter-clockwise seen from outside), about the centre of mass: to draw it. */
    fun triangles(): FloatArray {
        var n = 0
        for (f in 0 until poly.faceCount) n += poly.faceStart[f + 1] - poly.faceStart[f] - 2
        val out = FloatArray(9 * n); var o = 0
        for (f in 0 until poly.faceCount) {
            val s = poly.faceStart[f]; val e = poly.faceStart[f + 1]
            for (k in s + 1 until e - 1) for (vi in intArrayOf(poly.loop[s], poly.loop[k], poly.loop[k + 1])) {
                out[o++] = poly.v[3 * vi]; out[o++] = poly.v[3 * vi + 1]; out[o++] = poly.v[3 * vi + 2]
            }
        }
        return out
    }

    override fun inertia(mass: Float) = Vec3(moments.x * mass, moments.y * mass, moments.z * mass)

    /** Whether [point] (in the hull's frame, about its centre of mass) is inside the hull or on it. */
    fun contains(point: Vec3): Boolean {
        for (f in 0 until poly.faceCount) {
            val o = 4 * f
            if (poly.plane[o] * point.x + poly.plane[o + 1] * point.y + poly.plane[o + 2] * point.z > poly.plane[o + 3]) return false
        }
        return true
    }

    /** The same hull [factor] times larger (about its centre of mass; [centre] scales too). */
    override fun scaled(factor: Float): ConvexHull {
        require(factor > 0f) { "A scale must be positive" }
        val pts = FloatArray(3 * poly.vertexCount) { k -> poly.v[k] * factor + centre[k % 3] * factor }
        return ConvexHull(pts, max(4, poly.vertexCount))
    }

    override fun toString() = "ConvexHull(${poly.vertexCount} vertices, ${poly.faceCount} faces)"
}

/**
 * Builds a hull: quickhull in double precision, coplanar triangles merged, collinear vertices dropped, then
 * the exact mass properties of the solid (tetrahedra from an inner point) and their principal axes.
 */
internal class HullBuild(points: FloatArray, maxVertices: Int) {
    lateinit var poly: Polytope
    var centre = Vec3.ZERO; var volume = 0f; var simplificationError = 0f
    var axes = Quat.IDENTITY; var moments = Vec3.ZERO

    private val n = points.size / 3
    private val p = DoubleArray(points.size) { points[it].toDouble() }
    private var eps = 0.0

    // Quickhull's faces: triangles (vertex = point index), counter-clockwise from outside.
    private inner class Face(val a: Int, val b: Int, val c: Int) {
        val nx: Double; val ny: Double; val nz: Double; val d: Double
        val next = IntArray(3)               // face across edge a→b, b→c, c→a
        val outside = ArrayList<Int>()
        var alive = true
        init {
            val ux = p[3 * b] - p[3 * a]; val uy = p[3 * b + 1] - p[3 * a + 1]; val uz = p[3 * b + 2] - p[3 * a + 2]
            val vx = p[3 * c] - p[3 * a]; val vy = p[3 * c + 1] - p[3 * a + 1]; val vz = p[3 * c + 2] - p[3 * a + 2]
            var x = uy * vz - uz * vy; var y = uz * vx - ux * vz; var z = ux * vy - uy * vx
            val l = sqrt(x * x + y * y + z * z)
            if (l > 0.0) { x /= l; y /= l; z /= l }
            nx = x; ny = y; nz = z; d = x * p[3 * a] + y * p[3 * a + 1] + z * p[3 * a + 2]
        }
        fun vertex(k: Int) = when (k) { 0 -> a; 1 -> b; else -> c }
        fun distance(i: Int) = nx * p[3 * i] + ny * p[3 * i + 1] + nz * p[3 * i + 2] - d
    }
    private val faces = ArrayList<Face>()

    init {
        require(points.size % 3 == 0) { "A convex hull needs 3 floats per point" }
        require(n >= 4) { "A convex hull needs at least 4 points, got $n" }
        require(maxVertices in 4..255) { "A convex hull keeps 4 to 255 vertices, not $maxVertices" }
        for (x in points) require(x.isFinite()) { "The points of a convex hull must be finite numbers" }
        build(maxVertices)
    }

    private fun dist2(i: Int, j: Int): Double {
        val x = p[3 * i] - p[3 * j]; val y = p[3 * i + 1] - p[3 * j + 1]; val z = p[3 * i + 2] - p[3 * j + 2]
        return x * x + y * y + z * z
    }

    private fun build(maxVertices: Int) {
        // Tolerance: a hundred-thousandth of the points' extent (the points are floats, noisy in their 7th digit).
        var lo = DoubleArray(3) { Double.MAX_VALUE }; var hi = DoubleArray(3) { -Double.MAX_VALUE }
        val ext = IntArray(6)                         // points of least and most x, y, z
        for (i in 0 until n) for (k in 0..2) {
            val x = p[3 * i + k]
            if (x < lo[k]) { lo[k] = x; ext[2 * k] = i }
            if (x > hi[k]) { hi[k] = x; ext[2 * k + 1] = i }
        }
        val diag = sqrt((hi[0] - lo[0]) * (hi[0] - lo[0]) + (hi[1] - lo[1]) * (hi[1] - lo[1]) + (hi[2] - lo[2]) * (hi[2] - lo[2]))
        eps = diag * 1e-5
        require(diag > 1e-6) { "The points of a convex hull are all at one place" }
        // The first simplex: the two extreme points farthest apart, the point farthest from their line, the point farthest from that plane.
        var i0 = ext[0]; var i1 = ext[1]; var best = -1.0
        for (a in 0 until 6) for (b in a + 1 until 6) { val d = dist2(ext[a], ext[b]); if (d > best) { best = d; i0 = ext[a]; i1 = ext[b] } }
        require(sqrt(best) > eps) { "The points of a convex hull are all at one place" }
        var i2 = -1; best = -1.0
        val dx = p[3 * i1] - p[3 * i0]; val dy = p[3 * i1 + 1] - p[3 * i0 + 1]; val dz = p[3 * i1 + 2] - p[3 * i0 + 2]
        val dl = sqrt(dx * dx + dy * dy + dz * dz)
        for (i in 0 until n) {
            val wx = p[3 * i] - p[3 * i0]; val wy = p[3 * i + 1] - p[3 * i0 + 1]; val wz = p[3 * i + 2] - p[3 * i0 + 2]
            val cx = wy * dz - wz * dy; val cy = wz * dx - wx * dz; val cz = wx * dy - wy * dx
            val d = sqrt(cx * cx + cy * cy + cz * cz) / dl
            if (d > best) { best = d; i2 = i }
        }
        require(best > eps) { "The points of a convex hull lie on a line: a hull needs a volume" }
        val base = Face(i0, i1, i2)
        var i3 = -1; best = 0.0
        for (i in 0 until n) { val d = base.distance(i); if (abs(d) > abs(best)) { best = d; i3 = i } }
        require(abs(best) > eps) { "The points of a convex hull lie in a plane: a hull needs a volume (give it some thickness)" }
        // Four faces turned outwards (the fourth point below the base: keep the base's winding).
        if (best < 0.0) addSimplex(i0, i1, i2, i3) else addSimplex(i0, i2, i1, i3)
        var vertices = 4
        val used = BooleanArray(n); used[i0] = true; used[i1] = true; used[i2] = true; used[i3] = true
        for (i in 0 until n) if (!used[i]) assign(i, 0, faces.size)
        val visible = ArrayList<Int>(); val horizon = ArrayList<Int>()      // horizon: (face, edge) pairs
        while (true) {
            // The point farthest out of the hull so far, over every face (the first on a tie).
            var face = -1; var eye = -1; var far = eps
            for (f in faces.indices) {
                val fc = faces[f]
                if (!fc.alive) continue
                for (i in fc.outside) { val d = fc.distance(i); if (d > far) { far = d; face = f; eye = i } }
            }
            if (eye < 0) break
            if (vertices == maxVertices) { simplificationError = far.toFloat(); break }
            vertices++
            visible.clear(); horizon.clear()
            findHorizon(eye, face, -1, visible, horizon)
            val first = faces.size
            for (k in 0 until horizon.size / 2) {
                val f = faces[horizon[2 * k]]; val e = horizon[2 * k + 1]
                val a = f.vertex(e); val b = f.vertex((e + 1) % 3)
                val nf = Face(a, b, eye)
                val outer = f.next[e]
                nf.next[0] = outer
                val back = faces[outer]
                for (j in 0..2) if (back.vertex(j) == b && back.vertex((j + 1) % 3) == a) back.next[j] = faces.size
                faces.add(nf)
            }
            val m = faces.size - first
            for (k in 0 until m) {
                val f = faces[first + k]
                f.next[1] = first + (k + 1) % m           // b→eye: the next new face
                f.next[2] = first + (k + m - 1) % m       // eye→a: the previous one
            }
            for (f in visible) { val fc = faces[f]; fc.alive = false; for (i in fc.outside) if (i != eye) assign(i, first, faces.size); fc.outside.clear() }
        }
        finish()
    }

    private fun addSimplex(a: Int, b: Int, c: Int, d: Int) {
        // Base a, b, c (counter-clockwise from outside, d behind it), and the three sides.
        faces.add(Face(a, b, c)); faces.add(Face(a, d, b)); faces.add(Face(b, d, c)); faces.add(Face(c, d, a))
        for (f in 0..3) for (e in 0..2) {
            val x = faces[f].vertex(e); val y = faces[f].vertex((e + 1) % 3)
            for (g in 0..3) if (g != f) for (j in 0..2) if (faces[g].vertex(j) == y && faces[g].vertex((j + 1) % 3) == x) faces[f].next[e] = g
        }
    }

    /** Puts point [i] in the outside set of the face among [from, to) it is farthest out of, if any. */
    private fun assign(i: Int, from: Int, to: Int) {
        var best = -1; var far = eps
        for (f in from until to) { val fc = faces[f]; if (!fc.alive) continue; val d = fc.distance(i); if (d > far) { far = d; best = f } }
        if (best >= 0) faces[best].outside.add(i)
    }

    /** Depth-first over the faces [eye] sees: lists them, and the horizon edges in order around the eye. */
    private fun findHorizon(eye: Int, f: Int, from: Int, visible: ArrayList<Int>, horizon: ArrayList<Int>) {
        visible.add(f)
        val fc = faces[f]
        // Start after the edge we came through, so the horizon comes out as one loop.
        val start = if (from < 0) 0 else (0..2).first { fc.next[it] == from }
        for (k in 0..2) {
            val e = (start + k) % 3
            val g = fc.next[e]
            if (g == from && from >= 0) continue
            if (g in visible) continue
            if (faces[g].distance(eye) > eps) findHorizon(eye, g, f, visible, horizon)
            else { horizon.add(f); horizon.add(e) }
        }
    }

    // ---------------------------------------------------------------- merging, loops, mass

    private fun finish() {
        val tri = faces.filter { it.alive }
        val index = HashMap<Face, Int>(); tri.forEachIndexed { k, f -> index[f] = k }
        val across = Array(tri.size) { t -> IntArray(3) { e -> index.getValue(faces[tri[t].next[e]]) } }
        var loops = mergedLoops(tri, across, merge = true) ?: mergedLoops(tri, across, merge = false)!!
        loops = dropCollinear(loops) ?: loops
        // The vertices used, in the order of the points given.
        val usedPts = loops.flatMap { it.toList() }.distinct().sorted()
        val newIndex = HashMap<Int, Int>(); usedPts.forEachIndexed { k, i -> newIndex[i] = k }
        val faceLoops = loops.map { l -> IntArray(l.size) { newIndex.getValue(l[it]) } }
        val vCount = usedPts.size
        val vd = DoubleArray(3 * vCount) { k -> p[3 * usedPts[k / 3] + k % 3] }
        massProperties(vd, faceLoops)
        // Recentred floats, then planes, side planes and edges.
        val c = doubleArrayOf(centre.x.toDouble(), centre.y.toDouble(), centre.z.toDouble())
        val loopTotal = faceLoops.sumOf { it.size }
        val edges = loopTotal / 2
        val poly = Polytope(vCount, faceLoops.size, loopTotal, edges)
        poly.vertexCount = vCount; poly.faceCount = faceLoops.size; poly.edgeCount = edges
        for (k in 0 until 3 * vCount) poly.v[k] = (vd[k] - c[k % 3]).toFloat()
        var at = 0
        for ((f, l) in faceLoops.withIndex()) {
            poly.faceStart[f] = at
            // Newell's normal, in double, from the recentred points.
            var nx = 0.0; var ny = 0.0; var nz = 0.0
            for (k in l.indices) {
                val a = l[k]; val b = l[(k + 1) % l.size]
                val ax = vd[3 * a] - c[0]; val ay = vd[3 * a + 1] - c[1]; val az = vd[3 * a + 2] - c[2]
                val bx = vd[3 * b] - c[0]; val by = vd[3 * b + 1] - c[1]; val bz = vd[3 * b + 2] - c[2]
                nx += (ay - by) * (az + bz); ny += (az - bz) * (ax + bx); nz += (ax - bx) * (ay + by)
            }
            val nl = sqrt(nx * nx + ny * ny + nz * nz); nx /= nl; ny /= nl; nz /= nl
            val fx = nx.toFloat(); val fy = ny.toFloat(); val fz = nz.toFloat()
            var d = -Float.MAX_VALUE
            for (vi in l) d = max(d, fx * poly.v[3 * vi] + fy * poly.v[3 * vi + 1] + fz * poly.v[3 * vi + 2])
            poly.plane[4 * f] = fx; poly.plane[4 * f + 1] = fy; poly.plane[4 * f + 2] = fz; poly.plane[4 * f + 3] = d
            for (k in l.indices) {
                poly.loop[at + k] = l[k]
                val a = l[k]; val b = l[(k + 1) % l.size]
                val ex = poly.v[3 * b] - poly.v[3 * a]; val ey = poly.v[3 * b + 1] - poly.v[3 * a + 1]; val ez = poly.v[3 * b + 2] - poly.v[3 * a + 2]
                var sx = ey * fz - ez * fy; var sy = ez * fx - ex * fz; var sz = ex * fy - ey * fx
                val sl = sqrt(sx * sx + sy * sy + sz * sz); sx /= sl; sy /= sl; sz /= sl
                val o = 4 * (at + k)
                poly.side[o] = sx; poly.side[o + 1] = sy; poly.side[o + 2] = sz
                poly.side[o + 3] = sx * poly.v[3 * a] + sy * poly.v[3 * a + 1] + sz * poly.v[3 * a + 2]
            }
            at += l.size
        }
        poly.faceStart[faceLoops.size] = at
        // Edges, each once (from its lower vertex), with the face where it runs that way and its twin's face.
        val faceOf = HashMap<Long, Int>()
        for ((f, l) in faceLoops.withIndex()) for (k in l.indices) faceOf[l[k].toLong() * 65536 + l[(k + 1) % l.size]] = f
        var e = 0
        for ((f, l) in faceLoops.withIndex()) for (k in l.indices) {
            val a = l[k]; val b = l[(k + 1) % l.size]
            if (a > b) continue
            poly.edgeV[2 * e] = a; poly.edgeV[2 * e + 1] = b
            poly.edgeF[2 * e] = f; poly.edgeF[2 * e + 1] = faceOf.getValue(b.toLong() * 65536 + a)
            val f1 = 4 * poly.edgeF[2 * e]; val f2 = 4 * poly.edgeF[2 * e + 1]
            var mx = poly.plane[f1] + poly.plane[f2]; var my = poly.plane[f1 + 1] + poly.plane[f2 + 1]; var mz = poly.plane[f1 + 2] + poly.plane[f2 + 2]
            val ml = sqrt(mx * mx + my * my + mz * mz); mx /= ml; my /= ml; mz /= ml
            val c = min(1f, mx * poly.plane[f1] + my * poly.plane[f1 + 1] + mz * poly.plane[f1 + 2])
            poly.edgeCone[5 * e] = mx; poly.edgeCone[5 * e + 1] = my; poly.edgeCone[5 * e + 2] = mz
            poly.edgeCone[5 * e + 3] = c; poly.edgeCone[5 * e + 4] = sqrt(max(0f, 1f - c * c))
            e++
        }
        check(e == edges) { "Broken hull: $e edges for $edges" }
        this.poly = poly
    }

    /**
     * Groups the triangles into faces (neighbours whose points lie within the tolerance of each other's
     * plane, normals within 0.1 rad) and returns each face's boundary loop; null if a group does not make one
     * simple loop (then the triangles are kept apart).
     */
    private fun mergedLoops(tri: List<Face>, across: Array<IntArray>, merge: Boolean): List<IntArray>? {
        val parent = IntArray(tri.size) { it }
        fun root(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        if (merge) for (t in tri.indices) for (e in 0..2) {
            val u = across[t][e]
            if (u <= t) continue
            val a = tri[t]; val b = tri[u]
            if (a.nx * b.nx + a.ny * b.ny + a.nz * b.nz < .995) continue
            if (abs(a.distance(b.a)) > eps || abs(a.distance(b.b)) > eps || abs(a.distance(b.c)) > eps) continue
            if (abs(b.distance(a.a)) > eps || abs(b.distance(a.b)) > eps || abs(b.distance(a.c)) > eps) continue
            val ra = root(t); val rb = root(u); if (ra != rb) parent[max(ra, rb)] = min(ra, rb)
        }
        val out = ArrayList<IntArray>()
        val groups = LinkedHashMap<Int, ArrayList<Int>>()
        for (t in tri.indices) groups.getOrPut(root(t)) { ArrayList() }.add(t)
        for ((_, g) in groups) {
            val inGroup = HashSet(g)
            val next = HashMap<Int, Int>()
            for (t in g) for (e in 0..2) if (across[t][e] !in inGroup) {
                val a = tri[t].vertex(e); val b = tri[t].vertex((e + 1) % 3)
                if (next.put(a, b) != null) return null                         // the boundary touches itself
            }
            val start = next.keys.min()
            val l = ArrayList<Int>(); var x = start
            do { l.add(x); x = next[x] ?: return null; if (l.size > next.size) return null } while (x != start)
            if (l.size != next.size) return null                                // more than one loop (a hole)
            out.add(l.toIntArray())
        }
        val vCount = out.flatMap { it.toList() }.distinct().size
        val eCount = out.sumOf { it.size } / 2
        return if (vCount - eCount + out.size == 2) out else null
    }

    /** Drops the vertices only two faces share: they sit in the middle of a straight edge. Null if that breaks a face. */
    private fun dropCollinear(loops: List<IntArray>): List<IntArray>? {
        val count = HashMap<Int, Int>()
        for (l in loops) for (v in l) count[v] = (count[v] ?: 0) + 1
        if (count.values.none { it <= 2 }) return loops
        val out = loops.map { l -> l.filter { count.getValue(it) > 2 }.toIntArray() }
        if (out.any { it.size < 3 }) return null
        val vCount = out.flatMap { it.toList() }.distinct().size
        return if (vCount - out.sumOf { it.size } / 2 + out.size == 2) out else null
    }

    /**
     * Volume, centre of mass and inertia of the solid, exactly: a tetrahedron from an inner point to each
     * triangle of each face's fan (second moments V/20 (Σ pᵢpᵢᵀ + (Σ pᵢ)(Σ pᵢ)ᵀ)), then Jacobi's rotations for
     * the principal axes.
     */
    private fun massProperties(vd: DoubleArray, loops: List<IntArray>) {
        val m = vd.size / 3
        var ox = 0.0; var oy = 0.0; var oz = 0.0
        for (i in 0 until m) { ox += vd[3 * i]; oy += vd[3 * i + 1]; oz += vd[3 * i + 2] }
        ox /= m; oy /= m; oz /= m
        var vol = 0.0; var cx = 0.0; var cy = 0.0; var cz = 0.0
        val s = DoubleArray(6)                     // ∫ xx, yy, zz, xy, xz, yz about the inner point
        for (l in loops) for (k in 1 until l.size - 1) {
            val a = l[0]; val b = l[k]; val c = l[k + 1]
            val ax = vd[3 * a] - ox; val ay = vd[3 * a + 1] - oy; val az = vd[3 * a + 2] - oz
            val bx = vd[3 * b] - ox; val by = vd[3 * b + 1] - oy; val bz = vd[3 * b + 2] - oz
            val qx = vd[3 * c] - ox; val qy = vd[3 * c + 1] - oy; val qz = vd[3 * c + 2] - oz
            val det = ax * (by * qz - bz * qy) - ay * (bx * qz - bz * qx) + az * (bx * qy - by * qx)
            val v = det / 6.0
            vol += v
            val sx = ax + bx + qx; val sy = ay + by + qy; val sz = az + bz + qz
            cx += v * sx / 4.0; cy += v * sy / 4.0; cz += v * sz / 4.0
            val f = v / 20.0
            s[0] += f * (ax * ax + bx * bx + qx * qx + sx * sx)
            s[1] += f * (ay * ay + by * by + qy * qy + sy * sy)
            s[2] += f * (az * az + bz * bz + qz * qz + sz * sz)
            s[3] += f * (ax * ay + bx * by + qx * qy + sx * sy)
            s[4] += f * (ax * az + bx * bz + qx * qz + sx * sz)
            s[5] += f * (ay * az + by * bz + qy * qz + sy * sz)
        }
        require(vol > 1e-12) { "The points of a convex hull lie in a plane: a hull needs a volume (give it some thickness)" }
        cx /= vol; cy /= vol; cz /= vol
        // About the centre of mass, per unit mass.
        val xx = s[0] / vol - cx * cx; val yy = s[1] / vol - cy * cy; val zz = s[2] / vol - cz * cz
        val xy = s[3] / vol - cx * cy; val xz = s[4] / vol - cx * cz; val yz = s[5] / vol - cy * cz
        val inertia = doubleArrayOf(yy + zz, -xy, -xz, -xy, xx + zz, -yz, -xz, -yz, xx + yy)
        volume = vol.toFloat()
        centre = Vec3((cx + ox).toFloat(), (cy + oy).toFloat(), (cz + oz).toFloat())
        val (q, mom) = principal(inertia)
        axes = q; moments = mom
    }
}

/**
 * Principal axes of a symmetric 3 × 3 inertia tensor [t] (row by row): the rotation whose columns are the
 * axes (identity when the tensor is already diagonal) and the moments about them. Jacobi's rotations.
 */
internal fun principal(t: DoubleArray): Pair<Quat, Vec3> {
    val a = t.copyOf()
    val scale = abs(a[0]) + abs(a[4]) + abs(a[8])
    if (abs(a[1]) + abs(a[2]) + abs(a[5]) <= 1e-9 * scale) return Quat.IDENTITY to Vec3(a[0].toFloat(), a[4].toFloat(), a[8].toFloat())
    val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    repeat(50) {
        val off = abs(a[1]) + abs(a[2]) + abs(a[5])
        if (off <= 1e-15 * scale) return@repeat
        for ((pp, qq) in listOf(0 to 1, 0 to 2, 1 to 2)) {
            val apq = a[3 * pp + qq]
            if (abs(apq) <= 1e-18 * scale) continue
            val theta = (a[3 * qq + qq] - a[3 * pp + pp]) / (2 * apq)
            val tt = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
            val c = 1 / sqrt(tt * tt + 1); val sn = tt * c
            for (k in 0..2) {                    // A ← A·J, then Jᵀ·A
                val akp = a[3 * k + pp]; val akq = a[3 * k + qq]
                a[3 * k + pp] = c * akp - sn * akq; a[3 * k + qq] = sn * akp + c * akq
            }
            for (k in 0..2) {
                val apk = a[3 * pp + k]; val aqk = a[3 * qq + k]
                a[3 * pp + k] = c * apk - sn * aqk; a[3 * qq + k] = sn * apk + c * aqk
            }
            for (k in 0..2) {
                val vkp = v[3 * k + pp]; val vkq = v[3 * k + qq]
                v[3 * k + pp] = c * vkp - sn * vkq; v[3 * k + qq] = sn * vkp + c * vkq
            }
        }
    }
    // Columns of v are the axes; make them a right-handed rotation.
    val det = v[0] * (v[4] * v[8] - v[5] * v[7]) - v[1] * (v[3] * v[8] - v[5] * v[6]) + v[2] * (v[3] * v[7] - v[4] * v[6])
    if (det < 0) { v[2] = -v[2]; v[5] = -v[5]; v[8] = -v[8] }
    val ax = Vec3(v[0].toFloat(), v[3].toFloat(), v[6].toFloat())
    val ay = Vec3(v[1].toFloat(), v[4].toFloat(), v[7].toFloat())
    val az = Vec3(v[2].toFloat(), v[5].toFloat(), v[8].toFloat())
    return Quat.fromAxes(ax, ay, az).normalized() to Vec3(a[0].toFloat(), a[4].toFloat(), a[8].toFloat())
}
