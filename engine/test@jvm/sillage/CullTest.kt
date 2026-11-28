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

    /**
     * Les cellules des nœuds immobiles ne changent rien à ce que l'image dessine : mêmes lots, dans le même ordre,
     * mêmes lignes au bit près, mêmes comptes, que la liste parcourue nœud par nœud ; pendant que des nœuds
     * bougent, disparaissent, changent de maillage, sont retirés ou ajoutés, caméra et brume changeant.
     */
    @Test fun lesCellulesDonnentLesMemesLots() {
        val rnd = kotlin.random.Random(7)
        val s = Scene()
        s.ground(size = 600)
        val meshes = listOf(Mesh.box(1f, 6f, 1f, Color.STONE), Mesh.box(1.2f, 1.2f, 1.2f, Color.WOOD), Mesh.sphere(.8f, Color.RED), Mesh.box(3f, .5f, 2f, Color.GRASS))
        val far = Mesh.box(1f, 6f, 1f, Color.GREY)
        val all = ArrayList<Node>()
        fun make(): Node {
            val n = s.mesh(meshes[rnd.nextInt(meshes.size)], at = vec(rnd.nextFloat() * 400 - 200, rnd.nextFloat() * 4, rnd.nextFloat() * 400 - 200))
            if (rnd.nextInt(3) == 0) n.rotate(y = rnd.nextFloat() * 360, x = rnd.nextFloat() * 30)
            if (rnd.nextInt(5) == 0) n.scale = .5f + rnd.nextFloat() * 2
            if (rnd.nextInt(20) == 0) n.lod(far, from = 30 + rnd.nextInt(60))
            if (rnd.nextInt(15) == 0) n.drawDistance = 20f + rnd.nextFloat() * 80
            if (rnd.nextInt(25) == 0) n.castsShadow = false
            all.add(n)
            return n
        }
        repeat(3000) { make() }
        val withCells = RenderBatches {}; val plain = RenderBatches {}
        var framesWithCells = 0
        for (frame in 0 until 240) {
            // The game changes a few things.
            repeat(4) { val n = all[rnd.nextInt(all.size)]; n.position = n.position + vec(rnd.nextFloat() - .5f, 0, rnd.nextFloat() - .5f) }
            if (frame % 7 == 0) { val n = all[rnd.nextInt(all.size)]; n.visible = !n.visible }
            if (frame % 11 == 0) all[rnd.nextInt(all.size)].mesh = meshes[rnd.nextInt(meshes.size)]
            if (frame % 13 == 0) all[rnd.nextInt(all.size)].rotate(y = 20)
            if (frame % 17 == 0) all[rnd.nextInt(all.size)].scale *= 1.1f
            if (frame % 19 == 0) all.removeAt(rnd.nextInt(all.size)).remove()
            if (frame % 10 == 0) make()
            s.lighting.fogEnd = if ((frame / 40) % 2 == 0) 90f else 0f
            val a = frame * .03f
            val eye = vec(kotlin.math.cos(a) * 120, 3 + (frame % 50) * .4f, kotlin.math.sin(a) * 120)
            val look = vec(kotlin.math.cos(a * 3) * 150, 0, kotlin.math.sin(a * 2) * 150)
            val vp = sillage.math.Mat4.perspective(1.1f, 1.6f, .1f, 500f) * sillage.math.Mat4.lookAt(eye, look)
            val hidden = if (frame % 3 == 0) all[rnd.nextInt(all.size)] else null
            val list = s.drawList(eye)
            val main = s.lighting.mainLight()
            for (b in listOf(withCells, plain)) {
                b.schedule(true, s.lighting, look, main, SHADOW_TEXELS)
                b.gather(if (b === withCells) list else ArrayList(list), hidden, vp, eye, s.lighting.fogEnd, main)
            }
            if (s.stillCells.cells.isNotEmpty()) framesWithCells++
            // The cells know their nodes by their place in the list; the others are visited in the list's order.
            for (c in s.stillCells.cells) for (j in 0 until c.count) {
                val n = list[c.ranks[j]]
                assertTrue(n.cell === c && n.cellSlot == j && n.rank == c.ranks[j], "image $frame : cellule, place $j")
            }
            val moving = s.stillCells.moving
            for (k in moving.indices) {
                assertTrue(moving[k].cell == null && list[moving[k].rank] === moving[k], "image $frame : hors cellule $k")
                if (k > 0) assertTrue(moving[k - 1].rank < moving[k].rank, "image $frame : ordre hors cellule")
            }
            assertEquals(list.size, moving.size + s.stillCells.still, "image $frame : chaque nœud une fois")
            assertEquals(plain.culled, withCells.culled, "image $frame : culled")
            assertEquals(plain.drawn, withCells.drawn, "image $frame : drawn")
            fun same(what: String, x: List<Batch>, y: List<Batch>) {
                assertEquals(x.size, y.size, "image $frame : $what, nombre de lots")
                for (k in x.indices) {
                    assertSame(x[k].mesh, y[k].mesh, "image $frame : $what, lot $k")
                    assertEquals(x[k].count, y[k].count, "image $frame : $what, lot $k")
                    for (i in 0 until 12 * x[k].count) assertEquals(x[k].rows[i].toRawBits(), y[k].rows[i].toRawBits(), "image $frame : $what, lot $k, ligne $i")
                }
            }
            same("opaques", plain.opaque, withCells.opaque); same("discrets", plain.quiet, withCells.quiet)
            same("ciel", plain.skies, withCells.skies); same("taches", plain.shadows, withCells.shadows); same("eaux", plain.waters, withCells.waters)
            for (c in 0 until CASCADES) same("ombres $c", plain.casters[c], withCells.casters[c])
        }
        assertTrue(framesWithCells > 150, "les cellules ont servi ($framesWithCells images)")
        assertTrue(s.stillCells.still > 2500, "nœuds dans les cellules : ${s.stillCells.still}")
    }

    @Test fun laSphèreDUnMaillageLeContient() {
        val m = Mesh.box(2f, 4f, 6f, Color.WOOD)
        val (cx, cy, cz, r) = m.sphere.toList()
        assertEquals(0f, cx, 1e-5f); assertEquals(0f, cy, 1e-5f); assertEquals(0f, cz, 1e-5f)
        assertEquals(kotlin.math.sqrt(1f + 4f + 9f), r, 1e-4f)
    }
}
