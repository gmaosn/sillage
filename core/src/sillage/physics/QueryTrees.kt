package sillage.physics

/**
 * The bodies of a world in two dynamic box trees, for the queries (rays, sphere sweeps of any radius, the
 * contacts of a shape, continuous collision): one for what the game moves or nothing moves (static and
 * kinematic bodies), one for the dynamic bodies. Each body's leaf is a box around its shape, a little
 * larger for what moves ([BoxTree.MARGIN]): brought up to date when a query needs it, only for the bodies
 * that moved out of their box since, so that a large world of still scenery costs nothing to keep. Small
 * worlds are searched body by body. The answers are those of a body-by-body search, ties included.
 */
internal class QueryTrees {
    var valid = false
    /** Static and kinematic bodies. */
    val still = BoxTree()
    /** Dynamic bodies. */
    val moving = BoxTree()
    private var leaf = IntArray(0)              // per body: its leaf (−1: none)
    private var inMoving = BooleanArray(0)      // per body: in which tree
    private var dirty = BooleanArray(0)         // per body: moved by hand, added or removed since the last refresh
    private var last = FloatArray(0)            // per body: position and rotation when its leaf was last checked
    private val bounds = FloatArray(6)
    private val pose = Pose()
    /** Refreshes since the trees were made and leaves moved (diagnostics, tests). */
    var refreshes = 0L; private set
    var moves = 0L; private set

    /** Body [i] was placed by hand, added or removed: its leaf is checked at the next refresh. */
    fun touched(i: Int) {
        if (i >= dirty.size) grow(i + 1)
        dirty[i] = true; valid = false
    }

    private fun grow(n: Int) {
        val c = maxOf(n, 2 * dirty.size, 16)
        val old = leaf.size
        leaf = leaf.copyOf(c); for (k in old until c) leaf[k] = -1
        inMoving = inMoving.copyOf(c); dirty = dirty.copyOf(c); last = last.copyOf(7 * c)
        for (k in old until c) dirty[k] = true
    }

    /** Brings every leaf up to date with its body. */
    fun refresh(w: PhysicsWorld) {
        val n = w.count
        if (leaf.size < n) grow(n)
        for (i in 0 until n) update(w, i)
        refreshes++
        valid = true
    }

    /**
     * Brings the static and kinematic bodies' leaves up to date (and those of bodies added, removed or moved
     * by hand), for the broad phase at the start of a step: the dynamic ones wait for the next query.
     */
    fun refreshStill(w: PhysicsWorld) {
        val n = w.count
        if (leaf.size < n) grow(n)
        val types = w.types
        for (i in 0 until n) if (types[i] != BodyType.DYNAMIC || dirty[i]) update(w, i)
    }

    /** Brings body [i]'s leaf up to date (a body put back by continuous collision while the trees serve). */
    fun update(w: PhysicsWorld, i: Int) {
        if (w.removed[i]) {
            if (leaf[i] >= 0) { (if (inMoving[i]) moving else still).remove(leaf[i]); leaf[i] = -1 }
            dirty[i] = false
            return
        }
        val px = w.px; val py = w.py; val pz = w.pz; val qx = w.qx; val qy = w.qy; val qz = w.qz; val qw = w.qw
        val type = w.types[i]
        val dynamic = type == BodyType.DYNAMIC
        val l = 7 * i
        if (leaf[i] >= 0) {
            if (inMoving[i] != dynamic) { (if (inMoving[i]) moving else still).remove(leaf[i]); leaf[i] = -1 }
            else if (!dirty[i] && (type == BodyType.STATIC || (last[l] == px[i] && last[l + 1] == py[i] && last[l + 2] == pz[i] &&
                    last[l + 3] == qx[i] && last[l + 4] == qy[i] && last[l + 5] == qz[i] && last[l + 6] == qw[i]))) return
        }
        dirty[i] = false
        last[l] = px[i]; last[l + 1] = py[i]; last[l + 2] = pz[i]; last[l + 3] = qx[i]; last[l + 4] = qy[i]; last[l + 5] = qz[i]; last[l + 6] = qw[i]
        localBounds(w.shapes[i]!!, pose.set(px[i], py[i], pz[i], qx[i], qy[i], qz[i], qw[i]), bounds, 0)
        val b = bounds
        val margin = if (type == BodyType.STATIC) 0f else BoxTree.MARGIN
        val tree = if (dynamic) moving else still
        if (leaf[i] < 0) { leaf[i] = tree.insert(i, b[0], b[1], b[2], b[3], b[4], b[5], margin); inMoving[i] = dynamic }
        else if (!tree.holds(leaf[i], b[0], b[1], b[2], b[3], b[4], b[5])) { tree.move(leaf[i], b[0], b[1], b[2], b[3], b[4], b[5], margin); moves++ }
    }

    /** Adds to [out] the bodies whose boxes overlap the box, sorted by id (the order of a body-by-body search). */
    fun bodiesIn(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, stillOnly: Boolean, out: IntList, stack: IntList) {
        out.clear()
        still.query(x0, y0, z0, x1, y1, z1, out, stack)
        if (!stillOnly) moving.query(x0, y0, z0, x1, y1, z1, out, stack)
        out.sort()
    }

    companion object {
        /** Below this many bodies, queries search body by body. */
        const val MIN_BODIES = 64
    }
}
