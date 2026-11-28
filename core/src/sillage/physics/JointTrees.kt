package sillage.physics

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The joints' rigid rows (anchors together, axes aligned, rotations locked, rods at length) solved all at once,
 * exactly, over each tree of joined bodies (after Baraff's linear-time idea, in the joints' own space): the
 * matrix K = J M⁻¹ Jᵀ of a tree of joints fills in nothing when the joints are eliminated from the leaves in,
 * each one when all the joints it still touches meet at one of its bodies. A heavy load on light bodies then
 * reaches the posts or the ceiling in one solve, instead of creeping through Gauss–Seidel passes that the soft
 * step gives up on (a 70 kg character on a bridge of 5 kg planks, a 500 kg crate on a chain of 0.7 kg links).
 *
 * Joints that would close a loop of dynamic bodies are left to the iterative passes, as are limits, motors,
 * springs, cones and twists. The rows are taken at the start of the step, and each pass solves that linear
 * system with the velocities of the moment: the rigid and relax passes make the velocities keep the rows
 * exactly, the biased pass corrects the positions at the joints' stiffness without the soft step's give.
 * Redundant rows (two hinges on one door, a bridge hung at both ends) are regularised by a hair. Doubles
 * inside, a fixed order everywhere: the same bits on every platform, with or without threads.
 */
internal class JointTrees(private val world: PhysicsWorld) {
    private val list = ArrayList<Joint>()
    private var n = 0                                   // joints solved here this step
    private var rows = IntArray(0); private var start = IntArray(0)
    private var bodyA = IntArray(0); private var bodyB = IntArray(0)      // their bodies (a: −1 for the world)
    private var nodeA = IntArray(0); private var nodeB = IntArray(0)      // the same where dynamic, else −1
    private var total = 0                                                 // rows of all of them
    /** Per row, 12 doubles: linear and angular parts on a, then on b. */
    private var jac = DoubleArray(0)
    /** Per joint, its diagonal block (6 × 6 slots) and its Cholesky factor. */
    private var diag = DoubleArray(0); private var chol = DoubleArray(0)
    // Per dynamic body: its joints (positions in its list) and its off-diagonal blocks (d × d slots of 6 × 6).
    private var bodyDegree = IntArray(0); private var degree = IntArray(0); private var stamp = IntArray(0); private var mark = 0
    private var bodyStart = IntArray(0); private var bodyJoints = IntArray(0)
    private var blockStart = IntArray(0); private var blocks = DoubleArray(0)
    private var posInBody = IntArray(0)                                   // per joint and end: its position in that body's list
    // Elimination: the order, and per joint its later neighbours with their factors L_kj = K_kj D_j⁻¹.
    private var order = IntArray(0); private var eliminated = BooleanArray(0)
    private var laterFrom = IntArray(0); private var laterTo = IntArray(0)
    private var laterJoint = IntArray(16); private var laterFactor = IntArray(16)
    private var factors = DoubleArray(36 * 16)
    private var queue = IntArray(0)
    private var parent = IntArray(0); private var tainted = BooleanArray(0); private var calm = BooleanArray(0)
    private var rhs = DoubleArray(0); private var err = DoubleArray(0)
    private val tmp = DoubleArray(6)


    private fun root(i: Int): Int { var r = i; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }

    private fun dynamic(i: Int) = i >= 0 && world.st[i * S + IM] > 0f

    /** Picks this step's tree joints among [joints] (prepared already), builds K at the step's start and factors it. */
    fun prepare(joints: List<Joint>, dt: Float) {
        for (k in 0 until list.size) list[k].direct = false
        list.clear(); n = 0; total = 0
        if (joints.isEmpty()) return
        val bodies = world.count
        if (parent.size < bodies) {
            val c = bodies + 16
            parent = IntArray(c); tainted = BooleanArray(c); calm = BooleanArray(c); bodyDegree = IntArray(c); degree = IntArray(c); stamp = IntArray(c)
            bodyStart = IntArray(c); blockStart = IntArray(c)
        }
        for (j in joints) { if (j.ia >= 0) { parent[j.ia] = j.ia; tainted[j.ia] = false }; parent[j.ib] = j.ib; tainted[j.ib] = false }
        // The groups of dynamic bodies joined together, and those with a joint that stays iterative: one with no
        // rigid rows (a cone, a spring, a rope), at or near one of its limits (its limit and its anchor are solved
        // together), whose two bodies touch each other (their contacts and the joint have it out softly), or that
        // closes a loop. Such a group stays iterative as a whole: an exact joint beside an iterative one pushes it
        // past its limits.
        for (j in joints) {
            val a = if (dynamic(j.ia)) j.ia else -1
            val b = if (dynamic(j.ib)) j.ib else -1
            if (a < 0 && b < 0) continue
            var iterative = j.directRows == 0 || j.nearLimits(dt) || (j.collideConnected && j.ia >= 0 && world.touchingEachOther(j.ia, j.ib))
            var r = if (a >= 0) root(a) else root(b)
            if (a >= 0 && b >= 0) {
                val rb = root(b)
                if (r == rb) iterative = true
                else { parent[r] = rb; tainted[rb] = tainted[rb] || tainted[r]; r = rb }
            }
            if (iterative) tainted[r] = true
        }
        for (j in joints) {
            val a = if (dynamic(j.ia)) j.ia else -1
            val b = if (dynamic(j.ib)) j.ib else -1
            if (a < 0 && b < 0) continue
            val r = root(if (a >= 0) a else b)
            if (!tainted[r]) list.add(j)
            calm[r] = false
        }
        // A group a kinematic body touches (a character standing on a bridge) is not stiffened: its contacts, which
        // have the last word, would fight the stiffened bodies.
        for (j in joints) {
            val a = if (dynamic(j.ia)) j.ia else -1
            val b = if (dynamic(j.ib)) j.ib else -1
            if (a < 0 && b < 0) continue
            if ((a >= 0 && world.touchingKinematic(a)) || (b >= 0 && world.touchingKinematic(b))) calm[root(if (a >= 0) a else b)] = true
        }
        val count = list.size
        if (count == 0) return
        if (rows.size < count) {
            val c = count + 16
            rows = IntArray(c); start = IntArray(c + 1); bodyA = IntArray(c); bodyB = IntArray(c); nodeA = IntArray(c); nodeB = IntArray(c)
            order = IntArray(c); eliminated = BooleanArray(c); laterFrom = IntArray(c); laterTo = IntArray(c); loads = FloatArray(c)
            posInBody = IntArray(2 * c); queue = IntArray(4 * c + 16)
            diag = DoubleArray(36 * c); chol = DoubleArray(36 * c)
        }
        for (k in 0 until count) {
            val j = list[k]
            rows[k] = j.directRows; start[k] = total; total += rows[k]
            bodyA[k] = j.ia; bodyB[k] = j.ib
            nodeA[k] = if (dynamic(j.ia)) j.ia else -1
            nodeB[k] = if (dynamic(j.ib)) j.ib else -1
        }
        start[count] = total
        if (jac.size < 12 * total) jac = DoubleArray(12 * total + 64)
        if (rhs.size < total) { rhs = DoubleArray(total + 16); err = DoubleArray(total + 16) }
        buildBodies(count)
        if (ordering(count) != count) { list.clear(); return }     // not a forest after all: everything iterative this step
        n = count
        for (k in 0 until n) list[k].direct = true
    }

    /**
     * The rows where the bodies are now (each substep: a chain swinging fast turns its anchors within a step),
     * K, and its factors in the order found for this step.
     */
    fun refresh(h: Float, substepStart: Boolean) {
        if (n == 0) return
        // Their inertia as they are turned now (a light link swung by a heavy load turns a lot within a step).
        for (k in 0 until n) { if (nodeA[k] >= 0) world.prepareInertia(nodeA[k]); if (nodeB[k] >= 0) world.prepareInertia(nodeB[k]) }
        // The loads they carry, taken once a substep (the biased and relax passes must see the same bodies).
        if (substepStart) for (k in 0 until n) loads[k] = list[k].treeLoad
        stiffen(h)
        for (k in 0 until n) list[k].directJacobian(jac, 12 * start[k])
        buildK(n)
        numeric()
    }

    /**
     * The inverse inertia the joints see, per body: its own, with the geometric stiffness of the loads it carries
     * added to its inertia (h² · force · lever about each joint, after Andrews, Teichmann and Kry, "Geometric
     * stiffness for real-time constrained multibody dynamics", 2017). A light link held taut between heavy loads
     * swings across with waves far faster than a substep (a 200 kg crate on a 7 kg chain), which the rigid rows,
     * taken where the bodies are at the start of the substep, would feed; the stiffness makes it answer them as
     * the tension does. Nothing changes for a body that carries little.
     */
    private fun stiffen(h: Float) {
        if (effective.size < 6 * world.count) effective = DoubleArray(6 * world.count + 96)
        if (load.size < world.count) load = DoubleArray(world.count + 16)
        for (k in 0 until n) { if (nodeA[k] >= 0) load[nodeA[k]] = 0.0; if (nodeB[k] >= 0) load[nodeB[k]] = 0.0 }
        for (k in 0 until n) {
            val j = list[k]; val f = loads[k].toDouble()            // impulse per substep: force × h
            if (nodeA[k] >= 0) load[nodeA[k]] += f * j.frameA.length()
            if (nodeB[k] >= 0) load[nodeB[k]] += f * j.frameB.length()
        }
        val st = world.st
        for (k in 0 until n) for (end in 0..1) {
            val x = if (end == 0) nodeA[k] else nodeB[k]
            if (x < 0) continue
            if (load[x] < 0.0) continue                            // seen through another of its joints
            if (calm[root(x)]) load[x] = 0.0
            val s = x * S + I0; val e = 6 * x
            val a = st[s].toDouble(); val b = st[s + 1].toDouble(); val c = st[s + 2].toDouble()
            val d = st[s + 3].toDouble(); val f = st[s + 4].toDouble(); val i = st[s + 5].toDouble()
            // h² · force · lever, beyond a quarter of its own inertia: below that the substeps follow it well (a
            // ragdoll's limbs under their weight, a door) and the body is left as it is.
            val g = h * load[x] - .25 * 3.0 / (a + d + i)
            if (g <= 0.0) { effective[e] = a; effective[e + 1] = b; effective[e + 2] = c; effective[e + 3] = d; effective[e + 4] = f; effective[e + 5] = i; load[x] = -2.0; continue }
            // I = W⁻¹ (W symmetric: a b c / b d f / c f i), I + g, then back.
            invertSym(a, b, c, d, f, i)
            invertSym(inv[0] + g, inv[1], inv[2], inv[3] + g, inv[4], inv[5] + g)
            for (t in 0..5) effective[e + t] = inv[t]
            load[x] = -1.0                                        // stiffened (and done: its other joints skip it)
        }
        // A joint with a stiffened body is not warm started in the next substep (see Joint.treeStiff).
        for (k in 0 until n) list[k].treeStiff = (nodeA[k] >= 0 && load[nodeA[k]] == -1.0) || (nodeB[k] >= 0 && load[nodeB[k]] == -1.0)
    }
    private var effective = DoubleArray(0); private var load = DoubleArray(0); private var loads = FloatArray(0)
    private val inv = DoubleArray(6)

    /** The inverse of the symmetric matrix (a b c / b d f / c f i) into [inv], in the same order. */
    private fun invertSym(a: Double, b: Double, c: Double, d: Double, f: Double, i: Double) {
        val c00 = d * i - f * f; val c01 = c * f - b * i; val c02 = b * f - c * d
        val c11 = a * i - c * c; val c12 = b * c - a * f; val c22 = a * d - b * b
        val det = a * c00 + b * c01 + c * c02
        val r = if (det != 0.0) 1.0 / det else 0.0
        inv[0] = c00 * r; inv[1] = c01 * r; inv[2] = c02 * r; inv[3] = c11 * r; inv[4] = c12 * r; inv[5] = c22 * r
    }

    /** Each dynamic body's joints, in joint order, and room for its blocks. */
    private fun buildBodies(count: Int) {
        if (++mark == Int.MAX_VALUE) { stamp.fill(0); mark = 1 }
        for (k in 0 until count) for (end in 0..1) {
            val x = if (end == 0) nodeA[k] else nodeB[k]
            if (x < 0) continue
            if (stamp[x] != mark) { stamp[x] = mark; bodyDegree[x] = 0 }
            bodyDegree[x]++
        }
        var at = 0; var blockAt = 0
        mark++
        for (k in 0 until count) for (end in 0..1) {
            val x = if (end == 0) nodeA[k] else nodeB[k]
            if (x < 0 || stamp[x] == mark) continue
            stamp[x] = mark
            val d = bodyDegree[x]
            bodyStart[x] = at; at += d
            blockStart[x] = blockAt; blockAt += d * d * 36
            degree[x] = 0
        }
        if (bodyJoints.size < at) bodyJoints = IntArray(at + 16)
        if (blocks.size < blockAt) blocks = DoubleArray(blockAt + 64)
        for (k in 0 until count) for (end in 0..1) {
            val x = if (end == 0) nodeA[k] else nodeB[k]
            if (x < 0) continue
            posInBody[2 * k + end] = degree[x]
            bodyJoints[bodyStart[x] + degree[x]] = k; degree[x]++
        }
    }

    /** Row at [o] (6 coefficients on body x) times x's inverse mass matrix, dotted with the row at [p]. */
    private fun coupling(o: Int, p: Int, x: Int): Double {
        val im = world.st[x * S + IM].toDouble()
        val e = effective; val s = 6 * x
        val i0 = e[s]; val i1 = e[s + 1]; val i2 = e[s + 2]; val i3 = e[s + 3]; val i4 = e[s + 4]; val i5 = e[s + 5]
        val j = jac
        val ax = j[p + 3]; val ay = j[p + 4]; val az = j[p + 5]
        return im * (j[o] * j[p] + j[o + 1] * j[p + 1] + j[o + 2] * j[p + 2]) +
            j[o + 3] * (i0 * ax + i1 * ay + i2 * az) + j[o + 4] * (i1 * ax + i3 * ay + i4 * az) + j[o + 5] * (i2 * ax + i4 * ay + i5 * az)
    }

    /** Which end of joint k body x is (0: a, 1: b). */
    private fun endOf(k: Int, x: Int) = if (nodeA[k] == x) 0 else 1

    private fun buildK(count: Int) {
        for (k in 0 until count) {
            val m = rows[k]; val d = 36 * k
            for (r in 0 until m) for (c in 0 until m) {
                var v = 0.0
                val o = 12 * (start[k] + r); val p = 12 * (start[k] + c)
                if (nodeA[k] >= 0) v += coupling(o, p, nodeA[k])
                if (nodeB[k] >= 0) v += coupling(o + 6, p + 6, nodeB[k])
                diag[d + 6 * r + c] = v
            }
            // A hair of compliance: redundant rows (two hinges on one door) stay solvable.
            for (r in 0 until m) diag[d + 7 * r] = diag[d + 7 * r] * (1.0 + 1e-6) + 1e-12
        }
        // Off-diagonal blocks, body by body: K_kl = J_kx M_x⁻¹ J_lxᵀ.
        for (k in 0 until count) for (end in 0..1) {
            val x = if (end == 0) nodeA[k] else nodeB[k]
            if (x < 0) continue
            val d = bodyDegree[x]; val pk = posInBody[2 * k + end]
            for (q in 0 until d) {
                val l = bodyJoints[bodyStart[x] + q]
                if (l == k) continue
                val el = endOf(l, x)
                val o = blockStart[x] + (pk * d + q) * 36
                for (r in 0 until rows[k]) for (c in 0 until rows[l])
                    blocks[o + 6 * r + c] = coupling(12 * (start[k] + r) + 6 * end, 12 * (start[l] + c) + 6 * el, x)
            }
        }
    }

    /** Cholesky of joint k's (updated) diagonal block, lower triangle. */
    private fun cholesky(k: Int) {
        val m = rows[k]; val d = 36 * k
        for (r in 0 until m) for (c in 0..r) {
            var s = diag[d + 6 * r + c]
            for (t in 0 until c) s -= chol[d + 6 * r + t] * chol[d + 6 * c + t]
            chol[d + 6 * r + c] = if (r == c) sqrt(max(s, 1e-30)) else s / chol[d + 7 * c]
        }
    }

    /** Solves D_k x = b for the [rows] values of [v] at [o], in place. */
    private fun cholSolve(k: Int, v: DoubleArray, o: Int) {
        val m = rows[k]; val d = 36 * k
        for (r in 0 until m) { var s = v[o + r]; for (t in 0 until r) s -= chol[d + 6 * r + t] * v[o + t]; v[o + r] = s / chol[d + 7 * r] }
        for (r in m - 1 downTo 0) { var s = v[o + r]; for (t in r + 1 until m) s -= chol[d + 6 * t + r] * v[o + t]; v[o + r] = s / chol[d + 7 * r] }
    }

    /**
     * Finds the order in which the joints are eliminated, from the leaves in, and each one's later neighbours
     * (the joints it still touches then, all at one of its bodies, [pivot]); returns how many it placed (all
     * of them in a forest).
     */
    private fun ordering(count: Int): Int {
        for (k in 0 until count) eliminated[k] = false
        if (pivot.size < count) pivot = IntArray(count + 16)
        var head = 0; var tail = 0
        // Ready: one of its bodies is the world or has no other joint left.
        for (k in 0 until count) if (nodeA[k] < 0 || nodeB[k] < 0 || degree[nodeA[k]] == 1 || degree[nodeB[k]] == 1) queue[tail++] = k
        var laters = 0; var factorAt = 0; var done = 0
        while (head < tail) {
            val j = queue[head++]
            if (eliminated[j]) continue
            // The body where its remaining neighbours meet.
            val x = when {
                nodeA[j] < 0 -> nodeB[j]
                nodeB[j] < 0 -> nodeA[j]
                degree[nodeA[j]] == 1 -> nodeB[j]
                degree[nodeB[j]] == 1 -> nodeA[j]
                else -> continue                                         // queued too soon (cannot be, in a forest)
            }
            eliminated[j] = true; order[done++] = j; pivot[j] = x
            laterFrom[j] = laters
            for (q in 0 until bodyDegree[x]) {
                val k = bodyJoints[bodyStart[x] + q]
                if (k == j || eliminated[k]) continue
                if (laterJoint.size <= laters) { laterJoint = laterJoint.copyOf(2 * laters + 16); laterFactor = laterFactor.copyOf(2 * laters + 16) }
                laterJoint[laters] = k; laterFactor[laters] = factorAt; laters++
                factorAt += 36
            }
            laterTo[j] = laters
            // Its bodies lose a joint: a body left with one makes that one ready.
            for (end in 0..1) {
                val y = if (end == 0) nodeA[j] else nodeB[j]
                if (y < 0) continue
                degree[y]--
                if (degree[y] == 1) for (q in 0 until bodyDegree[y]) {
                    val k = bodyJoints[bodyStart[y] + q]
                    if (!eliminated[k]) { if (tail == queue.size) queue = queue.copyOf(2 * tail); queue[tail++] = k }
                }
            }
        }
        if (factors.size < factorAt) factors = DoubleArray(factorAt + 36 * 16)
        return done
    }
    private var pivot = IntArray(0)

    /** K = L D Lᵀ in the order found: each joint's block factored, its later neighbours' factors, what is left updated. */
    private fun numeric() {
        for (o in 0 until n) {
            val j = order[o]; val x = pivot[j]
            cholesky(j)
            val mj = rows[j]
            val d = bodyDegree[x]
            val pj = posInBody[2 * j + endOf(j, x)]
            // L_kj = K_kj D_j⁻¹, row by row (D_j symmetric).
            for (a in laterFrom[j] until laterTo[j]) {
                val k = laterJoint[a]; val f = laterFactor[a]
                val q = posInBody[2 * k + endOf(k, x)]
                val kj = blockStart[x] + (q * d + pj) * 36
                for (r in 0 until rows[k]) {
                    for (c in 0 until mj) tmp[c] = blocks[kj + 6 * r + c]
                    cholSolve(j, tmp, 0)
                    for (c in 0 until mj) factors[f + 6 * r + c] = tmp[c]
                }
            }
            val laters = laterTo[j]
            // What is left at x: K_kl −= L_kj K_jl (the diagonal blocks for k = l).
            for (a in laterFrom[j] until laters) {
                val k = laterJoint[a]; val f = laterFactor[a]; val qk = posInBody[2 * k + endOf(k, x)]
                for (b in laterFrom[j] until laters) {
                    val l = laterJoint[b]; val ql = posInBody[2 * l + endOf(l, x)]
                    val jl = blockStart[x] + (pj * d + ql) * 36
                    val kl = blockStart[x] + (qk * d + ql) * 36
                    for (r in 0 until rows[k]) for (c in 0 until rows[l]) {
                        var s = 0.0
                        for (t in 0 until mj) s += factors[f + 6 * r + t] * blocks[jl + 6 * t + c]
                        if (k == l) diag[36 * k + 6 * r + c] -= s else blocks[kl + 6 * r + c] -= s
                    }
                }
            }
        }
    }

    /** Solves K λ = v in place: forward over the elimination order, the diagonal blocks, then back. */
    private fun solveK(v: DoubleArray) {
        for (o in 0 until n) {
            val j = order[o]; val mj = rows[j]; val sj = start[j]
            for (a in laterFrom[j] until laterTo[j]) {
                val k = laterJoint[a]; val f = laterFactor[a]; val sk = start[k]
                for (r in 0 until rows[k]) { var s = 0.0; for (c in 0 until mj) s += factors[f + 6 * r + c] * v[sj + c]; v[sk + r] -= s }
            }
        }
        for (o in 0 until n) { val j = order[o]; cholSolve(j, v, start[j]) }
        for (o in n - 1 downTo 0) {
            val j = order[o]; val mj = rows[j]; val sj = start[j]
            for (a in laterFrom[j] until laterTo[j]) {
                val k = laterJoint[a]; val f = laterFactor[a]; val sk = start[k]
                for (c in 0 until mj) { var s = 0.0; for (r in 0 until rows[k]) s += factors[f + 6 * r + c] * v[sk + r]; v[sj + c] -= s }
            }
        }
    }

    /** The rows' speeds J v into [out] (kinematic bodies' velocities count, the world's are zero). */
    private fun rowSpeeds(out: DoubleArray) {
        val st = world.st
        for (k in 0 until n) {
            val a = bodyA[k]; val b = bodyB[k]
            for (r in 0 until rows[k]) {
                val o = 12 * (start[k] + r)
                var s = 0.0
                if (a >= 0) { val p = a * S; for (c in 0..5) s += jac[o + c] * st[p + c] }
                val p = b * S; for (c in 0..5) s += jac[o + 6 + c] * st[p + c]
                out[start[k] + r] = s
            }
        }
    }

    /** Applies the impulses λ (one per row) to the dynamic bodies: M⁻¹ Jᵀ λ. */
    private fun apply(lambda: DoubleArray) {
        val st = world.st
        for (k in 0 until n) for (end in 0..1) {
            val x = if (end == 0) nodeA[k] else nodeB[k]
            if (x < 0) continue
            var lx = 0.0; var ly = 0.0; var lz = 0.0; var ax = 0.0; var ay = 0.0; var az = 0.0
            for (r in 0 until rows[k]) {
                val o = 12 * (start[k] + r) + 6 * end; val l = lambda[start[k] + r]
                lx += jac[o] * l; ly += jac[o + 1] * l; lz += jac[o + 2] * l; ax += jac[o + 3] * l; ay += jac[o + 4] * l; az += jac[o + 5] * l
            }
            val s = x * S; val im = st[s + IM].toDouble()
            st[s] = (st[s] + im * lx).toFloat(); st[s + 1] = (st[s + 1] + im * ly).toFloat(); st[s + 2] = (st[s + 2] + im * lz).toFloat()
            val e = effective; val i = 6 * x
            st[s + 3] = (st[s + 3] + (e[i] * ax + e[i + 1] * ay + e[i + 2] * az)).toFloat()
            st[s + 4] = (st[s + 4] + (e[i + 1] * ax + e[i + 3] * ay + e[i + 4] * az)).toFloat()
            st[s + 5] = (st[s + 5] + (e[i + 2] * ax + e[i + 4] * ay + e[i + 5] * az)).toFloat()
        }
    }

    /**
     * One pass. With [bias] the rows' position errors are corrected at the joints' stiffness ([biasRate],
     * [massScale]); without, the velocities are brought to keep the rows exactly. The impulses join each
     * joint's own, for the next warm start and its [Joint.force].
     */
    fun solve(bias: Boolean, biasRate: Float, massScale: Float) {
        if (n == 0) return
        rowSpeeds(rhs)
        if (bias) {
            for (k in 0 until n) list[k].directError(err, start[k])
            for (r in 0 until total) rhs[r] += biasRate * err[r]
        }
        solveK(rhs)
        val scale = if (bias) -massScale.toDouble() else -1.0
        for (r in 0 until total) rhs[r] *= scale
        apply(rhs)
        for (k in 0 until n) list[k].directAccumulate(rhs, start[k])
    }


}
