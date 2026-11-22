package sillage.audio

import sillage.fmt
import kotlin.math.*
import kotlin.test.*

class SoundsTest {
    private fun peak(s: Sound) = s.data.maxOf { abs(it) }
    private fun energy(d: FloatArray) = d.sumOf { (it * it).toDouble() }
    /** Part of the energy above [hz]: how bright a sound is. */
    private fun bright(s: Sound, hz: Float): Double { val f = Biquad(s.rate).highPass(hz); return energy(FloatArray(s.frames) { f.process(s.data[it]) }) / energy(s.data) }
    /** Strongest frequency between [lo] and [hi] Hz of the samples [from] until [to]. */
    private fun pitch(s: Sound, from: Int, to: Int, lo: Int, hi: Int): Int = (lo..hi step 2).maxBy { f ->
        var re = 0.0; var im = 0.0
        for (i in from until to) { val a = 2 * PI * f * i / s.rate; re += s.data[i] * cos(a); im += s.data[i] * sin(a) }
        re * re + im * im
    }

    private val all: Map<String, Sound> by lazy {
        val m = LinkedHashMap<String, Sound>()
        for (sol in listOf("grass", "sand", "wood", "stone", "road", "ice", "default")) for (v in 0..3) m["pas-$sol-$v"] = Sounds.footstep(sol, v)
        m["saut"] = Sounds.jump(); m["atterrissage"] = Sounds.land(); m["coquillage"] = Sounds.pickup()
        for (v in 0..2) { m["caisse-$v"] = Sounds.knock(v); m["ballon-$v"] = Sounds.bounce(v); m["vagues-$v"] = Sounds.waves(v); m["mouette-$v"] = Sounds.gull(v) }
        m["frottement"] = Sounds.scrape()
        m
    }

    @Test fun tousLesSonsSontPropresEtReproductibles() {
        for ((nom, s) in all) {
            assertTrue(s.data.all { it.isFinite() }, nom)
            assertTrue(peak(s) in .3f..1f, "$nom : crête ${peak(s)}")
            // Les boucles (vagues, frottement) n'ont pas de bords : leur couture est vérifiée à part.
            val boucle = nom.startsWith("vagues") || nom == "frottement"
            assertTrue(boucle || (abs(s.data.first()) < .01f && abs(s.data.last()) < .01f), "$nom : bords")
        }
        assertContentEquals(Sounds.footstep("sand", 2).data, all.getValue("pas-sand-2").data)
        assertFalse(Sounds.footstep("sand", 1).data.contentEquals(all.getValue("pas-sand-2").data), "les variantes diffèrent")
    }

    @Test fun lesSolsSeDistinguent() {
        fun b(sol: String) = (0..3).map { bright(all.getValue("pas-$sol-$it"), 2500f) }.average()
        val bois = b("wood"); val herbe = b("grass"); val sable = b("sand"); val pierre = b("stone")
        println("PAS énergie au-dessus de 2,5 kHz : bois ${"%.2f".fmt(bois)}, herbe ${"%.2f".fmt(herbe)}, pierre ${"%.2f".fmt(pierre)}, sable ${"%.2f".fmt(sable)}")
        assertTrue(bois < herbe && herbe < sable, "le bois sonne sourd, le sable crisse")
        assertTrue(all.getValue("pas-stone-0").duration < all.getValue("pas-grass-0").duration, "un talon sur la pierre est bref")
    }

    @Test fun leCoquillageJoueUnArpegeMontant() {
        val s = all.getValue("coquillage")
        val r = s.rate
        val notes = (0..3).map { k -> pitch(s, (k * .075f * r).toInt() + 100, (k * .075f * r).toInt() + 3000, 1000, 3000) }
        println("COQUILLAGE notes : $notes Hz")
        for ((n, f) in notes.zip(listOf(1318, 1661, 1975, 2637))) assertTrue(abs(n - f) <= 20, "note $n au lieu de $f")
    }

    @Test fun lesVaguesBouclentSansCouture() {
        for (v in 0..2) {
            val s = all.getValue("vagues-$v")
            val rms = sqrt(energy(s.data) / s.frames)
            val seam = abs(s.data.last() - s.data.first())
            assertTrue(seam < 3 * rms, "vagues-$v : couture $seam pour un niveau $rms")
            assertTrue(s.duration > 8f, "une boucle courte s'entendrait")
            assertTrue(bright(s, 6000f) < .1, "la mer reste douce")
        }
    }

    @Test fun laMouetteCrieDansLAigu() {
        val s = all.getValue("mouette-0")
        val f = pitch(s, (.06f * s.rate).toInt(), (.12f * s.rate).toInt(), 800, 2000)
        assertTrue(f in 1300..1800, "hauteur $f Hz")
    }
}
