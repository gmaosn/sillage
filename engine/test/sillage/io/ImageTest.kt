package sillage.io

import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.*
import kotlin.test.*

/** Les décodeurs PNG et JPEG du moteur, comparés à ceux du JDK (ImageIO sert de référence ici, jamais dans le moteur). */
class ImageTest {
    /** Une image qui a de tout : dégradés, bords francs, bruit, transparence variable. */
    private fun picture(w: Int, h: Int, type: Int, alpha: Boolean = false): BufferedImage {
        val img = BufferedImage(w, h, type)
        var seed = 12345
        for (y in 0 until h) for (x in 0 until w) {
            seed = seed * 1103515245 + 12345
            val n = (seed ushr 24) and 31
            val r = (x * 255 / max(1, w - 1) + n) and 255
            val g = if ((x / 7 + y / 5) % 2 == 0) 40 else 210
            val b = (y * 255 / max(1, h - 1)) xor (n * 3)
            val a = if (alpha) (x * 17 + y * 29) and 255 else 255
            img.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or (b and 255))
        }
        return img
    }

    private fun png(img: BufferedImage, interlaced: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        val param = writer.defaultWriteParam
        if (interlaced) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
        writer.output = ImageIO.createImageOutputStream(out)
        writer.write(null, IIOImage(img, null, null), param)
        writer.dispose()
        return out.toByteArray()
    }

    private fun assertSame(expected: BufferedImage, actual: Image, what: String) {
        assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
        for (y in 0 until actual.height) for (x in 0 until actual.width)
            assertEquals(Integer.toHexString(expected.getRGB(x, y)), Integer.toHexString(actual.argb(x, y)), "$what, pixel ($x, $y)")
    }

    // ------------------------------------------------------------------ inflate

    @Test fun inflateLitTousLesBlocsDeDeflate() {
        val data = ByteArray(200_000) { i -> if (i % 997 < 400) (i % 7).toByte() else ((i * 2654435761L) ushr 13).toByte() }
        for ((level, strategy) in listOf(0 to 0, 1 to 0, 6 to 0, 9 to 0, 6 to java.util.zip.Deflater.HUFFMAN_ONLY, 6 to java.util.zip.Deflater.FILTERED)) {
            val d = java.util.zip.Deflater(level); d.setStrategy(strategy); d.setInput(data); d.finish()
            val buf = ByteArray(data.size * 2 + 1024); var n = 0
            while (!d.finished()) n += d.deflate(buf, n, buf.size - n)
            d.end()
            val back = Inflate.zlib(buf, n, data.size)
            assertContentEquals(data, back, "niveau $level, stratégie $strategy")
        }
        // Bright, repetitive data: the checksum's sums are largest (they once overflowed a signed Int).
        val bright = ByteArray(131_200) { i -> if (i % 1025 == 0) 0 else if (i % 4 == 3) -1 else (226 - (i / 4100) % 9).toByte() }
        val db = java.util.zip.Deflater(9); db.setInput(bright); db.finish()
        val bufB = ByteArray(bright.size + 1024); var nb = 0
        while (!db.finished()) nb += db.deflate(bufB, nb, bufB.size - nb)
        assertContentEquals(bright, Inflate.zlib(bufB, nb, bright.size), "données claires et répétitives")
        val tiny = byteArrayOf(1, 2, 3)
        val d = java.util.zip.Deflater(9); d.setInput(tiny); d.finish(); val buf = ByteArray(64); val n = d.deflate(buf)
        assertContentEquals(tiny, Inflate.zlib(buf, n, 3), "bloc Huffman fixe")
        buf[n - 1] = (buf[n - 1] + 1).toByte()
        assertFailsWith<IllegalArgumentException> { Inflate.zlib(buf, n, 3) }
    }

    // ------------------------------------------------------------------ PNG

    @Test fun pngEnCouleursAvecEtSansTransparence() {
        for ((type, alpha) in listOf(BufferedImage.TYPE_INT_RGB to false, BufferedImage.TYPE_INT_ARGB to true, BufferedImage.TYPE_3BYTE_BGR to false)) {
            val img = picture(37, 23, type, alpha)
            assertSame(img, Png.decode(png(img)), "type $type")
            assertSame(img, Png.decode(png(img, interlaced = true)), "type $type entrelacé")
        }
    }

    @Test fun pngEnGrisEtEnPalette() {
        // Grey: the raw samples (ImageIO's getRGB turns linear grey into sRGB, the file holds the samples).
        val gray = picture(31, 17, BufferedImage.TYPE_BYTE_GRAY)
        val decoded = Png.decode(png(gray))
        for (y in 0 until 17) for (x in 0 until 31) assertEquals(gray.raster.getSample(x, y, 0), decoded.argb(x, y) and 255, "gris 8 bits ($x, $y)")
        for (bits in intArrayOf(1, 2, 4, 8)) {
            val n = 1 shl bits
            val r = ByteArray(n) { (it * 255 / (n - 1)).toByte() }; val g = ByteArray(n) { (255 - it * 255 / (n - 1)).toByte() }
            val b = ByteArray(n) { (it * 37).toByte() }; val a = ByteArray(n) { if (it == 1) 0 else if (it == 2) 128.toByte() else -1 }
            val cm = IndexColorModel(bits, n, r, g, b, a)
            val img = BufferedImage(29, 13, if (bits == 8) BufferedImage.TYPE_BYTE_INDEXED else BufferedImage.TYPE_BYTE_BINARY, cm)
            val raster = img.raster
            for (y in 0 until 13) for (x in 0 until 29) raster.setSample(x, y, 0, (x * 3 + y * 5) % n)
            val bytes = png(img)
            assertSame(img, Png.decode(bytes), "palette $bits bits")
            assertSame(img, Png.decode(png(img, interlaced = true)), "palette $bits bits entrelacée")
        }
    }

    @Test fun pngSeizeBits() {
        val img = BufferedImage(19, 11, BufferedImage.TYPE_USHORT_GRAY)
        for (y in 0 until 11) for (x in 0 until 19) img.raster.setSample(x, y, 0, (x * 3449 + y * 911) and 0xFFFF)
        val out = Png.decode(png(img))
        for (y in 0 until 11) for (x in 0 until 19) {
            val expected = img.raster.getSample(x, y, 0) ushr 8
            assertEquals(expected, out.argb(x, y) and 255, "gris 16 bits ($x, $y)")
        }
    }

    @Test fun pngAbimeRefuse() {
        val good = png(picture(16, 16, BufferedImage.TYPE_INT_RGB))
        val flipped = good.copyOf(); flipped[good.size / 2] = (flipped[good.size / 2].toInt() xor 0x40).toByte()
        assertFailsWith<IllegalArgumentException> { Png.decode(flipped) }
        for (cut in intArrayOf(8, 20, 40, good.size / 2, good.size - 5)) assertFailsWith<IllegalArgumentException> { Png.decode(good.copyOf(cut)) }
        assertFailsWith<IllegalArgumentException> { Image.decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)) }
    }

    // ------------------------------------------------------------------ JPEG

    private fun jpeg(img: BufferedImage, progressive: Boolean = false, restart: Int = 0, quality: Float = .92f): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT; param.compressionQuality = quality
        if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
        val meta = writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(img), param)
        if (restart > 0) {
            val root = meta.getAsTree("javax_imageio_jpeg_image_1.0") as IIOMetadataNode
            val markers = root.getElementsByTagName("markerSequence").item(0) as IIOMetadataNode
            markers.insertBefore(IIOMetadataNode("dri").also { it.setAttribute("interval", restart.toString()) }, markers.firstChild)
            meta.setFromTree("javax_imageio_jpeg_image_1.0", root)
        }
        writer.output = ImageIO.createImageOutputStream(out)
        writer.write(null, IIOImage(img, null, meta), param)
        writer.dispose()
        return out.toByteArray()
    }

    /** Écart moyen et part des écarts de plus de 6 entre notre décodage et celui du JDK, sur les trois canaux. */
    private fun compare(bytes: ByteArray, what: String, mean: Double = 1.0, far: Double = .01) {
        val ref = ImageIO.read(ByteArrayInputStream(bytes))
        val ours = Jpeg.decode(bytes)
        assertEquals(ref.width, ours.width); assertEquals(ref.height, ours.height)
        val grey = ref.raster.numBands == 1          // raw samples: getRGB would turn linear grey into sRGB
        var sum = 0.0; var big = 0; var worst = 0
        for (y in 0 until ref.height) for (x in 0 until ref.width) {
            val a = if (grey) ref.raster.getSample(x, y, 0) * 0x010101 else ref.getRGB(x, y); val b = ours.argb(x, y)
            for (s in intArrayOf(0, 8, 16)) { val d = abs(((a shr s) and 255) - ((b shr s) and 255)); sum += d; if (d > 6) big++; worst = max(worst, d) }
        }
        val n = 3.0 * ref.width * ref.height
        println("JPEG $what : écart moyen ${"%.3f".format(sum / n)}, ${"%.3f".format(100 * big / n)} % au-delà de 6, pire $worst")
        assertTrue(sum / n <= mean, "$what : écart moyen ${sum / n}")
        assertTrue(big / n <= far, "$what : ${100 * big / n} % des valeurs à plus de 6")
    }

    @Test fun jpegDeBaseProgressifGrisRedemarrages() {
        val img = picture(61, 43, BufferedImage.TYPE_INT_RGB)
        fun has(b: ByteArray, marker: Int) = (0 until b.size - 1).any { (b[it].toInt() and 255) == 0xFF && (b[it + 1].toInt() and 255) == marker }
        assertTrue(has(jpeg(img, progressive = true), 0xC2), "le fichier d'essai progressif a bien un SOF2")
        assertTrue(has(jpeg(img, restart = 3), 0xDD) && has(jpeg(img, restart = 3), 0xD0), "le fichier d'essai a bien des redémarrages")
        assertTrue(!has(jpeg(img), 0xC2))
        compare(jpeg(img), "de base 4:2:0")
        compare(jpeg(img, progressive = true), "progressif")
        compare(jpeg(img, restart = 3), "intervalles de redémarrage")
        compare(jpeg(img, progressive = true, restart = 5), "progressif avec redémarrages")
        compare(jpeg(picture(45, 29, BufferedImage.TYPE_BYTE_GRAY)), "gris")
        compare(jpeg(picture(256, 192, BufferedImage.TYPE_INT_RGB), quality = .75f), "256 × 192 qualité 0,75")
    }

    @Test fun jpegAbimeRefuse() {
        val good = jpeg(picture(32, 32, BufferedImage.TYPE_INT_RGB))
        for (cut in intArrayOf(4, 30, 200, good.size / 2)) assertFailsWith<IllegalArgumentException>("coupé à $cut") { Jpeg.decode(good.copyOf(cut)) }
    }
}
