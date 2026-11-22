package sillage.io

/**
 * The outlines of an OpenType font with PostScript outlines (the `CFF ` table): its INDEXes and DICTs,
 * name-keyed or CID-keyed fonts (FDArray, FDSelect: each glyph's own local subroutines), and an
 * interpreter of Type 2 charstrings (moves, lines, curves, flex, hints skipped, subroutines, the few
 * arithmetic operators fonts use). Coordinates come out in the face's units (the FontMatrix brought back
 * to unitsPerEm). Not read: `seac` accents (deprecated, absent from OpenType fonts) and CFF2.
 */
internal class Cff(bytes: ByteArray, private val start: Int, length: Int, unitsPerEm: Int) {
    private val d = FontBytes(bytes)
    private val end = start + length
    private class Index(val count: Int, val offSize: Int, val offsets: Int, val data: Int, val next: Int)
    private val charStrings: Index
    private val globalSubrs: Index
    private val localSubrs: Array<Index?>
    private val fdSelect: Int                       // offset of FDSelect, or −1 (one private DICT)
    private var scale = 1f

    init {
        val hdrSize = d.u8(start + 2)
        val names = index(start + hdrSize)
        val topDicts = index(names.next)
        val strings = index(topDicts.next)
        globalSubrs = index(strings.next)
        require(topDicts.count >= 1) { "CFF without a Top DICT" }
        val top = dict(item(topDicts, 0))
        charStrings = index(start + (top[17]?.get(0)?.toInt() ?: error("CFF without CharStrings")))
        top[1207]?.let { m -> if (m.size >= 1 && m[0] != 0.0) scale = (m[0] * unitsPerEm).toFloat() }
        val fdArray = top[1236]
        if (fdArray != null) {
            val fds = index(start + fdArray[0].toInt())
            localSubrs = Array(fds.count) { i -> privateSubrs(dict(item(fds, i))[18]) }
            fdSelect = start + (top[1237]?.get(0)?.toInt() ?: error("CID-keyed CFF without FDSelect"))
        } else {
            localSubrs = arrayOf(privateSubrs(top[18]))
            fdSelect = -1
        }
        require(charStrings.next <= end + 1) { "CFF CharStrings outside the table" }
    }

    private fun privateSubrs(p: List<Double>?): Index? {
        if (p == null || p.size < 2) return null
        val size = p[0].toInt(); val at = start + p[1].toInt()
        val priv = dict(Pair(at, at + size))
        val subrs = priv[19]?.get(0)?.toInt() ?: return null
        return index(at + subrs)
    }

    private fun index(at: Int): Index {
        val count = d.u16(at)
        if (count == 0) return Index(0, 1, at + 2, at + 2, at + 2)
        val offSize = d.u8(at + 2)
        require(offSize in 1..4) { "CFF INDEX offSize $offSize" }
        val offsets = at + 3
        val data = offsets + (count + 1) * offSize - 1
        val idx = Index(count, offSize, offsets, data, 0)
        return Index(count, offSize, offsets, data, data + offset(idx, count))
    }

    private fun offset(i: Index, k: Int): Int {
        var v = 0; val o = i.offsets + k * i.offSize
        for (j in 0 until i.offSize) v = (v shl 8) or d.u8(o + j)
        return v
    }
    private fun item(i: Index, k: Int): Pair<Int, Int> = (i.data + offset(i, k)) to (i.data + offset(i, k + 1))

    /** A DICT: operator (escaped ones as 1200 + b1) → operands. */
    private fun dict(range: Pair<Int, Int>): Map<Int, List<Double>> {
        val out = HashMap<Int, List<Double>>()
        val ops = ArrayList<Double>()
        var p = range.first
        while (p < range.second) {
            val b0 = d.u8(p)
            when {
                b0 <= 21 -> {
                    val op = if (b0 == 12) { p++; 1200 + d.u8(p) } else b0
                    out[op] = ArrayList(ops); ops.clear(); p++
                }
                b0 == 28 -> { ops.add(d.i16(p + 1).toDouble()); p += 3 }
                b0 == 29 -> { ops.add(d.u32(p + 1).toDouble()); p += 5 }
                b0 == 30 -> {
                    val s = StringBuilder(); p++
                    loop@ while (true) {
                        val b = d.u8(p++)
                        for (nib in intArrayOf(b shr 4, b and 15)) when (nib) {
                            in 0..9 -> s.append(nib); 0xa -> s.append('.'); 0xb -> s.append('E'); 0xc -> s.append("E-"); 0xe -> s.append('-')
                            0xf -> break@loop
                        }
                    }
                    ops.add(s.toString().toDoubleOrNull() ?: 0.0)
                }
                b0 in 32..246 -> { ops.add((b0 - 139).toDouble()); p++ }
                b0 in 247..250 -> { ops.add(((b0 - 247) * 256 + d.u8(p + 1) + 108).toDouble()); p += 2 }
                b0 in 251..254 -> { ops.add((-(b0 - 251) * 256 - d.u8(p + 1) - 108).toDouble()); p += 2 }
                else -> p++
            }
        }
        return out
    }

    private fun fdOf(g: Int): Int {
        if (fdSelect < 0) return 0
        when (d.u8(fdSelect)) {
            0 -> return d.u8(fdSelect + 1 + g)
            3 -> {
                val n = d.u16(fdSelect + 1)
                for (r in 0 until n) {
                    val at = fdSelect + 3 + 3 * r
                    if (g >= d.u16(at) && g < d.u16(at + 3)) return d.u8(at + 2)
                }
            }
        }
        return 0
    }

    private fun bias(i: Index?): Int { val n = i?.count ?: 0; return if (n < 1240) 107 else if (n < 33900) 1131 else 32768 }

    /** The outline of glyph [g] (CFF glyph ids are those of the font) added to [path]. */
    fun outline(g: Int, path: GlyphPath) {
        if (g < 0 || g >= charStrings.count) return
        Interpreter(path, localSubrs.getOrNull(fdOf(g))).run(item(charStrings, g), 0)
        if (scale != 1f) { for (k in 0 until path.size) path.coords[k] *= scale }
    }

    private inner class Interpreter(val path: GlyphPath, val local: Index?) {
        val stack = DoubleArray(48); var sp = 0
        var x = 0.0; var y = 0.0
        var stems = 0
        var widthSeen = false
        var open = false
        var ended = false
        val transient = DoubleArray(32)
        val localBias = bias(local); val globalBias = bias(globalSubrs)

        fun moveTo(dx: Double, dy: Double) { x += dx; y += dy; path.moveTo(x.toFloat(), y.toFloat()); open = true }
        fun lineTo(dx: Double, dy: Double) { x += dx; y += dy; path.lineTo(x.toFloat(), y.toFloat()) }
        fun curveTo(a: Double, b: Double, c: Double, e: Double, f: Double, g: Double) {
            val x1 = x + a; val y1 = y + b; val x2 = x1 + c; val y2 = y1 + e; x = x2 + f; y = y2 + g
            path.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x.toFloat(), y.toFloat())
        }
        /** The first stem operator may carry the advance width before its pairs (an odd count): drop it. */
        fun stemWidth() { if (!widthSeen) { widthSeen = true; if (sp % 2 == 1) shift() } }
        fun shift() { stack.copyInto(stack, 0, 1, 1 + (sp - 1)); sp-- }

        fun run(range: Pair<Int, Int>, depth: Int) {
            require(depth <= 10) { "CFF subroutines nested too deep" }
            var p = range.first
            while (p < range.second && !ended) {
                val b0 = d.u8(p)
                if (b0 >= 32 || b0 == 28) {
                    val v: Double
                    when {
                        b0 == 28 -> { v = d.i16(p + 1).toDouble(); p += 3 }
                        b0 <= 246 -> { v = (b0 - 139).toDouble(); p++ }
                        b0 <= 250 -> { v = ((b0 - 247) * 256 + d.u8(p + 1) + 108).toDouble(); p += 2 }
                        b0 <= 254 -> { v = (-(b0 - 251) * 256 - d.u8(p + 1) - 108).toDouble(); p += 2 }
                        else -> { v = d.u32(p + 1) / 65536.0; p += 5 }
                    }
                    if (sp < stack.size) stack[sp++] = v
                    continue
                }
                p++
                when (b0) {
                    1, 3, 18, 23 -> { stemWidth(); stems += sp / 2; sp = 0 }                 // stems: counted for the masks
                    19, 20 -> { stemWidth(); stems += sp / 2; sp = 0; p += (stems + 7) / 8 }  // hintmask, cntrmask
                    21 -> { if (!widthSeen) { widthSeen = true; if (sp > 2) shift() }; moveTo(stack[0], stack[1]); sp = 0 }
                    22 -> { if (!widthSeen) { widthSeen = true; if (sp > 1) shift() }; moveTo(stack[0], 0.0); sp = 0 }
                    4 -> { if (!widthSeen) { widthSeen = true; if (sp > 1) shift() }; moveTo(0.0, stack[0]); sp = 0 }
                    5 -> { var i = 0; while (i + 1 < sp) { lineTo(stack[i], stack[i + 1]); i += 2 }; sp = 0 }
                    6, 7 -> {
                        var horizontal = b0 == 6
                        for (i in 0 until sp) { if (horizontal) lineTo(stack[i], 0.0) else lineTo(0.0, stack[i]); horizontal = !horizontal }
                        sp = 0
                    }
                    8 -> { var i = 0; while (i + 5 < sp) { curveTo(stack[i], stack[i + 1], stack[i + 2], stack[i + 3], stack[i + 4], stack[i + 5]); i += 6 }; sp = 0 }
                    24 -> {                                                                   // rcurveline
                        var i = 0
                        while (i + 5 < sp - 2) { curveTo(stack[i], stack[i + 1], stack[i + 2], stack[i + 3], stack[i + 4], stack[i + 5]); i += 6 }
                        if (i + 1 < sp) lineTo(stack[i], stack[i + 1])
                        sp = 0
                    }
                    25 -> {                                                                   // rlinecurve
                        var i = 0
                        while (i + 1 < sp - 6) { lineTo(stack[i], stack[i + 1]); i += 2 }
                        if (i + 5 < sp) curveTo(stack[i], stack[i + 1], stack[i + 2], stack[i + 3], stack[i + 4], stack[i + 5])
                        sp = 0
                    }
                    26 -> {                                                                   // vvcurveto
                        var i = 0; var dx1 = 0.0
                        if (sp % 2 == 1) { dx1 = stack[0]; i = 1 }
                        while (i + 3 < sp) { curveTo(dx1, stack[i], stack[i + 1], stack[i + 2], 0.0, stack[i + 3]); dx1 = 0.0; i += 4 }
                        sp = 0
                    }
                    27 -> {                                                                   // hhcurveto
                        var i = 0; var dy1 = 0.0
                        if (sp % 2 == 1) { dy1 = stack[0]; i = 1 }
                        while (i + 3 < sp) { curveTo(stack[i], dy1, stack[i + 1], stack[i + 2], stack[i + 3], 0.0); dy1 = 0.0; i += 4 }
                        sp = 0
                    }
                    30, 31 -> {                                                               // vhcurveto, hvcurveto
                        var horizontal = b0 == 31
                        var i = 0
                        while (i + 3 < sp) {
                            val last = i + 4 >= sp - 1
                            val extra = if (last && sp - i == 5) stack[i + 4] else 0.0
                            if (horizontal) curveTo(stack[i], 0.0, stack[i + 1], stack[i + 2], extra, stack[i + 3])
                            else curveTo(0.0, stack[i], stack[i + 1], stack[i + 2], stack[i + 3], extra)
                            horizontal = !horizontal
                            i += 4
                        }
                        sp = 0
                    }
                    10, 29 -> {                                                               // callsubr, callgsubr
                        if (sp == 0) return
                        val subrs = if (b0 == 10) local else globalSubrs
                        val n = stack[--sp].toInt() + if (b0 == 10) localBias else globalBias
                        if (subrs == null || n < 0 || n >= subrs.count) return
                        run(item(subrs, n), depth + 1)
                    }
                    11 -> return
                    14 -> { ended = true; sp = 0 }                                            // endchar
                    12 -> {
                        val op = d.u8(p++)
                        escape(op)
                    }
                    else -> sp = 0
                }
            }
        }

        private fun escape(op: Int) {
            val s = stack
            fun pop(): Double = if (sp > 0) s[--sp] else 0.0
            fun push(v: Double) { if (sp < s.size) s[sp++] = v }
            when (op) {
                35 -> { if (sp >= 12) { curveTo(s[0], s[1], s[2], s[3], s[4], s[5]); curveTo(s[6], s[7], s[8], s[9], s[10], s[11]) }; sp = 0 }   // flex
                34 -> {                                                                       // hflex
                    if (sp >= 7) { val y0 = y; curveTo(s[0], 0.0, s[1], s[2], s[3], 0.0); curveTo(s[4], 0.0, s[5], y0 - y, s[6], 0.0) }
                    sp = 0
                }
                36 -> {                                                                       // hflex1
                    if (sp >= 9) { val y0 = y; curveTo(s[0], s[1], s[2], s[3], s[4], 0.0); curveTo(s[5], 0.0, s[6], s[7], s[8], y0 - (y + s[7])) }
                    sp = 0
                }
                37 -> {                                                                       // flex1
                    if (sp >= 11) {
                        val x0 = x; val y0 = y
                        var dx = 0.0; var dy = 0.0
                        for (i in 0 until 10 step 2) { dx += s[i]; dy += s[i + 1] }
                        curveTo(s[0], s[1], s[2], s[3], s[4], s[5])
                        val last = s[10]
                        if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) curveTo(s[6], s[7], s[8], s[9], last, y0 - (y + s[7] + s[9]))
                        else curveTo(s[6], s[7], s[8], s[9], x0 - (x + s[6] + s[8]), last)
                    }
                    sp = 0
                }
                3 -> { val b = pop(); val a = pop(); push(if (a != 0.0 && b != 0.0) 1.0 else 0.0) }      // and
                4 -> { val b = pop(); val a = pop(); push(if (a != 0.0 || b != 0.0) 1.0 else 0.0) }      // or
                5 -> push(if (pop() == 0.0) 1.0 else 0.0)                                              // not
                9 -> push(kotlin.math.abs(pop()))
                10 -> { val b = pop(); val a = pop(); push(a + b) }
                11 -> { val b = pop(); val a = pop(); push(a - b) }
                12 -> { val b = pop(); val a = pop(); push(if (b == 0.0) 0.0 else a / b) }
                14 -> push(-pop())
                15 -> { val b = pop(); val a = pop(); push(if (a == b) 1.0 else 0.0) }
                18 -> pop()
                20 -> { val i = pop().toInt(); val v = pop(); if (i in transient.indices) transient[i] = v }    // put
                21 -> { val i = pop().toInt(); push(if (i in transient.indices) transient[i] else 0.0) }       // get
                22 -> { val v2 = pop(); val v1 = pop(); val s2 = pop(); val s1 = pop(); push(if (v1 <= v2) s1 else s2) }
                23 -> push(.5)                                                                // random: fixed, outlines must not change
                24 -> { val b = pop(); val a = pop(); push(a * b) }
                26 -> push(kotlin.math.sqrt(kotlin.math.abs(pop())))
                27 -> { val a = pop(); push(a); push(a) }
                28 -> { val b = pop(); val a = pop(); push(b); push(a) }
                29 -> { val i = pop().toInt(); push(if (i >= 0 && sp - 1 - i >= 0) s[sp - 1 - i] else if (sp > 0) s[sp - 1] else 0.0) }
                30 -> {                                                                       // roll
                    val j = pop().toInt(); val n = pop().toInt()
                    if (n in 1..sp) {
                        val base = sp - n; val tmp = DoubleArray(n) { s[base + it] }
                        for (k in 0 until n) s[base + (k + j).mod(n)] = tmp[k]
                    }
                }
                else -> sp = 0
            }
        }
    }
}
