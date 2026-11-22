package sillage.audio

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import kotlin.math.roundToInt

/**
 * Sound on FreeBSD (and DragonFly): the system's own mixer behind `/dev/dsp` (OSS). The device is opened,
 * told the format (16 bits, stereo, the mixer's rate) and written to by a thread, block after block.
 * No library: three ioctls and write(2).
 */
internal class OssOutput(path: String, private val mixer: Mixer, private val rate: Int, latencyMs: Int) : AudioOutput {
    override val device = path
    override val latency: Float
    private val arena = Arena.ofShared()
    private val fd: Int
    private val period = (rate / 200).coerceAtLeast(64)             // 5 ms blocks: quick reaction to the game
    @Volatile private var running = true
    private val thread: Thread

    init {
        fd = OPEN.invokeExact(fOpen, arena.allocateFrom(path), 1 /* O_WRONLY */, 0) as Int
        check(fd >= 0) { "cannot open $path (no sound card, or no right to use it)" }
        try {
            val v = arena.allocate(JAVA_INT)
            fun set(request: Long, value: Int, what: String): Int {
                v.set(JAVA_INT, 0, value)
                check((IOCTL.invokeExact(fIoctl, fd, request, v) as Int) >= 0) { "$path refuses $what" }
                return v.get(JAVA_INT, 0)
            }
            // Fragments of 2^10 bytes (256 frames), as many as the latency asked for.
            val fragments = (rate * latencyMs / 1000 * 4 / 1024).coerceIn(2, 32)
            set(SNDCTL_DSP_SETFRAGMENT, (fragments shl 16) or 10, "its buffer size")
            check(set(SNDCTL_DSP_SETFMT, AFMT_S16_LE, "16-bit samples") == AFMT_S16_LE) { "$path has no 16-bit samples" }
            check(set(SNDCTL_DSP_CHANNELS, 2, "stereo") == 2) { "$path is not stereo" }
            val got = set(SNDCTL_DSP_SPEED, rate, "$rate Hz")
            check(kotlin.math.abs(got - rate) <= rate / 100) { "$path plays at $got Hz, the mixer at $rate" }
            latency = fragments * 256f / rate
        } catch (e: Throwable) { CLOSE.invokeExact(fClose, fd) as Int; arena.close(); throw e }
        thread = Thread(::loop, "sillage-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }

    private fun loop() {
        val mix = FloatArray(2 * period)
        val pcm = arena.allocate(JAVA_SHORT, 2L * period)
        val bytes = 4L * period
        while (running) {
            mixer.render(mix, period)
            for (k in 0 until 2 * period) pcm.setAtIndex(JAVA_SHORT, k.toLong(), (mix[k] * 32767f).roundToInt().toShort())
            var done = 0L
            while (done < bytes && running) {
                val n = WRITE.invokeExact(fWrite, fd, pcm.asSlice(done), bytes - done) as Long
                if (n <= 0) { System.err.println("Sillage: sound interrupted ($device)"); running = false } else done += n
            }
        }
    }

    override fun close() {
        if (!running && !thread.isAlive) return
        running = false
        thread.join(500)
        CLOSE.invokeExact(fClose, fd) as Int
        arena.close()
    }

    private companion object {
        // OSS requests: _IOWR('P', n, int), the same numbers on FreeBSD and Linux.
        const val SNDCTL_DSP_SPEED = 0xC0045002L
        const val SNDCTL_DSP_SETFMT = 0xC0045005L
        const val SNDCTL_DSP_CHANNELS = 0xC0045006L
        const val SNDCTL_DSP_SETFRAGMENT = 0xC004500AL
        const val AFMT_S16_LE = 0x10

        private val linker: Linker = Linker.nativeLinker()
        private val libc = linker.defaultLookup()
        private fun fn(name: String): MemorySegment = libc.find(name).orElseThrow { IllegalStateException("Missing libc symbol $name") }
        private val fOpen = fn("open"); private val fClose = fn("close"); private val fWrite = fn("write"); private val fIoctl = fn("ioctl")
        // open and ioctl take their last argument as « ... »: the linker must know where the variable part begins.
        @JvmField val OPEN: MethodHandle = linker.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(2))
        @JvmField val IOCTL: MethodHandle = linker.downcallHandle(FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS), Linker.Option.firstVariadicArg(2))
        @JvmField val WRITE: MethodHandle = linker.downcallHandle(FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG))
        @JvmField val CLOSE: MethodHandle = linker.downcallHandle(FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    }
}
