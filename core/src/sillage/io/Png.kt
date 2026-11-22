package sillage.io

/**
 * An image in memory: [width] × [height] pixels, 4 bytes each (red, green, blue, alpha), rows from the
 * top. Made by [Png] and [Jpeg], or by hand.
 */
class Image(val width: Int, val height: Int, val rgba: ByteArray) {
    init {
        require(width > 0 && height > 0) { "An image needs at least one pixel" }
        require(rgba.size == width * height * 4) { "An image of $width × $height needs ${width * height * 4} bytes" }
    }

    /** The pixel at ([x], [y]) as 0xAARRGGBB. */
    fun argb(x: Int, y: Int): Int {
        val i = 4 * (y * width + x)
        return ((rgba[i + 3].toInt() and 255) shl 24) or ((rgba[i].toInt() and 255) shl 16) or
            ((rgba[i + 1].toInt() and 255) shl 8) or (rgba[i + 2].toInt() and 255)
    }

    companion object {
        /** Decodes a PNG or JPEG image from its bytes (the format is found from the first bytes). */
        fun decode(bytes: ByteArray): Image = when {
            Png.isPng(bytes) -> Png.decode(bytes)
            Jpeg.isJpeg(bytes) -> Jpeg.decode(bytes)
            else -> throw IllegalArgumentException("Unknown image format (PNG or JPEG are read)")
        }
    }
}

/**
 * PNG decoder, in Kotlin: every colour type and bit depth (1 to 16 bits, grey, palette, RGB, with or
 * without alpha), transparency (tRNS), Adam7 interlacing; checksums verified. 16-bit samples keep their
 * high byte. Damaged files are refused with a message.
 */
object Png {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    /** Largest image read (pixels): 64 million. */
    const val MAX_PIXELS = 64L * 1024 * 1024

    fun isPng(b: ByteArray) = b.size >= 8 && (0 until 8).all { b[it] == SIGNATURE[it] }

    fun decode(b: ByteArray): Image {
        require(isPng(b)) { "Not a PNG file" }
        var p = 8
        var width = 0; var height = 0; var depth = 0; var type = -1; var interlace = 0
        var palette: ByteArray? = null; var trns: ByteArray? = null
        val idat = Bytes()
        var ended = false
        while (!ended) {
            require(p + 12 <= b.size) { "PNG: truncated (no IEND)" }
            val len = be32(b, p); val name = latin1(b, p + 4, 4)
            require(len in 0..(b.size - p - 12)) { "PNG: chunk $name runs past the end" }
            val data = p + 8
            require(Crc32.of(b, p + 4, len + 4) == be32(b, data + len)) { "PNG: damaged chunk $name (checksum)" }
            when (name) {
                "IHDR" -> {
                    require(len == 13) { "PNG: bad IHDR" }
                    width = be32(b, data); height = be32(b, data + 4)
                    depth = b[data + 8].toInt() and 255; type = b[data + 9].toInt() and 255
                    require(b[data + 10].toInt() == 0 && b[data + 11].toInt() == 0) { "PNG: unknown compression or filter method" }
                    interlace = b[data + 12].toInt() and 255
                    require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS) { "PNG: size $width × $height refused" }
                    require(interlace <= 1) { "PNG: unknown interlace method" }
                    require(when (type) { 0 -> depth in intArrayOf(1, 2, 4, 8, 16); 3 -> depth in intArrayOf(1, 2, 4, 8); 2, 4, 6 -> depth == 8 || depth == 16; else -> false }) {
                        "PNG: colour type $type with $depth bits is not valid" }
                }
                "PLTE" -> { require(len % 3 == 0 && len in 3..768) { "PNG: bad palette" }; palette = b.copyOfRange(data, data + len) }
                "tRNS" -> trns = b.copyOfRange(data, data + len)
                "IDAT" -> idat.add(b, data, len)
                "IEND" -> ended = true
                else -> require(name[0].isLowerCase()) { "PNG: unknown critical chunk $name" }
            }
            p = data + len + 4
        }
        require(type >= 0) { "PNG: no IHDR" }
        require(type != 3 || palette != null) { "PNG: palette image without palette" }
        val channels = when (type) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; else -> 4 }
        val bitsPerPixel = channels * depth
        // The size of the raw rows: per pass when interlaced.
        val passes = if (interlace == 0) arrayOf(intArrayOf(0, 0, 1, 1)) else ADAM7
        var raw = 0L
        for (pass in passes) {
            val w = passWidth(width, pass); val h = passHeight(height, pass)
            if (w > 0 && h > 0) raw += h.toLong() * (1 + (w.toLong() * bitsPerPixel + 7) / 8)
        }
        require(raw <= Int.MAX_VALUE) { "PNG: too large" }
        val data = Inflate.zlib(idat.array, idat.size, raw.toInt())
        require(data.size == raw.toInt()) { "PNG: image data of ${data.size} bytes instead of $raw" }
        val out = ByteArray(width * height * 4)
        var at = 0
        val bpp = maxOf(1, bitsPerPixel / 8)
        for (pass in passes) {
            val w = passWidth(width, pass); val h = passHeight(height, pass)
            if (w == 0 || h == 0) continue
            val stride = (w * bitsPerPixel + 7) / 8
            unfilter(data, at, stride, h, bpp)
            for (y in 0 until h) {
                val row = at + y * (stride + 1) + 1
                for (x in 0 until w) {
                    val o = 4 * ((pass[1] + y * pass[3]) * width + pass[0] + x * pass[2])
                    pixel(data, row, x, type, depth, palette, trns, out, o)
                }
            }
            at += h * (stride + 1)
        }
        return Image(width, height, out)
    }

    // Adam7: x start, y start, x step, y step.
    private val ADAM7 = arrayOf(intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
        intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2))
    private fun passWidth(w: Int, p: IntArray) = if (w <= p[0]) 0 else (w - p[0] + p[2] - 1) / p[2]
    private fun passHeight(h: Int, p: IntArray) = if (h <= p[1]) 0 else (h - p[1] + p[3] - 1) / p[3]

    /** Undoes the row filters in place: each row is a filter byte, then [stride] bytes. */
    private fun unfilter(d: ByteArray, at: Int, stride: Int, rows: Int, bpp: Int) {
        for (y in 0 until rows) {
            val r = at + y * (stride + 1); val f = d[r].toInt() and 255; val c = r + 1; val up = c - (stride + 1)
            when (f) {
                0 -> {}
                1 -> for (i in bpp until stride) d[c + i] = (d[c + i] + d[c + i - bpp]).toByte()
                2 -> if (y > 0) for (i in 0 until stride) d[c + i] = (d[c + i] + d[up + i]).toByte()
                3 -> for (i in 0 until stride) {
                    val left = if (i >= bpp) d[c + i - bpp].toInt() and 255 else 0
                    val above = if (y > 0) d[up + i].toInt() and 255 else 0
                    d[c + i] = (d[c + i] + ((left + above) ushr 1)).toByte()
                }
                4 -> for (i in 0 until stride) {
                    val a = if (i >= bpp) d[c + i - bpp].toInt() and 255 else 0
                    val b = if (y > 0) d[up + i].toInt() and 255 else 0
                    val cc = if (i >= bpp && y > 0) d[up + i - bpp].toInt() and 255 else 0
                    val p = a + b - cc; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - cc)
                    d[c + i] = (d[c + i] + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else cc).toByte()
                }
                else -> throw IllegalArgumentException("PNG: unknown row filter $f")
            }
        }
    }

    /** One pixel of a filtered-out row to RGBA. */
    private fun pixel(d: ByteArray, row: Int, x: Int, type: Int, depth: Int, palette: ByteArray?, trns: ByteArray?, out: ByteArray, o: Int) {
        fun sample(i: Int): Int = when (depth) {
            8 -> d[row + i].toInt() and 255
            16 -> ((d[row + 2 * i].toInt() and 255) shl 8) or (d[row + 2 * i + 1].toInt() and 255)
            else -> { val bit = i * depth; ((d[row + bit / 8].toInt() and 255) shr (8 - depth - bit % 8)) and ((1 shl depth) - 1) }
        }
        fun to8(v: Int): Int = when (depth) { 16 -> v ushr 8; 8 -> v; else -> v * 255 / ((1 shl depth) - 1) }
        when (type) {
            0 -> {
                val v = sample(x); val g = to8(v).toByte()
                out[o] = g; out[o + 1] = g; out[o + 2] = g
                out[o + 3] = if (trns != null && trns.size >= 2 && v == be16(trns, 0)) 0 else -1
            }
            2 -> {
                val r = sample(3 * x); val g = sample(3 * x + 1); val b = sample(3 * x + 2)
                out[o] = to8(r).toByte(); out[o + 1] = to8(g).toByte(); out[o + 2] = to8(b).toByte()
                out[o + 3] = if (trns != null && trns.size >= 6 && r == be16(trns, 0) && g == be16(trns, 2) && b == be16(trns, 4)) 0 else -1
            }
            3 -> {
                val i = sample(x); val pal = palette!!
                require(3 * i + 2 < pal.size) { "PNG: palette index $i out of range" }
                out[o] = pal[3 * i]; out[o + 1] = pal[3 * i + 1]; out[o + 2] = pal[3 * i + 2]
                out[o + 3] = if (trns != null && i < trns.size) trns[i] else -1
            }
            4 -> { val g = to8(sample(2 * x)).toByte(); out[o] = g; out[o + 1] = g; out[o + 2] = g; out[o + 3] = to8(sample(2 * x + 1)).toByte() }
            else -> for (c in 0..3) out[o + c] = to8(sample(4 * x + c)).toByte()
        }
    }

    internal fun be32(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 24) or ((b[p + 1].toInt() and 255) shl 16) or
        ((b[p + 2].toInt() and 255) shl 8) or (b[p + 3].toInt() and 255)
    private fun be16(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
}

/** A growing byte array. */
internal class Bytes(capacity: Int = 1024) {
    var array = ByteArray(capacity); var size = 0
    fun add(b: ByteArray, from: Int, len: Int) {
        if (size + len > array.size) array = array.copyOf(maxOf(array.size * 2, size + len))
        b.copyInto(array, size, from, from + len); size += len
    }
}

/** CRC-32 (ISO 3309), as PNG uses it. */
internal object Crc32 {
    private val TABLE = IntArray(256) { n -> var c = n; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }; c }
    fun of(b: ByteArray, from: Int, len: Int): Int {
        var c = -1
        for (i in from until from + len) c = TABLE[(c xor b[i].toInt()) and 255] xor (c ushr 8)
        return c.inv()
    }
}

/**
 * Inflate (RFC 1950 zlib and RFC 1951 deflate): stored, fixed and dynamic Huffman blocks, decoded with
 * canonical Huffman tables (after M. Adler's puff). The output never grows past [expected] bytes.
 */
internal object Inflate {
    fun zlib(src: ByteArray, size: Int, expected: Int): ByteArray {
        require(size >= 6) { "zlib: truncated stream" }
        val cmf = src[0].toInt() and 255; val flg = src[1].toInt() and 255
        require(cmf and 15 == 8 && (cmf shl 8 or flg) % 31 == 0 && flg and 32 == 0) { "zlib: bad header" }
        val s = State(src, 2, size, expected)
        s.inflate()
        require(s.bytePos() + 4 <= size) { "zlib: truncated stream (no checksum)" }
        val adler = Png.be32(src, s.bytePos())
        require(adler == adler32(s.out, s.n)) { "zlib: damaged data (checksum)" }
        return if (s.n == s.out.size) s.out else s.out.copyOf(s.n)
    }

    // In Long: zlib's 5552 bytes between reductions are for unsigned 32-bit sums, which a signed Int
    // overflows on bright data (the checksum of a white image came out negative).
    private fun adler32(b: ByteArray, n: Int): Int {
        var a = 1L; var c = 0L; var i = 0
        while (i < n) { val end = minOf(n, i + 5552); while (i < end) { a += b[i].toInt() and 255; c += a; i++ }; a %= 65521; c %= 65521 }
        return ((c shl 16) or a).toInt()
    }

    private class Huffman(val count: ShortArray, val symbol: ShortArray)

    private val LBASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LEXT = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DBASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DEXT = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private fun build(lengths: IntArray, n: Int): Huffman {
        val count = ShortArray(16); val symbol = ShortArray(n)
        for (i in 0 until n) count[lengths[i]]++
        val offs = IntArray(16)
        for (len in 1 until 15) offs[len + 1] = offs[len] + count[len]
        for (i in 0 until n) if (lengths[i] != 0) symbol[offs[lengths[i]]++] = i.toShort()
        return Huffman(count, symbol)
    }

    private val FIXED_LENGTH: Huffman = build(IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } }, 288)
    private val FIXED_DISTANCE: Huffman = build(IntArray(30) { 5 }, 30)

    private class State(val src: ByteArray, var pos: Int, val end: Int, val expected: Int) {
        var out = ByteArray(maxOf(16, expected)); var n = 0
        var bitBuf = 0; var bitCount = 0

        fun bytePos() = pos

        fun bits(need: Int): Int {
            var v = bitBuf
            while (bitCount < need) {
                require(pos < end) { "deflate: truncated data" }
                v = v or ((src[pos++].toInt() and 255) shl bitCount); bitCount += 8
            }
            bitBuf = v ushr need; bitCount -= need
            return v and ((1 shl need) - 1)
        }

        fun decode(h: Huffman): Int {
            var code = 0; var first = 0; var index = 0
            for (len in 1..15) {
                code = code or bits(1)
                val count = h.count[len].toInt()
                if (code - count < first) return h.symbol[index + (code - first)].toInt()
                index += count; first += count; first = first shl 1; code = code shl 1
            }
            throw IllegalArgumentException("deflate: bad Huffman code")
        }

        fun put(b: Byte) {
            require(n < expected) { "deflate: more data than the image holds" }
            if (n == out.size) out = out.copyOf(out.size * 2)
            out[n++] = b
        }

        fun inflate() {
            do {
                val last = bits(1)
                when (bits(2)) {
                    0 -> stored()
                    1 -> codes(FIXED_LENGTH, FIXED_DISTANCE)
                    2 -> dynamic()
                    else -> throw IllegalArgumentException("deflate: bad block type")
                }
            } while (last == 0)
            bitBuf = 0; bitCount = 0
        }

        fun stored() {
            bitBuf = 0; bitCount = 0
            require(pos + 4 <= end) { "deflate: truncated stored block" }
            val len = (src[pos].toInt() and 255) or ((src[pos + 1].toInt() and 255) shl 8)
            val nlen = (src[pos + 2].toInt() and 255) or ((src[pos + 3].toInt() and 255) shl 8)
            require(len == nlen.inv() and 0xFFFF) { "deflate: bad stored block" }
            pos += 4
            require(pos + len <= end) { "deflate: truncated stored block" }
            for (i in 0 until len) put(src[pos + i])
            pos += len
        }

        fun codes(lengths: Huffman, distances: Huffman) {
            while (true) {
                var sym = decode(lengths)
                if (sym < 256) { put(sym.toByte()); continue }
                if (sym == 256) return
                sym -= 257
                require(sym < 29) { "deflate: bad length code" }
                val len = LBASE[sym] + bits(LEXT[sym])
                val ds = decode(distances)
                require(ds < 30) { "deflate: bad distance code" }
                val dist = DBASE[ds] + bits(DEXT[ds])
                require(dist <= n) { "deflate: distance too far back" }
                for (i in 0 until len) put(out[n - dist])
            }
        }

        fun dynamic() {
            val nlen = bits(5) + 257; val ndist = bits(5) + 1; val ncode = bits(4) + 4
            require(nlen <= 286 && ndist <= 30) { "deflate: bad code counts" }
            val lengths = IntArray(320)
            for (i in 0 until ncode) lengths[ORDER[i]] = bits(3)
            val lencode = build(lengths, 19)
            var i = 0
            val all = IntArray(nlen + ndist)
            while (i < nlen + ndist) {
                val sym = decode(lencode)
                if (sym < 16) { all[i++] = sym; continue }
                var len = 0; val repeat: Int
                when (sym) {
                    16 -> { require(i > 0) { "deflate: repeat with no previous length" }; len = all[i - 1]; repeat = 3 + bits(2) }
                    17 -> repeat = 3 + bits(3)
                    else -> repeat = 11 + bits(7)
                }
                require(i + repeat <= nlen + ndist) { "deflate: too many lengths" }
                repeat(repeat) { all[i++] = len }
            }
            require(all[256] != 0) { "deflate: no end-of-block code" }
            codes(build(all.copyOfRange(0, nlen), nlen), build(all.copyOfRange(nlen, nlen + ndist), ndist))
        }
    }
}
