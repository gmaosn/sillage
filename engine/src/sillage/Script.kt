package sillage

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.createCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume

/**
 * A script: a block that goes on over several frames, written in order instead of with flags and
 * timers. Made by [Scene.script]; inside it, [wait], [waitUntil], [nextFrame] and [waitFor] let the game
 * run meanwhile.
 *
 * ```
 * script {
 *     banner.visible = true
 *     wait(2)
 *     waitUntil { player!!.onGround }
 *     player!!.celebrate(1.5)
 * }
 * ```
 *
 * Scripts run on the game's thread, at the scene's update, in the order they were made: no locks, the
 * same result every time. They follow the scene's clock, so they stop while the game is paused. Only
 * these waiting functions can be called inside (other suspending functions would never come back).
 */
@RestrictsSuspension
class Script internal constructor(private val scene: Scene) {
    internal var continuation: Continuation<Unit>? = null
    private var resumeAt = 0f
    private var until: (() -> Boolean)? = null

    /** True once the block has ended, was [stop]ped, or failed. */
    var done = false
        private set

    /** Ends the script where it waits; it will not go on. */
    fun stop() { done = true; continuation = null }

    /** Waits [seconds] of the scene's time. */
    suspend fun wait(seconds: Number) {
        val s = seconds.toFloat()
        require(s >= 0f && s.isFinite()) { "wait takes a number of seconds, not $seconds" }
        resumeAt = scene.time + s; until = null
        suspend()
    }

    /** Waits until [condition] holds, checked once per frame (now first: it may not wait at all). */
    suspend fun waitUntil(condition: () -> Boolean) {
        if (condition()) return
        resumeAt = 0f; until = condition
        suspend()
    }

    /** Waits for the next frame. */
    suspend fun nextFrame() { resumeAt = scene.time; until = null; suspend() }

    /** Waits until [tween] has finished. */
    suspend fun waitFor(tween: Tween) = waitUntil { tween.done }

    private suspend fun suspend() = suspendCoroutineUninterceptedOrReturn<Unit> { c -> continuation = c; COROUTINE_SUSPENDED }

    internal fun start(block: suspend Script.() -> Unit) {
        continuation = block.createCoroutine(this, object : Continuation<Unit> {
            override val context: CoroutineContext get() = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                done = true; continuation = null
                result.exceptionOrNull()?.let { throw it }
            }
        })
        resumeAt = 0f
    }

    /** Runs the script on to its next wait, if the wait is over. Called once per frame by the scene. */
    internal fun tick() {
        val c = continuation ?: return
        if (done) return
        if (scene.time < resumeAt) return
        val u = until
        if (u != null && !u()) return
        until = null
        continuation = null
        c.resume(Unit)
    }
}

/** How a [Tween] goes from start to end: the share of the way done for a share of the time. */
enum class Ease {
    /** Even speed. */
    LINEAR,
    /** Starts slowly. */
    IN,
    /** Ends slowly. */
    OUT,
    /** Starts and ends slowly (the default: things look carried). */
    IN_OUT,
    /** Goes a little past the end, then comes back. */
    SPRING;

    /** The eased share for [t] in 0..1. */
    fun at(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x
            IN -> x * x * x
            OUT -> 1f - (1f - x) * (1f - x) * (1f - x)
            IN_OUT -> if (x < .5f) 4f * x * x * x else 1f - 4f * (1f - x) * (1f - x) * (1f - x)
            SPRING -> { val c = 1.70158f * 1.2f; val y = x - 1f; 1f + (c + 1f) * y * y * y + c * y * y }
        }
    }
}

/**
 * A change spread over time: [Scene.tween], [Node.moveTo], [Node.scaleTo], and the HUD's fades
 * ([Scene.fade], [Scene.fadeIn], [Scene.fadeOut]). It follows the scene's clock.
 */
class Tween internal constructor(private val start: Float, private val duration: Float, private val ease: Ease,
                                 private val apply: (Float) -> Unit, private val finished: () -> Unit) {
    private var begun = false
    /** True once it reached its end or was [stop]ped. */
    var done = false
        private set

    /** Stops where it is. */
    fun stop() { done = true }

    internal fun tick(time: Float) {
        if (done || time < start) return
        begun = true
        val t = if (duration <= 0f) 1f else ((time - start) / duration).coerceIn(0f, 1f)
        apply(ease.at(t))
        if (t >= 1f) { done = true; finished() }
    }
}
