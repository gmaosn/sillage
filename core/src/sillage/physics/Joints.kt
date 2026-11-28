package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Joints, solved with the contacts in each substep (warm start, solve with soft correction, relax),
// after Box2D v3 (E. Catto) carried to 3D. A joint ties body b to body a, or to the world when a is
// null. Positions are measured as the contacts measure them: from the start of the step, with each
// body's displacement and rotation since (Δp, Δq), so a joint stays right within a step.

/** Softness of a constraint: Box2D v3's soft step (bias rate, mass scale, impulse scale). */
internal class Softness {
    var biasRate = 0f; var massScale = 1f; var impulseScale = 0f
    fun set(hertz: Float, dampingRatio: Float, h: Float): Softness {
        if (hertz <= 0f) { biasRate = 0f; massScale = 1f; impulseScale = 0f; return this }
        val omega = 2f * PI.toFloat() * hertz
        val a1 = 2f * dampingRatio + h * omega; val a2 = h * omega * a1; val a3 = 1f / (1f + a2)
        biasRate = omega / a1; massScale = a2 * a3; impulseScale = a3
        return this
    }
}

/**
 * A joint between two bodies, or between a body and the world ([a] null). Made by the world's `add…`
 * functions ([PhysicsWorld.addBall], [PhysicsWorld.addHinge]…), removed by [PhysicsWorld.removeJoint]
 * or with one of its bodies. The anchors are given in world coordinates where the bodies are when joined.
 */
sealed class Joint(internal val world: PhysicsWorld, val a: Body?, val b: Body, anchorA: Vec3, anchorB: Vec3,
                   /** Whether the two bodies still collide with each other (false: they pass through each other). */
                   val collideConnected: Boolean) {
    internal val ia = a?.id ?: -1
    internal val ib = b.id
    /** The anchors in each body's frame (in the world for the world). */
    internal val localA = local(ia, anchorA)
    internal val localB = local(ib, anchorB)
    /** A disabled joint holds nothing (a door off its hinges, a broken rope); it can be enabled again. */
    var enabled = true
        set(v) { field = v; if (v) world.wakeJoint(this) }
    /** True once removed from the world. */
    var isRemoved = false
        internal set

    /** The anchor on a, then on b, where the bodies are now. */
    val anchorA: Vec3 get() = worldPoint(ia, localA)
    val anchorB: Vec3 get() = worldPoint(ib, localB)

    /**
     * The force the joint applied during the last step (N, its point or length part), to break it when
     * overloaded: `if (joint.force > 5000f) world.removeJoint(joint)`.
     */
    val force: Float get() = lastImpulse * invH
    internal var lastImpulse = 0f
    internal var invH = 0f
    /** Set while the world runs the rigid velocity pass: springs and motors sit it out. */
    internal var rigid = false

    internal fun local(i: Int, p: Vec3): Vec3 = if (i < 0) p else rot(i).conjugate().rotate(p - centre(i))
    internal fun worldPoint(i: Int, l: Vec3) = if (i < 0) l else centre(i) + rot(i).rotate(l)

    // ---- measured at the start of the step

    internal var frameA = Vec3.ZERO; internal var frameB = Vec3.ZERO       // anchors from each centre, in world axes
    internal var deltaCenter = Vec3.ZERO                                    // centre of b minus centre of a
    internal val soft = Softness()
    internal var h = 0f

    internal open fun prepare(h: Float, hertz: Float, dampingRatio: Float) {
        this.h = h; invH = 1f / h
        frameA = if (ia < 0) localA else rot(ia).rotate(localA)
        frameB = rot(ib).rotate(localB)
        deltaCenter = centre(ib) - centre(ia)
        soft.set(hertz, dampingRatio, h)
    }
    internal abstract fun warmStart()
    internal abstract fun solve(useBias: Boolean, invH: Float)

    // ---- rigid rows solved by the joint trees ([JointTrees]): this step, the iterative passes leave them out

    /** Set for the steps in which [JointTrees] solves this joint's rigid rows. */
    internal var direct = false
    /** Its rigid rows (anchors together, axes aligned, rotations locked, a rod's length), 0 for none. */
    internal open val directRows: Int get() = 0
    /** What its rigid rows carry as a force: the size of their accumulated impulse (N·s per substep), the anchors' or the sides'. */
    internal open val directForce: Float get() = 0f
    /**
     * Whether a body of it had its inertia stiffened by the trees in the last substep (see [JointTrees]): then its
     * rigid rows are not warm started ([directReset]), as the trees solve them whole and the warm start would give
     * them through the body's own inertia.
     */
    internal var treeStiff = false
    /** Forgets the rigid rows' impulses before a substep's warm start (see [treeStiff]). */
    internal open fun directReset() {}
    /** [directForce] as the last substep left it, taken before the warm start (the trees' geometric stiffness). */
    internal var treeLoad = 0f
    /** Whether a limit of its own (beside the rigid rows) is reached, or close (by a margin and what it may turn in [dt]). */
    internal open fun nearLimits(dt: Float): Boolean = false
    /** Its rows where the bodies are now: per row 12 values, linear and angular parts on a, then on b. */
    internal open fun directJacobian(out: DoubleArray, at: Int) {}
    /** Their position errors now (the row speeds integrate them). */
    internal open fun directError(out: DoubleArray, at: Int) {}
    /** Adds the trees' impulses (one per row) to its own. */
    internal open fun directAccumulate(lambda: DoubleArray, at: Int) {}

    /** Writes one row at [o]: linear and angular parts on a (la, aa), then on b (lb, ab). */
    internal fun jacobianRow(out: DoubleArray, o: Int, la: Vec3, aa: Vec3, lb: Vec3, ab: Vec3) {
        out[o] = la.x.toDouble(); out[o + 1] = la.y.toDouble(); out[o + 2] = la.z.toDouble()
        out[o + 3] = aa.x.toDouble(); out[o + 4] = aa.y.toDouble(); out[o + 5] = aa.z.toDouble()
        out[o + 6] = lb.x.toDouble(); out[o + 7] = lb.y.toDouble(); out[o + 8] = lb.z.toDouble()
        out[o + 9] = ab.x.toDouble(); out[o + 10] = ab.y.toDouble(); out[o + 11] = ab.z.toDouble()
    }
    // The anchors from each centre as turned since the step began (dq · frame), in plain floats: (rax, ray, raz), (rbx…).
    private var rax = 0.0; private var ray = 0.0; private var raz = 0.0; private var rbx = 0.0; private var rby = 0.0; private var rbz = 0.0
    private fun turnedAnchors() {
        val s = world.st
        if (ia < 0) { rax = frameA.x.toDouble(); ray = frameA.y.toDouble(); raz = frameA.z.toDouble() }
        else { val o = ia * SS + 16; turn(s[o], s[o + 1], s[o + 2], s[o + 3], frameA); rax = tx; ray = ty; raz = tz }
        val o = ib * SS + 16; turn(s[o], s[o + 1], s[o + 2], s[o + 3], frameB); rbx = tx; rby = ty; rbz = tz
    }
    private var tx = 0.0; private var ty = 0.0; private var tz = 0.0
    /** v turned by the unit quaternion (x, y, z, w) into (tx, ty, tz): v + w t + q × t, t = 2 q × v (as [Quat.rotate]). */
    private fun turn(x: Float, y: Float, z: Float, w: Float, v: Vec3) {
        val cx = 2f * (y * v.z - z * v.y); val cy = 2f * (z * v.x - x * v.z); val cz = 2f * (x * v.y - y * v.x)
        tx = (v.x + w * cx + (y * cz - z * cy)).toDouble(); ty = (v.y + w * cy + (z * cx - x * cz)).toDouble(); tz = (v.z + w * cz + (x * cy - y * cx)).toDouble()
    }

    /** The three rows that keep the anchors together, at row [first] of [out] (from [at]): ± e_k, and ∓ r × e_k on each side. */
    internal fun pointRows(out: DoubleArray, at: Int, first: Int) {
        turnedAnchors()
        val o = at + 12 * first
        out.fill(0.0, o, o + 36)
        // Row x: a (−1, 0, 0 | 0, −raz, ray), b (1, 0, 0 | 0, rbz, −rby); rows y and z likewise.
        out[o] = -1.0; out[o + 4] = -raz; out[o + 5] = ray; out[o + 6] = 1.0; out[o + 10] = rbz; out[o + 11] = -rby
        val p = o + 12
        out[p + 1] = -1.0; out[p + 3] = raz; out[p + 5] = -rax; out[p + 7] = 1.0; out[p + 9] = -rbz; out[p + 11] = rbx
        val q = o + 24
        out[q + 2] = -1.0; out[q + 3] = -ray; out[q + 4] = rax; out[q + 8] = 1.0; out[q + 9] = rby; out[q + 10] = -rbx
    }
    /** The anchors' separation now (b's minus a's), into [out] at [at]. */
    internal fun pointErrorInto(out: DoubleArray, at: Int) {
        turnedAnchors()
        val s = world.st
        var x = rbx - rax + deltaCenter.x; var y = rby - ray + deltaCenter.y; var z = rbz - raz + deltaCenter.z
        val b = ib * SS + 13; x += s[b]; y += s[b + 1]; z += s[b + 2]
        if (ia >= 0) { val a = ia * SS + 13; x -= s[a]; y -= s[a + 1]; z -= s[a + 2] }
        out[at] = x; out[at + 1] = y; out[at + 2] = z
    }
    /** The three rows that lock the rotation, at row [first]. */
    internal fun lockRows(out: DoubleArray, at: Int, first: Int) {
        for (k in 0..2) {
            val e = when (k) { 0 -> Vec3.X; 1 -> Vec3.Y; else -> Vec3.Z }
            jacobianRow(out, at + 12 * (first + k), Vec3.ZERO, -e, Vec3.ZERO, e)
        }
    }
    /** How far b's rotation relative to a is from [relative0], as a small rotation in the world (as [solveLock] measures it). */
    internal fun lockError(relative0: Quat): Vec3 {
        val qa = rot(ia)
        var e = qa.conjugate() * rot(ib) * relative0.conjugate()
        if (e.w < 0f) e = Quat(-e.x, -e.y, -e.z, -e.w)
        return qa.rotate(Vec3(e.x, e.y, e.z) * 2f)
    }

    // ---- body state in the world's solver arrays; a = −1 is the world: still, without mass

    /** Rotation now (at the step's start in prepare, as integrated so far in the substeps). */
    internal fun rot(i: Int) = if (i < 0) Quat.IDENTITY else Quat(world.qx[i], world.qy[i], world.qz[i], world.qw[i])
    internal fun centre(i: Int) = if (i < 0) Vec3.ZERO else Vec3(world.px[i], world.py[i], world.pz[i])
    internal fun im(i: Int) = if (i < 0) 0f else world.st[i * SS + 6]
    internal fun v(i: Int) = if (i < 0) Vec3.ZERO else world.st.let { s -> Vec3(s[i * SS], s[i * SS + 1], s[i * SS + 2]) }
    internal fun w(i: Int) = if (i < 0) Vec3.ZERO else world.st.let { s -> Vec3(s[i * SS + 3], s[i * SS + 4], s[i * SS + 5]) }
    /** Displacement and rotation since the step began. */
    internal fun dp(i: Int) = if (i < 0) Vec3.ZERO else world.st.let { s -> Vec3(s[i * SS + 13], s[i * SS + 14], s[i * SS + 15]) }
    internal fun dq(i: Int) = if (i < 0) Quat.IDENTITY else world.st.let { s -> Quat(s[i * SS + 16], s[i * SS + 17], s[i * SS + 18], s[i * SS + 19]) }
    /** World inverse inertia times [x] (zero for a body that does not turn). */
    internal fun ii(i: Int, x: Vec3): Vec3 {
        if (i < 0 || im(i) == 0f) return Vec3.ZERO
        val s = world.st; val k = i * SS + 7
        return Vec3(s[k] * x.x + s[k + 1] * x.y + s[k + 2] * x.z, s[k + 1] * x.x + s[k + 3] * x.y + s[k + 4] * x.z,
            s[k + 2] * x.x + s[k + 4] * x.y + s[k + 5] * x.z)
    }
    // apply, push, solvePoint and solveLock run for every joint in every pass of every substep: they work on
    // floats, with the very operations of the vector functions they were written with, in the same order (the
    // results are the same to the bit), and make no vector but the impulse they return.

    /** Applies the linear impulse [p] at the levers [ra], [rb] and the angular impulse [l]: −(p, l) on a, +(p, l) on b. */
    internal fun apply(p: Vec3, ra: Vec3, rb: Vec3, l: Vec3 = Vec3.ZERO) =
        apply(p.x, p.y, p.z, ra.x, ra.y, ra.z, rb.x, rb.y, rb.z, l.x, l.y, l.z)

    /** [apply] on floats: p, then the levers ra and rb, then l. */
    private fun apply(px: Float, py: Float, pz: Float, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, lx: Float, ly: Float, lz: Float) {
        // (r × p) + l for each side.
        val tax = (ay * pz - az * py) + lx; val tay = (az * px - ax * pz) + ly; val taz = (ax * py - ay * px) + lz
        val tbx = (by * pz - bz * py) + lx; val tby = (bz * px - bx * pz) + ly; val tbz = (bx * py - by * px) + lz
        push(ia, -1f, px, py, pz, tax, tay, taz)
        push(ib, 1f, px, py, pz, tbx, tby, tbz)
    }
    internal fun applyAngular(l: Vec3) { push(ia, -1f, 0f, 0f, 0f, l.x, l.y, l.z); push(ib, 1f, 0f, 0f, 0f, l.x, l.y, l.z) }
    /** Adds sign × (p, torque) to body [i]'s velocities. */
    internal fun push(i: Int, sign: Float, p: Vec3, torque: Vec3) = push(i, sign, p.x, p.y, p.z, torque.x, torque.y, torque.z)

    private fun push(i: Int, sign: Float, px: Float, py: Float, pz: Float, tx: Float, ty: Float, tz: Float) {
        if (i < 0) return
        val m = im(i)
        if (m == 0f) return
        val s = world.st; val o = i * SS
        s[o] += sign * px * m; s[o + 1] += sign * py * m; s[o + 2] += sign * pz * m
        // ii(i, torque), written out.
        val k = i * SS + 7
        val dx = s[k] * tx + s[k + 1] * ty + s[k + 2] * tz; val dy = s[k + 1] * tx + s[k + 3] * ty + s[k + 4] * tz
        val dz = s[k + 2] * tx + s[k + 4] * ty + s[k + 5] * tz
        s[o + 3] += sign * dx; s[o + 4] += sign * dy; s[o + 5] += sign * dz
    }

    // What the float helpers below found.
    private var fx = 0f; private var fy = 0f; private var fz = 0f
    /** The three columns of a 3 × 3 matrix being solved (Mat3's c0, c1, c2). */
    private val k3 = FloatArray(9)

    /** dq(i).rotate(v) into fx, fy, fz. */
    private fun turnedBy(i: Int, v: Vec3) {
        val x: Float; val y: Float; val z: Float; val w: Float
        if (i < 0) { x = 0f; y = 0f; z = 0f; w = 1f }
        else { val s = world.st; val o = i * SS; x = s[o + 16]; y = s[o + 17]; z = s[o + 18]; w = s[o + 19] }
        val tx = 2f * (y * v.z - z * v.y); val ty = 2f * (z * v.x - x * v.z); val tz = 2f * (x * v.y - y * v.x)
        fx = v.x + w * tx + (y * tz - z * ty); fy = v.y + w * ty + (z * tx - x * tz); fz = v.z + w * tz + (x * ty - y * tx)
    }

    /** ii(i, (x, y, z)) into fx, fy, fz. */
    private fun inertiaTimes(i: Int, x: Float, y: Float, z: Float) {
        if (i < 0 || im(i) == 0f) { fx = 0f; fy = 0f; fz = 0f; return }
        val s = world.st; val k = i * SS + 7
        fx = s[k] * x + s[k + 1] * y + s[k + 2] * z; fy = s[k + 1] * x + s[k + 3] * y + s[k + 4] * z; fz = s[k + 2] * x + s[k + 4] * y + s[k + 5] * z
    }

    /** Mat3(k3).solve(r) into fx, fy, fz (Cramer; zero when singular). */
    private fun solve3(rx: Float, ry: Float, rz: Float) {
        val k = k3
        val c0x = k[0]; val c0y = k[1]; val c0z = k[2]; val c1x = k[3]; val c1y = k[4]; val c1z = k[5]; val c2x = k[6]; val c2y = k[7]; val c2z = k[8]
        // c1 × c2, then det = c0 · (c1 × c2)
        val ux = c1y * c2z - c1z * c2y; val uy = c1z * c2x - c1x * c2z; val uz = c1x * c2y - c1y * c2x
        val det = c0x * ux + c0y * uy + c0z * uz
        val l0 = sqrt(c0x * c0x + c0y * c0y + c0z * c0z); val l1 = sqrt(c1x * c1x + c1y * c1y + c1z * c1z); val l2 = sqrt(c2x * c2x + c2y * c2y + c2z * c2z)
        if (abs(det) <= 1e-10f * l0 * l1 * l2 || det == 0f) { fx = 0f; fy = 0f; fz = 0f; return }
        val inv = 1f / det
        // r × c2 and c1 × r
        val vx = ry * c2z - rz * c2y; val vy = rz * c2x - rx * c2z; val vz = rx * c2y - ry * c2x
        val wx = c1y * rz - c1z * ry; val wy = c1z * rx - c1x * rz; val wz = c1x * ry - c1y * rx
        fx = (rx * ux + ry * uy + rz * uz) * inv; fy = (c0x * vx + c0y * vy + c0z * vz) * inv; fz = (c0x * wx + c0y * wy + c0z * wz) * inv
    }

    /** Column [col] of pointMass(ra, rb) (unit vector e = X, Y or Z) into k3: e * m − ra × ii(ia, ra × e) − rb × ii(ib, rb × e). */
    private fun pointMassColumn(col: Int, ex: Float, ey: Float, ez: Float, m: Float,
                                ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
        inertiaTimes(ia, ay * ez - az * ey, az * ex - ax * ez, ax * ey - ay * ex)
        val iax = fx; val iay = fy; val iaz = fz
        inertiaTimes(ib, by * ez - bz * ey, bz * ex - bx * ez, bx * ey - by * ex)
        val ibx = fx; val iby = fy; val ibz = fz
        val o = 3 * col
        k3[o] = ex * m - (ay * iaz - az * iay) - (by * ibz - bz * iby)
        k3[o + 1] = ey * m - (az * iax - ax * iaz) - (bz * ibx - bx * ibz)
        k3[o + 2] = ez * m - (ax * iay - ay * iax) - (bx * iby - by * ibx)
    }

    /** Both anchors at one place (3 rows); returns the impulse applied. */
    internal fun solvePoint(useBias: Boolean, accumulated: Vec3): Vec3 {
        turnedBy(ia, frameA); val ax = fx; val ay = fy; val az = fz
        turnedBy(ib, frameB); val bx = fx; val by = fy; val bz = fz
        val s = world.st
        // v(i) and w(i): zero for the world.
        val oa = ia * SS; val ob = ib * SS
        val vax = if (ia < 0) 0f else s[oa]; val vay = if (ia < 0) 0f else s[oa + 1]; val vaz = if (ia < 0) 0f else s[oa + 2]
        val wax = if (ia < 0) 0f else s[oa + 3]; val way = if (ia < 0) 0f else s[oa + 4]; val waz = if (ia < 0) 0f else s[oa + 5]
        val vbx = if (ib < 0) 0f else s[ob]; val vby = if (ib < 0) 0f else s[ob + 1]; val vbz = if (ib < 0) 0f else s[ob + 2]
        val wbx = if (ib < 0) 0f else s[ob + 3]; val wby = if (ib < 0) 0f else s[ob + 4]; val wbz = if (ib < 0) 0f else s[ob + 5]
        // cdot = v(ib) + (w(ib) × rb) − v(ia) − (w(ia) × ra)
        val cx = ((vbx + (wby * bz - wbz * by)) - vax) - (way * az - waz * ay)
        val cy = ((vby + (wbz * bx - wbx * bz)) - vay) - (waz * ax - wax * az)
        val cz = ((vbz + (wbx * by - wby * bx)) - vaz) - (wax * ay - way * ax)
        var gx = 0f; var gy = 0f; var gz = 0f; var massScale = 1f; var impulseScale = 0f
        if (useBias) {
            // separation = dp(ib) − dp(ia) + (rb − ra) + deltaCenter; bias = separation * biasRate
            val pax = if (ia < 0) 0f else s[oa + 13]; val pay = if (ia < 0) 0f else s[oa + 14]; val paz = if (ia < 0) 0f else s[oa + 15]
            val pbx = if (ib < 0) 0f else s[ob + 13]; val pby = if (ib < 0) 0f else s[ob + 14]; val pbz = if (ib < 0) 0f else s[ob + 15]
            val d = deltaCenter; val rate = soft.biasRate
            gx = (((pbx - pax) + (bx - ax)) + d.x) * rate; gy = (((pby - pay) + (by - ay)) + d.y) * rate; gz = (((pbz - paz) + (bz - az)) + d.z) * rate
            massScale = soft.massScale; impulseScale = soft.impulseScale
        }
        val m = im(ia) + im(ib)
        pointMassColumn(0, 1f, 0f, 0f, m, ax, ay, az, bx, by, bz)
        pointMassColumn(1, 0f, 1f, 0f, m, ax, ay, az, bx, by, bz)
        pointMassColumn(2, 0f, 0f, 1f, m, ax, ay, az, bx, by, bz)
        solve3(-(cx + gx), -(cy + gy), -(cz + gz))
        val ix = fx * massScale - accumulated.x * impulseScale; val iy = fy * massScale - accumulated.y * impulseScale
        val iz = fz * massScale - accumulated.z * impulseScale
        apply(ix, iy, iz, ax, ay, az, bx, by, bz, 0f, 0f, 0f)
        return Vec3(ix, iy, iz)
    }

    /**
     * The linear impulse at the anchors (+ on b, − on a) that keeps them together while the angular impulse
     * [l] acts (+[l] on b, −[l] on a), given the point constraint's K [k]: the bodies then turn about the
     * anchor, not each about its centre of mass. A hinge's limits, motor, spring and axes act this way, so
     * that a door slammed against its stop does not tear at its hinges (the Schur complement of the point
     * rows, instead of leaving the point rows to catch up in the next passes).
     */
    internal fun anchorHold(l: Vec3, ra: Vec3, rb: Vec3, k: Mat3): Vec3 =
        k.solve(-((ii(ib, l) cross rb) + (ii(ia, l) cross ra)))

    /** Change of b's angular velocity relative to a under the angular impulse [l] with its anchor hold [hold]. */
    internal fun coupledTurn(l: Vec3, hold: Vec3, ra: Vec3, rb: Vec3): Vec3 =
        ii(ib, l) + ii(ia, l) + ii(ib, rb cross hold) + ii(ia, ra cross hold)

    /** Applies the angular impulse [l] with its anchor hold [hold]; returns the hold, to be counted with the point's impulse. */
    internal fun applyCoupled(l: Vec3, hold: Vec3, ra: Vec3, rb: Vec3): Vec3 { apply(hold, ra, rb, l); return hold }

    /** K of the point constraint: (mA + mB)·1 − [rA]× IA [rA]× − [rB]× IB [rB]×. */
    internal fun pointMass(ra: Vec3, rb: Vec3): Mat3 {
        val m = im(ia) + im(ib)
        fun col(e: Vec3) = e * m - (ra cross ii(ia, ra cross e)) - (rb cross ii(ib, rb cross e))
        return Mat3(col(Vec3.X), col(Vec3.Y), col(Vec3.Z))
    }

    /** Inverse inertia about [axis] of the two bodies together, inverted (0: neither turns). */
    internal fun axialMass(axis: Vec3): Float = (axis dot (ii(ia, axis) + ii(ib, axis))).let { if (it > 0f) 1f / it else 0f }

    /**
     * Keeps b's rotation relative to a at [relative0] (3 rows), with the accumulated angular impulse
     * [accumulated]; returns the impulse applied.
     */
    internal fun solveLock(useBias: Boolean, relative0: Quat, accumulated: Vec3): Vec3 {
        val s = world.st; val oa = ia * SS; val ob = ib * SS
        // cdot = w(ib) − w(ia)
        var cx = (if (ib < 0) 0f else s[ob + 3]) - (if (ia < 0) 0f else s[oa + 3])
        var cy = (if (ib < 0) 0f else s[ob + 4]) - (if (ia < 0) 0f else s[oa + 4])
        var cz = (if (ib < 0) 0f else s[ob + 5]) - (if (ia < 0) 0f else s[oa + 5])
        var massScale = 1f; var impulseScale = 0f
        if (useBias) {
            // b = a · E · relative0: E, the error in a's frame, turned into the world. E = (qa* · qb) · relative0*.
            val q = world
            val ax = if (ia < 0) 0f else q.qx[ia]; val ay = if (ia < 0) 0f else q.qy[ia]; val az = if (ia < 0) 0f else q.qz[ia]; val aw = if (ia < 0) 1f else q.qw[ia]
            val bx = if (ib < 0) 0f else q.qx[ib]; val by = if (ib < 0) 0f else q.qy[ib]; val bz = if (ib < 0) 0f else q.qz[ib]; val bw = if (ib < 0) 1f else q.qw[ib]
            // qa* = (−ax, −ay, −az, aw), times qb (Quat.times: w·q.x + x·q.w + y·q.z − z·q.y …)
            val nx = -ax; val ny = -ay; val nz = -az
            val px = aw * bx + nx * bw + ny * bz - nz * by
            val py = aw * by - nx * bz + ny * bw + nz * bx
            val pz = aw * bz + nx * by - ny * bx + nz * bw
            val pw = aw * bw - nx * bx - ny * by - nz * bz
            // times relative0* = (−r.x, −r.y, −r.z, r.w)
            val rx = -relative0.x; val ry = -relative0.y; val rz = -relative0.z; val rw = relative0.w
            var ex = pw * rx + px * rw + py * rz - pz * ry
            var ey = pw * ry - px * rz + py * rw + pz * rx
            var ez = pw * rz + px * ry - py * rx + pz * rw
            val ew = pw * rw - px * rx - py * ry - pz * rz
            if (ew < 0f) { ex = -ex; ey = -ey; ez = -ez }
            // cdot += qa.rotate(Vec3(e.x, e.y, e.z) * 2f) * biasRate
            val vx = ex * 2f; val vy = ey * 2f; val vz = ez * 2f
            val tx = 2f * (ay * vz - az * vy); val ty = 2f * (az * vx - ax * vz); val tz = 2f * (ax * vy - ay * vx)
            val rate = soft.biasRate
            cx += (vx + aw * tx + (ay * tz - az * ty)) * rate; cy += (vy + aw * ty + (az * tx - ax * tz)) * rate; cz += (vz + aw * tz + (ax * ty - ay * tx)) * rate
            massScale = soft.massScale; impulseScale = soft.impulseScale
        }
        // angularMass() = Mat3(ii(ia, X) + ii(ib, X), ii(ia, Y) + ii(ib, Y), ii(ia, Z) + ii(ib, Z)), then solve(−cdot).
        for (col in 0..2) {
            val ex = if (col == 0) 1f else 0f; val ey = if (col == 1) 1f else 0f; val ez = if (col == 2) 1f else 0f
            inertiaTimes(ia, ex, ey, ez); val ux = fx; val uy = fy; val uz = fz
            inertiaTimes(ib, ex, ey, ez)
            k3[3 * col] = ux + fx; k3[3 * col + 1] = uy + fy; k3[3 * col + 2] = uz + fz
        }
        solve3(-cx, -cy, -cz)
        val lx = fx * massScale - accumulated.x * impulseScale; val ly = fy * massScale - accumulated.y * impulseScale
        val lz = fz * massScale - accumulated.z * impulseScale
        push(ia, -1f, 0f, 0f, 0f, lx, ly, lz); push(ib, 1f, 0f, 0f, 0f, lx, ly, lz)
        return Vec3(lx, ly, lz)
    }
}

/** A ball joint: the two anchors stay together, the bodies turn freely about it (chains, pendulums, ragdolls). */
class BallJoint internal constructor(world: PhysicsWorld, a: Body?, b: Body, anchor: Vec3, collideConnected: Boolean) :
    Joint(world, a, b, anchor, anchor, collideConnected) {
    private var impulse = Vec3.ZERO
    override fun prepare(h: Float, hertz: Float, dampingRatio: Float) { super.prepare(h, hertz, dampingRatio); lastImpulse = impulse.length() }
    override fun warmStart() = apply(impulse, dq(ia).rotate(frameA), dq(ib).rotate(frameB))
    override fun solve(useBias: Boolean, invH: Float) { if (!direct) impulse += solvePoint(useBias, impulse) }
    override val directRows: Int get() = 3
    override val directForce: Float get() = impulse.length()
    override fun directReset() { impulse = Vec3.ZERO }
    override fun directJacobian(out: DoubleArray, at: Int) = pointRows(out, at, 0)
    override fun directError(out: DoubleArray, at: Int) = pointErrorInto(out, at)
    override fun directAccumulate(lambda: DoubleArray, at: Int) { impulse += readVec(lambda, at) }
}

internal fun writeVec(out: DoubleArray, at: Int, v: Vec3) { out[at] = v.x.toDouble(); out[at + 1] = v.y.toDouble(); out[at + 2] = v.z.toDouble() }
internal fun readVec(v: DoubleArray, at: Int) = Vec3(v[at].toFloat(), v[at + 1].toFloat(), v[at + 2].toFloat())

/**
 * A hinge: the anchors stay together and the bodies turn about one axis only (doors, wheels, levers,
 * see-saws). Optional limits ([limits]), a motor ([motor]) and a spring back to an angle ([spring]).
 */
class HingeJoint internal constructor(world: PhysicsWorld, a: Body?, b: Body, anchor: Vec3, axis: Vec3, collideConnected: Boolean) :
    Joint(world, a, b, anchor, anchor, collideConnected) {
    private val axisA = localDir(ia, axis.normalized()); private val axisB = localDir(ib, axis.normalized())
    private val refA = localDir(ia, perpendicular(axis.normalized())); private val refB = localDir(ib, perpendicular(axis.normalized()))
    var lowerAngle = 0f; private set
    var upperAngle = 0f; private set
    var limitsEnabled = false; private set
    var motorSpeed = 0f; private set
    var maxMotorTorque = 0f; private set
    var motorEnabled = false; private set
    var springHertz = 0f; private set
    var springDampingRatio = 0f; private set
    var springAngle = 0f; private set
    private var impulse = Vec3.ZERO
    private var angular = Vec3.ZERO
    private var lowerImpulse = 0f; private var upperImpulse = 0f; private var motorImpulse = 0f; private var springImpulse = 0f
    private var axisA0 = Vec3.Y; private var axisB0 = Vec3.Y; private var refA0 = Vec3.X; private var refB0 = Vec3.X
    private val spring = Softness()

    private fun localDir(i: Int, d: Vec3): Vec3 = (if (i < 0) d else rot(i).conjugate().rotate(d)).normalized()

    /** The angle of b about the axis relative to a, from where they were when joined (radians, −π..π). */
    val angle: Float get() = angleOf(rot(ia).rotate(axisA), rot(ia).rotate(refA), rot(ib).rotate(refB))
    /** Relative turning speed about the axis (rad/s). */
    val speed: Float get() = (w(ib) - w(ia)) dot rot(ia).rotate(axisA)
    /** The axis in the world now (as a sees it). */
    val axis: Vec3 get() = rot(ia).rotate(axisA)

    private fun angleOf(axis: Vec3, ra: Vec3, rb: Vec3) = atan2((ra cross rb) dot axis, ra dot rb)

    /** Keeps the angle within [lower]..[upper] radians (null, null: no limits). */
    fun limits(lower: Float?, upper: Float?) {
        require(lower == null || upper == null || lower <= upper) { "The lower limit must not exceed the upper one" }
        limitsEnabled = lower != null && upper != null; lowerAngle = lower ?: 0f; upperAngle = upper ?: 0f
        world.wakeJoint(this)
    }
    /** Drives the turning speed towards [speed] rad/s with at most [maxTorque] N·m (null: no motor). */
    fun motor(speed: Float?, maxTorque: Float = 0f) {
        motorEnabled = speed != null; motorSpeed = speed ?: 0f; maxMotorTorque = maxTorque; world.wakeJoint(this)
    }
    /** Pulls back to [angle] like a spring of [hertz] Hz (0: none), damped by [dampingRatio] (1: no overshoot). */
    fun spring(hertz: Float, dampingRatio: Float = 1f, angle: Float = 0f) {
        springHertz = hertz; springDampingRatio = dampingRatio; springAngle = angle; world.wakeJoint(this)
    }

    override fun prepare(h: Float, hertz: Float, dampingRatio: Float) {
        super.prepare(h, hertz, dampingRatio)
        axisA0 = rot(ia).rotate(axisA); axisB0 = rot(ib).rotate(axisB); refA0 = rot(ia).rotate(refA); refB0 = rot(ib).rotate(refB)
        spring.set(springHertz, springDampingRatio, h)
        if (!motorEnabled) motorImpulse = 0f
        if (!limitsEnabled) { lowerImpulse = 0f; upperImpulse = 0f }
        if (springHertz <= 0f) springImpulse = 0f
        lastImpulse = impulse.length()
    }

    override fun warmStart() {
        val axis = dq(ia).rotate(axisA0)
        apply(impulse, dq(ia).rotate(frameA), dq(ib).rotate(frameB), angular + axis * (springImpulse + motorImpulse + lowerImpulse - upperImpulse))
    }

    override fun solve(useBias: Boolean, invH: Float) {
        val qa = dq(ia); val qb = dq(ib)
        val axis = qa.rotate(axisA0)
        val angle = angleOf(axis, qa.rotate(refA0), qb.rotate(refB0))
        fun cdot() = (w(ib) - w(ia)) dot axis
        // The angular rows turn the bodies about the anchor: each comes with the linear impulse that keeps the
        // anchors together (Joint.anchorHold), and its mass is the inertia about the anchor. Solved about each
        // centre of mass, a limit stopping a door turned it about its middle and pulled the hinge along, which
        // the point rows then had to bring back (with the rigid pass: 3.9 mm out of the hinges, now 2.1 mm).
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        val k = pointMass(ra, rb)
        val hold = anchorHold(axis, ra, rb, k)
        val mass = (axis dot coupledTurn(axis, hold, ra, rb)).let { if (it > 0f) 1f / it else 0f }
        // Spring, then motor, then limits (which overrule both), then the axes, then the point (Box2D's order).
        if (springHertz > 0f && !rigid) {
            val c = angle - springAngle
            val l = -spring.massScale * mass * (cdot() + spring.biasRate * c) - spring.impulseScale * springImpulse
            springImpulse += l; impulse += applyCoupled(axis * l, hold * l, ra, rb)
        }
        if (motorEnabled && !rigid) {
            val max = maxMotorTorque * h; val old = motorImpulse
            motorImpulse = (motorImpulse - mass * (cdot() - motorSpeed)).coerceIn(-max, max)
            val l = motorImpulse - old
            impulse += applyCoupled(axis * l, hold * l, ra, rb)
        }
        if (limitsEnabled) for (side in 0..1) {
            // Lower: angle − lower ≥ 0; upper: upper − angle ≥ 0, the same row turned round.
            val c = if (side == 0) angle - lowerAngle else upperAngle - angle
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (c > 0f) bias = c * invH                       // not there yet: arrive exactly, no sooner
            else if (useBias) { bias = soft.biasRate * c; massScale = soft.massScale; impulseScale = soft.impulseScale }
            val sign = if (side == 0) 1f else -1f
            val old = if (side == 0) lowerImpulse else upperImpulse
            val new = max(old - mass * massScale * (sign * cdot() + bias) - impulseScale * old, 0f)
            if (side == 0) lowerImpulse = new else upperImpulse = new
            val l = sign * (new - old)
            impulse += applyCoupled(axis * l, hold * l, ra, rb)
        }
        // The axes stay aligned: two rows across a's axis, about the anchor too.
        if (!direct) run {
            val j1 = perpendicular(axis); val j2 = axis cross j1
            val rel = w(ib) - w(ia)
            var b1 = rel dot j1; var b2 = rel dot j2
            var massScale = 1f; var impulseScale = 0f
            if (useBias) {
                // Turned by θ about u (across the axis), b's axis is off by θ u × axis, so axis × it is θ u.
                val err = axis cross qb.rotate(axisB0)
                b1 += soft.biasRate * (err dot j1); b2 += soft.biasRate * (err dot j2)
                massScale = soft.massScale; impulseScale = soft.impulseScale
            }
            val h1 = anchorHold(j1, ra, rb, k); val h2 = anchorHold(j2, ra, rb, k)
            val i1 = coupledTurn(j1, h1, ra, rb); val i2 = coupledTurn(j2, h2, ra, rb)
            val k11 = j1 dot i1; val k12 = j1 dot i2; val k22 = j2 dot i2
            val det = k11 * k22 - k12 * k12
            if (det > 1e-12f * k11 * k22 && det > 0f) {
                val l1 = -(k22 * b1 - k12 * b2) / det; val l2 = -(-k12 * b1 + k11 * b2) / det
                val s1 = l1 * massScale - impulseScale * (angular dot j1); val s2 = l2 * massScale - impulseScale * (angular dot j2)
                val step = j1 * s1 + j2 * s2
                angular += step; impulse += applyCoupled(step, h1 * s1 + h2 * s2, ra, rb)
            }
        }
        if (!direct) impulse += solvePoint(useBias, impulse)
    }

    // The trees' rows: the point, then the two axes across a's axis (where the bodies are now).
    private var across1 = Vec3.X; private var across2 = Vec3.Z
    override val directRows: Int get() = 5
    override val directForce: Float get() = impulse.length()
    override fun directReset() { impulse = Vec3.ZERO; angular = Vec3.ZERO }
    override fun nearLimits(dt: Float): Boolean = limitsEnabled && run { val m = .2f + 2f * abs(speed) * dt; val a = angle; a - lowerAngle < m || upperAngle - a < m }
    override fun directJacobian(out: DoubleArray, at: Int) {
        pointRows(out, at, 0)
        val axis = dq(ia).rotate(axisA0); across1 = perpendicular(axis); across2 = axis cross across1
        jacobianRow(out, at + 36, Vec3.ZERO, -across1, Vec3.ZERO, across1)
        jacobianRow(out, at + 48, Vec3.ZERO, -across2, Vec3.ZERO, across2)
    }
    override fun directError(out: DoubleArray, at: Int) {
        pointErrorInto(out, at)
        val e = dq(ia).rotate(axisA0) cross dq(ib).rotate(axisB0)
        out[at + 3] = (e dot across1).toDouble(); out[at + 4] = (e dot across2).toDouble()
    }
    override fun directAccumulate(lambda: DoubleArray, at: Int) {
        impulse += readVec(lambda, at)
        angular += across1 * lambda[at + 3].toFloat() + across2 * lambda[at + 4].toFloat()
    }
}

/**
 * A slider: b moves along one axis of a and does not turn relative to it (lifts, pistons, drawers,
 * suspensions). Optional limits ([limits], metres from where they were joined), a motor ([motor]) and a
 * spring ([spring]).
 */
class SliderJoint internal constructor(world: PhysicsWorld, a: Body?, b: Body, anchor: Vec3, axis: Vec3, collideConnected: Boolean) :
    Joint(world, a, b, anchor, anchor, collideConnected) {
    private val axisA = (if (ia < 0) axis else rot(ia).conjugate().rotate(axis)).normalized()
    private val relative0 = rot(ia).conjugate() * rot(ib)          // b's rotation relative to a when joined
    var lower = 0f; private set
    var upper = 0f; private set
    var limitsEnabled = false; private set
    var motorSpeed = 0f; private set
    var maxMotorForce = 0f; private set
    var motorEnabled = false; private set
    var springHertz = 0f; private set
    var springDampingRatio = 0f; private set
    var springPosition = 0f; private set
    private var linear = Vec3.ZERO; private var angular = Vec3.ZERO
    private var lowerImpulse = 0f; private var upperImpulse = 0f; private var motorImpulse = 0f; private var springImpulse = 0f
    private var axis0 = Vec3.X
    private val spring = Softness()

    /** How far b moved along the axis since they were joined (m). */
    val translation: Float get() = (anchorB - anchorA) dot axis
    /** Speed along the axis (m/s). */
    val speed: Float get() = (v(ib) - v(ia)) dot axis
    /** The axis in the world now (as a sees it). */
    val axis: Vec3 get() = rot(ia).rotate(axisA)

    fun limits(lower: Float?, upper: Float?) {
        require(lower == null || upper == null || lower <= upper) { "The lower limit must not exceed the upper one" }
        limitsEnabled = lower != null && upper != null; this.lower = lower ?: 0f; this.upper = upper ?: 0f; world.wakeJoint(this)
    }
    /** Drives the speed along the axis towards [speed] m/s with at most [maxForce] N (null: no motor). */
    fun motor(speed: Float?, maxForce: Float = 0f) { motorEnabled = speed != null; motorSpeed = speed ?: 0f; maxMotorForce = maxForce; world.wakeJoint(this) }
    /** Pulls back to [position] (m along the axis) like a spring of [hertz] Hz (0: none). */
    fun spring(hertz: Float, dampingRatio: Float = 1f, position: Float = 0f) {
        springHertz = hertz; springDampingRatio = dampingRatio; springPosition = position; world.wakeJoint(this)
    }

    override fun prepare(h: Float, hertz: Float, dampingRatio: Float) {
        super.prepare(h, hertz, dampingRatio)
        axis0 = rot(ia).rotate(axisA)
        spring.set(springHertz, springDampingRatio, h)
        if (!motorEnabled) motorImpulse = 0f
        if (!limitsEnabled) { lowerImpulse = 0f; upperImpulse = 0f }
        if (springHertz <= 0f) springImpulse = 0f
        lastImpulse = linear.length()
    }

    // A linear row along n: on a's turning (rA + d) × n (n turns with a), on b's rB × n.
    private var aa = Vec3.ZERO; private var bb = Vec3.ZERO
    private fun row(n: Vec3, ra: Vec3, rb: Vec3, d: Vec3): Float {
        aa = (ra + d) cross n; bb = rb cross n
        val k = im(ia) + im(ib) + (aa dot ii(ia, aa)) + (bb dot ii(ib, bb))
        return if (k > 0f) 1f / k else 0f
    }
    private fun rowSpeed(n: Vec3) = (n dot (v(ib) - v(ia))) + (bb dot w(ib)) - (aa dot w(ia))
    private fun applyRow(n: Vec3, lambda: Float) {
        val p = n * lambda
        push(ia, -1f, p, aa * lambda); push(ib, 1f, p, bb * lambda)
    }

    override fun warmStart() {
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        val d = dp(ib) - dp(ia) + (rb - ra) + deltaCenter
        val axis = dq(ia).rotate(axis0)
        val t1 = perpendicular(axis); val t2 = axis cross t1
        row(t1, ra, rb, d); applyRow(t1, linear dot t1)
        row(t2, ra, rb, d); applyRow(t2, linear dot t2)
        row(axis, ra, rb, d); applyRow(axis, springImpulse + motorImpulse + lowerImpulse - upperImpulse)
        applyAngular(angular)
    }

    override fun solve(useBias: Boolean, invH: Float) {
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        val d = dp(ib) - dp(ia) + (rb - ra) + deltaCenter
        val axis = dq(ia).rotate(axis0)
        val translation = d dot axis
        if (springHertz > 0f && !rigid) {
            val m = row(axis, ra, rb, d)
            val l = -spring.massScale * m * (rowSpeed(axis) + spring.biasRate * (translation - springPosition)) - spring.impulseScale * springImpulse
            springImpulse += l; applyRow(axis, l)
        }
        if (motorEnabled && !rigid) {
            val m = row(axis, ra, rb, d)
            val max = maxMotorForce * h; val old = motorImpulse
            motorImpulse = (motorImpulse - m * (rowSpeed(axis) - motorSpeed)).coerceIn(-max, max)
            applyRow(axis, motorImpulse - old)
        }
        if (limitsEnabled) for (side in 0..1) {
            val c = if (side == 0) translation - lower else upper - translation
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (c > 0f) bias = c * invH
            else if (useBias) { bias = soft.biasRate * c; massScale = soft.massScale; impulseScale = soft.impulseScale }
            val sign = if (side == 0) 1f else -1f
            val m = row(axis, ra, rb, d)
            val old = if (side == 0) lowerImpulse else upperImpulse
            val new = max(old - m * massScale * (sign * rowSpeed(axis) + bias) - impulseScale * old, 0f)
            if (side == 0) lowerImpulse = new else upperImpulse = new
            applyRow(axis, sign * (new - old))
        }
        if (direct) return
        angular += solveLock(useBias, relative0, angular)
        // Across the axis: two rows.
        val t1 = perpendicular(axis); val t2 = axis cross t1
        for (k in 0..1) {
            val n = if (k == 0) t1 else t2
            val m = row(n, ra, rb, d)
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (useBias) { bias = soft.biasRate * (d dot n); massScale = soft.massScale; impulseScale = soft.impulseScale }
            val lambda = -m * massScale * (rowSpeed(n) + bias) - impulseScale * (linear dot n)
            linear += n * lambda
            applyRow(n, lambda)
        }
    }

    // The trees' rows: two across the axis (a's turning about b's anchor counts), then the rotation locked.
    private var across1 = Vec3.X; private var across2 = Vec3.Z
    override val directRows: Int get() = 5
    override val directForce: Float get() = linear.length()
    override fun directReset() { linear = Vec3.ZERO; angular = Vec3.ZERO }
    override fun nearLimits(dt: Float): Boolean = limitsEnabled && run { val m = .02f + 2f * abs(speed) * dt; val t = translation; t - lower < m || upper - t < m }
    override fun directJacobian(out: DoubleArray, at: Int) {
        val axis = dq(ia).rotate(axis0); across1 = perpendicular(axis); across2 = axis cross across1
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        val d = dp(ib) - dp(ia) + (rb - ra) + deltaCenter
        for (k in 0..1) {
            val n = if (k == 0) across1 else across2
            jacobianRow(out, at + 12 * k, -n, -((ra + d) cross n), n, rb cross n)
        }
        lockRows(out, at, 2)
    }
    override fun directError(out: DoubleArray, at: Int) {
        val d = dp(ib) - dp(ia) + (dq(ib).rotate(frameB) - dq(ia).rotate(frameA)) + deltaCenter
        out[at] = (d dot across1).toDouble(); out[at + 1] = (d dot across2).toDouble()
        writeVec(out, at + 2, lockError(relative0))
    }
    override fun directAccumulate(lambda: DoubleArray, at: Int) {
        linear += across1 * lambda[at].toFloat() + across2 * lambda[at + 1].toFloat()
        angular += readVec(lambda, at + 2)
    }
}

/**
 * A ball joint whose swing is held in a cone and whose twist is limited: the shoulders, hips, elbows and neck
 * of a ragdoll. Made by [PhysicsWorld.addConeTwist]. [axis] (b's bone, in the world as joined) is the twist
 * axis: b's axis may lean away from a's by at most [swing] towards ±[across] and by [swing2] towards
 * ±(axis × across) — an elliptical cone when the two differ — and b may turn about it between [twistLower]
 * and [twistUpper] (radians, from where it was joined). [friction] (N·m) resists every turn of the joint, so
 * that limbs come to rest instead of swinging on. Limits and point are solved like a hinge's: about the
 * anchor, with a rigid velocity pass and soft position correction.
 */
class ConeTwistJoint internal constructor(world: PhysicsWorld, a: Body?, b: Body, anchor: Vec3, axis: Vec3, across: Vec3,
                                          collideConnected: Boolean) : Joint(world, a, b, anchor, anchor, collideConnected) {
    private val axisA: Vec3; private val axisB: Vec3; private val refA: Vec3; private val refB: Vec3
    init {
        val t = axis.normalized()
        val r = (across - t * (across dot t)).let { if (it.lengthSquared() > 1e-8f) it.normalized() else perpendicular(t) }
        axisA = localDir(ia, t); axisB = localDir(ib, t); refA = localDir(ia, r); refB = localDir(ib, r)
    }
    /** Half-angle of the cone towards ±across (radians). */
    var swing = .5f; private set
    /** Half-angle of the cone towards ±(axis × across) (radians). */
    var swing2 = .5f; private set
    var twistLower = -.3f; private set
    var twistUpper = .3f; private set
    /** Torque that resists every turn of the joint (N·m), 0 for none. */
    var friction = 0f
        set(v) { require(v >= 0f) { "Joint friction must not be negative" }; field = v; world.wakeJoint(this) }

    private var impulse = Vec3.ZERO                    // point (with the anchor holds of the angular rows)
    private var swingImpulse = 0f; private var lowerImpulse = 0f; private var upperImpulse = 0f
    private var frictionImpulse = Vec3.ZERO
    private var swingAxis = Vec3.ZERO; private var twistAxis = Vec3.ZERO   // the rows' axes as last solved (warm start)

    private fun localDir(i: Int, d: Vec3): Vec3 = (if (i < 0) d else rot(i).conjugate().rotate(d)).normalized()

    /** Sets the cone (radians, below π) and the twist range. */
    fun limits(swing: Float, swing2: Float = swing, twistLower: Float = this.twistLower, twistUpper: Float = this.twistUpper) {
        require(swing > 0f && swing < 3.1f && swing2 > 0f && swing2 < 3.1f) { "A cone's half-angles must be between 0 and π" }
        require(twistLower <= twistUpper && twistLower >= -PI.toFloat() && twistUpper <= PI.toFloat()) {
            "The twist limits need −π ≤ lower ≤ upper ≤ π" }
        this.swing = swing; this.swing2 = swing2; this.twistLower = twistLower; this.twistUpper = twistUpper
        world.wakeJoint(this)
    }

    /** How far b's axis leans from a's now (radians). */
    val swingAngle: Float get() { measure(rot(ia), rot(ib)); return swingNow }
    /** How far b has turned about its axis relative to a, from where they were joined (radians). */
    val twistAngle: Float get() { measure(rot(ia), rot(ib)); return twistNow }

    // Measured by [measure]: the swing angle and its axis, the cone's half-angle in that direction, the twist and its axis.
    private var swingNow = 0f; private var swingDir = Vec3.ZERO; private var limitNow = 0f
    private var twistNow = 0f; private var twistDir = Vec3.ZERO

    private fun measure(qa: Quat, qb: Quat) {
        val ta = qa.rotate(axisA); val tb = qb.rotate(axisB); val ra = qa.rotate(refA); val rb = qb.rotate(refB)
        val c = ta cross tb; val sn = c.length(); val cs = ta dot tb
        swingNow = atan2(sn, cs)
        swingDir = if (sn > 1e-6f) c / sn else Vec3.ZERO
        // The cone's half-angle in the direction b leans to: the ellipse's radius there.
        val lean = tb - ta * cs
        limitNow = if (lean.lengthSquared() < 1e-12f) min(swing, swing2) else {
            val l = lean.normalized(); val x = l dot ra; val y = l dot (ta cross ra)
            swing * swing2 / sqrt((swing2 * x) * (swing2 * x) + (swing * y) * (swing * y))
        }
        // Twist: b's reference brought back along the shortest turn from b's axis to a's, measured about a's axis.
        var rb0 = rb
        if (sn > 1e-6f) {
            val k = -swingDir                                      // turns tb onto ta by the swing angle
            rb0 = rb * cs + (k cross rb) * sn + k * ((k dot rb) * (1f - cs))
        }
        twistNow = atan2((ra cross rb0) dot ta, ra dot rb0)
        val mid = ta + tb
        twistDir = if (mid.lengthSquared() > 1e-6f) mid.normalized() else Vec3.ZERO
    }

    override fun prepare(h: Float, hertz: Float, dampingRatio: Float) {
        super.prepare(h, hertz, dampingRatio)
        if (friction <= 0f) frictionImpulse = Vec3.ZERO
        lastImpulse = impulse.length()
    }

    override fun warmStart() {
        val l = swingAxis * (-swingImpulse) + twistAxis * (lowerImpulse - upperImpulse) + frictionImpulse
        apply(impulse, dq(ia).rotate(frameA), dq(ib).rotate(frameB), l)
    }

    override fun solve(useBias: Boolean, invH: Float) {
        measure(rot(ia), rot(ib))                              // the rotations as integrated so far
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        val k = pointMass(ra, rb)
        fun rel() = w(ib) - w(ia)
        // Friction first (Box2D's motor order), within ±friction·h, about the anchor.
        if (friction > 0f && !rigid) {
            val hx = anchorHold(Vec3.X, ra, rb, k); val hy = anchorHold(Vec3.Y, ra, rb, k); val hz = anchorHold(Vec3.Z, ra, rb, k)
            val m = Mat3(coupledTurn(Vec3.X, hx, ra, rb), coupledTurn(Vec3.Y, hy, ra, rb), coupledTurn(Vec3.Z, hz, ra, rb))
            val old = frictionImpulse
            var acc = old + m.solve(-rel())
            val cap = friction * h
            if (acc.length() > cap) acc = acc * (cap / acc.length())
            frictionImpulse = acc
            val d = acc - old
            impulse += applyCoupled(d, hx * d.x + hy * d.y + hz * d.z, ra, rb)
        }
        // The cone: the angle between the axes stays within the cone's half-angle in the direction of the lean.
        if (swingDir != Vec3.ZERO) {
            val s = swingDir; swingAxis = s
            val hold = anchorHold(s, ra, rb, k)
            val inv = s dot coupledTurn(s, hold, ra, rb)
            val mass = if (inv > 0f) 1f / inv else 0f
            val c = limitNow - swingNow
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (c > 0f) bias = c * invH                       // not there yet: arrive exactly, no sooner
            else if (useBias) { bias = soft.biasRate * c; massScale = soft.massScale; impulseScale = soft.impulseScale }
            val old = swingImpulse
            val new = max(old - mass * massScale * (-(rel() dot s) + bias) - impulseScale * old, 0f)
            swingImpulse = new
            val l = -(new - old)
            impulse += applyCoupled(s * l, hold * l, ra, rb)
        } else swingImpulse = 0f
        // The twist, both ways about the axis halfway between the two.
        if (twistDir != Vec3.ZERO) {
            val t = twistDir; twistAxis = t
            val hold = anchorHold(t, ra, rb, k)
            val inv = t dot coupledTurn(t, hold, ra, rb)
            val mass = if (inv > 0f) 1f / inv else 0f
            for (side in 0..1) {
                val c = if (side == 0) twistNow - twistLower else twistUpper - twistNow
                var bias = 0f; var massScale = 1f; var impulseScale = 0f
                if (c > 0f) bias = c * invH
                else if (useBias) { bias = soft.biasRate * c; massScale = soft.massScale; impulseScale = soft.impulseScale }
                val sign = if (side == 0) 1f else -1f
                val old = if (side == 0) lowerImpulse else upperImpulse
                val new = max(old - mass * massScale * (sign * (rel() dot t) + bias) - impulseScale * old, 0f)
                if (side == 0) lowerImpulse = new else upperImpulse = new
                val l = sign * (new - old)
                impulse += applyCoupled(t * l, hold * l, ra, rb)
            }
        } else { lowerImpulse = 0f; upperImpulse = 0f }
        impulse += solvePoint(useBias, impulse)
    }
    // No rows for the joint trees: a ragdoll's cones and twists bound every limb at once, and are solved with their
    // points as before (a limb is no load-carrying structure; measured, the trees made the limits overshoot more).
}

/** A fixed joint: the two bodies move as one (glued, welded); [b] keeps its place and rotation relative to [a]. */
class FixedJoint internal constructor(world: PhysicsWorld, a: Body?, b: Body, anchor: Vec3, collideConnected: Boolean) :
    Joint(world, a, b, anchor, anchor, collideConnected) {
    private val relative0 = rot(ia).conjugate() * rot(ib)
    private var impulse = Vec3.ZERO
    private var angular = Vec3.ZERO
    override fun prepare(h: Float, hertz: Float, dampingRatio: Float) { super.prepare(h, hertz, dampingRatio); lastImpulse = impulse.length() }
    override fun warmStart() = apply(impulse, dq(ia).rotate(frameA), dq(ib).rotate(frameB), angular)
    override fun solve(useBias: Boolean, invH: Float) {
        if (direct) return
        angular += solveLock(useBias, relative0, angular)
        impulse += solvePoint(useBias, impulse)
    }
    override val directRows: Int get() = 6
    override val directForce: Float get() = impulse.length()
    override fun directReset() { impulse = Vec3.ZERO; angular = Vec3.ZERO }
    override fun directJacobian(out: DoubleArray, at: Int) { pointRows(out, at, 0); lockRows(out, at, 3) }
    override fun directError(out: DoubleArray, at: Int) { pointErrorInto(out, at); writeVec(out, at + 3, lockError(relative0)) }
    override fun directAccumulate(lambda: DoubleArray, at: Int) { impulse += readVec(lambda, at); angular += readVec(lambda, at + 3) }
}

/**
 * Keeps an anchor on each body at a distance: rigid at [length], a spring ([spring]), or a rope (slack
 * below its length, made by [PhysicsWorld.addRope]); a spring can be bounded by [limits].
 */
class DistanceJoint internal constructor(world: PhysicsWorld, a: Body?, b: Body, anchorA: Vec3, anchorB: Vec3, collideConnected: Boolean,
                                         /** A rope holds only at its length, never pushes. */
                                         val rope: Boolean) :
    Joint(world, a, b, anchorA, anchorB, collideConnected) {
    /** Rest length (m): the distance when joined, unless set. */
    var length = (anchorB - anchorA).length()
        set(v) { require(v >= 0f); field = v; world.wakeJoint(this) }
    var minLength = 0f; private set
    var maxLength = Float.POSITIVE_INFINITY; private set
    var springHertz = 0f; private set
    var springDampingRatio = 0f; private set
    private var impulse = 0f; private var lowerImpulse = 0f; private var upperImpulse = 0f
    private val spring = Softness()

    /** The distance between the two anchors now (m). */
    val currentLength: Float get() = (anchorB - anchorA).length()

    /** Makes it a spring of [hertz] Hz towards [length], damped by [dampingRatio] (0 Hz: rigid again). */
    fun spring(hertz: Float, dampingRatio: Float = .5f) {
        require(!rope) { "A rope is no spring" }
        springHertz = hertz; springDampingRatio = dampingRatio; world.wakeJoint(this)
    }
    /** A spring (or a rope) never shorter than [min], never longer than [max] (m). */
    fun limits(min: Float = 0f, max: Float = Float.POSITIVE_INFINITY) {
        require(min in 0f..max) { "Limits need 0 ≤ min ≤ max" }
        minLength = min; maxLength = max; world.wakeJoint(this)
    }

    override fun prepare(h: Float, hertz: Float, dampingRatio: Float) {
        super.prepare(h, hertz, dampingRatio)
        spring.set(springHertz, springDampingRatio, h)
        if (rope) impulse = 0f
        lastImpulse = abs(impulse + lowerImpulse - upperImpulse)
    }

    private var n = Vec3.Y
    private var ca = Vec3.ZERO; private var cb = Vec3.ZERO
    private var current = 0f
    /** Measures the axis and the distance now; returns the effective mass along it. */
    private fun measure(ra: Vec3, rb: Vec3): Float {
        val d = dp(ib) - dp(ia) + (rb - ra) + deltaCenter
        val l = d.length()
        n = if (l > 1e-6f) d / l else Vec3.Y
        current = l
        ca = ra cross n; cb = rb cross n
        val k = im(ia) + im(ib) + (ca dot ii(ia, ca)) + (cb dot ii(ib, cb))
        return if (k > 0f) 1f / k else 0f
    }
    private fun speed() = (n dot (v(ib) - v(ia))) + (cb dot w(ib)) - (ca dot w(ia))
    private fun applyAlong(ra: Vec3, rb: Vec3, lambda: Float) = apply(n * lambda, ra, rb)

    override fun warmStart() {
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        measure(ra, rb)
        applyAlong(ra, rb, impulse + lowerImpulse - upperImpulse)
    }

    override fun solve(useBias: Boolean, invH: Float) {
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        val m = measure(ra, rb)
        if (!rope && !(rigid && springHertz > 0f) && !direct) {
            // A spring pulls in both passes (softness is its nature); a rigid rod corrects only with bias.
            val c = current - length
            val s = if (springHertz > 0f) spring else soft
            val corrected = springHertz > 0f || useBias
            val bias = if (corrected) s.biasRate * c else 0f
            val massScale = if (corrected) s.massScale else 1f
            val impulseScale = if (corrected) s.impulseScale else 0f
            val l = -massScale * m * (speed() + bias) - impulseScale * impulse
            impulse += l; applyAlong(ra, rb, l)
        }
        // Limits: a rope's length, a spring's bounds (a rigid rod has nothing to bound).
        if (!rope && springHertz <= 0f) return
        val top = if (rope) min(length, maxLength) else maxLength
        for (side in 0..1) {
            val limit = if (side == 0) minLength else top
            if (side == 0 && limit <= 0f || side == 1 && limit == Float.POSITIVE_INFINITY) continue
            val c = if (side == 0) current - limit else limit - current
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (c > 0f) bias = c * invH
            else if (useBias) { bias = soft.biasRate * c; massScale = soft.massScale; impulseScale = soft.impulseScale }
            val sign = if (side == 0) 1f else -1f
            val old = if (side == 0) lowerImpulse else upperImpulse
            val new = max(old - m * massScale * (sign * speed() + bias) - impulseScale * old, 0f)
            if (side == 0) lowerImpulse = new else upperImpulse = new
            applyAlong(ra, rb, sign * (new - old))
        }
    }

    // A rigid rod: one row along the rod (where the bodies are now).
    private var along = Vec3.Y
    override val directRows: Int get() = if (!rope && springHertz <= 0f) 1 else 0
    override val directForce: Float get() = abs(impulse)
    override fun directReset() { impulse = 0f }
    override fun directJacobian(out: DoubleArray, at: Int) {
        val ra = dq(ia).rotate(frameA); val rb = dq(ib).rotate(frameB)
        val d = dp(ib) - dp(ia) + (rb - ra) + deltaCenter
        val l = d.length()
        along = if (l > 1e-6f) d / l else Vec3.Y
        jacobianRow(out, at, -along, -(ra cross along), along, rb cross along)
    }
    override fun directError(out: DoubleArray, at: Int) {
        val d = dp(ib) - dp(ia) + (dq(ib).rotate(frameB) - dq(ia).rotate(frameA)) + deltaCenter
        out[at] = (d.length() - length).toDouble()
    }
    override fun directAccumulate(lambda: DoubleArray, at: Int) { impulse += lambda[at].toFloat() }
}

/** A 3 × 3 matrix by columns, for the joints' effective masses. */
internal class Mat3(val c0: Vec3, val c1: Vec3, val c2: Vec3) {
    /** Solves M x = r (Cramer); zero when M is singular. */
    fun solve(r: Vec3): Vec3 {
        val det = c0 dot (c1 cross c2)
        // Relative test: a joint between heavy bodies has tiny entries, yet a well-conditioned matrix.
        if (abs(det) <= 1e-10f * c0.length() * c1.length() * c2.length() || det == 0f) return Vec3.ZERO
        val inv = 1f / det
        return Vec3((r dot (c1 cross c2)) * inv, (c0 dot (r cross c2)) * inv, (c0 dot (c1 cross r)) * inv)
    }
}

/** Some unit vector perpendicular to [n] (unit). */
internal fun perpendicular(n: Vec3): Vec3 =
    if (abs(n.x) >= .57735f) Vec3(n.y, -n.x, 0f).normalized() else Vec3(0f, n.z, -n.y).normalized()

/** Floats per body in the world's solver arrays (S in PhysicsWorld.kt). */
internal const val SS = 20
