package sillage

import sillage.math.Vec3
import sillage.physics.Box
import sillage.physics.PhysicsWorld
import sillage.physics.TriangleMesh
import sillage.platform.Advice
import sillage.platform.EglGl
import sillage.platform.Native
import sillage.platform.Native.fn
import sillage.platform.Os
import sillage.platform.PSig
import sillage.vk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * `--doctor` (`./sillage doctor`): looks at this computer as the engine sees it and prints a short report to
 * paste in a message — the system and its processor, Java, Vulkan (driver, version), the screen (Wayland, X11),
 * EGL and OpenGL ES, the sound output (opened and closed again, nothing played), memory, cores, free space — then
 * times the game without a window (a few hundred frames) and the physics alone (a heap of crates).
 *
 * Written to run anywhere the engine might, a Raspberry Pi included, and never to stop half way: each check
 * runs on a thread of its own with a time limit, and whatever it throws becomes a line of the report that
 * says what is missing and what to type.
 */
/** [this] as a format, with a dot for decimals whatever the language of the system (the report is in English). */
private fun String.en(vararg a: Any?): String = String.format(java.util.Locale.ROOT, this, *a)

internal class Doctor(private val title: String, private val args: Array<String>, private val setup: Scene.() -> Unit) {
    private val out: PrintStream = System.out
    private class Problem(val what: String, val fix: String?, val blocking: Boolean)
    private val problems: MutableList<Problem> = java.util.Collections.synchronizedList(ArrayList())   // filled from the checks' threads
    private var vulkanDevice: String? = null
    private var vulkanSoftware = false
    private var glRenderer: String? = null

    private fun option(flag: String): String? = args.indexOf(flag).let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }

    private fun line(label: String, text: String) {
        val lines = text.lines()
        out.println(label.padEnd(11) + lines.first())
        for (l in lines.drop(1)) out.println(" ".repeat(11) + l)
        out.flush()
    }

    /**
     * Runs [check] on a thread of its own, at most [seconds]: its text, or what went wrong. A check that does
     * not answer is left behind (daemon thread) and said so.
     */
    private fun timed(seconds: Int, check: () -> String): String {
        val result = AtomicReference<Any?>(null)
        val t = Thread({ result.set(try { check() } catch (e: Throwable) { e }) }, "sillage-doctor")
        t.isDaemon = true
        t.start()
        t.join(seconds * 1000L)
        return when (val r = result.get()) {
            null -> throw DoctorTimeout(seconds)
            is Throwable -> throw r
            else -> r as String
        }
    }
    private class DoctorTimeout(val seconds: Int) : RuntimeException("did not answer within $seconds s")

    /** [check]'s line, or a line that says what failed (never an exception). */
    private fun section(label: String, seconds: Int, check: () -> String) {
        val text = try { timed(seconds, check) } catch (e: Throwable) {
            val why = (e.message ?: e.javaClass.simpleName).lineSequence().first()
            problems.add(Problem(label.lowercase() + " could not be checked: " + why, null, false))
            "could not be checked: $why"
        }
        line(label, text)
    }

    fun run() {
        try {
            out.println("Sillage doctor, " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + ", with the game \"" + title + "\"")
            section("System", 10) { system() }
            section("Processor", 10) { processor() }
            section("Memory", 10) { memory() }
            section("Disk", 10) { disk() }
            section("Java", 10) { java() }
            section("Screen", 15) { screen() }
            section("Vulkan", 30) { vulkan() }
            section("OpenGL ES", 30) { gles() }
            section("Sound", 15) { sound() }
            section("Game", 240) { bench() }
            section("Physics", 120) { physics() }
            verdict()
        } catch (e: Throwable) {
            out.println("(the doctor itself stopped: " + e + ")")
        }
        out.flush()
    }

    // ------------------------------------------------------------------ the machine

    private fun read(path: String): String? = try { Files.readString(Path.of(path)) } catch (e: Throwable) { null }

    private fun system(): String {
        val machine = (read("/proc/device-tree/model") ?: read("/sys/firmware/devicetree/base/model"))?.trim('\u0000', ' ', '\n')
            ?: listOfNotNull(read("/sys/class/dmi/id/sys_vendor")?.trim(), read("/sys/class/dmi/id/product_name")?.trim())
                .filter { it.isNotEmpty() && !it.startsWith("To be filled") }.joinToString(" ").ifEmpty { null }
        val kernel = System.getProperty("os.name") + " " + System.getProperty("os.version") + ", " + System.getProperty("os.arch")
        return Advice.system + ", " + kernel + (machine?.let { "\n$it" } ?: "")
    }

    private fun processor(): String {
        val cores = java.lang.Runtime.getRuntime().availableProcessors()
        val info = read("/proc/cpuinfo") ?: ""
        fun field(name: String) = info.lineSequence().firstOrNull { it.startsWith(name) && ':' in it }?.substringAfter(':')?.trim()
        val name = field("model name") ?: armCore(field("CPU part"), field("CPU implementer")) ?: field("cpu model") ?: field("Hardware")
            ?: System.getenv("PROCESSOR_IDENTIFIER") ?: System.getProperty("os.arch")
        val logical = info.lineSequence().count { it.startsWith("processor") }
        val mhz = read("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")?.trim()?.toLongOrNull()?.let { (it / 1000).toString() + " MHz at most" }
        val temperature = read("/sys/class/thermal/thermal_zone0/temp")?.trim()?.toLongOrNull()?.let { "%.0f C now".en(it / 1000.0) }
        val threads = System.getProperty("sillage.threads")?.toIntOrNull() ?: minOf(8, maxOf(1, cores - 2))
        return name + ", " + (if (logical > 0 && logical != cores) "$logical cores, Java may use $cores" else "$cores cores") +
            listOfNotNull(mhz, temperature).joinToString("") { ", $it" } + "; the physics uses " + threads + (if (threads == 1) " thread" else " threads")
    }

    /** Arm's own cores, by their part number (a Raspberry Pi 5 or 500 has Cortex-A76, a Pi 4 Cortex-A72). */
    private fun armCore(part: String?, implementer: String?): String? {
        if (part == null || implementer?.lowercase() != "0x41") return null
        val names = mapOf("0xd03" to "Cortex-A53", "0xd04" to "Cortex-A35", "0xd05" to "Cortex-A55", "0xd07" to "Cortex-A57", "0xd08" to "Cortex-A72",
            "0xd09" to "Cortex-A73", "0xd0a" to "Cortex-A75", "0xd0b" to "Cortex-A76", "0xd0c" to "Neoverse N1", "0xd0d" to "Cortex-A77",
            "0xd41" to "Cortex-A78", "0xd44" to "Cortex-X1", "0xd46" to "Cortex-A510", "0xd47" to "Cortex-A710", "0xd4d" to "Cortex-A715",
            "0xd80" to "Cortex-A520", "0xd81" to "Cortex-A720")
        return "Arm " + (names[part.lowercase()] ?: "core $part")
    }

    private fun gb(bytes: Long) = "%.1f GB".en(bytes / 1e9)

    private fun memory(): String {
        val info = read("/proc/meminfo")
        fun kb(name: String) = info?.lineSequence()?.firstOrNull { it.startsWith("$name:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024)
        var total = kb("MemTotal"); var free = kb("MemAvailable")
        // Without /proc (Windows, the BSDs): the JDK's management bean, reached by name so that a packed game's
        // Java runtime need not carry its modules (without them, the memory is not said).
        if (total == null) try {
            val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getOperatingSystemMXBean").invoke(null)
            val sun = Class.forName("com.sun.management.OperatingSystemMXBean")
            total = sun.getMethod("getTotalMemorySize").invoke(bean) as Long
            free = sun.getMethod("getFreeMemorySize").invoke(bean) as Long
        } catch (e: Throwable) {}
        val java = java.lang.Runtime.getRuntime().maxMemory()
        val swap = kb("SwapTotal")?.takeIf { it > 0 }?.let { ", swap " + gb(it) } ?: ""
        if (total != null && total < 3_000_000_000L) problems.add(Problem("little memory (${gb(total)}): close other programs while playing", null, false))
        return (total?.let { gb(it) } ?: "?") + (free?.let { ", " + gb(it) + " free" } ?: "") + swap + "; Java may take " + gb(java)
    }

    private fun disk(): String {
        val here = File(".").absoluteFile.normalize()
        val tmp = File(System.getProperty("java.io.tmpdir"))
        val free = here.usableSpace
        if (free in 1 until 1_000_000_000L) problems.add(Problem("less than 1 GB free on the disk of ${here.path}", null, false))
        return gb(free) + " free in " + here.path + (if (tmp.exists() && tmp.usableSpace != free) ", " + gb(tmp.usableSpace) + " in " + tmp.path else "")
    }

    private fun java(): String {
        val native = try { Doctor::class.java.module.isNativeAccessEnabled } catch (e: Throwable) { false }
        if (!native) problems.add(Problem("Java was started without --enable-native-access=ALL-UNNAMED: it warns at every system call (./sillage and the packages add it)", null, false))
        return java.lang.Runtime.version().toString() + ", " + System.getProperty("java.vendor") + " (" + System.getProperty("java.vm.name") + "), " +
            System.getProperty("java.home") + "; native access " + (if (native) "on" else "OFF")
    }

    // ------------------------------------------------------------------ the screen

    private fun screen(): String {
        if (Os.windows) return "Windows: the game opens a Win32 window"
        val wayland = System.getenv("WAYLAND_DISPLAY")?.takeIf { it.isNotEmpty() }
        val x = System.getenv("DISPLAY")?.takeIf { it.isNotEmpty() }
        val parts = ArrayList<String>()
        // Connecting and leaving at once: nothing is shown.
        if (wayland != null) parts.add("Wayland ($wayland) " + try {
            val lib = Native.library("libwayland-client.so.0", "libwayland-client.so")
            val d = PSig.P_P.invokeExact(lib.fn("wl_display_connect"), MemorySegment.NULL) as MemorySegment
            if (d == MemorySegment.NULL) "does not answer" else { PSig.V_P.invokeExact(lib.fn("wl_display_disconnect"), d); "answers" }
        } catch (e: Throwable) { "cannot be reached: " + e.message })
        if (x != null) parts.add("X11 ($x) " + try {
            val lib = Native.library("libxcb.so.1", "libxcb.so")
            val c = PSig.P_PP.invokeExact(lib.fn("xcb_connect"), MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
            val bad = c == MemorySegment.NULL || (PSig.I_P.invokeExact(lib.fn("xcb_connection_has_error"), c) as Int) != 0
            if (c != MemorySegment.NULL) PSig.V_P.invokeExact(lib.fn("xcb_disconnect"), c)
            if (bad) "does not answer" else "answers"
        } catch (e: Throwable) { "cannot be reached: " + e.message })
        if (parts.isEmpty()) {
            problems.add(Problem("no screen: this terminal is not in a desktop session (the game can still run with --headless)", null, false))
            return "none (neither WAYLAND_DISPLAY nor DISPLAY is set: over SSH or in a text console?)"
        }
        val forced = System.getenv("SILLAGE_PLATFORM")?.takeIf { it.isNotEmpty() }
        val keys = try { Native.library("libxkbcommon.so.0", "libxkbcommon.so"); "typed text by libxkbcommon" } catch (e: Throwable) { "no libxkbcommon: typed text on a US layout" }
        val egl = if (wayland != null) try { Native.library("libwayland-egl.so.1", "libwayland-egl.so"); "" } catch (e: Throwable) {
            problems.add(Problem("libwayland-egl.so.1 is missing: OpenGL ES cannot draw into a Wayland window", Advice.install(Advice.waylandEgl), false)); "; no libwayland-egl" } else ""
        return parts.joinToString("; ") + "; the game opens " + (forced ?: if (wayland != null) "a Wayland window" else "an X11 window") + "; " + keys + egl
    }

    // ------------------------------------------------------------------ Vulkan

    private fun vulkan(): String {
        val text = StringBuilder()
        val loaderVersion = try {
            val p = VkLoader.instanceProc(MemorySegment.NULL, "vkEnumerateInstanceVersion")
            if (p == MemorySegment.NULL) VK_API_VERSION_1_0 else Arena.ofConfined().use { a ->
                val v = a.allocate(JAVA_INT); Sig.I_P.invokeExact(p, v) as Int; v.get(JAVA_INT, 0) }
        } catch (e: Throwable) {
            problems.add(Problem("Vulkan: " + Advice.vulkanReason(e) + " (the game is drawn with OpenGL ES instead)", Advice.vulkanFix(e), false))
            return "MISSING: " + Advice.vulkanReason(e) + (Advice.vulkanFix(e)?.let { "\nto have it: $it" } ?: "") + icdFiles()
        }
        val arena = Arena.ofConfined()
        try {
            val stack = NativeStack(256 * 1024, arena)
            val api = minOf(loaderVersion, (1 shl 22) or (3 shl 12))       // at most 1.3: enough to ask for the driver's name
            val vk = stack.frame { s ->
                val app = s.struct(VkApplicationInfo, "pApplicationName" to s.allocateFrom("Sillage doctor"),
                    "pEngineName" to s.allocateFrom("Sillage Kotlin"), "apiVersion" to api)
                val out = s.allocate(ADDRESS); VkLoader.createInstance(s.struct(VkInstanceCreateInfo, "pApplicationInfo" to app), out)
                VkInstanceApi(out.get(ADDRESS, 0))
            }
            try {
                stack.frame { s ->
                    val n = s.allocate(JAVA_INT); vk.enumeratePhysicalDevices(n, MemorySegment.NULL)
                    val count = n.get(JAVA_INT, 0)
                    if (count == 0) throw IllegalStateException("No Vulkan driver available")
                    val devices = s.allocate(ADDRESS, count.toLong()); vk.enumeratePhysicalDevices(n, devices)
                    for (i in 0 until count) {
                        val d = devices.getAtIndex(ADDRESS, i.toLong())
                        val props = s.struct(VkPhysicalDeviceProperties); vk.getPhysicalDeviceProperties(d, props)
                        val name = props.chars(VkPhysicalDeviceProperties, "deviceName")
                        val type = when (props.u32(VkPhysicalDeviceProperties, "deviceType")) { 1 -> "integrated"; 2 -> "discrete"; 3 -> "virtual"; 4 -> "on the processor (software)"; else -> "other" }
                        val v = props.u32(VkPhysicalDeviceProperties, "apiVersion")
                        val version = "Vulkan " + ((v ushr 22) and 0x7f) + "." + ((v ushr 12) and 0x3ff) + "." + (v and 0xfff)
                        val driver = driverName(vk, d, v, api)
                        val heap = deviceMemory(vk, d, s)
                        if (i > 0) text.append('\n')
                        text.append(name).append(", ").append(type).append(", ").append(version).append(driver?.let { ", $it" } ?: "").append(heap)
                        if (vulkanDevice == null || type == "discrete") { vulkanDevice = name; vulkanSoftware = type.startsWith("on the processor") }
                    }
                }
            } finally { vk.destroyInstance() }
            val surfaces = try { VkLoader.extensionNames().filter { it.endsWith("_surface") && it != "VK_KHR_surface" }.joinToString { it.removePrefix("VK_KHR_").removePrefix("VK_EXT_").removeSuffix("_surface") } } catch (e: Throwable) { "?" }
            text.append("\nwindows it can draw into: ").append(surfaces.ifEmpty { "none" })
            if (vulkanSoftware) {
                val node = Advice.renderNodeProblem()
                problems.add(Problem("Vulkan runs on the processor only, slowly: " + (node ?: "no Vulkan driver for the graphics card"),
                    if (node == null) Advice.install(*Advice.vulkanDrivers.toTypedArray()) else null, false))
            }
            return text.toString()
        } catch (e: Throwable) {
            problems.add(Problem("Vulkan: " + Advice.vulkanReason(e) + " (the game is drawn with OpenGL ES instead)", Advice.vulkanFix(e), false))
            return "MISSING: " + Advice.vulkanReason(e) + (Advice.vulkanFix(e)?.let { "\nto have it: $it" } ?: "") + icdFiles()
        } finally { arena.close() }
    }

    /** The driver's own name and version (« Intel open-source Mesa driver, Mesa 26.3.1 », « V3DV Mesa, Mesa 25.0.7 »), when it says. */
    private fun driverName(vk: VkInstanceApi, device: MemorySegment, deviceVersion: Int, instanceVersion: Int): String? {
        val wanted = (1 shl 22) or (2 shl 12)
        if (instanceVersion < ((1 shl 22) or (1 shl 12)) || (deviceVersion < wanted && "VK_KHR_driver_properties" !in vk.deviceExtensionNames(device))) return null
        val f = VkLoader.instanceProc(vk.handle, "vkGetPhysicalDeviceProperties2")
        if (f == MemorySegment.NULL) return null
        return Arena.ofConfined().use { a ->
            val driver = a.allocate(536, 8)
            driver.set(JAVA_INT, 0, 1000196000)                         // VkPhysicalDeviceDriverProperties
            val props2 = a.allocate(16 + VkPhysicalDeviceProperties.size, 8)
            props2.set(JAVA_INT, 0, 1000059001)                         // VkPhysicalDeviceProperties2
            props2.set(ADDRESS, 8, driver)
            Sig.V_PP.invokeExact(f, device, props2)
            val name = driver.asSlice(20, 256).getString(0)
            val info = driver.asSlice(276, 256).getString(0)
            listOf(name, info).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
        }
    }

    /** The memory the card keeps for itself (on a Raspberry Pi or a laptop it is the main memory, shared). */
    private fun deviceMemory(vk: VkInstanceApi, device: MemorySegment, s: NativeStack): String {
        val mp = s.struct(VkPhysicalDeviceMemoryProperties); vk.getPhysicalDeviceMemoryProperties(device, mp)
        var local = 0L
        for (h in 0 until mp.u32(VkPhysicalDeviceMemoryProperties, "memoryHeapCount"))
            if (mp.u32(VkPhysicalDeviceMemoryProperties, "memoryHeaps[$h].flags") and 1 != 0) local = maxOf(local, mp.u64(VkPhysicalDeviceMemoryProperties, "memoryHeaps[$h].size"))
        return if (local > 0) ", " + gb(local) + " of memory" else ""
    }

    /** The Vulkan drivers installed, by their files (none: no driver package). */
    private fun icdFiles(): String {
        if (!Os.linux && !Os.bsd) return ""
        val names = listOf("/usr/share/vulkan/icd.d", "/etc/vulkan/icd.d", "/usr/local/share/vulkan/icd.d").flatMap { d ->
            try { Files.list(Path.of(d)).use { s -> s.map { it.fileName.toString() }.toList() } } catch (e: Throwable) { emptyList() } }
        return "\ndrivers installed: " + names.joinToString().ifEmpty { "none" } + (Advice.renderNodeProblem()?.let { "\n$it" } ?: "")
    }

    // ------------------------------------------------------------------ OpenGL ES

    private fun gles(): String {
        val gl = try { EglGl() } catch (e: Throwable) {
            // Windows has no OpenGL ES of its own: nothing to mend there.
            if (!Os.windows) problems.add(Problem("OpenGL ES: " + Advice.glReason(e) + " (the fallback when Vulkan fails)", Advice.glFix(e), false))
            return "MISSING: " + Advice.glReason(e) + (Advice.glFix(e)?.let { "\nto have it: $it" } ?: "")
        }
        try {
            glRenderer = gl.rendererName
            val client = try {
                val ext = Native.string(eglClientExtensions())
                listOfNotNull(if ("platform_wayland" in ext) "Wayland" else null, if ("platform_xcb" in ext || "platform_x11" in ext) "X11" else null).joinToString(", ")
            } catch (e: Throwable) { "?" }
            if (!gl.floatTargets) problems.add(Problem("OpenGL ES cannot draw into floating-point pictures: the OpenGL ES renderer will not start here", null, false))
            return gl.versionName + " on " + gl.rendererName + ", EGL " + gl.eglVersion + "\nfloating-point pictures " + (if (gl.floatTargets) "yes" else "NO") +
                ", " + gl.maxSamples + " samples at most, " + gl.kind + "; windows it can draw into: " + client.ifEmpty { "none" }
        } finally { gl.close() }
    }

    private fun eglClientExtensions(): MemorySegment {
        val lib = Native.library("libEGL.so.1", "libEGL.so")
        val h = java.lang.foreign.Linker.nativeLinker().downcallHandle(lib.fn("eglQueryString"), java.lang.foreign.FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
        return h.invokeExact(MemorySegment.NULL, 0x3055) as MemorySegment
    }

    // ------------------------------------------------------------------ sound

    private fun sound(): String {
        val device = option("--audio-device") ?: "default"
        val servers = listOfNotNull(System.getenv("XDG_RUNTIME_DIR")?.let { dir ->
            listOfNotNull(if (File("$dir/pipewire-0").exists()) "PipeWire" else null, if (File("$dir/pulse/native").exists()) "PulseAudio" else null).joinToString(" and ").ifEmpty { null }
        }).joinToString()
        val cards = read("/proc/asound/cards")?.lineSequence()?.filter { Regex("^\\s*\\d+ \\[").containsMatchIn(it) }
            ?.map { it.substringAfter("]:").substringAfter(" - ").trim() }?.toList()?.joinToString() ?: ""
        val opened = try {
            when {
                Os.linux && !device.startsWith("/dev/") -> sillage.audio.AlsaOutput.probe(device, 48_000, 40)
                else -> {
                    // Elsewhere the output is opened as the game would, and closed at once: only silence goes out.
                    val o = sillage.audio.platformAudioOutput(device, sillage.audio.Mixer(), 48_000, 40)
                    val d = o.device; val l = o.latency; o.close()
                    "\"$d\" opens, ${(l * 1000).toInt()} ms (closed at once, nothing was heard)"
                }
            }
        } catch (e: Throwable) {
            val why = Advice.soundReason(e)
            problems.add(Problem("no sound: $why (the game plays on without sound)", if (why.contains("libasound")) Advice.install(Advice.alsa) else null, false))
            "NONE: $why"
        }
        return opened + (if (servers.isNotEmpty()) "; sound server: $servers" else "") + (if (cards.isNotEmpty()) "\ncards: $cards" else "")
    }

    // ------------------------------------------------------------------ timings

    /** The game's scene without a window, its player walking and jumping, the sound mixed and thrown away. */
    private fun bench(): String {
        val software = vulkanSoftware || (vulkanDevice == null && glRenderer?.let { r -> listOf("llvmpipe", "softpipe", "swrast").any { it in r } } == true)
        val frames = option("--frames")?.toIntOrNull() ?: if (software) 120 else 300
        val size = option("--size") ?: if (software) "640x400" else "1280x800"
        // The player walks and jumps (the smoke run) when there are frames enough for it: from frame 20 to 110.
        val benchArgs = arrayListOf("--headless", "--mute", "--frames", frames.toString(), "--size", size)
        if (frames >= 120) benchArgs += "--smoke"
        option("--renderer")?.let { benchArgs += listOf("--renderer", it) }
        option("--quality")?.let { benchArgs += listOf("--quality", it) }
        // What the run prints (the renderer's first line) is kept aside, not mixed into the report.
        val said = ByteArrayOutputStream()
        val before = System.out
        val report: RunReport
        val started = System.nanoTime()
        try {
            System.setOut(PrintStream(said, true))
            report = Runtime(title, benchArgs.toTypedArray(), setup).run()
        } catch (e: Throwable) {
            System.setOut(before)
            val first = said.toString().lines().firstOrNull { it.startsWith("Sillage:") }
            problems.add(Problem("the game did not run without a window: " + (e.message ?: e.javaClass.simpleName).lineSequence().first(), null, true))
            return "FAILED: " + (e.message ?: e.toString()) + (first?.let { "\n$it" } ?: "")
        } finally { System.setOut(before) }
        val seconds = (System.nanoTime() - started) / 1e9
        val lines = said.toString().lines()
        val vk = lines.firstOrNull { it.startsWith("SILLAGE_VULKAN_OK") }
        val gl = lines.firstOrNull { it.startsWith("SILLAGE_GL_OK") }
        val fallback = lines.firstOrNull { it.startsWith("Sillage:") && "OpenGL ES instead" in it }
        fun field(l: String, name: String) = Regex("$name=(\\S+(?: \\S+)*?)(?= \\w+=|$)").find(l)?.groupValues?.get(1)
        val renderer = when {
            vk != null -> "Vulkan on " + field(vk, "GPU") + ", quality " + field(vk, "quality")?.lowercase() + ", " + field(vk, "msaa") + " samples, shadows " + field(vk, "shadows")
            gl != null -> "OpenGL ES on " + field(gl, "GPU") + ", " + field(gl, "msaa") + " samples"
            else -> "?"
        }
        val fps = if (report.meanMs > 0) 1000.0 / report.meanMs else 0.0
        if (report.meanMs > 16.7) problems.add(Problem("the game takes %.1f ms per frame without a window (more than 1/60 s): try --quality low".en(report.meanMs), null, false))
        return "$title, ${report.frames} frames of $size without a window, $renderer\n" +
            "%.2f ms per frame (99 %%: %.2f, worst %.1f), that is %.0f frames per second".en(report.meanMs, report.p99Ms, report.maxMs, fps) +
            (if (report.gpuMs > 0) "; GPU %.2f ms".en(report.gpuMs) else "") +
            "; %.1f s in all, the scene built and the renderer made".en(seconds) +
            (fallback?.let { "\n" + it.removePrefix("Sillage: ") } ?: "")
    }

    /** A heap of crates falling onto the ground, alone (no scene, no drawing). */
    private fun physics(): String {
        val w = PhysicsWorld()
        val g = 40f
        w.addMesh(TriangleMesh(floatArrayOf(-g, 0f, -g, -g, 0f, g, g, 0f, g, -g, 0f, -g, g, 0f, g, g, 0f, -g)))
        val rnd = java.util.Random(1)
        val crates = 1000
        var k = 0
        for (y in 0 until 10) for (x in 0 until 10) for (z in 0 until 10) {
            w.add(Box(.25f, .25f, .25f), Vec3(x * .55f - 2.5f + rnd.nextFloat() * .05f, .3f + y * .6f, z * .55f - 2.5f + rnd.nextFloat() * .05f)); k++
        }
        val steps = 240
        val times = DoubleArray(steps)
        for (i in 0 until steps) { val t = System.nanoTime(); w.step(1f / 60f); times[i] = (System.nanoTime() - t) / 1e6 }
        val settled = times.drop(30).sorted()
        val mean = settled.average()
        if (mean > 16.7) problems.add(Problem("the physics takes %.1f ms per step for %d crates: big heaps will slow the game".en(mean, crates), null, false))
        return "%d crates falling into a heap, %d steps of 1/60 s: %.2f ms per step (median %.2f, worst %.1f)".en(
            k, steps, mean, settled[settled.size / 2], settled.last())
    }

    // ------------------------------------------------------------------ what to do

    private fun verdict() {
        val problems = synchronized(this.problems) { this.problems.toList() }
        if (problems.isEmpty()) { line("Verdict", "everything Sillage needs is here."); return }
        val text = StringBuilder()
        for ((i, p) in problems.withIndex()) {
            if (i > 0) text.append('\n')
            text.append(if (p.blocking) "! " else "- ").append(p.what)
            p.fix?.let { text.append("\n    ").append(it) }
        }
        line("Verdict", (if (problems.any { it.blocking }) "the game cannot run here as it is:\n" else "the game runs; to make it better:\n") + text)
    }
}
