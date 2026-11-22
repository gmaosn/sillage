package sillage.physics

import kotlin.concurrent.Volatile

import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min

/**
 * The physics step's thread pool, one per process, shared by every [PhysicsWorld]. [run] cuts n items
 * into fixed chunks that the caller and the workers take in turn, and returns when all are done.
 * Each item writes only its own data and merges happen in chunk order, so the simulation gives the
 * same result bit for bit whatever the number of threads. Workers spin a little between jobs (a step
 * runs dozens back to back), then sleep. A world stepping while another holds the pool runs alone.
 */
internal class ThreadWorkers(threads: Int) : Workers() {
    override val size = threads
    private val workers: Array<Thread>
    private val parked: Array<AtomicInteger> = Array(threads - 1) { AtomicInteger() }
    private val lock = ReentrantLock()

    // The job in progress, published whole (final fields behind one volatile write): a late worker still
    // holds the previous task and its own exhausted cursor, so it can never take or count a chunk of this one.
    private class Task(val generation: Int, val job: Job, val items: Int, val chunk: Int) {
        val cursor = AtomicInteger()
        val done = AtomicInteger()
        @Volatile var failure: Throwable? = null
    }
    @Volatile private var task: Task? = null
    private var generation = 0

    init {
        workers = Array(threads - 1) { k -> thread(isDaemon = true, name = "sillage-physics-${k + 1}") { loop(k) } }
    }

    /** Runs [job] over [n] items in chunks of [chunkSize], on every thread of the pool (or alone if it is busy). */
    override fun run(n: Int, chunkSize: Int, job: Job) {
        if (n <= 0) return
        if (size == 1 || n <= chunkSize || !lock.tryLock()) { serialChunks(n, chunkSize, job); return }
        try {
            val t = Task(++generation, job, n, chunkSize)
            task = t
            for (k in parked.indices) if (parked[k].get() == 1) LockSupport.unpark(workers[k])
            work(t)
            while (t.done.get() < n) Thread.onSpinWait()
            t.failure?.let { throw IllegalStateException("A physics worker failed", it) }
        } finally {
            lock.unlock()
        }
    }

    private fun work(t: Task) {
        val n = t.items; val c = t.chunk
        var did = 0
        while (true) {
            val start = t.cursor.getAndAdd(c)
            if (start >= n) break
            val end = min(n, start + c)
            try { t.job.run(start, end) } catch (e: Throwable) { t.failure = e }
            did += end - start
        }
        if (did > 0) t.done.addAndGet(did)
    }

    private fun loop(k: Int) {
        var seen = 0
        var idleSince = sillage.math.nanoTime()
        while (true) {
            val t = task
            if (t != null && t.generation != seen) { seen = t.generation; work(t); idleSince = sillage.math.nanoTime(); continue }
            if (sillage.math.nanoTime() - idleSince < SPIN_NANOS) { Thread.onSpinWait(); continue }
            parked[k].set(1)
            if (task?.generation == seen) LockSupport.park(this)
            parked[k].set(0)
            idleSince = sillage.math.nanoTime()
        }
    }

    private companion object { const val SPIN_NANOS = 200_000L }
}

internal actual fun platformWorkers(threads: Int): Workers = ThreadWorkers(threads)
