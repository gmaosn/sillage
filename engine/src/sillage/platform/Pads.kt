package sillage.platform

import sillage.PadDevice
import sillage.PadSource
import sillage.platform.Posix.Companion.EACCES
import sillage.platform.Posix.Companion.EPERM
import sillage.platform.Posix.Companion.HANGUP
import sillage.platform.Posix.Companion.O_CLOEXEC
import sillage.platform.Posix.Companion.O_NONBLOCK
import sillage.platform.Posix.Companion.O_RDONLY
import sillage.platform.Posix.Companion.O_RDWR
import sillage.platform.Posix.Companion.READABLE
import sillage.platform.Posix.Companion.WOKEN
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path

/** The system calls a pad's thread makes: libc ([Posix]) in a game, an imitation in tests. Calls return −errno on failure. */
internal interface DeviceIo {
    fun open(path: String, flags: Int): Int
    fun close(fd: Int)
    fun read(fd: Int, buf: MemorySegment, max: Int): Int
    fun write(fd: Int, buf: MemorySegment, len: Int): Int
    fun ioctl(fd: Int, request: Long, arg: MemorySegment): Int
    fun ioctl(fd: Int, request: Long, arg: Int): Int
    /** [Posix.READABLE], [Posix.WOKEN], [Posix.HANGUP] bits; 0 on timeout. */
    fun waitReadable(fd: Int, wake: Int, timeoutMs: Int): Int
    fun eventfd(): Int
    fun signal(efd: Int)
    fun drain(efd: Int)
}

/** Makes a [DeviceIo] for one thread (libc's errno and scratch memory are per thread). */
internal fun interface DeviceIoFactory { fun create(arena: Arena): DeviceIo }

/**
 * A pad read by its own thread: it opens the device, reads its reports as they come and hands each new
 * state to the game thread through a [StateExchange] (no lock), and sends what the game wants (rumble,
 * lights) when the game changes it or when a rumble's time is up. When the device goes away (unplugged,
 * out of range) the thread ends and the pad leaves [SystemPads.devices].
 */
internal abstract class HardwarePad(protected val owner: SystemPads, val node: String, protected var fd: Int) : PadDevice() {
    private val exchange = StateExchange()
    private val lock = Any()
    private val pending = PadOutput()
    private var dirty = false
    private var wakeFd = -1
    @Volatile var running = true
    protected val current = PadOutput()
    private var rumbling = false
    var thread: Thread? = null
        private set
    /** Why the thread ended, for messages and tests. */
    @Volatile var endedBecause = ""
    /** True once the device is gone (nothing more is sent to it). */
    @Volatile var lost = false
        protected set

    /** The state being built by the reader (the device thread's only). */
    protected val cur = PadState()

    override fun read(into: PadState) { into.copyFrom(exchange.latest()) }

    override fun write(output: PadOutput) {
        synchronized(lock) { pending.copyFrom(output); dirty = true; if (wakeFd >= 0) owner.signal(wakeFd) }
    }

    /** Hands [cur] to the game thread. */
    protected fun publish() { exchange.writing().copyFrom(cur); exchange.publish() }

    fun start() { thread = Thread(::run, "sillage-pad " + node.substringAfterLast('/')).apply { isDaemon = true; start() } }

    /** Asks the thread to stop (it frees the triggers and motors first). */
    fun stop() { running = false; synchronized(lock) { if (wakeFd >= 0) owner.signal(wakeFd) } }

    private fun run() {
        Arena.ofConfined().use { arena ->
            val x = owner.io.create(arena)
            val w = x.eventfd()
            synchronized(lock) { wakeFd = w }
            try {
                setUp(x, arena)
                publish()
                owner.added(this)
                loop(x)
            } catch (e: Throwable) {
                endedBecause = "error: " + e
                owner.say("pad-error:$node", "the pad at $node stopped: $e")
            } finally {
                try { if (fd >= 0 && !lost) shutDown(x) } catch (_: Throwable) {}
                synchronized(lock) { wakeFd = -1 }
                x.close(w); x.close(fd); fd = -1
                owner.removed(this)
            }
        }
    }

    private fun loop(x: DeviceIo) {
        while (running) {
            val timeout = if (rumbling) ((current.rumbleUntil - System.nanoTime()) / 1_000_000L + 1).coerceIn(1L, 1000L).toInt() else 1000
            val r = x.waitReadable(fd, wakeFd, timeout)
            if (r < 0) { endedBecause = "poll: " + Posix.describe(-r); lost = true; return }
            if (r and WOKEN != 0) x.drain(wakeFd)
            if (r and READABLE != 0) { if (!readReports(x)) { lost = true; return } }
            else if (r and HANGUP != 0) { endedBecause = "device gone"; lost = true; return }
            flush(x)
        }
        endedBecause = "stopped"
    }

    /** Sends the game's wishes if they changed, and stops the motors when the rumble's time is up. */
    private fun flush(x: DeviceIo) {
        var send = false
        synchronized(lock) { if (dirty) { current.copyFrom(pending); dirty = false; send = true } }
        val on = current.touched and PadOutput.RUMBLE != 0 && System.nanoTime() < current.rumbleUntil && (current.rumbleLow > 0f || current.rumbleHigh > 0f)
        if (rumbling && !on) send = true
        if (send) { rumbling = on; sendOutput(x, current, on) }
    }

    /** Opens the conversation with the device (feature reports, ranges, first state into [cur]). */
    protected abstract fun setUp(x: DeviceIo, arena: Arena)
    /** Reads what is waiting; false when the device is gone ([endedBecause] says why). */
    protected abstract fun readReports(x: DeviceIo): Boolean
    protected abstract fun sendOutput(x: DeviceIo, o: PadOutput, rumbleOn: Boolean)
    /** Leaves the pad as the game found it: trigger effects off, motors stopped. */
    protected abstract fun shutDown(x: DeviceIo)
}

/**
 * The computer's pads, found and followed while the game runs. A thread watches /dev and /dev/input with
 * inotify (a node appears, disappears or gets its permissions from udev) and looks again a moment later;
 * pads seen leave at once, when their reads fail. DualSense pads are read through their hidraw node
 * (every feature), other gamepads through evdev; a DualSense's own evdev node is then skipped.
 *
 * Why inotify rather than looking every second: a pad plugged in is there a tenth of a second later,
 * nothing runs while nothing changes, and a node created and removed between two looks is not missed. If
 * inotify is not available, it looks every second.
 *
 * [sys], [dev], [io], [log], [accept] and [env] are for tests: a made-up sysfs and /dev tree, made-up
 * devices, and which devices count.
 */
internal class SystemPads(
    val sys: Path = Path.of("/sys"),
    val dev: Path = Path.of("/dev"),
    val io: DeviceIoFactory = DeviceIoFactory { Posix(it) },
    private val log: (String) -> Unit = { System.err.println("Sillage: $it") },
    private val accept: (name: String, vendor: Int, product: Int) -> Boolean = { _, _, _ -> true },
    private val env: (String) -> String? = System::getenv,
    private val hidraw: Boolean = true,
    private val evdev: Boolean = true,
    /** Delay after a change in /dev before looking (udev sets permissions just after the node appears). */
    private val settleMs: Long = 100,
    /**
     * Tests only: take evdev pads for ourselves (EVIOCGRAB), so that the events of a virtual pad reach no
     * other program (Steam adopts every new pad into the running game's controller settings).
     */
    val grab: Boolean = false,
) : PadSource {
    @Volatile override var devices: List<PadDevice> = emptyList()
        private set
    private val live = ArrayList<HardwarePad>()             // started: setting up or published
    private val shown = ArrayList<PadDevice>()
    private val claimed = HashSet<Path>()                    // HID devices read through hidraw: their evdev nodes are skipped
    private val denied = HashSet<Path>()                     // DualSense HID devices whose hidraw node is closed to us
    private val retryAfter = HashMap<String, Long>()
    private val said = HashSet<String>()
    /** Every message printed, for the pad test's summary. */
    val notes = ArrayList<String>()
    private val wakeArena = Arena.ofShared()
    private val real = Posix(wakeArena)                      // the watcher's own wake-up (it polls inotify through libc)
    private val devSignal = io.create(wakeArena)             // the pads' wake-ups, through their kind of IO
    private val hotplugWake: Int = real.eventfd()
    @Volatile private var running = true
    @Volatile private var rescanNow = false
    /** Number of looks at the devices so far (tests wait on it). */
    @Volatile var scans = 0
        private set
    val thread: Thread = Thread(::watch, "sillage-pads").apply { isDaemon = true; start() }

    /** Signals an eventfd from any thread (the game's, a pad's). */
    fun signal(efd: Int) { synchronized(devSignal) { devSignal.signal(efd) } }

    private fun wakeWatcher() { synchronized(real) { real.signal(hotplugWake) } }

    fun say(key: String, message: String) {
        synchronized(said) { if (!said.add(key)) return; notes.add(message) }
        log(message)
    }

    fun added(p: HardwarePad) = synchronized(this) { if (p in live && p.running) { shown.add(p); devices = shown.toList() } }

    fun removed(p: HardwarePad) {
        synchronized(this) {
            live.remove(p)
            if (shown.remove(p)) devices = shown.toList()
            if (p is HidrawDualSense) claimed.remove(p.hidDevice)
            if (running) retryAfter[p.node] = System.currentTimeMillis() + 1000
        }
        if (running) { rescanNow = true; wakeWatcher() }
    }

    override fun close() {
        if (!running) return
        running = false
        wakeWatcher()
        thread.join(2000)
        val all = synchronized(this) { live.toList() }
        for (p in all) p.stop()
        for (p in all) p.thread?.join(1000)
        synchronized(real) { real.close(hotplugWake) }
        wakeArena.close()
    }

    private fun watch() {
        Arena.ofConfined().use { arena ->
            val x = io.create(arena)
            val posix = Posix(arena)
            val notify = posix.inotifyInit()
            var watching = notify >= 0
            if (watching) {
                val mask = Posix.IN_CREATE or Posix.IN_DELETE or Posix.IN_ATTRIB or Posix.IN_MOVED_TO
                val a = if (evdev) posix.inotifyWatch(notify, dev.resolve("input").toString(), mask) else 0
                val b = if (hidraw) posix.inotifyWatch(notify, dev.toString(), mask) else 0
                watching = a >= 0 || b >= 0
            }
            val buf = arena.allocate(4096)
            try {
                var due = 0L                                   // when to look next (0: now)
                while (running) {
                    if (System.currentTimeMillis() >= due || rescanNow) {
                        rescanNow = false
                        scan(x)
                        due = if (watching) Long.MAX_VALUE else System.currentTimeMillis() + 1000
                        // A node that could not be opened yet is tried again soon (udev may still be at work).
                        synchronized(this) { minOf(retryAfter.values.minOrNull() ?: Long.MAX_VALUE, recheckAt) }.let { due = minOf(due, it) }
                    }
                    val wait = (due - System.currentTimeMillis()).coerceIn(1, 1000).toInt()
                    val r = posix.waitReadable(if (watching) notify else -1, hotplugWake, wait)
                    if (r and WOKEN != 0) posix.drain(hotplugWake)
                    if (r and READABLE != 0) {
                        while (posix.read(notify, buf, 4096) > 0) {}
                        due = minOf(due, System.currentTimeMillis() + settleMs)
                    }
                }
            } finally { posix.close(notify) }
        }
    }

    /**
     * A node closed to us right after it appeared may only be waiting for udev: say so only if it is still
     * closed [DENIED_FOR] ms later (the watcher looks again then).
     */
    private val deniedAt = HashMap<String, Long>()
    private var recheckAt = Long.MAX_VALUE

    private fun stillDenied(node: String, now: Long): Boolean {
        val first = deniedAt.getOrPut(node) { now }
        if (now - first >= DENIED_FOR) return true
        recheckAt = minOf(recheckAt, first + DENIED_FOR + 50)
        return false
    }

    /** Looks at the device nodes and opens the pads not open yet. */
    internal fun scan(x: DeviceIo) {
        synchronized(this) {
            val now = System.currentTimeMillis()
            recheckAt = Long.MAX_VALUE
            retryAfter.entries.removeIf { it.value <= now }
            val open = live.mapTo(HashSet()) { it.node }
            if (hidraw) for (entry in list(sys.resolve("class/hidraw"))) {
                val name = entry.fileName.toString()
                val node = dev.resolve(name).toString()
                if (node in open || node in retryAfter) continue
                val info = HidInfo.read(entry.resolve("device")) ?: continue
                if (!DualSense.isDualSense(info.vendor, info.product) || !accept(info.name, info.vendor, info.product) || ignored(info.vendor, info.product)) continue
                val fd = x.open(node, O_RDWR or O_NONBLOCK or O_CLOEXEC)
                if (fd < 0) {
                    if (fd == -EACCES || fd == -EPERM) {
                        if (stillDenied(node, now)) {
                            denied.add(info.device)
                            say("hidraw-denied", "the DualSense at $node cannot be opened (${Posix.describe(-fd)}), so it is read through evdev: " +
                                "buttons, sticks, triggers and rumble work, but not the light bar, player LEDs, adaptive triggers, motion sensors, touchpad or battery. " +
                                "Udev rules giving your user access to it bring them back (Steam's 60-steam-input.rules does).")
                        }
                    } else if (fd != -Posix.ENOENT) {                     // not there yet: inotify will say when it is
                        say("hidraw-fail:$node", "the DualSense at $node cannot be opened: ${Posix.describe(-fd)}")
                        retryAfter[node] = now + 2000
                    }
                    continue
                }
                deniedAt.remove(node); denied.remove(info.device); claimed.add(info.device)
                // Read through evdev until now (its hidraw node opened late): hidraw takes over, the slot stays.
                for (p in live) if (p is EvdevPad && p.caps.hidDevice == info.device) p.stop()
                val p = HidrawDualSense(this, node, fd, info)
                live.add(p); p.start()
            }
            if (evdev) for (entry in list(sys.resolve("class/input"))) {
                val name = entry.fileName.toString()
                if (!name.startsWith("event")) continue
                val node = dev.resolve("input").resolve(name).toString()
                if (node in open || node in retryAfter) continue
                val caps = EvdevCaps.read(entry.resolve("device")) ?: continue
                if (!caps.layout.isGamepad || !accept(caps.name, caps.vendor, caps.product) || ignored(caps.vendor, caps.product)) continue
                val hid = caps.hidDevice
                if (hid != null && hid in claimed) continue                                   // a DualSense read through hidraw
                // A DualSense whose hidraw node may still open (udev at work): wait for it rather than take evdev.
                if (hidraw && hid != null && DualSense.isDualSense(caps.vendor, caps.product) && hid !in denied && hasHidraw(hid)) continue
                var fd = x.open(node, O_RDWR or O_NONBLOCK or O_CLOEXEC)
                var writable = true
                if (fd == -EACCES || fd == -EPERM) { fd = x.open(node, O_RDONLY or O_NONBLOCK or O_CLOEXEC); writable = false }
                if (fd < 0) {
                    if (fd == -EACCES || fd == -EPERM) { if (stillDenied(node, now)) say("evdev-denied:$node", "the gamepad « ${caps.name} » at $node cannot be read (${Posix.describe(-fd)})") }
                    else if (fd != -Posix.ENOENT) retryAfter[node] = now + 2000
                    continue
                }
                deniedAt.remove(node)
                if (!writable) say("evdev-readonly:$node", "the gamepad « ${caps.name} » at $node is read-only for this user: no rumble")
                val p = EvdevPad(this, node, fd, caps, writable)
                live.add(p); p.start()
            }
            deniedAt.keys.retainAll { Files.exists(Path.of(it)) }
            scans++
        }
    }

    /** True if [hid] has a hidraw node in sysfs. */
    private fun hasHidraw(hid: Path) = list(sys.resolve("class/hidraw")).any { runCatching { it.resolve("device").toRealPath() == hid }.getOrDefault(false) }

    private fun list(dir: Path): List<Path> = runCatching {
        Files.list(dir).use { s -> s.toList().sortedWith(compareBy({ it.fileName.toString().length }, { it.fileName.toString() })) }
    }.getOrDefault(emptyList())

    /**
     * Devices left out, as SDL does, so that a game started by Steam with Steam Input sees Steam's virtual
     * pads and not the real ones twice: SDL_GAMECONTROLLER_IGNORE_DEVICES (and _EXCEPT) list them as
     * 0xVVVV/0xPPPP; Steam's virtual pads (28de:11ff) only count when Steam set that list.
     */
    private fun ignored(vendor: Int, product: Int): Boolean {
        fun listed(list: String) = list.split(',').any { e ->
            val (v, p) = e.trim().split('/').let { it.getOrNull(0) to it.getOrNull(1) }
            fun hex(s: String?) = s?.trim()?.removePrefix("0x")?.removePrefix("0X")?.toIntOrNull(16)
            hex(v) == vendor && (hex(p) == product || hex(p) == 0xFFFF)
        }
        val except = env("SDL_GAMECONTROLLER_IGNORE_DEVICES_EXCEPT")
        if (!except.isNullOrBlank()) return !listed(except)
        val ignore = env("SDL_GAMECONTROLLER_IGNORE_DEVICES")
        if (vendor == 0x28DE && product == 0x11FF) return ignore.isNullOrBlank()
        return !ignore.isNullOrBlank() && listed(ignore)
    }

    private companion object { const val DENIED_FOR = 1500L }
}
