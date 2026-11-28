package sillage.audio

import sillage.math.Vec3
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sin
import kotlin.test.*

/**
 * Les sons longs lus au fil de l'eau : décodés par morceaux pendant qu'ils jouent, ils doivent donner
 * exactement les mêmes échantillons que décodés en entier, et le mélangeur la même sortie, au bit près.
 */
class StreamTest {
    private fun root(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("core/test@jvm/fixtures/vorbis"))) dir = dir.parent ?: error("fixtures introuvables")
        return dir
    }
    private fun ogg(name: String): ByteArray = Files.readAllBytes(root().resolve("core/test@jvm/fixtures/vorbis/$name.ogg"))
    /** La guitare de la démo : 25 s en mono, le seul long fichier du dépôt. */
    private fun guitare(): ByteArray = Files.readAllBytes(root().resolve("demo/resources/sons/guitare.ogg"))
    private val fichiers get() = listOf("court", "mono-22k", "stereo-q4", "stereo-qm1", "stereo-q10").associateWith { ogg(it) } + ("guitare" to guitare())

    private fun memes(a: FloatArray, b: FloatArray, what: String) {
        assertEquals(a.size, b.size, "$what : nombre d'échantillons")
        for (i in a.indices) if (a[i].toRawBits() != b[i].toRawBits()) fail("$what : échantillon $i diffère (${a[i]} contre ${b[i]})")
    }

    /** Lit tout [s] par le curseur, fenêtre après fenêtre, comme le mélangeur le ferait à hauteur 1. */
    private fun parFenetres(s: Sound): FloatArray {
        val c = s.cursor()!!
        val out = FloatArray(s.frames * s.channels)
        var j = 0
        while (j < s.frames) {
            c.cover(j)
            val upTo = minOf(c.limit, s.frames)
            for (f in j until upTo) for (ch in 0 until s.channels) out[f * s.channels + ch] = c.buffer[(f - c.base) * s.channels + ch]
            j = upTo
        }
        return out
    }

    @Test fun unSonLuEnFluxDonneLesMemesEchantillonsQueDecodeEnEntier() {
        for ((name, bytes) in fichiers) {
            val whole = Vorbis.decode(bytes)
            val streamed = Sound.decode(bytes, 0f)
            assertTrue(streamed.streamed, "$name : en flux")
            assertEquals(whole.frames, streamed.frames, "$name : longueur connue sans décoder")
            assertEquals(whole.channels, streamed.channels); assertEquals(whole.rate, streamed.rate)
            memes(whole.data, parFenetres(streamed), "$name par fenêtres")
            memes(whole.data, streamed.data, "$name, data lu d'un coup")
        }
    }

    @Test fun lesSautsDansLeFluxRetombentSurLesMemesEchantillons() {
        val rnd = java.util.Random(7)
        for ((name, bytes) in fichiers) {
            val whole = Vorbis.decode(bytes)
            val s = Sound.decode(bytes, 0f)
            val c = s.cursor()!!
            val ch = s.channels
            repeat(300) {
                // En avant, en arrière, un peu plus loin, au début : chaque fois la fenêtre doit tenir j et j + 1.
                val j = when (it % 4) { 0 -> rnd.nextInt(s.frames); 1 -> 0; 2 -> minOf(s.frames - 1, c.base + rnd.nextInt(20_000)); else -> s.frames - 1 }
                c.cover(j)
                assertTrue(c.base <= j && j + 1 < c.limit, "$name : fenêtre ${c.base}..${c.limit} pour $j")
                for (f in j until minOf(c.limit, s.frames)) for (k in 0 until ch)
                    assertEquals(whole.data[f * ch + k].toRawBits(), c.buffer[(f - c.base) * ch + k].toRawBits(), "$name : image $f après un saut vers $j")
                if (c.limit == s.frames + 1) for (k in 0 until ch)
                    assertEquals(whole.data[k].toRawBits(), c.buffer[(s.frames - c.base) * ch + k].toRawBits(), "$name : la première image suit la dernière")
            }
        }
    }

    /** Le même jeu de voix sur deux mélangeurs, l'un avec le son en mémoire, l'autre avec le même lu en flux. */
    private fun melange(son: Sound, blocs: Int, taille: Int): FloatArray {
        val m = Mixer(); m.listen(Vec3.ZERO, Vec3(0f, 0f, -1f)); m.reverb = Reverb.OUTDOOR
        val out = FloatArray(2 * blocs * taille); val buf = FloatArray(2 * taille)
        val a = m.play(son, volume = .5f, loop = true)                               // une ambiance qui boucle
        for (k in 0 until blocs) {
            when (k) {
                3 -> m.play(son, volume = .4f, pitch = 1.37f, pan = -.5f, loop = true, offset = son.duration * .6f)
                10 -> m.playAt(son, Vec3(3f, 0f, -4f), volume = .7f, pitch = .61f, offset = son.duration * .2f)
                25 -> m.play(son, volume = .3f, pitch = 2.9f, offset = son.duration * .95f, loop = true)   // près de la fin : la boucle repart
                40 -> a.pitch(.83f)
                90 -> a.stop(.05f)
            }
            m.render(buf, taille); System.arraycopy(buf, 0, out, 2 * k * taille, 2 * taille)
        }
        return out
    }

    @Test fun leMelangeurDonneLaMemeSortieEnFluxQuEnMemoire() {
        for ((name, bytes) in fichiers) {
            val whole = Vorbis.decode(bytes)
            val s = Sound.decode(bytes, 0f)
            memes(melange(whole, 240, 256), melange(s, 240, 256), "$name mélangé")
        }
    }

    private fun wavLong(channels: Int, bits: Int): ByteArray {
        val rate = 22_050; val n = rate * 3
        val d = FloatArray(n * channels) { i -> (sin(i * .011f) * .6f + sin(i * .173f) * .2f) * (if (i % 2 == 1) -.8f else 1f) }
        val w = Sound(d, channels, rate).toWav()
        if (bits == 16) return w
        // Converti en 8 ou 24 bits : même en-tête, autre largeur.
        val width = bits / 8; val b = ByteArray(44 + n * channels * width)
        w.copyInto(b, 0, 0, 44)
        sillage.io.Le.put32(b, 4, 36 + n * channels * width); sillage.io.Le.put32(b, 28, rate * channels * width)
        sillage.io.Le.put16(b, 32, channels * width); sillage.io.Le.put16(b, 34, bits); sillage.io.Le.put32(b, 40, n * channels * width)
        for (i in 0 until n * channels) {
            val v = sillage.io.Le.i16(w, 44 + 2 * i)
            if (bits == 8) b[44 + i] = ((v shr 8) + 128).toByte()
            else { val x = v shl 8; b[44 + 3 * i] = x.toByte(); b[44 + 3 * i + 1] = (x shr 8).toByte(); b[44 + 3 * i + 2] = (x shr 16).toByte() }
        }
        return b
    }

    @Test fun unLongWavSeLitEnFluxCommeEnEntier() {
        for (channels in 1..2) for (bits in intArrayOf(8, 16, 24)) {
            val bytes = wavLong(channels, bits)
            val whole = Sound.wav(bytes)
            assertFalse(whole.streamed)
            val s = Sound.decode(bytes, 1f)
            assertTrue(s.streamed, "$channels canaux, $bits bits : en flux")
            assertEquals(whole.frames, s.frames)
            memes(whole.data, parFenetres(s), "wav $channels×$bits par fenêtres")
            memes(melange(whole, 120, 300), melange(s, 120, 300), "wav $channels×$bits mélangé")
            memes(whole.data, s.data, "wav $channels×$bits data")
        }
    }

    @Test fun lesSonsCourtsRestentEnMemoireEtLesLongsCompresses() {
        assertFalse(Sound.decode(ogg("stereo-q4")).streamed, "0,7 s : en mémoire")
        val g = Sound.decode(guitare())
        assertTrue(g.streamed, "25 s : décodée en jouant")
        val src = g.source as VorbisSource
        val gardes = src.packets.sumOf { it.size.toLong() }
        val entier = 4L * g.frames * g.channels
        println("STREAM guitare : ${g.frames} images, ${gardes / 1024} Kio gardés au lieu de ${entier / 1024} Kio")
        assertTrue(gardes * 10 < entier, "la guitare doit tenir dix fois moins de place compressée")
    }

    @Test fun lireUnSonEnFluxNAlloueRien() {
        val m = Mixer(); m.listen(Vec3.ZERO, Vec3(0f, 0f, -1f))
        val g = Sound.decode(guitare())
        val s = Sound.decode(ogg("stereo-q4"), 0f)
        m.play(g, loop = true); m.play(g, pitch = 1.5f, loop = true); m.playAt(s, Vec3(1f, 0f, -2f), loop = true)
        val buf = FloatArray(512)
        repeat(400) { m.render(buf, 256) }               // échauffement (compilation JIT)
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val t = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(t)
        repeat(3000) { m.render(buf, 256) }               // 16 s de son : la guitare passe plusieurs paquets et boucle
        val bytes = bean.getThreadAllocatedBytes(t) - before
        assertTrue(bytes < 16_384, "$bytes octets alloués pour 3000 blocs")
    }

    @Test fun unFichierAbimeEstRefuseAuChargementMemeLong() {
        // Le fichier est lu jusqu'au bout au chargement : un défaut est refusé tout de suite, comme avant,
        // et ce qui se décode donne en flux les mêmes échantillons.
        val rnd = java.util.Random(43)
        val good = ogg("stereo-q4")
        var refused = 0; var decoded = 0
        repeat(150) {
            val b = good.copyOf()
            repeat(1 + rnd.nextInt(6)) { val i = 28 + rnd.nextInt(b.size - 28); b[i] = (b[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
            recrc(b)
            val whole = try { Vorbis.decode(b) } catch (e: IllegalArgumentException) { null }
            val s = try { Sound.decode(b, 0f) } catch (e: IllegalArgumentException) { null }
            assertEquals(whole == null, s == null, "refusé en entier si et seulement si refusé en flux")
            if (whole != null && s != null) { memes(whole.data, if (s.streamed) parFenetres(s) else s.data, "abîmé $it"); decoded++ } else refused++
        }
        println("STREAM abîmés : $refused refusés, $decoded décodés")
    }

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
}
