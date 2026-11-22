package sillage.dev

import sillage.Color
import sillage.Hud

/**
 * Under `./sillage dev`, the last error (a compilation, a scene that failed to build, an update that
 * threw) shown over the game, at the top of the screen, until a save fixes it.
 */
internal class DevOverlay {
    private var shown: String? = null
    private var hud: Hud? = null
    private val elements = ArrayList<Hud.Element>()

    /** Puts the current error on [h] if it changed (nothing to do on most frames). */
    fun show(h: Hud) {
        val error = DevLink.error
        if (error === shown && h === hud) return
        clear()
        shown = error; hud = h
        if (error == null) return
        val lines = error.lines().filter { it.isNotBlank() }.take(7).map { if (it.length > 104) it.take(101) + "..." else it } +
            "save a file to try again"
        val sizes = lines.indices.map { if (it == 0) 2f else 1.25f }
        val gaps = sizes.map { it * 7 + 8 }
        val width = lines.indices.maxOf { Hud.textWidth(lines[it], sizes[it]) } + 48
        val height = gaps.sum() + 28
        elements += h.panel(0, 16, width, height, Color.hex(0x4A1C22), Hud.Anchor.TOP, opacity = .92)
        var y = 32f
        for (i in lines.indices) {
            val color = when (i) { 0 -> Color.hex(0xFFB4A0); lines.size - 1 -> Color.hex(0xC9A9A6); else -> Color.hex(0xFFF2E6) }
            elements += h.text(lines[i], 0, y, sizes[i], color, Hud.Anchor.TOP)
            y += gaps[i]
        }
    }

    /** After a new scene: the old HUD went with the old scene. */
    fun reset() { clear(); shown = null; hud = null }

    private fun clear() {
        hud?.let { h -> for (e in elements) h.remove(e) }
        elements.clear()
    }
}
