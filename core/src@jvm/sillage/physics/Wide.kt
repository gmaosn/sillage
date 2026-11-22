@file:Suppress("NOTHING_TO_INLINE")       // the small vector helpers are inlined on purpose (see below: platform types)

package sillage.physics

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorMask
import jdk.incubator.vector.VectorOperators
import jdk.incubator.vector.VectorSpecies
import kotlin.concurrent.Volatile
import kotlin.math.*

/** The wide solver's vector species as a static final field, loaded only by [Wide]. */
private object WideLanes { @JvmField val SPECIES: VectorSpecies<Float> = FloatVector.SPECIES_PREFERRED }

internal actual fun platformWideSolver(world: PhysicsWorld): WideContacts? {
    if (!ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent) return null
    // Made by name: the class uses the vector module and must never be loaded without it.
    return try {
        Class.forName("sillage.physics.Wide").getDeclaredConstructor(PhysicsWorld::class.java).newInstance(world) as WideContacts
    } catch (e: Throwable) { System.err.println("Sillage: no vector solver (" + e + ")"); null }
}

/**
 * Batches of the patches of each colour, their data laid out lane by lane (structure of arrays): the
 * patch rows, the points slot by slot (a batch has as many slots as its longest patch has points; the
 * shorter ones are masked), the two bodies' state. Built once per step, read by the 12 passes of its
 * substeps; the impulses go back to the contact sets at the end ([finish]).
 */
internal class Wide(private val world: PhysicsWorld) : WideContacts {
    // What it reads and calls in its world (it used to be an inner class of PhysicsWorld).
    private inline val st get() = world.st
    private inline val round get() = world.round
    private inline val colours get() = world.colours
    private inline val colourStart get() = world.colourStart
    private inline val ordered get() = world.ordered
    private inline val patches get() = world.patches
    private inline val points get() = world.points
    private inline val scaled get() = world.scaled
    private inline val hiddenM get() = world.hiddenM
    private inline val hiddenN get() = world.hiddenN
    private inline val hiddenT get() = world.hiddenT
    private inline val slide get() = world.slide
    private inline val gravity get() = world.gravity
    private inline val contactSpeed get() = world.contactSpeed
    private fun forChunks(n: Int, chunk: Int, large: Boolean, job: Job) = world.forChunks(n, chunk, large, job)
    private fun solvePatch(q: Int, useBias: Boolean, invH: Float, anyHidden: Boolean, gl: Float) = world.solvePatch(q, useBias, invH, anyHidden, gl)
    private fun warmStartPatch(q: Int) = world.warmStartPatch(q)

    // The species must be a constant (a static final field) for C2 to turn the vector code into SIMD
    // instructions: read from an instance field, every operation fell back to Java code, 30 times slower.
    private inline val sp: VectorSpecies<Float> get() = WideLanes.SPECIES
    private val n = sp.length()
    private var batches = 0
    private val colourBatch = IntArray(MAX_COLOURS + 1)  // first batch of each colour, then the end
    private var first = IntArray(0)          // per batch: its first patch in [ordered]
    private var lanes = IntArray(0)          // per batch: its patches (n at most)
    private var slots = IntArray(0)          // per batch: points of its longest patch
    private var slotBase = IntArray(0)       // per batch: its first slot
    private var aOff = IntArray(0); private var bOff = IntArray(0)   // per lane: body offsets in st (−1: none)
    private var rotA = BooleanArray(0); private var rotB = BooleanArray(0)   // per lane: anchors turn with the body
    private var live = BooleanArray(0)       // per slot and lane: a real point
    private var wp = FloatArray(0)           // per batch: PW vectors
    private var wc = FloatArray(0)           // per slot: CW vectors
    private var bb = FloatArray(0)           // per batch: 2 × S vectors (body a, then b)
    private var sorted = IntArray(0)
    private val buckets = IntArray(SORT_BUCKETS + 1)
    private val scalarStart = IntArray(MAX_COLOURS)      // per colour: its patches scaled for support, at the end of its run, scalar
    private var isScaled = BooleanArray(0)
    private var hs = FloatArray(0)                       // per batch: load, twist cap, reserved grip of each lane (hidden loads)

    override fun prepare(): Boolean {
        if (colours == 0) return false
        val ps = patches
        if (isScaled.size < ps.size) isScaled = BooleanArray(ps.size * 2) else isScaled.fill(false, 0, ps.size)
        for (k in 0 until scaled.size) isScaled[scaled[k]] = true
        var g = 0; var slotsTotal = 0
        for (c in 0 until MAX_COLOURS) {
            colourBatch[c] = g
            val from = colourStart[c]; val to = colourStart[c + 1]
            val wideTo = sortByPoints(from, to)
            scalarStart[c] = wideTo
            var i = from
            while (i < wideTo) {
                val k = min(n, wideTo - i)
                var most = 0
                for (l in 0 until k) { val q = ordered[i + l]; most = max(most, ps.end[q] - ps.start[q]) }
                if (g == first.size) grow(g + 1)
                first[g] = i; lanes[g] = k; slots[g] = most; slotBase[g] = slotsTotal
                slotsTotal += most; g++; i += k
            }
        }
        colourBatch[MAX_COLOURS] = g
        batches = g
        if (wp.size < g * PW * n) wp = FloatArray(g * PW * n * 3 / 2)
        if (bb.size < g * 2 * S * n) bb = FloatArray(g * 2 * S * n * 3 / 2)
        if (aOff.size < g * n) { aOff = IntArray(g * n * 3 / 2); bOff = IntArray(aOff.size); rotA = BooleanArray(aOff.size); rotB = BooleanArray(aOff.size) }
        if (wc.size < slotsTotal * CW * n) wc = FloatArray(slotsTotal * CW * n * 3 / 2)
        if (live.size < slotsTotal * n) live = BooleanArray(slotsTotal * n * 3 / 2)
        if (hs.size < g * 3 * n) hs = FloatArray(g * 3 * n * 3 / 2)
        forChunks(g, 8, g >= 16, fillJob)
        return true
    }

    private fun grow(size: Int) {
        val c = max(64, size * 2)
        first = first.copyOf(c); lanes = lanes.copyOf(c); slots = slots.copyOf(c); slotBase = slotBase.copyOf(c)
    }

    /**
     * Orders the patches of one colour by their number of points (fewer masked slots in each batch), those
     * scaled for support last: they stay scalar. Returns where they start. The order within a colour does not
     * change the result: its patches share no moving body.
     */
    private fun sortByPoints(from: Int, to: Int): Int {
        val ps = patches
        if (to - from < 1) return to
        buckets.fill(0)
        fun bucket(q: Int) = if (isScaled[q]) SORT_BUCKETS else min(ps.end[q] - ps.start[q], SORT_BUCKETS - 1)
        for (i in from until to) buckets[bucket(ordered[i])]++
        val scaledCount = buckets[SORT_BUCKETS]
        var at = 0
        for (k in 0..SORT_BUCKETS) { val c = buckets[k]; buckets[k] = at; at += c }
        if (sorted.size < to - from) sorted = IntArray((to - from) * 2)
        for (i in from until to) { val q = ordered[i]; sorted[buckets[bucket(q)]++] = q }
        sorted.copyInto(ordered, from, 0, 0 + (to - from))
        return to - scaledCount
    }

    private val fillJob = Job { from, to -> for (g in from until to) fill(g) }

    private fun fill(g: Int) {
        val ps = patches; val pd = ps.d; val d = points.d; val st = st
        val n = n; val k = lanes[g]; val sb = slotBase[g]; val slotCount = slots[g]
        for (l in 0 until n) {
            val lane = g * n + l
            if (l < k) {
                val q = ordered[first[g] + l]; val a = ps.a[q]; val b = ps.b[q]
                val k0 = ps.start[q]; val points = ps.end[q] - k0
                aOff[lane] = if (a >= 0) a * S else -1; bOff[lane] = b * S
                rotA[lane] = a >= 0 && !round[a]; rotB[lane] = !round[b]
                for (f in 0 until PW) {
                    val src = PATCH_SOURCE[f]
                    wp[(g * PW + f) * n + l] = if (src >= 0) pd[q * P + src] else d[k0 * C + (if (src == -1) CSA else CSB)]
                }
                for (j in 0 until slotCount) {
                    val s = sb + j
                    if (j < points) {
                        val o = (k0 + j) * C
                        for (f in 0 until CW) wc[(s * CW + f) * n + l] = d[o + POINT_SOURCE[f]]
                        live[s * n + l] = true
                    } else {
                        for (f in 0 until CW) wc[(s * CW + f) * n + l] = 0f
                        live[s * n + l] = false
                    }
                }
                // Mass and inverse inertia: they do not change during the step.
                for (f in IM..I0 + 5) {
                    bb[((g * 2) * S + f) * n + l] = if (a >= 0) st[a * S + f] else 0f
                    bb[((g * 2 + 1) * S + f) * n + l] = st[b * S + f]
                }
            } else {
                aOff[lane] = -1; bOff[lane] = -1; rotA[lane] = false; rotB[lane] = false
                for (f in 0 until PW) wp[(g * PW + f) * n + l] = 0f
                for (j in 0 until slotCount) { val s = sb + j; for (f in 0 until CW) wc[(s * CW + f) * n + l] = 0f; live[s * n + l] = false }
                for (f in 0 until 2 * S) bb[(g * 2 * S + f) * n + l] = 0f
            }
        }
    }

    /** The two bodies' velocities (and, for the solve, their motion since the step began) into the batch. */
    private fun gather(g: Int, pose: Boolean) {
        val st = st; val n = n; val k = lanes[g]
        for (l in 0 until k) {
            val lane = g * n + l
            val a = aOff[lane]; val b = bOff[lane]
            val ba = (g * 2) * S * n + l; val bbo = (g * 2 + 1) * S * n + l
            for (f in 0..5) { bb[ba + f * n] = if (a >= 0) st[a + f] else 0f; bb[bbo + f * n] = st[b + f] }
            if (pose) for (f in DPX..DQW) { bb[ba + f * n] = if (a >= 0) st[a + f] else if (f == DQW) 1f else 0f; bb[bbo + f * n] = st[b + f] }
        }
    }

    /** Back to the bodies, those with mass only (as the scalar solver). */
    private fun scatter(g: Int) {
        val st = st; val n = n; val k = lanes[g]
        for (l in 0 until k) {
            val lane = g * n + l
            val a = aOff[lane]; val b = bOff[lane]
            val ba = (g * 2) * S * n + l; val bbo = (g * 2 + 1) * S * n + l
            if (a >= 0 && bb[ba + IM * n] > 0f) for (f in 0..5) st[a + f] = bb[ba + f * n]
            if (bb[bbo + IM * n] > 0f) for (f in 0..5) st[b + f] = bb[bbo + f * n]
        }
    }

    // ---------------------------------------------------------------- warm start

    override fun warmStart() {
        for (c in 0 until MAX_COLOURS) {
            val from = colourBatch[c]; val to = colourBatch[c + 1]
            if (from < to) { batchBase = from; forChunks(to - from, WIDE_CHUNK, true, warmJob) }
            for (i in scalarStart[c] until colourStart[c + 1]) warmStartPatch(ordered[i])
        }
        val from = colourStart[MAX_COLOURS]; val to = colourStart[MAX_COLOURS + 1]
        for (i in from until to) warmStartPatch(ordered[i])
    }
    @Volatile private var batchBase = 0
    // Each pass is cut in methods small enough for C2 (under 8000 bytes of bytecode, else it is never
    // compiled and the vector operations stay on their slow Java path); they hand the velocities to each
    // other through the batch's array [bb], which stays in the L1 cache.

    private val warmJob = Job { from, to -> val b0 = batchBase; for (g in from until to) { gather(b0 + g, false); warmSlots(b0 + g); warmPatch(b0 + g); scatter(b0 + g) } }

    /** The point slots of the warm start: last step's normal impulses and rolling torques. */
    private fun warmSlots(g: Int) {
        val sp = sp; val n = n; val bb = bb; val wc = wc
        val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
        var vax = vec(bb, oa); var vay = vec(bb, oa + n); var vaz = vec(bb, oa + 2 * n)
        var wax = vec(bb, oa + 3 * n); var way = vec(bb, oa + 4 * n); var waz = vec(bb, oa + 5 * n)
        val mA = vec(bb, oa + IM * n)
        val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
        val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
        var vbx = vec(bb, ob); var vby = vec(bb, ob + n); var vbz = vec(bb, ob + 2 * n)
        var wbx = vec(bb, ob + 3 * n); var wby = vec(bb, ob + 4 * n); var wbz = vec(bb, ob + 5 * n)
        val mB = vec(bb, ob + IM * n)
        val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
        val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
        val zero = FloatVector.zero(sp)
        val heavyA = mA.compare(VectorOperators.GT, zero); val heavyB = mB.compare(VectorOperators.GT, zero)
        for (j in 0 until slots[g]) {
        val s = slotBase[g] + j; val o = s * CW * n
        val here = VectorMask.fromArray(sp, live, s * n)
        val massA = here.and(heavyA); val massB = here.and(heavyB)
        val ln = vec(wc, o + WC_LN * n)
        val px = vec(wc, o + WC_N * n) * ln; val py = vec(wc, o + (WC_N + 1) * n) * ln; val pz = vec(wc, o + (WC_N + 2) * n) * ln
        run {
            val f = vec(wc, o + WC_SA * n); val qx = px * f; val qy = py * f; val qz = pz * f
            vax = vax.blend(vax - qx * mA, massA); vay = vay.blend(vay - qy * mA, massA); vaz = vaz.blend(vaz - qz * mA, massA)
            val rx = vec(wc, o + WC_RA * n); val ry = vec(wc, o + (WC_RA + 1) * n); val rz = vec(wc, o + (WC_RA + 2) * n)
            val cx = ry * qz - rz * qy; val cy = rz * qx - rx * qz; val cz = rx * qy - ry * qx
            wax = wax.blend(wax - (ia0 * cx + ia1 * cy + ia2 * cz), massA)
            way = way.blend(way - (ia1 * cx + ia3 * cy + ia4 * cz), massA)
            waz = waz.blend(waz - (ia2 * cx + ia4 * cy + ia5 * cz), massA)
        }
        run {
            val f = vec(wc, o + WC_SB * n); val qx = px * f; val qy = py * f; val qz = pz * f
            vbx = vbx.blend(vbx + qx * mB, massB); vby = vby.blend(vby + qy * mB, massB); vbz = vbz.blend(vbz + qz * mB, massB)
            val rx = vec(wc, o + WC_RB * n); val ry = vec(wc, o + (WC_RB + 1) * n); val rz = vec(wc, o + (WC_RB + 2) * n)
            val cx = ry * qz - rz * qy; val cy = rz * qx - rx * qz; val cz = rx * qy - ry * qx
            wbx = wbx.blend(wbx + (ib0 * cx + ib1 * cy + ib2 * cz), massB)
            wby = wby.blend(wby + (ib1 * cx + ib3 * cy + ib4 * cz), massB)
            wbz = wbz.blend(wbz + (ib2 * cx + ib4 * cy + ib5 * cz), massB)
        }
        val roll = here.and(vec(wc, o + WC_RL * n).compare(VectorOperators.GT, zero))
        if (roll.anyTrue()) {
            val lx = vec(wc, o + WC_LR * n); val ly = vec(wc, o + (WC_LR + 1) * n); val lz = vec(wc, o + (WC_LR + 2) * n)
            val ma = roll.and(massA); val mb = roll.and(massB)
            wax = wax.blend(wax - (ia0 * lx + ia1 * ly + ia2 * lz), ma); way = way.blend(way - (ia1 * lx + ia3 * ly + ia4 * lz), ma)
            waz = waz.blend(waz - (ia2 * lx + ia4 * ly + ia5 * lz), ma)
            wbx = wbx.blend(wbx + (ib0 * lx + ib1 * ly + ib2 * lz), mb); wby = wby.blend(wby + (ib1 * lx + ib3 * ly + ib4 * lz), mb)
            wbz = wbz.blend(wbz + (ib2 * lx + ib4 * ly + ib5 * lz), mb)
        }
        }
        vax.intoArray(bb, oa); vay.intoArray(bb, oa + n); vaz.intoArray(bb, oa + 2 * n)
        wax.intoArray(bb, oa + 3 * n); way.intoArray(bb, oa + 4 * n); waz.intoArray(bb, oa + 5 * n)
        vbx.intoArray(bb, ob); vby.intoArray(bb, ob + n); vbz.intoArray(bb, ob + 2 * n)
        wbx.intoArray(bb, ob + 3 * n); wby.intoArray(bb, ob + 4 * n); wbz.intoArray(bb, ob + 5 * n)
    }

    /** The friction and twist of the patch, from the last step. */
    private fun warmPatch(g: Int) {
        val sp = sp; val n = n; val bb = bb; val wp = wp
        val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
        var vax = vec(bb, oa); var vay = vec(bb, oa + n); var vaz = vec(bb, oa + 2 * n)
        var wax = vec(bb, oa + 3 * n); var way = vec(bb, oa + 4 * n); var waz = vec(bb, oa + 5 * n)
        val mA = vec(bb, oa + IM * n)
        val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
        val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
        var vbx = vec(bb, ob); var vby = vec(bb, ob + n); var vbz = vec(bb, ob + 2 * n)
        var wbx = vec(bb, ob + 3 * n); var wby = vec(bb, ob + 4 * n); var wbz = vec(bb, ob + 5 * n)
        val mB = vec(bb, ob + IM * n)
        val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
        val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
        val zero = FloatVector.zero(sp)
        val massA = mA.compare(VectorOperators.GT, zero); val massB = mB.compare(VectorOperators.GT, zero)
        val p = g * PW * n
        val l1 = vec(wp, p + WP_L1 * n); val l2 = vec(wp, p + WP_L2 * n); val lt = vec(wp, p + WP_LT * n)
        val sa = vec(wp, p + WP_SA * n); val sb = vec(wp, p + WP_SB * n)
        val px = vec(wp, p + WP_T1 * n) * l1 + vec(wp, p + WP_T2 * n) * l2
        val py = vec(wp, p + (WP_T1 + 1) * n) * l1 + vec(wp, p + (WP_T2 + 1) * n) * l2
        val pz = vec(wp, p + (WP_T1 + 2) * n) * l1 + vec(wp, p + (WP_T2 + 2) * n) * l2
        val tx = vec(wp, p + WP_N * n) * lt; val ty = vec(wp, p + (WP_N + 1) * n) * lt; val tz = vec(wp, p + (WP_N + 2) * n) * lt
        run {
            val m = mA * sa
            vax = vax.blend(vax - px * m, massA); vay = vay.blend(vay - py * m, massA); vaz = vaz.blend(vaz - pz * m, massA)
            val rx = vec(wp, p + WP_RA * n); val ry = vec(wp, p + (WP_RA + 1) * n); val rz = vec(wp, p + (WP_RA + 2) * n)
            val cx = (ry * pz - rz * py) * sa + tx * sa; val cy = (rz * px - rx * pz) * sa + ty * sa; val cz = (rx * py - ry * px) * sa + tz * sa
            wax = wax.blend(wax - (ia0 * cx + ia1 * cy + ia2 * cz), massA)
            way = way.blend(way - (ia1 * cx + ia3 * cy + ia4 * cz), massA)
            waz = waz.blend(waz - (ia2 * cx + ia4 * cy + ia5 * cz), massA)
        }
        run {
            val m = mB * sb
            vbx = vbx.blend(vbx + px * m, massB); vby = vby.blend(vby + py * m, massB); vbz = vbz.blend(vbz + pz * m, massB)
            val rx = vec(wp, p + WP_RB * n); val ry = vec(wp, p + (WP_RB + 1) * n); val rz = vec(wp, p + (WP_RB + 2) * n)
            val cx = (ry * pz - rz * py) * sb + tx * sb; val cy = (rz * px - rx * pz) * sb + ty * sb; val cz = (rx * py - ry * px) * sb + tz * sb
            wbx = wbx.blend(wbx + (ib0 * cx + ib1 * cy + ib2 * cz), massB)
            wby = wby.blend(wby + (ib1 * cx + ib3 * cy + ib4 * cz), massB)
            wbz = wbz.blend(wbz + (ib2 * cx + ib4 * cy + ib5 * cz), massB)
        }
        vax.intoArray(bb, oa); vay.intoArray(bb, oa + n); vaz.intoArray(bb, oa + 2 * n)
        wax.intoArray(bb, oa + 3 * n); way.intoArray(bb, oa + 4 * n); waz.intoArray(bb, oa + 5 * n)
        vbx.intoArray(bb, ob); vby.intoArray(bb, ob + n); vbz.intoArray(bb, ob + 2 * n)
        wbx.intoArray(bb, ob + 3 * n); wby.intoArray(bb, ob + 4 * n); wbz.intoArray(bb, ob + 5 * n)
    }

    // ---------------------------------------------------------------- solve

    @Volatile private var useBias = false
    @Volatile private var invH = 0f
    @Volatile private var anyHidden = false
    @Volatile private var gl = 0f
    private val solveJob = Job { from, to ->
        val b0 = batchBase
        for (i in from until to) {
            val g = b0 + i
            gather(g, true)
            normalSlots(g)
            frictionStart(g); frictionRow(g, 0); frictionRow(g, 1); twist(g)
            rolling(g)
            scatter(g)
        }
    }

    override fun solve(useBias: Boolean, invH: Float, anyHidden: Boolean, gl: Float) {
        this.useBias = useBias; this.invH = invH; this.anyHidden = anyHidden; this.gl = gl
        for (c in 0 until MAX_COLOURS) {
            val from = colourBatch[c]; val to = colourBatch[c + 1]
            if (from < to) { batchBase = from; forChunks(to - from, WIDE_CHUNK, true, solveJob) }
            for (i in scalarStart[c] until colourStart[c + 1]) solvePatch(ordered[i], useBias, invH, anyHidden, gl)
        }
        val from = colourStart[MAX_COLOURS]; val to = colourStart[MAX_COLOURS + 1]
        for (i in from until to) solvePatch(ordered[i], useBias, invH, anyHidden, gl)
    }

    /** The normal points, slot after slot: soft non-penetration, separation from the motion since the step began. */
    private fun normalSlots(g: Int) {
        val sp = sp; val n = n; val bb = bb; val wc = wc
        val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
        val dax = vec(bb, oa + DPX * n); val day = vec(bb, oa + (DPX + 1) * n); val daz = vec(bb, oa + (DPX + 2) * n)
        val qax = vec(bb, oa + DQX * n); val qay = vec(bb, oa + (DQX + 1) * n); val qaz = vec(bb, oa + (DQX + 2) * n); val qaw = vec(bb, oa + DQW * n)
        val dbx = vec(bb, ob + DPX * n); val dby = vec(bb, ob + (DPX + 1) * n); val dbz = vec(bb, ob + (DPX + 2) * n)
        val qbx = vec(bb, ob + DQX * n); val qby = vec(bb, ob + (DQX + 1) * n); val qbz = vec(bb, ob + (DQX + 2) * n); val qbw = vec(bb, ob + DQW * n)
        val zero = FloatVector.zero(sp); val one = FloatVector.broadcast(sp, 1f); val two = FloatVector.broadcast(sp, 2f)
        val turnA = VectorMask.fromArray(sp, rotA, g * n); val turnB = VectorMask.fromArray(sp, rotB, g * n)
        val anyTurnA = turnA.anyTrue(); val anyTurnB = turnB.anyTrue()
        for (j in 0 until slots[g]) {
        val s = slotBase[g] + j; val o = s * CW * n
        val here = VectorMask.fromArray(sp, live, s * n)
        // Velocities read again at each point: the last one's push changed them (in [bb]).
        val vax = vec(bb, oa); val vay = vec(bb, oa + n); val vaz = vec(bb, oa + 2 * n)
        val wax = vec(bb, oa + 3 * n); val way = vec(bb, oa + 4 * n); val waz = vec(bb, oa + 5 * n)
        val vbx = vec(bb, ob); val vby = vec(bb, ob + n); val vbz = vec(bb, ob + 2 * n)
        val wbx = vec(bb, ob + 3 * n); val wby = vec(bb, ob + 4 * n); val wbz = vec(bb, ob + 5 * n)
        val nx = vec(wc, o + WC_N * n); val ny = vec(wc, o + (WC_N + 1) * n); val nz = vec(wc, o + (WC_N + 2) * n)
        val rax = vec(wc, o + WC_RA * n); val ray = vec(wc, o + (WC_RA + 1) * n); val raz = vec(wc, o + (WC_RA + 2) * n)
        val rbx = vec(wc, o + WC_RB * n); val rby = vec(wc, o + (WC_RB + 1) * n); val rbz = vec(wc, o + (WC_RB + 2) * n)
        // Anchors turned by each body's rotation since the step began (a sphere keeps its anchor).
        var cbx = rbx; var cby = rby; var cbz = rbz
        if (anyTurnB) {
            val tx = two * (qby * rbz - qbz * rby); val ty = two * (qbz * rbx - qbx * rbz); val tz = two * (qbx * rby - qby * rbx)
            cbx = rbx.blend(rbx + qbw * tx + (qby * tz - qbz * ty), turnB)
            cby = rby.blend(rby + qbw * ty + (qbz * tx - qbx * tz), turnB)
            cbz = rbz.blend(rbz + qbw * tz + (qbx * ty - qby * tx), turnB)
        }
        var cax = rax; var cay = ray; var caz = raz
        if (anyTurnA) {
            val tx = two * (qay * raz - qaz * ray); val ty = two * (qaz * rax - qax * raz); val tz = two * (qax * ray - qay * rax)
            cax = rax.blend(rax + qaw * tx + (qay * tz - qaz * ty), turnA)
            cay = ray.blend(ray + qaw * ty + (qaz * tx - qax * tz), turnA)
            caz = raz.blend(raz + qaw * tz + (qax * ty - qay * tx), turnA)
        }
        val sep = (dbx - dax + cbx - cax) * nx + (dby - day + cby - cay) * ny + (dbz - daz + cbz - caz) * nz + vec(wc, o + WC_ADJ * n)
        val apart = sep.compare(VectorOperators.GT, zero)
        val toward = sep * FloatVector.broadcast(sp, invH)
        val bias = if (useBias) (vec(wc, o + WC_BIAS * n) * sep).max(FloatVector.broadcast(sp, -contactSpeed)).blend(toward, apart) else zero.blend(toward, apart)
        val massScale = if (useBias) vec(wc, o + WC_MS * n).blend(one, apart) else one
        val impulseScale = if (useBias) vec(wc, o + WC_IS * n).blend(zero, apart) else zero
        val rvx = vbx + wby * rbz - wbz * rby - (vax + way * raz - waz * ray)
        val rvy = vby + wbz * rbx - wbx * rbz - (vay + waz * rax - wax * raz)
        val rvz = vbz + wbx * rby - wby * rbx - (vaz + wax * ray - way * rax)
        val vn = rvx * nx + rvy * ny + rvz * nz
        val old = vec(wc, o + WC_LN * n)
        val total = (old - vec(wc, o + WC_NM * n) * massScale * (vn + bias) - impulseScale * old).max(zero)
        val impulse = total - old
        old.blend(total, here).intoArray(wc, o + WC_LN * n)
        val maxn = vec(wc, o + WC_MAXN * n)
        maxn.blend(impulse, here.and(impulse.compare(VectorOperators.GT, maxn))).intoArray(wc, o + WC_MAXN * n)
        val push = here.and(impulse.compare(VectorOperators.NE, zero))
        if (!push.anyTrue()) continue
        // Through the batch's arrays: vectors given to a method C2 does not inline would be allocated.
        zero.blend(impulse, push).intoArray(hs, g * 3 * n + 2 * n)
        pushApart(g, o)
        }
    }

    /** Applies the slot's normal impulse (in [hs], zero where nothing pushes) to both bodies, where they have mass. */
    private fun pushApart(g: Int, o: Int) {
        val sp = sp; val n = n; val bb = bb; val wc = wc
        val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
        val zero = FloatVector.zero(sp)
        val impulse = vec(hs, g * 3 * n + 2 * n)
        val push = impulse.compare(VectorOperators.NE, zero)
        val px = vec(wc, o + WC_N * n) * impulse; val py = vec(wc, o + (WC_N + 1) * n) * impulse; val pz = vec(wc, o + (WC_N + 2) * n) * impulse
        run {
            val mA = vec(bb, oa + IM * n)
            val m = push.and(mA.compare(VectorOperators.GT, zero))
            val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
            val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
            val rax = vec(wc, o + WC_RA * n); val ray = vec(wc, o + (WC_RA + 1) * n); val raz = vec(wc, o + (WC_RA + 2) * n)
            val f = vec(wc, o + WC_SA * n); val qx = px * f; val qy = py * f; val qz = pz * f
            val vax = vec(bb, oa); val vay = vec(bb, oa + n); val vaz = vec(bb, oa + 2 * n)
            vax.blend(vax - qx * mA, m).intoArray(bb, oa); vay.blend(vay - qy * mA, m).intoArray(bb, oa + n); vaz.blend(vaz - qz * mA, m).intoArray(bb, oa + 2 * n)
            val cx = ray * qz - raz * qy; val cy = raz * qx - rax * qz; val cz = rax * qy - ray * qx
            val wax = vec(bb, oa + 3 * n); val way = vec(bb, oa + 4 * n); val waz = vec(bb, oa + 5 * n)
            wax.blend(wax - (ia0 * cx + ia1 * cy + ia2 * cz), m).intoArray(bb, oa + 3 * n)
            way.blend(way - (ia1 * cx + ia3 * cy + ia4 * cz), m).intoArray(bb, oa + 4 * n)
            waz.blend(waz - (ia2 * cx + ia4 * cy + ia5 * cz), m).intoArray(bb, oa + 5 * n)
        }
        run {
            val mB = vec(bb, ob + IM * n)
            val m = push.and(mB.compare(VectorOperators.GT, zero))
            val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
            val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
            val rbx = vec(wc, o + WC_RB * n); val rby = vec(wc, o + (WC_RB + 1) * n); val rbz = vec(wc, o + (WC_RB + 2) * n)
            val f = vec(wc, o + WC_SB * n); val qx = px * f; val qy = py * f; val qz = pz * f
            val vbx = vec(bb, ob); val vby = vec(bb, ob + n); val vbz = vec(bb, ob + 2 * n)
            vbx.blend(vbx + qx * mB, m).intoArray(bb, ob); vby.blend(vby + qy * mB, m).intoArray(bb, ob + n); vbz.blend(vbz + qz * mB, m).intoArray(bb, ob + 2 * n)
            val cx = rby * qz - rbz * qy; val cy = rbz * qx - rbx * qz; val cz = rbx * qy - rby * qx
            val wbx = vec(bb, ob + 3 * n); val wby = vec(bb, ob + 4 * n); val wbz = vec(bb, ob + 5 * n)
            wbx.blend(wbx + (ib0 * cx + ib1 * cy + ib2 * cz), m).intoArray(bb, ob + 3 * n)
            wby.blend(wby + (ib1 * cx + ib3 * cy + ib4 * cz), m).intoArray(bb, ob + 4 * n)
            wbz.blend(wbz + (ib2 * cx + ib4 * cy + ib5 * cz), m).intoArray(bb, ob + 5 * n)
        }
    }

    /**
     * The patch's normal load and twist cap (with the loads hidden by the support scaling), then the
     * friction limit μ·N and the twist cap μ·Σ Nᵢ·dᵢ, kept in [hs] for the rows and the twist.
     */
    private fun frictionStart(g: Int) {
        val sp = sp; val n = n; val wc = wc; val wp = wp
        val zero = FloatVector.zero(sp)
        var load = zero; var twistCap = zero
        for (j in 0 until slots[g]) {
            val s = slotBase[g] + j; val o = s * CW * n
            val here = VectorMask.fromArray(sp, live, s * n)
            val l = vec(wc, o + WC_LN * n)
            load = load.blend(load + l, here); twistCap = twistCap.blend(twistCap + l * vec(wc, o + WC_DIST * n), here)
        }
        val h = g * 3 * n
        var reserved = zero
        if (anyHidden && gl > 0f) {
            // Loads hidden by the support scaling, lane by lane as the scalar solver does (rare: heavy on light).
            load.intoArray(hs, h); twistCap.intoArray(hs, h + n); zero.intoArray(hs, h + 2 * n)
            if (hidden(g)) { load = vec(hs, h); twistCap = vec(hs, h + n); reserved = vec(hs, h + 2 * n) }
        }
        val mu = vec(wp, g * PW * n + WP_FR * n)
        zero.max(mu * load - reserved).intoArray(hs, h)
        (mu * twistCap).intoArray(hs, h + n)
    }

    /** One friction row at the patch centre: first along the sliding direction, then across, within the circle μ·N. */
    private fun frictionRow(g: Int, row: Int) {
        val sp = sp; val n = n; val bb = bb; val wp = wp
        val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
        val p = g * PW * n; val h = g * 3 * n
        val vax = vec(bb, oa); val vay = vec(bb, oa + n); val vaz = vec(bb, oa + 2 * n)
        val wax = vec(bb, oa + 3 * n); val way = vec(bb, oa + 4 * n); val waz = vec(bb, oa + 5 * n)
        val vbx = vec(bb, ob); val vby = vec(bb, ob + n); val vbz = vec(bb, ob + 2 * n)
        val wbx = vec(bb, ob + 3 * n); val wby = vec(bb, ob + 4 * n); val wbz = vec(bb, ob + 5 * n)
        val zero = FloatVector.zero(sp)
        val limit = vec(hs, h)
        val rax = vec(wp, p + WP_RA * n); val ray = vec(wp, p + (WP_RA + 1) * n); val raz = vec(wp, p + (WP_RA + 2) * n)
        val rbx = vec(wp, p + WP_RB * n); val rby = vec(wp, p + (WP_RB + 1) * n); val rbz = vec(wp, p + (WP_RB + 2) * n)
        val t = p + (if (row == 0) WP_T1 else WP_T2) * n
        val tx = vec(wp, t); val ty = vec(wp, t + n); val tz = vec(wp, t + 2 * n)
        val rvx = vbx + wby * rbz - wbz * rby - (vax + way * raz - waz * ray)
        val rvy = vby + wbz * rbx - wbx * rbz - (vay + waz * rax - wax * raz)
        val rvz = vbz + wbx * rby - wby * rbx - (vaz + wax * ray - way * rax)
        val li = p + (if (row == 0) WP_L1 else WP_L2) * n
        val bound = if (row == 0) limit else { val l1 = vec(wp, p + WP_L1 * n); zero.max(limit * limit - l1 * l1).sqrt() }
        val old = vec(wp, li)
        val new = clamp(old - vec(wp, p + (if (row == 0) WP_M1 else WP_M2) * n) * (rvx * tx + rvy * ty + rvz * tz), bound)
        new.intoArray(wp, li)
        val moved = new.compare(VectorOperators.NE, old)
        if (!moved.anyTrue()) return
        val e = new - old
        val px = tx * e; val py = ty * e; val pz = tz * e
        val sa = vec(wp, p + WP_SA * n); val sb = vec(wp, p + WP_SB * n)
        run {
            val msa = vec(bb, oa + IM * n) * sa
            val m = moved.and(msa.compare(VectorOperators.GT, zero))
            val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
            val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
            vax.blend(vax - px * msa, m).intoArray(bb, oa); vay.blend(vay - py * msa, m).intoArray(bb, oa + n); vaz.blend(vaz - pz * msa, m).intoArray(bb, oa + 2 * n)
            val cx = (ray * pz - raz * py) * sa; val cy = (raz * px - rax * pz) * sa; val cz = (rax * py - ray * px) * sa
            wax.blend(wax - (ia0 * cx + ia1 * cy + ia2 * cz), m).intoArray(bb, oa + 3 * n)
            way.blend(way - (ia1 * cx + ia3 * cy + ia4 * cz), m).intoArray(bb, oa + 4 * n)
            waz.blend(waz - (ia2 * cx + ia4 * cy + ia5 * cz), m).intoArray(bb, oa + 5 * n)
        }
        run {
            val msb = vec(bb, ob + IM * n) * sb
            val m = moved.and(msb.compare(VectorOperators.GT, zero))
            val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
            val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
            vbx.blend(vbx + px * msb, m).intoArray(bb, ob); vby.blend(vby + py * msb, m).intoArray(bb, ob + n); vbz.blend(vbz + pz * msb, m).intoArray(bb, ob + 2 * n)
            val cx = (rby * pz - rbz * py) * sb; val cy = (rbz * px - rbx * pz) * sb; val cz = (rbx * py - rby * px) * sb
            wbx.blend(wbx + (ib0 * cx + ib1 * cy + ib2 * cz), m).intoArray(bb, ob + 3 * n)
            wby.blend(wby + (ib1 * cx + ib3 * cy + ib4 * cz), m).intoArray(bb, ob + 4 * n)
            wbz.blend(wbz + (ib2 * cx + ib4 * cy + ib5 * cz), m).intoArray(bb, ob + 5 * n)
        }
    }

    /** The twist about the normal, within μ·Σ Nᵢ·dᵢ. */
    private fun twist(g: Int) {
        val sp = sp; val n = n; val bb = bb; val wp = wp
        val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
        val p = g * PW * n; val h = g * 3 * n
        val wax = vec(bb, oa + 3 * n); val way = vec(bb, oa + 4 * n); val waz = vec(bb, oa + 5 * n)
        val wbx = vec(bb, ob + 3 * n); val wby = vec(bb, ob + 4 * n); val wbz = vec(bb, ob + 5 * n)
        val nx = vec(wp, p + WP_N * n); val ny = vec(wp, p + (WP_N + 1) * n); val nz = vec(wp, p + (WP_N + 2) * n)
        val wn = (wbx - wax) * nx + (wby - way) * ny + (wbz - waz) * nz
        val old = vec(wp, p + WP_LT * n)
        val new = clamp(old - vec(wp, p + WP_MT * n) * wn, vec(hs, h + n))
        new.intoArray(wp, p + WP_LT * n)
        val moved = new.compare(VectorOperators.NE, old)
        if (!moved.anyTrue()) return
        val zero = FloatVector.zero(sp)
        val e = new - old; val lx = nx * e; val ly = ny * e; val lz = nz * e
        val sa = vec(wp, p + WP_SA * n); val sb = vec(wp, p + WP_SB * n)
        run {
            val ma = moved.and(vec(bb, oa + IM * n).compare(VectorOperators.GT, zero))
            val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
            val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
            wax.blend(wax - (ia0 * lx + ia1 * ly + ia2 * lz) * sa, ma).intoArray(bb, oa + 3 * n)
            way.blend(way - (ia1 * lx + ia3 * ly + ia4 * lz) * sa, ma).intoArray(bb, oa + 4 * n)
            waz.blend(waz - (ia2 * lx + ia4 * ly + ia5 * lz) * sa, ma).intoArray(bb, oa + 5 * n)
        }
        run {
            val mb = moved.and(vec(bb, ob + IM * n).compare(VectorOperators.GT, zero))
            val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
            val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
            wbx.blend(wbx + (ib0 * lx + ib1 * ly + ib2 * lz) * sb, mb).intoArray(bb, ob + 3 * n)
            wby.blend(wby + (ib1 * lx + ib3 * ly + ib4 * lz) * sb, mb).intoArray(bb, ob + 4 * n)
            wbz.blend(wbz + (ib2 * lx + ib4 * ly + ib5 * lz) * sb, mb).intoArray(bb, ob + 5 * n)
        }
    }

    /**
     * The scalar solver's hidden loads, lane by lane on the batch's arrays (load, twist cap, reserved grip in
     * [hs], velocities in [bb]): the same operations in the same order. False if no lane carries one.
     */
    private fun hidden(g: Int): Boolean {
        val ps = patches; val n = n; val h = g * 3 * n; val p = g * PW * n
        var any = false
        for (l in 0 until lanes[g]) {
            val q = ordered[first[g] + l]; val a = ps.a[q]; val b = ps.b[q]
            val nx = wp[p + WP_N * n + l]; val ny = wp[p + (WP_N + 1) * n + l]; val nz = wp[p + (WP_N + 2) * n + l]
            val up = -(nx * gravity.x + ny * gravity.y + nz * gravity.z) / gl
            val x = if (up >= .5f) b else if (up <= -.5f && a >= 0) a else -1
            if (x < 0 || hiddenN[x] <= 0f) continue
            any = true
            var load = hs[h + l]; var twistCap = hs[h + n + l]
            twistCap *= (load + hiddenN[x]) / max(load, 1e-12f); load += hiddenN[x]
            var hx = hiddenT[3 * x]; var hy = hiddenT[3 * x + 1]; var hz = hiddenT[3 * x + 2]
            val hn = hx * nx + hy * ny + hz * nz; hx -= hn * nx; hy -= hn * ny; hz -= hn * nz
            val hl = sqrt(hx * hx + hy * hy + hz * hz)
            val grip = wp[p + WP_FR * n + l] * load
            if (hl > grip && useBias) {
                val e = (hl - grip) / hl / (1f / st[x * S + IM] + hiddenM[x])
                val ex = hx * e; val ey = hy * e; val ez = hz * e
                val o = (if (x == b) (g * 2 + 1) else (g * 2)) * S * n + l
                bb[o] += ex; bb[o + n] += ey; bb[o + 2 * n] += ez
                slide[3 * x] += ex; slide[3 * x + 1] += ey; slide[3 * x + 2] += ez
            }
            hs[h + l] = load; hs[h + n + l] = twistCap; hs[h + 2 * n + l] = min(hl, grip)
        }
        return any
    }

    /** Rolling resistance, per point: a torque against the relative spin, bounded by μr × radius × N. */
    private fun rolling(g: Int) {
        val sp = sp; val n = n; val bb = bb; val wc = wc
        val zero = FloatVector.zero(sp)
        var any = false
        for (j in 0 until slots[g]) {
            val s = slotBase[g] + j
            if (VectorMask.fromArray(sp, live, s * n).and(vec(wc, (s * CW + WC_RL) * n).compare(VectorOperators.GT, zero)).anyTrue()) { any = true; break }
        }
        if (!any) return
        val oa = (g * 2) * S * n; val ob = (g * 2 + 1) * S * n
        var wax = vec(bb, oa + 3 * n); var way = vec(bb, oa + 4 * n); var waz = vec(bb, oa + 5 * n)
        val mA = vec(bb, oa + IM * n)
        val ia0 = vec(bb, oa + I0 * n); val ia1 = vec(bb, oa + (I0 + 1) * n); val ia2 = vec(bb, oa + (I0 + 2) * n)
        val ia3 = vec(bb, oa + (I0 + 3) * n); val ia4 = vec(bb, oa + (I0 + 4) * n); val ia5 = vec(bb, oa + (I0 + 5) * n)
        var wbx = vec(bb, ob + 3 * n); var wby = vec(bb, ob + 4 * n); var wbz = vec(bb, ob + 5 * n)
        val mB = vec(bb, ob + IM * n)
        val ib0 = vec(bb, ob + I0 * n); val ib1 = vec(bb, ob + (I0 + 1) * n); val ib2 = vec(bb, ob + (I0 + 2) * n)
        val ib3 = vec(bb, ob + (I0 + 3) * n); val ib4 = vec(bb, ob + (I0 + 4) * n); val ib5 = vec(bb, ob + (I0 + 5) * n)
        val massA = mA.compare(VectorOperators.GT, zero); val massB = mB.compare(VectorOperators.GT, zero)
        for (j in 0 until slots[g]) {
            val s = slotBase[g] + j; val o = s * CW * n
            val rl = vec(wc, o + WC_RL * n)
            val roll = VectorMask.fromArray(sp, live, s * n).and(rl.compare(VectorOperators.GT, zero))
            if (!roll.anyTrue()) continue
            val m = vec(wc, o + WC_RM * n); val cap = rl * vec(wc, o + WC_LN * n)
            val ox = vec(wc, o + WC_LR * n); val oy = vec(wc, o + (WC_LR + 1) * n); val oz = vec(wc, o + (WC_LR + 2) * n)
            var lx = ox - m * (wbx - wax); var ly = oy - m * (wby - way); var lz = oz - m * (wbz - waz)
            val l2 = lx * lx + ly * ly + lz * lz
            val over = l2.compare(VectorOperators.GT, cap * cap)
            if (over.anyTrue()) { val f = cap / l2.sqrt(); lx = lx.blend(lx * f, over); ly = ly.blend(ly * f, over); lz = lz.blend(lz * f, over) }
            ox.blend(lx, roll).intoArray(wc, o + WC_LR * n); oy.blend(ly, roll).intoArray(wc, o + (WC_LR + 1) * n); oz.blend(lz, roll).intoArray(wc, o + (WC_LR + 2) * n)
            val ex = lx - ox; val ey = ly - oy; val ez = lz - oz
            val ma = roll.and(massA); val mb = roll.and(massB)
            wax = wax.blend(wax - (ia0 * ex + ia1 * ey + ia2 * ez), ma); way = way.blend(way - (ia1 * ex + ia3 * ey + ia4 * ez), ma)
            waz = waz.blend(waz - (ia2 * ex + ia4 * ey + ia5 * ez), ma)
            wbx = wbx.blend(wbx + (ib0 * ex + ib1 * ey + ib2 * ez), mb); wby = wby.blend(wby + (ib1 * ex + ib3 * ey + ib4 * ez), mb)
            wbz = wbz.blend(wbz + (ib2 * ex + ib4 * ey + ib5 * ez), mb)
        }
        wax.intoArray(bb, oa + 3 * n); way.intoArray(bb, oa + 4 * n); waz.intoArray(bb, oa + 5 * n)
        wbx.intoArray(bb, ob + 3 * n); wby.intoArray(bb, ob + 4 * n); wbz.intoArray(bb, ob + 5 * n)
    }

    /** The impulses back into the contact sets: the restitution, sleep and next step's warm start read them there. */
    override fun finish() { forChunks(batches, 8, batches >= 16, finishJob) }
    private val finishJob = Job { from, to -> for (g in from until to) back(g) }

    private fun back(g: Int) {
        val ps = patches; val pd = ps.d; val d = points.d; val n = n
        for (l in 0 until lanes[g]) {
            val q = ordered[first[g] + l]
            pd[q * P + PL1] = wp[(g * PW + WP_L1) * n + l]; pd[q * P + PL2] = wp[(g * PW + WP_L2) * n + l]; pd[q * P + PLT] = wp[(g * PW + WP_LT) * n + l]
            val k0 = ps.start[q]
            for (j in 0 until ps.end[q] - k0) {
                val s = slotBase[g] + j; val o = (k0 + j) * C
                d[o + CLN] = wc[(s * CW + WC_LN) * n + l]; d[o + CMAXN] = wc[(s * CW + WC_MAXN) * n + l]
                d[o + CLR] = wc[(s * CW + WC_LR) * n + l]; d[o + CLR + 1] = wc[(s * CW + WC_LR + 1) * n + l]; d[o + CLR + 2] = wc[(s * CW + WC_LR + 2) * n + l]
            }
        }
    }

    // Operators and helpers inlined where they are used: a vector never crosses a call the JIT might not inline.
    // Their results keep Java's platform types (no declared type): a value given a Kotlin non-null type gets a
    // null check, a call the vector escapes into, and C2 then allocates it (3.5 times slower).
    private inline operator fun FloatVector.plus(o: FloatVector) = add(o)
    private inline operator fun FloatVector.minus(o: FloatVector) = sub(o)
    private inline operator fun FloatVector.times(o: FloatVector) = mul(o)
    private inline fun vec(a: FloatArray, at: Int) = FloatVector.fromArray(sp, a, at)

    /** Kotlin's coerceIn(−bound, bound), lane by lane: below → −bound, above → bound, else as it is. */
    private inline fun clamp(x: FloatVector, bound: FloatVector) =
        x.blend(bound.neg(), x.compare(VectorOperators.LT, bound.neg())).blend(bound, x.compare(VectorOperators.GT, bound))
}
