package sillage.platform

import sillage.Color
import sillage.TriggerEffect
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything a pad reports, in plain fields: what the reader threads fill and the game thread reads.
 * Buttons are bits (bit i is the [sillage.Pad] code BUTTON_BASE + i).
 */
internal class PadState {
    var buttons = 0
    /** Left x, left y (up positive), right x, right y (−1..1), L2, R2 (0..1, or −1 when the pad has no analog trigger). */
    val axes = FloatArray(6)
    val gyro = FloatArray(3)          // rad/s
    val accel = FloatArray(3)         // m/s²
    /** Angles turned about the three axes since the device was opened (radians), integrated over every report. */
    val turn = DoubleArray(3)
    var turnValid = false
    val touchActive = BooleanArray(2)
    val touchId = IntArray(2)
    val touchX = FloatArray(2)
    val touchY = FloatArray(2)
    var battery = -1f
    var batteryState = 0              // BatteryState ordinal
    var headphones = false
    var microphone = false
    /** Reports read so far (0: nothing yet). */
    var reports = 0L

    fun copyFrom(o: PadState) {
        buttons = o.buttons
        System.arraycopy(o.axes, 0, axes, 0, 6)
        System.arraycopy(o.gyro, 0, gyro, 0, 3); System.arraycopy(o.accel, 0, accel, 0, 3); System.arraycopy(o.turn, 0, turn, 0, 3)
        turnValid = o.turnValid
        System.arraycopy(o.touchActive, 0, touchActive, 0, 2); System.arraycopy(o.touchId, 0, touchId, 0, 2)
        System.arraycopy(o.touchX, 0, touchX, 0, 2); System.arraycopy(o.touchY, 0, touchY, 0, 2)
        battery = o.battery; batteryState = o.batteryState; headphones = o.headphones; microphone = o.microphone
        reports = o.reports
    }

    /** Untouched: nothing held, sticks centred, triggers released. */
    fun neutral() {
        buttons = 0
        for (i in 0 until 4) axes[i] = 0f
        if (axes[4] > 0f) axes[4] = 0f
        if (axes[5] > 0f) axes[5] = 0f
        gyro.fill(0f); touchActive.fill(false)
    }
}

/** What the game wants of a pad (rumble, lights, trigger effects), handed to the device that sends it. */
internal class PadOutput {
    /** Which outputs the game has used: the others are never sent, so the system's own settings stay. */
    var touched = 0
    var rumbleLow = 0f
    var rumbleHigh = 0f
    var rumbleSeconds = 0f
    /** System.nanoTime() when the rumble ends. */
    var rumbleUntil = 0L
    var rumbleCount = 0
    var lightbar: Color? = null
    var playerLeds = 0
    var muteLed = false
    var l2: TriggerEffect = TriggerEffect.OFF
    var r2: TriggerEffect = TriggerEffect.OFF
    var version = 0

    fun copyFrom(o: PadOutput) {
        touched = o.touched; rumbleLow = o.rumbleLow; rumbleHigh = o.rumbleHigh; rumbleSeconds = o.rumbleSeconds
        rumbleUntil = o.rumbleUntil; rumbleCount = o.rumbleCount
        lightbar = o.lightbar; playerLeds = o.playerLeds; muteLed = o.muteLed; l2 = o.l2; r2 = o.r2; version = o.version
    }

    companion object {
        const val RUMBLE = 1; const val LIGHTBAR = 2; const val LEDS = 4; const val MUTE = 8; const val L2 = 16; const val R2 = 32
    }
}

/**
 * Hands states from one reader thread to the game thread without locks: three buffers, the writer fills
 * its own and swaps it with the middle one; the reader swaps the middle one with its own when it is new.
 * Neither ever waits, nor sees a half-written state.
 */
internal class StateExchange {
    private val buffers = arrayOf(PadState(), PadState(), PadState())
    private val middle = AtomicInteger(1)
    private var back = 0        // writer's
    private var front = 2       // reader's

    /** The writer's buffer, to fill completely before [publish]. */
    fun writing(): PadState = buffers[back]

    fun publish() { back = middle.getAndSet(back or FRESH) and 3 }

    /** The newest state published (the same as last time if nothing new came). */
    fun latest(): PadState {
        if (middle.get() and FRESH != 0) front = middle.getAndSet(front) and 3
        return buffers[front]
    }

    private companion object { const val FRESH = 4 }
}
