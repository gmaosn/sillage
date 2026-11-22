@file:OptIn(ExperimentalWasmJsInterop::class)

package sillage.audio

import sillage.web.floatsOut

// Web Audio: a script node asks for blocks of samples; the mixer fills them. A page may only start
// sound on a user's gesture: the context is resumed at the first key or click (see WebWindow).

@JsFun("""(rate, block, render) => {
  const A = window.AudioContext || window.webkitAudioContext; if (!A) return 0;
  let ctx; try { ctx = new A({ sampleRate: rate, latencyHint: 'interactive' }) } catch (e) { ctx = new A() }
  const node = ctx.createScriptProcessor(block, 0, 2);
  node.onaudioprocess = (e) => { S.out = e.outputBuffer; render(e.outputBuffer.length) };
  node.connect(ctx.destination); S.audio = ctx; S.audioNode = node;
  return ctx.sampleRate;
}""")
private external fun jsAudioOpen(rate: Int, block: Int, render: (Int) -> Unit): Int
@JsFun("(p, n) => { const v = new Float32Array(S.m.buffer, p, 2 * n), l = S.out.getChannelData(0), r = S.out.getChannelData(1); for (let i = 0; i < n; i++) { l[i] = v[2 * i]; r[i] = v[2 * i + 1] } }")
private external fun jsAudioFill(pointer: Int, frames: Int)
@JsFun("() => { if (S.audioNode) S.audioNode.disconnect(); if (S.audio) S.audio.close(); S.audio = null; S.audioNode = null }")
private external fun jsAudioClose()

private class WebOutput(mixer: Mixer, rate: Int, latencyMs: Int) : AudioOutput {
    private val block = if (latencyMs <= 25) 1024 else 2048
    private val mix = FloatArray(2 * 16384)
    override val device = "web"
    override val latency: Float
    init {
        val got = jsAudioOpen(rate, block) { frames ->
            val n = minOf(frames, mix.size / 2)
            mixer.render(mix, n)
            floatsOut(mix, 0, 2 * n) { jsAudioFill(it, n) }
        }
        check(got != 0) { "this browser has no Web Audio" }
        check(got == rate) { jsAudioClose(); "the page's sound runs at $got Hz, the mixer at $rate" }
        latency = block.toFloat() / rate
    }
    override fun close() = jsAudioClose()
}

internal actual fun platformAudioOutput(device: String, mixer: Mixer, rate: Int, latencyMs: Int): AudioOutput = WebOutput(mixer, rate, latencyMs)
