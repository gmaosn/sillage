package sillage.audio

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import kotlin.math.roundToInt

/**
 * Sound on Windows through winmm's waveOut: the oldest of its sound interfaces, in every Windows, and three
 * calls are enough. A thread keeps a few small blocks queued (16 bits, stereo) and refills each as soon as
 * Windows has played it.
 */
internal class WaveOutOutput(private val mixer: Mixer, private val rate: Int, latencyMs: Int) : AudioOutput {
    override val device = "waveOut"
    override val latency: Float
    private val arena = Arena.ofShared()
    private val handle: MemorySegment
    private val period = (rate / 100).coerceAtLeast(64)             // 10 ms blocks
    private val blocks = (latencyMs / 10).coerceIn(3, 8)
    private val headers = Array(blocks) { arena.allocate(HEADER, 8) }
    @Volatile private var running = true
    private val thread: Thread

    init {
        val format = arena.allocate(18, 4)                          // WAVEFORMATEX
        format.set(JAVA_SHORT, 0, 1 /* PCM */); format.set(JAVA_SHORT, 2, 2); format.set(JAVA_INT, 4, rate); format.set(JAVA_INT, 8, rate * 4)
        format.set(JAVA_SHORT, 12, 4); format.set(JAVA_SHORT, 14, 16); format.set(JAVA_SHORT, 16, 0)
        val out = arena.allocate(ADDRESS)
        val code = OPEN.invokeExact(out, -1 /* WAVE_MAPPER: the user's usual output */, format, 0L, 0L, 0) as Int
        if (code != 0) { arena.close(); throw IllegalStateException("Windows has no sound output to give (waveOutOpen: $code)") }
        handle = out.get(ADDRESS, 0)
        for (h in headers) {
            h.fill(0); h.set(ADDRESS, 0, arena.allocate(4L * period, 2)); h.set(JAVA_INT, 8, 4 * period)
            PREPARE.invokeExact(handle, h, HEADER.toInt()) as Int
        }
        latency = blocks * period.toFloat() / rate
        thread = Thread(::loop, "sillage-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }

    private fun loop() {
        val mix = FloatArray(2 * period)
        while (running) {
            var wrote = false
            for (h in headers) {
                if (h.get(JAVA_INT, 24) and IN_QUEUE != 0) continue   // Windows still holds it
                mixer.render(mix, period)
                val data = h.get(ADDRESS, 0).reinterpret(4L * period)
                for (k in 0 until 2 * period) data.setAtIndex(JAVA_SHORT, k.toLong(), (mix[k] * 32767f).roundToInt().toShort())
                if ((WRITE.invokeExact(handle, h, HEADER.toInt()) as Int) != 0) { System.err.println("Sillage: sound interrupted (waveOut)"); running = false; break }
                wrote = true
            }
            if (!wrote) Thread.sleep(2)
        }
    }

    override fun close() {
        if (!running && !thread.isAlive) return
        running = false
        thread.join(500)
        RESET.invokeExact(handle) as Int
        for (h in headers) UNPREPARE.invokeExact(handle, h, HEADER.toInt()) as Int
        CLOSE.invokeExact(handle) as Int
        arena.close()
    }

    private companion object {
        const val HEADER = 48L                                      // WAVEHDR on 64-bit Windows; its flags are at 24
        const val IN_QUEUE = 0x10
        private val linker: Linker = Linker.nativeLinker()
        private val winmm: SymbolLookup by lazy { SymbolLookup.libraryLookup("winmm", Arena.global()) }
        private fun h(name: String, vararg args: MemoryLayout): MethodHandle =
            linker.downcallHandle(winmm.find(name).orElseThrow { IllegalStateException("Missing Windows function $name") }, FunctionDescriptor.of(JAVA_INT, *args))
        @JvmField val OPEN: MethodHandle = h("waveOutOpen", ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT)
        @JvmField val PREPARE: MethodHandle = h("waveOutPrepareHeader", ADDRESS, ADDRESS, JAVA_INT)
        @JvmField val UNPREPARE: MethodHandle = h("waveOutUnprepareHeader", ADDRESS, ADDRESS, JAVA_INT)
        @JvmField val WRITE: MethodHandle = h("waveOutWrite", ADDRESS, ADDRESS, JAVA_INT)
        @JvmField val RESET: MethodHandle = h("waveOutReset", ADDRESS)
        @JvmField val CLOSE: MethodHandle = h("waveOutClose", ADDRESS)
    }
}
