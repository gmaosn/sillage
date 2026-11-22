package sillage.audio

import sillage.io.Le
import sillage.io.Storage
import sillage.io.latin1

import kotlin.math.roundToInt


/**
 * A sound in memory: floating-point samples in −1..1, mono or interleaved stereo, at [rate] Hz.
 * Sounds are immutable once built and can be played by many voices at once.
 */
class Sound(val data: FloatArray, val channels: Int = 1, val rate: Int = 48_000) {
    init {
        require(channels == 1 || channels == 2) { "A sound is mono or stereo" }
        require(rate in 4_000..192_000 && data.size % channels == 0) { "Invalid sound" }
    }
    val frames: Int get() = data.size / channels
    val duration: Float get() = frames.toFloat() / rate

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
        /** Reads a PCM WAV file (8, 16, 24 or 32-bit integers, or 32-bit floats), mono or stereo. */
        fun wav(bytes: ByteArray): Sound {
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
                        val out = FloatArray(n)
                        for (i in 0 until n) {
                            val o = start + i * width
                            out[i] = when {
                                format == 3 -> Le.f32(bytes, o)
                                bits == 8 -> ((bytes[o].toInt() and 255) - 128) / 128f
                                bits == 16 -> Le.i16(bytes, o) / 32768f
                                bits == 24 -> ((bytes[o].toInt() and 255) or ((bytes[o + 1].toInt() and 255) shl 8) or
                                    (bytes[o + 2].toInt() shl 16)) / 8388608f
                                else -> Le.i32(bytes, o) / 2147483648f
                            }
                        }
                        return Sound(out, channels, rate)
                    }
                }
                p = start + size + (size and 1)
            }
            throw IllegalArgumentException("WAV file without data")
        }

        /** Decodes a WAV or an Ogg Vorbis file, found from its first bytes. */
        fun decode(bytes: ByteArray): Sound = if (Vorbis.isOgg(bytes)) Vorbis.decode(bytes) else wav(bytes)

        /** Reads a WAV or Ogg Vorbis file from disk, or from the resources when no such file exists. */
        fun load(path: String): Sound = decode(sillage.io.Assets.bytes(path, "Sound"))
    }
}
