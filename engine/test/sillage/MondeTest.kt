package sillage

import sillage.math.Vec3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MondeTest {
    private fun racine(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("project.yaml"))) dir = dir.parent
        return dir
    }

    @Test fun leMarinRamasseLesCoquillagesEnPassantEtLeJeuRepart() {
        val dir = racine()
        val m = Monde()
        m.terrain(dir.resolve("demo/resources/models/terrain.glb").toString())
        m.coquillage(2f, 4f); m.coquillage(-6f, 4f)
        m.heros(dir.resolve("demo/resources/models/marin.glb").toString(), x = 0f, y = m.sol(0f, 4f) + .05f, z = 4f)
        repeat(30) { m.miseAJour(Vec3.ZERO, false, 1f / 60f) }
        repeat(60) { m.miseAJour(Vec3(1f, 0f, 0f), false, 1f / 60f) }
        assertEquals(1, m.recoltes); assertFalse(m.coquillages[0].visible); assertTrue(m.coquillages[1].visible)
        repeat(60) { m.miseAJour(Vec3(1f, 0f, 0f), false, 1f / 60f) }
        assertEquals(1, m.recoltes, "un coquillage ramassé ne compte qu'une fois")
        m.reinitialiser(); repeat(5) { m.miseAJour(Vec3.ZERO, false, 1f / 60f) }
        assertEquals(0, m.recoltes); assertTrue(m.coquillages.all { it.visible })
    }
}
