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

    /** Signalé le 18 novembre : on tombait à travers la barque de la démo. */
    @Test fun onSeTientSurLaBarqueQuiTangue() {
        val dir = racine()
        val m = Monde()
        val port = m.modele(dir.resolve("demo/resources/models/port.glb").toString()).animer("vie")
        m.corpsSurNoeud(port, "bateau", sillage.physics.Box(.7f, .25f, .75f), Vec3(0f, -.04f, 0f))
        m.corpsSurNoeud(port, "bateau", sillage.physics.Box(.42f, .25f, 1.4f), Vec3(0f, -.04f, 0f))
        // Le héros tombe d'un mètre sur le pont (barque en (-4 ; 10,7), pont à 0,21 m au-dessus du nœud).
        m.heros(dir.resolve("demo/resources/models/marin.glb").toString(), x = -4f, y = 1.5f, z = 10.4f)
        var bas = 9f; var haut = -9f
        repeat(60 * 5) { f ->
            m.miseAJour(Vec3.ZERO, false, 1f / 60f)
            if (f > 60) { bas = minOf(bas, m.joueur.position.y); haut = maxOf(haut, m.joueur.position.y) }
        }
        assertTrue(m.joueur.auSol, "pas posé : ${m.joueur.position}")
        assertSame(port, m.joueur.personnage.groundBody?.tag, "posé sur un des corps de la barque")
        // La houle soulève le pont de ± 5 cm (période 4 s) : le marin suit, sans traverser ni décoller.
        assertTrue(bas > .16f + .21f - .07f && haut < .16f + .21f + .07f && haut - bas > .05f, "hauteurs $bas … $haut")
    }
}
