package sillage

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// The nodes of a scene that stand still, in the cells of a grid over the ground (x, z). Each cell keeps the box
// that holds its nodes' bounding spheres (and the longest of their draw distances), so that a frame can leave a
// whole cell out at once when the camera cannot see that box and no shadow square reaches it (RenderBatches).
// A node joins a cell once nothing that places or draws it has changed for CALM_FRAMES frames, and leaves it at
// the first change (Node.stir); every other node is visited each frame, as before. A frame visits the nodes it
// kept by their index in the scene's draw list, so that what is drawn, and in which order, is exactly what
// visiting every node gives.
//
// A cell holds its nodes' indices, not the nodes: the collector then copies the nodes in the order of the draw
// list that refers to them, and a frame that visits them in that order reads memory in order (with the nodes
// referred to from the cells too, the copies followed the cells, and 10 000 crates all in sight were drawn
// 20 % slower).

/** Frames without a change before a node joins a cell. */
private const val CALM_FRAMES = 30
/** Nodes per cell aimed at when the grid's side is chosen. */
private const val PER_CELL = 16f

/** One cell of [StillCells]: its nodes (by their index in the draw list) and the box holding their spheres. */
internal class StillCell {
    var ranks = IntArray(8)
    var count = 0
    /** Left out whole this frame (RenderBatches). */
    var out = false
    var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
    /** The longest draw distance of its nodes. */
    var far = 0f
    /** The largest coordinate of its box, for the margin of the tests. */
    var size = 0f

    fun add(o: Node, b: FloatArray) {
        if (count == ranks.size) ranks = ranks.copyOf(2 * count)
        ranks[count] = o.rank; o.cell = this; o.cellSlot = count; count++
        minX = min(minX, b[0]); minY = min(minY, b[1]); minZ = min(minZ, b[2])
        maxX = max(maxX, b[3]); maxY = max(maxY, b[4]); maxZ = max(maxZ, b[5])
        far = max(far, o.drawDistance)
        size = max(max(max(abs(minX), abs(maxX)), max(abs(minY), abs(maxY))), max(abs(minZ), abs(maxZ)))
    }

    /** Takes [o] out ([nodes]: the draw list, to find the node moved into its slot). */
    fun remove(o: Node, nodes: List<Node>) {
        val s = o.cellSlot
        val last = ranks[--count]
        ranks[s] = last; nodes[last].cellSlot = s
        o.cell = null; o.cellSlot = -1
        if (count == 0) {       // its box starts afresh (while it holds nodes, it only grows: still holding the ones left)
            minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY; minZ = Float.POSITIVE_INFINITY
            maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY; maxZ = Float.NEGATIVE_INFINITY
            far = 0f; size = 0f
        }
    }
}

/**
 * The still nodes of a scene in cells, and the nodes visited every frame ([moving]), for the scene's draw list
 * [nodes]. Every node of the list knows its index in it ([Node.rank]) once the cells have seen it.
 */
internal class StillCells(private val nodes: List<Node>) {
    /** Nodes not in a cell (moving, just made, or never still: the sky, players, animated models…), in the list's order. */
    val moving = ArrayList<Node>()
    val cells = ArrayList<StillCell>()
    private val byKey = HashMap<Long, StillCell>()
    /** Side of a cell in metres (0 until the first node joins). */
    var side = 0f
        private set
    /** Nodes in cells, and how many there were when [side] was chosen. */
    var still = 0
        private set
    private var sideFor = 0
    /** How many nodes of the list the cells know (the others were added since). */
    private var known = 0
    private val joining = ArrayList<Node>()
    private val box = FloatArray(6)
    private val sphere = FloatArray(4)

    /** [o] changed: it leaves its cell and is visited every frame again (in the list's order). */
    fun wake(o: Node) {
        o.cell!!.remove(o, nodes); still--
        o.calm = 0
        backToMoving(o)
    }

    /** [o] among the nodes visited every frame, at its place in the list's order. */
    private fun backToMoving(o: Node) {
        var lo = 0; var hi = moving.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (moving[mid].rank < o.rank) lo = mid + 1 else hi = mid }
        moving.add(lo, o)
    }

    /** [o] is about to leave the draw list (the indices still hold). */
    fun forget(o: Node) {
        val c = o.cell
        if (c != null) { c.remove(o, nodes); still-- } else moving.remove(o)
    }

    /** The node that was at [r] left the draw list: the indices after it move down by one. */
    fun removedAt(r: Int) {
        if (r >= known) return
        known--
        for (i in r until nodes.size) nodes[i].rank = i
        for (k in 0 until cells.size) { val c = cells[k]; val rs = c.ranks; for (j in 0 until c.count) if (rs[j] > r) rs[j]-- }
    }

    /** Brings the cells up to date for this frame: new nodes are visited every frame, the ones calm for long enough join their cells. */
    fun sync() {
        if (nodes.size > known) {
            for (i in known until nodes.size) { val o = nodes[i]; o.rank = i; o.calm = 0; moving.add(o) }
            known = nodes.size
        }
        var w = 0
        for (k in 0 until moving.size) {
            val o = moving[k]
            if (o.stirred) { o.stirred = false; o.calm = 0; moving[w++] = o; continue }
            if (++o.calm < CALM_FRAMES) { moving[w++] = o; continue }
            o.calm = 0                                      // tried now; if it cannot join, tried again later
            if (canJoin(o)) joining.add(o) else moving[w++] = o
        }
        while (moving.size > w) moving.removeAt(moving.size - 1)
        if (joining.isNotEmpty()) {
            if (side == 0f || still + joining.size > 4 * sideFor) regrid()
            for (k in 0 until joining.size) { val o = joining[k]; if (!join(o)) backToMoving(o) }
            joining.clear()
        }
    }

    /** What may stand in a cell: a visible node drawing meshes that do not change, not a player, the sky, a blob shadow or an animated model. */
    private fun canJoin(o: Node): Boolean {
        if (!o.visible || o.isSky || o.isShadow || o is Player || o.parts.isNotEmpty() || o.animator != null || o.removed) return false
        if (!fixed(o.mesh)) return false
        val lods = o.lodMeshes
        for (k in lods.indices) if (!fixed(lods[k].second)) return false
        return true
    }

    private fun fixed(m: Mesh) = m.vertices.isNotEmpty() && !m.dynamic

    /** The world sphere of [mesh] placed by [o] into [sphere], as RenderBatches computes it. */
    private fun sphereOf(o: Node, mesh: Mesh) {
        val sp = mesh.sphere
        val q = o.rotation; val qx = q.x; val qy = q.y; val qz = q.z; val qw = q.w
        val cx = sp[0]; val cy = sp[1]; val cz = sp[2]
        val tx = 2f * (qy * cz - qz * cy); val ty = 2f * (qz * cx - qx * cz); val tz = 2f * (qx * cy - qy * cx)
        val k = o.scale; val p = o.position
        sphere[0] = (cx + qw * tx + (qy * tz - qz * ty)) * k + p.x; sphere[1] = (cy + qw * ty + (qz * tx - qx * tz)) * k + p.y
        sphere[2] = (cz + qw * tz + (qx * ty - qy * tx)) * k + p.z
        sphere[3] = sp[3] * abs(k)
    }

    /** The box holding the spheres of all of [o]'s meshes (its levels of detail too) into [box]; false if it is not finite. */
    private fun boxOf(o: Node): Boolean {
        sphereOf(o, o.mesh)
        box[0] = sphere[0] - sphere[3]; box[1] = sphere[1] - sphere[3]; box[2] = sphere[2] - sphere[3]
        box[3] = sphere[0] + sphere[3]; box[4] = sphere[1] + sphere[3]; box[5] = sphere[2] + sphere[3]
        val lods = o.lodMeshes
        for (k in lods.indices) {
            sphereOf(o, lods[k].second)
            box[0] = min(box[0], sphere[0] - sphere[3]); box[1] = min(box[1], sphere[1] - sphere[3]); box[2] = min(box[2], sphere[2] - sphere[3])
            box[3] = max(box[3], sphere[0] + sphere[3]); box[4] = max(box[4], sphere[1] + sphere[3]); box[5] = max(box[5], sphere[2] + sphere[3])
        }
        for (v in box) if (!v.isFinite()) return false
        return true
    }

    /** Puts [o] in the cell under the centre of its box; false if it is too large for a cell (or not finite). */
    private fun join(o: Node): Boolean {
        if (!boxOf(o)) return false
        if (max(box[3] - box[0], box[5] - box[2]) > 4f * side) return false      // the ground, a sea: visited every frame
        val ix = floor((box[0] + box[3]) * .5f / side).toInt(); val iz = floor((box[2] + box[5]) * .5f / side).toInt()
        val key = (ix.toLong() shl 32) or (iz.toLong() and 0xffffffffL)
        val c = byKey.getOrPut(key) { StillCell().also { cells.add(it) } }
        c.add(o, box); still++
        return true
    }

    /** Chooses the side of the cells for the still nodes and those [joining] (about [PER_CELL] nodes a cell), and lays the cells again. */
    private fun regrid() {
        for (k in 0 until cells.size) { val c = cells[k]; while (c.count > 0) { val o = nodes[c.ranks[c.count - 1]]; c.remove(o, nodes); joining.add(o) } }
        cells.clear(); byKey.clear(); still = 0
        var lx = Float.POSITIVE_INFINITY; var lz = Float.POSITIVE_INFINITY; var hx = Float.NEGATIVE_INFINITY; var hz = Float.NEGATIVE_INFINITY
        var n = 0
        for (k in 0 until joining.size) {
            if (!boxOf(joining[k])) continue
            val x = (box[0] + box[3]) * .5f; val z = (box[2] + box[5]) * .5f
            lx = min(lx, x); hx = max(hx, x); lz = min(lz, z); hz = max(hz, z); n++
        }
        val area = if (n > 0) max(hx - lx, 1f) * max(hz - lz, 1f) else 1f
        side = sqrt(area * PER_CELL / max(n, 1)).coerceIn(2f, 100_000f)
        sideFor = max(n, 64)
    }
}
