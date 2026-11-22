package sillage

import sillage.audio.AudioOutput
import sillage.audio.Mixer

internal actual fun platformPads(): PadSource = PadSource.NONE

internal actual fun <T> platformBackground(work: () -> T): Pending<T> = object : Pending<T> {
    private var done = false; private var value: Any? = null
    override val isDone get() = true            // one thread: ready as soon as it is asked for
    @Suppress("UNCHECKED_CAST")
    override fun get(): T { if (!done) { value = work(); done = true }; return value as T }
}

internal actual fun <T, R> parallelMap(items: List<T>, f: (T) -> R): List<R> = items.map(f)

internal actual class PerThread<T : Any> actual constructor(make: () -> T) {
    private val value by lazy(make)
    actual fun get(): T = value
}
