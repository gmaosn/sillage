package sillage

import sillage.math.Vec3
import sillage.platform.PadOutput
import sillage.platform.PadState

/**
 * A controller as a [PadSource] provides it: a real one found by the system, or a [VirtualPad]. Games
 * use [Pad], which the scene's [Input] makes from these.
 */
abstract class PadDevice internal constructor() {
    /** What identifies the same pad when it comes back (its address, serial or system path). */
    internal abstract val deviceId: String
    internal abstract val deviceName: String
    internal abstract val deviceKind: PadKind
    internal abstract val deviceConnection: PadConnection
    internal abstract val devicePath: String
    internal abstract val deviceFeatures: Int
    /** Copies the newest state into [into] (game thread). */
    internal abstract fun read(into: PadState)
    /** Takes the game's wishes (game thread); the device sends them in its own time. */
    internal abstract fun write(output: PadOutput)
}

/**
 * Where an [Input] finds its pads. A game with a window uses [system]; without a window there are none
 * ([NONE]) unless you plug [VirtualPad]s (`input.plug(pad)`) or set a source of your own.
 */
interface PadSource : AutoCloseable {
    /** The pads plugged in now. Read once a frame on the game's thread: return the same list object until something changes. */
    val devices: List<PadDevice>

    /** Stops reading (and frees the trigger effects and motors of real pads). */
    override fun close() {}

    companion object {
        val NONE: PadSource = object : PadSource { override val devices: List<PadDevice> = emptyList() }

        /**
         * The computer's controllers, plugged in or paired at any time: DualSense pads through hidraw
         * (everything, over USB or Bluetooth), other gamepads through evdev. A DualSense whose hidraw node
         * the user may not open is read through evdev, and a message says once what is missing.
         */
        fun system(): PadSource = platformPads()
    }
}

/**
 * A pad driven by code, for tests, bots and scripted runs: plug it with `input.plug(pad)`, then press its
 * buttons and push its sticks; the scene reads it as a real one (players walk with its left stick…), and
 * what the game sends back ([rumbleLow], [lightbar], [triggerEffect]…) can be checked.
 * Use it from the game's thread (in `onUpdate`, or between [Scene.step] calls).
 */
class VirtualPad(name: String = "Virtual pad", kind: PadKind = PadKind.VIRTUAL, id: String? = null) : PadDevice() {
    override val deviceId: String = id ?: "virtual:" + virtualIds++
    override val deviceName: String = name
    override val deviceKind: PadKind = kind
    override val deviceConnection: PadConnection get() = PadConnection.OTHER
    override val devicePath: String get() = "virtual"
    override val deviceFeatures: Int get() = Pad.F_ALL
    private val state = PadState().apply { axes[4] = 0f; axes[5] = 0f; battery = 1f; batteryState = BatteryState.FULL.ordinal }
    private val output = PadOutput()

    override fun read(into: PadState) { into.copyFrom(state) }
    override fun write(output: PadOutput) { this.output.copyFrom(output) }

    /** Holds [buttons] ([Pad] codes) down until [release]. */
    fun press(vararg buttons: Int) { for (b in buttons) set(b, true) }
    fun release(vararg buttons: Int) { for (b in buttons) set(b, false) }
    fun set(button: Int, down: Boolean) {
        require(Pad.isButton(button)) { "Not a pad button: $button" }
        val bit = 1 shl (button - Pad.BUTTON_BASE)
        state.buttons = if (down) state.buttons or bit else state.buttons and bit.inv()
        // The triggers' buttons pull the triggers, as on a real pad.
        if (button == Pad.L2) state.axes[4] = if (down) 1f else 0f
        if (button == Pad.R2) state.axes[5] = if (down) 1f else 0f
        state.reports++
    }
    /** Lets go of everything and centres the sticks. */
    fun releaseAll() { state.buttons = 0; for (i in 0 until 6) state.axes[i] = 0f; state.gyro.fill(0f); state.touchActive.fill(false) }

    /** Puts [which] stick at ([x], [y]), each −1..1 (y up), as the pad would report it (the dead zone applies after). */
    fun stick(which: Stick, x: Number, y: Number) {
        val k = if (which == Stick.LEFT) 0 else 2
        state.axes[k] = x.toFloat().coerceIn(-1f, 1f); state.axes[k + 1] = y.toFloat().coerceIn(-1f, 1f)
    }

    /** Pulls [trigger] ([Pad.L2] or [Pad.R2]) to [value] (0..1); its button is down past a tenth, as on a DualSense. */
    fun trigger(trigger: Int, value: Number) {
        val v = value.toFloat().coerceIn(0f, 1f)
        require(trigger == Pad.L2 || trigger == Pad.R2) { "Pad.L2 or Pad.R2" }
        val axis = if (trigger == Pad.L2) 4 else 5; val bit = axis + 2
        state.axes[axis] = v
        state.buttons = if (v > .1f) state.buttons or (1 shl bit) else state.buttons and (1 shl bit).inv()
    }

    /** Turn rate (rad/s) and acceleration (m/s²) on the pad's axes; see [Pad.gyro]. */
    fun motion(gyro: Vec3, accel: Vec3 = Vec3(0f, 9.80665f, 0f)) {
        state.gyro[0] = gyro.x; state.gyro[1] = gyro.y; state.gyro[2] = gyro.z
        state.accel[0] = accel.x; state.accel[1] = accel.y; state.accel[2] = accel.z
    }

    /** Puts [finger] (0 or 1) on the touchpad at ([x], [y]), 0..1 from the top left. */
    fun touch(finger: Int, x: Number, y: Number, id: Int = finger) {
        state.touchActive[finger] = true; state.touchId[finger] = id
        state.touchX[finger] = x.toFloat().coerceIn(0f, 1f); state.touchY[finger] = y.toFloat().coerceIn(0f, 1f)
    }
    fun lift(finger: Int) { state.touchActive[finger] = false }

    /** Battery level 0..1 and state, as the game will read them. */
    fun battery(level: Number, state: BatteryState = BatteryState.DISCHARGING) {
        this.state.battery = level.toFloat(); this.state.batteryState = state.ordinal
    }

    /** The last rumble the game asked for: motors 0..1, how long, and how many times it asked. */
    val rumbleLow: Float get() = output.rumbleLow
    val rumbleHigh: Float get() = output.rumbleHigh
    val rumbleSeconds: Float get() = output.rumbleSeconds
    val rumbles: Int get() = output.rumbleCount
    /** The light bar colour the game set (null: never set). */
    val lightbar: Color? get() = output.lightbar
    val playerLeds: Int get() = output.playerLeds
    val muteLed: Boolean get() = output.muteLed
    fun triggerEffect(trigger: Int): TriggerEffect = if (trigger == Pad.L2) output.l2 else output.r2
}

private var virtualIds = 1
