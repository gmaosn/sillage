package sillage

import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.Character
import kotlin.math.*

/** Keys that drive a [Player] (several keys may do the same thing). */
class PlayerKeys {
    var forward = intArrayOf(Key.W, Key.UP)
    var back = intArrayOf(Key.S, Key.DOWN)
    var left = intArrayOf(Key.A, Key.LEFT)
    var right = intArrayOf(Key.D, Key.RIGHT)
    var jump = intArrayOf(Key.SPACE)
    /** Held: walk instead of run. */
    var walk = intArrayOf(Key.SHIFT)
}

/**
 * Names of the clips a [Player]'s model plays by itself, as named in Blender; a clip the model lacks is
 * simply skipped (set a name to null to never play it).
 */
class PlayerAnimations {
    var idle: String? = "idle"
    var walk: String? = "walk"
    var run: String? = "run"
    var jump: String? = "jump"
    var fall: String? = "fall"
    /** After a real fall (more than [landAfter] seconds in the air). */
    var land: String? = "land"
    var landAfter = .45f
    var push: String? = "push"
    /** Played by [Player.celebrate]. */
    var celebrate: String? = "celebrate"
    /** Played after [idleFor] seconds standing still. */
    var wave: String? = "wave"
    var idleFor = 7f
}

/**
 * A character the game plays: walks, runs, jumps, climbs low steps, pushes and stands on things, with
 * the keyboard and mouse ([controls]), the camera following and its model's clips chosen by itself.
 * Underneath it is a [Character] of the physics engine.
 */
class Player internal constructor(scene: Scene, val character: Character, var spawn: Vec3) : Node(scene, Mesh.EMPTY) {
    /** Running and walking speeds (m/s), and the upward speed of a jump. */
    var runSpeed = 4.4f
    var walkSpeed = 1.8f
    var jumpSpeed = 8f
    /** How fast it turns towards where it goes (radians per second). */
    var turnSpeed = 14f
    /** Driven by the keyboard and the camera; false to drive it yourself with [move] and [jump]. */
    var controls = true
    val keys = PlayerKeys()
    val animations = PlayerAnimations()
    /** Footsteps, jump and landing sounds. */
    var sounds = true

    /** Where its feet are; setting it teleports it. */
    override var position: Vec3
        get() = character.position
        set(v) { character.teleport(v) }

    /** Which way the camera looks at it — a shortcut for the scene's camera mode. */
    var view: CameraMode
        get() = scene.camera.mode
        set(v) { scene.camera.follow(this, v) }

    val onGround: Boolean get() = character.onGround
    val velocity: Vec3 get() = character.velocity
    /** True while it really moves (not only pushing against a wall). */
    val isMoving: Boolean get() = character.velocity.let { it.x * it.x + it.z * it.z } > .16f

    private var wish = Vec3.ZERO
    private var wishWalk = false
    private var wantJump = false
    private var coyote = 0f
    private var buffered = 0f
    private var airTime = 0f
    private var celebrating = 0f
    private var landing = 0f
    private var idleTime = 0f
    internal var forcedClip: String? = null

    /** Moves it this frame along [direction] (horizontal, length up to 1), walking or running. */
    fun move(direction: Vec3, walk: Boolean = false) { wish = Vec3(direction.x, 0f, direction.z); wishWalk = walk }

    /** Jumps (if on the ground, or just off a ledge; a jump asked a little early is kept a moment). */
    fun jump() { wantJump = true }

    /** Back to [spawn], still. */
    fun respawn() { position = spawn; coyote = 0f; buffered = 0f; airTime = 0f }

    /** Plays the celebrate clip for [seconds]. */
    fun celebrate(seconds: Number = .9) { celebrating = seconds.toFloat() }

    internal fun update(dt: Float) {
        val input = scene.input
        if (controls) {
            fun any(keys: IntArray) = keys.any { input.isDown(it) }
            val x = (if (any(keys.right)) 1f else 0f) - (if (any(keys.left)) 1f else 0f)
            val z = (if (any(keys.forward)) 1f else 0f) - (if (any(keys.back)) 1f else 0f)
            if (x != 0f || z != 0f) move(scene.camera.moveDirection(x, z), any(keys.walk))
            if (keys.jump.any { input.pressed(it) }) jump()
        }
        if (wantJump) buffered = .12f
        coyote = if (character.onGround) .1f else max(0f, coyote - dt)
        var up = 0f
        if (buffered > 0f && coyote > 0f) { up = jumpSpeed; buffered = 0f; coyote = 0f }
        buffered = max(0f, buffered - dt)
        character.update(wish * (if (wishWalk) walkSpeed else runSpeed), up, dt)
        // Facing: where it walks, or in first person where it looks.
        if (scene.camera.mode == CameraMode.FIRST_PERSON && scene.camera.target === this) rotation = Quat.rotationY(scene.camera.yaw * PI.toFloat() / 180f)
        else if (wish.lengthSquared() > .01f) {
            val goal = atan2(-wish.x, -wish.z); val now = yaw * PI.toFloat() / 180f
            var d = (goal - now) % (2 * PI.toFloat())
            if (d > PI) d -= 2 * PI.toFloat() else if (d < -PI) d += 2 * PI.toFloat()
            rotation = Quat.rotationY(if (abs(d) <= turnSpeed * dt) goal else now + sign(d) * turnSpeed * dt)
        }
        animate(dt)
        animator?.advance(dt)
        wish = Vec3.ZERO; wantJump = false
        if (position.y < scene.fallLimit) respawn()
    }

    /**
     * Picks its clip from how it really moves: up then down in the air, a landing after a real fall,
     * celebrating, pushing, walking or running by speed, idle, and a wave after a while standing still.
     */
    private fun animate(dt: Float) {
        val a = animator ?: return
        forcedClip?.let { a.play(it, fade = 0f, speed = 1f); return }
        val clips = a.model.clips
        fun has(name: String?) = name != null && name in clips
        val anim = animations
        val before = airTime
        airTime = if (onGround) 0f else airTime + dt
        if (onGround && before > anim.landAfter && has(anim.land)) landing = .22f
        celebrating = max(0f, celebrating - dt); landing = max(0f, landing - dt)
        val speed = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
        idleTime = if (speed < .3f && onGround && celebrating == 0f) idleTime + dt else 0f
        when {
            velocity.y > 0f && !onGround && has(anim.jump) -> a.play(anim.jump!!, loop = false, fade = .08f, speed = 1f)
            airTime > .12f && has(anim.fall) -> a.play(anim.fall!!, loop = false, fade = .2f, speed = 1f)
            airTime > .12f -> {}
            celebrating > 0f && has(anim.celebrate) -> a.play(anim.celebrate!!, loop = false, fade = .1f, speed = 1f)
            landing > 0f -> a.play(anim.land!!, loop = false, fade = .05f, speed = 1f)
            character.pushing != null && speed > .05f && has(anim.push) -> a.play(anim.push!!, fade = .15f, speed = (speed / 1.2f).coerceIn(.4f, 1.3f))
            speed > (walkSpeed + runSpeed) / 2 * .85f && has(anim.run) -> a.play(anim.run!!, fade = .12f, speed = (speed / runSpeed).coerceIn(.5f, 1.4f))
            speed > .3f && has(anim.walk) -> a.play(anim.walk!!, fade = .15f, speed = (speed / walkSpeed).coerceIn(.5f, 1.6f))
            speed > .3f && has(anim.run) -> a.play(anim.run!!, fade = .12f, speed = (speed / runSpeed).coerceIn(.5f, 1.4f))
            idleTime > anim.idleFor && has(anim.wave) -> {
                a.play(anim.wave!!, loop = false, fade = .25f, speed = 1f)
                if (a.time >= clips.getValue(anim.wave!!).duration) idleTime = 0f
            }
            has(anim.idle) -> a.play(anim.idle!!, fade = .25f, speed = 1f)
        }
    }
}
