package sillage.ui

import sillage.io.FontFace
import sillage.io.FontRaster
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.*

/**
 * Le lecteur de polices et le tramage du moteur, comparés à FreeType et HarfBuzz sur des polices installées :
 * une TrueType à glyphes composés et crénage GPOS (Noto Sans), une autre avec aussi une table `kern`
 * (DejaVu Sans), une à empattements (Liberation Serif), une collection CFF à identifiants CID (Noto Sans
 * CJK, deux faces) et une OpenType CFF simple (Powerline). Les références viennent de
 * tools/freetype_reference.py (engine/test/fixtures/fonts) ; une police absente, ou d'une autre version que
 * celle de la référence, est passée.
 */
class FontTest {
    private class Glyph(val px: Int, val gid: Int, val left: Int, val top: Int, val w: Int, val h: Int, val pixels: ByteArray)
    private class Reference(val path: Path, val index: Int, val size: Long, val checksum: Int, val metrics: IntArray,
                            val chars: List<IntArray>, val kern: List<IntArray>, val gpos: List<IntArray>, val glyphs: List<Glyph>)

    private fun fixtures(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("engine/test/fixtures/fonts"))) dir = dir.parent ?: error("fixtures introuvables")
        return dir.resolve("engine/test/fixtures/fonts")
    }

    private fun reference(name: String): Reference? {
        val lines = GZIPInputStream(Files.newInputStream(fixtures().resolve("$name.txt.gz"))).bufferedReader().readLines()
        val head = lines[0].split(' ')
        val path = Path.of(head[1])
        if (!Files.exists(path) || Files.size(path) != head[3].toLong()) { println("police absente ou changée, passée : $path"); return null }
        val chars = ArrayList<IntArray>(); val kern = ArrayList<IntArray>(); val gpos = ArrayList<IntArray>(); val glyphs = ArrayList<Glyph>()
        var metrics = IntArray(0)
        for (l in lines.drop(1)) {
            val f = l.split(' ')
            when (f[0]) {
                "metrics" -> metrics = IntArray(5) { f[1 + it].toInt() }
                "char" -> chars.add(IntArray(3) { f[1 + it].toInt() })
                "kern" -> kern.add(IntArray(3) { f[1 + it].toInt() })
                "gpos" -> gpos.add(IntArray(3) { f[1 + it].toInt() })
                "glyph" -> {
                    val hex = f.getOrElse(7) { "" }
                    glyphs.add(Glyph(f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt(),
                        ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }))
                }
            }
        }
        val face = FontFace.load(path, head[2].toInt())
        if (face.checksum != head[4].toLong(16).toInt()) { println("autre version de la police, passée : $path"); return null }
        return Reference(path, head[2].toInt(), head[3].toLong(), head[4].toLong(16).toInt(), metrics, chars, kern, gpos, glyphs)
    }

    private val fonts = listOf("noto-sans", "dejavu-sans", "liberation-serif", "noto-cjk-0", "noto-cjk-3", "powerline")

    @Test fun lesTablesDisentCommeFreeType() {
        var checked = 0
        for (name in fonts) {
            val r = reference(name) ?: continue
            val face = FontFace.load(r.path, r.index)
            assertEquals(r.metrics[0], face.unitsPerEm, "$name : unitsPerEm")
            assertEquals(r.metrics[4], face.glyphCount, "$name : nombre de glyphes")
            assertEquals(r.metrics[1], face.ascent, "$name : ascendante")
            assertEquals(r.metrics[2], face.descent, "$name : descendante")
            assertEquals(r.metrics[3], face.ascent - face.descent + face.lineGap, "$name : interligne")
            for ((c, gid, advance) in r.chars) {
                assertEquals(gid, face.glyph(c), "$name : glyphe de U+%04X".format(c))
                assertEquals(advance, face.advance(gid), "$name : avance de U+%04X".format(c))
            }
            for ((a, b, k) in r.kern) assertEquals(k, face.tableKerning(a, b), "$name : table kern ($a, $b)")
            for ((a, b, k) in r.gpos) {
                // HarfBuzz lit GPOS quand la police en a, sinon la table kern : comme kerning().
                assertEquals(k, face.kerning(a, b), "$name : crénage ($a, $b)")
            }
            checked++
        }
        println("tables comparées sur $checked polices")
    }

    @Test fun lesNomsEtLesStyles() {
        val noto = Path.of("/usr/share/fonts/noto/NotoSans-Bold.ttf")
        if (Files.exists(noto)) {
            val f = FontFace.load(noto)
            assertEquals("Noto Sans", f.family); assertEquals("Bold", f.subfamily); assertEquals(700, f.weight); assertFalse(f.italic)
            assertTrue(f.capHeight > 600, "hauteur des capitales lue dans OS/2")
        }
        val cjk = Path.of("/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc")
        if (Files.exists(cjk)) {
            val faces = FontFace.scan(cjk)
            assertTrue(faces.size >= 5, "une collection : ${faces.size} faces")
            assertTrue(faces.any { it.family == "Noto Sans CJK JP" }, faces.joinToString { it.family })
            assertEquals(faces[3].family, FontFace.load(cjk, 3).family)
        }
    }

    /**
     * Le tramage, pixel par pixel contre celui de FreeType (sans hinting, niveaux de gris), dans un cadre de
     * 2 pixels autour de la boîte de FreeType. Mesuré le 21 novembre 2026 (écart maximal, moyen sur les pixels
     * encrés, sur 255) : Noto Sans 18 / 1,14 ; DejaVu Sans 16 / 0,97 ; Liberation Serif 12 / 1,19 ;
     * Powerline (CFF) 20 / 0,85 ; Noto Sans CJK (CFF, CID) 36 / 2,79 et 43 / 3,12. La même encre (±0,5 %) et le
     * même centre (±0,005 px) partout : les écarts sont au bord des courbes, que FreeType découpe plus
     * grossièrement (un demi-pixel de tolérance pour les cubiques, 1/50 ici), plus visibles sur les
     * idéogrammes CFF, faits de courbes cubiques.
     */
    @Test fun leTramageRessembleAFreeType() {
        val raster = FontRaster()
        val limits = mapOf("noto-sans" to (24 to 1.5), "dejavu-sans" to (24 to 1.5), "liberation-serif" to (24 to 1.5),
            "powerline" to (28 to 1.5), "noto-cjk-0" to (48 to 4.0), "noto-cjk-3" to (52 to 4.0))
        var glyphs = 0
        for (name in fonts) {
            val r = reference(name) ?: continue
            val face = FontFace.load(r.path, r.index)
            var worst = 0; var total = 0L; var count = 0L; var ink = 0L; var refInk = 0L
            for (g in r.glyphs) {
                val m = 2
                val w = g.w + 2 * m; val h = g.h + 2 * m
                val mine = ByteArray(w * h)
                raster.draw(face.outline(g.gid), g.px.toFloat() / face.unitsPerEm, (m - g.left).toFloat(), (m + g.top).toFloat(), w, h, mine)
                for (y in 0 until h) for (x in 0 until w) {
                    val ref = if (x in m until m + g.w && y in m until m + g.h) g.pixels[(y - m) * g.w + (x - m)].toInt() and 255 else 0
                    val v = mine[y * w + x].toInt() and 255
                    val d = abs(ref - v)
                    if (ref != 0 || v != 0) { total += d; count++ }
                    worst = max(worst, d); ink += v; refInk += ref
                }
                glyphs++
            }
            val mean = total.toDouble() / max(1, count)
            println("FREETYPE $name : ${r.glyphs.size} glyphes, écart max $worst, moyen ${"%.2f".format(mean)} (sur 255), encre ${"%.4f".format(ink.toDouble() / refInk)}")
            val (maxLimit, meanLimit) = limits.getValue(name)
            assertTrue(worst <= maxLimit, "$name : écart maximal $worst sur 255")
            assertTrue(mean <= meanLimit, "$name : écart moyen $mean sur 255")
            assertEquals(1.0, ink.toDouble() / refInk, .01, "$name : autant d'encre que FreeType")
        }
        println("glyphes comparés : $glyphs")
    }
}
