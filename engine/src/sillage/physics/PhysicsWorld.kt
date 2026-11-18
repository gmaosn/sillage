package sillage.physics

import sillage.math.Quat
import sillage.math.ValueCandidate
import sillage.math.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class BodyType { STATIC, DYNAMIC, KINEMATIC }

/** Handle to a body of a [PhysicsWorld]. Two handles to the same body are equal. */
@ValueCandidate
class Body internal constructor(private val world: PhysicsWorld, val id: Int) {
    val type: BodyType get() = world.types[id]
    val shape: Shape get() = world.shapes[id]!!
    var position: Vec3
        get() = Vec3(world.px[id], world.py[id], world.pz[id])
        set(v) { world.px[id] = v.x; world.py[id] = v.y; world.pz[id] = v.z; world.wake(id) }
    var rotation: Quat
        get() = Quat(world.qx[id], world.qy[id], world.qz[id], world.qw[id])
        set(q) { val u = q.normalized(); world.qx[id] = u.x; world.qy[id] = u.y; world.qz[id] = u.z; world.qw[id] = u.w; world.wake(id) }
    var velocity: Vec3
        get() = Vec3(world.vx[id], world.vy[id], world.vz[id])
        set(v) { world.vx[id] = v.x; world.vy[id] = v.y; world.vz[id] = v.z; world.wake(id) }
    var angularVelocity: Vec3
        get() = Vec3(world.wx[id], world.wy[id], world.wz[id])
        set(v) { world.wx[id] = v.x; world.wy[id] = v.y; world.wz[id] = v.z; world.wake(id) }
    val mass: Float get() = if (world.invMass[id] > 0f) 1f / world.invMass[id] else Float.POSITIVE_INFINITY
    var friction: Float
        get() = world.friction[id]
        set(v) { world.friction[id] = v }
    var restitution: Float
        get() = world.restitution[id]
        set(v) { world.restitution[id] = v }
    var linearDamping: Float
        get() = world.linearDamping[id]
        set(v) { world.linearDamping[id] = v }
    var angularDamping: Float
        get() = world.angularDamping[id]
        set(v) { world.angularDamping[id] = v }
    var gravityScale: Float
        get() = world.gravityScale[id]
        set(v) { world.gravityScale[id] = v }
    val isSleeping: Boolean get() = world.sleeping[id]

    /** Instant change of momentum, applied at [point] (the centre by default). */
    fun applyImpulse(impulse: Vec3, point: Vec3 = position) {
        if (world.types[id] != BodyType.DYNAMIC) return
        world.wake(id)
        val m = world.invMass[id]
        world.vx[id] += impulse.x * m; world.vy[id] += impulse.y * m; world.vz[id] += impulse.z * m
        val t = (point - position) cross impulse
        world.prepareInertia(id)
        val w = world.mulInvInertia(id, t)
        world.wx[id] += w.x; world.wy[id] += w.y; world.wz[id] += w.z
    }

    /** For a kinematic body: sets the velocities that reach [target] (and [targetRotation]) in [dt] seconds. */
    fun moveTo(target: Vec3, dt: Float, targetRotation: Quat? = null) {
        require(dt > 0f)
        velocity = (target - position) / dt
        if (targetRotation != null) {
            var d = targetRotation * rotation.conjugate()
            if (d.w < 0f) d = Quat(-d.x, -d.y, -d.z, -d.w)
            val s = sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
            angularVelocity = if (s < 1e-6f) Vec3.ZERO else Vec3(d.x, d.y, d.z) * (2f * acos(d.w.coerceIn(-1f, 1f)) / (s * dt))
        }
    }

    override fun equals(other: Any?) = other is Body && other.id == id && other.world === world
    override fun hashCode() = id
    override fun toString() = "Body#$id($type, ${shape::class.simpleName})"
}

/**
 * Rigid-body world, entirely in Kotlin. Bodies live in flat arrays (structure of arrays).
 *
 * Solver: soft-step sequential impulses after Box2D v3 (E. Catto): [substeps] substeps per step,
 * each integrating velocities, warm starting, solving with soft position correction, integrating
 * positions and relaxing without bias; then restitution. Contacts within the speculative margin
 * are solved before they touch, which keeps fast bodies out of thin geometry.
 */
class PhysicsWorld(var gravity: Vec3 = Vec3(0f, -9.81f, 0f)) {
    var substeps = 4
    /** Stiffness of contact correction (Hz) and its damping ratio. */
    var contactHertz = 30f
    var contactDampingRatio = 10f
    /** Fastest speed at which overlapping bodies are pushed apart (m/s). */
    var contactSpeed = 3f
    /** Approach speed below which bodies do not bounce (m/s). */
    var restitutionThreshold = 1f
    var sleepLinearSpeed = .05f
    var sleepAngularSpeed = .05f
    var timeToSleep = .5f
    var speculativeDistance = .02f

    internal var count = 0
    private var capacity = 0
    internal var types = arrayOfNulls<BodyType>(0).requireNoNulls()
    internal var shapes = arrayOfNulls<Shape>(0)
    internal var px = FloatArray(0); internal var py = FloatArray(0); internal var pz = FloatArray(0)
    internal var qx = FloatArray(0); internal var qy = FloatArray(0); internal var qz = FloatArray(0); internal var qw = FloatArray(0)
    internal var vx = FloatArray(0); internal var vy = FloatArray(0); internal var vz = FloatArray(0)
    internal var wx = FloatArray(0); internal var wy = FloatArray(0); internal var wz = FloatArray(0)
    internal var invMass = FloatArray(0)
    private var ilx = FloatArray(0); private var ily = FloatArray(0); private var ilz = FloatArray(0)   // inverse local inertia
    private var iw = FloatArray(0)                                                                    // world inverse inertia, 6 per body
    internal var friction = FloatArray(0); internal var restitution = FloatArray(0)
    internal var linearDamping = FloatArray(0); internal var angularDamping = FloatArray(0); internal var gravityScale = FloatArray(0)
    internal var sleeping = BooleanArray(0); private var sleepClock = FloatArray(0)
    private var fx = FloatArray(0); private var fy = FloatArray(0); private var fz = FloatArray(0)
    // Step scratch: start rotation, delta position and delta rotation since the step began, bounds.
    private var q0 = FloatArray(0); private var dp = FloatArray(0); private var dq = FloatArray(0)
    private var box = FloatArray(0)
    private var poses = arrayOfNulls<Pose>(0)
    private var sap = IntArray(0)

    private val meshes = ArrayList<TriangleMesh>()
    private val meshFriction = ArrayList<Float>()

    val bodyCount get() = count

    // ------------------------------------------------------------------ bodies

    private fun grow() {
        val c = max(16, capacity * 2)
        types = types.copyOf(c).let { a -> Array(c) { a[it] ?: BodyType.STATIC } }
        shapes = shapes.copyOf(c)
        px = px.copyOf(c); py = py.copyOf(c); pz = pz.copyOf(c)
        qx = qx.copyOf(c); qy = qy.copyOf(c); qz = qz.copyOf(c); qw = qw.copyOf(c)
        vx = vx.copyOf(c); vy = vy.copyOf(c); vz = vz.copyOf(c); wx = wx.copyOf(c); wy = wy.copyOf(c); wz = wz.copyOf(c)
        invMass = invMass.copyOf(c); ilx = ilx.copyOf(c); ily = ily.copyOf(c); ilz = ilz.copyOf(c); iw = iw.copyOf(6 * c)
        friction = friction.copyOf(c); restitution = restitution.copyOf(c)
        linearDamping = linearDamping.copyOf(c); angularDamping = angularDamping.copyOf(c); gravityScale = gravityScale.copyOf(c)
        sleeping = sleeping.copyOf(c); sleepClock = sleepClock.copyOf(c)
        fx = fx.copyOf(c); fy = fy.copyOf(c); fz = fz.copyOf(c)
        q0 = q0.copyOf(4 * c); dp = dp.copyOf(3 * c); dq = dq.copyOf(4 * c); box = box.copyOf(6 * c)
        poses = poses.copyOf(c); sap = sap.copyOf(c)
        capacity = c
    }

    /**
     * Adds a body. Its mass comes from [density] (kg/m³) unless [mass] is given; static and
     * kinematic bodies have infinite mass.
     */
    fun add(shape: Shape, position: Vec3, rotation: Quat = Quat.IDENTITY, type: BodyType = BodyType.DYNAMIC,
            density: Float = 1000f, mass: Float? = null, friction: Float = .6f, restitution: Float = 0f): Body {
        if (count == capacity) grow()
        val i = count++
        types[i] = type; shapes[i] = shape
        px[i] = position.x; py[i] = position.y; pz[i] = position.z
        val q = rotation.normalized(); qx[i] = q.x; qy[i] = q.y; qz[i] = q.z; qw[i] = q.w
        if (type == BodyType.DYNAMIC) {
            val m = mass ?: (density * shape.volume)
            require(m > 0f) { "A dynamic body needs a positive mass" }
            invMass[i] = 1f / m
            val inertia = shape.inertia(m)
            ilx[i] = 1f / inertia.x; ily[i] = 1f / inertia.y; ilz[i] = 1f / inertia.z
        }
        this.friction[i] = friction; this.restitution[i] = restitution; gravityScale[i] = 1f
        sap[i] = i
        return Body(this, i)
    }

    fun body(id: Int): Body { require(id in 0 until count); return Body(this, id) }

    /** Adds static triangles (terrain, buildings). */
    fun addMesh(mesh: TriangleMesh, friction: Float = .6f) { meshes.add(mesh); meshFriction.add(friction) }

    internal fun wake(i: Int) { sleeping[i] = false; sleepClock[i] = 0f }

    /** Applies a force at the centre during the next step. */
    fun applyForce(body: Body, force: Vec3) { val i = body.id; fx[i] += force.x; fy[i] += force.y; fz[i] += force.z; wake(i) }

    // ------------------------------------------------------------------ inertia

    /** World inverse inertia R · diag(invI) · Rᵀ, stored as xx, xy, xz, yy, yz, zz. */
    internal fun prepareInertia(i: Int) {
        val x = qx[i]; val y = qy[i]; val z = qz[i]; val w = qw[i]
        val r00 = 1 - 2 * (y * y + z * z); val r01 = 2 * (x * y - z * w); val r02 = 2 * (x * z + y * w)
        val r10 = 2 * (x * y + z * w); val r11 = 1 - 2 * (x * x + z * z); val r12 = 2 * (y * z - x * w)
        val r20 = 2 * (x * z - y * w); val r21 = 2 * (y * z + x * w); val r22 = 1 - 2 * (x * x + y * y)
        val a = ilx[i]; val b = ily[i]; val c = ilz[i]
        val o = 6 * i
        iw[o] = r00 * r00 * a + r01 * r01 * b + r02 * r02 * c
        iw[o + 1] = r00 * r10 * a + r01 * r11 * b + r02 * r12 * c
        iw[o + 2] = r00 * r20 * a + r01 * r21 * b + r02 * r22 * c
        iw[o + 3] = r10 * r10 * a + r11 * r11 * b + r12 * r12 * c
        iw[o + 4] = r10 * r20 * a + r11 * r21 * b + r12 * r22 * c
        iw[o + 5] = r20 * r20 * a + r21 * r21 * b + r22 * r22 * c
    }

    internal fun mulInvInertia(i: Int, v: Vec3): Vec3 {
        val o = 6 * i
        return Vec3(iw[o] * v.x + iw[o + 1] * v.y + iw[o + 2] * v.z,
            iw[o + 1] * v.x + iw[o + 3] * v.y + iw[o + 4] * v.z,
            iw[o + 2] * v.x + iw[o + 4] * v.y + iw[o + 5] * v.z)
    }

    // ------------------------------------------------------------------ step

    private var points = ContactSet()
    private var previous = ContactSet()
    private val pairsA = IntList(); private val pairsB = IntList()
    private val found = IntList(); private val stack = IntList()
    private val scratch = ContactPoints()

    /** Advances the simulation by [dt] seconds. */
    fun step(dt: Float) {
        if (dt <= 0f || count == 0) return
        val h = dt / substeps
        prepareBodies(dt)
        findPairs()
        collide(dt)
        prepareContacts(h)
        repeat(substeps) {
            integrateVelocities(h)
            warmStart()
            solve(h, true)
            integratePositions(h)
            solve(h, false)
        }
        restitute()
        updateSleep(dt)
        for (i in 0 until count) { fx[i] = 0f; fy[i] = 0f; fz[i] = 0f }
        // Les points de ce pas deviennent « précédents » ; l'autre ensemble servira au pas suivant.
        points.keep()
        val spare = previous; previous = points; points = spare
    }

    private fun awake(i: Int) = !sleeping[i]
    private fun moving(i: Int) = when (types[i]) {
        BodyType.DYNAMIC -> !sleeping[i]
        BodyType.KINEMATIC -> vx[i] != 0f || vy[i] != 0f || vz[i] != 0f || wx[i] != 0f || wy[i] != 0f || wz[i] != 0f
        BodyType.STATIC -> false
    }

    private fun prepareBodies(dt: Float) {
        for (i in 0 until count) {
            val x = qx[i]; val y = qy[i]; val z = qz[i]; val w = qw[i]
            val ax = Vec3(1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w))
            val ay = Vec3(2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w))
            val az = Vec3(2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y))
            val p = Vec3(px[i], py[i], pz[i])
            poses[i] = Pose(p, ax, ay, az)
            if (types[i] == BodyType.DYNAMIC) prepareInertia(i)
            q0[4 * i] = x; q0[4 * i + 1] = y; q0[4 * i + 2] = z; q0[4 * i + 3] = w
            dp[3 * i] = 0f; dp[3 * i + 1] = 0f; dp[3 * i + 2] = 0f
            dq[4 * i] = 0f; dq[4 * i + 1] = 0f; dq[4 * i + 2] = 0f; dq[4 * i + 3] = 1f
            // Bounds, widened by the distance travelled during the step.
            val e = when (val s = shapes[i]!!) {
                is Sphere -> Vec3(s.radius, s.radius, s.radius)
                is Box -> Vec3(abs(ax.x) * s.halfX + abs(ay.x) * s.halfY + abs(az.x) * s.halfZ,
                    abs(ax.y) * s.halfX + abs(ay.y) * s.halfY + abs(az.y) * s.halfZ,
                    abs(ax.z) * s.halfX + abs(ay.z) * s.halfY + abs(az.z) * s.halfZ)
                is Capsule -> Vec3(abs(ay.x) * s.halfHeight + s.radius, abs(ay.y) * s.halfHeight + s.radius, abs(ay.z) * s.halfHeight + s.radius)
            }
            val m = speculativeDistance + if (moving(i)) sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]) * dt else 0f
            val o = 6 * i
            box[o] = p.x - e.x - m; box[o + 1] = p.y - e.y - m; box[o + 2] = p.z - e.z - m
            box[o + 3] = p.x + e.x + m; box[o + 4] = p.y + e.y + m; box[o + 5] = p.z + e.z + m
        }
    }

    /** Sweep and prune on X (insertion sort keeps the nearly sorted order cheap), then the meshes. */
    private fun findPairs() {
        pairsA.clear(); pairsB.clear()
        for (k in 1 until count) {
            val v = sap[k]; val key = box[6 * v]
            var j = k - 1
            while (j >= 0 && box[6 * sap[j]] > key) { sap[j + 1] = sap[j]; j-- }
            sap[j + 1] = v
        }
        for (k in 0 until count) {
            val i = sap[k]; val oi = 6 * i
            for (l in k + 1 until count) {
                val j = sap[l]; val oj = 6 * j
                if (box[oj] > box[oi + 3]) break
                if (box[oj + 1] > box[oi + 4] || box[oj + 4] < box[oi + 1] || box[oj + 2] > box[oi + 5] || box[oj + 5] < box[oi + 2]) continue
                val di = types[i] == BodyType.DYNAMIC; val dj = types[j] == BodyType.DYNAMIC
                if (!di && !dj) continue
                val mi = moving(i); val mj = moving(j)
                if (!mi && !mj) continue
                // A moving body reaching a sleeping one wakes it.
                if (di && sleeping[i]) wake(i)
                if (dj && sleeping[j]) wake(j)
                pairsA.add(min(i, j)); pairsB.add(max(i, j))
            }
        }
    }

    private fun collide(dt: Float) {
        points.clear()
        for (k in 0 until pairsA.size) {
            val i = pairsA[k]; val j = pairsB[k]
            val margin = min(1f, speculativeDistance + (speed(i) + speed(j)) * dt)
            scratch.clear()
            collide(shapes[i]!!, poses[i]!!, shapes[j]!!, poses[j]!!, margin, scratch)
            if (scratch.count > 0) points.addAll(scratch, i, j, (i.toLong() shl 32) or j.toLong(),
                sqrt(friction[i] * friction[j]), max(restitution[i], restitution[j]), poses[j]!!, previous)
        }
        for ((m, mesh) in meshes.withIndex()) {
            for (b in 0 until count) {
                if (types[b] != BodyType.DYNAMIC || sleeping[b]) continue
                val o = 6 * b
                found.clear()
                mesh.query(box[o], box[o + 1], box[o + 2], box[o + 3], box[o + 4], box[o + 5], found, stack)
                if (found.size == 0) continue
                val margin = min(1f, speculativeDistance + speed(b) * dt)
                for (f in 0 until found.size) {
                    val t = found[f]
                    val v = mesh.v; val n = mesh.n
                    val tri = Tri(Vec3(v[9 * t], v[9 * t + 1], v[9 * t + 2]), Vec3(v[9 * t + 3], v[9 * t + 4], v[9 * t + 5]),
                        Vec3(v[9 * t + 6], v[9 * t + 7], v[9 * t + 8]), Vec3(n[3 * t], n[3 * t + 1], n[3 * t + 2]))
                    scratch.clear()
                    collideTriangle(tri, shapes[b]!!, poses[b]!!, margin, scratch)
                    if (scratch.count > 0) points.addAll(scratch, -1, b,
                        Long.MIN_VALUE or (m.toLong() shl 55) or (b.toLong() shl 24) or t.toLong(),
                        sqrt(meshFriction[m] * friction[b]), restitution[b], poses[b]!!, previous)
                }
            }
        }
    }

    private fun speed(i: Int) = if (moving(i)) sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]) else 0f

    // ------------------------------------------------------------------ solver

    private var softBias = 0f; private var softMass = 1f; private var softImpulse = 0f
    private var staticBias = 0f; private var staticMass = 1f; private var staticImpulse = 0f

    private fun soft(hertz: Float, zeta: Float, h: Float): FloatArray {
        if (hertz == 0f) return floatArrayOf(0f, 1f, 0f)
        val omega = 2f * Math.PI.toFloat() * hertz
        val a1 = 2f * zeta + h * omega; val a2 = h * omega * a1; val a3 = 1f / (1f + a2)
        return floatArrayOf(omega / a1, a2 * a3, a3)
    }

    private fun prepareContacts(h: Float) {
        val hertz = min(contactHertz, .25f / h)
        soft(hertz, contactDampingRatio, h).let { softBias = it[0]; softMass = it[1]; softImpulse = it[2] }
        soft(2f * hertz, contactDampingRatio, h).let { staticBias = it[0]; staticMass = it[1]; staticImpulse = it[2] }
        val c = points
        for (k in 0 until c.size) {
            val a = c.a[k]; val b = c.b[k]
            val pxk = c.x[k]; val pyk = c.y[k]; val pzk = c.z[k]
            val n = Vec3(c.nx[k], c.ny[k], c.nz[k])
            val rA = if (a >= 0) Vec3(pxk - px[a], pyk - py[a], pzk - pz[a]) else Vec3.ZERO
            val rB = Vec3(pxk - px[b], pyk - py[b], pzk - pz[b])
            c.setAnchors(k, rA, rB)
            c.adjSep[k] = c.sep[k] - ((rB - rA) dot n)
            val mA = if (a >= 0) invMass[a] else 0f; val mB = invMass[b]
            c.static[k] = mA == 0f || mB == 0f
            fun effectiveMass(dir: Vec3): Float {
                var kk = mA + mB
                if (a >= 0 && mA > 0f) { val ra = rA cross dir; kk += ra dot mulInvInertia(a, ra) }
                if (mB > 0f) { val rb = rB cross dir; kk += rb dot mulInvInertia(b, rb) }
                return if (kk > 0f) 1f / kk else 0f
            }
            val t1 = tangent(n); val t2 = n cross t1
            c.setTangents(k, t1, t2)
            c.normalMass[k] = effectiveMass(n); c.t1Mass[k] = effectiveMass(t1); c.t2Mass[k] = effectiveMass(t2)
            c.relVel[k] = relativeVelocity(a, b, rA, rB) dot n
            c.maxLamN[k] = 0f
        }
    }

    private fun tangent(n: Vec3): Vec3 =
        if (abs(n.x) >= .57735f) Vec3(n.y, -n.x, 0f).normalized() else Vec3(0f, n.z, -n.y).normalized()

    private fun relativeVelocity(a: Int, b: Int, rA: Vec3, rB: Vec3): Vec3 {
        val vB = Vec3(vx[b] + wy[b] * rB.z - wz[b] * rB.y, vy[b] + wz[b] * rB.x - wx[b] * rB.z, vz[b] + wx[b] * rB.y - wy[b] * rB.x)
        if (a < 0) return vB
        return vB - Vec3(vx[a] + wy[a] * rA.z - wz[a] * rA.y, vy[a] + wz[a] * rA.x - wx[a] * rA.z, vz[a] + wx[a] * rA.y - wy[a] * rA.x)
    }

    private fun integrateVelocities(h: Float) {
        val gx = gravity.x; val gy = gravity.y; val gz = gravity.z
        for (i in 0 until count) {
            if (types[i] != BodyType.DYNAMIC || sleeping[i]) continue
            val m = invMass[i]; val gs = gravityScale[i]
            vx[i] += h * (gx * gs + fx[i] * m); vy[i] += h * (gy * gs + fy[i] * m); vz[i] += h * (gz * gs + fz[i] * m)
            val ld = 1f / (1f + h * linearDamping[i]); val ad = 1f / (1f + h * angularDamping[i])
            vx[i] *= ld; vy[i] *= ld; vz[i] *= ld; wx[i] *= ad; wy[i] *= ad; wz[i] *= ad
        }
    }

    private fun integratePositions(h: Float) {
        for (i in 0 until count) {
            if (types[i] == BodyType.STATIC || (types[i] == BodyType.DYNAMIC && sleeping[i])) continue
            px[i] += h * vx[i]; py[i] += h * vy[i]; pz[i] += h * vz[i]
            dp[3 * i] += h * vx[i]; dp[3 * i + 1] += h * vy[i]; dp[3 * i + 2] += h * vz[i]
            val x = qx[i]; val y = qy[i]; val z = qz[i]; val w = qw[i]
            val ax = wx[i]; val ay = wy[i]; val az = wz[i]
            if (ax == 0f && ay == 0f && az == 0f) continue
            var nx = x + .5f * h * (w * ax + ay * z - az * y)
            var ny = y + .5f * h * (w * ay + az * x - ax * z)
            var nz = z + .5f * h * (w * az + ax * y - ay * x)
            var nw = w - .5f * h * (ax * x + ay * y + az * z)
            val l = 1f / sqrt(nx * nx + ny * ny + nz * nz + nw * nw)
            nx *= l; ny *= l; nz *= l; nw *= l
            qx[i] = nx; qy[i] = ny; qz[i] = nz; qw[i] = nw
            // Rotation since the step began: q · q0⁻¹.
            val cx = -q0[4 * i]; val cy = -q0[4 * i + 1]; val cz = -q0[4 * i + 2]; val cw = q0[4 * i + 3]
            dq[4 * i] = nw * cx + nx * cw + ny * cz - nz * cy
            dq[4 * i + 1] = nw * cy - nx * cz + ny * cw + nz * cx
            dq[4 * i + 2] = nw * cz + nx * cy - ny * cx + nz * cw
            dq[4 * i + 3] = nw * cw - nx * cx - ny * cy - nz * cz
        }
    }

    private fun applyImpulse(a: Int, b: Int, rA: Vec3, rB: Vec3, p: Vec3) {
        if (a >= 0 && invMass[a] > 0f) {
            val m = invMass[a]; vx[a] -= p.x * m; vy[a] -= p.y * m; vz[a] -= p.z * m
            val w = mulInvInertia(a, rA cross p); wx[a] -= w.x; wy[a] -= w.y; wz[a] -= w.z
        }
        if (invMass[b] > 0f) {
            val m = invMass[b]; vx[b] += p.x * m; vy[b] += p.y * m; vz[b] += p.z * m
            val w = mulInvInertia(b, rB cross p); wx[b] += w.x; wy[b] += w.y; wz[b] += w.z
        }
    }

    private fun warmStart() {
        val c = points
        for (k in 0 until c.size) {
            val p = c.normal(k) * c.lamN[k] + c.t1(k) * c.lamT1[k] + c.t2(k) * c.lamT2[k]
            applyImpulse(c.a[k], c.b[k], c.rA(k), c.rB(k), p)
        }
    }

    private fun rotateDelta(i: Int, r: Vec3): Vec3 {
        if (i < 0) return r
        val o = 4 * i
        return Quat(dq[o], dq[o + 1], dq[o + 2], dq[o + 3]).rotate(r)
    }

    private fun solve(h: Float, useBias: Boolean) {
        val c = points
        val invH = 1f / h
        for (k in 0 until c.size) {
            val a = c.a[k]; val b = c.b[k]
            val n = c.normal(k); val rA = c.rA(k); val rB = c.rB(k)
            // Current separation from the motion since the step began.
            val dA = if (a >= 0) Vec3(dp[3 * a], dp[3 * a + 1], dp[3 * a + 2]) else Vec3.ZERO
            val dB = Vec3(dp[3 * b], dp[3 * b + 1], dp[3 * b + 2])
            val d = (dB - dA) + (rotateDelta(b, rB) - rotateDelta(a, rA))
            val s = (d dot n) + c.adjSep[k]
            var bias = 0f; var massScale = 1f; var impulseScale = 0f
            if (s > 0f) bias = s * invH
            else if (useBias) {
                if (c.static[k]) { bias = max(staticBias * s, -contactSpeed); massScale = staticMass; impulseScale = staticImpulse }
                else { bias = max(softBias * s, -contactSpeed); massScale = softMass; impulseScale = softImpulse }
            }
            val vn = relativeVelocity(a, b, rA, rB) dot n
            var impulse = -c.normalMass[k] * massScale * (vn + bias) - impulseScale * c.lamN[k]
            val total = max(c.lamN[k] + impulse, 0f)
            impulse = total - c.lamN[k]; c.lamN[k] = total
            c.maxLamN[k] = max(c.maxLamN[k], impulse)
            if (impulse != 0f) applyImpulse(a, b, rA, rB, n * impulse)
        }
        for (k in 0 until c.size) {
            val a = c.a[k]; val b = c.b[k]
            val rA = c.rA(k); val rB = c.rB(k)
            val limit = c.friction[k] * c.lamN[k]
            for (axis in 0..1) {
                val t = if (axis == 0) c.t1(k) else c.t2(k)
                val vt = relativeVelocity(a, b, rA, rB) dot t
                val old = if (axis == 0) c.lamT1[k] else c.lamT2[k]
                val total = (old - (if (axis == 0) c.t1Mass[k] else c.t2Mass[k]) * vt).coerceIn(-limit, limit)
                if (axis == 0) c.lamT1[k] = total else c.lamT2[k] = total
                val impulse = total - old
                if (impulse != 0f) applyImpulse(a, b, rA, rB, t * impulse)
            }
        }
    }

    private fun restitute() {
        val c = points
        for (k in 0 until c.size) {
            val e = c.restitution[k]
            if (e == 0f || c.relVel[k] > -restitutionThreshold || c.maxLamN[k] == 0f) continue
            val n = c.normal(k); val rA = c.rA(k); val rB = c.rB(k)
            val vn = relativeVelocity(c.a[k], c.b[k], rA, rB) dot n
            var impulse = -c.normalMass[k] * (vn + e * c.relVel[k])
            val total = max(c.lamN[k] + impulse, 0f)
            impulse = total - c.lamN[k]; c.lamN[k] = total
            if (impulse != 0f) applyImpulse(c.a[k], c.b[k], rA, rB, n * impulse)
        }
    }

    // ------------------------------------------------------------------ sleep

    private var parent = IntArray(0)
    private var islandClock = FloatArray(0)

    private fun root(i: Int): Int { var r = i; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }

    private fun updateSleep(dt: Float) {
        if (parent.size < count) { parent = IntArray(capacity); islandClock = FloatArray(capacity) }
        val lin2 = sleepLinearSpeed * sleepLinearSpeed; val ang2 = sleepAngularSpeed * sleepAngularSpeed
        for (i in 0 until count) {
            parent[i] = i; islandClock[i] = Float.MAX_VALUE
            if (types[i] != BodyType.DYNAMIC || sleeping[i]) continue
            val v2 = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]; val w2 = wx[i] * wx[i] + wy[i] * wy[i] + wz[i] * wz[i]
            sleepClock[i] = if (v2 > lin2 || w2 > ang2) 0f else sleepClock[i] + dt
        }
        val c = points
        for (k in 0 until c.size) {
            if (c.lamN[k] <= 0f) continue
            val a = c.a[k]; val b = c.b[k]
            if (a < 0) continue
            // A dynamic body pushed by a moving kinematic one stays awake.
            if (types[a] == BodyType.KINEMATIC && moving(a)) { sleepClock[b] = 0f; continue }
            if (types[a] != BodyType.DYNAMIC || types[b] != BodyType.DYNAMIC) continue
            val ra = root(a); val rb = root(b)
            if (ra != rb) parent[ra] = rb
        }
        for (i in 0 until count) if (types[i] == BodyType.DYNAMIC && !sleeping[i]) {
            val r = root(i); islandClock[r] = min(islandClock[r], sleepClock[i])
        }
        for (i in 0 until count) if (types[i] == BodyType.DYNAMIC && !sleeping[i] && islandClock[root(i)] >= timeToSleep) {
            sleeping[i] = true
            vx[i] = 0f; vy[i] = 0f; vz[i] = 0f; wx[i] = 0f; wy[i] = 0f; wz[i] = 0f
        }
    }
}

/** Contact points of one step in flat arrays, with their solver data and accumulated impulses. */
internal class ContactSet {
    var size = 0; private set
    private var cap = 0
    var a = IntArray(0); var b = IntArray(0); var key = LongArray(0); var feature = IntArray(0)
    var x = FloatArray(0); var y = FloatArray(0); var z = FloatArray(0)
    var nx = FloatArray(0); var ny = FloatArray(0); var nz = FloatArray(0)
    var sep = FloatArray(0); var adjSep = FloatArray(0)
    var lx = FloatArray(0); var ly = FloatArray(0); var lz = FloatArray(0)      // point in B's local frame
    var ra = FloatArray(0); var rb = FloatArray(0); var tg = FloatArray(0)      // 3 + 3 + 6 per point
    var normalMass = FloatArray(0); var t1Mass = FloatArray(0); var t2Mass = FloatArray(0)
    var lamN = FloatArray(0); var lamT1 = FloatArray(0); var lamT2 = FloatArray(0); var maxLamN = FloatArray(0)
    var relVel = FloatArray(0); var friction = FloatArray(0); var restitution = FloatArray(0)
    var static = BooleanArray(0)
    private val byKey = HashMap<Long, Int>()

    fun clear() { size = 0 }

    private fun ensure(n: Int) {
        if (n <= cap) return
        val c = max(n, max(64, cap * 2)); cap = c
        a = a.copyOf(c); b = b.copyOf(c); key = key.copyOf(c); feature = feature.copyOf(c)
        x = x.copyOf(c); y = y.copyOf(c); z = z.copyOf(c); nx = nx.copyOf(c); ny = ny.copyOf(c); nz = nz.copyOf(c)
        sep = sep.copyOf(c); adjSep = adjSep.copyOf(c); lx = lx.copyOf(c); ly = ly.copyOf(c); lz = lz.copyOf(c)
        ra = ra.copyOf(3 * c); rb = rb.copyOf(3 * c); tg = tg.copyOf(6 * c)
        normalMass = normalMass.copyOf(c); t1Mass = t1Mass.copyOf(c); t2Mass = t2Mass.copyOf(c)
        lamN = lamN.copyOf(c); lamT1 = lamT1.copyOf(c); lamT2 = lamT2.copyOf(c); maxLamN = maxLamN.copyOf(c)
        relVel = relVel.copyOf(c); friction = friction.copyOf(c); restitution = restitution.copyOf(c); static = static.copyOf(c)
    }

    /** Adds the points of one pair and picks up last step's impulses for matching points (warm start). */
    fun addAll(src: ContactPoints, ia: Int, ib: Int, pairKey: Long, mu: Float, e: Float, poseB: Pose, prev: ContactSet) {
        ensure(size + src.count)
        val prevStart = prev.byKey[pairKey]
        for (i in 0 until src.count) {
            val k = size++
            a[k] = ia; b[k] = ib; key[k] = pairKey; feature[k] = src.id[i]
            x[k] = src.x[i]; y[k] = src.y[i]; z[k] = src.z[i]
            nx[k] = src.nx[i]; ny[k] = src.ny[i]; nz[k] = src.nz[i]
            sep[k] = src.sep[i]; friction[k] = mu; restitution[k] = e
            val local = poseB.toLocal(Vec3(x[k], y[k], z[k]))
            lx[k] = local.x; ly[k] = local.y; lz[k] = local.z
            lamN[k] = 0f; lamT1[k] = 0f; lamT2[k] = 0f
            if (prevStart != null) {
                var bestJ = -1; var bestD = .05f * .05f
                var j = prevStart
                while (j < prev.size && prev.key[j] == pairKey) {
                    val dx = prev.lx[j] - local.x; val dy = prev.ly[j] - local.y; val dz = prev.lz[j] - local.z
                    val d2 = dx * dx + dy * dy + dz * dz
                    val same = prev.feature[j] == feature[k] && d2 < .1f * .1f
                    if (same) { bestJ = j; break }
                    if (d2 < bestD) { bestD = d2; bestJ = j }
                    j++
                }
                if (bestJ >= 0) { lamN[k] = prev.lamN[bestJ]; lamT1[k] = prev.lamT1[bestJ]; lamT2[k] = prev.lamT2[bestJ] }
            }
        }
    }

    fun setAnchors(k: Int, rA: Vec3, rB: Vec3) {
        ra[3 * k] = rA.x; ra[3 * k + 1] = rA.y; ra[3 * k + 2] = rA.z
        rb[3 * k] = rB.x; rb[3 * k + 1] = rB.y; rb[3 * k + 2] = rB.z
    }
    fun setTangents(k: Int, t1: Vec3, t2: Vec3) {
        tg[6 * k] = t1.x; tg[6 * k + 1] = t1.y; tg[6 * k + 2] = t1.z; tg[6 * k + 3] = t2.x; tg[6 * k + 4] = t2.y; tg[6 * k + 5] = t2.z
    }
    fun rA(k: Int) = Vec3(ra[3 * k], ra[3 * k + 1], ra[3 * k + 2])
    fun rB(k: Int) = Vec3(rb[3 * k], rb[3 * k + 1], rb[3 * k + 2])
    fun normal(k: Int) = Vec3(nx[k], ny[k], nz[k])
    fun t1(k: Int) = Vec3(tg[6 * k], tg[6 * k + 1], tg[6 * k + 2])
    fun t2(k: Int) = Vec3(tg[6 * k + 3], tg[6 * k + 4], tg[6 * k + 5])

    /** Indexes this step's points by pair, so that the next step can find them. */
    fun keep() {
        byKey.clear()
        for (k in 0 until size) if (k == 0 || key[k] != key[k - 1]) byKey.putIfAbsent(key[k], k)
    }
}
