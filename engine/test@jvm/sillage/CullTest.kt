package sillage

import sillage.math.Vec3
import kotlin.test.*

/** Levels of detail and draw distance, as the renderer asks for them. */
class CullTest {
    @Test fun leMaillageChangeAvecLaDistance() {
        val s = Scene()
        val near = Mesh.box(1f, 1f, 1f, Color.WOOD); val mid = Mesh.box(1f, 1f, 1f, Color.STONE); val far = Mesh.box(1f, 1f, 1f, Color.GRASS)
        val n = s.mesh(near, at = vec(0, 0, 0)).lod(far, from = 60).lod(mid, from = 20)
        assertSame(near, n.meshAt(Vec3(0f, 0f, 10f)))
        assertSame(mid, n.meshAt(Vec3(0f, 0f, 20f)))
        assertSame(mid, n.meshAt(Vec3(0f, 30f, 30f)))
        assertSame(far, n.meshAt(Vec3(0f, 0f, 61f)))
        assertEquals(Float.POSITIVE_INFINITY, n.drawDistance)
    }

    @Test fun unModèleAniméNeChangePasDeMaillage() {
        val s = Scene()
        val path = java.nio.file.Path.of("").toAbsolutePath().let { var d = it; while (!java.nio.file.Files.exists(d.resolve("project.yaml"))) d = d.parent; d }
            .resolve("demo/resources/models/marin.glb").toString()
        val sailor = s.model(path)
        assertFailsWith<IllegalArgumentException> { sailor.lod(Mesh.box(1f, 1f, 1f, Color.WOOD), 10) }
    }

    @Test fun laSphèreDUnMaillageLeContient() {
        val m = Mesh.box(2f, 4f, 6f, Color.WOOD)
        val (cx, cy, cz, r) = m.sphere.toList()
        assertEquals(0f, cx, 1e-5f); assertEquals(0f, cy, 1e-5f); assertEquals(0f, cz, 1e-5f)
        assertEquals(kotlin.math.sqrt(1f + 4f + 9f), r, 1e-4f)
    }
}
