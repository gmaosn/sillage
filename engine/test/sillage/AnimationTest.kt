package sillage

import sillage.math.Quat
import sillage.math.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.*

class AnimationTest {
    private fun le(vararg v: Float): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()

    /**
     * Racine translatée de (0, 1, 0) avec un enfant porteur d'un triangle, animé par :
     * « tourner » : rotation Y de 0 à 90° en 1 s (LINEAR), « sauter » : translation en paliers (STEP).
     */
    private fun modele(): Model {
        val s = sqrt(.5f)
        val bin = le(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, -1f) +        // 36 : triangle
            le(0f, 1f) +                                          // 8 : temps
            le(0f, 0f, 0f, 1f, 0f, s, 0f, s) +                    // 32 : rotations
            le(0f, 0f, 0f, 2f, 0f, 0f)                            // 24 : translations
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
          "nodes":[{"name":"racine","translation":[0,1,0],"children":[1]},{"name":"bras","mesh":0}],
          "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
          "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
            {"bufferView":1,"componentType":5126,"count":2,"type":"SCALAR"},
            {"bufferView":2,"componentType":5126,"count":2,"type":"VEC4"},
            {"bufferView":3,"componentType":5126,"count":2,"type":"VEC3"}],
          "bufferViews":[{"buffer":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":8},
            {"buffer":0,"byteOffset":44,"byteLength":32},{"buffer":0,"byteOffset":76,"byteLength":24}],
          "buffers":[{"byteLength":${bin.size}}],
          "animations":[
            {"name":"tourner","samplers":[{"input":1,"output":2}],"channels":[{"sampler":0,"target":{"node":1,"path":"rotation"}}]},
            {"name":"sauter","samplers":[{"input":1,"output":3,"interpolation":"STEP"}],"channels":[{"sampler":0,"target":{"node":1,"path":"translation"}}]}]}"""
        val j = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val out = ByteBuffer.allocate(12 + 8 + j.size + 8 + bin.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546c67).putInt(2).putInt(out.capacity())
        out.putInt(j.size).putInt(0x4e4f534a).put(j)
        out.putInt(bin.size).putInt(0x004e4942).put(bin)
        return Glb.readModel(out.array())
    }

    private fun near(e: Vec3, a: Vec3, eps: Float = 1e-4f) = assertTrue(e.distanceTo(a) < eps, "attendu $e, obtenu $a")

    /** Direction de l'axe X de la pièce, dans l'espace de l'objet. */
    private fun axeX(p: Part) = p.pose.transformDirection(Vec3.X)

    @Test fun lectureDesNoeudsEtDesClips() {
        val m = modele()
        assertEquals(listOf("racine", "bras"), m.nodes.map { it.name })
        assertEquals(0, m.nodes[1].parent)
        assertEquals(setOf("tourner", "sauter"), m.clips.keys)
        assertEquals(1f, m.clips.getValue("tourner").duration)
        // Version aplatie au repos : le triangle suit la translation de la racine.
        assertEquals(1f, m.flatten().vertices[1])
    }

    @Test fun interpolationLineaireEnSlerpEtBoucle() {
        val m = modele(); val parts = m.parts(); val a = Animator(m, parts)
        assertEquals(1, parts.size)
        near(Vec3(0f, 1f, 0f), parts[0].pose.translation)
        a.play("tourner", fade = 0f)
        a.advance(.5f)
        val c = cos(PI / 4).toFloat(); val s = sin(PI / 4).toFloat()
        near(Vec3(c, 0f, -s), axeX(parts[0]))                  // 45° autour de Y
        a.advance(.75f)                                         // 1,25 s → 0,25 s en boucle
        near(Vec3(cos(PI / 8).toFloat(), 0f, -sin(PI / 8).toFloat()), axeX(parts[0]))
    }

    @Test fun sansBoucleOnResteSurLaDerniereCle() {
        val m = modele(); val parts = m.parts(); val a = Animator(m, parts)
        a.play("tourner", loop = false, fade = 0f)
        a.advance(3f)
        near(Vec3(0f, 0f, -1f), axeX(parts[0]))
    }

    @Test fun paliersSansInterpolation() {
        val m = modele(); val parts = m.parts(); val a = Animator(m, parts)
        a.play("sauter", fade = 0f)
        a.advance(.9f)
        near(Vec3(0f, 1f, 0f), parts[0].pose.translation)      // encore la première clé
        a.advance(.2f)                                          // 1,1 s → 0,1 s en boucle : retour au début
        near(Vec3(0f, 1f, 0f), parts[0].pose.translation)
        a.play("sauter", loop = false)
        a.advance(5f)
        near(Vec3(2f, 1f, 0f), parts[0].pose.translation)
    }

    @Test fun fonduEntreDeuxClips() {
        val m = modele(); val parts = m.parts(); val a = Animator(m, parts)
        a.play("tourner", loop = false, fade = 0f)
        a.advance(2f)                                           // pose figée à 90°
        a.play("sauter", fade = .2f)                          // « sauter » ne touche pas la rotation : retour au repos
        a.advance(.1f)                                          // mi-fondu : 45°
        val c = cos(PI / 4).toFloat()
        near(Vec3(c, 0f, -c), axeX(parts[0]), 1e-3f)
        a.advance(.2f)
        near(Vec3.X, axeX(parts[0]))
    }

    @Test fun clipAbsentSignale() {
        val e = assertFailsWith<IllegalArgumentException> { Animator(modele(), emptyList()).play("danser") }
        assertContains(e.message!!, "tourner")
    }

    @Test fun leMarinDeLaDemo() {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("project.yaml"))) dir = dir.parent
        val m = Glb.loadModel(dir.resolve("demo/resources/models/marin.glb").toString())
        assertEquals(setOf("idle", "run", "jump", "fall", "walk", "push", "land", "celebrate", "wave"), m.clips.keys)
        val parts = m.parts(); val a = Animator(m, parts)
        assertTrue(parts.size >= 10, "${parts.size} pièces")
        for (clip in m.clips.keys) {
            a.play(clip)
            repeat(40) { a.advance(1f / 60f) }
            for (p in parts) assertTrue(p.pose.toFloatArray().all { it.isFinite() }, "$clip : pose non finie")
        }
        // Pendant la course, les pieds passent devant puis derrière le bassin.
        a.play("run", fade = 0f)
        val tibia = parts.first { m.nodes[it.node].name == "jambe_d_bas" }
        var avant = 0f; var arriere = 0f
        repeat(36) { a.advance(1f / 60f); val z = tibia.pose.transformPoint(Vec3(0f, -.27f, 0f)).z; avant = minOf(avant, z); arriere = maxOf(arriere, z) }
        assertTrue(avant < -.2f && arriere > .2f, "pied entre $avant et $arriere")
    }
}
