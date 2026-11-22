package sillage

import sillage.math.Vec3
import sillage.platform.PadOutput
import sillage.platform.PadState
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The two sticks of a pad. */
enum class Stick { LEFT, RIGHT }

/** What kind of controller a [Pad] is. */
enum class PadKind {
    /** A PlayStation 5 DualSense. */
    DUALSENSE,
    /** A DualSense Edge (its back paddles and function buttons included). */
    DUALSENSE_EDGE,
    /** Any other gamepad the system reports (Xbox pads, 8BitDo, Switch Pro…), mapped to the standard layout. */
    GENERIC,
    /** A [VirtualPad] driven by code. */
    VIRTUAL,
}

/** How a pad is linked to the computer. */
enum class PadConnection { USB, BLUETOOTH, OTHER }

/** What the pad says about its battery. */
enum class BatteryState { UNKNOWN, DISCHARGING, CHARGING, FULL, ERROR }

/**
 * One of a pad's sticks as read this frame: [x] to the right, [y] up (pushed away from you), both −1..1,
 * with the pad's radial dead zone applied: positions closer to the centre than [Pad.deadZone] count as
 * centred, and the rest is rescaled to start from 0, so a small tilt gives a small value in any direction.
 */
class Thumbstick internal constructor() {
    var x = 0f
        private set
    var y = 0f
        private set
    /** How far it is pushed, 0 (centred or inside the dead zone) to 1 (fully). */
    var amount = 0f
        private set
    /** The position as the pad reports it, before the dead zone. */
    var rawX = 0f
        private set
    var rawY = 0f
        private set

    internal fun update(rx: Float, ry: Float, dead: Float, full: Float) {
        rawX = rx; rawY = ry
        val r = sqrt(rx * rx + ry * ry)
        if (r <= dead || r == 0f) { x = 0f; y = 0f; amount = 0f; return }
        val a = ((r - dead) / (full - dead).coerceAtLeast(1e-3f)).coerceAtMost(1f)
        x = rx / r * a; y = ry / r * a; amount = a
    }

    override fun toString() = "(%+.2f, %+.2f)".fmt(x, y)
}

/** A finger on a DualSense's touchpad: [x] from the left edge, [y] from the top, both 0..1. */
class Touch internal constructor() {
    /** True while this finger is on the pad. */
    var active = false
        internal set
    /** The pad's number for this touch, the same while the finger stays down. */
    var id = 0
        internal set
    var x = 0f
        internal set
    var y = 0f
        internal set
}

/**
 * An effect of a DualSense's adaptive trigger, for [Pad.triggerEffect]. Positions are fractions of the
 * trigger's travel (0 at rest, 1 fully pulled) and forces fractions of the strongest (0..1); the trigger
 * works in tenths of its travel and eight steps of force, so values are rounded to those.
 */
class TriggerEffect private constructor(val kind: Kind, val start: Float, val end: Float, val force: Float, val frequency: Float) {
    enum class Kind { OFF, RESISTANCE, WEAPON, VIBRATION }

    /** The 11 bytes of the effect in a DualSense output report. */
    internal fun bytes(into: ByteArray, at: Int) {
        for (k in 0 until 11) into[at + k] = 0
        fun zone(p: Float) = (p * 9f).roundToInt().coerceIn(0, 9)
        fun strength(f: Float) = (1 + (f * 7f).roundToInt()).coerceIn(1, 8)
        when (kind) {
            Kind.OFF -> into[at] = 0x05
            Kind.RESISTANCE, Kind.VIBRATION -> {
                // One force (or amplitude) of 3 bits per zone, from the start zone to the end of the travel.
                val v = strength(force) - 1
                var zones = 0; var forces = 0L
                for (i in zone(start) until 10) { zones = zones or (1 shl i); forces = forces or (v.toLong() shl (3 * i)) }
                into[at] = if (kind == Kind.RESISTANCE) 0x21 else 0x26
                into[at + 1] = zones.toByte(); into[at + 2] = (zones shr 8).toByte()
                for (b in 0 until 4) into[at + 3 + b] = (forces shr (8 * b)).toByte()
                if (kind == Kind.VIBRATION) into[at + 9] = frequency.roundToInt().coerceIn(1, 255).toByte()
            }
            Kind.WEAPON -> {
                // A click: resistance from the start zone (2..7) that gives way at the end zone (start+1..8).
                val s = zone(start).coerceIn(2, 7); val e = zone(end).coerceIn(s + 1, 8)
                val zones = (1 shl s) or (1 shl e)
                into[at] = 0x25; into[at + 1] = zones.toByte(); into[at + 2] = (zones shr 8).toByte()
                into[at + 3] = (strength(force) - 1).toByte()
            }
        }
    }

    override fun toString() = when (kind) {
        Kind.OFF -> "off"
        Kind.RESISTANCE -> "resistance from %.0f %% (force %.0f %%)".fmt(start * 100, force * 100)
        Kind.WEAPON -> "weapon %.0f–%.0f %% (force %.0f %%)".fmt(start * 100, end * 100, force * 100)
        Kind.VIBRATION -> "vibration from %.0f %% (%.0f %%, %.0f Hz)".fmt(start * 100, force * 100, frequency)
    }

    companion object {
        /** No effect: the trigger moves freely. */
        val OFF = TriggerEffect(Kind.OFF, 0f, 0f, 0f, 0f)
        /** The trigger resists from [start] to the end of its travel, with [force] (0 is [OFF]). */
        fun resistance(start: Number = 0, force: Number = 1): TriggerEffect =
            if (force.toFloat() <= 0f) OFF else TriggerEffect(Kind.RESISTANCE, start.toFloat().coerceIn(0f, 1f), 1f, force.toFloat().coerceIn(0f, 1f), 0f)
        /**
         * A gun's trigger: it resists from [start] and gives way with a click at [end]. The trigger only
         * does this between 20 % and 90 % of its travel.
         */
        fun weapon(start: Number = .3, end: Number = .6, force: Number = 1): TriggerEffect =
            if (force.toFloat() <= 0f) OFF else TriggerEffect(Kind.WEAPON, start.toFloat().coerceIn(0f, 1f), end.toFloat().coerceIn(0f, 1f), force.toFloat().coerceIn(0f, 1f), 0f)
        /** The trigger buzzes from [start] onwards, with [amplitude] (0..1) at [frequency] Hz (1 to 255). */
        fun vibration(start: Number = 0, amplitude: Number = 1, frequency: Number = 30): TriggerEffect =
            if (amplitude.toFloat() <= 0f) OFF else TriggerEffect(Kind.VIBRATION, start.toFloat().coerceIn(0f, 1f), 1f, amplitude.toFloat().coerceIn(0f, 1f), frequency.toFloat().coerceIn(1f, 255f))
    }
}

/**
 * A game controller as the scene sees it this frame. Pads are found by [Input.pads]: plugged in or paired
 * while the game runs, each keeps its [slot] (player number − 1) until the game ends, even if it is
 * unplugged and comes back.
 *
 * Buttons are named both by their place ([SOUTH], [EAST]…) and by their DualSense names ([CROSS],
 * [CIRCLE]…); they are codes like [Key]'s, so `input.pressed(Pad.CROSS)`, `onKey(Pad.OPTIONS) { … }` and
 * `keys.jump = intArrayOf(Key.SPACE, Pad.CROSS)` work as with keys (any pad counts there).
 *
 * A DualSense read through hidraw has everything here; other pads have buttons, sticks, triggers and
 * usually rumble — see [canRumble], [hasLightbar], [hasMotion]…
 */
class Pad internal constructor(slot: Int, internal var device: PadDevice) {
    /** The player slot, from 0: the first pad plugged in is 0. Its player LEDs show slot + 1. */
    val slot: Int = slot
    /** The name the pad gives (or the system gives it). */
    val name: String get() = device.deviceName
    val kind: PadKind get() = device.deviceKind
    val connection: PadConnection get() = device.deviceConnection
    /** Where the engine reads it: a /dev path, or "virtual". */
    val path: String get() = device.devicePath
    /** False once unplugged (the pad then reads as untouched); it becomes true again if the same pad comes back. */
    var connected = true
        internal set

    internal val state = PadState()
    internal var was = 0
    internal var leavingFrames = 0
    internal val out = PadOutput()
    private val turnBase = DoubleArray(3)
    private var turnKnown = false
    internal val turned = FloatArray(3)

    /** Radial dead zone of the sticks (fraction of a full tilt), and the tilt that already counts as full. */
    var deadZone = .1f
    var fullTilt = .95f
    /** Trigger values below this count as released. */
    var triggerDeadZone = .03f

    val leftStick = Thumbstick()
    val rightStick = Thumbstick()
    /** The touchpad's two fingers (always two objects; see [Touch.active]). */
    val touch: List<Touch> = listOf(Touch(), Touch())

    /** True while [button] (a [Pad] button code) is held. */
    fun isDown(button: Int): Boolean { val b = button - BUTTON_BASE; return b in 0 until BUTTON_COUNT && state.buttons and (1 shl b) != 0 }
    /** True on the frame [button] went down. */
    fun pressed(button: Int): Boolean { val b = button - BUTTON_BASE; return b in 0 until BUTTON_COUNT && state.buttons and (1 shl b) != 0 && was and (1 shl b) == 0 }
    /** True on the frame [button] was let go. */
    fun released(button: Int): Boolean { val b = button - BUTTON_BASE; return b in 0 until BUTTON_COUNT && state.buttons and (1 shl b) == 0 && was and (1 shl b) != 0 }

    fun stick(which: Stick): Thumbstick = if (which == Stick.LEFT) leftStick else rightStick

    /**
     * How far [trigger] ([L2] or [R2]) is pulled, 0..1. For another button: 1 while held, else 0. A pad
     * whose triggers are plain buttons gives 0 or 1.
     */
    fun trigger(trigger: Int = R2): Float = when (trigger) {
        L2 -> analog(4, 6)
        R2 -> analog(5, 7)
        else -> if (isDown(trigger)) 1f else 0f
    }

    private fun analog(axis: Int, bit: Int): Float {
        val v = state.axes[axis]
        if (v < 0f) return if (state.buttons and (1 shl bit) != 0) 1f else 0f   // no analog value: the button
        val d = triggerDeadZone
        return if (v <= d) 0f else ((v - d) / (1f - d)).coerceAtMost(1f)
    }

    /** True if [gyro] and [accel] are read. */
    val hasMotion: Boolean get() = device.deviceFeatures and F_MOTION != 0
    /** Turn rate in radians per second about the pad's axes: x to its right, y up out of its face, z towards you (counter-clockwise positive). */
    val gyro: Vec3 get() = Vec3(state.gyro[0], state.gyro[1], state.gyro[2])
    /** Acceleration in m/s², on the same axes; at rest it is about 9.8 upwards (the pad feels gravity's push). */
    val accel: Vec3 get() = Vec3(state.accel[0], state.accel[1], state.accel[2])

    /** Battery level 0..1, or −1 when unknown (a pad that does not say, or on a cable that does not report it). */
    val battery: Float get() = state.battery
    val batteryState: BatteryState get() = BATTERY_STATES[state.batteryState]
    /** A DualSense's headphone and microphone sockets: true when something is plugged in. */
    val headphones: Boolean get() = state.headphones
    val microphone: Boolean get() = state.microphone

    val canRumble: Boolean get() = device.deviceFeatures and F_RUMBLE != 0
    val hasLightbar: Boolean get() = device.deviceFeatures and F_LIGHTS != 0
    val hasAdaptiveTriggers: Boolean get() = device.deviceFeatures and F_TRIGGERS != 0
    val hasTouchpad: Boolean get() = device.deviceFeatures and F_TOUCH != 0
    val hasBattery: Boolean get() = device.deviceFeatures and F_BATTERY != 0

    /**
     * Shakes the pad for [seconds]: [low] drives the heavy motor (a rumble), [high] the light one (a
     * buzz), each 0..1. A new call replaces the last; the engine stops it when the time is up.
     */
    fun rumble(low: Number, high: Number = low, seconds: Number = .25) {
        val s = seconds.toFloat().coerceAtLeast(0f)
        out.rumbleLow = low.toFloat().coerceIn(0f, 1f); out.rumbleHigh = high.toFloat().coerceIn(0f, 1f)
        out.rumbleSeconds = s; out.rumbleUntil = sillage.math.nanoTime() + (s * 1e9).toLong(); out.rumbleCount++
        out.touched = out.touched or PadOutput.RUMBLE
        send()
    }

    fun stopRumble() = rumble(0, 0, 0)

    /** The DualSense's light bar colour (sent to the pad when set; the engine gives each slot its colour when the pad connects). */
    var lightbar: Color = Color.BLACK
        set(v) { field = v; out.lightbar = v; out.touched = out.touched or PadOutput.LIGHTBAR; send() }

    /** The five player LEDs under the touchpad, as bits (bit 0 is the leftmost); [playerPattern] gives the console's patterns. */
    var playerLeds: Int = 0
        set(v) { field = v and 0x1F; out.playerLeds = field; out.touched = out.touched or PadOutput.LEDS; send() }

    /** The light of the mute button. */
    var muteLed: Boolean = false
        set(v) { field = v; out.muteLed = v; out.touched = out.touched or PadOutput.MUTE; send() }

    /** Sets the adaptive effect of [trigger] ([L2] or [R2]); [TriggerEffect.OFF] frees it. */
    fun triggerEffect(trigger: Int, effect: TriggerEffect) {
        when (trigger) {
            L2 -> { out.l2 = effect; out.touched = out.touched or PadOutput.L2 }
            R2 -> { out.r2 = effect; out.touched = out.touched or PadOutput.R2 }
            else -> throw IllegalArgumentException("Adaptive effects are for Pad.L2 and Pad.R2")
        }
        send()
    }

    /** The effect last set on [trigger]. */
    fun triggerEffect(trigger: Int): TriggerEffect = if (trigger == L2) out.l2 else out.r2

    private fun send() { out.version++; if (connected) device.write(out) }

    /** Names of the buttons held, for messages and tests. */
    fun buttonsDown(): String = (0 until BUTTON_COUNT).filter { state.buttons and (1 shl it) != 0 }.joinToString(" ") { BUTTON_NAMES[it] }

    override fun toString() = "Pad ${slot + 1} ($name, ${connection.name.lowercase()}, $path)"

    // ------------------------------------------------------------------ game thread, once a frame

    internal fun poll() {
        was = state.buttons
        if (connected) device.read(state) else state.neutral()
        leftStick.update(state.axes[0], state.axes[1], deadZone, fullTilt)
        rightStick.update(state.axes[2], state.axes[3], deadZone, fullTilt)
        for (i in 0 until 2) { val t = touch[i]; t.active = state.touchActive[i]; t.id = state.touchId[i]; t.x = state.touchX[i]; t.y = state.touchY[i] }
        // How far the pad turned since the last frame, from the angles the device integrated over all its reports.
        if (state.turnValid && turnKnown) for (i in 0 until 3) turned[i] = (state.turn[i] - turnBase[i]).toFloat()
        else for (i in 0 until 3) turned[i] = 0f
        turnKnown = state.turnValid
        for (i in 0 until 3) turnBase[i] = state.turn[i]
    }

    /** A new device for this slot (the same pad plugged in again): its state starts fresh, and gets the game's lights and effects back. */
    internal fun attach(d: PadDevice) { device = d; connected = true; turnKnown = false; state.neutral(); was = 0; if (out.touched != 0) d.write(out) }

    /** Radians turned about [axis] since the last frame: from the device's integrated angles, else the latest rate × [dt]. */
    internal fun turnedAbout(axis: Int, dt: Float): Float = if (state.turnValid) turned[axis] else state.gyro[axis] * dt

    companion object {
        internal const val BUTTON_BASE = 0x1000
        internal const val BUTTON_COUNT = 23

        /** The bottom face button (cross on a DualSense, A on an Xbox pad, B on a Nintendo pad). */
        const val SOUTH = BUTTON_BASE
        /** The right face button (circle, B, A). */
        const val EAST = BUTTON_BASE + 1
        /** The left face button (square, X, Y). */
        const val WEST = BUTTON_BASE + 2
        /** The top face button (triangle, Y, X). */
        const val NORTH = BUTTON_BASE + 3
        /** Shoulder buttons and triggers (as buttons; see [trigger] for how far they are pulled). */
        const val L1 = BUTTON_BASE + 4
        const val R1 = BUTTON_BASE + 5
        const val L2 = BUTTON_BASE + 6
        const val R2 = BUTTON_BASE + 7
        /** The small button left of the centre (create, view, minus). */
        const val SELECT = BUTTON_BASE + 8
        /** The small button right of the centre (options, menu, plus). */
        const val START = BUTTON_BASE + 9
        /** The logo button (PS, Xbox, home). */
        const val HOME = BUTTON_BASE + 10
        /** Pressing the sticks in. */
        const val L3 = BUTTON_BASE + 11
        const val R3 = BUTTON_BASE + 12
        const val DPAD_UP = BUTTON_BASE + 13
        const val DPAD_DOWN = BUTTON_BASE + 14
        const val DPAD_LEFT = BUTTON_BASE + 15
        const val DPAD_RIGHT = BUTTON_BASE + 16
        /** Pressing a DualSense's touchpad. */
        const val TOUCHPAD = BUTTON_BASE + 17
        /** A DualSense's mute button. */
        const val MUTE = BUTTON_BASE + 18
        /** A DualSense Edge's function buttons and back paddles. */
        const val FN_LEFT = BUTTON_BASE + 19
        const val FN_RIGHT = BUTTON_BASE + 20
        const val PADDLE_LEFT = BUTTON_BASE + 21
        const val PADDLE_RIGHT = BUTTON_BASE + 22

        const val CROSS = SOUTH
        const val CIRCLE = EAST
        const val SQUARE = WEST
        const val TRIANGLE = NORTH
        const val CREATE = SELECT
        const val OPTIONS = START
        const val PS = HOME

        internal val BUTTON_NAMES = arrayOf("CROSS", "CIRCLE", "SQUARE", "TRIANGLE", "L1", "R1", "L2", "R2", "CREATE", "OPTIONS", "PS", "L3", "R3",
            "UP", "DOWN", "LEFT", "RIGHT", "TOUCHPAD", "MUTE", "FN_LEFT", "FN_RIGHT", "PADDLE_LEFT", "PADDLE_RIGHT")

        /** The name of a button code ("CROSS"…), or null if [code] is not a pad button. */
        fun buttonName(code: Int): String? = BUTTON_NAMES.getOrNull(code - BUTTON_BASE)

        /** True if [code] is a pad button code. */
        fun isButton(code: Int) = code - BUTTON_BASE in 0 until BUTTON_COUNT

        /** The player LEDs a PlayStation lights for player [n] (1 to 5; more lights them all). */
        fun playerPattern(n: Int): Int = when (n) {
            1 -> 0b00100; 2 -> 0b01010; 3 -> 0b10101; 4 -> 0b11011
            else -> 0b11111
        }

        /** The light bar colour the engine gives slot [slot]: blue, red, green, pink as on a PlayStation, then orange, cyan, yellow, white. */
        fun slotColor(slot: Int): Color = SLOT_COLORS[slot % SLOT_COLORS.size]
        private val SLOT_COLORS = listOf(Color.hex(0x0040FF), Color.hex(0xFF1020), Color.hex(0x10E030), Color.hex(0xFF20C0),
            Color.hex(0xFF7000), Color.hex(0x00E0E0), Color.hex(0xFFE000), Color.hex(0xFFFFFF))

        internal const val F_RUMBLE = 1; internal const val F_LIGHTS = 2; internal const val F_TRIGGERS = 4
        internal const val F_MOTION = 8; internal const val F_TOUCH = 16; internal const val F_BATTERY = 32
        internal const val F_ALL = 63
        private val BATTERY_STATES = BatteryState.entries.toTypedArray()

        /** sRGB bytes for a light from a linear [Color]. */
        internal fun lightByte(c: Float): Int = (c.coerceIn(0f, 1f).pow(1f / 2.2f) * 255f).roundToInt()
    }
}
