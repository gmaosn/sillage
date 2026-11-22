package sillage.io

import kotlin.math.roundToInt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * JPEG decoder, in Kotlin: baseline and extended sequential (8 bits), progressive (spectral selection
 * and successive approximation), restart intervals, any chroma subsampling, greyscale, YCbCr and RGB
 * (Adobe). Chroma is upsampled by centred linear interpolation, as libjpeg's "fancy" upsampling.
 * CMYK, lossless, 12-bit and arithmetic-coded files are refused with a message.
 */
object Jpeg {
    fun isJpeg(b: ByteArray) = b.size >= 4 && (b[0].toInt() and 255) == 0xFF && (b[1].toInt() and 255) == 0xD8

    fun decode(b: ByteArray): Image = Decoder(b).run()

    /** Zigzag order: the k-th coefficient of a scan is at ZIGZAG[k] in the 8 × 8 block (row-major). */
    private val ZIGZAG = intArrayOf(0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21,
        28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63)

    /** IDCT basis: COS[x * 8 + u] = C(u) / 2 · cos((2x + 1) u π / 16). */
    private val COS = FloatArray(64) { i -> val x = i / 8; val u = i % 8; ((if (u == 0) 1 / sqrt(2.0) else 1.0) / 2 * cos((2 * x + 1) * u * PI / 16)).toFloat() }

    private class Huffman(counts: IntArray, val symbols: IntArray) {
        val maxCode = IntArray(18) { -1 }; val first = IntArray(17); val index = IntArray(17)
        init {
            var code = 0; var k = 0
            for (len in 1..16) {
                first[len] = code; index[len] = k
                code += counts[len]; k += counts[len]
                maxCode[len] = if (counts[len] > 0) code - 1 else -1
                code = code shl 1
            }
            maxCode[17] = Int.MAX_VALUE
        }
    }

    private class Component(val id: Int, val h: Int, val v: Int, val tq: Int) {
        var blocksPerLine = 0; var blocksPerColumn = 0
        lateinit var coefficients: ShortArray
        var pred = 0
        var dc: Huffman? = null; var ac: Huffman? = null
        lateinit var plane: ByteArray               // the component's samples, blocksPerLine × 8 wide
    }

    private class Decoder(val b: ByteArray) {
        val quant = arrayOfNulls<IntArray>(4)
        val dcTables = arrayOfNulls<Huffman>(4); val acTables = arrayOfNulls<Huffman>(4)
        var components = ArrayList<Component>()
        var width = 0; var height = 0; var progressive = false
        var hMax = 1; var vMax = 1; var mcusX = 0; var mcusY = 0
        var restartInterval = 0
        var adobeTransform = -1
        var pos = 2

        fun u16(p: Int): Int { require(p + 1 < b.size) { "JPEG: truncated" }; return ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255) }

        fun run(): Image {
            var frame = false
            while (true) {
                // The next marker (fill bytes 0xFF allowed).
                require(pos + 1 < b.size) { "JPEG: truncated (no end of image)" }
                require((b[pos].toInt() and 255) == 0xFF) { "JPEG: marker expected at byte $pos" }
                while (pos < b.size && (b[pos].toInt() and 255) == 0xFF) pos++
                require(pos < b.size) { "JPEG: truncated" }
                val m = b[pos++].toInt() and 255
                when (m) {
                    0xD8 -> {}
                    0xD9 -> break
                    in 0xD0..0xD7 -> {}
                    else -> {
                        val len = u16(pos)
                        require(len >= 2 && pos + len <= b.size) { "JPEG: segment runs past the end" }
                        val start = pos + 2; val end = pos + len
                        when (m) {
                            0xDB -> quantization(start, end)
                            0xC4 -> huffman(start, end)
                            0xC0, 0xC1, 0xC2 -> { require(!frame) { "JPEG: two frames" }; frame(start, m == 0xC2); frame = true }
                            0xC3, in 0xC5..0xC7, in 0xC9..0xCB, in 0xCD..0xCF -> throw IllegalArgumentException("JPEG: lossless, hierarchical or arithmetic coding is not supported")
                            0xDD -> restartInterval = u16(start)
                            0xDA -> { require(frame) { "JPEG: scan before the frame" }; pos = scan(start); continue }
                            0xEE -> if (len >= 14 && latin1(b, start, 5) == "Adobe") adobeTransform = b[start + 11].toInt() and 255
                            else -> {}
                        }
                        pos = end
                    }
                }
            }
            require(frame) { "JPEG: no frame" }
            return output()
        }

        fun quantization(start: Int, end: Int) {
            var p = start
            while (p < end) {
                val pq = (b[p].toInt() and 255) shr 4; val tq = b[p].toInt() and 15; p++
                require(tq < 4 && pq <= 1) { "JPEG: bad quantization table" }
                val t = IntArray(64)
                for (k in 0 until 64) { t[ZIGZAG[k]] = if (pq == 0) b[p + k].toInt() and 255 else u16(p + 2 * k) }
                p += if (pq == 0) 64 else 128
                quant[tq] = t
            }
        }

        fun huffman(start: Int, end: Int) {
            var p = start
            while (p < end) {
                val tc = (b[p].toInt() and 255) shr 4; val th = b[p].toInt() and 15
                require(tc <= 1 && th < 4 && p + 17 <= end) { "JPEG: bad Huffman table" }
                val counts = IntArray(17); var total = 0
                for (i in 1..16) { counts[i] = b[p + i].toInt() and 255; total += counts[i] }
                require(total <= 256 && p + 17 + total <= end) { "JPEG: bad Huffman table" }
                val symbols = IntArray(total) { b[p + 17 + it].toInt() and 255 }
                val t = Huffman(counts, symbols)
                if (tc == 0) dcTables[th] = t else acTables[th] = t
                p += 17 + total
            }
        }

        fun frame(p: Int, progressive: Boolean) {
            this.progressive = progressive
            require((b[p].toInt() and 255) == 8) { "JPEG: only 8-bit samples are read" }
            height = u16(p + 1); width = u16(p + 3)
            require(width > 0 && height > 0) { "JPEG: unknown size (DNL) or empty image" }
            require(width.toLong() * height <= Png.MAX_PIXELS) { "JPEG: size $width × $height refused" }
            val n = b[p + 5].toInt() and 255
            require(n == 1 || n == 3) { if (n == 4) "JPEG: CMYK is not supported" else "JPEG: $n components" }
            for (i in 0 until n) {
                val q = p + 6 + 3 * i
                val hv = b[q + 1].toInt() and 255
                val c = Component(b[q].toInt() and 255, hv shr 4, hv and 15, b[q + 2].toInt() and 255)
                require(c.h in 1..4 && c.v in 1..4 && c.tq < 4) { "JPEG: bad component" }
                components.add(c)
            }
            hMax = components.maxOf { it.h }; vMax = components.maxOf { it.v }
            mcusX = (width + 8 * hMax - 1) / (8 * hMax); mcusY = (height + 8 * vMax - 1) / (8 * vMax)
            for (c in components) {
                c.blocksPerLine = mcusX * c.h; c.blocksPerColumn = mcusY * c.v
                c.coefficients = ShortArray(c.blocksPerLine * c.blocksPerColumn * 64)
            }
        }

        // ------------------------------------------------------------ entropy-coded data

        var p = 0; var bitBuf = 0; var bitCount = 0; var marker = -1; var eobRun = 0

        fun fill() {
            // One more byte into the bit buffer; at a marker, zeros (the scan is over or a restart comes).
            var v = 0
            if (marker < 0 && p < b.size) {
                val x = b[p].toInt() and 255
                if (x == 0xFF) {
                    val y = if (p + 1 < b.size) b[p + 1].toInt() and 255 else 0xD9
                    if (y == 0) { v = 0xFF; p += 2 } else marker = y
                } else { v = x; p++ }
            }
            bitBuf = (bitBuf shl 8) or v; bitCount += 8
        }

        fun bit(): Int { if (bitCount == 0) fill(); bitCount--; return (bitBuf ushr bitCount) and 1 }

        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }

        fun extend(v: Int, s: Int) = if (s == 0) 0 else if (v < (1 shl (s - 1))) v - (1 shl s) + 1 else v

        fun decode(h: Huffman): Int {
            var code = 0
            for (len in 1..16) {
                code = (code shl 1) or bit()
                if (code <= h.maxCode[len]) {
                    val i = h.index[len] + code - h.first[len]
                    require(i in h.symbols.indices) { "JPEG: bad Huffman code" }
                    return h.symbols[i]
                }
            }
            throw IllegalArgumentException("JPEG: bad Huffman code")
        }

        /** Reads one scan from [start] (the SOS segment); returns where the next marker is. */
        fun scan(start: Int): Int {
            val len = u16(start - 2)
            val n = b[start].toInt() and 255
            require(n in 1..4 && len == 6 + 2 * n) { "JPEG: bad scan header" }
            val scan = ArrayList<Component>()
            for (i in 0 until n) {
                val id = b[start + 1 + 2 * i].toInt() and 255; val t = b[start + 2 + 2 * i].toInt() and 255
                val c = components.firstOrNull { it.id == id } ?: throw IllegalArgumentException("JPEG: scan of an unknown component")
                c.dc = dcTables[t shr 4]; c.ac = acTables[t and 15]
                scan.add(c)
            }
            val q = start + 1 + 2 * n
            val ss = b[q].toInt() and 255; val se = b[q + 1].toInt() and 255
            val ah = (b[q + 2].toInt() and 255) shr 4; val al = b[q + 2].toInt() and 15
            if (!progressive) require(ss == 0 && se == 63 && ah == 0 && al == 0) { "JPEG: bad sequential scan" }
            else require(ss <= se && se <= 63 && (ss > 0 || se == 0) && (ss == 0 || n == 1)) { "JPEG: bad progressive scan" }
            p = q + 3; bitBuf = 0; bitCount = 0; marker = -1; eobRun = 0
            for (c in scan) c.pred = 0
            val block: (Component, Int) -> Unit = when {
                !progressive -> { c, o -> sequential(c, o) }
                ss == 0 && ah == 0 -> { c, o -> dcFirst(c, o, al) }
                ss == 0 -> { c, o -> dcRefine(c, o, al) }
                ah == 0 -> { c, o -> acFirst(c, o, ss, se, al) }
                else -> { c, o -> acRefine(c, o, ss, se, al) }
            }
            for (c in scan) {
                require(progressive && (ss > 0 || ah > 0) || c.dc != null) { "JPEG: missing DC table" }
                require(ss == 0 && progressive || c.ac != null) { "JPEG: missing AC table" }
            }
            var mcu = 0
            if (n == 1) {
                // One component: its blocks one by one, only those over the image.
                val c = scan[0]
                val w = (((width * c.h + hMax - 1) / hMax) + 7) / 8; val h = (((height * c.v + vMax - 1) / vMax) + 7) / 8
                for (by in 0 until h) for (bx in 0 until w) {
                    restart(mcu++)
                    block(c, (by * c.blocksPerLine + bx) * 64)
                }
            } else {
                for (my in 0 until mcusY) for (mx in 0 until mcusX) {
                    restart(mcu++)
                    for (c in scan) for (v in 0 until c.v) for (h in 0 until c.h)
                        block(c, ((my * c.v + v) * c.blocksPerLine + mx * c.h + h) * 64)
                }
            }
            // On to the next marker that is not a restart.
            var e = if (marker >= 0) p else p
            while (e + 1 < b.size && !((b[e].toInt() and 255) == 0xFF && (b[e + 1].toInt() and 255).let { it != 0 && it !in 0xD0..0xD7 })) e++
            return e
        }

        /** Before MCU number [mcu]: at each restart interval, the RSTn marker, then fresh predictions. */
        fun restart(mcu: Int) {
            if (restartInterval == 0 || mcu == 0 || mcu % restartInterval != 0) return
            bitBuf = 0; bitCount = 0
            if (marker < 0) {
                // Skip to the marker (padding bits are ignored).
                while (p + 1 < b.size && !((b[p].toInt() and 255) == 0xFF && (b[p + 1].toInt() and 255) != 0)) p++
                marker = if (p + 1 < b.size) b[p + 1].toInt() and 255 else 0xD9
            }
            require(marker in 0xD0..0xD7) { "JPEG: restart marker expected" }
            p += 2; marker = -1; eobRun = 0
            for (c in components) c.pred = 0
        }

        fun sequential(c: Component, o: Int) {
            val k0 = c.coefficients
            val t = decode(c.dc!!)
            c.pred += extend(bits(t), t)
            k0[o] = c.pred.toShort()
            var k = 1
            val ac = c.ac!!
            while (k < 64) {
                val rs = decode(ac); val r = rs shr 4; val s = rs and 15
                if (s == 0) { if (r == 15) { k += 16; continue }; break }
                k += r
                require(k < 64) { "JPEG: coefficient out of the block" }
                k0[o + ZIGZAG[k]] = extend(bits(s), s).toShort()
                k++
            }
        }

        fun dcFirst(c: Component, o: Int, al: Int) {
            val t = decode(c.dc!!)
            c.pred += extend(bits(t), t)
            c.coefficients[o] = (c.pred shl al).toShort()
        }

        fun dcRefine(c: Component, o: Int, al: Int) {
            if (bit() != 0) c.coefficients[o] = (c.coefficients[o].toInt() or (1 shl al)).toShort()
        }

        fun acFirst(c: Component, o: Int, ss: Int, se: Int, al: Int) {
            if (eobRun > 0) { eobRun--; return }
            var k = ss
            val ac = c.ac!!
            while (k <= se) {
                val rs = decode(ac); val r = rs shr 4; val s = rs and 15
                if (s == 0) {
                    if (r < 15) { eobRun = (1 shl r) - 1; if (r > 0) eobRun += bits(r); break }
                    k += 16; continue
                }
                k += r
                require(k <= se) { "JPEG: coefficient out of the band" }
                c.coefficients[o + ZIGZAG[k]] = (extend(bits(s), s) * (1 shl al)).toShort()
                k++
            }
        }

        /** Successive approximation of the AC coefficients (libjpeg's decode_mcu_AC_refine). */
        fun acRefine(c: Component, o: Int, ss: Int, se: Int, al: Int) {
            val co = c.coefficients
            val p1 = 1 shl al; val m1 = -1 shl al
            var k = ss
            if (eobRun == 0) {
                val ac = c.ac!!
                while (k <= se) {
                    val rs = decode(ac); var r = rs shr 4; var s = rs and 15
                    if (s != 0) {
                        require(s == 1) { "JPEG: bad refinement" }
                        s = if (bit() != 0) p1 else m1
                    } else if (r != 15) {
                        eobRun = 1 shl r
                        if (r > 0) eobRun += bits(r)
                        break
                    }
                    while (k <= se) {
                        val z = o + ZIGZAG[k]; val v = co[z].toInt()
                        if (v != 0) {
                            if (bit() != 0 && (v and p1) == 0) co[z] = (if (v >= 0) v + p1 else v + m1).toShort()
                        } else {
                            if (r == 0) break
                            r--
                        }
                        k++
                    }
                    if (s != 0 && k <= se) co[o + ZIGZAG[k]] = s.toShort()
                    k++
                }
            }
            if (eobRun > 0) {
                while (k <= se) {
                    val z = o + ZIGZAG[k]; val v = co[z].toInt()
                    if (v != 0 && bit() != 0 && (v and p1) == 0) co[z] = (if (v >= 0) v + p1 else v + m1).toShort()
                    k++
                }
                eobRun--
            }
        }

        // ------------------------------------------------------------ samples

        /** Dequantizes and inverse-transforms every block into each component's plane, then builds RGBA. */
        fun output(): Image {
            val tmp = FloatArray(64)
            for (c in components) {
                val q = requireNotNull(quant[c.tq]) { "JPEG: missing quantization table" }
                val w = c.blocksPerLine * 8
                c.plane = ByteArray(w * c.blocksPerColumn * 8)
                val co = c.coefficients
                for (by in 0 until c.blocksPerColumn) for (bx in 0 until c.blocksPerLine) {
                    val o = (by * c.blocksPerLine + bx) * 64
                    // Rows: tmp[y * 8 + x] = Σu C(u) cos(...) F(y, u).
                    for (y in 0 until 8) for (x in 0 until 8) {
                        var s = 0f
                        for (u in 0 until 8) s += COS[x * 8 + u] * co[o + y * 8 + u] * q[y * 8 + u]
                        tmp[y * 8 + x] = s
                    }
                    for (x in 0 until 8) for (y in 0 until 8) {
                        var s = 0f
                        for (v in 0 until 8) s += COS[y * 8 + v] * tmp[v * 8 + x]
                        val value = (s + 128f).roundToInt().coerceIn(0, 255)
                        c.plane[(by * 8 + y) * w + bx * 8 + x] = value.toByte()
                    }
                }
            }
            val out = ByteArray(width * height * 4)
            val rgb = components.size == 3 && (adobeTransform == 0 || (adobeTransform < 0 &&
                components[0].id == 'R'.code && components[1].id == 'G'.code && components[2].id == 'B'.code))
            for (y in 0 until height) for (x in 0 until width) {
                val o = 4 * (y * width + x)
                if (components.size == 1) {
                    val g = sample(components[0], x, y).toInt().toByte()
                    out[o] = g; out[o + 1] = g; out[o + 2] = g
                } else {
                    val a = sample(components[0], x, y); val bb = sample(components[1], x, y); val cc = sample(components[2], x, y)
                    if (rgb) { out[o] = clamp(a); out[o + 1] = clamp(bb); out[o + 2] = clamp(cc) }
                    else {
                        val cb = bb - 128f; val cr = cc - 128f
                        out[o] = clamp(a + 1.402f * cr); out[o + 1] = clamp(a - .344136f * cb - .714136f * cr); out[o + 2] = clamp(a + 1.772f * cb)
                    }
                }
                out[o + 3] = -1
            }
            return Image(width, height, out)
        }

        private fun clamp(v: Float): Byte = v.roundToInt().coerceIn(0, 255).toByte()

        /** The component's value at image pixel (x, y), its samples centred on their area and interpolated. */
        private fun sample(c: Component, x: Int, y: Int): Float {
            val w = c.blocksPerLine * 8
            if (c.h == hMax && c.v == vMax) return (c.plane[y * w + x].toInt() and 255).toFloat()
            val cw = (width * c.h + hMax - 1) / hMax; val ch = (height * c.v + vMax - 1) / vMax
            val fx = ((x + .5f) * c.h / hMax - .5f).coerceIn(0f, (cw - 1).toFloat())
            val fy = ((y + .5f) * c.v / vMax - .5f).coerceIn(0f, (ch - 1).toFloat())
            val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
            val x1 = minOf(x0 + 1, cw - 1); val y1 = minOf(y0 + 1, ch - 1)
            val tx = fx - x0; val ty = fy - y0
            fun at(px: Int, py: Int) = (c.plane[py * w + px].toInt() and 255).toFloat()
            val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * tx
            val bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * tx
            return top + (bottom - top) * ty
        }
    }
}
