package sillage.platform

import sillage.SurfaceSource

/**
 * Keys by physical position, named after the US layout (Linux evdev codes): the key under the
 * left middle finger is [W] on QWERTY and Z on AZERTY, so one binding serves both layouts.
 */

/** A desktop window that Vulkan can present to, with keyboard and mouse input. */
internal interface Window : GameWindow, SurfaceSource, AutoCloseable {
    override fun size(): IntArray = framebufferSize()
}

/**
 * Opens a window on the running desktop: Wayland when WAYLAND_DISPLAY is set, X11 (XCB) otherwise.
 * SILLAGE_PLATFORM=wayland or x11 forces one.
 */
internal fun openWindow(title: String, width: Int, height: Int): Window {
    if (Os.windows) return Win32Window(title, width, height)
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
