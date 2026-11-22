package sillage.audio

import sillage.math.Vec3
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

class AudioTest {
    private val rate = 48_000
    private fun sine(hz: Float, seconds: Float, amp: Float = 1f) = Sound(FloatArray((seconds * rate).toInt()) { amp * sin(2 * PI.toFloat() * hz * it / rate) })
    private fun render(m: Mixer, seconds: Float, block: Int = 256): FloatArray {
        val n = (seconds * rate).toInt() / block * block
        val out = FloatArray(2 * n); val buf = FloatArray(2 * block)
        for (k in 0 until n / block) { m.render(buf, block); System.arraycopy(buf, 0, out, 2 * k * block, 2 * block) }
        return out
    }
    private fun rms(x: FloatArray, ch: Int, from: Int = 0, to: Int = x.size / 2) =
        sqrt((from until to).sumOf { (x[2 * it + ch] * x[2 * it + ch]).toDouble() } / (to - from)).toFloat()

    @Test fun unSonAuCentreSortPareilDesDeuxCotesEtAPleinVolume() {
        val m = Mixer(); m.play(sine(440f, 1f, .5f))
        val o = render(m, .5f)
        assertEquals(rms(o, 0), rms(o, 1), 1e-4f)
        assertEquals(.5f / sqrt(2f), rms(o, 0), .01f)
    }

    @Test fun unSonPlaceADroiteSortADroite() {
        val m = Mixer(); m.listen(Vec3.ZERO, Vec3(0f, 0f, -1f))
        m.playAt(sine(200f, 1f, .5f), Vec3(3f, 0f, 0f))
        val o = render(m, .5f)
        assertTrue(rms(o, 1) > 5 * rms(o, 0), "droite ${rms(o, 1)}, gauche ${rms(o, 0)}")
        val m2 = Mixer(); m2.listen(Vec3.ZERO, Vec3(0f, 0f, -1f)); m2.play(sine(200f, 1f, .5f), pan = -1f)
        val o2 = render(m2, .5f)
        assertTrue(rms(o2, 0) > 50 * rms(o2, 1))
    }

    @Test fun deuxFoisPlusLoinDeuxFoisMoinsFort() {
        fun level(d: Float): Float {
            val m = Mixer(); m.listen(Vec3.ZERO, Vec3(0f, 0f, -1f))
            m.playAt(sine(200f, 1f, .5f), Vec3(0f, 0f, -d), referenceDistance = 2f, maxDistance = 200f)
            val o = render(m, .5f); return rms(o, 0) + rms(o, 1)
        }
        assertEquals(level(1f), level(2f), 1e-3f, "plein volume jusqu'à la distance de référence")
        assertEquals(.5f, level(8f) / level(4f), .03f)
        assertEquals(0f, level(250f), 1e-6f, "au-delà de la distance maximale : rien")
    }

    @Test fun lHauteurChangeLaDureeEtUnSonFiniLibereSaVoix() {
        val m = Mixer()
        val v = m.play(sine(300f, .5f), pitch = 2f)
        render(m, .2f); assertTrue(v.isPlaying)
        render(m, .1f); assertFalse(v.isPlaying, "un son de 0,5 s joué deux fois plus vite dure 0,25 s")
        assertEquals(0, m.playing)
    }

    @Test fun uneBoucleContinueEtSArreteSansClic() {
        val m = Mixer()
        val v = m.play(sine(200f, .1f), loop = true)
        val a = render(m, .5f)
        assertTrue(v.isPlaying); assertTrue(rms(a, 0, a.size / 2 - 4800) > .6f)
        v.stop(.02f)
        val b = render(m, .1f)
        var jump = 0f
        for (k in 1 until b.size / 2) jump = max(jump, abs(b[2 * k] - b[2 * k - 2]))
        // Pente maximale d'un sinus de 200 Hz d'amplitude 1 : 2π·200/48000 ≈ 0,026 par échantillon.
        assertTrue(jump < .04f, "saut de $jump à l'arrêt")
        assertEquals(0f, rms(b, 0, b.size / 2 - 2400), 1e-6f)
        assertFalse(v.isPlaying)
    }

    @Test fun beaucoupDeSonsEnsembleNeSaturentJamaisEtLeVolumeRevient() {
        val m = Mixer()
        repeat(40) { m.play(sine(220f, .3f)) }
        val o = render(m, .3f)
        assertTrue(o.all { abs(it) <= 1f }, "crête ${o.maxOf { abs(it) }}")
        render(m, 1.5f)                                  // le limiteur relâche en ~0,3 s (constante de temps)
        m.play(sine(220f, 1f, .5f))
        val after = render(m, .3f)
        assertEquals(.5f / sqrt(2f), rms(after, 0), .02f, "le limiteur doit relâcher")
    }

    @Test fun lesVoixSontReprisesQuandToutesSontPrises() {
        val m = Mixer(maxVoices = 4)
        val ambiance = m.play(sine(100f, 1f), loop = true)
        val courts = (0 until 6).map { m.play(sine(300f, 1f)) }
        render(m, .05f)
        assertTrue(ambiance.isPlaying, "une boucle n'est pas volée tant qu'il y a des sons courts")
        assertTrue(courts.last().isPlaying); assertFalse(courts.first().isPlaying)
        assertEquals(4, m.playing)
    }

    @Test fun mixerNAlloueRien() {
        val m = Mixer(); m.listen(Vec3.ZERO, Vec3(0f, 0f, -1f))
        val s = sine(330f, 2f, .2f)
        repeat(10) { m.playAt(s, Vec3(it.toFloat(), 0f, -2f), loop = true) }
        repeat(10) { m.play(s, loop = true, pan = it / 10f) }
        m.reverb = Reverb.HALL
        val buf = FloatArray(512)
        repeat(200) { m.render(buf, 256) }               // échauffement (compilation JIT)
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val t = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(t)
        repeat(2000) { m.render(buf, 256) }
        val bytes = bean.getThreadAllocatedBytes(t) - before
        assertTrue(bytes < 16_384, "$bytes octets alloués pour 2000 blocs")
    }

    /** Fréquence d'un canal par ses passages à zéro montants, entre [from] et [to] secondes. */
    private fun frequency(x: FloatArray, ch: Int, from: Float, to: Float): Float {
        val a = (from * rate).toInt(); val b = (to * rate).toInt()
        var first = -1.0; var last = -1.0; var count = 0
        for (i in a + 1 until b) {
            val p = x[2 * (i - 1) + ch]; val q = x[2 * i + ch]
            if (p < 0f && q >= 0f) { val t = i - 1 + p / (p - q).toDouble(); if (first < 0) first = t else count++; last = t }
        }
        return (count * rate / (last - first)).toFloat()
    }

    /** Une source qui file sur l'axe z et passe à côté de l'auditeur ; sa position change à 60 images par seconde, comme dans un jeu. */
    private fun passBy(speed: Float, doppler: Float = 1f, teleport: Boolean = false): FloatArray {
        val m = Mixer(); m.doppler = doppler; m.listen(Vec3.ZERO, Vec3(1f, 0f, 0f))
        val v = m.playAt(sine(1000f, 5f, .5f), Vec3(1f, 0f, -2 * speed), referenceDistance = 2f, maxDistance = 400f)
        val block = 200; val n = 4 * rate / block
        val out = FloatArray(2 * n * block); val buf = FloatArray(2 * block)
        for (k in 0 until n) {
            val t = k * block / rate.toFloat()
            if (k % 4 == 0) v.position(if (teleport) Vec3(1f, 0f, if (t < 2f) -5f else -60f) else Vec3(1f, 0f, -2 * speed + speed * t))
            m.render(buf, block); System.arraycopy(buf, 0, out, 2 * k * block, 2 * block)
        }
        return out
    }

    @Test fun effetDopplerQuiApprochePlusAiguQuiSEloignePlusGrave() {
        val o = passBy(30f)
        val coming = frequency(o, 1, .6f, 1.5f); val going = frequency(o, 1, 2.5f, 3.4f)
        // f' = f · c / (c + v), v la vitesse à laquelle la distance grandit.
        assertEquals(1000f * 343f / (343f - 30f), coming, 3f, "en approche")
        assertEquals(1000f * 343f / (343f + 30f), going, 3f, "en s'éloignant")
        val off = passBy(30f, doppler = 0f)
        assertEquals(1000f, frequency(off, 1, .6f, 1.5f), 1f, "sans effet Doppler")
        // Un saut (réapparition, coupure de caméra) n'est pas une vitesse : pas de glissando.
        val jump = passBy(0f, teleport = true)
        assertEquals(1000f, frequency(jump, 1, 2.01f, 2.3f), 3f, "après un saut de 55 m")
    }

    /** Durée de réverbération (−60 dB) par l'intégrale de Schroeder, droite ajustée entre −5 et −35 dB. */
    private fun rt60(x: FloatArray, from: Int): Float {
        val n = x.size / 2
        val e = DoubleArray(n - from) { val i = from + it; (x[2 * i] * x[2 * i] + x[2 * i + 1] * x[2 * i + 1]).toDouble() }
        for (i in e.size - 2 downTo 0) e[i] += e[i + 1]
        val db = DoubleArray(e.size) { 10 * log10(e[it] / e[0]) }
        val a = db.indexOfFirst { it <= -5 }; val b = db.indexOfFirst { it <= -35 }
        val slope = (db[b] - db[a]) / ((b - a).toDouble() / rate)
        return (-60 / slope).toFloat()
    }

    @Test fun laReverberationDureCeQuOnLuiDemande() {
        for (decay in floatArrayOf(.8f, 2.5f)) {
            val m = Mixer(); m.listen(Vec3.ZERO, Vec3(0f, 0f, -1f))
            m.reverb = Reverb(decay = decay, wet = .4f, damping = 0f)
            m.playAt(Sound(FloatArray(96) { if (it < 48) .8f else -.8f }), Vec3(0f, 0f, -3f), maxDistance = 100f)
            val o = render(m, decay * 1.6f)
            val t = rt60(o, (.02f * rate).toInt())
            assertEquals(decay, t, decay * .12f, "durée de réverbération")
            // Les deux oreilles n'entendent pas la même queue : un son large, pas au centre.
            var lr = 0.0; var ll = 0.0; var rr = 0.0
            for (i in (.1f * rate).toInt() until (.5f * rate).toInt()) { lr += o[2 * i] * o[2 * i + 1]; ll += o[2 * i] * o[2 * i]; rr += o[2 * i + 1] * o[2 * i + 1] }
            assertTrue(abs(lr / sqrt(ll * rr)) < .5, "queue trop corrélée entre gauche et droite : ${lr / sqrt(ll * rr)}")
        }
        // Sans réverbération : rien après le son.
        val m = Mixer(); m.playAt(Sound(FloatArray(96) { .8f }), Vec3(0f, 0f, -3f))
        val o = render(m, .3f)
        assertEquals(0f, rms(o, 0, (.05f * rate).toInt()), 0f)
        // Plus loin, plus de réverbération par rapport au son direct.
        fun ratio(d: Float): Float {
            val r = Mixer(); r.reverb = Reverb.HALL; r.listen(Vec3.ZERO, Vec3(0f, 0f, -1f))
            r.playAt(sine(500f, .2f, .5f), Vec3(0f, 0f, -d), maxDistance = 200f)
            val x = render(r, .8f)
            return rms(x, 0, (.25f * rate).toInt(), (.6f * rate).toInt()) / rms(x, 0, (.05f * rate).toInt(), (.18f * rate).toInt())
        }
        assertTrue(ratio(20f) > 1.5f * ratio(3f), "loin ${ratio(20f)}, près ${ratio(3f)}")
    }

    @Test fun wavAllerRetour() {
        val s = Sound(FloatArray(2000) { sin(it * .05f) * .7f }, 2, 44_100)
        val back = Sound.wav(s.toWav())
        assertEquals(2, back.channels); assertEquals(44_100, back.rate); assertEquals(s.frames, back.frames)
        for (i in s.data.indices) assertEquals(s.data[i], back.data[i], 1f / 16_000f)
    }

    @Test fun sortieAlsaSurLePeripheriqueNul() {
        if (!Files.exists(Path.of("/usr/lib/libasound.so.2")) && !Files.exists(Path.of("/usr/lib64/libasound.so.2"))) return
        // Le périphérique « null » d'ALSA ne joue rien : aucun son ne sort pendant les tests.
        Audio.open("null").use { a ->
            assertEquals("null", a.device)
            assertTrue(a.latency in .005f..0.2f, "latence ${a.latency}")
            val v = a.play(sine(440f, .1f))
            Thread.sleep(400)
            assertFalse(v.isPlaying, "le fil audio doit consommer le son")
        }
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "sillage-audio" && it.isAlive }, "le fil audio doit s'arrêter")
    }

    @Test fun unPeripheriqueAbsentDonneUnJeuMuetSansPlanter() {
        val a = Audio.open("pas-un-peripherique-sillage")
        assertNull(a.device); a.play(sine(440f, .1f)); a.close()
    }
}
