package sillage.platform

import sillage.platform.Native.fn
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/*
 * OpenGL ES in a desktop window, for when Vulkan is not there: EGL draws into the engine's own Wayland
 * window (through libwayland-egl's wl_egl_window) or X11 window (EGL's XCB platform on the window's own
 * connection, else its X11 platform on a connection of EGL's), all through FFM.
 */

/** One way EGL can reach a window: its platform, its native display and window, how it is paced. */
internal class EglPlatform(
    val name: String,
    /** EGL_PLATFORM_…, for eglGetPlatformDisplay, and the client extensions that offer it. */
    val id: Int, val extensions: List<String>,
    val nativeDisplay: MemorySegment, val attributes: IntArray,
    /** What eglGetDisplay takes when EGL has no eglGetPlatformDisplay (EGL 1.4). */
    val legacyDisplay: MemorySegment,
    /** The visual the configuration must have (X11), 0 for any. */
    val visual: Int,
    val swapInterval: Int,
    /** The window as eglCreatePlatformWindowSurface takes it (a pointer to it, on X11). */
    val platformWindow: (Arena) -> MemorySegment,
    /** The window as eglCreateWindowSurface takes it. */
    val legacyWindow: () -> MemorySegment)

/** What EGL needs of a window to draw into it, and what the window must hear around each picture. */
internal abstract class EglWindow {
    /** The platforms to try, best first. */
    abstract val platforms: List<EglPlatform>
    /** The one EGL took. */
    var chosen: EglPlatform? = null
    /** The drawable is now [width] × [height] pixels. */
    open fun resize(width: Int, height: Int) {}
    /** Just before eglSwapBuffers. */
    open fun beforeSwap() {}
    /** Waits (at most [seconds]) until the screen wants the next picture. */
    open fun waitForFrame(seconds: Double) {}
    open fun close() {}
}

/** The EGL side of [window], or a plain error for a window EGL cannot draw into here. */
internal fun eglWindowFor(window: Window): EglWindow = when (window) {
    is WaylandWindow -> WaylandEgl(window)
    is XcbWindow -> XcbEgl(window)
    else -> throw IllegalStateException("OpenGL ES cannot draw into a window of this system yet (${window.javaClass.simpleName})")
}

/** libwayland-egl: the wl_egl_window that stands between a wl_surface and EGL. */
private object WlEgl {
    private val linker = Linker.nativeLinker()
    private val lib = try { Native.library("libwayland-egl.so.1", "libwayland-egl.so") }
        catch (e: IllegalStateException) { throw IllegalStateException("libwayland-egl.so.1 is missing (OpenGL ES in a Wayland window needs it)", e) }
    @JvmField val create: MethodHandle = linker.downcallHandle(lib.fn("wl_egl_window_create"), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT))
    @JvmField val resize: MethodHandle = linker.downcallHandle(lib.fn("wl_egl_window_resize"), FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT))
    @JvmField val destroy: MethodHandle = linker.downcallHandle(lib.fn("wl_egl_window_destroy"), FunctionDescriptor.ofVoid(ADDRESS))
}

/**
 * A Wayland window: the compositor paces it. The swap does not wait (interval 0); before each picture the
 * window asks for a frame callback and the next frame waits for it, at most a tenth of a second, so that a
 * hidden window (which may never be called back) does not stop the game.
 */
private class WaylandEgl(private val window: WaylandWindow) : EglWindow() {
    private var native: MemorySegment

    init {
        val size = window.framebufferSize()
        native = WlEgl.create.invokeExact(window.wlSurface, size[0].coerceAtLeast(1), size[1].coerceAtLeast(1)) as MemorySegment
        check(native != MemorySegment.NULL) { "wl_egl_window_create failed" }
    }

    override val platforms = listOf(EglPlatform("Wayland", 0x31D8 /* EGL_PLATFORM_WAYLAND_KHR */,
        listOf("EGL_KHR_platform_wayland", "EGL_EXT_platform_wayland"), window.wlDisplay, intArrayOf(), window.wlDisplay,
        visual = 0, swapInterval = 0, platformWindow = { native }, legacyWindow = { native }))

    override fun resize(width: Int, height: Int) { WlEgl.resize.invokeExact(native, width, height, 0, 0) }
    override fun beforeSwap() = window.requestFrame()
    override fun waitForFrame(seconds: Double) { window.waitForFrame(seconds) }
    override fun close() {
        if (native != MemorySegment.NULL) { WlEgl.destroy.invokeExact(native); native = MemorySegment.NULL }
    }
}

/** An X11 window: EGL's XCB platform on the window's own connection, else its X11 one; the swap waits for the screen. */
private class XcbEgl(window: XcbWindow) : EglWindow() {
    private val xid = window.xcbWindow.toLong() and 0xffffffffL
    override val platforms = listOf(
        EglPlatform("X11 (XCB)", 0x31DC /* EGL_PLATFORM_XCB_EXT */, listOf("EGL_EXT_platform_xcb"), window.xcbConnection,
            intArrayOf(0x31DE /* EGL_PLATFORM_XCB_SCREEN_EXT */, 0), MemorySegment.NULL, window.xcbVisual, 1,
            platformWindow = { a -> a.allocateFrom(JAVA_INT, window.xcbWindow) }, legacyWindow = { MemorySegment.ofAddress(xid) }),
        // EGL opens its own connection to the same X server ($DISPLAY): window numbers are the server's.
        EglPlatform("X11", 0x31D5 /* EGL_PLATFORM_X11_KHR */, listOf("EGL_KHR_platform_x11", "EGL_EXT_platform_x11"), MemorySegment.NULL,
            intArrayOf(), MemorySegment.NULL, window.xcbVisual, 1,
            platformWindow = { a -> a.allocateFrom(JAVA_LONG, xid) }, legacyWindow = { MemorySegment.ofAddress(xid) }))
}
