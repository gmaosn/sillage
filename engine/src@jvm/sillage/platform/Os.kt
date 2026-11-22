package sillage.platform

/** Which system the desktop backend runs on: the windows and Vulkan are shared, sound and pads are each system's own. */
internal object Os {
    private val name = System.getProperty("os.name", "").lowercase()
    val linux = name.startsWith("linux")
    val freebsd = name.startsWith("freebsd") || name.startsWith("dragonfly")
    val netbsd = name.startsWith("netbsd")
    val openbsd = name.startsWith("openbsd")
    val bsd = freebsd || netbsd || openbsd
    val windows = name.startsWith("windows")
    val label: String get() = System.getProperty("os.name", "?") + " " + System.getProperty("os.arch", "?")
}
