package sillage


/**
 * What is drawn over the scene: texts and panels on a canvas 600 units high (its width follows the
 * window's shape), each placed from an [Anchor] — a corner, an edge's middle or the centre. Texts can
 * be fixed or computed every frame: `hud.text({ "SCORE $score" }, 24, 24)`. The HUD is rebuilt only
 * when something it shows changes.
 */
class Hud internal constructor() {
    enum class Anchor { TOP_LEFT, TOP, TOP_RIGHT, LEFT, CENTER, RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT }
    /** How a text sits on its anchor point; [AUTO] follows the anchor (left for the left side…). */
    enum class Align { AUTO, LEFT, CENTER, RIGHT }

    /**
     * Something on the HUD; hide it with [visible] or take it away with [Hud.remove]. [opacity] lets the
     * scene show through (1: solid, 0: invisible), for panels that do not hide the game or a text that fades.
     */
    sealed class Element(var x: Float, var y: Float, var anchor: Anchor) { var visible = true; var opacity = 1f }
    /** A line of text; [size] is the height of a pixel of the font (a letter is 7 of them high). */
    class Text internal constructor(var value: () -> String, x: Float, y: Float, var size: Float, var color: Color, anchor: Anchor, var align: Align) : Element(x, y, anchor)
    class Panel internal constructor(x: Float, y: Float, var width: Float, var height: Float, var color: Color, anchor: Anchor) : Element(x, y, anchor)

    private val elements = ArrayList<Element>()

    /** Shows the frames per second in the top right corner. */
    var showFps = false
    /** Hint shown while the mouse is free (null: none). */
    var mouseHint: String? = "TAB TO CAPTURE THE MOUSE"
    /** Colours of the built-in hint panel and its text. */
    var hintPanelColor: Color = Color.hex(0x153D48)
    var hintTextColor: Color = Color.hex(0xFFEDBE)

    fun text(value: String, x: Number, y: Number, size: Number = 2, color: Color = Color.WHITE, anchor: Anchor = Anchor.TOP_LEFT, align: Align = Align.AUTO): Text =
        text({ value }, x, y, size, color, anchor, align)

    fun text(value: () -> String, x: Number, y: Number, size: Number = 2, color: Color = Color.WHITE, anchor: Anchor = Anchor.TOP_LEFT, align: Align = Align.AUTO): Text =
        Text(value, x.toFloat(), y.toFloat(), size.toFloat(), color, anchor, align).also { elements.add(it) }

    fun panel(x: Number, y: Number, width: Number, height: Number, color: Color = Color.hex(0x153D48), anchor: Anchor = Anchor.TOP_LEFT,
              opacity: Number = 1): Panel =
        Panel(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), color, anchor).also { it.opacity = opacity.toFloat(); elements.add(it) }

    fun remove(element: Element) { elements.remove(element) }
    fun clear() = elements.clear()

    private var last: Mesh? = null
    // What the last mesh showed, to see without allocating whether anything changed.
    private var shownAspect = 0f; private var shownFps = 0; private var shownCaptured = false; private var shownHint: String? = null
    private var shownElements = 0
    private var shownNumbers = FloatArray(0)        // per element: x, y, visible, anchor, size or width, height, align, opacity
    private var shownThings = arrayOfNulls<Any>(0)  // per element: text, colour

    /** The HUD as an unlit mesh in clip space, rebuilt only when what it shows changed. */
    internal fun mesh(aspect: Float, fps: Int, mouseCaptured: Boolean): Mesh {
        val n = elements.size
        val shownFpsNow = if (showFps) fps else -1
        var changed = last == null || aspect != shownAspect || shownFpsNow != shownFps || mouseCaptured != shownCaptured ||
            mouseHint != shownHint || n != shownElements
        if (shownNumbers.size < 8 * n) { shownNumbers = FloatArray(16 * n); shownThings = arrayOfNulls(4 * n); changed = true }
        for (i in 0 until n) {
            val e = elements[i]; val f = 8 * i; val o = 2 * i
            val text = if (e is Text && e.visible) e.value() else null
            val a = e.x; val b = e.y; val c = if (e.visible) 1f else 0f; val d = e.anchor.ordinal.toFloat()
            val g = when (e) { is Text -> e.size; is Panel -> e.width }
            val h = when (e) { is Text -> 0f; is Panel -> e.height }
            val k = when (e) { is Text -> e.align.ordinal.toFloat(); is Panel -> 0f }
            val color = when (e) { is Text -> e.color; is Panel -> e.color }
            val nums = shownNumbers
            val op = e.opacity
            if (nums[f] != a || nums[f + 1] != b || nums[f + 2] != c || nums[f + 3] != d || nums[f + 4] != g || nums[f + 5] != h || nums[f + 6] != k ||
                nums[f + 7] != op || shownThings[o] != text || shownThings[o + 1] != color) {
                changed = true
                nums[f] = a; nums[f + 1] = b; nums[f + 2] = c; nums[f + 3] = d; nums[f + 4] = g; nums[f + 5] = h; nums[f + 6] = k; nums[f + 7] = op
                shownThings[o] = text; shownThings[o + 1] = color
            }
        }
        if (!changed) return last!!
        shownAspect = aspect; shownFps = shownFpsNow; shownCaptured = mouseCaptured; shownHint = mouseHint; shownElements = n
        val texts = List(n) { shownThings[2 * it] as String? }
        return build(aspect, fps, mouseCaptured, texts).also { last = it }
    }

    private fun build(aspect: Float, fps: Int, mouseCaptured: Boolean, texts: List<String?>): Mesh {
        val w = 600f * aspect; val h = 600f
        // Two triangles per rectangle, in clip space. The normal carries the transparency (1 − opacity) in x:
        // the HUD's shader blends with it, and a zero normal elsewhere means unlit.
        var v = FloatArray(54 * 64); var k = 0
        fun vertex(px: Float, py: Float, c: Color, clear: Float) {
            if (k + 9 > v.size) v = v.copyOf(v.size * 2)
            v[k] = px / (w / 2) - 1f; v[k + 1] = py / (h / 2) - 1f; v[k + 2] = 0f
            v[k + 3] = clear; v[k + 4] = 0f; v[k + 5] = 0f; v[k + 6] = c.r; v[k + 7] = c.g; v[k + 8] = c.b
            k += 9
        }
        var opacity = 1f
        fun rect(x: Float, y: Float, rw: Float, rh: Float, c: Color) {
            if (rw <= 0f || rh <= 0f || opacity <= 0f) return
            val clear = 1f - opacity.coerceIn(0f, 1f)
            vertex(x, y, c, clear); vertex(x + rw, y, c, clear); vertex(x + rw, y + rh, c, clear)
            vertex(x, y, c, clear); vertex(x + rw, y + rh, c, clear); vertex(x, y + rh, c, clear)
        }
        fun place(e: Element, ew: Float, eh: Float): Pair<Float, Float> {
            val ax = when (e.anchor) { Anchor.TOP_LEFT, Anchor.LEFT, Anchor.BOTTOM_LEFT -> e.x
                Anchor.TOP, Anchor.CENTER, Anchor.BOTTOM -> w / 2 - ew / 2 + e.x
                else -> w - ew - e.x }
            val ay = when (e.anchor) { Anchor.TOP_LEFT, Anchor.TOP, Anchor.TOP_RIGHT -> e.y
                Anchor.LEFT, Anchor.CENTER, Anchor.RIGHT -> h / 2 - eh / 2 + e.y
                else -> h - eh - e.y }
            return ax to ay
        }
        fun text(t: String, x: Float, y: Float, size: Float, c: Color) {
            var i = 0
            for (ch in t) {
                val glyph = Font.glyph(ch)
                // One rectangle per run of lit pixels in a row of the glyph.
                if (glyph != null) for ((row, line) in glyph.withIndex()) {
                    var col = 0
                    while (col < line.length) {
                        if (line[col] != '1') { col++; continue }
                        var end = col
                        while (end < line.length && line[end] == '1') end++
                        rect(x + (i * 6 + col) * size, y + row * size, (end - col) * size, size, c)
                        col = end
                    }
                }
                i++
            }
        }
        for ((index, e) in elements.withIndex()) {
            if (!e.visible) continue
            opacity = e.opacity
            when (e) {
                is Panel -> { val (x, y) = place(e, e.width, e.height); rect(x, y, e.width, e.height, e.color) }
                is Text -> {
                    val t = texts[index] ?: continue; val tw = textWidth(t, e.size)
                    var (x, y) = place(e, tw, 7 * e.size)
                    if (e.align != Align.AUTO) {
                        // The anchor point itself, then the text placed left, centred or right of it.
                        val px = when (e.anchor) { Anchor.TOP_LEFT, Anchor.LEFT, Anchor.BOTTOM_LEFT -> e.x; Anchor.TOP, Anchor.CENTER, Anchor.BOTTOM -> w / 2 + e.x; else -> w - e.x }
                        x = when (e.align) { Align.LEFT -> px; Align.CENTER -> px - tw / 2; else -> px - tw }
                    }
                    text(t, x, y, e.size, e.color)
                }
            }
        }
        opacity = 1f
        if (showFps) { val t = "$fps FPS"; text(t, w - textWidth(t, 1.25f) - 24f, 24f, 1.25f, hintTextColor) }
        val hint = mouseHint
        if (!mouseCaptured && hint != null) {
            val tw = textWidth(hint, 1.5f)
            opacity = .85f
            rect(w / 2 - tw / 2 - 14f, h - 122f, tw + 28f, 26f, hintPanelColor)
            opacity = 1f
            text(hint, w / 2 - tw / 2, h - 114f, 1.5f, hintTextColor)
        }
        return Mesh(v.copyOf(k))
    }

    companion object {
        /** Width of [text] in canvas units at [size]. */
        fun textWidth(text: String, size: Number): Float = (text.length * 6 - 1).coerceAtLeast(0) * size.toFloat()
    }
}

/** A 5 × 7 pixel font: capitals, small letters, French accents, digits and common signs. */
internal object Font {
    fun glyph(c: Char): List<String>? = glyphs[c] ?: glyphs[c.uppercaseChar().let { u -> plain[u] ?: u }] ?: if (c == ' ') null else glyphs['?']

    // Capitals with accents are drawn plain (no room above a capital in 7 rows).
    // g, j, p, q and y have two more rows: their tails hang below the line, like in print.
    private val plain = mapOf('À' to 'A', 'Â' to 'A', 'Ä' to 'A', 'Ç' to 'C', 'É' to 'E', 'È' to 'E', 'Ê' to 'E', 'Ë' to 'E',
        'Î' to 'I', 'Ï' to 'I', 'Ô' to 'O', 'Ö' to 'O', 'Ù' to 'U', 'Û' to 'U', 'Ü' to 'U', 'Ÿ' to 'Y', 'Œ' to 'O')

    private val glyphs: Map<Char, List<String>> = mapOf(
        'A' to "01110/10001/10001/11111/10001/10001/10001", 'B' to "11110/10001/10001/11110/10001/10001/11110",
        'C' to "01111/10000/10000/10000/10000/10000/01111", 'D' to "11110/10001/10001/10001/10001/10001/11110",
        'E' to "11111/10000/10000/11110/10000/10000/11111", 'F' to "11111/10000/10000/11110/10000/10000/10000",
        'G' to "01111/10000/10000/10111/10001/10001/01111", 'H' to "10001/10001/10001/11111/10001/10001/10001",
        'I' to "11111/00100/00100/00100/00100/00100/11111", 'J' to "00111/00010/00010/00010/10010/10010/01100",
        'K' to "10001/10010/10100/11000/10100/10010/10001", 'L' to "10000/10000/10000/10000/10000/10000/11111",
        'M' to "10001/11011/10101/10101/10001/10001/10001", 'N' to "10001/11001/10101/10011/10001/10001/10001",
        'O' to "01110/10001/10001/10001/10001/10001/01110", 'P' to "11110/10001/10001/11110/10000/10000/10000",
        'Q' to "01110/10001/10001/10001/10101/10010/01101", 'R' to "11110/10001/10001/11110/10100/10010/10001",
        'S' to "01111/10000/10000/01110/00001/00001/11110", 'T' to "11111/00100/00100/00100/00100/00100/00100",
        'U' to "10001/10001/10001/10001/10001/10001/01110", 'V' to "10001/10001/10001/10001/10001/01010/00100",
        'W' to "10001/10001/10001/10101/10101/10101/01010", 'X' to "10001/10001/01010/00100/01010/10001/10001",
        'Y' to "10001/10001/01010/00100/00100/00100/00100", 'Z' to "11111/00001/00010/00100/01000/10000/11111",
        'a' to "00000/00000/01110/00001/01111/10001/01111", 'b' to "10000/10000/11110/10001/10001/10001/11110",
        'c' to "00000/00000/01111/10000/10000/10000/01111", 'd' to "00001/00001/01111/10001/10001/10001/01111",
        'e' to "00000/00000/01110/10001/11111/10000/01111", 'f' to "00110/01001/01000/11100/01000/01000/01000",
        'g' to "00000/00000/01111/10001/10001/10001/01111/00001/01110", 'h' to "10000/10000/11110/10001/10001/10001/10001",
        'i' to "00100/00000/01100/00100/00100/00100/01110", 'j' to "00010/00000/00110/00010/00010/00010/00010/10010/01100",
        'k' to "10000/10000/10010/10100/11000/10100/10010", 'l' to "01100/00100/00100/00100/00100/00100/01110",
        'm' to "00000/00000/11010/10101/10101/10101/10101", 'n' to "00000/00000/11110/10001/10001/10001/10001",
        'o' to "00000/00000/01110/10001/10001/10001/01110", 'p' to "00000/00000/11110/10001/10001/10001/11110/10000/10000",
        'q' to "00000/00000/01111/10001/10001/10001/01111/00001/00001", 'r' to "00000/00000/10111/11000/10000/10000/10000",
        's' to "00000/00000/01111/10000/01110/00001/11110", 't' to "01000/01000/11100/01000/01000/01001/00110",
        'u' to "00000/00000/10001/10001/10001/10011/01101", 'v' to "00000/00000/10001/10001/10001/01010/00100",
        'w' to "00000/00000/10001/10001/10101/10101/01010", 'x' to "00000/00000/10001/01010/00100/01010/10001",
        'y' to "00000/00000/10001/10001/10001/10001/01111/00001/01110", 'z' to "00000/00000/11111/00010/00100/01000/11111",
        'é' to "00010/00100/01110/10001/11111/10000/01111", 'è' to "01000/00100/01110/10001/11111/10000/01111",
        'ê' to "00100/01010/01110/10001/11111/10000/01111", 'ë' to "01010/00000/01110/10001/11111/10000/01111",
        'à' to "01000/00100/01110/00001/01111/10001/01111", 'â' to "00100/01010/01110/00001/01111/10001/01111",
        'ä' to "01010/00000/01110/00001/01111/10001/01111", 'ç' to "00000/01111/10000/10000/01111/00100/01100",
        'î' to "00100/01010/00000/01100/00100/00100/01110", 'ï' to "01010/00000/01100/00100/00100/00100/01110",
        'ô' to "00100/01010/01110/10001/10001/10001/01110", 'ö' to "01010/00000/01110/10001/10001/10001/01110",
        'ù' to "01000/00100/10001/10001/10001/10011/01101", 'û' to "00100/01010/10001/10001/10001/10011/01101",
        'ü' to "01010/00000/10001/10001/10001/10011/01101", 'ÿ' to "01010/00000/10001/10001/01111/00001/01110",
        'œ' to "00000/00000/01010/10101/10111/10100/01011",
        '0' to "01110/10001/10011/10101/11001/10001/01110", '1' to "00100/01100/00100/00100/00100/00100/01110",
        '2' to "01110/10001/00001/00010/00100/01000/11111", '3' to "11110/00001/00001/01110/00001/00001/11110",
        '4' to "00010/00110/01010/10010/11111/00010/00010", '5' to "11111/10000/10000/11110/00001/00001/11110",
        '6' to "01110/10000/10000/11110/10001/10001/01110", '7' to "11111/00001/00010/00100/01000/01000/01000",
        '8' to "01110/10001/10001/01110/10001/10001/01110", '9' to "01110/10001/10001/01111/00001/00001/01110",
        '/' to "00001/00010/00010/00100/01000/01000/10000", '-' to "00000/00000/00000/11111/00000/00000/00000",
        '–' to "00000/00000/00000/11111/00000/00000/00000", '—' to "00000/00000/00000/11111/00000/00000/00000",
        ':' to "00000/00100/00100/00000/00100/00100/00000", '.' to "00000/00000/00000/00000/00000/00100/00100",
        ',' to "00000/00000/00000/00000/00110/00100/01000", ';' to "00000/00100/00100/00000/00100/00100/01000",
        '!' to "00100/00100/00100/00100/00100/00000/00100", '?' to "01110/10001/00001/00010/00100/00000/00100",
        '\'' to "00100/00100/01000/00000/00000/00000/00000", '’' to "00100/00100/01000/00000/00000/00000/00000",
        '"' to "01010/01010/00000/00000/00000/00000/00000", '(' to "00010/00100/01000/01000/01000/00100/00010",
        ')' to "01000/00100/00010/00010/00010/00100/01000", '[' to "01110/01000/01000/01000/01000/01000/01110",
        ']' to "01110/00010/00010/00010/00010/00010/01110", '+' to "00000/00100/00100/11111/00100/00100/00000",
        '=' to "00000/00000/11111/00000/11111/00000/00000", '%' to "11001/11010/00010/00100/01000/01011/10011",
        '_' to "00000/00000/00000/00000/00000/00000/11111", '<' to "00010/00100/01000/10000/01000/00100/00010",
        '>' to "01000/00100/00010/00001/00010/00100/01000", '#' to "01010/01010/11111/01010/11111/01010/01010",
        '&' to "01100/10010/10100/01000/10101/10010/01101", '*' to "00000/10101/01110/11111/01110/10101/00000",
        '«' to "00000/00101/01010/10100/01010/00101/00000", '»' to "00000/10100/01010/00101/01010/10100/00000",
        '°' to "01100/10010/10010/01100/00000/00000/00000", '×' to "00000/10001/01010/00100/01010/10001/00000",
    ).mapValues { it.value.split('/') }
}
