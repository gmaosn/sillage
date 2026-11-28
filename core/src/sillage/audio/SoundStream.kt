package sillage.audio

import sillage.io.Le

/**
 * A long sound kept in another form than its samples (compressed, or as the file's 16-bit numbers), decoded
 * by pieces while it plays: a minute of stereo at 44.1 kHz is 21 MB as floats, about 1 MB as Ogg Vorbis.
 * Every voice playing it reads it through a [SoundCursor] of its own.
 */
internal abstract class SoundSource(val frames: Int, val channels: Int, val rate: Int) {
    /** The first frame's samples: a loop going round reads them just after the last frame. */
    var head = FloatArray(channels); protected set

    /** A new reader, at no particular place yet. */
    abstract fun cursor(): SoundCursor

    /** Every sample, interleaved: exactly what playing it reads, decoded at once. */
    fun decodeAll(): FloatArray {
        val out = FloatArray(frames * channels)
        val c = cursor()
        c.seekTo(0)
        var at = 0
        while (at < frames) { val got = c.fill(out, at, frames - at); if (got == 0) break; at += got }
        return out
    }

    protected fun firstFrame(): FloatArray {
        val f = FloatArray(channels)
        if (frames > 0) { val c = cursor(); c.seekTo(0); c.fill(f, 0, 1) }
        return f
    }
}

/**
 * One voice's window on a [SoundSource]: the frames from [base] until [limit] are in [buffer] (interleaved,
 * frame [base] first). When the window holds the sound's last frame, [limit] is one more than [frames] and the
 * first frame follows the last one in the buffer, for a loop going round. [cover] moves the window; it
 * allocates nothing, so the audio thread may call it while it mixes.
 */
internal abstract class SoundCursor(source: SoundSource) {
    val frames = source.frames
    val channels = source.channels
    private val head = source.head
    val buffer = FloatArray((CAPACITY + 1) * channels)
    var base = 0; private set
    var limit = 0; private set
    private var end = 0                         // frames base until end are decoded into the buffer
    private var placed = false

    /** Places the decoding so that the next frame [produce] gives is [frame]. */
    protected abstract fun seek(frame: Int)
    /** Decodes the next [count] frames (fewer at the end) into [into] from frame index [at]; how many it gave. */
    protected abstract fun produce(into: FloatArray, at: Int, count: Int): Int

    internal fun seekTo(frame: Int) { seek(frame); placed = false }
    internal fun fill(into: FloatArray, at: Int, count: Int) = produce(into, at, count)

    /** Moves the window so that it holds frame [j] and the one after it (or the first, after the last). */
    fun cover(j: Int) {
        if (!placed || j < base || j > end + CAPACITY) {
            seek(j); base = j; end = j; placed = true
        } else if (j > base) {
            if (j < end) {
                buffer.copyInto(buffer, 0, (j - base) * channels, (end - base) * channels)
                base = j
            } else {
                // A little ahead (a high pitch): decode the frames between and drop them.
                var gap = j - end
                while (gap > 0) { val got = produce(buffer, 0, minOf(gap, CAPACITY)); if (got == 0) break; gap -= got }
                base = j; end = j
            }
        }
        val want = minOf(frames, base + CAPACITY)
        while (end < want) { val got = produce(buffer, end - base, want - end); if (got == 0) break; end += got }
        // Never short of the two frames asked for, whatever the decoding gave (silence rather than old samples).
        val needed = minOf(frames, j + 2)
        if (end < needed) { buffer.fill(0f, (end - base) * channels, (needed - base) * channels); end = needed }
        if (end >= frames) {
            for (c in 0 until channels) buffer[(frames - base) * channels + c] = head[c]
            limit = frames + 1
        } else limit = end
    }

    companion object {
        /** Frames decoded ahead at most: about 0.2 s, a few Vorbis packets. */
        const val CAPACITY = 8192
    }
}

/**
 * A long WAV file kept as its integer samples (8, 16 or 24 bits: half the size of floats, or less), turned into
 * floats while it plays, by the same arithmetic as [Sound.wav].
 */
internal class WavSource(private val pcm: ByteArray, private val bits: Int, frames: Int, channels: Int, rate: Int) :
    SoundSource(frames, channels, rate) {
    init { head = firstFrame() }
    override fun cursor(): SoundCursor = WavCursor(this)

    private class WavCursor(private val s: WavSource) : SoundCursor(s) {
        private var next = 0
        override fun seek(frame: Int) { next = frame }
        override fun produce(into: FloatArray, at: Int, count: Int): Int {
            val n = minOf(count, frames - next).coerceAtLeast(0)
            val width = s.bits / 8
            var o = next * channels * width
            for (i in at * channels until (at + n) * channels) { into[i] = wavSample(s.pcm, o, 1, s.bits); o += width }
            next += n
            return n
        }
    }
}

/** One sample of a WAV file's data at byte [o]: [format] 1 integers of [bits] bits, 3 32-bit floats. */
internal fun wavSample(bytes: ByteArray, o: Int, format: Int, bits: Int): Float = when {
    format == 3 -> Le.f32(bytes, o)
    bits == 8 -> ((bytes[o].toInt() and 255) - 128) / 128f
    bits == 16 -> Le.i16(bytes, o) / 32768f
    bits == 24 -> ((bytes[o].toInt() and 255) or ((bytes[o + 1].toInt() and 255) shl 8) or
        (bytes[o + 2].toInt() shl 16)) / 8388608f
    else -> Le.i32(bytes, o) / 2147483648f
}
