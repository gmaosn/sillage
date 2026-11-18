package sillage.physics

import sillage.math.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Call [update] after [PhysicsWorld.step]: the character then stands where moving platforms really are.
 *
 * A walking character on the physics world, in the spirit of Jolt's virtual character: a capsule that
 * is moved by the game, not by forces. It slides along walls, walks up gentle slopes and slides down
 * steep ones, climbs low steps, sticks to the ground when walking downhill, stands on bodies (and
 * weighs on them) and pushes them according to their mass: a heavy crate slows it down, a ball does
 * not. A kinematic capsule follows it in the world so that bodies collide with it.
 *
 * [position] is the point between the feet.
 */
class Character(val world: PhysicsWorld, feet: Vec3, val radius: Float = .28f, val height: Float = 1.8f, var mass: Float = 70f) {
    init { require(height > 2 * radius) { "A character must be taller than its width" } }

    /** Gravity of the character alone (games like it stronger than the world's: snappier jumps). */
    var gravity = Vec3(0f, -22f, 0f)
    /** Steepest walkable slope, in degrees; steeper ground makes the character slide. */
    var maxSlope = 50f
    /** Highest step climbed without jumping (m). */
    var stepHeight = .35f
    /** How far the character is pulled down to stay on the ground when walking downhill (m). */
    var snapDistance = .3f
    /** Strongest push on a movable body (N): about what a person can push; heavier loads do not budge. */
    var pushForce = 400f

    var position: Vec3 = feet
        private set
    /** Actual velocity of the last update (m/s). */
    var velocity: Vec3 = Vec3.ZERO
        private set
    var onGround = false
        private set
    var groundNormal: Vec3 = Vec3.UP
        private set
    /** What the character stands on: a body, or null for a static mesh (then see [groundMaterial]). */
    var groundBody: Body? = null
        private set
    var groundMaterial: Material? = null
        private set
    /** The movable body pushed during the last update, if any. */
    var pushing: Body? = null
        private set

    private val shape = Capsule(radius, (height - 2 * radius) / 2)
    /** Kinematic capsule in the world: what other bodies bump into. */
    val body: Body = world.add(shape, feet.plus(0f, height / 2, 0f), type = BodyType.KINEMATIC, restitution = 0f).also { it.tag = this }
    private val touches = Touches()
    private var fall = 0f                        // vertical speed while airborne
    private var rest = 0f

    private val cosSlope get() = cos(Math.toRadians(maxSlope.toDouble())).toFloat()

    private fun center(feet: Vec3) = feet.plus(0f, height / 2, 0f)
    private fun gather(feet: Vec3, margin: Float = .02f) =
        world.touching(shape, Pose(center(feet), Vec3.X, Vec3.Y, Vec3.Z), margin, body.id, touches)

    /** Puts the character at [feet] at once (respawn). */
    fun teleport(feet: Vec3) {
        position = feet; velocity = Vec3.ZERO; fall = 0f; onGround = false
        body.position = center(feet); body.velocity = Vec3.ZERO
    }

    /**
     * Moves the character for [dt] seconds. [walk]: wanted horizontal velocity (m/s, its y is ignored);
     * [jumpSpeed] > 0 starts a jump at that vertical speed (the game decides when a jump is allowed).
     */
    fun update(walk: Vec3, jumpSpeed: Float, dt: Float) {
        val start = position
        pushing = null
        rest += dt.coerceIn(0f, .1f)
        var jump = jumpSpeed
        val h = 1f / 120f
        while (rest >= h) { substep(walk, jump, h); jump = 0f; rest -= h }
        velocity = if (dt > 0f) (position - start) / dt else Vec3.ZERO
        body.moveTo(center(position), dt.coerceAtLeast(1e-4f))
    }

    private fun substep(walk: Vec3, jumpSpeed: Float, h: Float) {
        val wasOnGround = onGround
        var vx = walk.x; var vz = walk.z
        var vy: Float
        if (jumpSpeed > 0f) { vy = jumpSpeed; fall = jumpSpeed; onGround = false }
        else if (onGround) {
            // Follow the ground plane: no bounce when walking downhill, no slowdown uphill.
            val n = groundNormal
            vy = -(n.x * vx + n.z * vz) / n.y
            fall = 0f
        } else { fall += gravity.y * h; vy = fall }
        val intended = Vec3(vx * h, 0f, vz * h)
        var blockedByWall = false
        // Push out of everything, at most four rounds. The pushes share a budget: what this move can have
        // pushed in, plus MAX_PUSH. Deep inside a wall (spawned or teleported there), the character thus
        // comes out gradually, instead of being thrown metres in one go — through a floor.
        fun resolve(start: Vec3, pushBodies: Boolean): Vec3 {
            var q = start
            var budget = (q - position).length() + MAX_PUSH
            fun spend(v: Vec3): Vec3 {
                val l = v.length()
                if (l <= budget) { budget -= l; return v }
                val r = if (l > 0f) v * (budget / l) else v
                budget = 0f; return r
            }
            for (round in 0 until 4) {
                gather(q)
                var moved = false
                // Touching walkable ground (or standing): a push from above never drives the character into
                // it — deep in a block, its shortest way out may be down, through the floor.
                var grounded = wasOnGround
                for (k in 0 until touches.size) if (touches.ny[k] >= cosSlope && touches.sep[k] <= .02f) grounded = true
                for (k in 0 until touches.size) {
                    val s = touches.sep[k]
                    val b = touches.body[k]
                    val dynamic = b >= 0 && world.types[b] == BodyType.DYNAMIC
                    if (s >= (if (dynamic) .005f else 0f)) continue
                    val n = touches.normal(k)
                    // Leave a hair of space against movable bodies: otherwise the kinematic capsule, which
                    // follows the character, would overlap them and the solver would shove them away.
                    val skin = if (dynamic) .005f else 0f
                    if (n.y >= cosSlope) {
                        // Walkable: rise straight up, the slope does not drag the character down.
                        q += spend(Vec3(0f, (-s + skin) / n.y, 0f))
                        if (fall < 0f) fall = 0f
                    } else {
                        if (dynamic && round == 0 && pushBodies) push(Body(world, b), n, touches.point(k), walk, h)
                        else if (!dynamic && n.y > -.3f) blockedByWall = true
                        var push = n * (-s + skin)
                        if (grounded && push.y < 0f) push = Vec3(push.x, 0f, push.z)
                        q += spend(push)
                        if (n.y < -.3f && fall > 0f) fall = 0f       // ceiling
                    }
                    moved = true
                }
                if (!moved) break
            }
            return q
        }
        var p = resolve(position + Vec3(vx, vy, vz) * h, true)
        // A gap narrower than the capsule: the pushes out of each side undo each other and leave it
        // squeezed. Then the sideways move is refused; the fall goes on (along a corner of two walls),
        // through the same pushes; if even that squeezes, the character stands on the rims.
        var onRims = false
        val squeeze = embedded(p)
        if (squeeze > .005f) {
            val before = embedded(position)
            if (before < squeeze - .001f) {
                // The fall alone: straight down first (between upright walls it squeezes no more — pushed out
                // of both walls in turn, it would sink a hair deeper and be refused), else pushed out as usual.
                val down = if (vy < 0f && before <= .005f) {
                    val straight = position.plus(0f, vy * h, 0f)
                    if (embedded(straight) <= max(before, .002f) + 1e-4f) straight
                    else resolve(straight, false).takeIf { embedded(it) <= max(before, .002f) }
                } else null
                if (down != null) p = down
                else {
                    if (p.y < position.y - 1e-5f) { onRims = true; fall = 0f }
                    p = position
                }
            }
        }
        // Low step: up, forward, down.
        if (wasOnGround && blockedByWall && intended.lengthSquared() > 1e-10f) {
            val progress = Vec3(p.x - position.x, 0f, p.z - position.z) dot intended.normalized()
            if (progress < intended.length() * .5f) climbStep(intended)?.let { p = it }
        }
        // Stick to the ground when walking down a slope or off a small ledge.
        if (wasOnGround && jumpSpeed <= 0f && fall <= 0f) {
            groundUnder(p, snapDistance)?.let { hit ->
                val y = rest(p, hit); val drop = p.y - y
                if (drop > 0f && drop <= snapDistance && hit.normal.y >= cosSlope) p = Vec3(p.x, y, p.z)
            }
        }
        // Ground: walkable contacts just below.
        gather(p, .04f)
        var gn = Vec3.ZERO; var ground = -2; var material: Material? = null; var groundPoint = p
        for (k in 0 until touches.size) {
            val n = touches.normal(k)
            if (n.y < cosSlope || touches.sep[k] > .04f) continue
            gn += n; ground = touches.body[k]; material = touches.material[k]; groundPoint = touches.point(k)
        }
        onGround = (ground != -2 && fall <= .01f) || onRims
        if (onRims && ground == -2) gn = Vec3.UP
        if (onGround) {
            groundNormal = gn.normalized(); groundMaterial = material
            groundBody = if (ground >= 0) Body(world, ground) else null
            // The character weighs on what it stands on.
            groundBody?.let { g -> if (g.type == BodyType.DYNAMIC) g.applyImpulse(world.gravity * (mass * h), groundPoint) }
        } else { groundBody = null; groundMaterial = null; groundNormal = Vec3.UP }
        position = p
    }

    /**
     * Pushes a movable body like a person would: along the walking direction, with at most [pushForce],
     * until the body moves as fast as the character wants to walk. The character itself is held by
     * the body, so a heavy crate slows it down and one too heavy does not move at all.
     */
    private fun push(b: Body, n: Vec3, point: Vec3, walk: Vec3, h: Float) {
        val d = Vec3(-n.x, 0f, -n.z)
        if (d.lengthSquared() < 1e-6f) return
        val dir = d.normalized()
        val wanted = walk.x * dir.x + walk.z * dir.z
        if (wanted <= 0f) return
        pushing = b
        val missing = wanted - (b.velocity dot dir)
        if (missing <= 0f) return
        b.applyImpulse(dir * min(pushForce * h, b.mass * missing), point)
    }

    /**
     * What the character's rounded bottom would rest on below [feet], within [depth]: a sphere a hair
     * thinner than the capsule swept down from 5 cm above. On a slope or an edge it rests a little higher
     * than a single ray would say, and the contact normal tells whether it can stand there.
     */
    private fun groundUnder(feet: Vec3, depth: Float): RayHit? {
        val r = radius * .98f
        val hit = world.raycast(feet.plus(0f, r + LIFT, 0f), DOWN, LIFT + depth, radius = r, filter = { it.id != body.id }) ?: return null
        return if (hit.distance > 0f) hit else null
    }

    /** Feet height when resting on [hit] from [groundUnder] at [feet]. */
    private fun rest(feet: Vec3, hit: RayHit) = feet.y + LIFT - hit.distance

    /** Deepest overlap with what does not move by itself (static, kinematic, meshes), in metres. */
    private fun embedded(feet: Vec3): Float {
        gather(feet, 0f)
        var worst = 0f
        for (k in 0 until touches.size) {
            val b = touches.body[k]
            if (b >= 0 && world.types[b] == BodyType.DYNAMIC) continue
            worst = max(worst, -touches.sep[k])
        }
        return worst
    }

    private fun penetrating(feet: Vec3): Boolean {
        gather(feet, 0f)
        for (k in 0 until touches.size) if (touches.sep[k] < -.003f) return true
        return false
    }

    /**
     * Tries to walk up a low step: up, forward, down onto it. Forward by this substep's motion, or a few
     * centimetres more when the rounded bottom would only rest on the step's edge, too steep to stand on.
     * Returns the new feet position, or null (a wall, a steep slope, nothing higher).
     */
    private fun climbStep(intended: Vec3): Vec3? {
        val up = position.plus(0f, stepHeight, 0f)
        if (penetrating(up)) return null
        val dir = intended.normalized(); val len = intended.length()
        for (forward in floatArrayOf(len, .06f, .1f, .15f)) {
            if (forward < len) continue
            val q = up + dir * forward
            if (penetrating(q)) return null
            val hit = groundUnder(q, stepHeight + .05f) ?: return null
            // The step's height is where the capsule touches it (its edge or top), not where it would rest.
            if (hit.point.y - position.y > stepHeight + .01f) return null
            if (hit.normal.y < cosSlope) continue
            val y = rest(q, hit)
            val rise = y - position.y
            return if (rise > .005f && rise <= stepHeight + .01f) Vec3(q.x, y, q.z) else null
        }
        return null
    }

    private companion object {
        val DOWN = Vec3(0f, -1f, 0f)
        const val LIFT = .05f
        const val MAX_PUSH = .25f
    }
}
