package sillage.physics

import kotlin.math.min

/** Work on items [from, to): one chunk of a [Workers.run]. */
internal fun interface Job { fun run(from: Int, to: Int) }

/**
 * The physics step's pool of threads, one per process, shared by every [PhysicsWorld]. [run] cuts n items
 * into fixed chunks that the caller and the workers take in turn, and returns when all are done.
 * Each item writes only its own data and merges happen in chunk order, so the simulation gives the
 * same result bit for bit whatever the number of threads, one included: where the platform has a single
 * thread (a web page), the chunks simply run one after the other.
 */
internal abstract class Workers {
    abstract val size: Int

    /** Runs [job] over [n] items in chunks of [chunkSize], on every thread of the pool (or alone if it is busy). */
    abstract fun run(n: Int, chunkSize: Int, job: Job)

    private class Alone : Workers() {
        override val size get() = 1
        override fun run(n: Int, chunkSize: Int, job: Job) { if (n > 0) serialChunks(n, chunkSize, job) }
    }

    companion object {
        /** The same chunks as [run], one after the other on this thread. */
        fun serialChunks(n: Int, chunkSize: Int, job: Job) {
            var s = 0
            while (s < n) { val e = min(n, s + chunkSize); job.run(s, e); s = e }
        }
        /** All the cores but one or two left to the game (rendering, sound), at most 8 threads. */
        val shared: Workers by lazy {
            val threads = sillage.io.Storage.setting("sillage.threads")?.toIntOrNull()?.coerceIn(1, 64) ?: min(8, maxOf(1, sillage.io.Storage.spareProcessors - 1))
            if (threads <= 1) Alone() else platformWorkers(threads)
        }
    }
}

/** A pool of [threads] threads (the caller's included), where the platform has threads. */
internal expect fun platformWorkers(threads: Int): Workers
