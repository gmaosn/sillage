@file:OptIn(ExperimentalWasmJsInterop::class, UnsafeWasmMemoryApi::class)

package sillage.web

import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.wasmMemory
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// The page's side of the engine, in JavaScript: one object (globalThis.S) holds the canvas, the WebGL 2
// context and its objects by number, the sound and the files loaded. Kotlin and JavaScript exchange
// numbers and text; arrays go through the module's memory (a view on it, no copy on the JavaScript side).

@JsFun("""(memory, id) => {
  let c = document.getElementById(id) || document.querySelector('canvas');
  if (!c) { c = document.createElement('canvas'); c.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;display:block;outline:none';
            document.body.style.margin = '0'; document.body.style.background = '#000'; document.body.appendChild(c); }
  c.tabIndex = 0;
  const gl = c.getContext('webgl2', { antialias: false, alpha: false, depth: false, stencil: false, powerPreference: 'high-performance' });
  if (!gl) return 0;
  const float = !!gl.getExtension('EXT_color_buffer_float') || !!gl.getExtension('EXT_color_buffer_half_float');
  gl.getExtension('OES_texture_float_linear');
  gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1); gl.pixelStorei(gl.PACK_ALIGNMENT, 1);
  globalThis.S = { m: memory, canvas: c, gl, o: [null], u: [null], float, log: '', assets: [], wantLock: false, audio: null };
  return float ? 2 : 1;
}""")
internal external fun jsInit(memory: JsAny, canvasId: String): Int

internal fun webInit(canvasId: String): Int = jsInit(wasmMemory, canvasId)

@JsFun("(text) => { let e = document.getElementById('sillage-message'); if (!e) { e = document.createElement('pre'); e.id = 'sillage-message'; e.style.cssText = 'position:fixed;left:0;right:0;top:40%;margin:0;padding:0 24px;text-align:center;font:16px/1.5 system-ui,sans-serif;color:#fff;white-space:pre-wrap;pointer-events:none;text-shadow:0 1px 3px #000'; document.body.appendChild(e); } e.textContent = text; e.style.display = text ? 'block' : 'none'; }")
internal external fun jsMessage(text: String)

@JsFun("(text) => console.log(text)")
internal external fun jsLog(text: String)

@JsFun("(name) => new URLSearchParams(location.search).get(name)")
internal external fun jsQuery(name: String): String?

@JsFun("() => location.search")
internal external fun jsSearch(): String

@JsFun("(title) => { document.title = title }")
internal external fun jsTitle(title: String)

// ---- the loop
@JsFun("(frame) => { const step = (t) => { if (frame(t)) requestAnimationFrame(step) }; requestAnimationFrame(step) }")
internal external fun jsLoop(frame: (Double) -> Boolean)

@JsFun("() => performance.now()")
internal external fun jsNow(): Double

// ---- the files loaded before the game starts
@JsFun("""(listUrl, progress, done) => {
  fetch(listUrl).then(r => { if (!r.ok) throw new Error(listUrl + ': ' + r.status); return r.text() }).then(text => {
    const names = text.split('\n').map(s => s.trim()).filter(s => s && !s.startsWith('#'));
    let got = 0; progress(0, names.length);
    return Promise.all(names.map(n => fetch(n).then(r => { if (!r.ok) throw new Error(n + ': ' + r.status); return r.arrayBuffer() })
      .then(b => { got++; progress(got, names.length); return { name: n, bytes: new Uint8Array(b) } })));
  }).then(list => { S.assets = list; done(list.length, '') }).catch(e => done(-1, String(e)));
}""")
internal external fun jsLoadAssets(listUrl: String, progress: (Int, Int) -> Unit, done: (Int, String) -> Unit)

@JsFun("(i) => S.assets[i].name") internal external fun jsAssetName(i: Int): String
@JsFun("(i) => S.assets[i].bytes.length") internal external fun jsAssetSize(i: Int): Int
@JsFun("(i, p) => { new Uint8Array(S.m.buffer, p, S.assets[i].bytes.length).set(S.assets[i].bytes); S.assets[i].bytes = null }")
internal external fun jsAssetCopy(i: Int, pointer: Int)

@JsFun("(name, p, n) => { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([new Uint8Array(S.m.buffer, p, n).slice()])); a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 10000) }")
internal external fun jsDownload(name: String, pointer: Int, n: Int)

// ---- arrays through the module's memory
internal inline fun <T> floatsOut(a: FloatArray, from: Int, n: Int, use: (Int) -> T): T = withScopedMemoryAllocator { al ->
    val p = al.allocate(maxOf(n, 1) * 4)
    var q = p
    for (i in from until from + n) { q.storeInt(a[i].toRawBits()); q += 4 }
    use(p.address.toInt())
}

internal inline fun <T> bytesOut(a: ByteArray, from: Int, n: Int, use: (Int) -> T): T = withScopedMemoryAllocator { al ->
    val p = al.allocate(maxOf(n, 1) + 8)
    var q = p; var i = from; val end = from + n
    while (i + 4 <= end) {
        q.storeInt((a[i].toInt() and 255) or ((a[i + 1].toInt() and 255) shl 8) or ((a[i + 2].toInt() and 255) shl 16) or (a[i + 3].toInt() shl 24))
        q += 4; i += 4
    }
    while (i < end) { q.storeByte(a[i]); q += 1; i++ }
    use(p.address.toInt())
}

internal inline fun bytesIn(n: Int, fill: (Int) -> Unit): ByteArray = withScopedMemoryAllocator { al ->
    val p = al.allocate(maxOf(n, 1) + 8)
    fill(p.address.toInt())
    val out = ByteArray(n)
    var q: Pointer = p; var i = 0
    while (i + 4 <= n) { val v = q.loadInt(); out[i] = v.toByte(); out[i + 1] = (v shr 8).toByte(); out[i + 2] = (v shr 16).toByte(); out[i + 3] = (v shr 24).toByte(); q += 4; i += 4 }
    while (i < n) { out[i] = q.loadByte(); q += 1; i++ }
    out
}
