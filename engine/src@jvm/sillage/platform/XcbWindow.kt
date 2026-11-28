package sillage.platform

import sillage.platform.Native.fn
import sillage.vk.NativeStack
import sillage.vk.VK_KHR_SURFACE_EXTENSION_NAME
import sillage.vk.VK_KHR_XCB_SURFACE_EXTENSION_NAME
import sillage.vk.VkInstanceApi
import sillage.vk.VkXcbSurfaceCreateInfoKHR
import sillage.vk.struct
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

private val NULL: MemorySegment = MemorySegment.NULL

internal object Xcb {
    private val lib = Native.library("libxcb.so.1", "libxcb.so")
    val connect = lib.fn("xcb_connect")
    val disconnect = lib.fn("xcb_disconnect")
    val hasError = lib.fn("xcb_connection_has_error")
    val getSetup = lib.fn("xcb_get_setup")
    val rootsIterator = lib.fn("xcb_setup_roots_iterator")
    val generateId = lib.fn("xcb_generate_id")
    val createWindow = lib.fn("xcb_create_window")
    val destroyWindow = lib.fn("xcb_destroy_window")
    val mapWindow = lib.fn("xcb_map_window")
    val flush = lib.fn("xcb_flush")
    val pollForEvent = lib.fn("xcb_poll_for_event")
    val fileDescriptor = lib.fn("xcb_get_file_descriptor")
    val internAtom = lib.fn("xcb_intern_atom")
    val internAtomReply = lib.fn("xcb_intern_atom_reply")
    val changeProperty = lib.fn("xcb_change_property")
    val changeWindowAttributes = lib.fn("xcb_change_window_attributes")
    val warpPointer = lib.fn("xcb_warp_pointer")
    val createPixmap = lib.fn("xcb_create_pixmap")
    val createCursor = lib.fn("xcb_create_cursor")

    /** xcb_screen_iterator_t xcb_setup_roots_iterator(const xcb_setup_t *): a 16-byte struct returned by value. */
    @JvmField val ROOTS: MethodHandle = PSig.linker.downcallHandle(FunctionDescriptor.of(
        MemoryLayout.structLayout(ADDRESS.withName("data"), JAVA_INT.withName("rem"), JAVA_INT.withName("index")), ADDRESS))

    const val KEY_PRESS = 2; const val KEY_RELEASE = 3; const val BUTTON_PRESS = 4; const val BUTTON_RELEASE = 5; const val MOTION_NOTIFY = 6
    const val LEAVE_NOTIFY = 8
    const val FOCUS_IN = 9; const val FOCUS_OUT = 10; const val CONFIGURE_NOTIFY = 22; const val CLIENT_MESSAGE = 33
}

/** An X11 window through XCB: the fallback for X sessions and for tests under Xvfb. */
internal class XcbWindow(title: String, private var width: Int, private var height: Int) : Window {
    private val arena = Arena.ofConfined()
    private val stack = NativeStack(16 * 1024, arena)
    private val pollScratch = arena.allocate(8, 4)
    private val conn: MemorySegment
    private val window: Int
    /** The connection, the window and its visual, for EGL's X11 platforms (the window has the screen's own visual). */
    internal val xcbConnection: MemorySegment get() = conn
    internal val xcbWindow: Int get() = window
    internal var xcbVisual = 0; private set
    private var wmDelete = 0
    private var netWmName = 0
    private var utf8String = 0
    private var hiddenCursor = 0
    private var skipMotion = false
    private var mouseDx = 0f; private var mouseDy = 0f
    private var scroll = 0f
    private var resized = false
    private val keys = BooleanArray(KEY_COUNT)
    private val pressed = BooleanArray(KEY_COUNT)          // went down since the game last looked
    private val text = KeyboardText().also { it.repeats = false }   // le serveur X répète les touches lui-même
    private var leftDown = false; private var leftPressed = false
    override var pointerX = Float.NaN; private set
    override var pointerY = Float.NaN; private set
    override var clickCaptures = true

    override var shouldClose = false; private set
    // Sans gestionnaire de fenêtres (Xvfb), aucun FocusIn n'arrive : la fenêtre est réputée active.
    override var focused = true; private set

    override var title: String = title
        set(v) { field = v; writeTitle(v) }

    override var mouseCaptured = false
        set(v) { if (field != v) { field = v; applyCapture() } }

    init {
        conn = PSig.P_PP.invokeExact(Xcb.connect, NULL, NULL) as MemorySegment
        check(conn != NULL && (PSig.I_P.invokeExact(Xcb.hasError, conn) as Int) == 0) {
            "Cannot connect to X11 (DISPLAY=${System.getenv("DISPLAY")})" }
        try {
            val setup = PSig.P_P.invokeExact(Xcb.getSetup, conn) as MemorySegment
            val iterator = Xcb.ROOTS.invokeExact(Xcb.rootsIterator, arena as SegmentAllocator, setup) as MemorySegment
            val screen = iterator.get(ADDRESS, 0).reinterpret(40)
            val root = screen.get(JAVA_INT, 0); val visual = screen.get(JAVA_INT, 32)
            xcbVisual = visual
            window = PSig.I_P.invokeExact(Xcb.generateId, conn) as Int
            val mask = 0x1 or 0x2 or 0x4 or 0x8 or 0x20 or 0x40 or 0x20000 or 0x200000 // touches, boutons, sortie, mouvement, structure, focus
            stack.frame { s ->
                (PSig.I_PBIISSSSSSIIP.invokeExact(Xcb.createWindow, conn, 0.toByte(), window, root, 0.toShort(), 0.toShort(),
                    width.toShort(), height.toShort(), 0.toShort(), 1.toShort(), visual, 0x800 /* CW_EVENT_MASK */, s.ints(mask)) as Int)
            }
            val protocols = atom("WM_PROTOCOLS"); wmDelete = atom("WM_DELETE_WINDOW")
            netWmName = atom("_NET_WM_NAME"); utf8String = atom("UTF8_STRING")
            stack.frame { s ->
                (PSig.I_PBIIIBIP.invokeExact(Xcb.changeProperty, conn, 0.toByte(), window, protocols, 4 /* ATOM */, 32.toByte(), 1, s.ints(wmDelete)) as Int)
            }
            writeTitle(title)
            // La disposition du clavier pour le texte tapé : celle du serveur X, sinon celle par défaut.
            if (!text.keymapX11(conn)) text.keymapNames()
            println("SILLAGE_CLAVIER " + text.origin)
            (PSig.I_PI.invokeExact(Xcb.mapWindow, conn, window) as Int)
            flush()
        } catch (t: Throwable) { PSig.V_P.invokeExact(Xcb.disconnect, conn); arena.close(); throw t }
    }

    private fun atom(name: String): Int = stack.frame { s ->
        val cookie = PSig.I_PBSP.invokeExact(Xcb.internAtom, conn, 0.toByte(), name.length.toShort(), s.allocateFrom(name)) as Int
        val reply = PSig.P_PIP.invokeExact(Xcb.internAtomReply, conn, cookie, NULL) as MemorySegment
        check(reply != NULL) { "X11 atom $name unavailable" }
        val a = reply.reinterpret(12).get(JAVA_INT, 8)
        Native.free(reply)
        a
    }

    private fun writeTitle(text: String) = stack.frame { s ->
        val bytes = text.toByteArray(Charsets.UTF_8)
        val data = s.allocate(bytes.size.toLong().coerceAtLeast(1)); MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.size)
        (PSig.I_PBIIIBIP.invokeExact(Xcb.changeProperty, conn, 0.toByte(), window, 39 /* WM_NAME */, 31 /* STRING */, 8.toByte(), bytes.size, data) as Int)
        (PSig.I_PBIIIBIP.invokeExact(Xcb.changeProperty, conn, 0.toByte(), window, netWmName, utf8String, 8.toByte(), bytes.size, data) as Int)
        flush()
    }

    private fun applyCapture() {
        if (mouseCaptured && hiddenCursor == 0) {
            val pixmap = PSig.I_P.invokeExact(Xcb.generateId, conn) as Int
            (PSig.I_PBIISS.invokeExact(Xcb.createPixmap, conn, 1.toByte(), pixmap, window, 1.toShort(), 1.toShort()) as Int)
            hiddenCursor = PSig.I_P.invokeExact(Xcb.generateId, conn) as Int
            val z: Short = 0
            (PSig.I_PIIISSSSSSSS.invokeExact(Xcb.createCursor, conn, hiddenCursor, pixmap, pixmap, z, z, z, z, z, z, z, z) as Int)
        }
        stack.frame { s ->
            (PSig.I_PIIP.invokeExact(Xcb.changeWindowAttributes, conn, window, 0x4000 /* CW_CURSOR */, s.ints(if (mouseCaptured) hiddenCursor else 0)) as Int)
        }
        if (mouseCaptured) { warpToCenter(); skipMotion = true }
        mouseDx = 0f; mouseDy = 0f
        flush()
    }

    private fun warpToCenter() {
        val z: Short = 0
        (PSig.I_PIISSSSSS.invokeExact(Xcb.warpPointer, conn, 0, window, z, z, z, z, (width / 2).toShort(), (height / 2).toShort()) as Int)
    }

    override val instanceExtensions = listOf(VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_XCB_SURFACE_EXTENSION_NAME)

    override fun createSurface(vk: VkInstanceApi, stack: NativeStack): Long = stack.frame { s ->
        val out = s.allocate(JAVA_LONG)
        vk.createXcbSurfaceKHR(s.struct(VkXcbSurfaceCreateInfoKHR, "connection" to conn, "window" to window), out)
        out.get(JAVA_LONG, 0)
    }

    override fun framebufferSize() = intArrayOf(width, height)

    override fun poll() {
        var warp = false
        while (true) {
            val e = PSig.P_P.invokeExact(Xcb.pollForEvent, conn) as MemorySegment
            if (e == NULL) break
            val ev = e.reinterpret(32)
            try {
                when ((ev.get(JAVA_BYTE, 0).toInt() and 0x7f)) {
                    Xcb.KEY_PRESS, Xcb.KEY_RELEASE -> {
                        val code = (ev.get(JAVA_BYTE, 1).toInt() and 0xff) - 8  // X11 = evdev + 8
                        if (code in 0 until KEY_COUNT) {
                            val down = (ev.get(JAVA_BYTE, 0).toInt() and 0x7f) == Xcb.KEY_PRESS
                            keys[code] = down; if (down) pressed[code] = true
                            text.key(code, down, System.nanoTime(), tracksModifiers = true)
                        }
                    }
                    Xcb.BUTTON_PRESS -> when (ev.get(JAVA_BYTE, 1).toInt() and 0xff) {
                        1 -> { leftDown = true; leftPressed = true; if (!mouseCaptured && clickCaptures) mouseCaptured = true }
                        4 -> scroll += 1f
                        5 -> scroll -= 1f
                    }
                    Xcb.BUTTON_RELEASE -> if ((ev.get(JAVA_BYTE, 1).toInt() and 0xff) == 1) leftDown = false
                    Xcb.LEAVE_NOTIFY -> { pointerX = Float.NaN; pointerY = Float.NaN }
                    Xcb.MOTION_NOTIFY -> if (!mouseCaptured) { pointerX = ev.get(JAVA_SHORT, 24).toFloat(); pointerY = ev.get(JAVA_SHORT, 26).toFloat() }
                    else if (focused) {
                        val dx = ev.get(JAVA_SHORT, 24) - width / 2; val dy = ev.get(JAVA_SHORT, 26) - height / 2
                        if (skipMotion) skipMotion = false
                        else if (dx != 0 || dy != 0) { mouseDx += dx; mouseDy += dy }
                        if (dx != 0 || dy != 0) warp = true
                    }
                    Xcb.FOCUS_IN -> focused = true
                    Xcb.FOCUS_OUT -> { focused = false; keys.fill(false); pressed.fill(false); text.reset() }
                    Xcb.CONFIGURE_NOTIFY -> {
                        val w = ev.get(JAVA_SHORT, 20).toInt() and 0xffff; val h = ev.get(JAVA_SHORT, 22).toInt() and 0xffff
                        if (w != width || h != height) { width = w; height = h; resized = true }
                    }
                    Xcb.CLIENT_MESSAGE -> if (ev.get(JAVA_INT, 12) == wmDelete) shouldClose = true
                }
            } finally { Native.free(e) }
        }
        if (warp) { warpToCenter(); flush() }
        check((PSig.I_P.invokeExact(Xcb.hasError, conn) as Int) == 0) { "Connexion X11 perdue" }
    }

    override fun waitEvents(seconds: Double) {
        Native.pollIn(PSig.I_P.invokeExact(Xcb.fileDescriptor, conn) as Int, (seconds * 1000).toInt().coerceAtLeast(0), pollScratch)
        poll()
    }

    override fun isDown(key: Int) = key in 0 until KEY_COUNT && (keys[key] || pressed[key])
    override fun clearPresses() = pressed.fill(false)
    override fun consumeMouseDelta(): FloatArray { val d = floatArrayOf(mouseDx, mouseDy); mouseDx = 0f; mouseDy = 0f; return d }
    override fun consumeScroll(): Float { val s = scroll; scroll = 0f; return s }
    override fun consumeResize(): Boolean { val r = resized; resized = false; return r }
    override fun buttonDown(): Boolean { val d = leftDown || leftPressed; leftPressed = false; return d }
    override fun consumeText(): String = text.take()

    private fun flush() { (PSig.I_P.invokeExact(Xcb.flush, conn) as Int) }

    override fun close() {
        runCatching { (PSig.I_PI.invokeExact(Xcb.destroyWindow, conn, window) as Int); flush() }
        PSig.V_P.invokeExact(Xcb.disconnect, conn)
        text.close()
        arena.close()
    }
}
