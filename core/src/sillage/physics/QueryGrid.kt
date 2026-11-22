package sillage.physics

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The bodies of a world in a uniform grid, for the queries (rays, sphere sweeps, contacts of a shape):
 * each body is entered in every cell that its bounding sphere, grown by [PAD], overlaps. It is built
 * when a query needs it after bodies moved (once per step at most, in a game); bodies spanning many
 * cells (walls, floors) are kept apart and always tested. Small worlds are searched body by body.
 */
internal class QueryGrid {
    var valid = false
    var size = 1f; private set
    private var keys = LongArray(0)              // sorted: cell (3 × 13 bits) then body (24 bits)
    private var entries = 0
    private val first = LongIntMap()             // cell → its first entry in [keys]
    val large = IntList()
    private var stamp = IntArray(0)
    private var mark = 0
    // Bounds of everything entered: rays are clipped to them before walking the cells.
    var minX = 0f; var minY = 0f; var minZ = 0f; var maxX = 0f; var maxY = 0f; var maxZ = 0f

    fun cell(v: Float) = floor(v / size).toInt().coerceIn(-4095, 4095) + 4096
    private fun key(x: Int, y: Int, z: Int) = (x.toLong() shl 26) or (y.toLong() shl 13) or z.toLong()

    fun build(w: PhysicsWorld) {
        val n = w.count
        var sum = 0f; var live = 0
        for (i in 0 until n) if (!w.removed[i]) { sum += w.shapes[i]!!.boundingRadius; live++ }
        size = if (live == 0) 2f else (2f * (sum / live + PAD)).coerceIn(1f, 16f)
        if (stamp.size < n) stamp = IntArray(max(n, 2 * stamp.size))
        large.clear(); entries = 0
        minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; minZ = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE; maxZ = -Float.MAX_VALUE
        for (i in 0 until n) {
            if (w.removed[i]) continue
            val r = w.shapes[i]!!.boundingRadius + PAD
            val x = w.px[i]; val y = w.py[i]; val z = w.pz[i]
            minX = min(minX, x - r); minY = min(minY, y - r); minZ = min(minZ, z - r)
            maxX = max(maxX, x + r); maxY = max(maxY, y + r); maxZ = max(maxZ, z + r)
            val x0 = cell(x - r); val y0 = cell(y - r); val z0 = cell(z - r)
            val x1 = cell(x + r); val y1 = cell(y + r); val z1 = cell(z + r)
            val span = (x1 - x0 + 1).toLong() * (y1 - y0 + 1) * (z1 - z0 + 1)
            if (span > 27) { large.add(i); continue }
            if (keys.size < entries + span) keys = keys.copyOf(max(2 * keys.size, entries + span.toInt() + 256))
            for (cx in x0..x1) for (cy in y0..y1) for (cz in z0..z1) keys[entries++] = (key(cx, cy, cz) shl 24) or i.toLong()
        }
        keys.sort(0, entries)
        first.clear()
        var k = 0
        while (k < entries) { val c = keys[k] ushr 24; first.putIfAbsent(c, k); k++; while (k < entries && keys[k] ushr 24 == c) k++ }
        valid = true
    }

    /** Starts a new query: every body will be reported at most once. */
    fun begin() {
        if (++mark == Int.MAX_VALUE) { stamp.fill(0); mark = 1 }
    }

    /** Adds to [out] the bodies of cell (x, y, z) (biased coordinates, as [cell] gives) not reported yet. */
    fun collect(x: Int, y: Int, z: Int, out: IntList) {
        if (x < 1 || y < 1 || z < 1 || x > 8191 || y > 8191 || z > 8191) return
        val c = key(x, y, z)
        var k = first.get(c)
        if (k < 0) return
        while (k < entries && keys[k] ushr 24 == c) {
            val i = (keys[k] and 0xffffff).toInt()
            if (stamp[i] != mark) { stamp[i] = mark; out.add(i) }
            k++
        }
    }

    /** Adds to [out] the bodies that may overlap the box, then the large ones; sorted, as a body-by-body search would give them. */
    fun bodiesIn(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, out: IntList) {
        begin(); out.clear()
        if (x1 >= minX && x0 <= maxX && y1 >= minY && y0 <= maxY && z1 >= minZ && z0 <= maxZ) {
            val cx0 = cell(max(x0, minX)); val cy0 = cell(max(y0, minY)); val cz0 = cell(max(z0, minZ))
            val cx1 = cell(min(x1, maxX)); val cy1 = cell(min(y1, maxY)); val cz1 = cell(min(z1, maxZ))
            for (x in cx0..cx1) for (y in cy0..cy1) for (z in cz0..cz1) collect(x, y, z, out)
        }
        for (k in 0 until large.size) out.add(large[k])
        out.sort()
    }

    companion object {
        /** A sphere sweep wider than this goes body by body (the cells only cover bounding spheres grown by it). */
        const val PAD = .5f
        /** Below this many bodies, queries do not use a grid. */
        const val MIN_BODIES = 64
    }
}
