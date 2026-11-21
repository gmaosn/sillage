package sillage.ui

import sillage.Font
import sillage.PixelFont
import sillage.io.FontFace
import sillage.io.FontRaster
import sillage.io.GlyphPath
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A text laid out in lines: each glyph's font, its glyph (or character for the pixel font), its pen
 * position from the block's top left in canvas units. A text's [size] is its scale: capitals are 7 × size
 * units high whatever the font (the pixel font's pixel is [size] units), so a game can change fonts without
 * moving anything. The arrays are reused from one layout to the next.
 */
internal class LaidText {
    var count = 0
    var fonts = arrayOfNulls<Font>(32)
    /** Glyph index for a system font, the character for the pixel font, −1 for a missing character (a box). */
    var codes = IntArray(32)
    var xs = FloatArray(32)
    /** Top of the line (the capitals' top) the glyph sits on. */
    var tops = FloatArray(32)
    var width = 0f; var height = 0f
    var lines = 0
    var lineHeight = 0f
    /** Height of the capitals, and the font's em, in canvas units. */
    var cap = 0f; var em = 0f
    /** Canvas units per unit of [xs]: the size for the pixel font (laid out in cells), 1 otherwise. */
    var unit = 1f
    /** Per line, its first glyph and its width (in the units of [xs]). */
    var lineStarts = IntArray(4); var lineWidths = FloatArray(4)
    /** Per character of the source text (UTF-16 index), the pen x before it (canvas units) and its line: for carets. */
    var caretX = FloatArray(32); var caretLine = IntArray(32); var caretCount = 0

    fun clear() { count = 0; width = 0f; height = 0f; lines = 0; caretCount = 0 }

    fun add(font: Font?, code: Int, x: Float, top: Float) {
        if (count == codes.size) {
            val n = count * 2
            fonts = fonts.copyOf(n); codes = codes.copyOf(n); xs = xs.copyOf(n); tops = tops.copyOf(n)
        }
        fonts[count] = font; codes[count] = code; xs[count] = x; tops[count] = top; count++
    }

    fun caret(x: Float, line: Int) {
        if (caretCount == caretX.size) { caretX = caretX.copyOf(caretCount * 2); caretLine = caretLine.copyOf(caretCount * 2) }
        caretX[caretCount] = x; caretLine[caretCount] = line; caretCount++
    }
}

internal object TextLayout {
    /** Canvas units per font unit of [font] at [size]. */
    fun unitScale(font: Font, size: Float): Float = 7f * size / font.capUnits

    fun lineHeight(font: Font, size: Float): Float {
        val f = font.face ?: return 11f * size
        return (f.ascent - f.descent + f.lineGap) * unitScale(font, size)
    }

    /** Width of one line of [text] (no wrapping). */
    fun measure(text: String, font: Font, size: Float): Float {
        val t = scratch.get(); layout(text, font, size, 0f, 0f, t); return t.width
    }
    private val scratch = ThreadLocal.withInitial { LaidText() }

    /** The font that draws [c]: [font], else its first fallback that has it, else null (a box). */
    fun fontFor(font: Font, c: Int): Font? {
        if (font.face == null) return font
        if (font.face.glyph(c) != 0) return font
        for (f in font.fallbacks) if (f.face != null && f.face.glyph(c) != 0) return f
        return null
    }

    // Per character of a paragraph: its font, code, advance, and whether a line may break after it.
    private class Chars { var n = 0; var fonts = arrayOfNulls<Font>(64); var codes = IntArray(64); var adv = FloatArray(64); var space = BooleanArray(64); var units = IntArray(64)
        fun grow() { val k = n * 2; fonts = fonts.copyOf(k); codes = codes.copyOf(k); adv = adv.copyOf(k); space = space.copyOf(k); units = units.copyOf(k) } }
    private val chars = ThreadLocal.withInitial { Chars() }

    /**
     * Lays out [text] at [size] in [font]: lines broken at '\n' and, when [wrap] > 0, between words to stay
     * within [wrap] units (a word longer than that is cut). [align]: 0 left, .5 centred, 1 right. The pixel
     * font is laid out in whole cells (6 per character, [LaidText.unit] = size), so that its rectangles fall
     * exactly where they always did.
     */
    fun layout(text: String, font: Font, size: Float, wrap: Float, align: Float, out: LaidText) {
        out.clear()
        val pixel = font.face == null
        val unit = if (pixel) size else 1f
        out.unit = unit
        out.cap = 7f * size
        out.em = font.face?.let { it.unitsPerEm * unitScale(font, size) } ?: (7f * size)
        val lh = lineHeight(font, size)
        out.lineHeight = lh
        val limit = if (wrap > 0f) wrap / unit else 0f
        val c = chars.get()
        var line = 0; var widest = 0f
        var i = 0
        while (true) {
            // One paragraph: up to '\n' or the end.
            var end = text.indexOf('\n', i); if (end < 0) end = text.length
            c.n = 0
            var k = i
            var prev: Font? = null; var prevGid = -1
            while (k < end) {
                val cp = text.codePointAt(k); val len = Character.charCount(cp)
                if (c.n == c.codes.size) c.grow()
                val f = fontFor(font, cp)
                val code: Int; val a: Float
                if (pixel) { code = cp; a = 6f }
                else if (f == null) { code = -1; a = .6f * out.em }
                else {
                    val face = f.face!!
                    code = face.glyph(cp)
                    val fu = out.em / face.unitsPerEm
                    a = face.advance(code) * fu
                    if (prev === f && prevGid >= 0 && c.n > 0) c.adv[c.n - 1] += face.kerning(prevGid, code) * fu
                }
                c.fonts[c.n] = f; c.codes[c.n] = code; c.adv[c.n] = a; c.space[c.n] = cp == ' '.code || cp == '\t'.code; c.units[c.n] = len
                c.n++
                prev = f; prevGid = if (f != null && !pixel) code else -1
                k += len
            }
            // The lines of this paragraph.
            var s = 0
            do {
                var e = s; var w = 0f; var lastBreak = -1; var wrapped = false
                while (e < c.n) {
                    val nw = w + c.adv[e]
                    if (limit > 0f && nw - (if (pixel) 1f else 0f) > limit && e > s) {
                        wrapped = true
                        if (c.space[e]) { e++; break }                    // the space that overflows goes with the break
                        if (lastBreak >= s) e = lastBreak + 1
                        break
                    }
                    if (c.space[e]) lastBreak = e
                    w = nw; e++
                }
                // Its width: without the spaces where it was broken, without the pixel font's last gap.
                var last = e - 1
                if (wrapped) while (last >= s && c.space[last]) last--
                var lw = 0f
                for (j in s..last) lw += c.adv[j]
                if (pixel && last >= s) lw -= 1f
                if (line == out.lineStarts.size) { out.lineStarts = out.lineStarts.copyOf(line * 2); out.lineWidths = out.lineWidths.copyOf(line * 2) }
                out.lineStarts[line] = out.count; out.lineWidths[line] = lw
                widest = max(widest, lw)
                var x = 0f
                val top = line * lh
                for (j in s until e) {
                    for (q in 0 until c.units[j]) out.caret(x, line)
                    if (!c.space[j]) out.add(c.fonts[j], c.codes[j], x, top)
                    x += c.adv[j]
                }
                if (e >= c.n) out.caret(x, line)                 // after the paragraph's last character
                line++
                s = e
            } while (s < c.n)
            if (end >= text.length) break
            i = end + 1
        }
        out.lines = line
        out.width = widest * unit
        out.height = (line - 1) * lh + out.cap
        if (align > 0f) for (l in 0 until line) {
            val from = out.lineStarts[l]; val to = if (l + 1 < line) out.lineStarts[l + 1] else out.count
            val dx = (widest - out.lineWidths[l]) * align
            for (g in from until to) out.xs[g] += dx
            for (q in 0 until out.caretCount) if (out.caretLine[q] == l) out.caretX[q] += dx
        }
        if (unit != 1f) for (q in 0 until out.caretCount) out.caretX[q] *= unit
    }
}

/**
 * The glyphs drawn so far, in one 8-bit texture: rasterised once per font, glyph, size (to a quarter of a
 * pixel) and horizontal phase (a quarter of a pixel), packed in shelves. When it is full it doubles, up to
 * 4096 × 4096, then starts again empty (the HUD is then rebuilt). The renderer sends the rows changed since
 * it last looked.
 */
internal class GlyphAtlas(initial: Int = 512, private val limit: Int = 4096) {
    var width = initial; private set
    var height = initial; private set
    /** The texels, row by row; empty until the first glyph (a HUD in the pixel font never needs them). */
    var pixels = ByteArray(0); private set
    /** Bumped whenever pixels change; the rows changed since the renderer last took them. */
    var version = 0; private set
    var dirtyTop = Int.MAX_VALUE; var dirtyBottom = 0
    /** Bumped when the atlas is emptied: every glyph placed before must be placed again. */
    var generation = 0; private set
    /** Glyphs rasterised since the start (for checks). */
    var rasterised = 0; private set

    class Entry(@JvmField val x: Int, @JvmField val y: Int, @JvmField val w: Int, @JvmField val h: Int,
                /** Offset of the bitmap's top left from the pen (pixels, y down). */
                @JvmField val left: Int, @JvmField val top: Int)

    private val entries = HashMap<Long, Entry>()
    private var shelfX = PAD_EDGE; private var shelfY = PAD_EDGE; private var shelfH = 0
    private val raster = FontRaster()
    private val path = GlyphPath()

    /** The glyph [gid] of [face] at [emPx] pixels per em, its pen [phase] quarters of a pixel to the right. */
    fun glyph(font: Font, gid: Int, emPx: Float, phase: Int): Entry {
        val face = font.face!!
        val sizeKey = (emPx * 4f).roundToInt().coerceIn(1, (1 shl 18) - 1)
        val key = (font.id.toLong() shl 40) or (gid.toLong() and 0xFFFFF shl 20) or (sizeKey.toLong() shl 2) or phase.toLong()
        entries[key]?.let { return it }
        val e = rasterise(face, gid, sizeKey / 4f, phase)
        entries[key] = e
        return e
    }

    private fun rasterise(face: FontFace, gid: Int, emPx: Float, phase: Int): Entry {
        if (pixels.isEmpty()) pixels = ByteArray(width * height)
        path.count = 0; path.size = 0
        face.outline(gid, path)
        rasterised++
        if (path.isEmpty) return Entry(0, 0, 0, 0, 0, 0)
        val s = emPx / face.unitsPerEm
        val dx = phase / 4f
        val b = path.controlBox()
        val left = floor(b[0] * s + dx).toInt() - PAD; val right = ceil(b[2] * s + dx).toInt() + PAD
        val top = ceil(b[3] * s).toInt() + PAD; val bottom = floor(b[1] * s).toInt() - PAD
        val w = right - left; val h = top - bottom
        if (w > width - 2 * PAD_EDGE || h > height - 2 * PAD_EDGE) {
            if (!grow()) return Entry(0, 0, 0, 0, 0, 0)
            return rasterise(face, gid, emPx, phase).also { rasterised-- }
        }
        if (shelfX + w + PAD_EDGE > width) { shelfY += shelfH + PAD_EDGE; shelfX = PAD_EDGE; shelfH = 0 }
        if (shelfY + h + PAD_EDGE > height) {
            if (!grow()) { clear(); }
            return rasterise(face, gid, emPx, phase).also { rasterised-- }
        }
        val x = shelfX; val y = shelfY
        raster.draw(path, s, dx - left, top.toFloat(), w, h, pixels, y * width + x, width)
        shelfX += w + PAD_EDGE; shelfH = max(shelfH, h)
        dirtyTop = minOf(dirtyTop, y); dirtyBottom = max(dirtyBottom, y + h)
        version++
        return Entry(x, y, w, h, left, -top)
    }

    /** Doubles the atlas (the glyphs keep their places); false at the limit. */
    private fun grow(): Boolean {
        if (width >= limit) return false
        val nw = width * 2; val nh = height * 2
        val np = ByteArray(nw * nh)
        if (pixels.isNotEmpty()) for (row in 0 until height) System.arraycopy(pixels, row * width, np, row * nw, width)
        pixels = np; width = nw; height = nh
        dirtyTop = 0; dirtyBottom = height
        version++
        return true
    }

    /** Empties the atlas: what the HUD showed must be placed again. */
    fun clear() {
        entries.clear(); java.util.Arrays.fill(pixels, 0)
        shelfX = PAD_EDGE; shelfY = PAD_EDGE; shelfH = 0
        dirtyTop = 0; dirtyBottom = height
        version++; generation++
    }

    /** Times the rows changed were taken for the GPU. */
    var uploads = 0; private set

    /** The rows changed since the last call, as (top, bottom); empty if none. */
    fun takeDirty(): IntArray {
        val r = if (dirtyTop >= dirtyBottom) intArrayOf(0, 0) else intArrayOf(dirtyTop, dirtyBottom).also { uploads++ }
        dirtyTop = Int.MAX_VALUE; dirtyBottom = 0
        return r
    }

    companion object {
        /** Empty pixels around each glyph: room for outlines and shadows drawn by the shader. */
        const val PAD = 5
        private const val PAD_EDGE = 1
    }
}

/** The pixel font's glyph rows, kept here so the painter reads them without allocating. */
internal object PixelGlyphs {
    private val cache = HashMap<Char, Array<IntArray>>()
    /** Per row of [c], the runs of lit pixels as (start, end) pairs; null for a space. */
    fun runs(c: Char): Array<IntArray>? {
        cache[c]?.let { return it }
        val g = PixelFont.glyph(c) ?: return null
        val rows = Array(g.size) { r ->
            val line = g[r]; val list = ArrayList<Int>()
            var col = 0
            while (col < line.length) {
                if (line[col] != '1') { col++; continue }
                var end = col
                while (end < line.length && line[end] == '1') end++
                list.add(col); list.add(end); col = end
            }
            list.toIntArray()
        }
        cache[c] = rows
        return rows
    }
}
