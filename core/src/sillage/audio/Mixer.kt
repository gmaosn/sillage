package sillage.audio

import kotlin.concurrent.Volatile

import sillage.math.ValueCandidate
import sillage.math.Vec3
import kotlin.math.*

/**
 * The software mixer: plays up to [maxVoices] sounds at once, each with its volume, pitch, stereo
 * placement or position in the world, and mixes them into interleaved stereo floats. Sounds placed in the
 * world are softer and duller far away, shift in pitch as they come and go (Doppler), and echo in the
 * place's [reverb].
 *
 * The game thread calls [play], [playAt], [listen] and changes [Voice]s; the audio thread calls
 * [render]. Changes cross over under a short lock at the start of each block, and [render] allocates
 * nothing. Volumes glide over each block, so nothing clicks.
 */
class Mixer(val rate: Int = 48_000, val maxVoices: Int = 48) {
    // ---- what the game asks for (guarded by lock)
    private val lock = Any()
    private val sounds = arrayOfNulls<Sound>(maxVoices)
    private val cursors = arrayOfNulls<SoundCursor>(maxVoices)   // long sounds: each voice's own reader, made by play
    private val generation = IntArray(maxVoices)
    private val busy = BooleanArray(maxVoices)            // slot taken (from play until the audio side frees it)
    private val starting = BooleanArray(maxVoices)
    private val stopping = FloatArray(maxVoices)          // > 0: fade-out time requested
    private val volume = FloatArray(maxVoices)
    private val pitch = FloatArray(maxVoices)
    private val pan = FloatArray(maxVoices)
    private val loop = BooleanArray(maxVoices)
    private val spatial = BooleanArray(maxVoices)
    private val sx = FloatArray(maxVoices); private val sy = FloatArray(maxVoices); private val sz = FloatArray(maxVoices)
    private val reference = FloatArray(maxVoices)
    private val maximum = FloatArray(maxVoices)
    private val startedAt = LongArray(maxVoices)
    private val offset = FloatArray(maxVoices)
    private val sendAmount = FloatArray(maxVoices)
    private val finished = BooleanArray(maxVoices)        // set by the audio side, read by Voice.isPlaying
    private var plays = 0L
    private var lx = 0f; private var ly = 0f; private var lz = 0f
    private var rx = 1f; private var ry = 0f; private var rz = 0f
    private var fx = 0f; private var fy = 0f; private var fz = -1f
    /** Volume of everything, 0..1 (and a little more). */
    @Volatile var masterVolume = 1f
    /** How the place sounds (null: no reverberation). Changes glide over a tenth of a second. */
    @Volatile var reverb: Reverb? = null
    /**
     * The Doppler effect: a sound coming closer is higher, one going away lower, by c / (c + v) where v is
     * how fast its distance grows; 1 is the real effect, 0 none, more exaggerates it (games often do).
     */
    @Volatile var doppler = 1f
    /** Speed of sound for the Doppler effect (m/s). */
    @Volatile var speedOfSound = 343f

    // ---- audio side
    private val aSound = arrayOfNulls<Sound>(maxVoices)
    private val aStream = arrayOfNulls<SoundCursor>(maxVoices)   // the reader of a long sound, decoded while it plays
    private val aActive = BooleanArray(maxVoices)
    private val aCursor = DoubleArray(maxVoices)
    private val aStep = DoubleArray(maxVoices)
    private val aLoop = BooleanArray(maxVoices)
    private val aGainL = FloatArray(maxVoices); private val aGainR = FloatArray(maxVoices)
    private val tGainL = FloatArray(maxVoices); private val tGainR = FloatArray(maxVoices)
    private val aCut = FloatArray(maxVoices); private val tCut = FloatArray(maxVoices)
    private val aLowL = FloatArray(maxVoices); private val aLowR = FloatArray(maxVoices)
    private val aFade = FloatArray(maxVoices)           // remaining fade-out frames, < 0 when none
    private val aFadeTotal = FloatArray(maxVoices)
    private val aDone = BooleanArray(maxVoices)
    private val aFresh = BooleanArray(maxVoices)        // first block: start at the wanted volume, no glide
    private val aSend = FloatArray(maxVoices); private val tSend = FloatArray(maxVoices)
    private val aDistance = FloatArray(maxVoices)       // distance at the last block (NaN: none yet)
    private val aRadial = FloatArray(maxVoices)         // how fast it grows, smoothed (m/s)
    private var sendBuffer = FloatArray(0)
    private val reverbUnit = ReverbUnit(rate)
    private var reverbOn = false
    private var limiter = 1f
    private var framesRendered = 0L

    /**
     * Plays [sound] without position: [pan] −1 left … 1 right; [offset] seconds into the sound; [reverb]:
     * how much of it echoes in the place (0 by default: interface sounds, music).
     */
    fun play(sound: Sound, volume: Float = 1f, pitch: Float = 1f, pan: Float = 0f, loop: Boolean = false, offset: Float = 0f,
             reverb: Float = 0f): Voice =
        start(sound, volume, pitch, loop, false, pan, 0f, 0f, 0f, 1f, 1f, offset, reverb)

    /**
     * Plays [sound] at [position] in the world: it is heard from the listener's place, softer with
     * distance (full volume up to [referenceDistance], silent from [maxDistance]), duller far away and
     * behind the head, shifted in pitch as it moves ([doppler]); [reverb] scales how much of it echoes.
     */
    fun playAt(sound: Sound, position: Vec3, volume: Float = 1f, pitch: Float = 1f, loop: Boolean = false,
               referenceDistance: Float = 2f, maxDistance: Float = 50f, offset: Float = 0f, reverb: Float = 1f): Voice =
        start(sound, volume, pitch, loop, true, 0f, position.x, position.y, position.z, referenceDistance, maxDistance, offset, reverb)

    /** Where the ears are: [forward] is where the listener looks. */
    fun listen(position: Vec3, forward: Vec3, up: Vec3 = Vec3.UP) {
        val f = forward.normalized()
        var r = f cross up
        r = if (r.lengthSquared() < 1e-8f) Vec3.X else r.normalized()
        sillage.io.locked(lock) { lx = position.x; ly = position.y; lz = position.z; rx = r.x; ry = r.y; rz = r.z; fx = f.x; fy = f.y; fz = f.z }
    }

    /** Number of voices playing (or fading out). */
    val playing: Int get() = sillage.io.locked(lock) { busy.count { it } }

    private fun start(sound: Sound, v: Float, p: Float, l: Boolean, s: Boolean, pn: Float, x: Float, y: Float, z: Float,
                      ref: Float, max: Float, off: Float, send: Float): Voice {
        // A long sound gets its own decoder here, on the game's thread: the audio thread only reads it.
        val cursor = sound.cursor()
        return startLocked(sound, cursor, v, p, l, s, pn, x, y, z, ref, max, off, send)
    }

    private fun startLocked(sound: Sound, cursor: SoundCursor?, v: Float, p: Float, l: Boolean, s: Boolean, pn: Float, x: Float, y: Float, z: Float,
                            ref: Float, max: Float, off: Float, send: Float): Voice = sillage.io.locked(lock) {
        var slot = (0 until maxVoices).firstOrNull { !busy[it] }
        if (slot == null) {
            // All taken: steal the oldest sound that does not loop (loops are usually ambiences).
            slot = (0 until maxVoices).filter { !loop[it] }.minByOrNull { startedAt[it] }
                ?: (0 until maxVoices).minBy { startedAt[it] }
        }
        val i = slot
        generation[i]++
        sounds[i] = sound; cursors[i] = cursor; busy[i] = true; starting[i] = true; stopping[i] = 0f; finished[i] = false
        volume[i] = v.coerceAtLeast(0f); pitch[i] = p.coerceIn(.05f, 8f); pan[i] = pn.coerceIn(-1f, 1f); loop[i] = l
        spatial[i] = s; sx[i] = x; sy[i] = y; sz[i] = z
        reference[i] = ref.coerceAtLeast(.01f); maximum[i] = max(max, ref + .01f); startedAt[i] = ++plays
        offset[i] = off.coerceAtLeast(0f); sendAmount[i] = send.coerceAtLeast(0f)
        Voice(this, i, generation[i])
    }

    internal fun alive(i: Int, g: Int) = sillage.io.locked(lock) { generation[i] == g && busy[i] && !finished[i] }
    internal fun set(i: Int, g: Int, f: (Int) -> Unit) = sillage.io.locked(lock) { if (generation[i] == g && busy[i]) f(i) }
    internal fun setVolume(i: Int, v: Float) { volume[i] = v.coerceAtLeast(0f) }
    internal fun setPitch(i: Int, v: Float) { pitch[i] = v.coerceIn(.05f, 8f) }
    internal fun setPan(i: Int, v: Float) { pan[i] = v.coerceIn(-1f, 1f) }
    internal fun setPosition(i: Int, p: Vec3) { sx[i] = p.x; sy[i] = p.y; sz[i] = p.z }
    internal fun setStop(i: Int, seconds: Float) { stopping[i] = seconds.coerceAtLeast(.002f) }

    /** Stops every voice with a short fade. */
    fun stopAll(seconds: Float = .05f) = sillage.io.locked(lock) { for (i in 0 until maxVoices) if (busy[i]) stopping[i] = seconds.coerceAtLeast(.002f) }

    /** Mixes the next [frames] frames into [out] (interleaved left, right), replacing what was there. */
    fun render(out: FloatArray, frames: Int = out.size / 2) {
        require(out.size >= 2 * frames)
        val air = 1f - exp(-2f * PI.toFloat() * 16_000f / rate)
        val room = reverb
        if (room == null && reverbOn) { reverbUnit.clear(); reverbOn = false }
        if (room != null) reverbOn = true
        if (sendBuffer.size < frames) sendBuffer = FloatArray(frames)
        val block = frames.toFloat() / rate
        val follow = 1f - exp(-block / .08f)               // the Doppler speed's smoothing, about 80 ms
        val c = speedOfSound; val dopplerScale = doppler
        sillage.io.locked(lock) {
            for (i in 0 until maxVoices) {
                if (!busy[i]) { aActive[i] = false; continue }
                val snd = sounds[i]!!
                if (starting[i]) {
                    // A new sound, perhaps in a slot taken from an older one: forget the old one entirely.
                    starting[i] = false
                    aSound[i] = snd; aStream[i] = cursors[i]; cursors[i] = null; aActive[i] = true; aFade[i] = -1f; aDone[i] = false
                    aCursor[i] = (offset[i].toDouble() * snd.rate).let { if (loop[i] && snd.frames > 0) it % snd.frames else it }
                    aLowL[i] = 0f; aLowR[i] = 0f; aFresh[i] = true
                    aDistance[i] = Float.NaN; aRadial[i] = 0f
                } else if (aDone[i]) {
                    aDone[i] = false; aActive[i] = false; finished[i] = true; busy[i] = false; sounds[i] = null; aStream[i] = null
                    continue
                }
                if (stopping[i] > 0f && aFade[i] < 0f) { aFade[i] = stopping[i] * rate; aFadeTotal[i] = aFade[i] }
                aStep[i] = pitch[i].toDouble() * snd.rate / rate
                aLoop[i] = loop[i]
                var gl: Float; var gr: Float; var cut = air
                var send = volume[i] * sendAmount[i]
                if (spatial[i]) {
                    val dx = sx[i] - lx; val dy = sy[i] - ly; val dz = sz[i] - lz
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    val ref = reference[i]; val max = maximum[i]
                    // Inverse distance beyond the reference, then a gentle fade to silence at the maximum.
                    var att = if (d <= ref) 1f else ref / d
                    val edge = ((max - d) / (max * .25f)).coerceIn(0f, 1f)
                    att *= edge * edge * (3 - 2 * edge)
                    val side = if (d > 1e-4f) (dx * rx + dy * ry + dz * rz) / d else 0f
                    val front = if (d > 1e-4f) (dx * fx + dy * fy + dz * fz) / d else 1f
                    // Equal-power panning, narrowed when the source is very close (it is then all around).
                    val p = side * (d / (d + .5f))
                    val a = (p + 1f) * PI.toFloat() / 4f
                    gl = cos(a) * att * volume[i]; gr = sin(a) * att * volume[i]
                    // Air and head shadow: far and behind sound duller.
                    val hz = 16_000f / (1f + d / 25f) * (if (front < 0f) 1f + .45f * front else 1f)
                    cut = 1f - exp(-2f * PI.toFloat() * hz.coerceAtLeast(600f) / rate)
                    // The room: far sounds keep more of their echo than of themselves (√ of the direct attenuation).
                    send *= sqrt(att)
                    // Doppler: how fast the distance grows, from block to block, smoothed (the game moves sources
                    // at its own frame rate). A jump (a respawn, a camera cut) is not a speed.
                    if (aDistance[i].isNaN()) { aDistance[i] = d; aRadial[i] = 0f }
                    val raw = (d - aDistance[i]) / block
                    aDistance[i] = d
                    if (abs(raw) < c * .5f) aRadial[i] += (raw - aRadial[i]) * follow
                    if (dopplerScale > 0f) aStep[i] *= (c / (c + dopplerScale * aRadial[i])).coerceIn(.5f, 2f).toDouble()
                } else {
                    val a = (pan[i] + 1f) * PI.toFloat() / 4f
                    // Centred sounds at full volume on both sides; panned ones keep their power.
                    gl = cos(a) * SQRT2 * volume[i]; gr = sin(a) * SQRT2 * volume[i]
                    if (snd.channels == 2) { gl = min(1f, gl); gr = min(1f, gr) }
                }
                tGainL[i] = gl; tGainR[i] = gr; tCut[i] = cut; tSend[i] = if (room != null) send else 0f
                if (aFresh[i]) { aGainL[i] = gl; aGainR[i] = gr; aCut[i] = cut; aSend[i] = tSend[i]; aFresh[i] = false }
            }
        }
        out.fill(0f, 0, 2 * frames)
        val sends = sendBuffer
        if (room != null) sends.fill(0f, 0, frames)
        val inv = 1f / frames
        for (i in 0 until maxVoices) {
            if (!aActive[i] || aDone[i]) continue
            val snd = aSound[i]!!
            val n = snd.frames; val stereo = snd.channels == 2
            // The samples: all of them in memory (from frame 0), or the window of a long sound decoded while it
            // plays (from frame `from`; a loop going round reads the first frame just after the last, at n).
            val reader = aStream[i]
            var data: FloatArray; var from: Int; var until: Int; val wrap: Int
            if (reader == null) { data = snd.data; from = 0; until = n + 1; wrap = 0 }
            else { data = reader.buffer; from = reader.base; until = reader.limit; wrap = n }
            var cur = aCursor[i]; val step = aStep[i]
            var gl = aGainL[i]; var gr = aGainR[i]
            val dl = (tGainL[i] - gl) * inv; val dr = (tGainR[i] - gr) * inv
            var cut = aCut[i]; val dc = (tCut[i] - cut) * inv
            var lowL = aLowL[i]; var lowR = aLowR[i]
            var fade = aFade[i]; val fadeTotal = aFadeTotal[i]
            var sg = aSend[i]; val ds = (tSend[i] - sg) * inv
            val sending = room != null && (sg > 0f || tSend[i] > 0f)
            var k = 0
            while (k < frames) {
                val j = cur.toInt()
                if (j >= n) {
                    if (aLoop[i] && n > 0) { cur -= n; continue }
                    aDone[i] = true; break
                }
                val t = (cur - j).toFloat()
                val j1 = if (j + 1 < n) j + 1 else if (aLoop[i]) 0 else j
                val o1 = if (j1 < j) wrap else j1
                if (j < from || o1 >= until) {
                    val w = reader!!
                    w.cover(j); data = w.buffer; from = w.base; until = w.limit
                }
                val a0 = j - from; val a1 = o1 - from
                var l: Float; var r: Float
                if (stereo) {
                    l = data[2 * a0] + (data[2 * a1] - data[2 * a0]) * t
                    r = data[2 * a0 + 1] + (data[2 * a1 + 1] - data[2 * a0 + 1]) * t
                } else { l = data[a0] + (data[a1] - data[a0]) * t; r = l }
                lowL += (l - lowL) * cut; lowR += (r - lowR) * cut
                var g = 1f
                if (fade >= 0f) {
                    g = fade / fadeTotal
                    fade -= 1f
                    if (fade <= 0f) { aDone[i] = true; out[2 * k] += lowL * gl * g; out[2 * k + 1] += lowR * gr * g; break }
                }
                out[2 * k] += lowL * gl * g; out[2 * k + 1] += lowR * gr * g
                if (sending) sends[k] += (lowL + lowR) * .5f * sg * g
                gl += dl; gr += dr; cut += dc; sg += ds
                cur += step; k++
            }
            aCursor[i] = cur
            aGainL[i] = tGainL[i]; aGainR[i] = tGainR[i]; aCut[i] = tCut[i]; aSend[i] = tSend[i]
            aLowL[i] = lowL; aLowR[i] = lowR; aFade[i] = fade
        }
        // The place's echo of everything sent to it.
        if (room != null) reverbUnit.process(sends, out, frames, room)
        // Master volume, then a limiter: the gain drops at once on a peak and comes back in about 0.3 s;
        // a soft knee above 0.85 catches what is left, so the output never goes past 1.
        val master = masterVolume
        var peak = 0f
        for (k in 0 until 2 * frames) { val a = abs(out[k]) * master; if (a > peak) peak = a }
        val allowed = if (peak > 0f) min(1f, .95f / peak) else 1f
        val release = 1f - exp(-frames / (.3f * rate))
        val target = if (allowed < limiter) allowed else limiter + (allowed - limiter) * release
        val g0 = limiter; val dg = (target - g0) * inv
        for (k in 0 until frames) {
            val g = (g0 + dg * k) * master
            out[2 * k] = knee(out[2 * k] * g); out[2 * k + 1] = knee(out[2 * k + 1] * g)
        }
        limiter = target
        framesRendered += frames
    }

    private fun knee(x: Float): Float {
        val a = abs(x)
        if (a <= .85f) return x
        val y = .85f + .15f * tanh((a - .85f) / .15f)
        return if (x < 0f) -y else y
    }

    private companion object { const val SQRT2 = 1.4142135f }
}

/** A sound being played; changing it after it ended does nothing. */
@ValueCandidate
class Voice internal constructor(private val mixer: Mixer, private val slot: Int, private val generation: Int) {
    val isPlaying: Boolean get() = mixer.alive(slot, generation)
    fun volume(v: Float) = mixer.set(slot, generation) { mixer.setVolume(it, v) }
    fun pitch(v: Float) = mixer.set(slot, generation) { mixer.setPitch(it, v) }
    fun pan(v: Float) = mixer.set(slot, generation) { mixer.setPan(it, v) }
    fun position(p: Vec3) = mixer.set(slot, generation) { mixer.setPosition(it, p) }
    /** Stops with a fade of [seconds] (short enough to feel immediate, long enough not to click). */
    fun stop(seconds: Float = .03f) = mixer.set(slot, generation) { mixer.setStop(it, seconds) }
}
