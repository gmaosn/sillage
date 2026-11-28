package sillage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

/** Textures, lueurs, vent, heure du jour, lampes et particules. */
class LooksTest {
    private val dt = 1f / 60f
    private fun root(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("project.yaml"))) dir = dir.parent
        return dir
    }
    private fun model(name: String) = root().resolve("demo/resources/models/$name.glb").toString()
    private fun Scene.run(frames: Int, each: (Int) -> Unit = {}) = repeat(frames) { update(dt); camera.view(dt); each(it) }

    // ------------------------------------------------------------------ textures

    @Test fun lesTexturesSeTrouventParLeNomDesMateriaux() {
        assertSame(Textures.GRASS, Textures.named("herbe"))
        assertSame(Textures.GRASS, Textures.named("Grass_dark.001"))
        assertSame(Textures.SAND, Textures.named("sable_mouille"))
        assertSame(Textures.ROOF_TILES, Textures.named("Toit"))
        assertSame(Textures.PLASTER, Textures.named("plâtre"))
        assertSame(Textures.PLASTER, Textures.named("Enduit"))
        assertSame(Textures.ROCK, Textures.named("galet"))
        assertSame(Textures.PLANKS, Textures.named("deck"))
        assertSame(Textures.WOOD, Textures.named("bois_clair"))
        assertSame(Textures.LEAVES, Textures.named("buisson_fonce"))
        assertSame(Textures.BARK, Textures.named("tronc"))
        assertNull(Textures.named("corail")); assertNull(Textures.named("Material.001")); assertNull(Textures.named("marine"))
    }

    /** Une texture détaille sans recolorer (teinte moyenne proche du gris neutre) et se répète sans couture. */
    @Test fun lesTexturesSeRaccordentEtGardentLaCouleur() {
        for (t in Textures.ALL) {
            val px = t.pixels; val s = t.size
            var total = 0L; var j = 0; while (s shr j >= 1) { total += 4L * (s shr j) * (s shr j); j++ }
            assertEquals(total, px.size.toLong(), "${t.name} : tous les niveaux")
            assertEquals(j, t.levelCount)
            fun at(x: Int, y: Int, c: Int) = px[4 * (y * s + x) + c].toInt() and 255
            for (c in 0..2) {
                var sum = 0.0
                for (y in 0 until s) for (x in 0 until s) sum += at(x, y, c)
                val mean = sum / (s * s) / 127.5
                assertTrue(mean in .85..1.15, "${t.name} : teinte moyenne ${"%.3f".format(mean)} sur le canal $c")
            }
            // Écart moyen entre deux colonnes (ou rangées) voisines. Une couture se voit comme un saut au bord
            // (s−1 puis 0) que ses voisines immédiates n'ont pas ; une fissure qui passe là en a autant de chaque côté.
            fun gap(a: Int, b: Int): Double { var d = 0.0; for (y in 0 until s) for (c in 0..2) d += abs(at(a, y, c) - at(b, y, c)); return d / (3 * s) }
            fun gapRows(a: Int, b: Int): Double { var d = 0.0; for (x in 0 until s) for (c in 0..2) d += abs(at(x, a, c) - at(x, b, c)); return d / (3 * s) }
            // Les motifs réguliers (fils, rangées, planches) ont une limite au milieu comme au bord : on l'y compare aussi.
            val nearby = maxOf(gap(s - 3, s - 2), gap(1, 2), gap(s / 2 - 1, s / 2))
            val nearbyRows = maxOf(gapRows(s - 3, s - 2), gapRows(1, 2), gapRows(s / 2 - 1, s / 2))
            assertTrue(gap(s - 1, 0) < 1.6 * nearby + 1.5, "${t.name} : couture verticale ${gap(s - 1, 0)} contre $nearby à côté")
            assertTrue(gapRows(s - 1, 0) < 1.6 * nearbyRows + 1.5, "${t.name} : couture horizontale ${gapRows(s - 1, 0)} contre $nearbyRows à côté")
        }
    }

    @Test fun unePeintureMaisonEstUneTexture() {
        val t = Texture.paint("rayures", tile = 2, size = 16) { u, _ -> if (u < .5f) Color(.25f, .25f, .25f) else Color(.75f, .75f, .75f) }
        val px = t.pixels
        assertEquals(64, px[0].toInt() and 255); assertEquals(191, px[4 * 15].toInt() and 255); assertEquals(255, px[3].toInt() and 255)
        assertEquals(128, px[px.size - 4].toInt() and 255, "le dernier niveau est la moyenne")
        assertEquals(2f, t.tile)
        assertFailsWith<IllegalArgumentException> { Texture.paint("x", size = 12) { _, _ -> Color.WHITE } }
    }

    @Test fun lesModelesPrennentLeursTexturesEtLesCopiesRestentPartagees() {
        val s = Scene()
        val terrain = s.model(model("terrain"))
        val mesh = terrain.mesh; val e = mesh.effects!!; val mats = mesh.materials!!
        val names = terrain.model!!.materials
        for (t in 0 until mesh.triangleCount) {
            val expected = Textures.named(names[mats[t]])
            assertEquals(expected?.id?.toFloat() ?: 0f, e[3 * t * EFFECT_FLOATS + 4], "triangle $t (${names[mats[t]]})")
        }
        val a = s.model(model("caisse")); val b = s.model(model("caisse"))
        assertSame(a.mesh, b.mesh, "deux caisses : un seul maillage, donc un seul appel de dessin"); assertNotNull(a.mesh.effects)
        val boxes = List(100) { s.box(1, at = vec(it, 0, 0)).texture(Textures.PLANKS) }
        assertEquals(1, boxes.map { System.identityHashCode(it.mesh) }.toSet().size, "100 boîtes texturées pareil : un maillage")
        val sol = s.ground()
        assertEquals(Textures.GRASS.id.toFloat(), sol.mesh.effects!![4], "le sol prend la texture de sa surface")
        val plain = Scene().apply { autoTextures = false }.model(model("terrain"))
        assertNull(plain.mesh.effects)
        val none = s.model(model("terrain")).texture(null)
        assertEquals(0f, none.mesh.effects!!.filterIndexed { i, _ -> i % EFFECT_FLOATS == 4 }.max(), "texture(null) les retire")
    }

    // ------------------------------------------------------------------ vent et lueurs

    @Test fun leVentNeBalanceQueLesPlantesEtDavantageEnHaut() {
        val s = Scene(); s.model(model("terrain")).solid()
        val v = s.model(model("village")).sway(.1, "touffe", "fleur_*", reach = .3)
        val m = v.mesh; val e = m.effects!!; val mats = m.materials!!; val names = v.model!!.materials
        var plants = 0; var tipAboveBase = 0; var tufts = 0
        for (t in 0 until m.triangleCount) {
            val name = names[mats[t]]
            val sway = FloatArray(3) { e[(3 * t + it) * EFFECT_FLOATS + 3] }
            if (name == "touffe" || name.startsWith("fleur_")) {
                plants++
                assertTrue(sway.all { it in 0f..(.1f + 1e-6f) }, "$name : ${sway.toList()}")
                if (name == "touffe") {
                    tufts++
                    val ys = FloatArray(3) { m.vertices[9 * (3 * t + it) + 1] }
                    val top = ys.indices.maxBy { ys[it] }; val low = ys.indices.minBy { ys[it] }
                    if (sway[top] > sway[low] + .02f) tipAboveBase++
                }
            } else assertTrue(sway.all { it == 0f }, "$name ne bouge pas")
        }
        assertTrue(plants > 500); assertTrue(tipAboveBase > tufts * 9 / 10, "la pointe bouge plus que la base : $tipAboveBase / $tufts")
        assertFailsWith<IllegalArgumentException> { v.sway(.1, "inconnu") }
        assertFailsWith<IllegalArgumentException> { s.box(1).sway(.1, "touffe") }
    }

    @Test fun lesFenetresBrillentLaNuitSeulement() {
        val s = Scene()
        val v = s.model(model("village")).glow(Color(1f, .5f, .2f), "vitre", intensity = 2, atNight = true)
        val m = v.mesh; val e = m.effects!!; val mats = m.materials!!; val names = v.model!!.materials
        var lit = 0
        for (t in 0 until m.triangleCount) for (k in 0..2) {
            val o = (3 * t + k) * EFFECT_FLOATS
            if (names[mats[t]] == "vitre") { assertEquals(-2f, e[o], 1e-6f); assertEquals(-1f, e[o + 1], 1e-6f); assertEquals(-.4f, e[o + 2], 1e-6f); lit++ }
            else assertEquals(0f, e[o])
        }
        assertTrue(lit > 0)
        // Les textures posées d'elles-mêmes restent.
        assertTrue((0 until m.triangleCount).any { e[3 * it * EFFECT_FLOATS + 4] > 0f })
    }

    // ------------------------------------------------------------------ heure du jour

    @Test fun lHeureFaitTournerLeSoleilEtTomberLaNuit() {
        val l = Lighting()
        assertNull(l.time); assertEquals(0f, l.night); assertEquals(1f, l.sunShows())
        l.time = 12f; assertTrue(l.sunDirection.y > .8f); assertEquals(0f, l.night)
        l.time = 6f; assertEquals(0f, l.sunDirection.y, .01f)
        l.time = 18.2f; assertTrue(l.night in .1f..0.9f, "crépuscule : ${l.night}")
        l.time = 0f; assertEquals(1f, l.night); assertTrue(l.sunDirection.y < -.8f)
        assertTrue(l.mainLight().y >= .12f - 1e-4f, "la lumière principale ne passe jamais sous l'horizon")
        l.time = 25f; assertEquals(1f, l.time!!, 1e-5f)
        l.time = 10f; l.dayLength = 240f; l.advance(10f); assertEquals(11f, l.time!!, 1e-4f)
        val noon = Lighting().apply { time = 12f }; val dusk = Lighting().apply { time = 18.6f }
        assertTrue(dusk.zenith.b < noon.zenith.b, "le ciel s'assombrit")
    }

    // ------------------------------------------------------------------ lampes et particules

    @Test fun lesLampesLesPlusProchesEtCellesDeLaNuit() {
        val s = Scene()
        repeat(20) { s.light(at = vec(it * 2, 1, 0), radius = 3) }
        s.light(at = vec(0, 1, 1), radius = 6, onlyAtNight = true)
        s.lighting.time = 12f
        var x = s.frameExtras(vec(0, 1, 0))
        // Toutes les lampes allumées (128 au plus), la plus proche d'abord ; celle du soir est éteinte de jour.
        assertEquals(20, x.lightCount)
        assertEquals(0f, x.lights[0]); assertEquals(2f, x.lights[8]); assertEquals(30f, x.lights[8 * 15]); assertEquals(38f, x.lights[8 * 19])
        s.lighting.time = 23f
        x = s.frameExtras(vec(0, 1, 0))
        assertEquals(1f, x.lights[2], "la nuit, la lampe du soir est la plus proche"); assertEquals(21, x.lightCount)
        repeat(200) { s.light(at = vec(100 + it, 1, 0), radius = 3) }
        assertEquals(MAX_LIGHTS, s.frameExtras(vec(0, 1, 0)).lightCount, "au plus $MAX_LIGHTS, les plus proches")
        repeat(200) { s.removeLight(s.lights.last()) }
        val box = s.box(1, at = vec(5, 0, 5)); box.light(offset = vec(0, 2, 0), radius = 50)
        box.position = vec(7, 0, 5)
        x = s.frameExtras(vec(0, 1, 0))
        assertTrue((0 until x.lightCount).any { x.lights[8 * it] == 7f && x.lights[8 * it + 1] == 2f }, "une lampe suit son nœud")
        box.remove(); assertEquals(21, s.lights.size)
    }

    @Test fun lesParticulesNaissentEtMeurent() {
        val s = Scene()
        val e = s.emit(ParticleStyle(Color.WHITE, life = 1f, lifeSpread = 0f, speed = 1f), at = vec(0, 0, 0), rate = 30)
        s.run(120)
        assertTrue(s.particles.count in 28..31, "${s.particles.count} en vie")
        e.enabled = false; s.run(70)
        assertEquals(0, s.particles.count)
        s.burst(Particles.SPARKLE, vec(0, 1, 0), 25); assertEquals(25, s.particles.count)
        // Les mêmes gestes donnent les mêmes particules.
        fun positions(): List<Float> { val t = Scene(); t.burst(Particles.FIRE, vec(1, 2, 3), 40); t.run(20); val x = t.frameExtras(Vec3Zero); return x.particles.take(8 * x.particleCount) }
        assertEquals(positions(), positions())
        s.restart(); assertEquals(0, s.particles.count)
    }

    @Test fun uneCaisseQuiTombeALEauEclabousse() {
        val s = Scene(); s.water(level = 0)
        s.box(.5f, at = vec(0, 3, 0)).dynamic()
        var most = 0; s.run(120) { most = max(most, s.particles.count) }
        assertTrue(most >= 8, "$most gouttes")
        val calm = Scene(); calm.water(level = 0); calm.box(.5f, at = vec(0, 3, 0))
        var none = 0; calm.run(60) { none = max(none, calm.particles.count) }
        assertEquals(0, none, "rien ne tombe : pas d'éclaboussure")
    }

    private val Vec3Zero = vec(0, 0, 0)
}
