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

    /** Un triangle texturé : image PNG dans le fichier, coordonnées de texture, découpe alpha. */
    private fun texturedTriangle(uvs: Boolean = true): ByteArray {
        val img = java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 2) for (x in 0 until 3) img.setRGB(x, y, (if (x == 0) 0x40 else 0xFF) shl 24 or (x * 100 shl 16) or (y * 200 shl 8) or 50)
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        val positions = floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f); val coords = floats(0f, 0f, 1f, 0f, 0f, 1f)
        val attributes = if (uvs) """{"POSITION":0,"TEXCOORD_0":1}""" else """{"POSITION":0}"""
        return glb("""{
            "asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
            "meshes":[{"primitives":[{"attributes":$attributes,"material":0}]}],
            "materials":[{"name":"enseigne","pbrMetallicRoughness":{"baseColorTexture":{"index":0},"baseColorFactor":[1,0.5,1,1]},"alphaMode":"MASK","alphaCutoff":0.3}],
            "textures":[{"source":0}],"images":[{"bufferView":2,"mimeType":"image/png"}],
            "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"}],
            "bufferViews":[{"buffer":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":24},{"buffer":0,"byteOffset":60,"byteLength":${png.size}}],
            "buffers":[{"byteLength":${60 + png.size}}]}""", positions + coords + png)
    }

    @Test fun texturesDImageLues() {
        val model = Glb.readModel(texturedTriangle())
        val tex = assertNotNull(model.textures[0])
        assertEquals(Texture.Mapping.UV, tex.mapping); assertEquals(4, tex.size, "3 × 2 pixels portés au carré de 4")
        val mesh = model.flatten(); val e = assertNotNull(mesh.effects)
        for (k in 0..2) {
            val o = k * EFFECT_FLOATS
            assertEquals(tex.id.toFloat(), e[o + 4]); assertEquals(2f, e[o + 7], "pose par coordonnées"); assertEquals(.3f, e[o + 10], 1e-6f, "découpe")
        }
        // Le premier sommet a (u, v) = (0, 0), le deuxième (1, 0), le troisième (0, 1) — à l'ordre du triangle près.
        val uv = (0..2).map { e[it * EFFECT_FLOATS + 8] to e[it * EFFECT_FLOATS + 9] }.toSet()
        assertEquals(setOf(0f to 0f, 1f to 0f, 0f to 1f), uv)
        assertEquals(.5f, mesh.vertices[7], 1e-6f, "le facteur de couleur reste la couleur du sommet")
        // Les pixels : le coin gauche à demi transparent, couleurs gardées telles qu'écrites (sRGB).
        val px = tex.pixels
        assertEquals(0x40, px[3].toInt() and 255); assertEquals(50, px[2].toInt() and 255)
    }

    @Test fun textureSansCoordonneesRefusee() {
        val e = assertFailsWith<IllegalArgumentException> { Glb.readModel(texturedTriangle(uvs = false)) }
        assertContains(e.message!!, "TEXCOORD_0")
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
