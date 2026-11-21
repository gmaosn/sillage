package sillage

import kotlin.math.max
import kotlin.math.sqrt

/**
 * A named action ("jump", "fire", "pause") bound to keys and pad buttons, which the game reads instead of
 * the keys themselves, so players can change the bindings while the game runs (a settings menu: show
 * [bindings], wait for [Input.firstPressed], [rebind]). Made by [Input.action].
 */
class Action internal constructor(private val input: Input, val name: String) {
    private var codes = IntArray(0)
    /** The inputs bound: key codes ([Key]) and pad buttons ([Pad]). */
    val bindings: IntArray get() = codes.copyOf()
    /** Which pad counts, by its slot; −1 (the default) for any pad. */
    var pad = -1

    /** Adds [inputs] to the bindings. */
    fun bind(vararg inputs: Int): Action { codes = (codes + inputs).distinct().toIntArray(); return this }
    /** Replaces all bindings with [inputs] (none: the action can no longer be triggered). */
    fun rebind(vararg inputs: Int): Action { codes = inputs.distinct().toIntArray(); return this }
    fun unbind(vararg inputs: Int): Action { codes = codes.filter { it !in inputs }.toIntArray(); return this }

    /** True while one of its inputs is held. */
    val isDown: Boolean get() { for (c in codes) if (input.isDown(c, pad)) return true; return false }
    private val wasDown: Boolean get() { for (c in codes) if (input.wasDown(c, pad)) return true; return false }
    /** True on the frame it started (one of its inputs went down while none was held). */
    val pressed: Boolean get() = isDown && !wasDown
    /** True on the frame it ended. */
    val released: Boolean get() = !isDown && wasDown
    /** How much: 0..1, the strongest of its inputs (a trigger half pulled gives 0.5, a key 1). */
    val value: Float get() { var v = 0f; for (c in codes) v = max(v, input.value(c, pad)); return v }

    override fun toString() = "$name: " + codes.joinToString { Input.codeName(it) }
}

/**
 * Two axes under one name ("move", "look"): keys for each direction, a pad stick, and the mouse if you
 * want it. [x] is to the right and [y] up (or forward), together within the unit circle (the mouse adds
 * its movement on top). Made by [Input.axes]; rebind it like an [Action].
 */
class Axes internal constructor(private val input: Input, val name: String) {
    var left = IntArray(0)
    var right = IntArray(0)
    var down = IntArray(0)
    var up = IntArray(0)
    /** The stick that drives it (with the pad's dead zone), or null. */
    var stick: Stick? = null
    /** Mouse movement added, per pixel (0: the mouse does not count). Moving the mouse up gives a positive y. */
    var mouse = 0f
    /** Which pad's stick counts, by its slot; −1 for the most pushed of all pads. */
    var pad = -1

    /** Adds four keys or buttons, one for each direction. */
    fun bind(left: Int, right: Int, down: Int, up: Int): Axes {
        this.left += left; this.right += right; this.down += down; this.up += up; return this
    }
    /** Replaces the keys and buttons of the four directions. */
    fun rebind(left: Int, right: Int, down: Int, up: Int): Axes {
        this.left = intArrayOf(left); this.right = intArrayOf(right); this.down = intArrayOf(down); this.up = intArrayOf(up); return this
    }

    val x: Float get() { compute(); return cx }
    val y: Float get() { compute(); return cy }
    /** How far from the centre, keys and stick together (0..1). */
    val amount: Float get() { compute(); return ca }

    private var cx = 0f; private var cy = 0f; private var ca = 0f
    private var frame = -1L

    private fun compute() {
        if (frame == input.frame) return
        frame = input.frame
        fun any(codes: IntArray): Boolean { for (c in codes) if (input.isDown(c, pad)) return true; return false }
        var x = (if (any(right)) 1f else 0f) - (if (any(left)) 1f else 0f)
        var y = (if (any(up)) 1f else 0f) - (if (any(down)) 1f else 0f)
        stick?.let { s -> input.stick(s, pad)?.let { x += it.x; y += it.y } }
        val l = sqrt(x * x + y * y)
        if (l > 1f) { x /= l; y /= l }
        ca = minOf(l, 1f)
        val m = mouse
        if (m != 0f) { x += input.mouseDelta.first * m; y -= input.mouseDelta.second * m }
        cx = x; cy = y
    }

    override fun toString() = "$name: left ${left.joinToString { Input.codeName(it) }}, right ${right.joinToString { Input.codeName(it) }}, " +
        "down ${down.joinToString { Input.codeName(it) }}, up ${up.joinToString { Input.codeName(it) }}" + (stick?.let { ", stick $it" } ?: "") +
        (if (mouse != 0f) ", mouse" else "")
}
