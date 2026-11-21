package sillage.platform

import sillage.BatteryState
import sillage.Pad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/** CRC-32 (IEEE 802.3, reflected, as zlib computes it), for the DualSense's Bluetooth reports. */
internal object Crc32 {
    private val table = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            t[n] = c
        }
    }

    /** Continues a CRC ([crc] is the running value, before the final inversion; start from −1). */
    fun update(crc: Int, data: ByteArray, from: Int, length: Int): Int {
        var c = crc
        for (i in from until from + length) c = table[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        return c
    }

    fun update(crc: Int, b: Int): Int = table[(crc xor b) and 0xFF] xor (crc ushr 8)

    /** The CRC-32 of [length] bytes of [data] from [from]. */
    fun of(data: ByteArray, from: Int = 0, length: Int = data.size - from): Int = update(-1, data, from, length).inv()

    /** The CRC-32 of the byte [seed] followed by the bytes: how a DualSense checks its Bluetooth reports. */
    fun seeded(seed: Int, data: ByteArray, from: Int, length: Int): Int = update(update(-1, seed), data, from, length).inv()
}

/**
 * The DualSense's own protocol, as Linux's hid-playstation driver and SDL speak it: input reports (USB
 * 0x01, Bluetooth 0x31 with a CRC, the short Bluetooth 0x01 before the full mode), the motion sensors'
 * calibration (feature report 0x05) and output reports (lights, rumble, adaptive triggers).
 */
internal object DualSense {
    const val VENDOR = 0x054C
    const val PRODUCT = 0x0CE6
    const val PRODUCT_EDGE = 0x0DF2
    const val BUS_USB = 3
    const val BUS_BLUETOOTH = 5

    const val INPUT_USB = 0x01
    const val INPUT_USB_SIZE = 64
    const val INPUT_BT = 0x31
    const val INPUT_BT_SIZE = 78
    const val INPUT_BT_SIMPLE_SIZE = 10
    const val OUTPUT_USB = 0x02
    const val OUTPUT_USB_SIZE = 63
    const val OUTPUT_BT = 0x31
    const val OUTPUT_BT_SIZE = 78
    const val FEATURE_CALIBRATION = 0x05
    const val FEATURE_CALIBRATION_SIZE = 41
    const val FEATURE_PAIRING = 0x09
    const val FEATURE_PAIRING_SIZE = 20
    const val FEATURE_FIRMWARE = 0x20
    const val FEATURE_FIRMWARE_SIZE = 64
    const val SEED_INPUT = 0xA1
    const val SEED_OUTPUT = 0xA2
    const val SEED_FEATURE = 0xA3

    const val TOUCH_WIDTH = 1920
    const val TOUCH_HEIGHT = 1080
    private const val G = 9.80665f
    private const val DEG = (PI / 180).toFloat()

    fun isDualSense(vendor: Int, product: Int) = vendor == VENDOR && (product == PRODUCT || product == PRODUCT_EDGE)

    /**
     * Gyroscope and accelerometer scales from feature report 0x05: rad/s per unit for the three gyro axes,
     * m/s² per unit and the zero for the accelerometer's. Without a valid report, the kernel's defaults
     * (±2000 °/s and 8192 units per g over the 16-bit range).
     */
    class Calibration {
        val gyroScale = FloatArray(3) { 2048f / 32767f * DEG }
        val accelScale = FloatArray(3) { G / 8192f }
        val accelBias = IntArray(3)
        /** False when the report was missing or made no sense: the defaults are used. */
        var fromDevice = false
            private set

        /**
         * Reads [buf] (the feature report, report id first). Like the kernel driver, the gyro's bias is
         * not subtracted (the pad already does), only its sensitivity is used. Returns false (keeping the
         * defaults) if a denominator is zero.
         */
        fun read(buf: ByteArray, length: Int): Boolean {
            if (length < 35 || (buf[0].toInt() and 0xFF) != FEATURE_CALIBRATION) return false
            fun s16(i: Int) = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            val pitchBias = s16(1); val yawBias = s16(3); val rollBias = s16(5)
            val pitchPlus = s16(7); val pitchMinus = s16(9); val yawPlus = s16(11); val yawMinus = s16(13)
            val rollPlus = s16(15); val rollMinus = s16(17); val speedPlus = s16(19); val speedMinus = s16(21)
            val accXPlus = s16(23); val accXMinus = s16(25); val accYPlus = s16(27); val accYMinus = s16(29)
            val accZPlus = s16(31); val accZMinus = s16(33)
            val speed2x = speedPlus + speedMinus
            val gyroDenoms = intArrayOf(abs(pitchPlus - pitchBias) + abs(pitchMinus - pitchBias),
                abs(yawPlus - yawBias) + abs(yawMinus - yawBias), abs(rollPlus - rollBias) + abs(rollMinus - rollBias))
            val ranges = intArrayOf(accXPlus - accXMinus, accYPlus - accYMinus, accZPlus - accZMinus)
            if (gyroDenoms.any { it == 0 } || ranges.any { it == 0 } || speed2x == 0) return false
            for (i in 0 until 3) {
                // °/s = raw × speed2x / denominator (the kernel's sens_numer / sens_denom, without its 1024 units).
                gyroScale[i] = speed2x.toFloat() / gyroDenoms[i] * DEG
                // g = (raw − bias) × 2 / range, the bias being the middle of the ±1 g readings.
                accelBias[i] = when (i) { 0 -> accXPlus; 1 -> accYPlus; else -> accZPlus } - ranges[i] / 2
                accelScale[i] = 2f / ranges[i] * G
            }
            fromDevice = true
            return true
        }
    }

    /** What was read from a report, besides the state. */
    const val PARSED = 1
    const val IGNORED = 0
    const val BAD_CRC = -1

    /**
     * Parses an input report of [length] bytes into [into] (every field it carries). Returns [PARSED],
     * [IGNORED] (another report) or [BAD_CRC]. [timestamp] receives the sensors' clock (in 1/3 µs) or −1.
     */
    fun parseInput(buf: ByteArray, length: Int, cal: Calibration, into: PadState, edge: Boolean, timestamp: LongArray? = null): Int {
        if (length < 1) return IGNORED
        val id = buf[0].toInt() and 0xFF
        val at = when {
            id == INPUT_USB && length >= INPUT_USB_SIZE -> 1
            id == INPUT_BT && length >= INPUT_BT_SIZE -> {
                val crc = (buf[74].toInt() and 0xFF) or ((buf[75].toInt() and 0xFF) shl 8) or ((buf[76].toInt() and 0xFF) shl 16) or ((buf[77].toInt() and 0xFF) shl 24)
                if (Crc32.seeded(SEED_INPUT, buf, 0, 74) != crc) return BAD_CRC
                2
            }
            id == INPUT_USB && length >= INPUT_BT_SIMPLE_SIZE -> { parseSimple(buf, into); timestamp?.set(0, -1L); return PARSED }
            else -> return IGNORED
        }
        fun u8(i: Int) = buf[at + i].toInt() and 0xFF
        fun s16(i: Int) = ((buf[at + i].toInt() and 0xFF) or (buf[at + i + 1].toInt() shl 8)).toShort().toInt()
        sticks(u8(0), u8(1), u8(2), u8(3), into)
        into.axes[4] = u8(4) / 255f; into.axes[5] = u8(5) / 255f
        into.buttons = buttons(u8(7), u8(8), u8(9), edge)
        for (i in 0 until 3) {
            into.gyro[i] = s16(15 + 2 * i) * cal.gyroScale[i]
            into.accel[i] = (s16(21 + 2 * i) - cal.accelBias[i]) * cal.accelScale[i]
        }
        timestamp?.set(0, (u8(27).toLong() or (u8(28).toLong() shl 8) or (u8(29).toLong() shl 16) or (u8(30).toLong() shl 24)))
        for (f in 0 until 2) {
            val p = 32 + 4 * f
            val contact = u8(p)
            into.touchActive[f] = contact and 0x80 == 0
            into.touchId[f] = contact and 0x7F
            val x = u8(p + 1) or ((u8(p + 2) and 0x0F) shl 8)
            val y = (u8(p + 2) shr 4) or (u8(p + 3) shl 4)
            into.touchX[f] = (x / (TOUCH_WIDTH - 1f)).coerceIn(0f, 1f)
            into.touchY[f] = (y / (TOUCH_HEIGHT - 1f)).coerceIn(0f, 1f)
        }
        battery(u8(52), into)
        val plugs = u8(53)
        into.headphones = plugs and 0x01 != 0
        into.microphone = plugs and 0x02 != 0
        into.reports++
        return PARSED
    }

    /** The short Bluetooth report a DualSense sends until the host reads its calibration: sticks, buttons, triggers. */
    private fun parseSimple(buf: ByteArray, into: PadState) {
        fun u8(i: Int) = buf[i].toInt() and 0xFF
        sticks(u8(1), u8(2), u8(3), u8(4), into)
        into.buttons = buttons(u8(5), u8(6), u8(7) and 0x03, false)
        into.axes[4] = u8(8) / 255f; into.axes[5] = u8(9) / 255f
        into.reports++
    }

    private fun sticks(lx: Int, ly: Int, rx: Int, ry: Int, into: PadState) {
        fun axis(v: Int) = ((v - 128) / 127f).coerceIn(-1f, 1f)
        into.axes[0] = axis(lx); into.axes[1] = -axis(ly); into.axes[2] = axis(rx); into.axes[3] = -axis(ry)
    }

    /** The standard button bits from the DualSense's three button bytes (the first holds the d-pad's hat). */
    fun buttons(b0: Int, b1: Int, b2: Int, edge: Boolean): Int {
        var r = 0
        fun on(cond: Boolean, code: Int) { if (cond) r = r or (1 shl (code - Pad.BUTTON_BASE)) }
        on(b0 and 0x10 != 0, Pad.SQUARE); on(b0 and 0x20 != 0, Pad.CROSS); on(b0 and 0x40 != 0, Pad.CIRCLE); on(b0 and 0x80 != 0, Pad.TRIANGLE)
        val hat = b0 and 0x0F
        on(hat == 7 || hat == 0 || hat == 1, Pad.DPAD_UP); on(hat in 1..3, Pad.DPAD_RIGHT)
        on(hat in 3..5, Pad.DPAD_DOWN); on(hat in 5..7, Pad.DPAD_LEFT)
        on(b1 and 0x01 != 0, Pad.L1); on(b1 and 0x02 != 0, Pad.R1); on(b1 and 0x04 != 0, Pad.L2); on(b1 and 0x08 != 0, Pad.R2)
        on(b1 and 0x10 != 0, Pad.CREATE); on(b1 and 0x20 != 0, Pad.OPTIONS); on(b1 and 0x40 != 0, Pad.L3); on(b1 and 0x80 != 0, Pad.R3)
        on(b2 and 0x01 != 0, Pad.PS); on(b2 and 0x02 != 0, Pad.TOUCHPAD); on(b2 and 0x04 != 0, Pad.MUTE)
        if (edge) {
            on(b2 and 0x10 != 0, Pad.FN_LEFT); on(b2 and 0x20 != 0, Pad.FN_RIGHT)
            on(b2 and 0x40 != 0, Pad.PADDLE_LEFT); on(b2 and 0x80 != 0, Pad.PADDLE_RIGHT)
        }
        return r
    }

    /** The status byte: level in the low nibble (tenths), charging state in the high one (as the kernel reads it). */
    fun battery(status: Int, into: PadState) {
        val level = status and 0x0F
        when (status shr 4) {
            0x0 -> { into.battery = (level * 10 + 5).coerceAtMost(100) / 100f; into.batteryState = BatteryState.DISCHARGING.ordinal }
            0x1 -> { into.battery = (level * 10 + 5).coerceAtMost(100) / 100f; into.batteryState = BatteryState.CHARGING.ordinal }
            0x2 -> { into.battery = 1f; into.batteryState = BatteryState.FULL.ordinal }
            0xA, 0xB -> { into.battery = 0f; into.batteryState = BatteryState.ERROR.ordinal }
            else -> { into.battery = -1f; into.batteryState = BatteryState.UNKNOWN.ordinal }
        }
    }

    // Output report: offsets in the common part (hid-playstation's dualsense_output_report_common, SDL's DS5EffectsState_t).
    private const val FLAG0 = 0; private const val FLAG1 = 1; private const val MOTOR_RIGHT = 2; private const val MOTOR_LEFT = 3
    private const val MUTE_LED = 8; private const val RIGHT_TRIGGER = 10; private const val LEFT_TRIGGER = 21
    private const val FLAG2 = 38; private const val LIGHTBAR_SETUP = 41; private const val PLAYER_LEDS = 43
    private const val RED = 44; private const val GREEN = 45; private const val BLUE = 46
    const val COMMON_SIZE = 47

    /**
     * Writes an output report into [into] and returns its length: only the parts the game used
     * ([PadOutput.touched]) carry their « valid » flags, so the rest of the pad's settings (and what the
     * kernel driver or Steam set) stay as they are. [rumbleOn] false sends the motors stopped.
     * [lightOut] adds the order to fade out the start-up animation, without which a pad the kernel did
     * not set up keeps its blue glow. [seq] is the Bluetooth sequence number (0..15).
     */
    fun output(o: PadOutput, rumbleOn: Boolean, bluetooth: Boolean, seq: Int, vibrationV2: Boolean, lightOut: Boolean, into: ByteArray): Int {
        val size = if (bluetooth) OUTPUT_BT_SIZE else OUTPUT_USB_SIZE
        for (i in 0 until size) into[i] = 0
        val c: Int
        if (bluetooth) { into[0] = OUTPUT_BT.toByte(); into[1] = ((seq and 0x0F) shl 4).toByte(); into[2] = 0x10; c = 3 }
        else { into[0] = OUTPUT_USB.toByte(); c = 1 }
        var f0 = 0; var f1 = 0; var f2 = 0
        val t = o.touched
        if (t and PadOutput.RUMBLE != 0) {
            // Rumble emulated by the haptic actuators: the newer method on recent firmware, as the kernel does.
            f0 = f0 or 0x02
            if (vibrationV2) f2 = f2 or 0x04 else f0 = f0 or 0x01
            into[c + MOTOR_RIGHT] = (if (rumbleOn) motor(o.rumbleHigh) else 0).toByte()
            into[c + MOTOR_LEFT] = (if (rumbleOn) motor(o.rumbleLow) else 0).toByte()
        }
        if (t and PadOutput.R2 != 0) { f0 = f0 or 0x04; o.r2.bytes(into, c + RIGHT_TRIGGER) }
        if (t and PadOutput.L2 != 0) { f0 = f0 or 0x08; o.l2.bytes(into, c + LEFT_TRIGGER) }
        if (t and PadOutput.MUTE != 0) { f1 = f1 or 0x01; into[c + MUTE_LED] = (if (o.muteLed) 1 else 0).toByte() }
        if (t and PadOutput.LIGHTBAR != 0) {
            val l = o.lightbar
            f1 = f1 or 0x04
            if (l != null) { into[c + RED] = Pad.lightByte(l.r).toByte(); into[c + GREEN] = Pad.lightByte(l.g).toByte(); into[c + BLUE] = Pad.lightByte(l.b).toByte() }
        }
        if (t and PadOutput.LEDS != 0) { f1 = f1 or 0x10; into[c + PLAYER_LEDS] = (o.playerLeds and 0x1F).toByte() }
        if (lightOut) { f2 = f2 or 0x02; into[c + LIGHTBAR_SETUP] = 0x02 }
        into[c + FLAG0] = f0.toByte(); into[c + FLAG1] = f1.toByte(); into[c + FLAG2] = f2.toByte()
        if (bluetooth) {
            val crc = Crc32.seeded(SEED_OUTPUT, into, 0, size - 4)
            for (k in 0 until 4) into[size - 4 + k] = (crc ushr (8 * k)).toByte()
        }
        return size
    }

    private fun motor(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()

    /** The firmware's « update version » (feature report 0x20), which tells which rumble method it knows. */
    fun updateVersion(buf: ByteArray, length: Int): Int =
        if (length >= 46 && (buf[0].toInt() and 0xFF) == FEATURE_FIRMWARE) (buf[44].toInt() and 0xFF) or ((buf[45].toInt() and 0xFF) shl 8) else -1

    /** The pad's Bluetooth address (feature report 0x09), the same over USB and Bluetooth: how it is known when it comes back. */
    fun address(buf: ByteArray, length: Int): String? {
        if (length < 7 || (buf[0].toInt() and 0xFF) != FEATURE_PAIRING) return null
        if ((1..6).all { buf[it].toInt() == 0 }) return null
        return (6 downTo 1).joinToString(":") { "%02x".format(buf[it].toInt() and 0xFF) }
    }

    /** True if a Bluetooth feature report's last four bytes are its CRC (seeded with 0xA3). */
    fun featureCrcOk(buf: ByteArray, length: Int): Boolean {
        val crc = (buf[length - 4].toInt() and 0xFF) or ((buf[length - 3].toInt() and 0xFF) shl 8) or
            ((buf[length - 2].toInt() and 0xFF) shl 16) or ((buf[length - 1].toInt() and 0xFF) shl 24)
        return Crc32.seeded(SEED_FEATURE, buf, 0, length - 4) == crc
    }

    /** The update version from which a DualSense (not an Edge, which always has it) knows the newer rumble: 2.21. */
    const val VIBRATION_V2_FROM = 0x0215
}
