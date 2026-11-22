package sillage.audio

import sillage.math.Vec3
import sillage.platform.Native
import sillage.platform.Native.fn
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

/** ALSA playback through FFM: a thread mixes small blocks and writes them as 16-bit stereo. */
internal class AlsaOutput(override val device: String, private val mixer: Mixer, private val rate: Int, latencyMs: Int) : AudioOutput {
    private val arena = Arena.ofShared()
    private val pcm: MemorySegment
    private val period: Int
    override val latency: Float
    @Volatile private var running = true
    private val thread: Thread

    init {
        val handle = arena.allocate(ADDRESS)
        check(OPEN.invokeExact(fOpen, handle, arena.allocateFrom(device), 0 /* playback */, 0) as Int, "snd_pcm_open($device)")
        pcm = handle.get(ADDRESS, 0)
        try {
            check(SET_PARAMS.invokeExact(fSetParams, pcm, 2 /* S16_LE */, 3 /* RW_INTERLEAVED */, 2, rate, 1, latencyMs * 1000) as Int,
                "snd_pcm_set_params")
            val sizes = arena.allocate(JAVA_LONG, 2)
            check(GET_PARAMS.invokeExact(fGetParams, pcm, sizes, sizes.asSlice(8)) as Int, "snd_pcm_get_params")
            val buffer = sizes.get(JAVA_LONG, 0).toInt(); val p = sizes.get(JAVA_LONG, 8).toInt()
            // Mix in blocks of at most 5 ms whatever the period: quicker reaction to the game.
            period = p.coerceIn(64, rate / 200)
            latency = buffer.toFloat() / rate
        } catch (e: Throwable) { CLOSE.invokeExact(fClose, pcm) as Int; arena.close(); throw e }
        thread = Thread(::loop, "sillage-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }

    private fun loop() {
        val mix = FloatArray(2 * period)
        val pcm16 = arena.allocate(JAVA_SHORT, 2L * period)
        val start = System.nanoTime(); var written = 0L
        while (running) {
            mixer.render(mix, period)
            for (k in 0 until 2 * period) pcm16.setAtIndex(JAVA_SHORT, k.toLong(), Math.round(mix[k] * 32767f).toShort())
            var done = 0
            while (done < period && running) {
                val n = WRITEI.invokeExact(fWritei, pcm, pcm16.asSlice(4L * done), (period - done).toLong()) as Long
                if (n < 0) {
                    // Underrun (the game stalled) or suspend: recover and go on; give up on anything else.
                    if ((RECOVER.invokeExact(fRecover, pcm, n.toInt(), 1) as Int) < 0) { System.err.println("Sillage : son interrompu (${error(n.toInt())})"); running = false }
                } else done += n.toInt()
            }
            written += period
            // A device that takes everything at once (the null device) would make this thread spin: keep real time.
            val ahead = written * 1_000_000_000L / rate - (System.nanoTime() - start) - 100_000_000L
            if (ahead > 0) Thread.sleep(ahead / 1_000_000L, (ahead % 1_000_000L).toInt())
        }
    }

    override fun close() {
        if (!running && !thread.isAlive) return
        running = false
        thread.join(500)
        DROP.invokeExact(fDrop, pcm) as Int
        CLOSE.invokeExact(fClose, pcm) as Int
        arena.close()
    }

    private fun check(code: Int, what: String) { if (code < 0) throw IllegalStateException("$what: ${error(code)}") }
    private fun error(code: Int) = Native.string(STRERROR.invokeExact(fStrerror, code) as MemorySegment)

    private companion object {
        val lib = Native.library("libasound.so.2", "libasound.so")
        val fOpen = lib.fn("snd_pcm_open"); val fSetParams = lib.fn("snd_pcm_set_params"); val fGetParams = lib.fn("snd_pcm_get_params")
        val fWritei = lib.fn("snd_pcm_writei"); val fRecover = lib.fn("snd_pcm_recover"); val fDrop = lib.fn("snd_pcm_drop")
        val fClose = lib.fn("snd_pcm_close"); val fStrerror = lib.fn("snd_strerror")
        private val linker = java.lang.foreign.Linker.nativeLinker()
        private fun h(ret: java.lang.foreign.MemoryLayout?, vararg a: java.lang.foreign.MemoryLayout): MethodHandle =
            linker.downcallHandle(if (ret == null) FunctionDescriptor.ofVoid(*a) else FunctionDescriptor.of(ret, *a))
        // The function address is the handle's implicit first argument (same scheme as sillage.vk.Sig).
        @JvmField val OPEN: MethodHandle = h(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)
        @JvmField val SET_PARAMS: MethodHandle = h(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        @JvmField val GET_PARAMS: MethodHandle = h(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
        @JvmField val WRITEI: MethodHandle = h(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG)
        @JvmField val RECOVER: MethodHandle = h(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)
        @JvmField val DROP: MethodHandle = h(JAVA_INT, ADDRESS)
        @JvmField val CLOSE: MethodHandle = h(JAVA_INT, ADDRESS)
        @JvmField val STRERROR: MethodHandle = h(ADDRESS, JAVA_INT)
    }
}

/** The system's sound output: ALSA on Linux, the sound device on the BSDs. « default » is each system's usual one. */
internal actual fun platformAudioOutput(device: String, mixer: Mixer, rate: Int, latencyMs: Int): AudioOutput = when {
    sillage.platform.Os.freebsd -> OssOutput(if (device == "default") "/dev/dsp" else device, mixer, rate, latencyMs)
    sillage.platform.Os.linux -> AlsaOutput(device, mixer, rate, latencyMs)
    else -> throw IllegalStateException("no sound output yet on " + sillage.platform.Os.label)
}
