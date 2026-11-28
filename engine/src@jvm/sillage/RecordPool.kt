package sillage

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock

/**
 * The threads that record a frame's Vulkan commands besides the render thread (VulkanRenderer), one pool per
 * process. A frame opens a task, which wakes a few workers at once, adds its items as soon as they can be
 * recorded (some before the frame has chosen what it draws, the others after), and [finish] takes what is
 * left on the render thread, then waits for the rest. The workers take the items from the first, the render
 * thread from the last: add the long ones last, and the one thread sure to be there starts with them while
 * the others wake. Each item is a part of the frame recorded into a
 * command buffer of its own; the parts are submitted in a fixed order, so which thread recorded which never
 * shows in the picture. Slot 0 is the render thread, slots 1 to [size] − 1 the workers, each with its own
 * recorder (native scratch, command pools).
 *
 * A worker woken for a frame spins until the frame's task is finished (at most [OPEN_SPIN_NANOS]): its second
 * half of items comes once the copies are chosen, and a sleeping thread takes 50 to 100 µs to wake on a laptop
 * (measured), longer than most parts take to record. Then it spins a little more and sleeps. A frame that
 * finds the pool busy (another renderer drawing at the same time) records alone.
 */
internal class RecordPool private constructor(val size: Int) {
    fun interface Work { fun run(slot: Int, item: Int) }

    // A frame's items, published one count at a time (the items written before the volatile count): a worker
    // still holding an older task sees its cursor past its count and can never take an item of this one.
    private class Task(val work: Work) {
        val items = IntArray(MAX_ITEMS)
        @Volatile var published = 0
        @Volatile var closed = false
        val opened = System.nanoTime()
        val taken = AtomicIntegerArray(MAX_ITEMS)        // 1 once a thread took the item
        val cursor = AtomicInteger()                     // the workers' next item to try
        val done = AtomicInteger()
        @Volatile var failure: Throwable? = null

        /** The first item no thread took yet, taken now; -1 if none. */
        fun takeFirst(): Int {
            while (true) {
                val i = cursor.get()
                if (i >= published) return -1
                if (cursor.compareAndSet(i, i + 1) && taken.compareAndSet(i, 0, 1)) return i
            }
        }
    }

    private val lock = ReentrantLock()
    @Volatile private var task: Task? = null
    private var open: Task? = null                     // the render thread's task, between [open] and [finish]
    private val parked = Array(size - 1) { AtomicInteger() }
    private val workers = Array(size - 1) { k -> Thread({ loop(k) }, "sillage-record-${k + 1}").apply { isDaemon = true; start() } }

    /** Starts a frame's task and wakes [wake] sleeping workers for it; false when the pool is busy (record alone, on slot 0). */
    fun open(work: Work, wake: Int): Boolean {
        if (!lock.tryLock()) return false
        val t = Task(work)
        open = t; task = t
        wakeUp(wake)
        return true
    }

    /** Hands [item] to the workers, waking at most [wake] more of them that sleep. */
    fun add(item: Int, wake: Int = 0) {
        val t = open!!
        val n = t.published
        t.items[n] = item
        t.published = n + 1
        if (wake > 0) wakeUp(wake)
    }

    private fun wakeUp(wake: Int) {
        var woken = 0
        for (k in parked.indices) {
            if (woken >= wake) break
            if (parked[k].get() == 1) { LockSupport.unpark(workers[k]); woken++ }
        }
    }

    /** Takes the items no worker took yet (slot 0), waits for the others, then lets the pool go. */
    fun finish() {
        val t = open!!
        try {
            val n = t.published
            for (i in n - 1 downTo 0) if (t.taken.compareAndSet(i, 0, 1)) runItem(t, 0, i)
            while (t.done.get() < n) Thread.onSpinWait()
            t.failure?.let { throw IllegalStateException("A recording thread failed", it) }
        } finally {
            t.closed = true
            open = null
            lock.unlock()
        }
    }

    private fun runItem(t: Task, slot: Int, i: Int) {
        try { t.work.run(slot, t.items[i]) } catch (e: Throwable) { t.failure = e }
        t.done.incrementAndGet()
    }

    private fun loop(k: Int) {
        var idleSince = System.nanoTime()
        while (true) {
            val t = task
            if (t != null) {
                val i = t.takeFirst()
                if (i >= 0) { runItem(t, k + 1, i); idleSince = System.nanoTime(); continue }
                // The frame is still choosing what it draws: the rest of its items comes soon.
                if (!t.closed && System.nanoTime() - t.opened < OPEN_SPIN_NANOS) { Thread.onSpinWait(); idleSince = System.nanoTime(); continue }
            }
            if (System.nanoTime() - idleSince < SPIN_NANOS) { Thread.onSpinWait(); continue }
            parked[k].set(1)
            val again = task
            if (again == null || (again.closed || System.nanoTime() - again.opened >= OPEN_SPIN_NANOS) && again.cursor.get() >= again.published) LockSupport.park(this)
            parked[k].set(0)
            idleSince = System.nanoTime()
        }
    }

    companion object {
        /** Items a frame may hand out. */
        const val MAX_ITEMS = 64
        /** How long a worker spins after a frame's task is finished, before it sleeps. */
        private const val SPIN_NANOS = 30_000L
        /** The longest a worker spins for a task still open (a frame choosing among very many copies). */
        private const val OPEN_SPIN_NANOS = 2_000_000L

        /**
         * The process's pool: the render thread and at most [MAX_WORKERS] workers, never more than the cores
         * but one (`sillage.recordThreads` or `SILLAGE_RECORD_THREADS` sets the whole number from 2 on; a renderer
         * made with 1 records alone); null with a single core.
         */
        val shared: RecordPool? by lazy {
            val cores = java.lang.Runtime.getRuntime().availableProcessors()
            val threads = (System.getProperty("sillage.recordThreads") ?: System.getenv("SILLAGE_RECORD_THREADS"))?.toIntOrNull()?.takeIf { it >= 2 }?.coerceAtMost(64)
                ?: (1 + minOf(MAX_WORKERS, cores - 1))
            if (threads <= 1) null else RecordPool(threads)
        }
        private const val MAX_WORKERS = 6
    }
}
