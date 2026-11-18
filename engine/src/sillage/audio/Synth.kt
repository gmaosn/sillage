package sillage.audio

import kotlin.math.*

/** Seeded random numbers (xorshift): a synthesised sound comes out the same every time. */
class Noise(seed: Long) {
    private var s = (seed * -0x61c8864680b583ebL) xor 0x5DEECE66DL or 1L
    private fun bits(): Long { s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17); return s }
    /** White noise, −1..1. */
    fun next(): Float = (bits() ushr 40).toFloat() / (1L shl 23) - 1f
    /** 0..1. */
    fun unit(): Float = (bits() ushr 40).toFloat() / (1L shl 24)
}

/** Second-order filter (RBJ's cookbook), retunable while it runs for sweeps. */
class Biquad(private val rate: Int) {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun lowPass(hz: Float, q: Float = .707f) = apply { design(hz, q) { c, a -> Triple((1 - c) / 2, 1 - c, (1 - c) / 2) } }
    fun highPass(hz: Float, q: Float = .707f) = apply { design(hz, q) { c, a -> Triple((1 + c) / 2, -(1 + c), (1 + c) / 2) } }
    /** Band-pass with 0 dB at [hz]. */
    fun bandPass(hz: Float, q: Float = 1f) = apply { design(hz, q) { c, a -> Triple(a, 0f, -a) } }

    private inline fun design(hz: Float, q: Float, b: (Float, Float) -> Triple<Float, Float, Float>) {
        val w = 2 * PI.toFloat() * hz.coerceIn(10f, rate * .45f) / rate
        val c = cos(w); val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha
        val (x0, x1, x2) = b(c, alpha)
        b0 = x0 / a0; b1 = x1 / a0; b2 = x2 / a0; a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/** First-order low-pass (smooths) and its high-pass complement. */
class OnePole(rate: Int, hz: Float) {
    private val k = 1f - exp(-2f * PI.toFloat() * hz / rate)
    private var y = 0f
    fun low(x: Float): Float { y += (x - y) * k; return y }
    fun high(x: Float): Float = x - low(x)
}

/** Tools to build sounds by computation. */
object Synth {
    const val RATE = 48_000

    /** A mono sound of [seconds]: [sample] gets the sample index and time; the ends get 2 ms fades. */
    inline fun sound(seconds: Float, rate: Int = RATE, sample: (i: Int, t: Float) -> Float): Sound {
        val n = (seconds * rate).toInt()
        val d = FloatArray(n) { i -> sample(i, i.toFloat() / rate) }
        fade(d, rate)
        return Sound(d, 1, rate)
    }

    fun fade(d: FloatArray, rate: Int, seconds: Float = .002f) {
        val f = min(d.size / 2, (seconds * rate).toInt())
        for (i in 0 until f) { val g = i.toFloat() / f; d[i] *= g; d[d.size - 1 - i] *= g }
    }

    /** Scales so the loudest sample reaches [peak]. */
    fun normalize(s: Sound, peak: Float = .9f): Sound {
        val m = s.data.maxOf { abs(it) }
        return if (m < 1e-9f) s else Sound(FloatArray(s.data.size) { s.data[it] * peak / m }, s.channels, s.rate)
    }

    /** Mixes several sounds starting together (mono, same rate). */
    fun mix(vararg parts: Sound): Sound {
        val n = parts.maxOf { it.frames }
        val d = FloatArray(n)
        for (p in parts) for (i in 0 until p.frames) d[i] += p.data[i]
        return Sound(d, 1, parts[0].rate)
    }

    /**
     * Makes a seamless loop: the last [crossfade] seconds are blended into the start (equal power), so
     * the loop is that much shorter than the material.
     */
    fun loop(s: Sound, crossfade: Float): Sound {
        val f = (crossfade * s.rate).toInt().coerceAtMost(s.frames / 2)
        val n = s.frames - f
        val d = FloatArray(n) { s.data[it] }
        for (i in 0 until f) {
            val a = i.toFloat() / f * PI.toFloat() / 2
            d[i] = s.data[i] * sin(a) + s.data[n + i] * cos(a)
        }
        return Sound(d, 1, s.rate)
    }

    /** Exponential decay with time constant [tau] seconds (0 before [start]). */
    fun decay(t: Float, tau: Float, start: Float = 0f) = if (t < start) 0f else exp(-(t - start) / tau)

    /** A struck object: damped sine modes (frequency Hz, amplitude, time constant s). */
    fun modes(t: Float, vararg m: Triple<Float, Float, Float>): Float {
        var s = 0f
        for ((f, a, tau) in m) s += a * sin(2 * PI.toFloat() * f * t) * exp(-t / tau)
        return s
    }
}

/**
 * The engine's ready-made sounds, all computed (no files): footsteps for each kind of ground, jumps,
 * landings, a pickup jingle, wood and rubber impacts, scraping, waves and gulls. Each call builds
 * the sound again; keep what you use.
 */
object Sounds {
    private const val R = Synth.RATE
    private val TAU = 2 * PI.toFloat()

    /**
     * One footstep on [surface] (a [sillage.physics.Material] name: grass, sand, wood, stone, road,
     * ice…). Different [variant]s differ slightly, so walking does not sound mechanical.
     */
    fun footstep(surface: String, variant: Int = 0): Sound {
        val rnd = Noise(surface.hashCode() * 31L + variant)
        val tune = 1f + (rnd.unit() - .5f) * .16f
        return Synth.normalize(when (surface) {
            "sand" -> sandStep(rnd, tune)
            "wood" -> woodStep(rnd, tune)
            "stone", "road" -> stoneStep(rnd, tune, if (surface == "road") .8f else 1f)
            "ice" -> Synth.mix(stoneStep(rnd, tune * 1.3f, 1.2f), squeak(rnd))
            else -> grassStep(rnd, tune)
        }, .8f)
    }

    private fun grassStep(rnd: Noise, tune: Float): Sound {
        // Leaves brushed and pressed: a soft heel, then crackles in a rustle, then the toe.
        val band = Biquad(R).bandPass(2000f * tune, .8f); val top = Biquad(R).lowPass(6500f)
        val low = OnePole(R, 180f)
        var grain = 0f
        return Synth.sound(.2f) { _, t ->
            if (rnd.unit() < .004f + .02f * Synth.decay(t, .03f)) grain = .5f + rnd.unit()
            grain *= .992f
            val env = (t / .008f).coerceAtMost(1f) * (Synth.decay(t, .045f) + .45f * Synth.decay(t, .035f, .07f))
            val n = rnd.next()
            val rustle = top.process(band.process(n) * (.35f + grain))
            val thud = low.low(rnd.next()) * 2.5f * Synth.decay(t, .02f)
            rustle * env + thud + .25f * sin(TAU * 85f * t) * Synth.decay(t, .025f)
        }
    }

    private fun sandStep(rnd: Noise, tune: Float): Sound {
        // Many grains rubbing: a dense crunch of tiny clicks over a soft, dull push.
        val crunch = Biquad(R).bandPass(3600f * tune, 1.1f); val high = Biquad(R).highPass(1200f); val top = Biquad(R).lowPass(7000f)
        val dull = Biquad(R).lowPass(380f)
        return Synth.sound(.24f) { _, t ->
            val env = (t / .015f).coerceAtMost(1f) * Synth.decay(t, .06f)
            val click = if (rnd.unit() < .25f * env) rnd.next() * 3f * env else 0f
            val grains = top.process(high.process(crunch.process(click + rnd.next() * .08f * env)))
            val push = dull.process(rnd.next()) * 2.2f * (t / .02f).coerceAtMost(1f) * Synth.decay(t, .06f)
            grains * 1.4f + push
        }
    }

    private fun woodStep(rnd: Noise, tune: Float): Sound {
        // A hollow plank: the heel's knock rings a few modes, a short board click on top.
        val click = Biquad(R).highPass(2500f)
        return Synth.sound(.3f) { _, t ->
            val knock = Synth.modes(t, Triple(118f * tune, .55f, .06f), Triple(265f * tune, .38f, .045f),
                Triple(560f * tune, .2f, .028f), Triple(1270f * tune, .1f, .014f))
            val toe = .5f * Synth.modes((t - .055f).coerceAtLeast(0f), Triple(240f * tune, .3f, .03f), Triple(610f * tune, .15f, .02f)) *
                (if (t > .055f) 1f else 0f)
            knock + toe + click.process(rnd.next()) * .5f * Synth.decay(t, .003f)
        }
    }

    private fun stoneStep(rnd: Noise, tune: Float, hard: Float): Sound {
        // A hard heel: a sharp click and a short body, then the sole scuffs a little.
        val click = Biquad(R).bandPass(1900f * tune * hard, 1.3f); val scuff = Biquad(R).bandPass(3200f * tune, .7f)
        return Synth.sound(.16f) { _, t ->
            val n = rnd.next()
            click.process(n) * 1.6f * Synth.decay(t, .012f) +
                .35f * sin(TAU * 150f * tune * t) * Synth.decay(t, .018f) +
                scuff.process(rnd.next()) * .25f * (if (t > .035f) Synth.decay(t, .035f, .035f) else 0f)
        }
    }

    private fun squeak(rnd: Noise): Sound {
        var ph = 0f
        return Synth.sound(.12f) { _, t ->
            ph += TAU * (2300f - 2500f * t) / R
            .12f * sin(ph) * (if (t > .02f) Synth.decay(t, .025f, .02f) else 0f)
        }
    }

    /** Cloth and air at take-off. */
    fun jump(): Sound {
        val rnd = Noise(7)
        val band = Biquad(R)
        return Synth.normalize(Synth.sound(.24f) { _, t ->
            band.bandPass(500f + 1600f * (t / .24f), 1.2f)
            band.process(rnd.next()) * (t / .05f).coerceAtMost(1f) * Synth.decay(t, .07f, .05f).let { if (t < .05f) 1f else it }
        }, .6f)
    }

    /** A body landing on its feet: a deep thud and a puff of air. */
    fun land(): Sound {
        val rnd = Noise(11); val puff = Biquad(R).lowPass(900f)
        var ph = 0f
        return Synth.normalize(Synth.sound(.32f) { _, t ->
            ph += TAU * (48f + 40f * Synth.decay(t, .05f)) / R
            sin(ph) * Synth.decay(t, .07f) + puff.process(rnd.next()) * .9f * Synth.decay(t, .035f)
        }, .9f)
    }

    /** A little glockenspiel arpeggio, rising: something was won. */
    fun pickup(): Sound {
        val notes = floatArrayOf(1318.5f, 1661.2f, 1975.5f, 2637.0f)
        return Synth.normalize(Synth.sound(1.1f) { _, t ->
            var s = 0f
            for ((k, f) in notes.withIndex()) {
                val u = t - k * .075f
                if (u < 0f) continue
                val a = (u / .002f).coerceAtMost(1f) * (if (k == 3) 1.2f else 1f)
                // Bar partials (1, 2.76, 5.40) and a soft octave below for warmth.
                s += a * (sin(TAU * f * u) * exp(-u / .45f) + .35f * sin(TAU * 2.76f * f * u) * exp(-u / .12f) +
                    .12f * sin(TAU * 5.4f * f * u) * exp(-u / .04f) + .25f * sin(TAU * .5f * f * u) * exp(-u / .3f))
            }
            s
        }, .75f)
    }

    /** A wooden crate hitting something. */
    fun knock(variant: Int = 0): Sound {
        val rnd = Noise(100L + variant); val tune = 1f + (rnd.unit() - .5f) * .12f
        val click = Biquad(R).bandPass(2400f, 1f)
        return Synth.normalize(Synth.sound(.4f) { _, t ->
            Synth.modes(t, Triple(92f * tune, .6f, .09f), Triple(205f * tune, .45f, .07f), Triple(377f * tune, .3f, .05f),
                Triple(715f * tune, .18f, .03f), Triple(1490f * tune, .08f, .012f)) +
                click.process(rnd.next()) * Synth.decay(t, .004f)
        }, .85f)
    }

    /** A beach ball bouncing: a hollow, slightly dropping « pock » and a slap of vinyl. */
    fun bounce(variant: Int = 0): Sound {
        val rnd = Noise(200L + variant); val tune = 1f + (rnd.unit() - .5f) * .1f
        val slap = Biquad(R).highPass(2200f); val air = Biquad(R).lowPass(1200f)
        var p1 = 0f; var p2 = 0f
        return Synth.normalize(Synth.sound(.25f) { _, t ->
            p1 += TAU * tune * (150f + 40f * Synth.decay(t, .02f)) / R
            p2 += TAU * tune * (370f + 60f * Synth.decay(t, .015f)) / R
            sin(p1) * Synth.decay(t, .05f) + .45f * sin(p2) * Synth.decay(t, .025f) +
                slap.process(rnd.next()) * .5f * Synth.decay(t, .0015f) + air.process(rnd.next()) * .5f * Synth.decay(t, .015f)
        }, .8f)
    }

    /** A crate dragged on the ground, as a loop: grainy friction that sticks and slips. */
    fun scrape(): Sound {
        val rnd = Noise(300); val low = Biquad(R).bandPass(650f, .8f); val high = Biquad(R).bandPass(2100f, 2f)
        val wobble = OnePole(R, 25f)
        return Synth.loop(Synth.normalize(Synth.sound(1.6f) { _, _ ->
            val stick = (.55f + 2.2f * wobble.low(if (rnd.unit() < .01f) 1f else 0f)).coerceAtMost(1.6f)
            val n = rnd.next()
            (low.process(n) + .35f * high.process(n)) * stick
        }, .6f), .4f)
    }

    /**
     * The sea on the shore, as a loop of about 9 s: a low background surge, two waves of different
     * sizes that swell, break and draw back over the sand with a fizz.
     */
    fun waves(variant: Int = 0): Sound {
        val rnd = Noise(400L + variant)
        val rumble = Biquad(R).lowPass(260f); val body = Biquad(R); val hiss = Biquad(R)
        val brown = OnePole(R, 60f)
        var fizz = 0f
        val len = 9.6f + variant * .7f
        val waveAt = floatArrayOf(1.4f, 6.2f + variant * .5f); val size = floatArrayOf(1f, .6f + .1f * variant)
        return Synth.loop(Synth.normalize(Synth.sound(len + 1.2f) { _, t ->
            val n = rnd.next()
            var swell = 0f; var crash = 0f; var wash = 0f
            for (k in waveAt.indices) {
                // Each wave: rises for 1.6 s, breaks, and its wash draws back over 3 s.
                for (shift in floatArrayOf(0f, len)) {
                    val u = t - waveAt[k] + shift
                    if (u < -1.6f || u > 5f) continue
                    if (u < 0f) swell += size[k] * ((u + 1.6f) / 1.6f).pow(2)
                    else {
                        swell += size[k] * exp(-u / .25f)
                        crash += size[k] * (u / .15f).coerceAtMost(1f) * exp(-u / .5f)
                        wash += size[k] * exp(-u / 1.4f) * (u / .4f).coerceAtMost(1f)
                    }
                }
            }
            val cutoff = 300f + 1500f * swell.coerceAtMost(1f) + 3500f * crash.coerceAtMost(1f)
            body.lowPass(cutoff, .6f)
            if (rnd.unit() < .02f * wash) fizz = rnd.next()
            fizz *= .94f
            hiss.bandPass(3800f + 1500f * wash, .8f)
            rumble.process(brown.low(n) * 8f) * (.3f + .3f * swell) + body.process(n) * (.06f + .5f * swell + 1.1f * crash) +
                hiss.process(n + fizz * 3f) * (.04f + .45f * wash)
        }, .9f), 1.2f)
    }

    /** A herring gull's call, « kyow kyow kyow », two to four cries. */
    fun gull(variant: Int = 0): Sound {
        val rnd = Noise(500L + variant)
        val calls = 2 + (rnd.unit() * 3).toInt().coerceAtMost(2)
        val base = 1f + (rnd.unit() - .5f) * .2f
        val f1 = Biquad(R).bandPass(2300f, 2.5f); val f2 = Biquad(R).bandPass(3600f, 3f)
        var ph = 0f
        val gap = .36f + rnd.unit() * .08f
        return Synth.normalize(Synth.sound(calls * gap + .2f) { _, t ->
            val k = (t / gap).toInt(); val u = t - k * gap
            if (k >= calls || u > .3f) { f1.process(0f) + f2.process(0f) }
            else {
                // Pitch: a quick rise, then a long fall; each cry a little lower.
                val pitch = base * (1f - .05f * k) * (if (u < .05f) 1100f + 600f * u / .05f else 1700f - 800f * ((u - .05f) / .25f))
                ph += TAU * pitch / R
                var saw = 0f
                for (h in 1..8) if (h * pitch < 11_000f) saw += sin(h * ph) / h
                val env = (u / .02f).coerceAtMost(1f) * ((.3f - u) / .06f).coerceIn(0f, 1f)
                val rough = 1f + .35f * sin(TAU * 58f * u)
                val x = saw * env * rough
                f1.process(x) * 1.2f + f2.process(x) * .7f + x * .15f
            }
        }, .7f)
    }
}
