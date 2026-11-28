package sillage.platform

import sillage.vk.VulkanException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Something the game needs and this computer lacks (Vulkan, a screen, a file…), told in plain words for
 * someone who has never seen a stack trace: what is missing, and what to type to get it. When nothing
 * catches it, [Advice.explainUncaught] prints the message alone, with the line of the game that asked.
 */
internal class Trouble(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** What this system is, and the packages that bring what the engine needs. */
internal object Advice {
    private fun osRelease(): Map<String, String> = try {
        Files.readAllLines(Path.of("/etc/os-release")).mapNotNull { l ->
            val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1).trim().removeSurrounding("\"")
        }.toMap()
    } catch (e: Exception) { emptyMap() }

    private val release: Map<String, String> by lazy { osRelease() }

    /** The system's name, as it says it (« CachyOS », « Debian GNU/Linux 12 (bookworm) »…). */
    val system: String get() = release["PRETTY_NAME"] ?: (System.getProperty("os.name", "?") + " " + System.getProperty("os.version", ""))

    /** The package tool: pacman, apt, dnf, zypper, pkg, or null when it is not known. */
    val packageTool: String? by lazy {
        val ids = ((release["ID"] ?: "") + " " + (release["ID_LIKE"] ?: "")).lowercase().split(' ')
        when {
            Os.freebsd -> "pkg"
            ids.any { it in setOf("arch", "cachyos", "manjaro", "endeavouros", "artix") } -> "pacman"
            ids.any { it in setOf("debian", "ubuntu", "raspbian", "linuxmint", "pop") } -> "apt"
            ids.any { it in setOf("fedora", "rhel", "centos", "rocky", "almalinux") } -> "dnf"
            ids.any { it.startsWith("opensuse") || it == "suse" } -> "zypper"
            else -> null
        }
    }

    /** A graphics card the kernel knows: its maker and its kernel driver (« xe », « amdgpu », « v3d »…). */
    class Gpu(val vendor: String, val driver: String)

    val gpus: List<Gpu> by lazy {
        try {
            Files.list(Path.of("/sys/class/drm")).use { s ->
                s.filter { Regex("card[0-9]+").matches(it.fileName.toString()) }.map { card ->
                    val dev = card.resolve("device")
                    val vendor = runCatching { Files.readString(dev.resolve("vendor")).trim() }.getOrDefault("")
                    val driver = runCatching { Files.readSymbolicLink(dev.resolve("driver")).fileName.toString() }.getOrDefault("")
                    Gpu(when (vendor) {
                        "0x8086" -> "intel"; "0x1002" -> "amd"; "0x10de" -> "nvidia"; "0x1af4" -> "virtio"
                        else -> if (driver in setOf("v3d", "vc4")) "broadcom" else vendor.ifEmpty { "?" }
                    }, driver)
                }.toList()
            }
        } catch (e: Exception) { emptyList() }
    }

    /** The words to type to install [packages] here. */
    fun install(vararg packages: String): String {
        val list = packages.filter { it.isNotEmpty() }.joinToString(" ")
        return when (packageTool) {
            "pacman" -> "sudo pacman -S $list"
            "apt" -> "sudo apt install $list"
            "dnf" -> "sudo dnf install $list"
            "zypper" -> "sudo zypper install $list"
            "pkg" -> "sudo pkg install $list"
            else -> "install the package(s) $list (their names differ from one system to another)"
        }
    }

    /** The Vulkan loader (libvulkan.so.1). */
    val vulkanLoader: String get() = when (packageTool) {
        "pacman" -> "vulkan-icd-loader"; "apt" -> "libvulkan1"; "dnf" -> "vulkan-loader"; "zypper" -> "libvulkan1"; "pkg" -> "vulkan-loader"
        else -> "vulkan-loader"
    }

    /** The Vulkan drivers for the cards found (Mesa's, or NVIDIA's). */
    val vulkanDrivers: List<String> get() {
        val kinds = gpus.map { if (it.vendor == "nvidia" && it.driver == "nouveau") "nouveau" else it.vendor }.distinct()
        return when (packageTool) {
            "pacman" -> kinds.mapNotNull {
                when (it) { "intel" -> "vulkan-intel"; "amd" -> "vulkan-radeon"; "nvidia" -> "nvidia-utils"; "nouveau" -> "vulkan-nouveau"
                    "broadcom" -> "vulkan-broadcom"; "virtio" -> "vulkan-virtio"; else -> null }
            }.ifEmpty { listOf("vulkan-intel", "vulkan-radeon") }
            "apt", "dnf" -> listOf(if (kinds == listOf("nvidia")) "nvidia-vulkan-icd" else "mesa-vulkan-drivers")
            "zypper" -> kinds.mapNotNull { when (it) { "intel" -> "libvulkan_intel"; "amd" -> "libvulkan_radeon"; else -> null } }.ifEmpty { listOf("libvulkan_intel", "libvulkan_radeon") }
            else -> listOf("the Vulkan driver of your graphics card")
        }
    }

    /** OpenGL ES through EGL (libEGL.so.1, libGLESv2.so.2). */
    val eglPackages: List<String> get() = when (packageTool) {
        "pacman" -> listOf("mesa", "libglvnd"); "apt" -> listOf("libegl1", "libgles2", "libegl-mesa0"); "dnf" -> listOf("mesa-libEGL", "mesa-libGLES")
        "zypper" -> listOf("Mesa-libEGL1", "Mesa-libGLESv2-2"); else -> listOf("mesa")
    }

    /** libwayland-egl.so.1, which lets EGL draw into a Wayland window. */
    val waylandEgl: String get() = when (packageTool) {
        "pacman" -> "wayland"; "apt" -> "libwayland-egl1"; "dnf" -> "libwayland-egl"; "zypper" -> "libwayland-egl1"; else -> "wayland"
    }

    /** ALSA's library (libasound.so.2), through which sound reaches PipeWire or PulseAudio. */
    val alsa: String get() = when (packageTool) {
        "pacman" -> "alsa-lib"; "apt" -> "libasound2"; "dnf" -> "alsa-lib"; "zypper" -> "alsa"; else -> "alsa-lib"
    }

    /** Why this user may not use the graphics card, or null when the card's device is there and open to them. */
    fun renderNodeProblem(): String? {
        if (!Os.linux) return null
        val nodes = try { Files.list(Path.of("/dev/dri")).use { s -> s.filter { it.fileName.toString().startsWith("renderD") }.toList() } } catch (e: Exception) { emptyList() }
        if (nodes.isEmpty()) return "the graphics card has no device in /dev/dri (its kernel driver is not loaded)"
        if (nodes.none { Files.isReadable(it) && Files.isWritable(it) })
            return "you may not use the graphics card (${nodes.first()}): type  sudo usermod -aG render,video \$USER  then log out and back in"
        return null
    }

    /** The first message along [e] and its causes that says something (not « Could not initialize class … »). */
    private fun said(e: Throwable): String = generateSequence(e) { it.cause }.mapNotNull { it.message }
        .firstOrNull { !it.startsWith("Could not initialize class") } ?: e.javaClass.simpleName
    /** Whether a message along [e] and its causes holds [text]. */
    private fun mentions(e: Throwable, text: String) = generateSequence(e) { it.cause }.any { it.message?.contains(text) == true }
    private fun vulkanResult(e: Throwable): Int? = generateSequence(e) { it.cause }.filterIsInstance<VulkanException>().firstOrNull()?.result

    /** Why Vulkan did not start, in a few words, from what it threw. */
    fun vulkanReason(e: Throwable): String {
        val m = said(e)
        return when {
            mentions(e, "No Vulkan loader") -> "Vulkan is not installed (libvulkan.so.1 is missing)"
            vulkanResult(e) == -9 -> "there is no Vulkan driver for this graphics card"
            mentions(e, "No Vulkan driver") -> "Vulkan finds no graphics card" + (renderNodeProblem()?.let { ": $it" } ?: "")
            mentions(e, "No Vulkan graphics queue can present") -> "Vulkan cannot draw into this window"
            else -> m.lineSequence().first()
        }
    }

    /** What to type so that Vulkan works, or null when no package would help. */
    fun vulkanFix(e: Throwable): String? {
        if (Os.windows) return "install the newest driver of your graphics card (from Intel, AMD or NVIDIA)"
        val chosen = listOf("VK_DRIVER_FILES", "VK_ICD_FILENAMES").filter { !System.getenv(it).isNullOrEmpty() }
        if (chosen.isNotEmpty() && !mentions(e, "No Vulkan loader"))
            return "unset " + chosen.joinToString(" and ") + " (" + (if (chosen.size > 1) "they choose" else "it chooses") + " which drivers Vulkan loads)"
        return when {
            mentions(e, "No Vulkan loader") -> install(vulkanLoader, *vulkanDrivers.toTypedArray())
            vulkanResult(e) == -9 -> install(*vulkanDrivers.toTypedArray())
            mentions(e, "No Vulkan driver") -> renderNodeProblem() ?: install(*vulkanDrivers.toTypedArray())
            else -> null
        }
    }

    /** Why OpenGL ES did not start, in a few words. */
    fun glReason(e: Throwable): String = when {
        mentions(e, "libEGL") || mentions(e, "libGLESv2") -> "OpenGL ES is not installed (libEGL.so.1 or libGLESv2.so.2 is missing)"
        mentions(e, "libwayland-egl") -> "libwayland-egl.so.1 is missing"
        mentions(e, "EGL: no display") || mentions(e, "eglInitialize failed") -> "EGL finds no driver for this computer"
        mentions(e, "no OpenGL ES 3") -> "the graphics driver has no OpenGL ES 3"
        else -> said(e).lineSequence().first()
    }

    /** What to type so that OpenGL ES works, or null. */
    fun glFix(e: Throwable): String? = when {
        Os.windows -> null
        mentions(e, "libEGL") || mentions(e, "libGLESv2") || mentions(e, "EGL: no display") || mentions(e, "eglInitialize failed") -> install(*eglPackages.toTypedArray())
        mentions(e, "libwayland-egl") -> install(waylandEgl)
        else -> null
    }

    /** A sound output that would not open, told simply (the game goes on without sound). */
    fun soundReason(e: Throwable): String = when {
        mentions(e, "libasound") -> "ALSA is not installed (libasound.so.2 is missing: ${install(alsa)})"
        else -> said(e).lineSequence().first()
    }

    /**
     * Neither Vulkan nor OpenGL ES could draw: what each said, and what to type. [vulkan] or [gl] is null when
     * that one was not tried.
     */
    fun cannotDraw(vulkan: Throwable?, gl: Throwable?): Trouble {
        val text = StringBuilder(if (vulkan != null && gl != null) "This computer cannot draw the game: neither Vulkan nor OpenGL ES works.\n"
            else if (vulkan != null) "Vulkan, which draws the game, does not work here.\n" else "OpenGL ES, which draws the game here, does not work.\n")
        if (vulkan != null) text.append("  Vulkan: ").append(vulkanReason(vulkan)).append(".\n")
        if (gl != null) text.append("  OpenGL ES: ").append(glReason(gl)).append(".\n")
        val fixes = listOfNotNull(vulkan?.let { vulkanFix(it) }, gl?.let { glFix(it) }).distinct()
        if (fixes.isNotEmpty()) {
            text.append("  To mend it, type in a terminal:\n")
            for (f in fixes) text.append("      ").append(f).append('\n')
            text.append("  then start the game again.\n")
        }
        if (vulkan != null && gl == null) text.append("  --renderer gl draws with OpenGL ES instead.\n")
        text.append("  ./sillage doctor (or the game with --doctor) checks everything and says what is missing.")
        return Trouble(text.toString(), vulkan ?: gl)
    }

    // ------------------------------------------------------------------ what reaches the top

    private val enginePackages = setOf("sillage", "sillage.io", "sillage.audio", "sillage.platform", "sillage.gl", "sillage.vk", "sillage.math",
        "sillage.physics", "sillage.ui", "sillage.dev", "sillage.web", "sillage.android")

    /** The line of the game's own code that led to [e] (« Main.kt, line 12 »), or null. */
    fun gameLine(e: Throwable): String? {
        for (f in e.stackTrace) {
            val pkg = f.className.substringBeforeLast('.', "")
            if (pkg in enginePackages || pkg.startsWith("java.") || pkg.startsWith("jdk.") || pkg.startsWith("kotlin") || pkg.startsWith("sun.")) continue
            if (f.fileName == null || f.lineNumber <= 0) continue
            return f.fileName + ", line " + f.lineNumber
        }
        return null
    }

    @Volatile private var installed = false

    /**
     * When a plain-words problem reaches the top of a thread uncaught, prints its message alone (and where the
     * game asked), not a Java stack trace; anything else is printed as Java always does. SILLAGE_TRACE=1 keeps
     * the whole trace. The program still ends with an error status: nothing else changes.
     */
    fun explainUncaught() {
        if (installed) return
        installed = true
        if (Thread.getDefaultUncaughtExceptionHandler() != null || System.getenv("SILLAGE_TRACE") == "1") return
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val text = plainText(e)
            if (text == null) { System.err.print("Exception in thread \"" + t.name + "\" "); e.printStackTrace() }
            else System.err.println("\n" + text + "\n")
        }
    }

    /** What the top prints for [e] when it is a plain-words problem: its message and the game's line; null otherwise. */
    fun plainText(e: Throwable): String? {
        val plain = generateSequence(e) { it.cause }.firstOrNull { it is Trouble || it.javaClass.name == "sillage.io.AssetMissing" } ?: return null
        return "Sillage: " + plain.message + (gameLine(e)?.let { "\n(This was asked by your game: $it.)" } ?: "")
    }
}
