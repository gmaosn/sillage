package sillage.platform

import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands states from one reader thread to the game thread without locks: three buffers, the writer fills
 * its own and swaps it with the middle one; the reader swaps the middle one with its own when it is new.
 * Neither ever waits, nor sees a half-written state.
 */
internal class StateExchange {
    private val buffers = arrayOf(PadState(), PadState(), PadState())
    private val middle = AtomicInteger(1)
    private var back = 0        // writer's
    private var front = 2       // reader's

    /** The writer's buffer, to fill completely before [publish]. */
    fun writing(): PadState = buffers[back]

    fun publish() { back = middle.getAndSet(back or FRESH) and 3 }

    /** The newest state published (the same as last time if nothing new came). */
    fun latest(): PadState {
        if (middle.get() and FRESH != 0) front = middle.getAndSet(front) and 3
        return buffers[front]
    }

    private companion object { const val FRESH = 4 }
}
