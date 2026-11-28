package sillage.io

import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.random.Random
import kotlin.test.*

/** The engine's PNG writer, read back by its own decoder, by the JDK's zlib and by ImageIO (references for the test only). */
class PngWriterTest {
    private fun image(w: Int, h: Int, kind: Int): ByteArray {
        val r = Random(w * 31 + h + kind)
        return ByteArray(w * h * 3) { i ->
            val p = i / 3; val x = p % w; val y = p / w
            when (kind) {
                0 -> (x * 255 / maxOf(1, w - 1)).toByte()                 // gradient
                1 -> r.nextInt(256).toByte()                               // noise: nothing to compress
                else -> (if ((x / 8 + y / 8) % 2 == 0) 40 + i % 3 * 60 else 200).toByte()   // checks: long runs
            }
        }
    }

    @Test fun relusÀLIdentique() {
        for ((w, h) in listOf(1 to 1, 3 to 2, 17 to 13, 333 to 211, 640 to 400)) for (kind in 0..2) {
            val rgb = image(w, h, kind)
            val png = PngWriter.encode(w, h, rgb)
            val own = Png.decode(png)
            assertEquals(w, own.width); assertEquals(h, own.height)
            for (p in 0 until w * h) for (c in 0..2) assertEquals(rgb[3 * p + c], own.rgba[4 * p + c], "own decoder, $w×$h kind $kind pixel $p")
            val awt = javax.imageio.ImageIO.read(ByteArrayInputStream(png))
            for (p in 0 until w * h) {
                val v = awt.getRGB(p % w, p / w)
                assertEquals(rgb[3 * p].toInt() and 255, (v ushr 16) and 255); assertEquals(rgb[3 * p + 2].toInt() and 255, v and 255)
            }
        }
    }

    /**
     * The very bytes written: captures are compared to their references byte for byte, so a faster encoder must
     * write the same files. Fingerprints (size, CRC-32 of the file) taken with the encoder of 22 November 2026.
     */
    @Test fun mêmesOctetsQuAvant() {
        fun scene(w: Int, h: Int): ByteArray {
            val r = Random(w * 31 + h + 3)
            return ByteArray(w * h * 3) { i ->
                val p = i / 3; val x = p % w; val y = p / w; val c = i % 3
                val disc = ((x - w / 3) * (x - w / 3) + (y - h / 2) * (y - h / 2)) < (h / 4) * (h / 4)
                val v = if (disc) 220 - c * 40 else if (y > h * 2 / 3) 60 + ((x * 7 + y * 13) % 23) + c * 15 else 120 + y * 100 / h + c * 20
                (v + r.nextInt(3) - 1).coerceIn(0, 255).toByte()
            }
        }
        fun print(png: ByteArray) = "${png.size} ${CRC32().also { it.update(png) }.value.toString(16)}"
        assertEquals("88 c512bcde", print(PngWriter.encode(17, 13, image(17, 13, 0))))
        assertEquals("780 8e665623", print(PngWriter.encode(17, 13, image(17, 13, 1))))
        assertEquals("1797 cbab923e", print(PngWriter.encode(333, 211, image(333, 211, 0))))
        assertEquals("222601 6532b6a0", print(PngWriter.encode(333, 211, image(333, 211, 1))))
        assertEquals("1859 5c63f525", print(PngWriter.encode(333, 211, image(333, 211, 2))))
        assertEquals("108474 188d261e", print(PngWriter.encode(333, 211, scene(333, 211))))
        assertEquals("385558 41a1d9de", print(PngWriter.encode(640, 400, scene(640, 400))))
    }

    @Test fun zlibEtCrcConformes() {
        val data = image(300, 200, 2) + image(50, 50, 1)
        val z = PngWriter.zlib(data)
        val inf = Inflater(); inf.setInput(z)
        val back = ByteArray(data.size); val n = inf.inflate(back)
        assertTrue(inf.finished()); assertEquals(data.size, n); assertContentEquals(data, back)
        val ref = CRC32(); ref.update(data)
        assertEquals(ref.value.toInt(), PngWriter.crc32(-1, data, 0, data.size).inv())
        // Runs compress: the checks shrink well below their size, noise does not grow much.
        assertTrue(PngWriter.zlib(image(300, 200, 2)).size < 300 * 200 * 3 / 10)
        assertTrue(PngWriter.zlib(image(100, 100, 1)).size < 100 * 100 * 3 * 1.15)
    }
}
