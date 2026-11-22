package sillage.physics

import sillage.math.Vec3

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Growable int list that keeps its storage between uses (no allocation once warmed up). */
class IntList(capacity: Int = 16) {
    var data = IntArray(capacity); private set
    var size = 0; private set
    fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    operator fun get(i: Int) = data[i]
    fun pop(): Int = data[--size]
    fun clear() { size = 0 }
    fun sort() { data.sort(0, size) }
}

/**
 * Static triangle soup (terrain, buildings) with a bounding volume hierarchy.
 * Triangles are one-sided: the front is the side of the counter-clockwise normal (b − a) × (c − a);
 * bodies are pushed out towards the front. Degenerate triangles are dropped.
 */
class TriangleMesh(triangles: FloatArray, materialIds: IntArray? = null, palette: List<Material> = listOf(Material.DEFAULT)) {
    init {
        require(triangles.size % 9 == 0) { "A triangle mesh needs 9 floats per triangle" }
        require(materialIds == null || materialIds.size == triangles.size / 9) { "One material index per triangle" }
        require(palette.isNotEmpty()) { "A mesh needs at least one material" }
    }

    /** One material for every triangle. */
    constructor(triangles: FloatArray, material: Material) : this(triangles, null, listOf(material))

    internal val palette: List<Material> = palette.toList()
    /** Material of each kept triangle, as an index in [palette]. */
    internal val material: IntArray

    /** a, b, c of each kept triangle. */
    val v: FloatArray
    /** Unit front normal of each triangle. */
    internal val n: FloatArray
    val count: Int
    /**
     * Per triangle, 3 bits: edge a→b, b→c, c→a is "active" — a free border or a convex fold of more
     * than 5°. Inactive edges (flat or concave joins between neighbours) give no collision axis or
     * normal of their own, so bodies slide across a triangulated floor without catching on seams.
     */
    internal val edges: IntArray

    private val bounds: FloatArray      // per node: minX, minY, minZ, maxX, maxY, maxZ
    private val left: IntArray          // child index, or -1 for a leaf
    private val right: IntArray
    private val first: IntArray         // leaf: first entry in [order]
    private val span: IntArray          // leaf: number of triangles
    private val order: IntArray
    private var nodes = 0

    init {
        val keptV = ArrayList<Float>(); val keptN = ArrayList<Float>(); val keptM = ArrayList<Int>()
        for (t in 0 until triangles.size / 9) {
            val o = t * 9
            val ux = triangles[o + 3] - triangles[o]; val uy = triangles[o + 4] - triangles[o + 1]; val uz = triangles[o + 5] - triangles[o + 2]
            val wx = triangles[o + 6] - triangles[o]; val wy = triangles[o + 7] - triangles[o + 1]; val wz = triangles[o + 8] - triangles[o + 2]
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-9f) continue
            for (k in 0 until 9) keptV.add(triangles[o + k])
            keptN.add(nx / l); keptN.add(ny / l); keptN.add(nz / l)
            keptM.add((materialIds?.get(t) ?: 0).let { if (it in palette.indices) it else 0 })
        }
        material = keptM.toIntArray()
        v = keptV.toFloatArray(); n = keptN.toFloatArray(); count = n.size / 3
        edges = activeEdges()
        val maxNodes = maxOf(1, 2 * count)
        bounds = FloatArray(6 * maxNodes); left = IntArray(maxNodes); right = IntArray(maxNodes); first = IntArray(maxNodes); span = IntArray(maxNodes)
        order = IntArray(count) { it }
        if (count > 0) build(0, count)
    }

    private fun activeEdges(): IntArray {
        fun key(t: Int, k: Int): Long {
            // Vertex identity by its exact coordinates, so triangle soups (no index buffer) still connect.
            val o = 9 * t + 3 * k
            return (v[o].toRawBits().toLong() * 73856093L) xor (v[o + 1].toRawBits().toLong() * 19349663L) xor (v[o + 2].toRawBits().toLong() * 83492791L)
        }
        val owners = HashMap<Pair<Long, Long>, MutableList<Int>>()
        for (t in 0 until count) for (j in 0..2) {
            val a = key(t, j); val b = key(t, (j + 1) % 3)
            owners.getOrPut(if (a < b) a to b else b to a) { ArrayList(2) }.add(t)
        }
        val cosLimit = kotlin.math.cos(sillage.math.toRadians(5.0)).toFloat()
        return IntArray(count) { t ->
            var bits = 0
            for (j in 0..2) {
                val a = key(t, j); val b = key(t, (j + 1) % 3)
                val others = owners.getValue(if (a < b) a to b else b to a).filter { it != t }
                val active = if (others.size != 1) true else {
                    val u = others[0]
                    val cos = n[3 * t] * n[3 * u] + n[3 * t + 1] * n[3 * u + 1] + n[3 * t + 2] * n[3 * u + 2]
                    // The neighbour's far vertex below this triangle's plane means a convex fold.
                    val far = (0..2).first { k -> key(u, k) != a && key(u, k) != b }
                    val o = 9 * u + 3 * far
                    val h = (v[o] - v[9 * t]) * n[3 * t] + (v[o + 1] - v[9 * t + 1]) * n[3 * t + 1] + (v[o + 2] - v[9 * t + 2]) * n[3 * t + 2]
                    h < -1e-6f && cos < cosLimit
                }
                if (active) bits = bits or (1 shl j)
            }
            bits
        }
    }

    private fun centroid(t: Int, axis: Int) = (v[9 * t + axis] + v[9 * t + 3 + axis] + v[9 * t + 6 + axis]) / 3f

    private fun build(start: Int, end: Int): Int {
        val node = nodes++
        val b = 6 * node
        bounds[b] = Float.MAX_VALUE; bounds[b + 1] = Float.MAX_VALUE; bounds[b + 2] = Float.MAX_VALUE
        bounds[b + 3] = -Float.MAX_VALUE; bounds[b + 4] = -Float.MAX_VALUE; bounds[b + 5] = -Float.MAX_VALUE
        for (i in start until end) {
            val t = order[i]
            for (k in 0..2) for (a in 0..2) {
                val x = v[9 * t + 3 * k + a]
                bounds[b + a] = min(bounds[b + a], x); bounds[b + 3 + a] = max(bounds[b + 3 + a], x)
            }
        }
        if (end - start <= 4) { left[node] = -1; first[node] = start; span[node] = end - start; return node }
        val axis = (0..2).maxBy { bounds[b + 3 + it] - bounds[b + it] }
        // Median split on the centroids along the longest axis.
        val slice = order.copyOfRange(start, end).sortedBy { centroid(it, axis) }
        for (i in slice.indices) order[start + i] = slice[i]
        val mid = (start + end) / 2
        left[node] = build(start, mid)
        right[node] = build(mid, end)
        return node
    }

    /** Adds to [out] every triangle whose bounds overlap the given box. */
    fun query(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float, out: IntList, stack: IntList) {
        if (count == 0) return
        stack.clear(); stack.add(0)
        while (stack.size > 0) {
            val node = stack.pop()
            val b = 6 * node
            if (bounds[b] > maxX || bounds[b + 3] < minX || bounds[b + 1] > maxY || bounds[b + 4] < minY ||
                bounds[b + 2] > maxZ || bounds[b + 5] < minZ) continue
            if (left[node] < 0) { for (i in first[node] until first[node] + span[node]) out.add(order[i]); continue }
            stack.add(left[node]); stack.add(right[node])
        }
    }

    /**
     * Closest front-facing triangle hit by the ray o + t·d, t ∈ [0, maxT] (d unit). Returns t or −1,
     * and the triangle index in [hitTriangle]. Möller–Trumbore, BVH walk with slab tests.
     */
    internal fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxT: Float, stack: IntList): Float {
        hitTriangle = -1
        if (count == 0) return -1f
        var best = maxT
        val ix = 1f / dx; val iy = 1f / dy; val iz = 1f / dz
        stack.clear(); stack.add(0)
        while (stack.size > 0) {
            val node = stack.pop(); val b = 6 * node
            var t0 = 0f; var t1 = best
            var ta = (bounds[b] - ox) * ix; var tb = (bounds[b + 3] - ox) * ix
            t0 = maxOf(t0, minOf(ta, tb)); t1 = minOf(t1, maxOf(ta, tb))
            ta = (bounds[b + 1] - oy) * iy; tb = (bounds[b + 4] - oy) * iy
            t0 = maxOf(t0, minOf(ta, tb)); t1 = minOf(t1, maxOf(ta, tb))
            ta = (bounds[b + 2] - oz) * iz; tb = (bounds[b + 5] - oz) * iz
            t0 = maxOf(t0, minOf(ta, tb)); t1 = minOf(t1, maxOf(ta, tb))
            if (t0 > t1) continue
            if (left[node] >= 0) { stack.add(left[node]); stack.add(right[node]); continue }
            for (i in first[node] until first[node] + span[node]) {
                val t = order[i]; val o = 9 * t
                if (dx * n[3 * t] + dy * n[3 * t + 1] + dz * n[3 * t + 2] >= 0f) continue      // back face
                val e1x = v[o + 3] - v[o]; val e1y = v[o + 4] - v[o + 1]; val e1z = v[o + 5] - v[o + 2]
                val e2x = v[o + 6] - v[o]; val e2y = v[o + 7] - v[o + 1]; val e2z = v[o + 8] - v[o + 2]
                val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
                val det = e1x * px + e1y * py + e1z * pz
                if (kotlin.math.abs(det) < 1e-12f) continue
                val inv = 1f / det
                val sx = ox - v[o]; val sy = oy - v[o + 1]; val sz = oz - v[o + 2]
                val u = (sx * px + sy * py + sz * pz) * inv
                if (u < 0f || u > 1f) continue
                val qx = sy * e1z - sz * e1y; val qy = sz * e1x - sx * e1z; val qz = sx * e1y - sy * e1x
                val w = (dx * qx + dy * qy + dz * qz) * inv
                if (w < 0f || u + w > 1f) continue
                val tt = (e2x * qx + e2y * qy + e2z * qz) * inv
                if (tt in 0f..best) { best = tt; hitTriangle = t }
            }
        }
        return if (hitTriangle >= 0) best else -1f
    }

    internal var hitTriangle = -1
        private set
}
