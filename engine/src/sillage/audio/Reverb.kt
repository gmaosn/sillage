package sillage.audio

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How a place sounds: [decay] is the time (seconds) the echo takes to fall by 60 dB, [wet] how loud it is
 * against the sound itself, [damping] (0 to 1) how much the walls swallow the highs, the echo growing
 * duller as it fades. Sounds placed in the world are sent to it, a little more when far away (the room
 * then weighs more than the sound itself, as in reality).
 */
class Reverb(val decay: Float, val wet: Float, val damping: Float = .5f) {
    init { require(decay > 0f && wet >= 0f && damping in 0f..1f) { "Reverb: decay > 0, wet ≥ 0, damping in 0..1" } }

    companion object {
        /** Open air: a short, faint echo from the ground and what is around. */
        val OUTDOOR = Reverb(decay = .5f, wet = .1f, damping = .75f)
        val ROOM = Reverb(decay = .6f, wet = .3f, damping = .5f)
        val HALL = Reverb(decay = 1.9f, wet = .35f, damping = .35f)
        val CAVE = Reverb(decay = 3.2f, wet = .45f, damping = .2f)
    }
}

/**
 * The mixer's reverberation: a feedback delay network of 8 lines mixed by a Householder matrix (every line
 * feeds every other, energy kept), each line's gain set so that the loop falls by 60 dB in [Reverb.decay]
 * seconds, a low-pass in each loop for the damping. Mono in, stereo out (two orthogonal sums of the lines).
 * Its settings glide from block to block, so a change of place does not click.
 */
internal class ReverbUnit(private val rate: Int) {
    private val n = 8
    private val lengths = IntArray(n) { i -> max(8, (DELAYS_MS[i] * rate / 1000f).toInt()) }
    private val lines = Array(n) { FloatArray(lengths[it]) }
    private val pos = IntArray(n)
    private val low = FloatArray(n)
    private val pre = FloatArray(max(8, (PRE_DELAY * rate).toInt()))
    private var prePos = 0
    private val x = FloatArray(n)
    private val gain = FloatArray(n)
    private var wet = 0f; private var damp = 0f
    private var decay = 1f
    private var started = false

    /** Adds the reverberation of [send] (mono, [frames] samples) to [out] (interleaved stereo), towards [r]. */
    fun process(send: FloatArray, out: FloatArray, frames: Int, r: Reverb) {
        // Glide the settings over about 100 ms (blocks are a few ms).
        val t = if (started) 1f - exp(-frames / (.1f * rate)) else 1f
        started = true
        decay += (r.decay - decay) * t; wet += (r.wet - wet) * t; damp += (r.damping - damp) * t
        for (i in 0 until n) gain[i] = 10f.pow(-3f * lengths[i] / (decay * rate))
        // One-pole low-pass in each loop: from 16 kHz (no damping) down to about 1.5 kHz.
        val cutoff = 16_000f * (1f - damp) + 1_500f * damp
        val a = 1f - exp(-2f * Math.PI.toFloat() * min(cutoff, rate * .45f) / rate)
        val scale = wet / sqrt(n.toFloat())
        for (k in 0 until frames) {
            // Pre-delay: the first echo comes a little after the sound.
            val input = pre[prePos]; pre[prePos] = send[k]; prePos = if (prePos + 1 == pre.size) 0 else prePos + 1
            var sum = 0f
            for (i in 0 until n) {
                val v = lines[i][pos[i]]
                low[i] += (v - low[i]) * a
                x[i] = low[i]; sum += low[i]
            }
            // Householder reflection: y = x − (2/n) Σx, orthogonal, every line into every other.
            val s = sum * (2f / n)
            var l = 0f; var rr = 0f
            for (i in 0 until n) {
                val xi = x[i]
                l += if (i and 1 == 0) xi else -xi
                rr += if (i and 2 == 0) xi else -xi
                val y = xi - s
                lines[i][pos[i]] = (y + input * (if (i % 3 == 0) -1f else 1f)) * gain[i]
                pos[i] = if (pos[i] + 1 == lengths[i]) 0 else pos[i] + 1
            }
            out[2 * k] += l * scale; out[2 * k + 1] += rr * scale
        }
    }

    /** Silence: the tail of the last place is forgotten. */
    fun clear() {
        for (line in lines) line.fill(0f)
        pre.fill(0f); low.fill(0f); started = false
    }

    private companion object {
        /** Delays of the lines (ms): mutually prime in samples at the usual rates, echoes spread evenly. */
        val DELAYS_MS = floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f, 53.9f, 59.3f, 67.1f, 79.7f)
        const val PRE_DELAY = .012f
    }
}
