package sillage.audio

internal actual fun platformAudioOutput(device: String, mixer: Mixer, rate: Int, latencyMs: Int): AudioOutput = throw IllegalStateException("no sound output yet")
