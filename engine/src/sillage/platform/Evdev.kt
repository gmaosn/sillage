package sillage.platform

import sillage.Pad
import sillage.PadConnection
import sillage.PadKind
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.nio.file.Files
import java.nio.file.Path

/** Linux input codes (linux/input-event-codes.h) used by the pads. */
internal object Ev {
    const val SYN = 0; const val KEY = 1; const val ABS = 3; const val FF = 0x15
    const val SYN_REPORT = 0; const val SYN_DROPPED = 3
    const val ABS_X = 0; const val ABS_Y = 1; const val ABS_Z = 2; const val ABS_RX = 3; const val ABS_RY = 4; const val ABS_RZ = 5
    const val ABS_GAS = 9; const val ABS_BRAKE = 10; const val ABS_HAT0X = 0x10; const val ABS_HAT0Y = 0x11
    const val BTN_SOUTH = 0x130; const val BTN_EAST = 0x131; const val BTN_C = 0x132; const val BTN_NORTH = 0x133; const val BTN_WEST = 0x134
    const val BTN_TL = 0x136; const val BTN_TR = 0x137; const val BTN_TL2 = 0x138; const val BTN_TR2 = 0x139
    const val BTN_SELECT = 0x13a; const val BTN_START = 0x13b; const val BTN_MODE = 0x13c; const val BTN_THUMBL = 0x13d; const val BTN_THUMBR = 0x13e
    const val BTN_DPAD_UP = 0x220; const val BTN_DPAD_DOWN = 0x221; const val BTN_DPAD_LEFT = 0x222; const val BTN_DPAD_RIGHT = 0x223
    const val KEY_RECORD = 167
    const val FF_RUMBLE = 0x50
    const val KEY_CODES = 0x300
    const val INPUT_EVENT_SIZE = 24      // struct input_event on 64-bit Linux
    const val FF_EFFECT_SIZE = 48
    fun eviocgabs(code: Int) = Posix.ior('E', 0x40 + code, 24)
    fun eviocgkey(len: Int) = Posix.ior('E', 0x18, len)
    val EVIOCSFF = Posix.iow('E', 0x80, FF_EFFECT_SIZE)
    val EVIOCGRAB = Posix.iow('E', 0x90, 4)
}

/**
 * How an evdev gamepad's codes map to the standard layout. Buttons by the Linux gamepad conventions
 * (BTN_SOUTH, BTN_EAST…, the shoulders, BTN_SELECT/START/MODE, the stick clicks, BTN_DPAD_*), sticks on
 * ABS_X/Y and ABS_RX/RY (or ABS_Z/RZ when the pad has no RX/RY), triggers on ABS_Z/RZ (or ABS_BRAKE/GAS),
 * the d-pad as buttons or as the ABS_HAT0 hat.
 *
 * One known trap, handled as SDL does: Xbox pads (xpad) send BTN_X (0x133) for their left button and
 * BTN_Y (0x134) for the top one, while Sony's and Nintendo's drivers send the same codes by position
 * (0x133 = BTN_NORTH, 0x134 = BTN_WEST).
 */
internal class EvdevLayout(keys: LongArray, abs: Long, val vendor: Int, val product: Int) {
    /** Button bit for each key code, −1 when not mapped. */
    val button = IntArray(Ev.KEY_CODES) { -1 }
    /** Role of each absolute axis ([LX]…), −1 when not used. */
    val role = IntArray(64) { -1 }
    val isGamepad: Boolean
    val digitalL2: Boolean
    val digitalR2: Boolean

    init {
        fun has(code: Int) = code / 64 < keys.size && (keys[code / 64] ushr (code % 64)) and 1L != 0L
        fun hasAbs(code: Int) = (abs ushr code) and 1L != 0L
        isGamepad = has(Ev.BTN_SOUTH) && hasAbs(Ev.ABS_X) && hasAbs(Ev.ABS_Y)
        val positional = vendor == 0x054C || vendor == 0x057E
        fun map(code: Int, b: Int) { if (has(code)) button[code] = b - Pad.BUTTON_BASE }
        map(Ev.BTN_SOUTH, Pad.SOUTH); map(Ev.BTN_EAST, Pad.EAST)
        map(Ev.BTN_NORTH, if (positional) Pad.NORTH else Pad.WEST); map(Ev.BTN_WEST, if (positional) Pad.WEST else Pad.NORTH)
        map(Ev.BTN_TL, Pad.L1); map(Ev.BTN_TR, Pad.R1); map(Ev.BTN_TL2, Pad.L2); map(Ev.BTN_TR2, Pad.R2)
        map(Ev.BTN_SELECT, Pad.SELECT); map(Ev.BTN_START, Pad.START); map(Ev.BTN_MODE, Pad.HOME)
        map(Ev.BTN_THUMBL, Pad.L3); map(Ev.BTN_THUMBR, Pad.R3)
        map(Ev.BTN_DPAD_UP, Pad.DPAD_UP); map(Ev.BTN_DPAD_DOWN, Pad.DPAD_DOWN); map(Ev.BTN_DPAD_LEFT, Pad.DPAD_LEFT); map(Ev.BTN_DPAD_RIGHT, Pad.DPAD_RIGHT)
        map(Ev.KEY_RECORD, Pad.MUTE)
        digitalL2 = has(Ev.BTN_TL2); digitalR2 = has(Ev.BTN_TR2)
        fun use(code: Int, r: Int) { if (hasAbs(code)) role[code] = r }
        use(Ev.ABS_X, LX); use(Ev.ABS_Y, LY)
        if (hasAbs(Ev.ABS_RX) && hasAbs(Ev.ABS_RY)) {
            use(Ev.ABS_RX, RX); use(Ev.ABS_RY, RY); use(Ev.ABS_Z, L2); use(Ev.ABS_RZ, R2)
        } else if (hasAbs(Ev.ABS_Z) && hasAbs(Ev.ABS_RZ)) {
            use(Ev.ABS_Z, RX); use(Ev.ABS_RZ, RY); use(Ev.ABS_BRAKE, L2); use(Ev.ABS_GAS, R2)
        }
        if (!has(Ev.BTN_DPAD_UP)) { use(Ev.ABS_HAT0X, HAT_X); use(Ev.ABS_HAT0Y, HAT_Y) }
    }

    fun hasRole(r: Int) = role.any { it == r }

    companion object {
        const val LX = 0; const val LY = 1; const val RX = 2; const val RY = 3; const val L2 = 4; const val R2 = 5; const val HAT_X = 6; const val HAT_Y = 7
    }
}

/** What sysfs says of an input device: name, ids, capabilities (readable by everyone, so nothing needs opening to find the pads). */
internal class EvdevCaps(val name: String, val bus: Int, val vendor: Int, val product: Int, val uniq: String, val phys: String,
                         keys: LongArray, abs: Long, val ffRumble: Boolean,
                         /** The HID device it belongs to, if any (a DualSense read through hidraw is skipped here). */ val hidDevice: Path?) {
    val layout = EvdevLayout(keys, abs, vendor, product)

    companion object {
        /** Bits from a sysfs bitmap: hexadecimal words of 64 bits, most significant first. */
        fun bits(text: String): LongArray {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.reversed()
            return LongArray(words.size) { java.lang.Long.parseUnsignedLong(words[it], 16) }
        }

        private val HID_NAME = Regex("[0-9A-Fa-f]{4}:[0-9A-Fa-f]{4}:[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]+")

        /** Reads /sys/class/input/eventN/device ([dir]); null if it cannot. */
        fun read(dir: Path): EvdevCaps? = runCatching {
            fun text(name: String) = runCatching { Files.readString(dir.resolve(name)).trim() }.getOrDefault("")
            fun hex(name: String) = text(name).ifEmpty { "0" }.toInt(16)
            val keys = bits(text("capabilities/key").ifEmpty { "0" })
            val abs = bits(text("capabilities/abs").ifEmpty { "0" }).firstOrNull() ?: 0L
            val ff = bits(text("capabilities/ff").ifEmpty { "0" })
            val rumble = ff.isNotEmpty() && Ev.FF_RUMBLE / 64 < ff.size && (ff[Ev.FF_RUMBLE / 64] ushr (Ev.FF_RUMBLE % 64)) and 1L != 0L
            val real = dir.toRealPath()
            val hid = real.parent?.takeIf { it.fileName?.toString() == "input" }?.parent?.takeIf { HID_NAME.matches(it.fileName.toString()) }
            EvdevCaps(text("name"), hex("id/bustype"), hex("id/vendor"), hex("id/product"), text("uniq"), text("phys"), keys, abs, rumble, hid)
        }.getOrNull()
    }
}

/**
 * A gamepad read through evdev: buttons, sticks, triggers, d-pad, and rumble through the kernel's force
 * feedback when the pad has it and the node is writable. Events are gathered until each SYN_REPORT, then
 * the whole state is handed over; after a SYN_DROPPED (the reader fell behind) the state is read again
 * from the kernel.
 */
internal class EvdevPad(owner: SystemPads, node: String, fd: Int, val caps: EvdevCaps, private val writable: Boolean) : HardwarePad(owner, node, fd) {
    private val layout = caps.layout
    private val min = IntArray(64)
    private val max = IntArray(64)
    private lateinit var events: MemorySegment
    private lateinit var absInfo: MemorySegment
    private lateinit var keyBits: MemorySegment
    private lateinit var effect: MemorySegment
    private lateinit var event: MemorySegment
    private var dropped = false
    private var effectId = -1
    private val canRumble = caps.ffRumble && writable
    /** Events read so far (tests). */
    @Volatile var eventCount = 0L
    /** True when the pad is taken for this reader alone ([SystemPads.grab]). */
    @Volatile var grabbed = false
        private set

    // Its address or serial when it has one (a Bluetooth pad, a DualSense); else what it is and where it is plugged in.
    override val deviceId: String get() = caps.uniq.lowercase().ifEmpty { "%04x:%04x:%s:%s".format(caps.vendor, caps.product, caps.name, caps.phys.ifEmpty { node }) }
    override val deviceName: String get() = caps.name
    override val deviceKind: PadKind get() = when {
        caps.vendor == DualSense.VENDOR && caps.product == DualSense.PRODUCT -> PadKind.DUALSENSE
        caps.vendor == DualSense.VENDOR && caps.product == DualSense.PRODUCT_EDGE -> PadKind.DUALSENSE_EDGE
        else -> PadKind.GENERIC
    }
    override val deviceConnection: PadConnection get() = when (caps.bus) { 3 -> PadConnection.USB; 5 -> PadConnection.BLUETOOTH; else -> PadConnection.OTHER }
    override val devicePath: String get() = node
    override val deviceFeatures: Int get() = if (canRumble) Pad.F_RUMBLE else 0

    override fun setUp(x: DeviceIo, arena: Arena) {
        if (owner.grab) grabbed = x.ioctl(fd, Ev.EVIOCGRAB, 1) == 0
        events = arena.allocate((Ev.INPUT_EVENT_SIZE * 64).toLong(), 8); absInfo = arena.allocate(24, 8)
        keyBits = arena.allocate(96, 8); effect = arena.allocate(Ev.FF_EFFECT_SIZE.toLong(), 8); event = arena.allocate(Ev.INPUT_EVENT_SIZE.toLong(), 8)
        for (code in 0 until 64) if (layout.role[code] >= 0) {
            val r = x.ioctl(fd, Ev.eviocgabs(code), absInfo)
            if (r < 0) throw IllegalStateException("EVIOCGABS($code): ${Posix.describe(-r)}")
            min[code] = absInfo.get(JAVA_INT, 4); max[code] = absInfo.get(JAVA_INT, 8)
            if (max[code] <= min[code]) max[code] = min[code] + 1
        }
        cur.axes[4] = if (layout.hasRole(EvdevLayout.L2)) 0f else -1f
        cur.axes[5] = if (layout.hasRole(EvdevLayout.R2)) 0f else -1f
        resync(x)
    }

    /** Reads the whole state from the kernel (at the start, and after events were dropped). */
    private fun resync(x: DeviceIo) {
        if (x.ioctl(fd, Ev.eviocgkey(96), keyBits) >= 0) {
            cur.buttons = cur.buttons and HAT_BITS
            for (code in 0 until Ev.KEY_CODES) {
                val b = layout.button[code]
                if (b >= 0 && (keyBits.get(JAVA_BYTE, (code / 8).toLong()).toInt() shr (code % 8)) and 1 != 0) cur.buttons = cur.buttons or (1 shl b)
            }
        }
        for (code in 0 until 64) if (layout.role[code] >= 0 && x.ioctl(fd, Ev.eviocgabs(code), absInfo) >= 0) axis(code, absInfo.get(JAVA_INT, 0))
    }

    override fun readReports(x: DeviceIo): Boolean {
        for (k in 0 until 16) {
            val n = x.read(fd, events, Ev.INPUT_EVENT_SIZE * 64)
            if (n == -Posix.EAGAIN) break
            if (n <= 0) { endedBecause = if (n == 0) "end of file" else Posix.describe(-n); return false }
            val count = n / Ev.INPUT_EVENT_SIZE
            for (i in 0 until count) {
                val at = (i * Ev.INPUT_EVENT_SIZE).toLong()
                val type = events.get(JAVA_SHORT, at + 16).toInt() and 0xFFFF
                val code = events.get(JAVA_SHORT, at + 18).toInt() and 0xFFFF
                val value = events.get(JAVA_INT, at + 20)
                handle(x, type, code, value)
            }
            eventCount += count
            if (count < 64) break
        }
        return true
    }

    private fun handle(x: DeviceIo, type: Int, code: Int, value: Int) {
        when (type) {
            Ev.SYN -> when (code) {
                Ev.SYN_REPORT -> { if (dropped) { dropped = false; resync(x) }; cur.reports++; publish() }
                Ev.SYN_DROPPED -> dropped = true
            }
            Ev.KEY -> if (!dropped && code < Ev.KEY_CODES) { val b = layout.button[code]; if (b >= 0) bit(b, value != 0) }
            Ev.ABS -> if (!dropped && code < 64) axis(code, value)
        }
    }

    private fun bit(b: Int, on: Boolean) { cur.buttons = if (on) cur.buttons or (1 shl b) else cur.buttons and (1 shl b).inv() }

    private fun axis(code: Int, value: Int) {
        val lo = min[code]; val hi = max[code]
        fun stick() = ((value - (lo + hi) / 2f) / ((hi - lo) / 2f)).coerceIn(-1f, 1f)
        fun trigger() = ((value - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
        when (layout.role[code]) {
            EvdevLayout.LX -> cur.axes[0] = stick()
            EvdevLayout.LY -> cur.axes[1] = -stick()
            EvdevLayout.RX -> cur.axes[2] = stick()
            EvdevLayout.RY -> cur.axes[3] = -stick()
            EvdevLayout.L2 -> { val v = trigger(); cur.axes[4] = v; if (!layout.digitalL2) bit(Pad.L2 - Pad.BUTTON_BASE, v > .25f) }
            EvdevLayout.R2 -> { val v = trigger(); cur.axes[5] = v; if (!layout.digitalR2) bit(Pad.R2 - Pad.BUTTON_BASE, v > .25f) }
            EvdevLayout.HAT_X -> { bit(Pad.DPAD_LEFT - Pad.BUTTON_BASE, value < 0); bit(Pad.DPAD_RIGHT - Pad.BUTTON_BASE, value > 0) }
            EvdevLayout.HAT_Y -> { bit(Pad.DPAD_UP - Pad.BUTTON_BASE, value < 0); bit(Pad.DPAD_DOWN - Pad.BUTTON_BASE, value > 0) }
        }
    }

    override fun sendOutput(x: DeviceIo, o: PadOutput, rumbleOn: Boolean) {
        if (!canRumble || o.touched and PadOutput.RUMBLE == 0) return
        if (rumbleOn) {
            // A rumble effect: strong (heavy motor) and weak (light motor) magnitudes, played once for its length.
            effect.fill(0)
            effect.set(JAVA_SHORT, 0, Ev.FF_RUMBLE.toShort()); effect.set(JAVA_SHORT, 2, effectId.toShort())
            val ms = ((o.rumbleUntil - System.nanoTime()) / 1_000_000L).coerceIn(1L, 0xFFFFL)
            effect.set(JAVA_SHORT, 10, ms.toInt().toShort())
            effect.set(JAVA_SHORT, 16, (o.rumbleLow * 0xFFFF).toInt().toShort()); effect.set(JAVA_SHORT, 18, (o.rumbleHigh * 0xFFFF).toInt().toShort())
            val r = x.ioctl(fd, Ev.EVIOCSFF, effect)
            if (r < 0) { owner.say("ff:$node", "the gamepad at $node refused its rumble: ${Posix.describe(-r)}"); return }
            effectId = effect.get(JAVA_SHORT, 2).toInt()
            play(x, 1)
        } else if (effectId >= 0) play(x, 0)
    }

    private fun play(x: DeviceIo, count: Int) {
        event.fill(0)
        event.set(JAVA_SHORT, 16, Ev.FF.toShort()); event.set(JAVA_SHORT, 18, effectId.toShort()); event.set(JAVA_INT, 20, count)
        x.write(fd, event, Ev.INPUT_EVENT_SIZE)
    }

    override fun shutDown(x: DeviceIo) { if (effectId >= 0) play(x, 0) }

    private companion object {
        // The d-pad's bits, kept when the buttons are read again (a hat is an axis, read separately).
        val HAT_BITS = (1 shl (Pad.DPAD_UP - Pad.BUTTON_BASE)) or (1 shl (Pad.DPAD_DOWN - Pad.BUTTON_BASE)) or
            (1 shl (Pad.DPAD_LEFT - Pad.BUTTON_BASE)) or (1 shl (Pad.DPAD_RIGHT - Pad.BUTTON_BASE))
    }
}
