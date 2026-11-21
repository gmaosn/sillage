package sillage.platform

import sillage.Pad
import sillage.PadConnection
import sillage.PadKind
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.nio.file.Files
import java.nio.file.Path

/** A HID device as sysfs describes it (its `uevent` file): bus, vendor, product, name, address, driver. */
internal class HidInfo(val bus: Int, val vendor: Int, val product: Int, val name: String, val uniq: String, val phys: String,
                       val driver: String, /** The HID device's directory in sysfs (symbolic links resolved). */ val device: Path) {
    companion object {
        /** Reads the HID device behind [dir] (a hidraw node's `device` link); null if it is not one. */
        fun read(dir: Path): HidInfo? = runCatching {
            val fields = Files.readAllLines(dir.resolve("uevent")).associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val id = fields["HID_ID"]?.split(':') ?: return null
            if (id.size != 3) return null
            HidInfo(id[0].toInt(16), id[1].toLong(16).toInt(), id[2].toLong(16).toInt(), fields["HID_NAME"] ?: "", fields["HID_UNIQ"] ?: "",
                fields["HID_PHYS"] ?: "", fields["DRIVER"] ?: "", dir.toRealPath())
        }.getOrNull()
    }
}

/**
 * A DualSense (or DualSense Edge) read through its hidraw node, over USB or Bluetooth: every button and
 * axis, the motion sensors with the pad's own calibration, the touchpad, the battery, and the outputs
 * (light bar, player LEDs, mute light, rumble, adaptive triggers).
 *
 * The kernel's hid-playstation driver usually holds the same pad and sends its own output reports (its
 * evdev rumble, its LEDs). Like Steam beside it, this reader only sends the parts the game used (each
 * output report says which parts it carries), so the two do not undo each other's settings, and reads the
 * same input reports the driver reads.
 */
internal class HidrawDualSense(owner: SystemPads, node: String, fd: Int, val info: HidInfo) : HardwarePad(owner, node, fd) {
    val hidDevice: Path get() = info.device
    private val bluetooth = info.bus == DualSense.BUS_BLUETOOTH
    private val edge = info.product == DualSense.PRODUCT_EDGE
    internal val calibration = DualSense.Calibration()
    internal var vibrationV2 = edge
        private set
    internal var firmware = -1
        private set
    private var address: String? = null
    private lateinit var inBuf: MemorySegment
    private lateinit var outSeg: MemorySegment
    private lateinit var featSeg: MemorySegment
    private val report = ByteArray(128)
    private val outBytes = ByteArray(DualSense.OUTPUT_BT_SIZE)
    private var seq = 0
    // hid-playstation fades out the start-up animation itself; without it, the first light bar order does.
    private var lightOutDone = info.driver == "playstation"
    private val stamp = LongArray(1)
    private var lastStamp = -1L
    private val none = PadOutput()
    /** Reports dropped for a wrong CRC (Bluetooth), and output reports that could not be written. */
    @Volatile var badReports = 0L
    @Volatile var writeErrors = 0L

    override val deviceId: String get() = address ?: info.uniq.lowercase().ifEmpty { node }
    override val deviceName: String get() = info.name.ifEmpty { "DualSense" }
    override val deviceKind: PadKind get() = if (edge) PadKind.DUALSENSE_EDGE else PadKind.DUALSENSE
    override val deviceConnection: PadConnection get() = when (info.bus) {
        DualSense.BUS_BLUETOOTH -> PadConnection.BLUETOOTH; DualSense.BUS_USB -> PadConnection.USB; else -> PadConnection.OTHER }
    override val devicePath: String get() = node
    override val deviceFeatures: Int get() = Pad.F_ALL

    override fun setUp(x: DeviceIo, arena: Arena) {
        inBuf = arena.allocate(128, 8); outSeg = arena.allocate(128, 8); featSeg = arena.allocate(64, 8)
        // The calibration (reading it also switches a Bluetooth pad to its full reports), the firmware, the address.
        val c = feature(x, DualSense.FEATURE_CALIBRATION, DualSense.FEATURE_CALIBRATION_SIZE)
        val calOk = c >= 35 && (!bluetooth || c < DualSense.FEATURE_CALIBRATION_SIZE || DualSense.featureCrcOk(report, DualSense.FEATURE_CALIBRATION_SIZE)) &&
            calibration.read(report, c)
        if (!calOk) owner.say("calibration:$node", "the DualSense at $node gave no usable motion calibration " +
            (if (c < 0) "(${Posix.describe(-c)})" else "") + ": default scales are used for its gyroscope and accelerometer")
        val f = feature(x, DualSense.FEATURE_FIRMWARE, DualSense.FEATURE_FIRMWARE_SIZE)
        if (f > 0) { firmware = DualSense.updateVersion(report, f); if (firmware >= DualSense.VIBRATION_V2_FROM) vibrationV2 = true }
        val p = feature(x, DualSense.FEATURE_PAIRING, DualSense.FEATURE_PAIRING_SIZE)
        if (p > 0) address = DualSense.address(report, p)
        cur.axes[4] = 0f; cur.axes[5] = 0f
    }

    /** Reads feature report [id] into [report]; its length, or −errno. */
    private fun feature(x: DeviceIo, id: Int, size: Int): Int {
        featSeg.fill(0); featSeg.set(JAVA_BYTE, 0, id.toByte())
        val r = x.ioctl(fd, Posix.iowr('H', 0x07, size), featSeg)
        if (r > 0) MemorySegment.copy(featSeg, JAVA_BYTE, 0, report, 0, minOf(r, size))
        return r
    }

    override fun readReports(x: DeviceIo): Boolean {
        var got = false
        for (k in 0 until 32) {
            val n = x.read(fd, inBuf, 128)
            if (n == -Posix.EAGAIN) break
            if (n <= 0) { endedBecause = if (n == 0) "end of file" else Posix.describe(-n); return false }
            MemorySegment.copy(inBuf, JAVA_BYTE, 0, report, 0, n)
            when (DualSense.parseInput(report, n, calibration, cur, edge, stamp)) {
                DualSense.PARSED -> { integrate(); got = true }
                DualSense.BAD_CRC -> badReports++
            }
        }
        if (got) publish()
        return true
    }

    /** Adds this report's turn to the angles, over the time the sensors measured (their clock counts thirds of a microsecond). */
    private fun integrate() {
        val ts = stamp[0]
        if (ts < 0) return
        if (lastStamp >= 0) {
            val seconds = ((ts - lastStamp) and 0xFFFFFFFFL) / 3e6
            if (seconds > 0 && seconds < .1) for (i in 0 until 3) cur.turn[i] += cur.gyro[i] * seconds
            cur.turnValid = true
        }
        lastStamp = ts
    }

    override fun sendOutput(x: DeviceIo, o: PadOutput, rumbleOn: Boolean) {
        if (!lightOutDone && o.touched and PadOutput.LIGHTBAR != 0) { send(x, none, false, true); lightOutDone = true }
        send(x, o, rumbleOn, false)
    }

    private fun send(x: DeviceIo, o: PadOutput, rumbleOn: Boolean, lightOut: Boolean) {
        val n = DualSense.output(o, rumbleOn, bluetooth, seq, vibrationV2, lightOut, outBytes)
        seq = (seq + 1) and 0x0F
        MemorySegment.copy(outBytes, 0, outSeg, JAVA_BYTE, 0, n)
        val r = x.write(fd, outSeg, n)
        if (r < 0) { writeErrors++; owner.say("write:$node", "cannot send to the DualSense at $node: ${Posix.describe(-r)}") }
    }

    override fun shutDown(x: DeviceIo) {
        val used = current.touched and (PadOutput.L2 or PadOutput.R2 or PadOutput.RUMBLE)
        if (used == 0) return
        val o = PadOutput(); o.touched = used
        send(x, o, false, false)
    }
}
