package sillage.ui

import sillage.Color
import sillage.Font
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Floats per vertex of the HUD: see [Painter]. */
internal const val HUD_FLOATS = 16

/**
 * Writes the HUD's triangles: per vertex its place in clip space, texture or local coordinates, colour and
 * transparency (1 − opacity), the kind of shape and its parameters (hud.vert / hud.frag):
 *
 * - plain rectangles (the pixel font, square panels), exactly as before fonts came;
 * - rounded boxes, rings and soft shadows, drawn by their distance in the shader (sharp at any scale);
 * - glyphs, read from the [GlyphAtlas] at whole pixels, with an optional outline drawn by the shader.
 *
 * The canvas is 600 units high; [k] turns its units into pixels of the picture.
 */
internal class Painter {
    var v = FloatArray(HUD_FLOATS * 6 * 256); var n = 0
    /** Canvas width and height in units, picture width and height in pixels. */
    var cw = 960f; var ch = 600f; var pw = 1280f; var ph = 800f
    val k get() = ph / ch
    var opacity = 1f
    lateinit var atlas: GlyphAtlas
    // A rectangle in pixels outside which nothing is drawn (text fields).
    private var clipX0 = -1e9f; private var clipY0 = -1e9f; private var clipX1 = 1e9f; private var clipY1 = 1e9f

    fun begin(canvasWidth: Float, pixelWidth: Float, pixelHeight: Float, atlas: GlyphAtlas) {
        n = 0; cw = canvasWidth; ch = 600f; pw = pixelWidth; ph = pixelHeight; this.atlas = atlas; opacity = 1f; noClip()
    }
    fun clip(x0: Float, y0: Float, x1: Float, y1: Float) { clipX0 = x0 * k; clipY0 = y0 * k; clipX1 = x1 * k; clipY1 = y1 * k }
    fun noClip() { clipX0 = -1e9f; clipY0 = -1e9f; clipX1 = 1e9f; clipY1 = 1e9f }

    private fun ensure(floats: Int) { if (n + floats > v.size) v = v.copyOf(max(v.size * 2, n + floats)) }

    private fun vertex(x: Float, y: Float, u: Float, w: Float, c: Color, clear: Float, kind: Float, p1: Float, p2: Float, p3: Float, soft: Float, border: Float) {
        val a = v; val i = n
        a[i] = x; a[i + 1] = y; a[i + 2] = u; a[i + 3] = w
        a[i + 4] = c.r; a[i + 5] = c.g; a[i + 6] = c.b; a[i + 7] = clear
        a[i + 8] = kind; a[i + 9] = p1; a[i + 10] = p2; a[i + 11] = p3
        a[i + 12] = soft; a[i + 13] = border; a[i + 14] = 0f; a[i + 15] = 0f
        n += HUD_FLOATS
    }

    /** A plain rectangle in canvas units, its corners turned to clip space as the HUD always did (same pixels as before fonts). */
    fun rect(x: Float, y: Float, w: Float, h: Float, c: Color) {
        if (w <= 0f || h <= 0f || opacity <= 0f) return
        ensure(6 * HUD_FLOATS)
        val clear = 1f - opacity.coerceIn(0f, 1f)
        val x0 = x / (cw / 2) - 1f; val y0 = y / (ch / 2) - 1f; val x1 = (x + w) / (cw / 2) - 1f; val y1 = (y + h) / (ch / 2) - 1f
        vertex(x0, y0, 0f, 0f, c, clear, 0f, 0f, 0f, 0f, 0f, 0f); vertex(x1, y0, 0f, 0f, c, clear, 0f, 0f, 0f, 0f, 0f, 0f); vertex(x1, y1, 0f, 0f, c, clear, 0f, 0f, 0f, 0f, 0f, 0f)
        vertex(x0, y0, 0f, 0f, c, clear, 0f, 0f, 0f, 0f, 0f, 0f); vertex(x1, y1, 0f, 0f, c, clear, 0f, 0f, 0f, 0f, 0f, 0f); vertex(x0, y1, 0f, 0f, c, clear, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    /**
     * A rounded box in canvas units: [radius] at the corners, edges [soft] pixels wide (1: just anti-aliased,
     * more: a soft shadow), filled ([border] 0) or a ring [border] units thick inside its edge. [bottom] is the
     * colour at the bottom edge (a gentle gradient), the same as [c] by default.
     */
    fun box(x: Float, y: Float, w: Float, h: Float, c: Color, radius: Float = 0f, soft: Float = 1f, border: Float = 0f, bottom: Color = c) {
        if (w <= 0f || h <= 0f || opacity <= 0f) return
        ensure(6 * HUD_FLOATS)
        val clear = 1f - opacity.coerceIn(0f, 1f)
        val kk = k
        // In pixels, grown by the softness so that the fading edge is drawn whole.
        val grow = soft
        val px0 = x * kk - grow; val py0 = y * kk - grow; val px1 = (x + w) * kk + grow; val py1 = (y + h) * kk + grow
        val hw = w * kk / 2; val hh = h * kk / 2
        val r = min(radius * kk, min(hw, hh)); val b = border * kk
        val kind = if (border > 0f) 3f else 2f
        fun cx(px: Float) = px / (pw / 2) - 1f
        fun cy(py: Float) = py / (ph / 2) - 1f
        // Local coordinates: from the box's centre, in pixels.
        val lx0 = px0 - (x * kk + hw); val lx1 = px1 - (x * kk + hw); val ly0 = py0 - (y * kk + hh); val ly1 = py1 - (y * kk + hh)
        vertex(cx(px0), cy(py0), lx0, ly0, c, clear, kind, r, hw, hh, soft, b); vertex(cx(px1), cy(py0), lx1, ly0, c, clear, kind, r, hw, hh, soft, b)
        vertex(cx(px1), cy(py1), lx1, ly1, bottom, clear, kind, r, hw, hh, soft, b)
        vertex(cx(px0), cy(py0), lx0, ly0, c, clear, kind, r, hw, hh, soft, b); vertex(cx(px1), cy(py1), lx1, ly1, bottom, clear, kind, r, hw, hh, soft, b)
        vertex(cx(px0), cy(py1), lx0, ly1, bottom, clear, kind, r, hw, hh, soft, b)
    }

    /** A glyph quad in pixels, the atlas texels (u0, v0)–(u1, v1); [spread] > 0: its outline that many pixels wide. */
    private fun glyphQuad(x0in: Float, y0in: Float, x1in: Float, y1in: Float, u0in: Float, v0in: Float, c: Color, spread: Float) {
        var x0 = x0in; var y0 = y0in; var x1 = x1in; var y1 = y1in; var u0 = u0in; var v0 = v0in
        // Clipped to the clip rectangle (texels follow pixels one to one).
        if (x0 < clipX0) { u0 += clipX0 - x0; x0 = clipX0 }
        if (y0 < clipY0) { v0 += clipY0 - y0; y0 = clipY0 }
        if (x1 > clipX1) x1 = clipX1
        if (y1 > clipY1) y1 = clipY1
        if (x1 <= x0 || y1 <= y0) return
        val u1 = u0 + (x1 - x0); val v1 = v0 + (y1 - y0)
        ensure(6 * HUD_FLOATS)
        val clear = 1f - opacity.coerceIn(0f, 1f)
        val a = x0 / (pw / 2) - 1f; val b = y0 / (ph / 2) - 1f; val cc = x1 / (pw / 2) - 1f; val d = y1 / (ph / 2) - 1f
        vertex(a, b, u0, v0, c, clear, 1f, spread, 0f, 0f, 0f, 0f); vertex(cc, b, u1, v0, c, clear, 1f, spread, 0f, 0f, 0f, 0f); vertex(cc, d, u1, v1, c, clear, 1f, spread, 0f, 0f, 0f, 0f)
        vertex(a, b, u0, v0, c, clear, 1f, spread, 0f, 0f, 0f, 0f); vertex(cc, d, u1, v1, c, clear, 1f, spread, 0f, 0f, 0f, 0f); vertex(a, d, u0, v1, c, clear, 1f, spread, 0f, 0f, 0f, 0f)
    }

    /**
     * A laid-out text with its top left at ([x], [y]) canvas units: the pixel font as rectangles (as always),
     * a system font as glyphs from the atlas, their pens on whole pixels vertically and quarter pixels across.
     * [spread] (canvas units) draws the glyphs' outline instead of the glyphs; [dx], [dy] move everything
     * (shadows). Returns false if the atlas was emptied meanwhile (the HUD must be drawn again).
     */
    fun text(t: LaidText, x: Float, y: Float, size: Float, c: Color, spread: Float = 0f, dx: Float = 0f, dy: Float = 0f): Boolean {
        val generation = atlas.generation
        val kk = k
        for (g in 0 until t.count) {
            val f = t.fonts[g]
            if (f != null && f.face == null) {
                // The pixel font: one rectangle per run of lit pixels in a row, at x + (cell + column) × size as
                // always (an outline grows each by the spread).
                val runs = PixelGlyphs.runs(t.codes[g].toChar()) ?: continue
                val ox = x + dx; val oy = y + t.tops[g] + dy
                for (row in runs.indices) {
                    val r = runs[row]
                    var i = 0
                    while (i < r.size) {
                        rect(ox + (t.xs[g] + r[i]) * size - spread, oy + row * size - spread, (r[i + 1] - r[i]) * size + 2 * spread, size + 2 * spread, c)
                        i += 2
                    }
                }
                continue
            }
            val gx = x + t.xs[g] * t.unit + dx; val gy = y + t.tops[g] + dy
            if (f == null) { missing(gx, gy, t, c, spread); continue }
            val emPx = t.em * kk
            val penX = gx * kk; val base = (gy + t.cap) * kk
            val whole = floor(penX); val phase = ((penX - whole) * 4f).toInt().coerceIn(0, 3)
            val e = atlas.glyph(f, t.codes[g], emPx, phase)
            if (atlas.generation != generation) return false
            if (e.w == 0) continue
            val bx = whole + e.left; val by = base.roundToInt() + e.top.toFloat()
            glyphQuad(bx, by, bx + e.w, by + e.h, e.x.toFloat(), e.y.toFloat(), c, spread * kk)
        }
        return true
    }

    /** An empty box for a character no font has: as wide as a narrow letter, as tall as the capitals. */
    private fun missing(x: Float, y: Float, t: LaidText, c: Color, spread: Float) {
        val w = .5f * t.em; val h = t.cap; val line = max(.06f * t.em, 1f / k)
        val sx = x + .05f * t.em - spread; val sy = y - spread; val sw = w + 2 * spread; val sh = h + 2 * spread
        rect(sx, sy, sw, line + 2 * spread, c); rect(sx, sy + sh - line - 2 * spread, sw, line + 2 * spread, c)
        rect(sx, sy, line + 2 * spread, sh, c); rect(sx + sw - line - 2 * spread, sy, line + 2 * spread, sh, c)
    }

    fun take(): FloatArray = v.copyOf(n)
}

/** Draws a laid-out text with its options: shadow first, then the outline, then the letters. */
internal fun Painter.styledText(t: LaidText, x: Float, y: Float, size: Float, color: Color, shadow: Color?, outline: Color?, outlineWidth: Float, font: Font): Boolean {
    val saved = opacity
    if (shadow != null) {
        // A soft offset copy: a little below and to the right, as a light from above would cast.
        val off = if (font.isPixel) 2f * max(1f, size / 5f) else .09f * 7f * size
        opacity = saved * .45f
        if (!text(t, x, y, size, shadow, if (font.isPixel) 0f else .04f * 7f * size, off, off)) { opacity = saved; return false }
        opacity = saved
    }
    if (outline != null && outlineWidth > 0f) { if (!text(t, x, y, size, outline, outlineWidth)) return false }
    return text(t, x, y, size, color)
}
