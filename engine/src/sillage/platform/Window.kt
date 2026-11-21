package sillage.platform

import sillage.SurfaceSource

/**
 * Keys by physical position, named after the US layout (Linux evdev codes): the key under the
 * left middle finger is [W] on QWERTY and Z on AZERTY, so one binding serves both layouts.
 */
/** Number of key codes the windows track (Linux evdev codes, see [sillage.Key]). */
internal const val KEY_COUNT = 256

/** A desktop window that Vulkan can present to, with keyboard and mouse input. */
internal interface Window : SurfaceSource, AutoCloseable {
    val shouldClose: Boolean
    /** True while the window has keyboard focus. */
    val focused: Boolean
    /** When true the cursor is hidden and held in place; mouse motion arrives as relative deltas. */
    var mouseCaptured: Boolean
    var title: String

    /** Handles pending events without blocking. */
    fun poll()
    /** Waits up to [seconds] for an event, then handles what arrived. */
    fun waitEvents(seconds: Double)
    /** True while [key] is held, or if it went down since the last [clearPresses] (a tap shorter than a frame still counts). */
    fun isDown(key: Int): Boolean
    /** Forgets the keys that went down and up again since the last call: the game has seen them. */
    fun clearPresses()
    /** Mouse motion accumulated while captured since the last call, in pixels: (dx, dy). */
    fun consumeMouseDelta(): FloatArray
    /** Wheel notches since the last call, positive away from the user. */
    fun consumeScroll(): Float
    /** True once after the drawable size changed. */
    fun consumeResize(): Boolean

    /** Pointer position in pixels of the drawable, NaN when it is outside the window. */
    val pointerX: Float
    val pointerY: Float
    /** True while the left button is held, or if it went down since the last call (a click shorter than a frame still counts). */
    fun buttonDown(): Boolean
    /** Characters typed since the last call, through the keyboard layout (see [KeyboardText]). */
    fun consumeText(): String
    /** When true (the default), a left click in the window captures the mouse; the interface turns it off over its widgets and menus. */
    var clickCaptures: Boolean
}

/**
 * Opens a window on the running desktop: Wayland when WAYLAND_DISPLAY is set, X11 (XCB) otherwise.
 * SILLAGE_PLATFORM=wayland or x11 forces one.
 */
internal fun openWindow(title: String, width: Int, height: Int): Window {
    val forced = System.getenv("SILLAGE_PLATFORM")?.lowercase()
    val wayland = when (forced) {
        "wayland" -> true
        "x11" -> false
        null, "" -> !System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()
        else -> throw IllegalArgumentException("SILLAGE_PLATFORM=$forced: use wayland or x11")
    }
    if (wayland) return WaylandWindow(title, width, height)
    check(!System.getenv("DISPLAY").isNullOrEmpty()) {
        "No graphical session (neither WAYLAND_DISPLAY nor DISPLAY). Use --headless to render off screen." }
    return XcbWindow(title, width, height)
}
