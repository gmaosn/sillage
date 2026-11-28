package sillage.physics

import kotlin.math.max
import kotlin.math.min

/**
 * A dynamic bounding volume hierarchy of boxes (after Box2D's dynamic tree, E. Catto): each leaf holds an
 * item (a body) and a box a little larger than it ("fat", by [MARGIN]), so that an item moving within its
 * fat box needs no update. Leaves are inserted where they enlarge the tree's surface least, and the tree is
 * kept balanced by rotations. Queries walk down from the root, skipping every branch whose box misses.
 *
 * Plain arrays, no allocation once grown. Its shape depends only on the order of the insertions and
 * removals, so it is the same from one run to the next; the queries' answers do not depend on it at all.
 */
internal class BoxTree {
    /** Per node: minX, minY, minZ, maxX, maxY, maxZ. */
    var box = FloatArray(0); private set
    /** Per node: parent (or the next free node), the two children (−1 for a leaf), height (leaf 0), item (leaf only). */
    var parent = IntArray(0); private set
    var child1 = IntArray(0); private set
    var child2 = IntArray(0); private set
    private var height = IntArray(0)
    var item = IntArray(0); private set
    var root = -1; private set
    private var capacity = 0
    private var freeList = -1
    var leaves = 0; private set

    private fun allocate(): Int {
        if (freeList < 0) {
            val c = max(16, capacity * 2)
            box = box.copyOf(6 * c); parent = parent.copyOf(c); child1 = child1.copyOf(c); child2 = child2.copyOf(c)
            height = height.copyOf(c); item = item.copyOf(c)
            for (k in capacity until c) parent[k] = if (k + 1 < c) k + 1 else -1
            freeList = capacity; capacity = c
        }
        val n = freeList
        freeList = parent[n]
        parent[n] = -1; child1[n] = -1; child2[n] = -1; height[n] = 0; item[n] = -1
        return n
    }

    private fun release(n: Int) { parent[n] = freeList; height[n] = -1; freeList = n }

    /** Inserts [id] with the box (x0, y0, z0)–(x1, y1, z1) grown by [margin]; returns its leaf. */
    fun insert(id: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, margin: Float): Int {
        val leaf = allocate()
        val b = 6 * leaf
        box[b] = x0 - margin; box[b + 1] = y0 - margin; box[b + 2] = z0 - margin
        box[b + 3] = x1 + margin; box[b + 4] = y1 + margin; box[b + 5] = z1 + margin
        item[leaf] = id
        insertLeaf(leaf)
        leaves++
        return leaf
    }

    fun remove(leaf: Int) { removeLeaf(leaf); release(leaf); leaves-- }

    /** Whether leaf's fat box still holds the box (x0, y0, z0)–(x1, y1, z1). */
    fun holds(leaf: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Boolean {
        val b = 6 * leaf
        return box[b] <= x0 && box[b + 1] <= y0 && box[b + 2] <= z0 && box[b + 3] >= x1 && box[b + 4] >= y1 && box[b + 5] >= z1
    }

    /** Moves leaf to a new fat box (removed and inserted again); its node stays the same. */
    fun move(leaf: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, margin: Float) {
        removeLeaf(leaf)
        val b = 6 * leaf
        box[b] = x0 - margin; box[b + 1] = y0 - margin; box[b + 2] = z0 - margin
        box[b + 3] = x1 + margin; box[b + 4] = y1 + margin; box[b + 5] = z1 + margin
        insertLeaf(leaf)
    }

    fun clear() {
        // Every node back to the free list.
        for (k in 0 until capacity) { parent[k] = if (k + 1 < capacity) k + 1 else -1; height[k] = -1 }
        freeList = if (capacity > 0) 0 else -1
        root = -1; leaves = 0
    }

    private fun area(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Float {
        val wx = x1 - x0; val wy = y1 - y0; val wz = z1 - z0
        return 2f * (wx * wy + wy * wz + wz * wx)
    }
    private fun area(n: Int): Float { val b = 6 * n; return area(box[b], box[b + 1], box[b + 2], box[b + 3], box[b + 4], box[b + 5]) }
    /** Surface of the box holding nodes a and l. */
    private fun unionArea(a: Int, l: Int): Float {
        val p = 6 * a; val q = 6 * l
        return area(min(box[p], box[q]), min(box[p + 1], box[q + 1]), min(box[p + 2], box[q + 2]),
            max(box[p + 3], box[q + 3]), max(box[p + 4], box[q + 4]), max(box[p + 5], box[q + 5]))
    }
    private fun fit(n: Int) {
        val a = 6 * child1[n]; val c = 6 * child2[n]; val b = 6 * n
        box[b] = min(box[a], box[c]); box[b + 1] = min(box[a + 1], box[c + 1]); box[b + 2] = min(box[a + 2], box[c + 2])
        box[b + 3] = max(box[a + 3], box[c + 3]); box[b + 4] = max(box[a + 4], box[c + 4]); box[b + 5] = max(box[a + 5], box[c + 5])
        height[n] = 1 + max(height[child1[n]], height[child2[n]])
    }

    private fun insertLeaf(leaf: Int) {
        if (root < 0) { root = leaf; parent[leaf] = -1; return }
        // The best sibling: down the branch whose growth costs least (the surface area heuristic of Box2D v2.4).
        var index = root
        while (child1[index] >= 0) {
            val a = area(index)
            val combined = unionArea(index, leaf)
            val cost = 2f * combined
            val inheritance = 2f * (combined - a)
            val c1 = child1[index]; val c2 = child2[index]
            val cost1 = unionArea(c1, leaf) - (if (child1[c1] < 0) 0f else area(c1)) + inheritance
            val cost2 = unionArea(c2, leaf) - (if (child1[c2] < 0) 0f else area(c2)) + inheritance
            if (cost < cost1 && cost < cost2) break
            index = if (cost1 < cost2) c1 else c2
        }
        val sibling = index
        val oldParent = parent[sibling]
        val node = allocate()
        parent[node] = oldParent
        child1[node] = sibling; child2[node] = leaf
        parent[sibling] = node; parent[leaf] = node
        fit(node)
        if (oldParent < 0) root = node
        else if (child1[oldParent] == sibling) child1[oldParent] = node else child2[oldParent] = node
        // Back up: refit and rebalance.
        var n = parent[leaf]
        while (n >= 0) { n = balance(n); fit(n); n = parent[n] }
    }

    private fun removeLeaf(leaf: Int) {
        if (leaf == root) { root = -1; return }
        val p = parent[leaf]
        val grand = parent[p]
        val sibling = if (child1[p] == leaf) child2[p] else child1[p]
        if (grand >= 0) {
            if (child1[grand] == p) child1[grand] = sibling else child2[grand] = sibling
            parent[sibling] = grand
            release(p)
            var n = grand
            while (n >= 0) { n = balance(n); fit(n); n = parent[n] }
        } else {
            root = sibling; parent[sibling] = -1; release(p)
        }
        parent[leaf] = -1
    }

    /** A left or right rotation at [a] if its children's heights differ by more than one; returns the new root of that subtree. */
    private fun balance(a: Int): Int {
        if (child1[a] < 0 || height[a] < 2) return a
        val b = child1[a]; val c = child2[a]
        val diff = height[c] - height[b]
        if (diff > 1) return rotate(a, c, b)
        if (diff < -1) return rotate(a, b, c)
        return a
    }

    /** Lifts [up] (the taller child of [a]) above [a]; [other] is a's other child. */
    private fun rotate(a: Int, up: Int, other: Int): Int {
        val f = child1[up]; val g = child2[up]
        // up takes a's place.
        child1[up] = a
        parent[up] = parent[a]
        parent[a] = up
        val pu = parent[up]
        if (pu >= 0) { if (child1[pu] == a) child1[pu] = up else child2[pu] = up } else root = up
        // The taller of up's children stays under up; the other goes under a.
        val keep: Int; val give: Int
        if (height[f] > height[g]) { keep = f; give = g } else { keep = g; give = f }
        child2[up] = keep
        if (child1[a] == up) child1[a] = give else child2[a] = give
        parent[give] = a
        if (child1[a] != other && child2[a] != other) error("box tree rotation")
        fit(a); fit(up)
        return up
    }

    /** Adds to [out] the items whose fat boxes overlap (x0, y0, z0)–(x1, y1, z1). */
    fun query(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, out: IntList, stack: IntList) {
        if (root < 0) return
        stack.clear(); stack.add(root)
        while (stack.size > 0) {
            val n = stack.pop(); val b = 6 * n
            if (box[b] > x1 || box[b + 3] < x0 || box[b + 1] > y1 || box[b + 4] < y0 || box[b + 2] > z1 || box[b + 5] < z0) continue
            if (child1[n] < 0) out.add(item[n]) else { stack.add(child1[n]); stack.add(child2[n]) }
        }
    }

    /**
     * Builds the tree again from its leaves, top down: each branch split at the median of its leaves' centres
     * along the longest side of their spread, the nodes laid out branch by branch. After many insertions (a
     * world of scenery built body by body) a query then walks fewer boxes, close together in memory. The leaves
     * get new nodes: [moved] is told each item's.
     */
    fun rebuild(moved: (item: Int, leaf: Int) -> Unit) {
        if (leaves < 2) return
        val m = leaves
        val ids = IntArray(m); val boxes = FloatArray(6 * m); val centres = FloatArray(3 * m)
        var k = 0
        val stack = IntList(64); stack.add(root)
        while (stack.size > 0) {
            val n = stack.pop()
            if (child1[n] >= 0) { stack.add(child2[n]); stack.add(child1[n]); continue }
            ids[k] = item[n]
            for (t in 0..5) boxes[6 * k + t] = box[6 * n + t]
            for (t in 0..2) centres[3 * k + t] = .5f * (box[6 * n + t] + box[6 * n + 3 + t])
            k++
        }
        val order = IntArray(m) { it }
        next = 0
        root = build(order, 0, m, -1, ids, boxes, centres)
        // The other nodes back to the free list.
        for (n in next until capacity) { parent[n] = if (n + 1 < capacity) n + 1 else -1; height[n] = -1 }
        freeList = if (next < capacity) next else -1
        for (n in 0 until next) if (child1[n] < 0) moved(item[n], n)
    }
    private var next = 0

    private fun build(order: IntArray, lo: Int, hi: Int, up: Int, ids: IntArray, boxes: FloatArray, centres: FloatArray): Int {
        val n = next++
        parent[n] = up
        if (hi - lo == 1) {
            val l = order[lo]
            for (t in 0..5) box[6 * n + t] = boxes[6 * l + t]
            item[n] = ids[l]; child1[n] = -1; child2[n] = -1; height[n] = 0
            return n
        }
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (q in lo until hi) {
            val c = 3 * order[q]
            x0 = min(x0, centres[c]); y0 = min(y0, centres[c + 1]); z0 = min(z0, centres[c + 2])
            x1 = max(x1, centres[c]); y1 = max(y1, centres[c + 1]); z1 = max(z1, centres[c + 2])
        }
        val axis = if (x1 - x0 >= y1 - y0 && x1 - x0 >= z1 - z0) 0 else if (y1 - y0 >= z1 - z0) 1 else 2
        val mid = (lo + hi) ushr 1
        select(order, lo, hi - 1, mid, centres, axis)
        item[n] = -1
        child1[n] = build(order, lo, mid, n, ids, boxes, centres)
        child2[n] = build(order, mid, hi, n, ids, boxes, centres)
        fit(n)
        return n
    }

    /** Puts in order[k] the leaf whose centre is k-th along [axis] among order[lo..hi], the smaller ones before it. */
    private fun select(order: IntArray, lo0: Int, hi0: Int, k: Int, centres: FloatArray, axis: Int) {
        var lo = lo0; var hi = hi0
        while (hi > lo) {
            val pivot = centres[3 * order[(lo + hi) ushr 1] + axis]
            var i = lo; var j = hi
            while (i <= j) {
                while (centres[3 * order[i] + axis] < pivot) i++
                while (centres[3 * order[j] + axis] > pivot) j--
                if (i <= j) { val t = order[i]; order[i] = order[j]; order[j] = t; i++; j-- }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return
        }
    }

    /** Height of the tree (diagnostics, tests). */
    val depth: Int get() = if (root < 0) 0 else height[root]

    /** Checks parents, heights and boxes everywhere (tests). */
    fun validate() {
        if (root < 0) { check(leaves == 0); return }
        check(parent[root] == -1)
        var count = 0
        fun visit(n: Int): Int {
            if (child1[n] < 0) { count++; check(height[n] == 0); return 0 }
            val a = child1[n]; val c = child2[n]
            check(parent[a] == n && parent[c] == n)
            val h = 1 + max(visit(a), visit(c))
            check(h == height[n]) { "height" }
            val b = 6 * n
            for (k in 0..2) {
                check(box[b + k] == min(box[6 * a + k], box[6 * c + k]))
                check(box[b + 3 + k] == max(box[6 * a + 3 + k], box[6 * c + 3 + k]))
            }
            return h
        }
        visit(root)
        check(count == leaves) { "$count leaves for $leaves" }
    }

    companion object {
        /** How much larger than its item a leaf's box is (m): a body moving within it is not moved in the tree. */
        const val MARGIN = .1f
    }
}
