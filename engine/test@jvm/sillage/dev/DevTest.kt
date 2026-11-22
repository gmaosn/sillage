package sillage.dev

import sillage.*
import sillage.io.Assets
import java.nio.file.Files
import kotlin.test.*

/** The pieces of `./sillage dev` that work without a window: finding files, carrying state over, showing errors. */
class DevTest {
    @AfterTest fun clean() { Assets.roots = emptyList(); DevLink.error = null }

    @Test fun lesRessourcesSontCherchéesDansLeDossierDuJeuAvantLeClasspath() {
        val dir = Files.createTempDirectory("sillage-assets")
        try {
            Files.createDirectories(dir.resolve("models"))
            Files.write(dir.resolve("models/a.bin"), byteArrayOf(1, 2, 3))
            assertNull(Assets.bytesOrNull("models/a.bin"))
            Assets.roots = listOf(dir.toString())
            assertContentEquals(byteArrayOf(1, 2, 3), Assets.bytes("models/a.bin", "Test"))
            // A changed file is read again: no cache between the disk and the scene.
            Files.write(dir.resolve("models/a.bin"), byteArrayOf(4))
            assertContentEquals(byteArrayOf(4), Assets.bytes("models/a.bin", "Test"))
            // The engine's own resources still come from the classpath.
            assertNotNull(Assets.bytesOrNull("shaders/toon.vert.spv"))
            val e = assertFailsWith<IllegalStateException> { Assets.bytes("models/missing.glb", "GLB") }
            assertTrue("models/missing.glb" in e.message!! && dir.toString() in e.message!!, e.message)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun world(spawn: sillage.math.Vec3, hour: Float = 16f): Scene.() -> Unit = {
        ground()
        lighting.time = hour
        lighting.dayLength = 600f
        player(at = spawn)
    }

    @Test fun unRechargementGardeLeJoueurLaCaméraEtLHeure() {
        val old = Scene().apply(world(vec(0, 0, 2)))
        val oldInitial = DevLink.Initial(old)
        repeat(30) { old.update(1f / 60f) }
        old.player!!.position = vec(4, 0, -1)
        old.camera.yaw = 40f
        old.camera.distance = 9f
        old.lighting.time = 17.25f
        val fresh = Scene().apply(world(vec(0, 0, 2)))
        DevLink.carryOver(old, oldInitial, fresh, DevLink.Initial(fresh))
        assertEquals(4f, fresh.player!!.position.x, 1e-4f)
        assertEquals(-1f, fresh.player!!.position.z, 1e-4f)
        assertEquals(40f, fresh.camera.yaw, 1e-4f)
        assertEquals(9f, fresh.camera.distance, 1e-4f)
        assertEquals(17.25f, fresh.lighting.time!!, 1e-4f)
    }

    @Test fun ceQueLeCodeAChangéLEmporte() {
        val old = Scene().apply(world(vec(0, 0, 2)))
        val oldInitial = DevLink.Initial(old)
        old.player!!.position = vec(4, 0, -1)
        old.lighting.time = 17.25f
        // The new code moved the spawn point and the hour: the player starts there, at that hour.
        val fresh = Scene().apply(world(vec(-3, 0, 5), hour = 9f))
        DevLink.carryOver(old, oldInitial, fresh, DevLink.Initial(fresh))
        assertEquals(-3f, fresh.player!!.position.x, 1e-4f)
        assertEquals(5f, fresh.player!!.position.z, 1e-4f)
        assertEquals(9f, fresh.lighting.time!!, 1e-4f)
    }

    @Test fun lErreurSAfficheEtSEfface() {
        val s = Scene()
        val overlay = DevOverlay()
        val empty = s.hud.mesh(1.6f, 60, true).vertices.size
        DevLink.error = "COMPILATION FAILED\nMain.kt:10:44: error: unresolved reference 'nimporte'."
        overlay.show(s.hud)
        val shown = s.hud.mesh(1.6f, 60, true).vertices.size
        assertTrue(shown > empty + 1000, "panel and two lines drawn ($empty → $shown floats)")
        overlay.show(s.hud)                                  // same error: nothing added
        assertEquals(shown, s.hud.mesh(1.6f, 60, true).vertices.size)
        DevLink.error = null
        overlay.show(s.hud)
        assertEquals(empty, s.hud.mesh(1.6f, 60, true).vertices.size)
    }

    @Test fun uneExceptionEstDécriteParSaLigneDansLeJeu() {
        val e = IllegalStateException("panne voulue")
        e.stackTrace = arrayOf(
            StackTraceElement("sillage.Scene", "update", "Scene.kt", 500),
            StackTraceElement("monjeu.MainKt", "world\$lambda", "Main.kt", 12))
        assertEquals("IllegalStateException: panne voulue (Main.kt:12)", DevHost.describe(e))
    }
}
