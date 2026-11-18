package sillage.physics

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
}

/**
 * Static triangle soup (terrain, buildings) with a bounding volume hierarchy.
 * Triangles are one-sided: the front is the side of the counter-clockwise normal (b − a) × (c − a);
 * bodies are pushed out towards the front. Degenerate triangles are dropped.
 */
class TriangleMesh(triangles: FloatArray) {
    init { require(triangles.size % 9 == 0) { "A triangle mesh needs 9 floats per triangle" } }

    /** a, b, c of each kept triangle. */
    internal val v: FloatArray
    /** Unit front normal of each triangle. */
    internal val n: FloatArray
    val count: Int

    private val bounds: FloatArray      // per node: minX, minY, minZ, maxX, maxY, maxZ
    private val left: IntArray          // child index, or -1 for a leaf
    private val right: IntArray
    private val first: IntArray         // leaf: first entry in [order]
    private val span: IntArray          // leaf: number of triangles
    private val order: IntArray
    private var nodes = 0

    init {
        val keptV = ArrayList<Float>(); val keptN = ArrayList<Float>()
        for (t in 0 until triangles.size / 9) {
            val o = t * 9
            val ux = triangles[o + 3] - triangles[o]; val uy = triangles[o + 4] - triangles[o + 1]; val uz = triangles[o + 5] - triangles[o + 2]
            val wx = triangles[o + 6] - triangles[o]; val wy = triangles[o + 7] - triangles[o + 1]; val wz = triangles[o + 8] - triangles[o + 2]
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-9f) continue
            for (k in 0 until 9) keptV.add(triangles[o + k])
            keptN.add(nx / l); keptN.add(ny / l); keptN.add(nz / l)
        }
        v = keptV.toFloatArray(); n = keptN.toFloatArray(); count = n.size / 3
        val maxNodes = maxOf(1, 2 * count)
        bounds = FloatArray(6 * maxNodes); left = IntArray(maxNodes); right = IntArray(maxNodes); first = IntArray(maxNodes); span = IntArray(maxNodes)
        order = IntArray(count) { it }
        if (count > 0) build(0, count)
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
}
