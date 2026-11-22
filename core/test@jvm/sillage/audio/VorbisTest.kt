package sillage.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.*

/**
 * Le décodeur Ogg Vorbis du moteur contre libvorbis, le décodeur de référence de Xiph : mêmes échantillons.
 * Les fichiers d'essai (core/test@jvm/fixtures/vorbis) viennent de tools/vorbis_fixtures.sh.
 */
class VorbisTest {
    private fun root(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("core/test@jvm/fixtures/vorbis"))) dir = dir.parent ?: error("fixtures introuvables")
        return dir.resolve("core/test@jvm/fixtures/vorbis")
    }

    private fun reference(name: String): FloatArray {
        val b = ByteBuffer.wrap(Files.readAllBytes(root().resolve("$name.f32"))).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.remaining() / 4) { b.float }
    }

    private fun check(name: String, channels: Int, rate: Int) {
        val bytes = Files.readAllBytes(root().resolve("$name.ogg"))
        val t = System.nanoTime()
        val s = Sound.decode(bytes)
        val ms = (System.nanoTime() - t) / 1e6
        val ref = reference(name)
        assertEquals(channels, s.channels, "$name : canaux"); assertEquals(rate, s.rate, "$name : fréquence")
        assertEquals(ref.size, s.data.size, "$name : nombre d'échantillons")
        var worst = 0f; var peak = 0f
        for (i in ref.indices) { worst = max(worst, abs(ref[i] - s.data[i])); peak = max(peak, abs(ref[i])) }
        println("VORBIS $name : ${s.frames} images, écart max ${"%.2e".format(worst)} (crête ${"%.2f".format(peak)}), ${"%.1f".format(ms)} ms")
        assertTrue(worst < 1e-6f, "$name : écart max $worst avec libvorbis")
    }

    @Test fun stereoQualiteMoyenne() = check("stereo-q4", 2, 44100)
    @Test fun stereoQualiteBasse() = check("stereo-qm1", 2, 44100)
    @Test fun stereoQualiteHaute() = check("stereo-q10", 2, 44100)
    @Test fun mono22kHz() = check("mono-22k", 1, 22050)
    @Test fun sonPlusCourtQuUnBloc() = check("court", 1, 48000)

    @Test fun fichiersAbimesRefuses() {
        val good = Files.readAllBytes(root().resolve("stereo-q4.ogg"))
        val flipped = good.copyOf(); flipped[good.size / 2] = (flipped[good.size / 2].toInt() xor 0x10).toByte()
        assertFailsWith<IllegalArgumentException> { Sound.decode(flipped) }            // somme de contrôle d'une page
        for (cut in intArrayOf(10, 40, 200, 3000)) assertFailsWith<IllegalArgumentException>("coupé à $cut") { Sound.decode(good.copyOf(cut)) }
        assertFailsWith<IllegalArgumentException> { Vorbis.decode("OggS pas vraiment".toByteArray()) }
    }

    /** Recalcule les sommes de contrôle des pages, pour que l'abîmage passe la couche Ogg et atteigne le décodeur. */
    private fun recrc(b: ByteArray) {
        var p = 0
        while (p + 27 <= b.size && b[p] == 'O'.code.toByte()) {
            val segs = b[p + 26].toInt() and 255; var size = 0
            for (s in 0 until segs) size += b[p + 27 + s].toInt() and 255
            val total = 27 + segs + size
            if (p + total > b.size) return
            for (i in 22..25) b[p + i] = 0
            var crc = 0
            for (i in 0 until total) {
                crc = crc xor ((b[p + i].toInt() and 255) shl 24)
                repeat(8) { crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
            }
            for (i in 0..3) b[p + 22 + i] = (crc ushr (8 * i)).toByte()
            p += total
        }
    }

    @Test fun desPaquetsAbimesNeFontJamaisPlanter() {
        // 600 fichiers abîmés au hasard (octets changés dans les en-têtes ou le son), sommes refaites :
        // le décodeur refuse (IllegalArgumentException) ou décode quelque chose, sans autre exception.
        val rnd = java.util.Random(42)
        var refused = 0; var decoded = 0
        for (name in listOf("stereo-q4", "mono-22k")) {
            val good = Files.readAllBytes(root().resolve("$name.ogg"))
            repeat(300) {
                val b = good.copyOf()
                repeat(1 + rnd.nextInt(6)) { val i = 28 + rnd.nextInt(b.size - 28); b[i] = (b[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
                recrc(b)
                try { val s = Sound.decode(b); assertTrue(s.data.all { it.isFinite() }, "valeurs non finies"); decoded++ }
                catch (e: IllegalArgumentException) { refused++ }
            }
        }
        println("VORBIS abîmés : $refused refusés, $decoded décodés")
    }

    @Test fun unFichierCoupeEnFinDePageSeLitJusquOuIlVa() {
        // Coupé à une limite de page : ce qui reste se décode, plus court.
        val good = Files.readAllBytes(root().resolve("stereo-q4.ogg"))
        var p = 0; val starts = ArrayList<Int>()
        while (p + 27 <= good.size) { starts.add(p); val segs = good[p + 26].toInt() and 255; var size = 0; for (s in 0 until segs) size += good[p + 27 + s].toInt() and 255; p += 27 + segs + size }
        assertTrue(starts.size >= 3)
        val whole = Sound.decode(good)
        val cut = Sound.decode(good.copyOf(starts.last()))
        assertTrue(cut.frames < whole.frames)
        for (i in 0 until cut.data.size) assertEquals(whole.data[i], cut.data[i], "échantillon $i")
    }
}
