package sillage

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GlbTest {
    /** Assemble un GLB minimal : en-tête, chunk JSON aligné sur 4 octets, chunk BIN. */
    private fun glb(json: String, bin: ByteArray): ByteArray {
        val j = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val b = bin + ByteArray((4 - bin.size % 4) % 4)
        val out = ByteBuffer.allocate(12 + 8 + j.size + 8 + b.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546c67).putInt(2).putInt(out.capacity())
        out.putInt(j.size).putInt(0x4e4f534a).put(j)
        out.putInt(b.size).putInt(0x004e4942).put(b)
        return out.array()
    }

    private fun floats(vararg v: Float): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()

    private fun triangle(noeud: String, materiau: String = """{"pbrMetallicRoughness":{"baseColorFactor":[0.5,0.25,1,1]}}""",
                         extra: String = "") = glb("""{
        "asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
        "nodes":[$noeud],
        "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}],
        "materials":[$materiau],
        "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}],
        "bufferViews":[{"buffer":0,"byteLength":36}],
        "buffers":[{"byteLength":36}]$extra}""", floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, -1f))

    @Test fun triangleAvecTranslationEchelleEtCouleur() {
        val m = Glb.read(triangle("""{"mesh":0,"translation":[10,1,0],"scale":[2,2,2]}"""))
        assertEquals(27, m.vertices.size)
        val attendus = listOf(floatArrayOf(10f, 1f, 0f), floatArrayOf(12f, 1f, 0f), floatArrayOf(10f, 1f, -2f))
        for ((k, p) in attendus.withIndex()) for (c in 0..2) assertEquals(p[c], m.vertices[k * 9 + c], 1e-5f)
        assertEquals(1f, m.vertices[4], 1e-5f, "la normale doit pointer vers le haut")
        assertEquals(.5f, m.vertices[6]); assertEquals(.25f, m.vertices[7]); assertEquals(1f, m.vertices[8])
    }

    @Test fun hierarchieEtRotation() {
        // Parent tourné de 90° autour de Y (quaternion), enfant translaté de 1 sur X → enfant en z = -1.
        val s = Math.sqrt(.5).toFloat()
        val m = Glb.read(triangle("""{"rotation":[0,$s,0,$s],"children":[1]},{"mesh":0,"translation":[1,0,0]}"""))
        assertEquals(0f, m.vertices[0], 1e-5f)
        assertEquals(-1f, m.vertices[2], 1e-5f)
    }

    @Test fun echelleNegativeGardeLesNormalesVersLeHaut() {
        val m = Glb.read(triangle("""{"mesh":0,"scale":[-1,1,1]}"""))
        assertEquals(1f, m.vertices[4], 1e-5f)
    }

    @Test fun texturesRefusees() {
        val e = assertFailsWith<IllegalArgumentException> {
            Glb.read(triangle("""{"mesh":0}""", """{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}"""))
        }
        assertContains(e.message!!, "Textures")
    }

    @Test fun squelettesRefuses() {
        val e = assertFailsWith<IllegalArgumentException> { Glb.read(triangle("""{"mesh":0}""", extra = ""","skins":[{}]""")) }
        assertContains(e.message!!, "Skins")
    }

    @Test fun fichierTronqueOuFauxRefuse() {
        assertFailsWith<IllegalArgumentException> { Glb.read(ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { Glb.read(ByteArray(40)) }
    }

    @Test fun modelesDeLaDemo() {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("project.yaml"))) dir = requireNotNull(dir.parent) { "racine du projet introuvable" }
        for (nom in listOf("terrain", "village", "marin", "palmiers", "ciel", "port")) {
            val m = Glb.load(dir.resolve("demo/resources/models/$nom.glb").toString())
            assertTrue(m.triangleCount > 10, "$nom : ${m.triangleCount} triangles")
            assertTrue(m.vertices.all { it.isFinite() }, "$nom : valeur non finie")
        }
    }
}
