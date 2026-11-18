package sillage.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

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
        val b = ByteBuffer.allocate(44 + data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(data.size * 2)
        for (x in data) b.putShort((x.coerceIn(-1f, 1f) * 32767f).let { Math.round(it) }.toShort())
        return b.array()
    }

    fun save(path: Path) { path.parent?.let { Files.createDirectories(it) }; Files.write(path, toWav()) }

    companion object {
        /** Reads a PCM WAV file (8, 16, 24 or 32-bit integers, or 32-bit floats), mono or stereo. */
        fun wav(bytes: ByteArray): Sound {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun tag() = String(ByteArray(4).also { b.get(it) }, Charsets.US_ASCII)
            require(bytes.size >= 12 && tag() == "RIFF") { "Not a WAV file" }
            b.int; require(tag() == "WAVE") { "Not a WAV file" }
            var format = 0; var channels = 0; var rate = 0; var bits = 0
            while (b.remaining() >= 8) {
                val id = tag(); val size = b.int
                require(size >= 0 && size <= b.remaining()) { "Truncated WAV chunk $id" }
                val start = b.position()
                when (id) {
                    "fmt " -> {
                        format = b.short.toInt() and 0xffff; channels = b.short.toInt(); rate = b.int; b.int; b.short
                        bits = b.short.toInt()
                        if (format == 0xfffe && size >= 40) { b.short; b.short; b.int; format = b.short.toInt() and 0xffff }
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
                                format == 3 -> b.getFloat(o)
                                bits == 8 -> ((b.get(o).toInt() and 255) - 128) / 128f
                                bits == 16 -> b.getShort(o) / 32768f
                                bits == 24 -> ((b.get(o).toInt() and 255) or ((b.get(o + 1).toInt() and 255) shl 8) or
                                    (b.get(o + 2).toInt() shl 16)) / 8388608f
                                else -> b.getInt(o) / 2147483648f
                            }
                        }
                        return Sound(out, channels, rate)
                    }
                }
                b.position(start + size + (size and 1))
            }
            throw IllegalArgumentException("WAV file without data")
        }

        /** Reads a WAV file from disk, or from the resources when no such file exists. */
        fun load(path: String): Sound = wav(
            if (Files.exists(Path.of(path))) Files.readAllBytes(Path.of(path))
            else Sound::class.java.getResourceAsStream("/$path")?.use { it.readAllBytes() } ?: error("Sound not found: $path"))
    }
}
