package sillage.io


/**
 * Writes PNG files (8-bit RGB) without the JDK's image libraries: each row filtered the way that leaves
 * the smallest numbers (None, Sub, Up, Average or Paeth), then deflated (LZ77 over a 32 KiB window with
 * hash chains, fixed Huffman codes). Captures and texture exports go through it.
 */
object PngWriter {
    /** [rgb]: [width] × [height] pixels, 3 bytes each, rows top first. */
    fun encode(width: Int, height: Int, rgb: ByteArray): ByteArray {
        require(width > 0 && height > 0 && rgb.size == width * height * 3) { "PNG: ${rgb.size} bytes for $width × $height RGB pixels" }
        val stride = width * 3
        val raw = ByteArray((stride + 1) * height)
        for (y in 0 until height) filterRow(rgb, y, stride, raw)
        val out = ByteSink(raw.size / 2 + 1024)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val header = ByteArray(13)
        put32(header, 0, width); put32(header, 4, height)
        header[8] = 8; header[9] = 2                       // 8 bits, truecolour; deflate, filters, no interlace
        chunk(out, "IHDR", header)
        chunk(out, "IDAT", zlib(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** [encode] into a file (its folder made if needed). */
    fun write(path: String, width: Int, height: Int, rgb: ByteArray) {
        Storage.write(path, encode(width, height, rgb))
    }

    /**
     * Row [y] into [raw] with the filter that leaves the smallest numbers (the first of the smallest: None, Sub,
     * Up, Average, Paeth): the five sums in one pass, then the row written with the one chosen.
     */
    private fun filterRow(rgb: ByteArray, y: Int, stride: Int, raw: ByteArray) {
        val row = y * stride; val up = row - stride
        var s0 = 0L; var s1 = 0L; var s2 = 0L; var s3 = 0L; var s4 = 0L
        for (x in 0 until stride) {
            val cur = rgb[row + x].toInt() and 255
            val a = if (x >= 3) rgb[row + x - 3].toInt() and 255 else 0
            val b = if (y > 0) rgb[up + x].toInt() and 255 else 0
            val c = if (x >= 3 && y > 0) rgb[up + x - 3].toInt() and 255 else 0
            s0 += score(cur); s1 += score((cur - a) and 255); s2 += score((cur - b) and 255)
            s3 += score((cur - ((a + b) ushr 1)) and 255); s4 += score((cur - paeth(a, b, c)) and 255)
        }
        var best = 0; var bestScore = s0
        if (s1 < bestScore) { best = 1; bestScore = s1 }
        if (s2 < bestScore) { best = 2; bestScore = s2 }
        if (s3 < bestScore) { best = 3; bestScore = s3 }
        if (s4 < bestScore) best = 4
        val o = y * (stride + 1)
        raw[o] = best.toByte()
        for (x in 0 until stride) {
            val cur = rgb[row + x].toInt() and 255
            val a = if (x >= 3) rgb[row + x - 3].toInt() and 255 else 0
            val b = if (y > 0) rgb[up + x].toInt() and 255 else 0
            val c = if (x >= 3 && y > 0) rgb[up + x - 3].toInt() and 255 else 0
            raw[o + 1 + x] = (when (best) { 0 -> cur; 1 -> cur - a; 2 -> cur - b; 3 -> cur - ((a + b) ushr 1); else -> cur - paeth(a, b, c) }).toByte()
        }
    }

    private fun score(v: Int): Int = if (v < 128) v else 256 - v

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun put32(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte() }

    private fun chunk(out: ByteSink, type: String, data: ByteArray) {
        val head = ByteArray(8); put32(head, 0, data.size)
        for (i in 0..3) head[4 + i] = type[i].code.toByte()
        out.write(head)
        out.write(data)
        var crc = crc32(-1, head, 4, 4); crc = crc32(crc, data, 0, data.size)
        val tail = ByteArray(4); put32(tail, 0, crc.inv())
        out.write(tail)
    }

    private val crcTable = IntArray(256) { n -> var c = n; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }; c }

    /** CRC-32 (the zlib one) of [len] bytes at [off], continuing [crc] (start with −1, invert at the end). */
    internal fun crc32(crc: Int, b: ByteArray, off: Int, len: Int): Int {
        var c = crc
        for (i in off until off + len) c = crcTable[(c xor b[i].toInt()) and 255] xor (c ushr 8)
        return c
    }

    // ---------------------------------------------------------------- deflate

    private class Bits(capacity: Int) {
        var buf = ByteArray(capacity); var size = 0; private var acc = 0L; private var count = 0
        /** [n] bits of [v], least significant first. */
        fun put(v: Int, n: Int) {
            acc = acc or ((v.toLong() and ((1L shl n) - 1)) shl count); count += n
            while (count >= 8) { byte((acc and 255).toInt()); acc = acc ushr 8; count -= 8 }
        }
        /** A Huffman code: its bits from the most significant. */
        fun code(c: Int, n: Int) { var r = 0; for (i in 0 until n) r = (r shl 1) or ((c ushr i) and 1); put(r, n) }
        fun flush() { if (count > 0) { byte((acc and 255).toInt()); acc = 0; count = 0 } }
        fun byte(v: Int) { if (size == buf.size) buf = buf.copyOf(buf.size * 2); buf[size++] = v.toByte() }
    }

    private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

    /** The bits of [c] ([n] of them) from the most significant: how a Huffman code goes out (see [Bits.code]). */
    private fun reversed(c: Int, n: Int): Int { var r = 0; for (i in 0 until n) r = (r shl 1) or ((c ushr i) and 1); return r }

    // The fixed Huffman table of deflate, laid out once: each literal or length symbol's code, already reversed, and its length.
    private val LITERAL_BITS = IntArray(288) { v -> when { v < 144 -> 8; v < 256 -> 9; v < 280 -> 7; else -> 8 } }
    private val LITERAL_CODE = IntArray(288) { v ->
        reversed(when { v < 144 -> 0x30 + v; v < 256 -> 0x190 + v - 144; v < 280 -> v - 256; else -> 0xC0 + v - 280 }, LITERAL_BITS[v])
    }
    /** The largest i with LENGTH_BASE[i] ≤ length, for each length 0..258; the same for distances 0..32768. */
    private val LENGTH_INDEX = IntArray(259) { l -> var i = LENGTH_BASE.size - 1; while (i > 0 && LENGTH_BASE[i] > l) i--; i }
    private val DIST_INDEX = ByteArray(32769) { d -> var i = DIST_BASE.size - 1; while (i > 0 && DIST_BASE[i] > d) i--; i.toByte() }
    private val DIST_CODE = IntArray(30) { reversed(it, 5) }

    /** A literal or a length code with the fixed Huffman table of deflate. */
    private fun literal(o: Bits, v: Int) = o.put(LITERAL_CODE[v], LITERAL_BITS[v])

    private fun match(o: Bits, length: Int, distance: Int) {
        val li = LENGTH_INDEX[length]
        literal(o, 257 + li); if (LENGTH_EXTRA[li] > 0) o.put(length - LENGTH_BASE[li], LENGTH_EXTRA[li])
        val di = DIST_INDEX[distance].toInt()
        o.put(DIST_CODE[di], 5); if (DIST_EXTRA[di] > 0) o.put(distance - DIST_BASE[di], DIST_EXTRA[di])
    }

    /** zlib around one deflate block with fixed codes. */
    internal fun zlib(data: ByteArray): ByteArray {
        val o = Bits(data.size / 2 + 64)
        o.byte(0x78); o.byte(0x01)
        o.put(1, 1); o.put(1, 2)                           // the last block, fixed Huffman codes
        val window = 32768; val hashSize = 1 shl 15
        val head = IntArray(hashSize) { -1 }; val prev = IntArray(window)
        fun hash(i: Int) = (((data[i].toInt() and 255) shl 10) xor ((data[i + 1].toInt() and 255) shl 5) xor (data[i + 2].toInt() and 255)) and (hashSize - 1)
        fun insert(i: Int) { if (i + 2 < data.size) { val h = hash(i); prev[i and (window - 1)] = head[h]; head[h] = i } }
        var i = 0
        while (i < data.size) {
            var bestLen = 0; var bestDist = 0
            if (i + 2 < data.size) {
                var cand = head[hash(i)]; var chain = 48
                val max = minOf(258, data.size - i)
                while (cand >= 0 && i - cand <= window && chain-- > 0) {
                    if (data[cand + bestLen] == data[i + bestLen]) {
                        var l = 0
                        while (l < max && data[cand + l] == data[i + l]) l++
                        if (l > bestLen) { bestLen = l; bestDist = i - cand; if (l == max) break }
                    }
                    val next = prev[cand and (window - 1)]
                    if (next >= cand) break
                    cand = next
                }
            }
            if (bestLen >= 3) {
                match(o, bestLen, bestDist)
                for (k in 0 until bestLen) insert(i + k)
                i += bestLen
            } else {
                literal(o, data[i].toInt() and 255); insert(i); i++
            }
        }
        literal(o, 256)
        o.flush()
        // Adler-32, the remainders taken every 5552 bytes (as zlib does: the sums cannot overflow in between).
        var a = 1L; var b = 0L; var k = 0
        while (k < data.size) {
            val end = minOf(data.size, k + 5552)
            while (k < end) { a += data[k].toInt() and 255; b += a; k++ }
            a %= 65521; b %= 65521
        }
        val adler = (b.toInt() shl 16) or a.toInt()
        o.byte(adler ushr 24); o.byte(adler ushr 16); o.byte(adler ushr 8); o.byte(adler)
        return o.buf.copyOf(o.size)
    }
}
