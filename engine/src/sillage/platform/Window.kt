package sillage.platform

import sillage.SurfaceSource

/**
 * Keys by physical position, named after the US layout (Linux evdev codes): the key under the
 * left middle finger is [W] on QWERTY and Z on AZERTY, so one binding serves both layouts.
 */
object Key {
    const val ESCAPE = 1
    const val TAB = 15
    const val Q = 16
    const val W = 17
    const val E = 18
    const val R = 19
    const val A = 30
    const val S = 31
    const val D = 32
    const val Z = 44
    const val SHIFT = 42
    const val RIGHT_SHIFT = 54
    const val SPACE = 57
    const val UP = 103
    const val LEFT = 105
    const val RIGHT = 106
    const val DOWN = 108
    internal const val COUNT = 256
}

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
    fun isDown(key: Int): Boolean
    /** Mouse motion accumulated while captured since the last call, in pixels: (dx, dy). */
    fun consumeMouseDelta(): FloatArray
    /** Wheel notches since the last call, positive away from the user. */
    fun consumeScroll(): Float
    /** True once after the drawable size changed. */
    fun consumeResize(): Boolean
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
        else -> throw IllegalArgumentException("SILLAGE_PLATFORM=$forced : wayland ou x11")
    }
    if (wayland) return WaylandWindow(title, width, height)
    check(!System.getenv("DISPLAY").isNullOrEmpty()) {
        "Pas de session graphique (ni WAYLAND_DISPLAY ni DISPLAY). Utiliser --headless pour le rendu Vulkan hors écran." }
    return XcbWindow(title, width, height)
}
