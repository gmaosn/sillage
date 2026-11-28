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
 * Opens a window on the running desktop: Wayland when WAYLAND_DISPLAY is set, X11 (XCB) otherwise (and when
 * the Wayland desktop does not answer but an X server does). SILLAGE_PLATFORM=wayland or x11 forces one.
 */
internal fun openWindow(title: String, width: Int, height: Int): Window {
    if (Os.windows) return Win32Window(title, width, height)
    val forced = System.getenv("SILLAGE_PLATFORM")?.lowercase()
    val hasWayland = !System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()
    val hasX = !System.getenv("DISPLAY").isNullOrEmpty()
    val wayland = when (forced) {
        "wayland" -> true
        "x11" -> false
        null, "" -> hasWayland
        else -> throw IllegalArgumentException("SILLAGE_PLATFORM=$forced: use wayland or x11")
    }
    if (!wayland && !hasX && forced == null) throw Trouble(
        "There is no screen to open a window on.\n" +
        "  This terminal is not part of a desktop session (neither WAYLAND_DISPLAY nor DISPLAY is set): over SSH, or in a text console.\n" +
        "  Start the game from a terminal opened on the desktop, or add --headless to draw without a window\n" +
        "  (with --capture picture.png to see what it drew).")
    if (wayland) {
        try { return WaylandWindow(title, width, height) }
        catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            if (forced == "wayland" || !hasX) throw Trouble(
                "The window could not open on the Wayland desktop (WAYLAND_DISPLAY=${System.getenv("WAYLAND_DISPLAY")}): ${e.message}\n" +
                "  SILLAGE_PLATFORM=x11 tries X11 instead; --headless draws without a window.", e)
            println("Sillage: the Wayland desktop did not open a window (" + e.message + "), opening it with X11 instead")
        }
    }
    try { return XcbWindow(title, width, height) }
    catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        throw Trouble("The window could not open on the X11 desktop (DISPLAY=${System.getenv("DISPLAY")}): ${e.message}\n" +
            "  --headless draws without a window.", e)
    }
}
