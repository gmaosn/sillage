package sillage

import sillage.math.Vec3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
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

    /** La maison de la démo : toit solide, volume jusqu'au bord du toit, cheminée, tonneaux. */
    private fun maison(): Monde {
        val dir = racine()
        val m = Monde()
        m.terrain(dir.resolve("demo/resources/models/terrain.glb").toString())
        m.solide(m.modele(dir.resolve("demo/resources/models/village.glb").toString()), "toit")
        m.obstacle(-3f, 1.45f, -2f, 3.4f, 1.65f, 3.0f)
        m.obstacle(-2f, 3.2f, -2.6f, .5f, 1.5f, .5f)
        for ((x, z) in listOf(-1.25f to -1.5f, -1.2f to -2.15f)) m.obstacleAuSol(x, z, .55f, .75f, .55f, camera = false)
        return m
    }

    /** Hauteur du toit (pignons en z = −3,5 et −0,5, faîtage à 4,2 m en x = −3, bords à 3,1 m en x = −4,7 et −1,3). */
    private fun dessusMaison(x: Float, z: Float): Float? =
        if (x in -4.7f..-1.3f && z in -3.5f..-.5f) 4.2f - abs(x + 3f) / 1.7f * 1.1f else null

    /** Pire enfoncement d'un point de la capsule du marin dans la maison et son toit (0 si aucun). */
    private fun enfoncement(m: Monde): Float {
        val p = m.joueur.position; val r = m.joueur.rayon * .95f
        var pire = 0f
        var h = .05f
        while (h <= 1.75f) {
            for (k in 0 until 8) {
                val a = k * PI.toFloat() / 4
                val rayon = if (h < .28f || h > 1.52f) sqrt(max(0f, r * r - (if (h < .28f) .28f - h else h - 1.52f).pow(2))) else r
                val x = p.x + cos(a) * rayon; val z = p.z + sin(a) * rayon; val y = p.y + h
                val haut = dessusMaison(x, z) ?: continue
                if (y in 1.45f..haut) {
                    val d = minOf(haut - y, x + 4.7f, -1.3f - x, z + 3.5f, -.5f - z)
                    pire = max(pire, d)
                }
            }
            h += .05f
        }
        return pire
    }

    /** Signalé le 18 novembre (capture) : debout sur un tonneau, le marin passait la tête à travers l'avant-toit. */
    @Test fun leMarinNeTraverseJamaisLeToit() {
        val m = maison()
        val essais = listOf(
            Vec3(-1.05f, 0f, -1.5f) to Vec3(-1f, 0f, 0f),        // du tonneau vers la maison
            Vec3(-1.05f, 0f, -1.5f) to Vec3(-1f, 0f, -.6f).normalized(),
            Vec3(-1f, 0f, -1f) to Vec3(-.3f, 0f, -1f).normalized(),   // le long du mur, sous l'avant-toit
            Vec3(-3f, 0f, .2f) to Vec3(.2f, 0f, -1f).normalized())    // vers la porte
        var pire = 0f; var images = 0
        for ((depart, dir) in essais) for (saut in listOf(-1, 12, 40)) {
            m.heros(racine().resolve("demo/resources/models/marin.glb").toString(), depart.x, m.sol(depart.x, depart.z) + 1.2f, depart.z)
            repeat(40) { m.miseAJour(Vec3.ZERO, false, 1f / 60f) }
            repeat(150) { f -> m.miseAJour(dir, f == saut, 1f / 60f); pire = max(pire, enfoncement(m)); images++ }
        }
        assertTrue(images > 1500)
        assertTrue(pire < .03f, "le marin s'enfonce de ${"%.2f".format(pire)} m dans la maison ou son toit")
    }

    @Test fun depuisLeTonneauOnSauteSurLeToit() {
        val m = maison()
        m.heros(racine().resolve("demo/resources/models/marin.glb").toString(), -1.05f, m.sol(-1.25f, -1.5f) + 1.5f, -1.5f)
        repeat(60) { m.miseAJour(Vec3.ZERO, false, 1f / 60f) }
        val tonneau = m.sol(-1.25f, -1.5f) + .75f
        assertEquals(tonneau, m.joueur.position.y, .03f, "debout sur le tonneau")
        // Un saut vers la maison, puis on s'arrête sur la pente (à 4,4 m/s on franchirait le faîtage).
        repeat(90) { f -> m.miseAJour(if (m.joueur.position.x > -2.2f) Vec3(-1f, 0f, 0f) else Vec3.ZERO, f == 3, 1f / 60f) }
        val p = m.joueur.position
        assertTrue(m.joueur.auSol && p.y > 3.1f, "pas sur le toit : $p")
        assertEquals(dessusMaison(p.x, p.z)!!, p.y, .06f, "posé sur la pente du toit : $p")
    }
}
