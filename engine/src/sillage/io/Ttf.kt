package sillage.io


/**
 * An outline as drawing commands, in font units (y up) once read, or in pixels (y down) once placed:
 * [MOVE] x y, [LINE] x y, [QUAD] cx cy x y, [CUBIC] c1x c1y c2x c2y x y. A move closes the contour before it.
 */
internal class GlyphPath {
    var commands = ByteArray(32); var count = 0
    var coords = FloatArray(128); var size = 0

    private fun add(c: Int, n: Int) {
        if (count == commands.size) commands = commands.copyOf(count * 2)
        if (size + n > coords.size) coords = coords.copyOf(maxOf(coords.size * 2, size + n))
        commands[count++] = c.toByte()
    }
    fun moveTo(x: Float, y: Float) { add(MOVE, 2); coords[size++] = x; coords[size++] = y }
    fun lineTo(x: Float, y: Float) { add(LINE, 2); coords[size++] = x; coords[size++] = y }
    fun quadTo(cx: Float, cy: Float, x: Float, y: Float) { add(QUAD, 4); coords[size++] = cx; coords[size++] = cy; coords[size++] = x; coords[size++] = y }
    fun cubicTo(ax: Float, ay: Float, bx: Float, by: Float, x: Float, y: Float) {
        add(CUBIC, 6); coords[size++] = ax; coords[size++] = ay; coords[size++] = bx; coords[size++] = by; coords[size++] = x; coords[size++] = y
    }
    val isEmpty get() = count == 0

    /** The box of every point, control points included (as FreeType's control box): xMin, yMin, xMax, yMax. */
    fun controlBox(): FloatArray {
        if (size == 0) return floatArrayOf(0f, 0f, 0f, 0f)
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        var i = 0
        while (i < size) { val x = coords[i]; val y = coords[i + 1]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; i += 2 }
        return floatArrayOf(x0, y0, x1, y1)
    }

    companion object { const val MOVE = 0; const val LINE = 1; const val QUAD = 2; const val CUBIC = 3 }
}

/** Big-endian reads in a font file. */
internal class FontBytes(val b: ByteArray) {
    fun u8(o: Int) = b[o].toInt() and 255
    fun u16(o: Int) = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)
    fun i16(o: Int) = (((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)).toShort().toInt()
    fun u32(o: Int) = ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)
    fun tag(o: Int) = latin1(b, o, 4)
}

/** What the font finder needs from a file without reading it whole: its faces' names and styles. */
internal class FaceInfo(val path: String, val index: Int, val family: String, val subfamily: String, val weight: Int,
                        val italic: Boolean, val variable: Boolean, val outlines: Boolean)

/**
 * A face of a TrueType or OpenType font file (a collection's [index]-th): metrics, the character map,
 * outlines (quadratic `glyf` with composite glyphs, or cubic CFF), names, and pair kerning from GPOS
 * (pair adjustment, formats 1 and 2, under the `kern` feature) or the old `kern` table. No shaping:
 * one character, one glyph.
 */
internal class FontFace private constructor(val bytes: ByteArray, val index: Int) {
    private val d = FontBytes(bytes)
    private val tables = HashMap<String, Int>()
    private val lengths = HashMap<String, Int>()
    val unitsPerEm: Int
    val ascent: Int; val descent: Int; val lineGap: Int
    val glyphCount: Int
    /** Height of capitals and of small letters (OS/2), 0 when the font does not say. */
    val capHeight: Int; val xHeight: Int
    val weight: Int
    val italic: Boolean
    val family: String; val subfamily: String
    /** The file's checksum adjustment (head): tells one version of a font from another. */
    val checksum: Int
    private val hmetrics: Int
    private val longLoca: Boolean
    private var cmapFormat = -1; private var cmapAt = 0; private var symbol = false
    private val cff: Cff?
    private var gposLookups: IntArray? = null
    private var kernPairs = 0; private var kernAt = 0
    val variable: Boolean

    init {
        var o = 0
        if (d.tag(0) == "ttcf") {
            val n = d.u32(8)
            require(index in 0 until n) { "No face $index in this collection ($n faces)" }
            o = d.u32(12 + 4 * index)
        } else require(index == 0) { "Not a font collection" }
        val version = d.u32(o)
        require(version == 0x00010000 || version == 0x4F54544F || version == 0x74727565) { "Not a TrueType or OpenType font" }
        val n = d.u16(o + 4)
        for (i in 0 until n) {
            val r = o + 12 + 16 * i
            val at = d.u32(r + 8); val len = d.u32(r + 12)
            require(at >= 0 && len >= 0 && at.toLong() + len <= bytes.size) { "Table ${d.tag(r)} outside the file" }
            tables[d.tag(r)] = at; lengths[d.tag(r)] = len
        }
        for (t in listOf("head", "hhea", "hmtx", "maxp", "cmap")) require(t in tables) { "Font without a $t table" }
        val head = tables.getValue("head")
        unitsPerEm = d.u16(head + 18).also { require(it in 16..16384) { "unitsPerEm $it" } }
        checksum = d.u32(head + 8)
        longLoca = d.i16(head + 50) == 1
        val hhea = tables.getValue("hhea")
        glyphCount = d.u16(tables.getValue("maxp") + 4)
        hmetrics = d.u16(hhea + 34).coerceIn(1, maxOf(1, glyphCount))
        val os2 = tables["OS/2"]
        var asc = d.i16(hhea + 4); var desc = d.i16(hhea + 6); var gap = d.i16(hhea + 8)
        // Fonts that ask for their typographic metrics (OS/2 fsSelection bit 7) get them.
        if (os2 != null && d.u16(os2 + 62) and 0x80 != 0) { asc = d.i16(os2 + 68); desc = d.i16(os2 + 70); gap = d.i16(os2 + 72) }
        if (asc == 0 && desc == 0 && os2 != null) { asc = d.u16(os2 + 74); desc = -d.u16(os2 + 76) }
        ascent = asc; descent = desc; lineGap = gap
        weight = if (os2 != null) d.u16(os2 + 4) else 400
        val version2 = os2 != null && d.u16(os2) >= 2 && lengths.getValue("OS/2") >= 90
        capHeight = if (version2) d.i16(os2!! + 88) else 0
        xHeight = if (version2) d.i16(os2!! + 86) else 0
        italic = (os2 != null && d.u16(os2 + 62) and 1 != 0) || d.u16(head + 44) and 2 != 0
        val names = readNames(d, tables["name"] ?: -1)
        family = names.first; subfamily = names.second
        cff = tables["CFF "]?.let { Cff(bytes, it, lengths.getValue("CFF "), unitsPerEm) }
        require(cff != null || ("glyf" in tables && "loca" in tables)) { "Font without outlines this reader knows (glyf or CFF; CFF2 is not read)" }
        variable = "fvar" in tables
        readCmap()
        readKerning()
    }

    // ------------------------------------------------------------------ characters and metrics

    private fun readCmap() {
        val cmap = tables.getValue("cmap")
        val n = d.u16(cmap + 2)
        var best = -1; var bestAt = 0; var bestFormat = 0
        for (i in 0 until n) {
            val p = d.u16(cmap + 4 + 8 * i); val e = d.u16(cmap + 6 + 8 * i); val at = cmap + d.u32(cmap + 8 + 8 * i)
            if (at + 4 > bytes.size) continue
            val format = d.u16(at)
            val score = when {
                format == 12 && (p == 3 && e == 10 || p == 0 && (e == 4 || e == 6)) -> 5
                format == 4 && (p == 3 && e == 1 || p == 0) -> 4
                format == 4 && p == 3 && e == 0 -> 2
                else -> -1
            }
            if (score > best) { best = score; bestAt = at; bestFormat = format; symbol = p == 3 && e == 0 }
        }
        if (best >= 0) { cmapFormat = bestFormat; cmapAt = bestAt }
    }

    /** The glyph of character [c] (a code point), 0 if the font has none. */
    fun glyph(c: Int): Int {
        val g = lookup(c)
        // Symbol fonts put their characters at U+F000 and above.
        return if (g == 0 && symbol && c in 0x20..0xFF) lookup(0xF000 + c) else g
    }

    private fun lookup(c: Int): Int {
        val t = cmapAt
        when (cmapFormat) {
            4 -> {
                if (c > 0xFFFF) return 0
                val segs = d.u16(t + 6) / 2
                val ends = t + 14; val starts = ends + 2 * segs + 2; val deltas = starts + 2 * segs; val ranges = deltas + 2 * segs
                var lo = 0; var hi = segs - 1
                while (lo < hi) { val m = (lo + hi) ushr 1; if (d.u16(ends + 2 * m) < c) lo = m + 1 else hi = m }
                if (lo >= segs || d.u16(ends + 2 * lo) < c) return 0
                val start = d.u16(starts + 2 * lo)
                if (start > c) return 0
                val delta = d.u16(deltas + 2 * lo); val range = d.u16(ranges + 2 * lo)
                if (range == 0) return (c + delta) and 0xFFFF
                val at = ranges + 2 * lo + range + 2 * (c - start)
                if (at + 2 > bytes.size) return 0
                val g = d.u16(at)
                return if (g == 0) 0 else (g + delta) and 0xFFFF
            }
            12 -> {
                val groups = d.u32(t + 12)
                var lo = 0; var hi = groups - 1
                while (lo <= hi) {
                    val m = (lo + hi) ushr 1; val g = t + 16 + 12 * m
                    val s = d.u32(g); val e = d.u32(g + 4)
                    when { c < s -> hi = m - 1; c > e -> lo = m + 1; else -> return d.u32(g + 8) + (c - s) }
                }
                return 0
            }
        }
        return 0
    }

    /** Advance width of glyph [g] in font units. */
    fun advance(g: Int): Int {
        val h = tables.getValue("hmtx")
        return d.u16(h + 4 * (if (g < hmetrics) g else hmetrics - 1))
    }

    // ------------------------------------------------------------------ outlines

    /** The outline of glyph [g] in font units (y up), added to [path]. */
    fun outline(g: Int, path: GlyphPath = GlyphPath()): GlyphPath {
        if (g < 0 || g >= glyphCount) return path
        if (cff != null) cff.outline(g, path) else glyf(g, path, 1f, 0f, 0f, 1f, 0f, 0f, 0)
        return path
    }

    private fun glyphRange(g: Int): Pair<Int, Int> {
        val loca = tables.getValue("loca"); val glyf = tables.getValue("glyf")
        val a: Int; val b: Int
        if (longLoca) { a = d.u32(loca + 4 * g); b = d.u32(loca + 4 * g + 4) } else { a = 2 * d.u16(loca + 2 * g); b = 2 * d.u16(loca + 2 * g + 2) }
        if (b <= a || glyf + b > bytes.size) return 0 to 0
        return glyf + a to b - a
    }

    /** A `glyf` glyph through the 2 × 2 matrix (a b; c d) and offset (e, f): composites call it for their parts. */
    private fun glyf(g: Int, path: GlyphPath, a: Float, b: Float, c: Float, dd: Float, e: Float, f: Float, depth: Int) {
        if (depth > 8 || g >= glyphCount) return
        val (at, len) = glyphRange(g)
        if (len < 10) return
        val contours = d.i16(at)
        if (contours >= 0) simple(at, contours, path, a, b, c, dd, e, f) else composite(at, path, a, b, c, dd, e, f, depth)
    }

    /** Points of a simple glyph, without transforming them: x, y, on-curve flags, and the contours' last points. */
    private fun points(at: Int, contours: Int): Triple<FloatArray, BooleanArray, IntArray> {
        val ends = IntArray(contours) { d.u16(at + 10 + 2 * it) }
        val n = if (contours == 0) 0 else ends.last() + 1
        var p = at + 10 + 2 * contours
        p += 2 + d.u16(p)                                     // instructions, skipped
        val flags = ByteArray(n)
        var i = 0
        while (i < n) {
            val fl = d.u8(p++); flags[i++] = fl.toByte()
            if (fl and 8 != 0) { var r = d.u8(p++); while (r-- > 0 && i < n) flags[i++] = fl.toByte() }
        }
        val xy = FloatArray(2 * n)
        var v = 0
        for (k in 0 until n) {
            val fl = flags[k].toInt()
            if (fl and 2 != 0) { val dx = d.u8(p++); v += if (fl and 16 != 0) dx else -dx }
            else if (fl and 16 == 0) { v += d.i16(p); p += 2 }
            xy[2 * k] = v.toFloat()
        }
        v = 0
        for (k in 0 until n) {
            val fl = flags[k].toInt()
            if (fl and 4 != 0) { val dy = d.u8(p++); v += if (fl and 32 != 0) dy else -dy }
            else if (fl and 32 == 0) { v += d.i16(p); p += 2 }
            xy[2 * k + 1] = v.toFloat()
        }
        return Triple(xy, BooleanArray(n) { flags[it].toInt() and 1 != 0 }, ends)
    }

    private fun simple(at: Int, contours: Int, path: GlyphPath, a: Float, b: Float, c: Float, dd: Float, e: Float, f: Float) {
        val (xy, on, ends) = points(at, contours)
        fun tx(k: Int) = a * xy[2 * k] + c * xy[2 * k + 1] + e
        fun ty(k: Int) = b * xy[2 * k] + dd * xy[2 * k + 1] + f
        var start = 0
        for (end in ends) {
            val n = end - start + 1
            if (n < 2 || end >= on.size) { start = end + 1; continue }
            // Start on an on-curve point, or between two off-curve points when there is none.
            var first = -1
            for (k in 0 until n) if (on[start + k]) { first = k; break }
            val sx: Float; val sy: Float
            if (first >= 0) { sx = tx(start + first); sy = ty(start + first) }
            else { first = 0; sx = (tx(start) + tx(start + 1)) / 2; sy = (ty(start) + ty(start + 1)) / 2 }
            path.moveTo(sx, sy)
            var cx = 0f; var cy = 0f; var pending = false
            for (s in 1..n) {
                val k = start + (first + s) % n
                val x = tx(k); val y = ty(k)
                if (on[k]) {
                    if (pending) path.quadTo(cx, cy, x, y) else path.lineTo(x, y)
                    pending = false
                } else {
                    if (pending) { val mx = (cx + x) / 2; val my = (cy + y) / 2; path.quadTo(cx, cy, mx, my) }
                    cx = x; cy = y; pending = true
                }
            }
            if (pending) path.quadTo(cx, cy, sx, sy)
            else if (!on[start + first]) path.lineTo(sx, sy)
            start = end + 1
        }
    }

    private fun composite(at0: Int, path: GlyphPath, a: Float, b: Float, c: Float, dd: Float, e: Float, f: Float, depth: Int) {
        var p = at0 + 10
        // The parts placed so far (glyph, matrix, offset): a part anchored by point numbers needs their points.
        val placed = ArrayList<FloatArray>()
        fun parentPoint(k: Int): Pair<Float, Float> {
            var seen = 0
            for (part in placed) {
                val pts = GlyphPath(); glyfPoints(part[0].toInt(), pts, depth + 1)
                val n = pts.size / 2
                if (k < seen + n) {
                    val x = pts.coords[2 * (k - seen)]; val y = pts.coords[2 * (k - seen) + 1]
                    return part[1] * x + part[3] * y + part[5] to part[2] * x + part[4] * y + part[6]
                }
                seen += n
            }
            return 0f to 0f
        }
        while (true) {
            val flags = d.u16(p); val child = d.u16(p + 2); p += 4
            val words = flags and 1 != 0; val xyValues = flags and 2 != 0
            val arg1: Int; val arg2: Int
            if (words) { arg1 = if (xyValues) d.i16(p) else d.u16(p); arg2 = if (xyValues) d.i16(p + 2) else d.u16(p + 2); p += 4 }
            else { arg1 = if (xyValues) d.b[p].toInt() else d.u8(p); arg2 = if (xyValues) d.b[p + 1].toInt() else d.u8(p + 1); p += 2 }
            var m00 = 1f; var m01 = 0f; var m10 = 0f; var m11 = 1f
            fun f2dot14(o: Int) = d.i16(o) / 16384f
            when {
                flags and 0x8 != 0 -> { m00 = f2dot14(p); m11 = m00; p += 2 }
                flags and 0x40 != 0 -> { m00 = f2dot14(p); m11 = f2dot14(p + 2); p += 4 }
                flags and 0x80 != 0 -> { m00 = f2dot14(p); m01 = f2dot14(p + 2); m10 = f2dot14(p + 4); m11 = f2dot14(p + 6); p += 8 }
            }
            var ox: Float; var oy: Float
            if (xyValues) {
                ox = arg1.toFloat(); oy = arg2.toFloat()
                // Offsets are not scaled unless the font asks (SCALED_COMPONENT_OFFSET), as FreeType does.
                if (flags and 0x800 != 0 && flags and 0x1000 == 0) { val x = m00 * ox + m10 * oy; val y = m01 * ox + m11 * oy; ox = x; oy = y }
            } else {
                // The child's point arg2 lands on the parent's point arg1 (both in the parent's space).
                val childPath = GlyphPath(); glyfPoints(child, childPath, depth + 1)
                val (px, py) = parentPoint(arg1)
                val cxp = childPath.coords.getOrElse(2 * arg2) { 0f }; val cyp = childPath.coords.getOrElse(2 * arg2 + 1) { 0f }
                ox = px - (m00 * cxp + m10 * cyp); oy = py - (m01 * cxp + m11 * cyp)
            }
            // Child space → this glyph's space (m, then offset) → the caller's space (a b c d e f).
            val na = a * m00 + c * m01; val nb = b * m00 + dd * m01
            val nc = a * m10 + c * m11; val nd = b * m10 + dd * m11
            val ne = a * ox + c * oy + e; val nf = b * ox + dd * oy + f
            placed.add(floatArrayOf(child.toFloat(), m00, m01, m10, m11, ox, oy))
            glyf(child, path, na, nb, nc, nd, ne, nf, depth + 1)
            if (flags and 0x20 == 0) break
        }
    }

    /** The raw points of a glyph (composites flattened), for parts anchored by point numbers. */
    private fun glyfPoints(g: Int, out: GlyphPath, depth: Int) {
        if (depth > 8 || g >= glyphCount) return
        val (at, len) = glyphRange(g)
        if (len < 10) return
        val contours = d.i16(at)
        if (contours >= 0) { val (xy, _, _) = points(at, contours); var k = 0; while (k < xy.size) { out.lineTo(xy[k], xy[k + 1]); k += 2 } }
    }

    // ------------------------------------------------------------------ kerning

    private fun readKerning() {
        val gpos = tables["GPOS"]
        if (gpos != null && lengths.getValue("GPOS") >= 10) {
            val features = gpos + d.u16(gpos + 6); val lookups = gpos + d.u16(gpos + 8)
            val found = HashSet<Int>()
            val nf = d.u16(features)
            for (i in 0 until nf) {
                if (d.tag(features + 2 + 6 * i) != "kern") continue
                val feature = features + d.u16(features + 6 + 6 * i)
                val n = d.u16(feature + 2)
                for (k in 0 until n) found.add(d.u16(feature + 4 + 2 * k))
            }
            val count = d.u16(lookups)
            val ok = found.sorted().filter { it < count }.map { lookups + d.u16(lookups + 2 + 2 * it) }
            if (ok.isNotEmpty()) gposLookups = ok.toIntArray()
        }
        val kern = tables["kern"] ?: return
        if (d.u16(kern) != 0) return                      // Apple's version 1 table: not read
        var p = kern + 4
        repeat(d.u16(kern + 2)) {
            val length = d.u16(p + 2); val coverage = d.u16(p + 4)
            if (coverage ushr 8 == 0 && coverage and 1 != 0 && coverage and 6 == 0) { kernPairs = d.u16(p + 6); kernAt = p + 14; return }
            p += length
        }
    }

    /** Kerning between glyphs [left] and [right], in font units (negative: closer): GPOS when the font has it, else `kern`. */
    fun kerning(left: Int, right: Int): Int = if (gposLookups != null) gposKerning(left, right) else tableKerning(left, right)

    /** True if the font kerns with GPOS (its `kern` feature). */
    val kernsWithGpos get() = gposLookups != null

    /** Kerning from the GPOS `kern` feature: every lookup's first subtable that holds the pair. */
    fun gposKerning(left: Int, right: Int): Int {
        val lookups = gposLookups ?: return 0
        var total = 0
        for (l in lookups) total += lookupKerning(l, left, right)
        return total
    }

    /** Kerning from the old `kern` table (format 0, horizontal). */
    fun tableKerning(left: Int, right: Int): Int {
        if (kernPairs == 0) return 0
        val key = (left shl 16) or right
        var lo = 0; var hi = kernPairs - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1; val at = kernAt + 6 * m
            val k = (d.u16(at) shl 16) or d.u16(at + 2)
            when { k.toUInt() < key.toUInt() -> lo = m + 1; k.toUInt() > key.toUInt() -> hi = m - 1; else -> return d.i16(at + 4) }
        }
        return 0
    }

    private fun lookupKerning(lookup: Int, left: Int, right: Int): Int {
        var type = d.u16(lookup)
        val n = d.u16(lookup + 4)
        for (i in 0 until n) {
            var sub = lookup + d.u16(lookup + 6 + 2 * i)
            var t = type
            if (t == 9) { t = d.u16(sub + 2); sub += d.u32(sub + 4) }
            if (t != 2) continue
            val v = pairValue(sub, left, right)
            if (v != null) return v
        }
        return 0
    }

    private fun valueSize(format: Int) = 2 * (format and 0xFF).countOneBits()
    /** The x advance in a value record of [format] at [at]. */
    private fun xAdvance(format: Int, at: Int): Int {
        if (format and 4 == 0) return 0
        return d.i16(at + 2 * (format and 3).countOneBits())
    }

    private fun pairValue(sub: Int, left: Int, right: Int): Int? {
        val format = d.u16(sub)
        val ci = coverage(sub + d.u16(sub + 2), left)
        if (ci < 0) return null
        val f1 = d.u16(sub + 4); val f2 = d.u16(sub + 6)
        val record = valueSize(f1) + valueSize(f2)
        if (format == 1) {
            if (ci >= d.u16(sub + 8)) return null
            val set = sub + d.u16(sub + 10 + 2 * ci)
            var lo = 0; var hi = d.u16(set) - 1
            val stride = 2 + record
            while (lo <= hi) {
                val m = (lo + hi) ushr 1; val at = set + 2 + stride * m
                val g = d.u16(at)
                when { g < right -> lo = m + 1; g > right -> hi = m - 1; else -> return xAdvance(f1, at + 2) }
            }
            return null
        }
        if (format == 2) {
            val c1 = classOf(sub + d.u16(sub + 8), left); val c2 = classOf(sub + d.u16(sub + 10), right)
            val n1 = d.u16(sub + 12); val n2 = d.u16(sub + 14)
            if (c1 >= n1 || c2 >= n2) return null
            return xAdvance(f1, sub + 16 + record * (c1 * n2 + c2))
        }
        return null
    }

    private fun coverage(at: Int, g: Int): Int {
        when (d.u16(at)) {
            1 -> {
                var lo = 0; var hi = d.u16(at + 2) - 1
                while (lo <= hi) { val m = (lo + hi) ushr 1; val v = d.u16(at + 4 + 2 * m); when { v < g -> lo = m + 1; v > g -> hi = m - 1; else -> return m } }
            }
            2 -> {
                var lo = 0; var hi = d.u16(at + 2) - 1
                while (lo <= hi) {
                    val m = (lo + hi) ushr 1; val r = at + 4 + 6 * m
                    when { g < d.u16(r) -> hi = m - 1; g > d.u16(r + 2) -> lo = m + 1; else -> return d.u16(r + 4) + g - d.u16(r) }
                }
            }
        }
        return -1
    }

    private fun classOf(at: Int, g: Int): Int {
        when (d.u16(at)) {
            1 -> { val s = d.u16(at + 2); val n = d.u16(at + 4); return if (g in s until s + n) d.u16(at + 6 + 2 * (g - s)) else 0 }
            2 -> {
                var lo = 0; var hi = d.u16(at + 2) - 1
                while (lo <= hi) {
                    val m = (lo + hi) ushr 1; val r = at + 4 + 6 * m
                    when { g < d.u16(r) -> hi = m - 1; g > d.u16(r + 2) -> lo = m + 1; else -> return d.u16(r + 4) }
                }
            }
        }
        return 0
    }

    companion object {
        /** Reads face [index] of the font file at [path] (a .ttf, .otf, or a .ttc collection). */
        fun load(path: String, index: Int = 0): FontFace = FontFace(Storage.file(path) ?: error("Font not found: $path"), index)
        fun of(bytes: ByteArray, index: Int = 0): FontFace = FontFace(bytes, index)

        /** Family and style of a face: the typographic names (16, 17) when present, else 1 and 2, in English when possible. */
        fun readNames(d: FontBytes, name: Int): Pair<String, String> {
            if (name < 0) return "" to ""
            val count = d.u16(name + 2); val strings = name + d.u16(name + 4)
            val found = HashMap<Int, Pair<Int, String>>()     // id → (score, text)
            for (i in 0 until count) {
                val r = name + 6 + 12 * i
                val p = d.u16(r); val e = d.u16(r + 2); val lang = d.u16(r + 4); val id = d.u16(r + 6)
                if (id != 1 && id != 2 && id != 16 && id != 17) continue
                val len = d.u16(r + 8); val at = strings + d.u16(r + 10)
                if (at + len > d.b.size) continue
                val text = when {
                    p == 3 || p == 0 -> CharArray(len / 2) { (((d.b[at + 2 * it].toInt() and 255) shl 8) or (d.b[at + 2 * it + 1].toInt() and 255)).toChar() }.concatToString()
                    p == 1 && e == 0 -> latin1(d.b, at, len)
                    else -> continue
                }
                val score = when { p == 3 && lang == 0x409 -> 3; p == 3 -> 1; p == 0 -> 2; else -> 0 }
                if ((found[id]?.first ?: -1) < score) found[id] = score to text
            }
            val family = found[16]?.second ?: found[1]?.second ?: ""
            val style = found[17]?.second ?: found[2]?.second ?: ""
            return family.trim() to style.trim()
        }

        /**
         * The names and styles of every face in [path], reading only the table directory, `name` and `OS/2`
         * (a scan of hundreds of files must not read them whole). Empty if it is not a font.
         */
        fun scan(path: String): List<FaceInfo> {
            run {
                fun read(at: Long, n: Int): FontBytes? = Storage.fileRange(path, at, n)?.let { FontBytes(it) }
                val head = read(0, 12) ?: return emptyList()
                val offsets = if (head.tag(0) == "ttcf") {
                    val n = head.u32(8).coerceIn(0, 256)
                    val o = read(12, 4 * n) ?: return emptyList()
                    IntArray(n) { o.u32(4 * it) }
                } else intArrayOf(0)
                val out = ArrayList<FaceInfo>()
                for ((i, o) in offsets.withIndex()) {
                    val h = read(o.toLong(), 12) ?: continue
                    val v = h.u32(0)
                    if (v != 0x00010000 && v != 0x4F54544F && v != 0x74727565) continue
                    val n = h.u16(4)
                    val dir = read(o + 12L, 16 * n) ?: continue
                    val t = HashMap<String, Pair<Int, Int>>()
                    for (k in 0 until n) t[dir.tag(16 * k)] = dir.u32(16 * k + 8) to dir.u32(16 * k + 12)
                    val name = t["name"] ?: continue
                    val nb = read(name.first.toLong(), name.second) ?: continue
                    val (family, style) = readNames(nb, 0)
                    if (family.isEmpty()) continue
                    var weight = 400; var italic = style.contains("Italic", true) || style.contains("Oblique", true)
                    t["OS/2"]?.let { (at, len) -> read(at.toLong(), minOf(len, 64))?.let { os -> if (len >= 64) { weight = os.u16(4); italic = italic || os.u16(62) and 1 != 0 } } }
                    val outlines = ("glyf" in t && "loca" in t) || "CFF " in t
                    out.add(FaceInfo(path, i, family, style, weight, italic, "fvar" in t, outlines && "cmap" in t))
                }
                return out
            }
        }
    }
}
