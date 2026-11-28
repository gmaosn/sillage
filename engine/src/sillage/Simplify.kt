package sillage

import kotlin.math.roundToInt

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A lighter version of this mesh, for far away ([Node.lod]): about [keep] of its triangles (0 to 1), the
 * shape kept as well as it can be. Edges are collapsed one after the other, the one that changes the
 * surface least first (quadric error metrics, Garland & Heckbert 1997); borders, colour and texture seams
 * and material borders hold firm, and no collapse may turn a face over. Normals are made again, soft.
 */
fun Mesh.simplified(keep: Number): Mesh {
    val k = keep.toFloat()
    require(k > 0f && k <= 1f) { "simplified(keep): a share of the triangles, above 0 and at most 1 (got $keep)" }
    if (k >= 1f || triangleCount < 8) return this
    return Simplifier(this).run((triangleCount * k).toInt().coerceAtLeast(4))
}

private class Simplifier(private val mesh: Mesh) {
    private val v = mesh.vertices
    private val e = mesh.effects
    private val mats = mesh.materials
    private val nTri = mesh.triangleCount

    // Vertices welded where position, colour and effects are all equal (a seam keeps its two sides apart).
    private val px = FloatArray(3 * nTri); private val py = FloatArray(3 * nTri); private val pz = FloatArray(3 * nTri)
    private val source = IntArray(3 * nTri)               // a corner of the mesh that carries the vertex's attributes
    private var nVert = 0
    private val tri = IntArray(3 * nTri)
    private val alive = BooleanArray(nTri) { true }
    private var liveTris = nTri
    private val q = DoubleArray(10 * 3 * nTri)            // quadric per vertex: a² ab ac ad b² bc bd c² cd d²
    private val version = IntArray(3 * nTri)
    private val removed = BooleanArray(3 * nTri)
    private val faces = ArrayList<IntArrayList>()

    init {
        // Positions closer than a hundred-thousandth of the mesh's size are the same (a sphere's seam, where
        // cos 2π is not quite 1, closes).
        val (lo, hi) = mesh.bounds
        val grain = maxOf(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z, 1e-6f) * 1e-5f
        val index = HashMap<String, Int>()
        for (c in 0 until 3 * nTri) {
            val o = 9 * c
            val sb = StringBuilder()
            for (j in 0..2) sb.append((v[o + j] / grain).roundToInt()).append(',')
            for (j in 6..8) sb.append(v[o + j].toRawBits()).append(',')
            if (e != null) for (j in 0 until EFFECT_FLOATS) sb.append(e[EFFECT_FLOATS * c + j].toRawBits()).append(',')
            val id = index.getOrPut(sb.toString()) {
                px[nVert] = v[o]; py[nVert] = v[o + 1]; pz[nVert] = v[o + 2]; source[nVert] = c; faces.add(IntArrayList()); nVert++
            }
            tri[c] = id
        }
        for (t in 0 until nTri) for (j in 0..2) faces[tri[3 * t + j]].add(t)
        for (t in 0 until nTri) addFaceQuadric(t, 1.0)
        borders()
    }

    private fun addPlane(vertex: Int, a: Double, b: Double, c: Double, d: Double, w: Double) {
        val o = 10 * vertex
        q[o] += w * a * a; q[o + 1] += w * a * b; q[o + 2] += w * a * c; q[o + 3] += w * a * d
        q[o + 4] += w * b * b; q[o + 5] += w * b * c; q[o + 6] += w * b * d
        q[o + 7] += w * c * c; q[o + 8] += w * c * d; q[o + 9] += w * d * d
    }

    private fun addFaceQuadric(t: Int, weight: Double) {
        val a = tri[3 * t]; val b = tri[3 * t + 1]; val c = tri[3 * t + 2]
        val ux = (px[b] - px[a]).toDouble(); val uy = (py[b] - py[a]).toDouble(); val uz = (pz[b] - pz[a]).toDouble()
        val vx = (px[c] - px[a]).toDouble(); val vy = (py[c] - py[a]).toDouble(); val vz = (pz[c] - pz[a]).toDouble()
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-20) return
        val area = l / 2
        nx /= l; ny /= l; nz /= l
        val d = -(nx * px[a] + ny * py[a] + nz * pz[a])
        for (vertex in intArrayOf(a, b, c)) addPlane(vertex, nx, ny, nz, d, area * weight)
    }

    /**
     * Borders (edges of a single face) and seams (edges whose two faces have different materials) hold firm:
     * a steep plane through the edge, perpendicular to its face, in both its ends' quadrics.
     */
    private fun borders() {
        val count = HashMap<Long, Int>(); val first = HashMap<Long, Int>()
        fun key(a: Int, b: Int) = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
        for (t in 0 until nTri) for (j in 0..2) {
            val kk = key(tri[3 * t + j], tri[3 * t + (j + 1) % 3])
            count[kk] = (count[kk] ?: 0) + 1
            if (kk !in first) first[kk] = t else if (mats != null && mats[first[kk]!!] != mats[t]) count[kk] = 1
        }
        for ((kk, n) in count) if (n == 1) {
            val a = (kk ushr 32).toInt(); val b = (kk and 0xffffffffL).toInt(); val t = first[kk]!!
            val ex = (px[b] - px[a]).toDouble(); val ey = (py[b] - py[a]).toDouble(); val ez = (pz[b] - pz[a]).toDouble()
            val f0 = tri[3 * t]; val f1 = tri[3 * t + 1]; val f2 = tri[3 * t + 2]
            val ux = (px[f1] - px[f0]).toDouble(); val uy = (py[f1] - py[f0]).toDouble(); val uz = (pz[f1] - pz[f0]).toDouble()
            val vx = (px[f2] - px[f0]).toDouble(); val vy = (py[f2] - py[f0]).toDouble(); val vz = (pz[f2] - pz[f0]).toDouble()
            val fnx = uy * vz - uz * vy; val fny = uz * vx - ux * vz; val fnz = ux * vy - uy * vx
            var nx = ey * fnz - ez * fny; var ny = ez * fnx - ex * fnz; var nz = ex * fny - ey * fnx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-20) continue
            nx /= l; ny /= l; nz /= l
            val d = -(nx * px[a] + ny * py[a] + nz * pz[a])
            val w = 1000.0 * (ex * ex + ey * ey + ez * ez)
            addPlane(a, nx, ny, nz, d, w); addPlane(b, nx, ny, nz, d, w)
        }
    }

    private fun error(o1: Int, o2: Int, x: Double, y: Double, z: Double): Double {
        fun c(i: Int) = q[o1 + i] + q[o2 + i]
        return c(0) * x * x + 2 * c(1) * x * y + 2 * c(2) * x * z + 2 * c(3) * x + c(4) * y * y + 2 * c(5) * y * z + 2 * c(6) * y +
            c(7) * z * z + 2 * c(8) * z + c(9)
    }

    /** An edge's collapse: its cost and where the kept vertex goes (one end or the middle, the cheapest). */
    private class Collapse(val a: Int, val b: Int, val cost: Double, val x: Float, val y: Float, val z: Float, val va: Int, val vb: Int)

    private fun collapse(a: Int, b: Int): Collapse {
        val o1 = 10 * a; val o2 = 10 * b
        var best = Double.MAX_VALUE; var bx = 0f; var by = 0f; var bz = 0f
        // The point where the error is least: the quadric's minimum, when its system is well conditioned
        // and the point stays near the edge (else one of the ends or the middle).
        val a11 = q[o1] + q[o2]; val a12 = q[o1 + 1] + q[o2 + 1]; val a13 = q[o1 + 2] + q[o2 + 2]
        val a22 = q[o1 + 4] + q[o2 + 4]; val a23 = q[o1 + 5] + q[o2 + 5]; val a33 = q[o1 + 7] + q[o2 + 7]
        val b1 = -(q[o1 + 3] + q[o2 + 3]); val b2 = -(q[o1 + 6] + q[o2 + 6]); val b3 = -(q[o1 + 8] + q[o2 + 8])
        val det = a11 * (a22 * a33 - a23 * a23) - a12 * (a12 * a33 - a23 * a13) + a13 * (a12 * a23 - a22 * a13)
        val scale = maxOf(abs(a11), abs(a22), abs(a33), 1e-30)
        if (abs(det) > 1e-9 * scale * scale * scale) {
            val x = (b1 * (a22 * a33 - a23 * a23) - a12 * (b2 * a33 - a23 * b3) + a13 * (b2 * a23 - a22 * b3)) / det
            val y = (a11 * (b2 * a33 - b3 * a23) - b1 * (a12 * a33 - a23 * a13) + a13 * (a12 * b3 - b2 * a13)) / det
            val z = (a11 * (a22 * b3 - a23 * b2) - a12 * (a12 * b3 - b2 * a13) + b1 * (a12 * a23 - a22 * a13)) / det
            val mx = (px[a] + px[b]) / 2.0; val my = (py[a] + py[b]) / 2.0; val mz = (pz[a] + pz[b]) / 2.0
            val ex = (px[a] - px[b]).toDouble(); val ey = (py[a] - py[b]).toDouble(); val ez = (pz[a] - pz[b]).toDouble()
            val reach = ex * ex + ey * ey + ez * ez
            if ((x - mx) * (x - mx) + (y - my) * (y - my) + (z - mz) * (z - mz) <= reach) {
                best = error(o1, o2, x, y, z); bx = x.toFloat(); by = y.toFloat(); bz = z.toFloat()
            }
        }
        for (s in 0..2) {
            val x = when (s) { 0 -> px[a]; 1 -> px[b]; else -> (px[a] + px[b]) / 2 }
            val y = when (s) { 0 -> py[a]; 1 -> py[b]; else -> (py[a] + py[b]) / 2 }
            val z = when (s) { 0 -> pz[a]; 1 -> pz[b]; else -> (pz[a] + pz[b]) / 2 }
            val err = error(o1, o2, x.toDouble(), y.toDouble(), z.toDouble())
            if (err < best) { best = err; bx = x; by = y; bz = z }
        }
        return Collapse(a, b, max(best, 0.0), bx, by, bz, version[a], version[b])
    }

    /** True if moving [moved] to (x, y, z) turns over or squashes any of its faces that do not also hold [other]. */
    private fun flips(moved: Int, other: Int, x: Float, y: Float, z: Float): Boolean {
        val list = faces[moved]
        for (i in 0 until list.size) {
            val t = list[i]
            if (!alive[t]) continue
            val a = tri[3 * t]; val b = tri[3 * t + 1]; val c = tri[3 * t + 2]
            if (a == other || b == other || c == other) continue
            fun p(vv: Int, k: Int): Float = if (vv == moved) (when (k) { 0 -> x; 1 -> y; else -> z }) else when (k) { 0 -> px[vv]; 1 -> py[vv]; else -> pz[vv] }
            fun normal(useNew: Boolean): DoubleArray {
                fun g(vv: Int, k: Int) = if (useNew) p(vv, k) else when (k) { 0 -> px[vv]; 1 -> py[vv]; else -> pz[vv] }
                val ux = (g(b, 0) - g(a, 0)).toDouble(); val uy = (g(b, 1) - g(a, 1)).toDouble(); val uz = (g(b, 2) - g(a, 2)).toDouble()
                val vx = (g(c, 0) - g(a, 0)).toDouble(); val vy = (g(c, 1) - g(a, 1)).toDouble(); val vz = (g(c, 2) - g(a, 2)).toDouble()
                return doubleArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
            }
            val n0 = normal(false); val n1 = normal(true)
            val l0 = sqrt(n0[0] * n0[0] + n0[1] * n0[1] + n0[2] * n0[2]); val l1 = sqrt(n1[0] * n1[0] + n1[1] * n1[1] + n1[2] * n1[2])
            if (l1 < 1e-12 * max(l0, 1e-12)) return true
            if ((n0[0] * n1[0] + n0[1] * n1[1] + n0[2] * n1[2]) / (l0 * l1) < .3) return true
        }
        return false
    }

    fun run(target: Int): Mesh {
        val heap = MinHeap<Collapse>(compareBy { it.cost })
        val seen = HashSet<Long>()
        for (t in 0 until nTri) for (j in 0..2) {
            val a = tri[3 * t + j]; val b = tri[3 * t + (j + 1) % 3]
            val kk = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
            if (seen.add(kk)) heap.add(collapse(a, b))
        }
        while (liveTris > target && heap.isNotEmpty()) {
            val c = heap.poll()
            if (removed[c.a] || removed[c.b] || version[c.a] != c.va || version[c.b] != c.vb) continue
            if (flips(c.a, c.b, c.x, c.y, c.z) || flips(c.b, c.a, c.x, c.y, c.z)) continue
            // b goes into a: a moves, takes b's faces and quadric; the faces holding both disappear.
            val a = c.a; val b = c.b
            px[a] = c.x; py[a] = c.y; pz[a] = c.z
            for (i in 0 until 10) q[10 * a + i] += q[10 * b + i]
            val fb = faces[b]
            for (i in 0 until fb.size) {
                val t = fb[i]
                if (!alive[t]) continue
                var hasA = false
                for (j in 0..2) if (tri[3 * t + j] == a) hasA = true
                if (hasA) { alive[t] = false; liveTris--; continue }
                for (j in 0..2) if (tri[3 * t + j] == b) tri[3 * t + j] = a
                faces[a].add(t)
            }
            removed[b] = true
            version[a]++
            // Its edges cost something else now.
            val near = HashSet<Int>()
            val fa = faces[a]
            for (i in 0 until fa.size) { val t = fa[i]; if (alive[t]) for (j in 0..2) { val w = tri[3 * t + j]; if (w != a) near.add(w) } }
            for (w in near) heap.add(collapse(a, w))
        }
        return build()
    }

    private fun build(): Mesh {
        val out = ArrayList<Int>()
        for (t in 0 until nTri) if (alive[t]) out.add(t)
        val verts = FloatArray(out.size * 27)
        val effects = if (e != null) FloatArray(out.size * 3 * EFFECT_FLOATS) else null
        val su = mesh.surface
        val surface = if (su != null) FloatArray(out.size * 3 * SURFACE_FLOATS) else null
        val materials = if (mats != null) IntArray(out.size) else null
        for ((n, t) in out.withIndex()) {
            for (j in 0..2) {
                val vv = tri[3 * t + j]; val src = source[vv]
                val o = 27 * n + 9 * j
                verts[o] = px[vv]; verts[o + 1] = py[vv]; verts[o + 2] = pz[vv]
                verts[o + 3] = v[9 * src + 3]; verts[o + 4] = v[9 * src + 4]; verts[o + 5] = v[9 * src + 5]
                verts[o + 6] = v[9 * src + 6]; verts[o + 7] = v[9 * src + 7]; verts[o + 8] = v[9 * src + 8]
                if (effects != null) (e!!).copyInto(effects, EFFECT_FLOATS * (3 * n + j), EFFECT_FLOATS * src, EFFECT_FLOATS * src + EFFECT_FLOATS)
                if (surface != null) su!!.copyInto(surface, SURFACE_FLOATS * (3 * n + j), SURFACE_FLOATS * src, SURFACE_FLOATS * src + SURFACE_FLOATS)
            }
            if (materials != null) materials[n] = mats!![t]
        }
        // Normals made again: soft, and crisp across the edges still sharp.
        var m = Mesh(verts).smooth()
        if (materials != null) m = m.withMaterials(materials)
        if (effects != null) m = m.withEffects(effects)
        if (surface != null) m = m.withSurface(surface)
        return m
    }
}

/** A growable list of ints (a vertex's faces). */
private class IntArrayList {
    private var data = IntArray(6); var size = 0; private set
    fun add(x: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = x }
    operator fun get(i: Int) = data[i]
}
