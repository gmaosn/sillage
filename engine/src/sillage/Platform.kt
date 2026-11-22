package sillage

import sillage.audio.Mixer

// What the engine's shared code asks of each platform besides a window, drawing and sound output.

/** The controllers this system can see (none where there is no way to read them yet). */
internal expect fun platformPads(): PadSource

/** A result being worked out on the side (another thread where there are threads; when first asked for, otherwise). */
interface Pending<T> {
    val isDone: Boolean
    /** The result, waiting for it if need be. */
    fun get(): T
}

/**
 * Works [work] out without holding up the game where the platform has threads (decoding a long sound,
 * painting a texture); in a page, it is done when the result is first asked for.
 */
fun <T> background(work: () -> T): Pending<T> = platformBackground(work)

internal expect fun <T> platformBackground(work: () -> T): Pending<T>

/** [f] of each of [items], in their order, on several threads where there are some. */
internal expect fun <T, R> parallelMap(items: List<T>, f: (T) -> R): List<R>

/** One value per thread, made by [make] the first time a thread asks. */
internal expect class PerThread<T : Any>(make: () -> T) { fun get(): T }
