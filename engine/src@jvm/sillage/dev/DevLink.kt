package sillage.dev

import sillage.CameraMode
import sillage.Scene
import sillage.math.Vec3
import java.util.concurrent.atomic.AtomicReference

/**
 * What `./sillage dev` and the game's loop share. The loop runs the first build of the game; each
 * reload calls the game's `main` again in a new class loader, and its call to `game()` lands here
 * ([offer]) instead of opening a second window: the loop takes the new scene at the end of a frame.
 */
internal object DevLink {
    /** True while `./sillage dev` hosts the game. */
    @Volatile var enabled = false
    /** True once the loop runs: later calls to `game()` hand their scene over. */
    @Volatile var looping = false
    /** Set to end the loop: 0 to quit, [RESTART] when the engine itself changed. */
    @Volatile var quitCode: Int? = null
    /** The last error to show over the game (compilation, building the scene, an update), or null. */
    @Volatile var error: String? = null

    /** When the change being reloaded was seen (System.nanoTime), to say how long it took to reach the screen. */
    @Volatile var changedAt = 0L
    /** What the reload brings: "code" or "resources". */
    @Volatile var reloadWhat = "code"

    const val RESTART = 75

    private val pending = AtomicReference<(Scene.() -> Unit)?>(null)

    /** In dev mode, once the loop runs: takes the reloaded game's scene and returns true. */
    fun offer(setup: Scene.() -> Unit): Boolean {
        if (!enabled || !looping) return false
        pending.set(setup)
        return true
    }

    /** The scene a reload brought, once. */
    fun take(): (Scene.() -> Unit)? = pending.getAndSet(null)

    /**
     * What a scene looked like right after it was built: a reload keeps the player, the camera and the
     * hour where they were, unless the game's code changed them.
     */
    class Initial(scene: Scene) {
        val spawn: Vec3? = scene.player?.spawn
        val mode: CameraMode = scene.camera.mode
        val distance = scene.camera.distance
        val time: Float? = scene.lighting.time
        val dayLength = scene.lighting.dayLength
    }

    /** Carries the player, the camera and the hour from [old] to [fresh], where the code left them as they were. */
    fun carryOver(old: Scene, oldInitial: Initial, fresh: Scene, freshInitial: Initial) {
        val p = old.player; val q = fresh.player
        if (p != null && q != null && oldInitial.spawn == freshInitial.spawn) {
            q.position = p.position
            q.rotation = p.rotation
        }
        val a = old.camera; val b = fresh.camera
        if (oldInitial.mode == freshInitial.mode && oldInitial.distance == freshInitial.distance) {
            b.mode = a.mode; b.distance = a.distance
            b.yaw = a.yaw; b.elevation = a.elevation; b.pitch = a.pitch
        }
        if (oldInitial.time == freshInitial.time && oldInitial.dayLength == freshInitial.dayLength && old.lighting.time != null)
            fresh.lighting.time = old.lighting.time
    }
}
