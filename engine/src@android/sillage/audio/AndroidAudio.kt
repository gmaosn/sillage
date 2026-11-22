package sillage.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** The phone's sound output: a thread mixes small blocks and writes them to an audio track as floats. */
internal class AndroidOutput(private val mixer: Mixer, private val rate: Int, latencyMs: Int) : AudioOutput {
    override val device = "android"
    override val latency: Float
    private val track: AudioTrack
    @Volatile private var running = true
    private val thread: Thread

    init {
        val least = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        check(least > 0) { "the phone refuses float sound at $rate Hz" }
        val bytes = maxOf(least, rate * latencyMs / 1000 * 8)
        @Suppress("DEPRECATION")
        track = AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT, bytes, AudioTrack.MODE_STREAM)
        check(track.state == AudioTrack.STATE_INITIALIZED) { "the phone's sound output could not be opened" }
        latency = bytes / 8f / rate
        track.play()
        thread = Thread({
            val frames = 256; val block = FloatArray(2 * frames)
            while (running) {
                mixer.render(block, frames)
                if (track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING) < 0) break
            }
        }, "sillage-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        outputs.add(this)
    }

    fun pause() { runCatching { track.pause() } }
    fun resume() { runCatching { track.play() } }

    override fun close() {
        running = false; outputs.remove(this)
        runCatching { track.stop() }; runCatching { thread.join(500) }; track.release()
    }

    companion object {
        private val outputs = java.util.concurrent.CopyOnWriteArrayList<AndroidOutput>()
        /** The application left the screen, or came back: called by the game's activity. */
        fun pauseAll() { for (o in outputs) o.pause() }
        fun resumeAll() { for (o in outputs) o.resume() }
    }
}

internal actual fun platformAudioOutput(device: String, mixer: Mixer, rate: Int, latencyMs: Int): AudioOutput = AndroidOutput(mixer, rate, latencyMs)
