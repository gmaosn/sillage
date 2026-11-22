@file:OptIn(ExperimentalWasmJsInterop::class)

package sillage.web

import sillage.platform.GameWindow
import sillage.platform.KEY_COUNT

// The page as a window: keys by their physical place (KeyboardEvent.code, turned into the engine's key
// codes, which are Linux evdev codes), the mouse held by the pointer lock, the wheel, the text typed.

@JsFun("""(key, text, move, button, wheel) => {
  const K = { Escape:1, Digit1:2, Digit2:3, Digit3:4, Digit4:5, Digit5:6, Digit6:7, Digit7:8, Digit8:9, Digit9:10, Digit0:11, Minus:12, Equal:13,
    Backspace:14, Tab:15, KeyQ:16, KeyW:17, KeyE:18, KeyR:19, KeyT:20, KeyY:21, KeyU:22, KeyI:23, KeyO:24, KeyP:25, BracketLeft:26, BracketRight:27,
    Enter:28, ControlLeft:29, KeyA:30, KeyS:31, KeyD:32, KeyF:33, KeyG:34, KeyH:35, KeyJ:36, KeyK:37, KeyL:38, Semicolon:39, Quote:40, Backquote:41,
    ShiftLeft:42, Backslash:43, KeyZ:44, KeyX:45, KeyC:46, KeyV:47, KeyB:48, KeyN:49, KeyM:50, Comma:51, Period:52, Slash:53, ShiftRight:54,
    AltLeft:56, Space:57, CapsLock:58, F1:59, F2:60, F3:61, F4:62, F5:63, F6:64, F7:65, F8:66, F9:67, F10:68, F11:87, F12:88, NumpadEnter:28,
    ControlRight:97, AltRight:100, Home:102, ArrowUp:103, PageUp:104, ArrowLeft:105, ArrowRight:106, End:107, ArrowDown:108, PageDown:109,
    Insert:110, Delete:111 };
  const c = S.canvas, dpr = () => window.devicePixelRatio || 1;
  const sound = () => { if (S.audio && S.audio.state !== 'running') S.audio.resume() };
  window.addEventListener('keydown', e => { sound(); const k = K[e.code];
    if (k) { key(k, true); if (!(e.ctrlKey || e.metaKey) && !(k >= 59 && k <= 88 && k !== 63 && k !== 64 && k !== 65 && k !== 66)) e.preventDefault() }
    if (e.key && e.key.length === 1 && !e.ctrlKey && !e.metaKey) text(e.key) });
  window.addEventListener('keyup', e => { const k = K[e.code]; if (k) key(k, false) });
  window.addEventListener('blur', () => key(0, false));
  c.addEventListener('mousemove', e => { const r = c.getBoundingClientRect(), locked = document.pointerLockElement === c;
    move(locked ? e.movementX : 0, locked ? e.movementY : 0, (e.clientX - r.left) * dpr(), (e.clientY - r.top) * dpr()) });
  c.addEventListener('mouseleave', () => move(0, 0, NaN, NaN));
  c.addEventListener('mousedown', e => { sound(); c.focus(); if (e.button === 0) { button(true); if (S.wantLock && document.pointerLockElement !== c && c.requestPointerLock) { const p = c.requestPointerLock(); if (p && p.catch) p.catch(() => {}) } } });
  window.addEventListener('mouseup', e => { if (e.button === 0) button(false) });
  c.addEventListener('wheel', e => { wheel(-Math.sign(e.deltaY)); e.preventDefault() }, { passive: false });
  c.addEventListener('contextmenu', e => e.preventDefault());
  c.focus();
}""")
private external fun jsListen(key: (Int, Boolean) -> Unit, text: (String) -> Unit, move: (Float, Float, Float, Float) -> Unit, button: (Boolean) -> Unit, wheel: (Float) -> Unit)

@JsFun("() => document.pointerLockElement === S.canvas") private external fun jsLocked(): Boolean
@JsFun("(want) => { S.wantLock = want; if (!want && document.pointerLockElement === S.canvas) document.exitPointerLock() }") private external fun jsWantLock(want: Boolean)
@JsFun("() => document.hasFocus()") private external fun jsFocused(): Boolean
/** Fits the canvas's pixels to its place in the page; true when its size changed. */
@JsFun("() => { const c = S.canvas, d = window.devicePixelRatio || 1, w = Math.max(1, Math.round(c.clientWidth * d)), h = Math.max(1, Math.round(c.clientHeight * d)); if (c.width === w && c.height === h) return false; c.width = w; c.height = h; return true }")
private external fun jsFit(): Boolean
@JsFun("() => S.canvas.width") private external fun jsWidth(): Int
@JsFun("() => S.canvas.height") private external fun jsHeight(): Int

internal class WebWindow(initialTitle: String) : GameWindow {
    private val down = BooleanArray(KEY_COUNT)
    private val tapped = BooleanArray(KEY_COUNT)         // went down since the last clearPresses
    private var dx = 0f; private var dy = 0f; private var scroll = 0f
    private var held = false; private var clicked = false
    private val typed = StringBuilder()
    private var resized = true
    private var want = false
    private val delta = FloatArray(2)

    override var pointerX = Float.NaN; private set
    override var pointerY = Float.NaN; private set
    override val shouldClose: Boolean get() = false
    override val focused: Boolean get() = jsFocused()
    override var title: String = initialTitle
        set(v) { if (field != v) { field = v; jsTitle(v) } }
    override var clickCaptures: Boolean = true
        set(v) { field = v; jsWantLock(want && v) }
    /** Whether the page holds the mouse. Asking for it takes effect at the next click (a page may only take the mouse on a user's gesture). */
    override var mouseCaptured: Boolean
        get() = jsLocked()
        set(v) { want = v; jsWantLock(v && clickCaptures) }

    init {
        jsTitle(initialTitle)
        jsListen(
            { k, isDown -> if (k == 0) down.fill(false) else if (k < KEY_COUNT) { down[k] = isDown; if (isDown) tapped[k] = true } },
            { t -> if (typed.length < 256) typed.append(t) },
            { mx, my, x, y -> dx += mx; dy += my; pointerX = x; pointerY = y },
            { b -> held = b; if (b) clicked = true },
            { w -> scroll += w })
        jsFit()
    }

    override fun poll() { if (jsFit()) resized = true }
    override fun waitEvents(seconds: Double) {}
    override fun isDown(key: Int): Boolean = key in 0 until KEY_COUNT && (down[key] || tapped[key])
    override fun clearPresses() { tapped.fill(false) }
    override fun consumeMouseDelta(): FloatArray { delta[0] = dx; delta[1] = dy; dx = 0f; dy = 0f; return delta }
    override fun consumeScroll(): Float { val s = scroll; scroll = 0f; return s }
    override fun consumeResize(): Boolean { val r = resized; resized = false; return r }
    override fun size(): IntArray = intArrayOf(jsWidth(), jsHeight())
    override fun buttonDown(): Boolean { val b = held || clicked; clicked = false; return b }
    override fun consumeText(): String { val t = typed.toString(); typed.clear(); return t }
}
