package sillage.audio

import sillage.math.Vec3

/** Where the mixed sound goes on this platform: a thread or a callback of its own asks the [Mixer] for blocks. */
interface AudioOutput {
    val device: String
    /** Delay between mixing and hearing, in seconds. */
    val latency: Float
    fun close()
}

/**
 * Sound for a game: a [Mixer] and, when there is one, the system's sound output (ALSA on Linux, which
 * hands the sound to PipeWire or PulseAudio; Web Audio in a page; the system's audio track on Android).
 *
 * Without a usable output (no sound card, headless runs) the game still runs: [Audio.offline] mixes
 * only when asked to, which is also how sound is tested and recorded to a file.
 */
class Audio(val mixer: Mixer, private val output: AudioOutput?) : AutoCloseable {
    /** Where the sound goes ("default", "null"…), or null when nothing is played. */
    val device: String? get() = output?.device
    /** Delay between mixing and hearing, in seconds (0 without output). */
    val latency: Float get() = output?.latency ?: 0f

    var volume: Float
        get() = mixer.masterVolume
        set(v) { mixer.masterVolume = v.coerceIn(0f, 2f) }

    fun play(sound: Sound, volume: Float = 1f, pitch: Float = 1f, pan: Float = 0f, loop: Boolean = false, offset: Float = 0f) =
        mixer.play(sound, volume, pitch, pan, loop, offset)
    fun playAt(sound: Sound, position: Vec3, volume: Float = 1f, pitch: Float = 1f, loop: Boolean = false,
               referenceDistance: Float = 2f, maxDistance: Float = 50f, offset: Float = 0f) =
        mixer.playAt(sound, position, volume, pitch, loop, referenceDistance, maxDistance, offset)
    fun listen(position: Vec3, forward: Vec3, up: Vec3 = Vec3.UP) = mixer.listen(position, forward, up)

    override fun close() { output?.close() }

    companion object {
        /**
         * Opens the sound output [device] ("default" follows the system's choice). If it cannot be
         * opened the reason is printed and a silent [offline] audio is returned, so a game never fails
         * for lack of sound.
         */
        fun open(device: String = "default", rate: Int = 48_000, latencyMs: Int = 40): Audio {
            val mixer = Mixer(rate)
            return try { Audio(mixer, platformAudioOutput(device, mixer, rate, latencyMs)) }
            catch (e: Throwable) {
                println("Sillage: no sound (${e.message})")
                Audio(mixer, null)
            }
        }

        /** Audio without output: call `mixer.render` yourself (tests, recording a session to a WAV file). */
        fun offline(rate: Int = 48_000) = Audio(Mixer(rate), null)
    }
}

/** This platform's sound output playing what [mixer] mixes; throws when there is none. */
internal expect fun platformAudioOutput(device: String, mixer: Mixer, rate: Int, latencyMs: Int): AudioOutput
