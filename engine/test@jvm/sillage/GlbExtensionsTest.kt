package sillage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

/**
 * What the loader now accepts: morph targets (deformed on the processor, weights animated), CUBICSPLINE curves and
 * KHR_texture_transform, on the GLB that tools/create_glb_essais.py writes (engine/test@jvm/fixtures/glb), whose
 * values are chosen to be checked by hand.
 */
class GlbExtensionsTest {
    private fun fixture(name: String): ByteArray {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("engine/test@jvm/fixtures/glb"))) dir = dir.parent ?: error("fixtures introuvables")
        return Files.readAllBytes(dir.resolve("engine/test@jvm/fixtures/glb/$name"))
    }

    /** The positions of a mesh's vertices, rounded to the millimetre (the order of the triangles aside). */
    private fun positions(m: Mesh) = (0 until m.vertices.size / 9).map { v ->
        Triple((m.vertices[9 * v] * 1000).roundToInt(), (m.vertices[9 * v + 1] * 1000).roundToInt(), (m.vertices[9 * v + 2] * 1000).roundToInt())
    }.toSet()

    @Test fun lesCiblesDeMorphoseSontAuRepos() {
        val model = Glb.readModel(fixture("morphose.glb"))
        // « visage » : les poids du maillage (0,25 ; 0), le haut monte d'un quart ; « masque » (décalé de 3 m) : les siens (0 ; 1), la droite avance de 0,5.
        val p = positions(model.flatten())
        assertTrue(Triple(1000, 1250, 0) in p && Triple(0, 1250, 0) in p, "le haut du visage à 1,25 m : $p")
        assertTrue(Triple(4000, 0, 500) in p && Triple(4000, 1000, 500) in p, "la droite du masque avancée de 0,5 m : $p")
        assertTrue(Triple(3000, 0, 0) in p, "la gauche du masque immobile")
    }

    @Test fun lesPoidsSAnimentEnLigneEnPalierEtEnCourbe() {
        val model = Glb.readModel(fixture("morphose.glb"))
        val parts = model.parts(); val a = Animator(model, parts)
        val visage = parts.first { it.node == 0 }; val masque = parts.first { it.node == 1 }
        // Linéaire : (0 ; 0) → (1 ; 0,5) en 1 s : à 0,5 s, (0,5 ; 0,25).
        a.play("respirer", fade = 0f); a.advance(.5f)
        assertEquals(.5f, a.morphWeights(0)!![0], 1e-6f); assertEquals(.25f, a.morphWeights(0)!![1], 1e-6f)
        val p = positions(visage.mesh)
        assertTrue(Triple(1000, 1500, 125) in p, "haut à 1,5 m, droite avancée de 0,125 : $p")
        assertTrue(visage.mesh.vertices.indices.all { visage.mesh.vertices[it].isFinite() })
        // Normales : unitaires après le déplacement.
        for (v in 0 until visage.mesh.vertices.size / 9) {
            val x = visage.mesh.vertices[9 * v + 3]; val y = visage.mesh.vertices[9 * v + 4]; val z = visage.mesh.vertices[9 * v + 5]
            assertEquals(1f, sqrt(x * x + y * y + z * z), 1e-4f)
        }
        // Courbe d'Hermite, tangente de sortie 2 au départ, valeur 1 et tangente 0 à 1 s : à 0,5 s, 0,125 × 2 + 0,5 = 0,75.
        a.play("cligner", fade = 0f); a.advance(.5f)
        assertEquals(.75f, a.morphWeights(0)!![0], 1e-5f)
        assertTrue(Triple(1000, 1750, 0) in positions(visage.mesh))
        // Palier, sur l'autre nœud : (0 ; 1) jusqu'à 1 s, puis (1 ; 0).
        a.play("sauter", loop = false, fade = 0f); a.advance(.5f)
        assertEquals(0f, a.morphWeights(1)!![0]); assertEquals(1f, a.morphWeights(1)!![1])
        a.advance(1f)
        assertEquals(1f, a.morphWeights(1)!![0]); assertEquals(0f, a.morphWeights(1)!![1])
        val q = positions(masque.mesh)
        assertTrue(Triple(1000, 0, 0) in q && Triple(1000, 2000, 0) in q, "le masque levé (son haut à 2 m), sa droite revenue : $q")
        assertEquals(2f, model.clips.getValue("respirer").duration)
    }

    @Test fun uneCourbeCubiqueDeplaceEtTourne() {
        val model = Glb.readModel(fixture("cubique.glb"))
        val a = Animator(model, model.parts())
        a.play("flotter", loop = false, fade = 0f); a.advance(1f)
        val pose = assertNotNull(a.nodePose("bouee"))
        // Hermite sur 2 s, à mi-chemin : 0,125 × (1 m/s × 2 s) + 0,5 × 1 m = 0,75 m.
        assertEquals(.75f, pose.m31, 1e-5f, "montée")
        // Tangentes nulles : à mi-chemin, la moitié du quart de tour (45°), le quaternion ramené à l'unité.
        assertEquals(cos(PI / 4).toFloat(), pose.m00, 1e-4f, "rotation")
        a.advance(1f)
        assertEquals(1f, assertNotNull(a.nodePose("bouee")).m31, 1e-5f, "à la fin, la valeur de la clé")
    }

    @Test fun laTransformeeDeTextureEstAppliqueeAuChargement() {
        val model = Glb.readModel(fixture("transformee.glb"))
        val mesh = model.flatten(); val e = assertNotNull(mesh.effects)
        fun uvs(right: Boolean) = (0 until mesh.vertices.size / 9).filter { (mesh.vertices[9 * it] > 1.5f) == right }
            .map { (e[it * EFFECT_FLOATS + 8] * 1000).roundToInt() to (e[it * EFFECT_FLOATS + 9] * 1000).roundToInt() }.toSet()
        // u' = 2 (u cos θ − v sin θ) + 0,5, v' = 2 (u sin θ + v cos θ), θ = π/2 : (0,0)→(0,5 ; 0), (1,0)→(0,5 ; 2), (1,1)→(−1,5 ; 2), (0,1)→(−1,5 ; 0).
        assertEquals(setOf(500 to 0, 500 to 2000, -1500 to 2000, -1500 to 0), uvs(right = false))
        assertEquals(setOf(0 to 0, 1000 to 0, 1000 to 1000, 0 to 1000), uvs(right = true), "l'autre matériau n'a pas de transformée")
    }

    @Test fun uneCubiqueMalFormeeEstRefusee() {
        val bytes = fixture("cubique.glb")
        // A cubic spline with one value per key (not three) is a broken file: refused with a message.
        val json = String(bytes, 20, java.nio.ByteBuffer.wrap(bytes, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertTrue("CUBICSPLINE" in json)
        val e = assertFailsWith<IllegalArgumentException> { splitKeys(FloatArray(6), 2, 3, cubic = true) }
        assertContains(e.message!!, "three values per key")
    }
}
