package sillage

import sillage.math.Vec3

/**
 * A pad driven by a timed script, for scripted runs (`--pad-script "…"`), recordings and checks:
 *
 *     0 L 0,1; 1.5 tap CROSS; 2 R 1,0; 3 R 0,0; 3 L 0,0
 *
 * Entries are separated by `;`, each a time in seconds from the start and an order: `L x,y` and `R x,y`
 * put a stick (it stays there), `press B`, `release B` and `tap B` (held a tenth of a second) work a
 * button by its name (CROSS, CIRCLE, SQUARE, TRIANGLE, L1, R1, L2, R2, CREATE, OPTIONS, PS, L3, R3, UP,
 * DOWN, LEFT, RIGHT, TOUCHPAD, MUTE), `L2 v` and `R2 v` pull a trigger (0..1), `gyro x,y,z` sets the turn
 * rate (rad/s). Time is the scene's, so a run without a window plays the same every time.
 */
class PadScript(text: String, name: String = "Scripted pad") {
    /** The pad the script drives. */
    val pad = VirtualPad(name, PadKind.VIRTUAL, "script:$name")
    private class Step(val at: Float, val order: String, val args: List<String>)
    private val steps: List<Step>
    private var time = 0f
    private var k = 0
    private val tapEnds = FloatArray(Pad.BUTTON_COUNT) { -1f }

    init {
        steps = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { e ->
            val parts = e.split(Regex("\\s+"))
            require(parts.size >= 2) { "--pad-script: « $e » needs a time and an order" }
            val at = parts[0].toFloatOrNull() ?: throw IllegalArgumentException("--pad-script: « ${parts[0]} » is not a time")
            val s = Step(at, parts[1].uppercase(), parts.drop(2).flatMap { it.split(',') }.filter { it.isNotEmpty() })
            validate(s)
            s
        }.sortedBy { it.at }
    }

    private fun button(name: String): Int {
        val i = Pad.BUTTON_NAMES.indexOf(name.uppercase())
        require(i >= 0) { "--pad-script: no button « $name » (${Pad.BUTTON_NAMES.joinToString()})" }
        return Pad.BUTTON_BASE + i
    }

    private fun validate(s: Step) {
        fun n(count: Int) = require(s.args.size == count && s.args.all { it.toFloatOrNull() != null }) { "--pad-script: « ${s.order} » takes $count numbers" }
        when (s.order) {
            "L", "R" -> n(2)
            "L2", "R2" -> n(1)
            "GYRO" -> n(3)
            "PRESS", "RELEASE", "TAP" -> { require(s.args.size == 1) { "--pad-script: « ${s.order} » takes a button name" }; button(s.args[0]) }
            else -> throw IllegalArgumentException("--pad-script: unknown order « ${s.order} » (L, R, L2, R2, press, release, tap, gyro)")
        }
    }

    /** True once every entry was played (taps let go). */
    val done: Boolean get() = k >= steps.size && tapEnds.all { it < 0f }

    /** Plugs the pad into [scene] and plays the script as the scene's time goes by. */
    fun attach(scene: Scene) { scene.input.plug(pad); scene.onUpdate { advance(it) } }

    /** Plays what is due after [dt] more seconds. */
    fun advance(dt: Float) {
        time += dt
        for (b in 0 until Pad.BUTTON_COUNT) if (tapEnds[b] >= 0f && time >= tapEnds[b]) { tapEnds[b] = -1f; pad.set(Pad.BUTTON_BASE + b, false) }
        while (k < steps.size && steps[k].at <= time) {
            val s = steps[k++]
            fun f(i: Int) = s.args[i].toFloat()
            when (s.order) {
                "L" -> pad.stick(Stick.LEFT, f(0), f(1))
                "R" -> pad.stick(Stick.RIGHT, f(0), f(1))
                "L2" -> pad.trigger(Pad.L2, f(0))
                "R2" -> pad.trigger(Pad.R2, f(0))
                "GYRO" -> pad.motion(Vec3(f(0), f(1), f(2)))
                "PRESS" -> pad.set(button(s.args[0]), true)
                "RELEASE" -> pad.set(button(s.args[0]), false)
                "TAP" -> { val b = button(s.args[0]); pad.set(b, true); tapEnds[b - Pad.BUTTON_BASE] = time + .1f }
            }
        }
    }
}
