package sillage.io

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

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
        val candidate = ByteArray(stride)
        for (y in 0 until height) {
            val row = y * stride; val up = row - stride
            var best = 0; var bestScore = Long.MAX_VALUE
            for (f in 0..4) {
                var score = 0L
                for (x in 0 until stride) {
                    val cur = rgb[row + x].toInt() and 255
                    val a = if (x >= 3) rgb[row + x - 3].toInt() and 255 else 0
                    val b = if (y > 0) rgb[up + x].toInt() and 255 else 0
                    val c = if (x >= 3 && y > 0) rgb[up + x - 3].toInt() and 255 else 0
                    val v = when (f) { 0 -> cur; 1 -> cur - a; 2 -> cur - b; 3 -> cur - ((a + b) ushr 1); else -> cur - paeth(a, b, c) } and 255
                    candidate[x] = v.toByte()
                    score += if (v < 128) v else 256 - v
                }
                if (score < bestScore) { bestScore = score; best = f; System.arraycopy(candidate, 0, raw, y * (stride + 1) + 1, stride) }
            }
            raw[y * (stride + 1)] = best.toByte()
        }
        val out = ByteArrayOutputStream(raw.size / 2 + 1024)
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
    fun write(path: Path, width: Int, height: Int, rgb: ByteArray) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.write(path, encode(width, height, rgb))
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun put32(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte() }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
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

    /** A literal or a length code with the fixed Huffman table of deflate. */
    private fun literal(o: Bits, v: Int) = when {
        v < 144 -> o.code(0x30 + v, 8)
        v < 256 -> o.code(0x190 + v - 144, 9)
        v < 280 -> o.code(v - 256, 7)
        else -> o.code(0xC0 + v - 280, 8)
    }

    private fun match(o: Bits, length: Int, distance: Int) {
        var li = LENGTH_BASE.size - 1; while (LENGTH_BASE[li] > length) li--
        literal(o, 257 + li); if (LENGTH_EXTRA[li] > 0) o.put(length - LENGTH_BASE[li], LENGTH_EXTRA[li])
        var di = DIST_BASE.size - 1; while (DIST_BASE[di] > distance) di--
        o.code(di, 5); if (DIST_EXTRA[di] > 0) o.put(distance - DIST_BASE[di], DIST_EXTRA[di])
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
        var a = 1; var b = 0
        for (x in data) { a = (a + (x.toInt() and 255)) % 65521; b = (b + a) % 65521 }
        val adler = (b shl 16) or a
        o.byte(adler ushr 24); o.byte(adler ushr 16); o.byte(adler ushr 8); o.byte(adler)
        return o.buf.copyOf(o.size)
    }
}
