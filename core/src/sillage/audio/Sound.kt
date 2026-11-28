package sillage.audio

import sillage.io.Le
import sillage.io.Storage
import sillage.io.latin1

import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * Sounds read from files ([Sound.decode], [Sound.load]) longer than this many seconds stay compressed and are
 * decoded while they play (every voice on its own); shorter ones are decoded whole.
 */
private const val STREAM_ABOVE = 10f

/**
 * A sound in memory: floating-point samples in −1..1, mono or interleaved stereo, at [rate] Hz.
 * Sounds are immutable once built and can be played by many voices at once.
 *
 * A long sound read from a file ([decode], [load]: music, ambiences) stays compressed and is decoded while it
 * plays; its [data] is decoded whole the first time it is read.
 */
class Sound private constructor(@Volatile private var held: FloatArray?, internal val source: SoundSource?, val channels: Int, val rate: Int) {
    constructor(data: FloatArray, channels: Int = 1, rate: Int = 48_000) : this(data, null, channels, rate)

    init {
        require(channels == 1 || channels == 2) { "A sound is mono or stereo" }
        require(rate in 4_000..192_000 && (held?.size ?: 0) % channels == 0) { "Invalid sound" }
    }
    private val lock = Any()

    /** The samples, interleaved when stereo. */
    val data: FloatArray get() = held ?: sillage.io.locked(lock) { held ?: source!!.decodeAll().also { held = it } }
    val frames: Int get() = source?.frames ?: data.size / channels
    val duration: Float get() = frames.toFloat() / rate

    /** Whether it plays through a decoder of its own (a long sound from a file, kept compressed). */
    internal val streamed: Boolean get() = source != null

    /** A reader for one voice playing it, or null when its samples are in memory. */
    internal fun cursor(): SoundCursor? = source?.cursor()

    /** The sound as a 16-bit PCM WAV file. */
    fun toWav(): ByteArray {
        val b = ByteArray(44 + data.size * 2)
        fun tag(o: Int, t: String) { for (k in 0 until 4) b[o + k] = t[k].code.toByte() }
        tag(0, "RIFF"); Le.put32(b, 4, 36 + data.size * 2); tag(8, "WAVE")
        tag(12, "fmt "); Le.put32(b, 16, 16); Le.put16(b, 20, 1); Le.put16(b, 22, channels); Le.put32(b, 24, rate)
        Le.put32(b, 28, rate * channels * 2); Le.put16(b, 32, channels * 2); Le.put16(b, 34, 16)
        tag(36, "data"); Le.put32(b, 40, data.size * 2)
        for ((i, x) in data.withIndex()) Le.put16(b, 44 + 2 * i, (x.coerceIn(-1f, 1f) * 32767f).roundToInt())
        return b
    }

    /** Writes it as a WAV file at [path]. */
    fun save(path: String) = Storage.write(path, toWav())

    companion object {
        /** A sound played from [source], decoded while it plays. */
        internal fun streamed(source: SoundSource) = Sound(null, source, source.channels, source.rate)

        /** Reads a PCM WAV file (8, 16, 24 or 32-bit integers, or 32-bit floats), mono or stereo. */
        fun wav(bytes: ByteArray): Sound = wav(bytes, Float.POSITIVE_INFINITY)

        /** [wav], keeping the integer samples of a sound longer than [streamAbove] seconds, to be turned into floats while it plays. */
        internal fun wav(bytes: ByteArray, streamAbove: Float): Sound {
            var p = 0
            fun tag() = latin1(bytes, p, 4).also { p += 4 }
            fun int() = Le.i32(bytes, p).also { p += 4 }
            fun short() = Le.i16(bytes, p).also { p += 2 }
            require(bytes.size >= 12 && tag() == "RIFF") { "Not a WAV file" }
            int(); require(tag() == "WAVE") { "Not a WAV file" }
            var format = 0; var channels = 0; var rate = 0; var bits = 0
            while (bytes.size - p >= 8) {
                val id = tag(); val size = int()
                require(size >= 0 && size <= bytes.size - p) { "Truncated WAV chunk $id" }
                val start = p
                when (id) {
                    "fmt " -> {
                        format = short() and 0xffff; channels = short(); rate = int(); int(); short()
                        bits = short()
                        if (format == 0xfffe && size >= 40) { short(); short(); int(); format = short() and 0xffff }
                    }
                    "data" -> {
                        require(channels in 1..2) { "Only mono and stereo WAV files are read" }
                        val width = bits / 8
                        require((format == 1 && bits in intArrayOf(8, 16, 24, 32)) || (format == 3 && bits == 32)) {
                            "WAV format $format/$bits bits not supported" }
                        val n = size / width / channels * channels
                        // A long one in 8, 16 or 24 bits stays as it is, turned into floats while it plays.
                        if (n / channels > streamAbove * rate && format == 1 && bits <= 24)
                            return Sound(null, WavSource(bytes.copyOfRange(start, start + n * width), bits, n / channels, channels, rate), channels, rate)
                        val out = FloatArray(n)
                        for (i in 0 until n) out[i] = wavSample(bytes, start + i * width, format, bits)
                        return Sound(out, channels, rate)
                    }
                }
                p = start + size + (size and 1)
            }
            throw IllegalArgumentException("WAV file without data")
        }

        /**
         * Decodes a WAV or an Ogg Vorbis file, found from its first bytes. A sound longer than ten seconds
         * (music, an ambience) is kept as it is in the file and decoded while it plays.
         */
        fun decode(bytes: ByteArray): Sound = decode(bytes, STREAM_ABOVE)

        /** [decode], with the length in seconds above which the sound is decoded while it plays. */
        internal fun decode(bytes: ByteArray, streamAbove: Float): Sound =
            if (Vorbis.isOgg(bytes)) Vorbis.forPlay(bytes, streamAbove) else wav(bytes, streamAbove)

        /** Reads a WAV or Ogg Vorbis file from disk, or from the resources when no such file exists. */
        fun load(path: String): Sound = decode(sillage.io.Assets.bytes(path, "Sound"))
    }
}
