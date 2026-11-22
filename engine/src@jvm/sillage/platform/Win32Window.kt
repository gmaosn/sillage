package sillage.platform

import sillage.vk.NativeStack
import sillage.vk.VK_KHR_SURFACE_EXTENSION_NAME
import sillage.vk.VK_KHR_WIN32_SURFACE_EXTENSION_NAME
import sillage.vk.VkInstanceApi
import sillage.vk.VkWin32SurfaceCreateInfoKHR
import sillage.vk.struct
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/** user32 and kernel32 through FFM (64-bit Windows: LONG and UINT are 32 bits, handles and LPARAM 64). */
private object Win32 {
    private val linker: Linker = Linker.nativeLinker()
    private val user: SymbolLookup = SymbolLookup.libraryLookup("user32", Arena.global())
    private val kernel: SymbolLookup = SymbolLookup.libraryLookup("kernel32", Arena.global())
    private fun h(lib: SymbolLookup, name: String, ret: MemoryLayout?, vararg args: MemoryLayout): MethodHandle {
        val f = lib.find(name).orElseThrow { IllegalStateException("Missing Windows function $name") }
        return linker.downcallHandle(f, if (ret == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(ret, *args))
    }
    private val P = ADDRESS; private val I = JAVA_INT; private val J = JAVA_LONG
    @JvmField val GetModuleHandleW = h(kernel, "GetModuleHandleW", P, P)
    @JvmField val RegisterClassExW = h(user, "RegisterClassExW", JAVA_SHORT, P)
    @JvmField val CreateWindowExW = h(user, "CreateWindowExW", P, I, P, P, I, I, I, I, I, P, P, P, P)
    @JvmField val DefWindowProcW = h(user, "DefWindowProcW", J, P, I, J, J)
    @JvmField val DestroyWindow = h(user, "DestroyWindow", I, P)
    @JvmField val ShowWindow = h(user, "ShowWindow", I, P, I)
    @JvmField val PeekMessageW = h(user, "PeekMessageW", I, P, P, I, I, I)
    @JvmField val TranslateMessage = h(user, "TranslateMessage", I, P)
    @JvmField val DispatchMessageW = h(user, "DispatchMessageW", J, P)
    @JvmField val MsgWaitForMultipleObjects = h(user, "MsgWaitForMultipleObjects", I, I, P, I, I, I)
    @JvmField val SetWindowTextW = h(user, "SetWindowTextW", I, P, P)
    @JvmField val GetClientRect = h(user, "GetClientRect", I, P, P)
    @JvmField val AdjustWindowRect = h(user, "AdjustWindowRect", I, P, I, I)
    @JvmField val ClientToScreen = h(user, "ClientToScreen", I, P, P)
    @JvmField val SetCursorPos = h(user, "SetCursorPos", I, I, I)
    @JvmField val ClipCursor = h(user, "ClipCursor", I, P)
    @JvmField val SetCursor = h(user, "SetCursor", P, P)
    @JvmField val LoadCursorW = h(user, "LoadCursorW", P, P, P)
    @JvmField val SetProcessDPIAware = h(user, "SetProcessDPIAware", I)
    @JvmField val WNDPROC: FunctionDescriptor = FunctionDescriptor.of(J, P, I, J, J)
    val upcalls: Linker get() = linker

    const val WM_DESTROY = 0x0002; const val WM_SIZE = 0x0005; const val WM_SETFOCUS = 0x0007; const val WM_KILLFOCUS = 0x0008
    const val WM_CLOSE = 0x0010; const val WM_SETCURSOR = 0x0020; const val WM_KEYDOWN = 0x0100; const val WM_KEYUP = 0x0101
    const val WM_CHAR = 0x0102; const val WM_SYSKEYDOWN = 0x0104; const val WM_SYSKEYUP = 0x0105; const val WM_MOUSEMOVE = 0x0200
    const val WM_LBUTTONDOWN = 0x0201; const val WM_LBUTTONUP = 0x0202; const val WM_MOUSEWHEEL = 0x020A
    const val WS_OVERLAPPEDWINDOW = 0x00CF0000; const val CW_USEDEFAULT = 0x80000000.toInt()
}

/**
 * A Windows window through user32. Keys come as scan codes, which are the engine's key codes (both descend
 * from the PC keyboard: the key under the left middle finger is 17 everywhere), so ZQSD on an AZERTY keyboard
 * is WASD on a QWERTY one; the keys of the right-hand blocks carry an « extended » mark and are translated.
 * Text comes from Windows itself (WM_CHAR: layouts and dead keys). A captured mouse is hidden, kept inside
 * the window and brought back to its centre after each move: only its motion is read.
 */
internal class Win32Window(title: String, private var width: Int, private var height: Int) : Window {
    private val arena = Arena.ofConfined()
    private val scratch = arena.allocate(64)
    private val message = arena.allocate(48)                         // MSG
    private val instance: MemorySegment
    private var hwnd: MemorySegment = MemorySegment.NULL
    private val arrow: MemorySegment
    private val keys = BooleanArray(KEY_COUNT)
    private val pressed = BooleanArray(KEY_COUNT)                    // went down since the game last looked
    private val typed = StringBuilder()
    private var mouseDx = 0f; private var mouseDy = 0f; private var scroll = 0f
    private var skipMotion = false
    private var resized = false
    private var leftDown = false; private var leftPressed = false
    override var pointerX = Float.NaN; private set
    override var pointerY = Float.NaN; private set
    override var clickCaptures = true
    override var shouldClose = false; private set
    override var focused = true; private set
    override var mouseCaptured = false
        set(v) { if (field != v) { field = v; applyCapture() } }
    override var title: String = title
        set(v) { if (field != v) { field = v; Win32.SetWindowTextW.invokeExact(hwnd, wide(v)) as Int } }

    /** SILLAGE_KEYS=1 prints each key as Windows reports it (its scan code) and the engine's code for it. */
    private val DIAGNOSTIC = System.getenv("SILLAGE_KEYS") == "1"

    private fun wide(s: String): MemorySegment = arena.allocateFrom(s, Charsets.UTF_16LE)

    init {
        Win32.SetProcessDPIAware.invokeExact() as Int                  // the picture in real pixels, not stretched
        instance = Win32.GetModuleHandleW.invokeExact(MemorySegment.NULL) as MemorySegment
        arrow = Win32.LoadCursorW.invokeExact(MemorySegment.NULL, MemorySegment.ofAddress(32512)) as MemorySegment
        val proc = Win32.upcalls.upcallStub(
            MethodHandles.lookup().bind(this, "handle", MethodType.methodType(Long::class.java, MemorySegment::class.java, Int::class.java, Long::class.java, Long::class.java)),
            Win32.WNDPROC, arena)
        val name = wide("SillageWindow")
        val wc = arena.allocate(80)                                  // WNDCLASSEXW
        wc.set(JAVA_INT, 0, 80); wc.set(JAVA_INT, 4, 0x0020 /* CS_OWNDC */); wc.set(ADDRESS, 8, proc)
        wc.set(ADDRESS, 24, instance); wc.set(ADDRESS, 40, arrow); wc.set(ADDRESS, 64, name)
        check((Win32.RegisterClassExW.invokeExact(wc) as Short).toInt() != 0) { "Windows refused the window class" }
        // The window's outer size for the client area asked for.
        val r = scratch; r.set(JAVA_INT, 0, 0); r.set(JAVA_INT, 4, 0); r.set(JAVA_INT, 8, width); r.set(JAVA_INT, 12, height)
        Win32.AdjustWindowRect.invokeExact(r, Win32.WS_OVERLAPPEDWINDOW, 0) as Int
        hwnd = Win32.CreateWindowExW.invokeExact(0, name, wide(title), Win32.WS_OVERLAPPEDWINDOW, Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT,
            r.get(JAVA_INT, 8) - r.get(JAVA_INT, 0), r.get(JAVA_INT, 12) - r.get(JAVA_INT, 4),
            MemorySegment.NULL, MemorySegment.NULL, instance, MemorySegment.NULL) as MemorySegment
        check(hwnd != MemorySegment.NULL) { "Windows could not open a window" }
        Win32.ShowWindow.invokeExact(hwnd, 5 /* SW_SHOW */) as Int
        readSize()
        poll()
    }

    private fun readSize() {
        Win32.GetClientRect.invokeExact(hwnd, scratch) as Int
        val w = scratch.get(JAVA_INT, 8); val h = scratch.get(JAVA_INT, 12)
        if (w > 0 && h > 0 && (w != width || h != height)) { width = w; height = h; resized = true }
    }

    /** The window procedure, called by Windows while messages are dispatched (and while the window is made). */
    @Suppress("unused")
    private fun handle(window: MemorySegment, msg: Int, w: Long, l: Long): Long {
        when (msg) {
            Win32.WM_CLOSE -> { shouldClose = true; return 0 }
            Win32.WM_SIZE -> { val nw = (l and 0xFFFF).toInt(); val nh = ((l shr 16) and 0xFFFF).toInt()
                if (nw > 0 && nh > 0 && (nw != width || nh != height)) { width = nw; height = nh; resized = true; if (mouseCaptured) clip() } }
            Win32.WM_SETFOCUS -> { focused = true; if (DIAGNOSTIC) System.err.println("SILLAGE_KEY focus") }
            Win32.WM_KILLFOCUS -> { focused = false; keys.fill(false); pressed.fill(false); if (mouseCaptured) mouseCaptured = false }
            Win32.WM_SETCURSOR -> if ((l and 0xFFFF).toInt() == 1 /* HTCLIENT */) {
                Win32.SetCursor.invokeExact(if (mouseCaptured) MemorySegment.NULL else arrow) as MemorySegment; return 1 }
            Win32.WM_KEYDOWN, Win32.WM_SYSKEYDOWN, Win32.WM_KEYUP, Win32.WM_SYSKEYUP -> {
                val down = msg == Win32.WM_KEYDOWN || msg == Win32.WM_SYSKEYDOWN
                val code = keyCode(((l shr 16) and 0xFF).toInt(), (l shr 24) and 1L == 1L)
                if (DIAGNOSTIC) System.err.println("SILLAGE_KEY scan=" + ((l shr 16) and 0xFF) + " extended=" + ((l shr 24) and 1L) + " code=" + code + (if (down) " down" else " up"))
                if (code in 1 until KEY_COUNT) { if (down && !keys[code]) pressed[code] = true; keys[code] = down }
                if (msg == Win32.WM_KEYDOWN || msg == Win32.WM_KEYUP) return 0      // system keys go on to Windows (Alt+F4…)
            }
            Win32.WM_CHAR -> { val c = w.toInt(); if (c >= 32 && c != 127 && typed.length < 256) typed.append(c.toChar()); return 0 }
            Win32.WM_LBUTTONDOWN -> { leftDown = true; leftPressed = true; if (!mouseCaptured && clickCaptures) mouseCaptured = true; return 0 }
            Win32.WM_LBUTTONUP -> { leftDown = false; return 0 }
            Win32.WM_MOUSEWHEEL -> { scroll += (w shr 16).toShort() / 120f; return 0 }
            Win32.WM_MOUSEMOVE -> {
                val x = l.toShort().toInt(); val y = (l shr 16).toShort().toInt()
                if (!mouseCaptured) { pointerX = x.toFloat(); pointerY = y.toFloat() }
                else if (focused) {
                    val dx = x - width / 2; val dy = y - height / 2
                    if (skipMotion) skipMotion = false else if (dx != 0 || dy != 0) { mouseDx += dx; mouseDy += dy }
                    if (dx != 0 || dy != 0) centre()
                }
                return 0
            }
        }
        return Win32.DefWindowProcW.invokeExact(window, msg, w, l) as Long
    }

    /** A PC scan code as the engine's key code: the same number, except for the « extended » keys. */
    private fun keyCode(scan: Int, extended: Boolean): Int = if (!extended) scan else when (scan) {
        0x1C -> 28 /* the keypad's Enter */; 0x1D -> 97; 0x38 -> 100; 0x47 -> 102; 0x48 -> 103; 0x49 -> 104; 0x4B -> 105; 0x4D -> 106
        0x4F -> 107; 0x50 -> 108; 0x51 -> 109; 0x52 -> 110; 0x53 -> 111; 0x35 -> 53
        else -> 0
    }

    private fun toScreen(x: Int, y: Int): MemorySegment {
        scratch.set(JAVA_INT, 32, x); scratch.set(JAVA_INT, 36, y)
        Win32.ClientToScreen.invokeExact(hwnd, scratch.asSlice(32)) as Int
        return scratch.asSlice(32)
    }
    private fun centre() { val p = toScreen(width / 2, height / 2); Win32.SetCursorPos.invokeExact(p.get(JAVA_INT, 0), p.get(JAVA_INT, 4)) as Int }
    private fun clip() {
        val a = toScreen(0, 0); val x0 = a.get(JAVA_INT, 0); val y0 = a.get(JAVA_INT, 4)
        scratch.set(JAVA_INT, 0, x0); scratch.set(JAVA_INT, 4, y0); scratch.set(JAVA_INT, 8, x0 + width); scratch.set(JAVA_INT, 12, y0 + height)
        Win32.ClipCursor.invokeExact(scratch) as Int
    }
    private fun applyCapture() {
        if (hwnd == MemorySegment.NULL) return
        if (mouseCaptured) { clip(); centre(); skipMotion = true; pointerX = Float.NaN; pointerY = Float.NaN; Win32.SetCursor.invokeExact(MemorySegment.NULL) as MemorySegment }
        else { Win32.ClipCursor.invokeExact(MemorySegment.NULL) as Int; Win32.SetCursor.invokeExact(arrow) as MemorySegment }
        mouseDx = 0f; mouseDy = 0f
    }

    override val instanceExtensions = listOf(VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_WIN32_SURFACE_EXTENSION_NAME)

    override fun createSurface(vk: VkInstanceApi, stack: NativeStack): Long = stack.frame { s ->
        val out = s.allocate(JAVA_LONG)
        vk.createWin32SurfaceKHR(s.struct(VkWin32SurfaceCreateInfoKHR, "hinstance" to instance, "hwnd" to hwnd), out)
        out.get(JAVA_LONG, 0)
    }

    override fun framebufferSize() = intArrayOf(width, height)

    override fun poll() {
        while ((Win32.PeekMessageW.invokeExact(message, MemorySegment.NULL, 0, 0, 1 /* PM_REMOVE */) as Int) != 0) {
            Win32.TranslateMessage.invokeExact(message) as Int
            Win32.DispatchMessageW.invokeExact(message) as Long
        }
    }

    override fun waitEvents(seconds: Double) {
        Win32.MsgWaitForMultipleObjects.invokeExact(0, MemorySegment.NULL, 0, (seconds * 1000).toInt().coerceAtLeast(0), 0x04FF /* QS_ALLINPUT */) as Int
        poll()
    }

    override fun isDown(key: Int) = key in 0 until KEY_COUNT && (keys[key] || pressed[key])
    override fun clearPresses() = pressed.fill(false)
    override fun consumeMouseDelta(): FloatArray { val d = floatArrayOf(mouseDx, mouseDy); mouseDx = 0f; mouseDy = 0f; return d }
    override fun consumeScroll(): Float { val s = scroll; scroll = 0f; return s }
    override fun consumeResize(): Boolean { val r = resized; resized = false; return r }
    override fun buttonDown(): Boolean { val d = leftDown || leftPressed; leftPressed = false; return d }
    override fun consumeText(): String { val t = typed.toString(); typed.setLength(0); return t }

    override fun close() {
        runCatching { Win32.ClipCursor.invokeExact(MemorySegment.NULL) as Int; Win32.DestroyWindow.invokeExact(hwnd) as Int }
        arena.close()
    }
}
