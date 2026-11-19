package sillage.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Ogg Vorbis files, decoded by the engine (no library): the Ogg pages and their checksums, the Vorbis I
 * headers (codebooks, floor 1, residues 0 to 2, channel coupling) and the audio packets, through an
 * inverse MDCT computed with an FFT. Mono and stereo. Written after the Vorbis I specification (Xiph.Org);
 * checked against libvorbis, the reference decoder, sample by sample.
 *
 * Floor 0 (not produced by encoders since 2001) and files of more than two channels are refused.
 */
object Vorbis {
    /** Whether [bytes] start like an Ogg file. */
    fun isOgg(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
        bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()

    /** Decodes a whole Ogg Vorbis file into a [Sound]. Damaged files are refused with an [IllegalArgumentException]. */
    fun decode(bytes: ByteArray): Sound {
        val ogg = OggPackets(bytes)
        require(ogg.packets.size >= 3) { "Ogg Vorbis: missing headers" }
        val d = VorbisDecoder(ogg.packets[0], ogg.packets[2])
        val channels = d.channels
        // Decoded at the stream's rate, into growing per-channel buffers.
        var out = Array(channels) { FloatArray(d.rate * 2) }
        var n = 0
        var start = 0
        var firstPage = true
        for (k in 3 until ogg.packets.size) {
            val got = d.decode(ogg.packets[k])
            if (got > 0) {
                if (n + got > out[0].size) out = Array(channels) { c -> out[c].copyOf(maxOf(out[c].size * 2, n + got)) }
                for (c in 0 until channels) System.arraycopy(d.output[c], 0, out[c], n, got)
                n += got
            }
            // The first audio page's granule is the position of its last sample: decoded beyond it, the
            // stream starts later than zero and the difference goes (as libvorbis does).
            // A first page that also ends the stream is trimmed at its end instead (spec, appendix A.2).
            if (ogg.pageEnd[k] && firstPage) {
                firstPage = false
                if (!ogg.lastPage[k] && ogg.granule[k] in 0 until n) start = (n - ogg.granule[k]).toInt()
            }
        }
        // The last granule position is the true length: the end of the last packet is padding.
        val last = ogg.lastGranule
        val end = if (last >= 0) min(n.toLong(), start + last).toInt() else n
        val frames = maxOf(0, end - start)
        val data = FloatArray(frames * channels)
        for (i in 0 until frames) for (c in 0 until channels) data[i * channels + c] = out[c][start + i]
        return Sound(data, channels, d.rate)
    }
}

// ---------------------------------------------------------------------------------------------- Ogg

/** The packets of the first logical stream of an Ogg file, with the granule of the page each ends on. */
private class OggPackets(b: ByteArray) {
    val packets = ArrayList<ByteArray>()
    val granule = ArrayList<Long>()             // per packet: the granule of the page it ends on (−1: none)
    val pageEnd = ArrayList<Boolean>()          // per packet: it is the last packet ending on its page
    val lastPage = ArrayList<Boolean>()         // per packet: it ends on the stream's last page
    var lastGranule = -1L

    init {
        var p = 0
        var serial = -1
        val pending = java.io.ByteArrayOutputStream()
        var pages = 0
        while (p + 27 <= b.size) {
            require(b[p] == 'O'.code.toByte() && b[p + 1] == 'g'.code.toByte() && b[p + 2] == 'g'.code.toByte() && b[p + 3] == 'S'.code.toByte()) {
                "Ogg: page ${pages + 1} does not start with OggS" }
            require(b[p + 4].toInt() == 0) { "Ogg: unknown version" }
            val type = b[p + 5].toInt()
            val gran = le64(b, p + 6)
            val ser = le32(b, p + 14)
            val crc = le32(b, p + 22)
            val segments = b[p + 26].toInt() and 255
            require(p + 27 + segments <= b.size) { "Ogg: truncated page" }
            var size = 0
            for (s in 0 until segments) size += b[p + 27 + s].toInt() and 255
            val total = 27 + segments + size
            require(p + total <= b.size) { "Ogg: truncated page" }
            // The checksum covers the page with its checksum field as zeros.
            require(oggCrc(b, p, total) == crc) { "Ogg: page ${pages + 1} is damaged (checksum)" }
            pages++
            if (serial == -1) serial = ser
            if (ser != serial) { p += total; continue }      // another logical stream (chained or multiplexed): ignored
            if (type and 1 == 0 && pending.size() > 0) pending.reset()   // a packet left unfinished by a lost page
            var q = p + 27 + segments
            var lastIndex = -1
            for (s in 0 until segments) {
                val l = b[p + 27 + s].toInt() and 255
                pending.write(b, q, l); q += l
                if (l < 255) {
                    packets.add(pending.toByteArray()); pending.reset()
                    granule.add(-1L); pageEnd.add(false); lastPage.add(type and 4 != 0)
                    lastIndex = packets.size - 1
                }
            }
            if (lastIndex >= 0) { granule[lastIndex] = gran; pageEnd[lastIndex] = true }
            if (gran >= 0 && lastIndex >= 0) lastGranule = gran
            p += total
            if (type and 4 != 0) break                      // end of stream
        }
        require(pages > 0) { "Not an Ogg file" }
    }

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or
        ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)
    private fun le64(b: ByteArray, o: Int) = (le32(b, o).toLong() and 0xffffffffL) or (le32(b, o + 4).toLong() shl 32)

    companion object {
        private val table = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
            r
        }
        fun oggCrc(b: ByteArray, from: Int, length: Int): Int {
            var crc = 0
            for (i in 0 until length) {
                val v = if (i in 22..25) 0 else b[from + i].toInt() and 255
                crc = (crc shl 8) xor table[((crc ushr 24) xor v) and 255]
            }
            return crc
        }
    }
}

// ---------------------------------------------------------------------------------------------- bits

/** Vorbis bit reading: least significant bit first. Past the end, reads give 0 and [eop] is set. */
private class Bits(private val data: ByteArray) {
    private var pos = 0
    private var acc = 0L
    private var count = 0                      // valid bits in acc
    var eop = false

    private fun fill() {
        while (count <= 56 && pos < data.size) { acc = acc or ((data[pos].toLong() and 255) shl count); pos++; count += 8 }
    }
    fun read(n: Int): Int {
        if (n == 0) return 0
        if (count < n) fill()
        if (count < n) { eop = true; count = 0; acc = 0; return 0 }
        val v = (acc and ((1L shl n) - 1)).toInt()
        acc = acc ushr n; count -= n
        return v
    }
    fun bit() = read(1) == 1
    /** The next [n] bits (n ≤ 32), padded with zeros past the end, not consumed. */
    fun peek(n: Int): Long { if (count < n) fill(); return acc and ((1L shl n) - 1) }
    /** Consumes [n] bits; false (and [eop]) when there were fewer. */
    fun skip(n: Int): Boolean {
        if (count < n) fill()
        if (count < n) { eop = true; count = 0; acc = 0; return false }
        acc = acc ushr n; count -= n
        return true
    }
}

private fun ilog(x: Int): Int { var v = x; var r = 0; while (v > 0) { r++; v = v ushr 1 }; return r }

/** Vorbis's 32-bit float: 21-bit mantissa, 10-bit exponent biased by 788, a sign. */
private fun float32(x: Int): Float {
    var mantissa = (x and 0x1fffff).toDouble()
    if (x and 0x80000000.toInt() != 0) mantissa = -mantissa
    val exponent = (x and 0x7fe00000) ushr 21
    return (mantissa * 2.0.pow(exponent - 788)).toFloat()
}

// ---------------------------------------------------------------------------------------------- codebooks

private class Codebook(b: Bits) {
    val dimensions: Int
    val entries: Int
    private val lengths: IntArray
    private val fast = IntArray(1 shl FAST) { -1 }
    private var longEntries = IntArray(0); private var longCodes = LongArray(0)
    /** The VQ vector of each entry ([dimensions] floats each), or null for a scalar book. */
    var vectors: FloatArray? = null; private set

    init {
        require(b.read(24) == 0x564342) { "Vorbis: bad codebook" }
        dimensions = b.read(16); entries = b.read(24)
        // Bounds before any allocation: a damaged (or hostile) header could ask for gigabytes.
        require(entries in 1..MAX_ENTRIES && dimensions in 1..MAX_DIMENSIONS) { "Vorbis: bad codebook size" }
        lengths = IntArray(entries)
        if (b.bit()) {
            // Ordered: runs of entries of growing lengths.
            var current = 0; var length = b.read(5) + 1
            while (current < entries) {
                val number = b.read(ilog(entries - current))
                require(current + number <= entries && length <= 32) { "Vorbis: bad ordered codebook" }
                for (i in current until current + number) lengths[i] = length
                current += number; length++
            }
        } else {
            val sparse = b.bit()
            for (i in 0 until entries) lengths[i] = if (!sparse || b.bit()) b.read(5) + 1 else 0
        }
        buildCodes()
        val lookup = b.read(4)
        require(lookup <= 2) { "Vorbis: bad codebook lookup type" }
        if (lookup > 0) {
            val minimum = float32(b.read(32)); val delta = float32(b.read(32))
            val bits = b.read(4) + 1; val sequence = b.bit()
            val values = if (lookup == 1) lookup1Values(entries, dimensions) else entries * dimensions
            require(entries.toLong() * dimensions <= MAX_VECTOR_FLOATS && values.toLong() * bits <= MAX_VECTOR_FLOATS * 8L) { "Vorbis: codebook too large" }
            val multiplicands = IntArray(values) { b.read(bits) }
            require(!b.eop) { "Vorbis: truncated codebook" }
            val v = FloatArray(entries * dimensions)
            for (e in 0 until entries) {
                var last = 0f
                if (lookup == 1) {
                    var divisor = 1
                    for (i in 0 until dimensions) {
                        val m = (e / divisor) % values
                        val x = multiplicands[m] * delta + minimum + last
                        v[e * dimensions + i] = x
                        if (sequence) last = x
                        divisor *= values
                    }
                } else {
                    for (i in 0 until dimensions) {
                        val x = multiplicands[e * dimensions + i] * delta + minimum + last
                        v[e * dimensions + i] = x
                        if (sequence) last = x
                    }
                }
            }
            vectors = v
        }
    }

    /** Canonical codewords (spec 3.2.1): each entry takes the lowest free codeword of its length. */
    private fun buildCodes() {
        val available = LongArray(33)
        val codes = LongArray(entries)
        var first = true
        val longs = ArrayList<Int>()
        for (i in 0 until entries) {
            val len = lengths[i]
            if (len == 0) continue
            var code: Long
            if (first) {
                first = false; code = 0
                for (k in 1..len) available[k] = 1L shl (32 - k)
            } else {
                var z = len
                while (z > 0 && available[z] == 0L) z--
                require(z > 0) { "Vorbis: overspecified codebook" }
                val res = available[z]; available[z] = 0
                code = res
                for (y in len downTo z + 1) available[y] = res + (1L shl (32 - y))
            }
            // Bits are read from the stream least significant first: the codeword's first bit is its highest.
            val reversed = java.lang.Long.reverse(code shl 32) and 0xffffffffL
            codes[i] = reversed
            if (len <= FAST) {
                val step = 1 shl len
                var k = reversed.toInt()
                while (k < (1 shl FAST)) { fast[k] = i; k += step }
            } else longs.add(i)
        }
        longEntries = longs.toIntArray(); longCodes = LongArray(longs.size) { codes[longs[it]] }
    }

    /** The next entry, or −1 at the end of the packet (or on a codeword that is not in the book). */
    fun decode(b: Bits): Int {
        val e = fast[b.peek(FAST).toInt()]
        if (e >= 0) return if (b.skip(lengths[e])) e else -1
        val p = b.peek(32)
        for (k in longEntries.indices) {
            val len = lengths[longEntries[k]]
            if ((p and ((1L shl len) - 1)) == longCodes[k]) return if (b.skip(len)) longEntries[k] else -1
        }
        b.eop = true
        return -1
    }

    companion object {
        const val FAST = 10
        const val MAX_ENTRIES = 1 shl 20
        const val MAX_DIMENSIONS = 256
        const val MAX_VECTOR_FLOATS = 1L shl 22
        /** The greatest r with r^dimensions ≤ entries. */
        fun lookup1Values(entries: Int, dimensions: Int): Int {
            var r = floor(Math.exp(ln(entries.toDouble()) / dimensions)).toInt()
            while (pow(r + 1, dimensions) <= entries) r++
            while (r > 0 && pow(r, dimensions) > entries) r--
            return r
        }
        private fun pow(x: Int, n: Int): Long { var v = 1L; repeat(n) { v *= x; if (v > Int.MAX_VALUE) return v }; return v }
    }
}

// ---------------------------------------------------------------------------------------------- floor 1

private class Floor1(b: Bits) {
    val partitionClass: IntArray
    val classDimensions: IntArray; val classSubclasses: IntArray; val classMasterbook: IntArray
    val subclassBooks: Array<IntArray>
    val multiplier: Int
    val x: IntArray                                  // the X list (two ends first)
    val low: IntArray; val high: IntArray            // neighbours of each point among those before it
    val order: IntArray                              // points sorted by X

    init {
        val partitions = b.read(5)
        partitionClass = IntArray(partitions) { b.read(4) }
        val classes = (partitionClass.maxOrNull() ?: -1) + 1
        classDimensions = IntArray(classes); classSubclasses = IntArray(classes); classMasterbook = IntArray(classes)
        subclassBooks = Array(classes) { IntArray(0) }
        for (c in 0 until classes) {
            classDimensions[c] = b.read(3) + 1
            classSubclasses[c] = b.read(2)
            if (classSubclasses[c] > 0) classMasterbook[c] = b.read(8)
            subclassBooks[c] = IntArray(1 shl classSubclasses[c]) { b.read(8) - 1 }
        }
        multiplier = b.read(2) + 1
        val rangeBits = b.read(4)
        val xs = ArrayList<Int>(); xs.add(0); xs.add(1 shl rangeBits)
        for (p in 0 until partitions) repeat(classDimensions[partitionClass[p]]) { xs.add(b.read(rangeBits)) }
        require(xs.size <= 65) { "Vorbis: too many floor points" }
        x = xs.toIntArray()
        require(x.toSet().size == x.size) { "Vorbis: repeated floor point" }
        low = IntArray(x.size); high = IntArray(x.size)
        for (i in 2 until x.size) {
            var lo = 0; var hi = 1; var lx = -1; var hx = Int.MAX_VALUE
            for (j in 0 until i) {
                if (x[j] < x[i] && x[j] > lx) { lx = x[j]; lo = j }
                if (x[j] > x[i] && x[j] < hx) { hx = x[j]; hi = j }
            }
            low[i] = lo; high[i] = hi
        }
        order = x.indices.sortedBy { x[it] }.toIntArray()
    }

    private val finalY = IntArray(65)
    private val step2 = BooleanArray(65)

    /**
     * Reads a channel's floor into [y] (its own: channels sharing this floor are all read before any is
     * applied); false when it is unused (silent) or the packet ended.
     */
    fun read(b: Bits, books: Array<Codebook>, y: IntArray): Boolean {
        if (!b.bit()) return false
        val range = RANGES[multiplier - 1]
        val bits = ilog(range - 1)
        y[0] = b.read(bits); y[1] = b.read(bits)
        var offset = 2
        for (p in partitionClass.indices) {
            val c = partitionClass[p]
            val cdim = classDimensions[c]; val cbits = classSubclasses[c]; val csub = (1 shl cbits) - 1
            var cval = 0
            if (cbits > 0) { cval = books[classMasterbook[c]].decode(b); if (cval < 0) return false }
            for (j in 0 until cdim) {
                val book = subclassBooks[c][cval and csub]
                cval = cval ushr cbits
                if (book >= 0) { val v = books[book].decode(b); if (v < 0) return false; y[offset + j] = v } else y[offset + j] = 0
            }
            offset += cdim
        }
        return !b.eop
    }

    /** Multiplies [v] (n/2 spectral values) by the floor curve of the values [y] (spec 7.2.4). */
    fun apply(v: FloatArray, half: Int, y: IntArray) {
        val range = RANGES[multiplier - 1]
        finalY[0] = y[0]; finalY[1] = y[1]; step2[0] = true; step2[1] = true
        for (i in 2 until x.size) {
            val lo = low[i]; val hi = high[i]
            val predicted = renderPoint(x[lo], finalY[lo], x[hi], finalY[hi], x[i])
            val value = y[i]
            val highroom = range - predicted; val lowroom = predicted
            val room = if (highroom < lowroom) highroom * 2 else lowroom * 2
            if (value != 0) {
                step2[lo] = true; step2[hi] = true; step2[i] = true
                finalY[i] = if (value >= room) {
                    if (highroom > lowroom) value - lowroom + predicted else predicted - value + highroom - 1
                } else if (value and 1 == 1) predicted - (value + 1) / 2 else predicted + value / 2
            } else { step2[i] = false; finalY[i] = predicted }
        }
        // The curve: lines between the points that count, in the dB domain, then to amplitudes.
        var lx = 0; var ly = finalY[order[0]] * multiplier
        var hx = 0; var hy = 0
        for (k in 1 until order.size) {
            val i = order[k]
            if (!step2[i]) continue
            hy = finalY[i] * multiplier; hx = x[i]
            renderLine(lx, ly, hx, hy, v, half)
            lx = hx; ly = hy
        }
        if (hx < half) renderLine(hx, hy, half, hy, v, half)
    }

    private fun renderPoint(x0: Int, y0: Int, x1: Int, y1: Int, x: Int): Int {
        val dy = y1 - y0; val adx = x1 - x0
        val off = abs(dy) * (x - x0) / adx
        return if (dy < 0) y0 - off else y0 + off
    }

    private fun renderLine(x0: Int, y0: Int, x1: Int, y1: Int, v: FloatArray, n: Int) {
        val dy = y1 - y0; val adx = x1 - x0
        if (adx <= 0) return
        var ady = abs(dy)
        val base = dy / adx
        val sy = if (dy < 0) base - 1 else base + 1
        ady -= abs(base) * adx
        var y = y0; var err = 0
        if (x0 < n) v[x0] *= INVERSE_DB[y.coerceIn(0, 255)]
        for (x in x0 + 1 until min(x1, n)) {
            err += ady
            if (err >= adx) { err -= adx; y += sy } else y += base
            v[x] *= INVERSE_DB[y.coerceIn(0, 255)]
        }
    }

    companion object {
        val RANGES = intArrayOf(256, 128, 86, 64)
        /** The floor's dB scale (spec 10.1): 256 steps from −140 dB to 0. */
        val INVERSE_DB = floatArrayOf(
            1.0649863e-07f, 1.1341951e-07f, 1.2079015e-07f, 1.2863978e-07f, 1.3699951e-07f, 1.4590251e-07f, 1.5538408e-07f, 1.6548181e-07f,
            1.7623575e-07f, 1.8768855e-07f, 1.9988561e-07f, 2.1287530e-07f, 2.2670913e-07f, 2.4144197e-07f, 2.5713223e-07f, 2.7384213e-07f,
            2.9163793e-07f, 3.1059021e-07f, 3.3077411e-07f, 3.5226968e-07f, 3.7516214e-07f, 3.9954229e-07f, 4.2550680e-07f, 4.5315863e-07f,
            4.8260743e-07f, 5.1396998e-07f, 5.4737065e-07f, 5.8294187e-07f, 6.2082472e-07f, 6.6116941e-07f, 7.0413592e-07f, 7.4989464e-07f,
            7.9862701e-07f, 8.5052630e-07f, 9.0579828e-07f, 9.6466216e-07f, 1.0273513e-06f, 1.0941144e-06f, 1.1652161e-06f, 1.2409384e-06f,
            1.3215816e-06f, 1.4074654e-06f, 1.4989305e-06f, 1.5963394e-06f, 1.7000785e-06f, 1.8105592e-06f, 1.9282195e-06f, 2.0535261e-06f,
            2.1869758e-06f, 2.3290978e-06f, 2.4804557e-06f, 2.6416497e-06f, 2.8133190e-06f, 2.9961443e-06f, 3.1908506e-06f, 3.3982101e-06f,
            3.6190449e-06f, 3.8542308e-06f, 4.1047004e-06f, 4.3714470e-06f, 4.6555282e-06f, 4.9580707e-06f, 5.2802740e-06f, 5.6234160e-06f,
            5.9888572e-06f, 6.3780469e-06f, 6.7925283e-06f, 7.2339451e-06f, 7.7040476e-06f, 8.2047000e-06f, 8.7378876e-06f, 9.3057248e-06f,
            9.9104632e-06f, 1.0554501e-05f, 1.1240392e-05f, 1.1970856e-05f, 1.2748789e-05f, 1.3577278e-05f, 1.4459606e-05f, 1.5399272e-05f,
            1.6400004e-05f, 1.7465768e-05f, 1.8600792e-05f, 1.9809576e-05f, 2.1096914e-05f, 2.2467911e-05f, 2.3928002e-05f, 2.5482978e-05f,
            2.7139006e-05f, 2.8902651e-05f, 3.0780908e-05f, 3.2781225e-05f, 3.4911534e-05f, 3.7180282e-05f, 3.9596466e-05f, 4.2169667e-05f,
            4.4910090e-05f, 4.7828601e-05f, 5.0936773e-05f, 5.4246931e-05f, 5.7772202e-05f, 6.1526565e-05f, 6.5524908e-05f, 6.9783085e-05f,
            7.4317983e-05f, 7.9147585e-05f, 8.4291040e-05f, 8.9768747e-05f, 9.5602426e-05f, 0.00010181521f, 0.00010843174f, 0.00011547824f,
            0.00012298267f, 0.00013097477f, 0.00013948625f, 0.00014855085f, 0.00015820453f, 0.00016848555f, 0.00017943469f, 0.00019109536f,
            0.00020351382f, 0.00021673929f, 0.00023082423f, 0.00024582449f, 0.00026179955f, 0.00027881276f, 0.00029693158f, 0.00031622787f,
            0.00033677814f, 0.00035866388f, 0.00038197188f, 0.00040679456f, 0.00043323036f, 0.00046138411f, 0.00049136745f, 0.00052329927f,
            0.00055730621f, 0.00059352311f, 0.00063209358f, 0.00067317058f, 0.00071691700f, 0.00076350630f, 0.00081312324f, 0.00086596457f,
            0.00092223983f, 0.00098217216f, 0.0010459992f, 0.0011139742f, 0.0011863665f, 0.0012634633f, 0.0013455702f, 0.0014330129f,
            0.0015261382f, 0.0016253153f, 0.0017309374f, 0.0018434235f, 0.0019632195f, 0.0020908006f, 0.0022266726f, 0.0023713743f,
            0.0025254795f, 0.0026895994f, 0.0028643847f, 0.0030505286f, 0.0032487691f, 0.0034598925f, 0.0036847358f, 0.0039241906f,
            0.0041792066f, 0.0044507950f, 0.0047400328f, 0.0050480668f, 0.0053761186f, 0.0057254891f, 0.0060975636f, 0.0064938176f,
            0.0069158225f, 0.0073652516f, 0.0078438871f, 0.0083536271f, 0.0088964928f, 0.009474637f, 0.010090352f, 0.010746080f,
            0.011444421f, 0.012188144f, 0.012980198f, 0.013823725f, 0.014722068f, 0.015678791f, 0.016697687f, 0.017782797f,
            0.018938423f, 0.020169149f, 0.021479854f, 0.022875735f, 0.024362330f, 0.025945531f, 0.027631618f, 0.029427276f,
            0.031339626f, 0.033376252f, 0.035545228f, 0.037855157f, 0.040315199f, 0.042935108f, 0.045725273f, 0.048696758f,
            0.051861348f, 0.055231591f, 0.058820850f, 0.062643361f, 0.066714279f, 0.071049749f, 0.075666962f, 0.080584227f,
            0.085821044f, 0.091398179f, 0.097337747f, 0.10366330f, 0.11039993f, 0.11757434f, 0.12521498f, 0.13335215f,
            0.14201813f, 0.15124727f, 0.16107617f, 0.17154380f, 0.18269168f, 0.19456402f, 0.20720788f, 0.22067342f,
            0.23501402f, 0.25028656f, 0.26655159f, 0.28387361f, 0.30232132f, 0.32196786f, 0.34289114f, 0.36517414f,
            0.38890521f, 0.41417847f, 0.44109412f, 0.46975890f, 0.50028648f, 0.53279791f, 0.56742212f, 0.60429640f,
            0.64356699f, 0.68538959f, 0.72993007f, 0.77736504f, 0.82788260f, 0.88168307f, 0.9389798f, 1.0f)
    }
}

// ---------------------------------------------------------------------------------------------- residues

private class Residue(b: Bits, val type: Int) {
    val begin = b.read(24); val end = b.read(24)
    val partitionSize = b.read(24) + 1
    val classifications = b.read(6) + 1
    val classbook = b.read(8)
    val books: Array<IntArray>                       // per classification, per pass: book or −1

    init {
        val cascade = IntArray(classifications) {
            val lowBits = b.read(3)
            val highBits = if (b.bit()) b.read(5) else 0
            highBits * 8 + lowBits
        }
        books = Array(classifications) { c -> IntArray(8) { pass -> if (cascade[c] and (1 shl pass) != 0) b.read(8) else -1 } }
    }

    private var classes = Array(0) { IntArray(0) }

    /**
     * Decodes into [v] (one vector per channel, [half] values each) the channels whose [skip] is false.
     * Type 2 interleaves all the channels into one vector; [scratch] holds it.
     */
    fun decode(bits: Bits, books: Array<Codebook>, v: Array<FloatArray>, skip: BooleanArray, half: Int, scratch: FloatArray) {
        val ch = v.size
        if (type == 2) {
            if (skip.all { it }) return
            java.util.Arrays.fill(scratch, 0, half * ch, 0f)
            decodeVectors(bits, books, arrayOf(scratch), BooleanArray(1), half * ch, 1)
            for (c in 0 until ch) { val out = v[c]; for (i in 0 until half) out[i] = scratch[i * ch + c] }
            return
        }
        decodeVectors(bits, books, v, skip, half, type)
    }

    private fun decodeVectors(bits: Bits, books: Array<Codebook>, v: Array<FloatArray>, skip: BooleanArray, size: Int, format: Int) {
        val from = min(begin, size); val to = min(end, size)
        if (to <= from) return
        val partitions = (to - from) / partitionSize
        if (partitions == 0) return
        val classBook = books[classbook]
        val perWord = classBook.dimensions
        val ch = v.size
        if (classes.size < ch || classes[0].size < partitions + perWord) classes = Array(ch) { IntArray(partitions + perWord) }
        for (pass in 0 until 8) {
            var p = 0
            while (p < partitions) {
                if (pass == 0) {
                    for (c in 0 until ch) {
                        if (skip[c]) continue
                        var temp = classBook.decode(bits)
                        if (temp < 0) return
                        for (i in perWord - 1 downTo 0) { classes[c][i + p] = temp % classifications; temp /= classifications }
                    }
                }
                var i = 0
                while (i < perWord && p < partitions) {
                    for (c in 0 until ch) {
                        if (skip[c]) continue
                        val book = this.books[classes[c][p]][pass]
                        if (book < 0) continue
                        val cb = books[book]
                        val vectors = cb.vectors ?: return
                        val dim = cb.dimensions
                        val out = v[c]; val offset = from + p * partitionSize
                        if (format == 0) {
                            val step = partitionSize / dim
                            for (k in 0 until step) {
                                val e = cb.decode(bits); if (e < 0) return
                                for (j in 0 until dim) out[offset + k + j * step] += vectors[e * dim + j]
                            }
                        } else {
                            var k = 0
                            while (k < partitionSize) {
                                val e = cb.decode(bits); if (e < 0) return
                                for (j in 0 until dim) { out[offset + k] += vectors[e * dim + j]; k++ }
                            }
                        }
                    }
                    p++; i++
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- the decoder

private class Mapping(val submaps: Int, val magnitude: IntArray, val angle: IntArray, val mux: IntArray,
                      val floor: IntArray, val residue: IntArray)
private class Mode(val blockflag: Boolean, val mapping: Int)

private class VorbisDecoder(id: ByteArray, setup: ByteArray) {
    val channels: Int
    val rate: Int
    private val blocksize = IntArray(2)
    private val books: Array<Codebook>
    private val floors: Array<Floor1>
    private val residues: Array<Residue>
    private val mappings: Array<Mapping>
    private val modes: Array<Mode>

    init {
        val b = Bits(id)
        require(b.read(8) == 1 && readTag(b)) { "Not a Vorbis stream" }
        require(b.read(32) == 0) { "Vorbis: unknown version" }
        channels = b.read(8); rate = b.read(32)
        require(channels in 1..2) { "Vorbis: only mono and stereo are read ($channels channels)" }
        require(rate in 4_000..192_000) { "Vorbis: unusual rate $rate Hz" }
        b.read(32); b.read(32); b.read(32)
        blocksize[0] = 1 shl b.read(4); blocksize[1] = 1 shl b.read(4)
        require(blocksize[0] in 64..8192 && blocksize[1] in blocksize[0]..8192) { "Vorbis: bad block sizes" }
        require(b.bit()) { "Vorbis: bad identification header" }

        val s = Bits(setup)
        require(s.read(8) == 5 && readTag(s)) { "Vorbis: setup header missing" }
        books = Array(s.read(8) + 1) { Codebook(s) }
        repeat(s.read(6) + 1) { require(s.read(16) == 0) { "Vorbis: bad time domain transform" } }
        floors = Array(s.read(6) + 1) {
            val type = s.read(16)
            require(type == 1) { if (type == 0) "Vorbis: floor 0 is not supported (no encoder has produced it since 2001)" else "Vorbis: bad floor type $type" }
            Floor1(s)
        }
        residues = Array(s.read(6) + 1) { val type = s.read(16); require(type <= 2) { "Vorbis: bad residue type $type" }; Residue(s, type) }
        mappings = Array(s.read(6) + 1) {
            require(s.read(16) == 0) { "Vorbis: bad mapping type" }
            val submaps = if (s.bit()) s.read(4) + 1 else 1
            val steps = if (s.bit()) s.read(8) + 1 else 0
            val bits = ilog(channels - 1)
            val magnitude = IntArray(steps); val angle = IntArray(steps)
            for (i in 0 until steps) {
                magnitude[i] = s.read(bits); angle[i] = s.read(bits)
                require(magnitude[i] != angle[i] && magnitude[i] < channels && angle[i] < channels) { "Vorbis: bad channel coupling" }
            }
            require(s.read(2) == 0) { "Vorbis: bad mapping" }
            val mux = IntArray(channels)
            if (submaps > 1) for (c in 0 until channels) { mux[c] = s.read(4); require(mux[c] < submaps) { "Vorbis: bad mapping" } }
            val floor = IntArray(submaps); val residue = IntArray(submaps)
            for (i in 0 until submaps) { s.read(8); floor[i] = s.read(8); residue[i] = s.read(8) }
            Mapping(submaps, magnitude, angle, mux, floor, residue)
        }
        modes = Array(s.read(6) + 1) {
            val flag = s.bit()
            require(s.read(16) == 0 && s.read(16) == 0) { "Vorbis: bad mode" }
            Mode(flag, s.read(8))
        }
        require(s.bit() && !s.eop) { "Vorbis: bad setup header" }
        for (m in mappings) for (k in 0 until m.submaps) require(m.floor[k] < floors.size && m.residue[k] < residues.size) { "Vorbis: bad mapping" }
        for (m in modes) require(m.mapping < mappings.size) { "Vorbis: bad mode" }
        for (f in floors) {
            for (c in f.classMasterbook.indices) if (f.classSubclasses[c] > 0) require(f.classMasterbook[c] < books.size) { "Vorbis: bad floor book" }
            for (sb in f.subclassBooks) for (k in sb) require(k < books.size) { "Vorbis: bad floor book" }
        }
        for (r in residues) {
            require(r.classbook < books.size) { "Vorbis: bad residue book" }
            for (row in r.books) for (k in row) require(k < books.size && (k < 0 || books[k].vectors != null)) { "Vorbis: bad residue book" }
        }
    }

    private fun readTag(b: Bits): Boolean { for (ch in "vorbis") if (b.read(8) != ch.code) return false; return true }

    private val windows = HashMap<Int, FloatArray>()     // the rising slope of each block size
    private val imdcts = HashMap<Int, Imdct>()
    private val spectrum = Array(channels) { FloatArray(blocksize[1] / 2) }
    private val samples = Array(channels) { FloatArray(blocksize[1]) }
    private val previous = Array(channels) { FloatArray(blocksize[1] / 2) }  // right half of the last block, windowed
    private var previousSize = 0
    private val scratch = FloatArray(blocksize[1] / 2 * 2)
    private val floorUsed = BooleanArray(channels)
    private val floorY = Array(channels) { IntArray(65) }
    private val skip = BooleanArray(channels)
    /** The samples of the last decoded packet, per channel ([decode] says how many). */
    val output = Array(channels) { FloatArray(blocksize[1]) }

    private fun slope(n: Int) = windows.getOrPut(n) {
        // Vorbis's window: sin(π/2 · sin²(π/2 · (i + ½)/n)), rising over n samples.
        FloatArray(n) { i -> sin(PI / 2 * sin(PI / 2 * (i + .5) / n).pow(2)).toFloat() }
    }

    /** Decodes one audio packet; returns how many samples per channel it completed (0 for the first). */
    fun decode(packet: ByteArray): Int {
        val b = Bits(packet)
        if (packet.isEmpty() || b.bit()) return 0                  // not an audio packet
        val mode = modes[b.read(ilog(modes.size - 1))]
        if (b.eop) return 0
        val n = blocksize[if (mode.blockflag) 1 else 0]
        val half = n / 2
        var prevLong = true; var nextLong = true
        if (mode.blockflag) { prevLong = b.bit(); nextLong = b.bit() }
        val map = mappings[mode.mapping]
        // Floors.
        for (c in 0 until channels) {
            java.util.Arrays.fill(spectrum[c], 0, half, 0f)
            val f = floors[map.floor[map.mux[c]]]
            floorUsed[c] = f.read(b, books, floorY[c])
            if (b.eop) floorUsed[c] = false
        }
        for (c in 0 until channels) skip[c] = !floorUsed[c]
        // A coupled pair is decoded if either of the two has a floor.
        for (i in map.magnitude.indices) {
            val m = map.magnitude[i]; val a = map.angle[i]
            if (!skip[m] || !skip[a]) { skip[m] = false; skip[a] = false }
        }
        // Residues, submap by submap.
        for (k in 0 until map.submaps) {
            var count = 0
            for (c in 0 until channels) if (map.mux[c] == k) count++
            val vecs = arrayOfNulls<FloatArray>(count); val sk = BooleanArray(count)
            var j = 0
            for (c in 0 until channels) if (map.mux[c] == k) { vecs[j] = spectrum[c]; sk[j] = skip[c]; j++ }
            @Suppress("UNCHECKED_CAST")
            residues[map.residue[k]].decode(b, books, vecs as Array<FloatArray>, sk, half, scratch)
        }
        // Inverse coupling, last step first.
        for (i in map.magnitude.indices.reversed()) {
            val mv = spectrum[map.magnitude[i]]; val av = spectrum[map.angle[i]]
            for (j in 0 until half) {
                val m = mv[j]; val a = av[j]
                if (m > 0f) { if (a > 0f) { av[j] = m - a } else { av[j] = m; mv[j] = m + a } }
                else { if (a > 0f) { av[j] = m + a } else { av[j] = m; mv[j] = m - a } }
            }
        }
        // Floor curves, inverse MDCT, window.
        for (c in 0 until channels) {
            val spec = spectrum[c]
            if (!floorUsed[c]) java.util.Arrays.fill(spec, 0, half, 0f)
            else floors[map.floor[map.mux[c]]].apply(spec, half, floorY[c])
            val out = samples[c]
            imdcts.getOrPut(n) { Imdct(n) }.inverse(spec, out)
            window(out, n, mode.blockflag, prevLong, nextLong)
        }
        // Overlap with the previous block: from its centre to this block's centre.
        val produced: Int
        if (previousSize == 0) produced = 0
        else {
            val pn = previousSize
            produced = pn / 4 + n / 4
            val shift = n / 4 - pn / 4                       // index in this block of the output's first sample
            for (c in 0 until channels) {
                val o = output[c]; val prev = previous[c]; val cur = samples[c]
                for (t in 0 until produced) {
                    var s = if (t < pn / 2) prev[t] else 0f
                    val ci = t + shift
                    if (ci in 0 until n) s += cur[ci]
                    o[t] = s
                }
            }
        }
        for (c in 0 until channels) System.arraycopy(samples[c], half, previous[c], 0, half)
        previousSize = n
        return produced
    }

    private fun window(v: FloatArray, n: Int, long: Boolean, prevLong: Boolean, nextLong: Boolean) {
        val bs0 = blocksize[0]
        val leftShort = long && !prevLong; val rightShort = long && !nextLong
        val leftStart = if (leftShort) n / 4 - bs0 / 4 else 0
        val leftN = if (leftShort) bs0 / 2 else n / 2
        val rightStart = if (rightShort) n * 3 / 4 - bs0 / 4 else n / 2
        val rightN = if (rightShort) bs0 / 2 else n / 2
        val up = slope(leftN); val down = slope(rightN)
        for (i in 0 until leftStart) v[i] = 0f
        for (i in 0 until leftN) v[leftStart + i] *= up[i]
        for (i in 0 until rightN) v[rightStart + i] *= down[rightN - 1 - i]
        for (i in rightStart + rightN until n) v[i] = 0f
    }
}

// ---------------------------------------------------------------------------------------------- inverse MDCT

/**
 * Vorbis's inverse MDCT of size n (n/2 coefficients in, n samples out):
 * y[i] = Σₖ X[k] cos(2π/n · (i + ½ + n/4)(k + ½)). Computed as a DCT-IV of size n/2 through a complex
 * FFT of size n/4 (pre- and post-twiddles), then unfolded by the DCT-IV's symmetries.
 */
internal class Imdct(val n: Int) {
    private val m = n / 2                            // coefficients (the DCT-IV's size)
    private val q = m / 2                            // complex FFT size
    private val preCos = FloatArray(q); private val preSin = FloatArray(q)
    private val postCos = FloatArray(q); private val postSin = FloatArray(q)
    private val fftCos = FloatArray(q / 2 + 1); private val fftSin = FloatArray(q / 2 + 1)
    private val bitrev = IntArray(q)
    private val re = FloatArray(q); private val im = FloatArray(q)
    private val dct = FloatArray(m)

    init {
        for (k in 0 until q) {
            val a = -PI * k / m; preCos[k] = cos(a).toFloat(); preSin[k] = sin(a).toFloat()
            val b = -PI * (k + .25) / m; postCos[k] = cos(b).toFloat(); postSin[k] = sin(b).toFloat()
        }
        for (k in 0..q / 2) { val a = -2 * PI * k / q; fftCos[k] = cos(a).toFloat(); fftSin[k] = sin(a).toFloat() }
        val bits = Integer.numberOfTrailingZeros(q)
        for (i in 0 until q) bitrev[i] = if (bits == 0) 0 else Integer.reverse(i) ushr (32 - bits)
    }

    fun inverse(x: FloatArray, out: FloatArray) {
        // DCT-IV: z_k = (X[2k] + i X[m−1−2k]) · e^{−iπk/m}; Z = FFT(z); Y_j = Z_j · e^{−iπ(j+¼)/m};
        // y[2j] = Re Y_j, y[m−1−2j] = −Im Y_j.
        for (k in 0 until q) {
            val a = x[2 * k]; val b = x[m - 1 - 2 * k]
            val r = bitrev[k]
            re[r] = a * preCos[k] - b * preSin[k]; im[r] = a * preSin[k] + b * preCos[k]
        }
        fft()
        for (j in 0 until q) {
            val yr = re[j] * postCos[j] - im[j] * postSin[j]
            val yi = re[j] * postSin[j] + im[j] * postCos[j]
            dct[2 * j] = yr; dct[m - 1 - 2 * j] = -yi
        }
        // Unfold: y[i] = dct[i + m/2] for i < m/2; −dct[3m/2 − 1 − i] up to 3m/2; −dct[i − 3m/2] after.
        val h = m / 2
        for (i in 0 until h) out[i] = dct[i + h]
        for (i in h until 3 * h) out[i] = -dct[3 * h - 1 - i]
        for (i in 3 * h until n) out[i] = -dct[i - 3 * h]
    }

    /** In-place radix-2 FFT (forward, e^{−i}), input already in bit-reversed order. */
    private fun fft() {
        var size = 2
        while (size <= q) {
            val halfSize = size / 2; val step = q / size
            var start = 0
            while (start < q) {
                for (k in 0 until halfSize) {
                    val wr = fftCos[k * step]; val wi = fftSin[k * step]
                    val i = start + k; val j = i + halfSize
                    val tr = re[j] * wr - im[j] * wi; val ti = re[j] * wi + im[j] * wr
                    re[j] = re[i] - tr; im[j] = im[i] - ti
                    re[i] += tr; im[i] += ti
                }
                start += size
            }
            size *= 2
        }
    }
}
