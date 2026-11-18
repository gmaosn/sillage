package sillage

import sillage.math.Vec3
import kotlin.math.abs
import kotlin.test.*

class PhysiqueTest {
    private val vide = Maillage(FloatArray(0))

    /** Sol plat carré de 20 m centré sur l'origine, à la hauteur y. */
    private fun sol(physique: Physique, y: Float = 0f, x0: Float = -10f, x1: Float = 10f) {
        val g = Geometrie()
        g.quad(Vec3(x0, y, -10f), Vec3(x0, y, 10f), Vec3(x1, y, 10f), Vec3(x1, y, -10f), Couleur(1f, 1f, 1f))
        physique.surfaces.addAll(g.construire().triangles)
    }

    private fun joueur(x: Float = 0f, y: Float = 0f, z: Float = 0f) = Joueur(Objet(vide), Vec3(x, y, z))

    private fun simuler(p: Physique, j: Joueur, secondes: Float, mouvement: Vec3 = Vec3.ZERO, saut: Boolean = false,
                        chaquePas: (Joueur) -> Unit = {}) {
        var premier = true
        repeat((secondes * 60).toInt()) {
            p.avancer(j, mouvement, saut && premier, 1f / 60f)
            premier = false
            chaquePas(j)
        }
    }

    @Test fun tombeEtSePoseSurLeSol() {
        val p = Physique(); sol(p)
        val j = joueur(y = 3f)
        simuler(p, j, 2f)
        assertEquals(0f, j.position.y, 1e-4f)
        assertTrue(j.auSol)
        assertEquals(0f, j.vitesseY)
    }

    @Test fun leSautMonteDeVSurDeuxGEtRetombe() {
        val p = Physique(); sol(p)
        val j = joueur()
        simuler(p, j, .5f)
        assertTrue(j.auSol)
        var haut = 0f
        simuler(p, j, 2f, saut = true) { haut = maxOf(haut, it.position.y) }
        val theorique = j.impulsion * j.impulsion / (2 * p.gravite)
        assertTrue(abs(haut - theorique) < .05f, "hauteur du saut $haut, attendu ≈ $theorique")
        assertEquals(0f, j.position.y, 1e-4f)
        assertTrue(j.auSol)
    }

    @Test fun pasDeSautEnLAir() {
        val p = Physique(); sol(p)
        val j = joueur(y = 4f)
        simuler(p, j, .2f)
        assertFalse(j.auSol)
        val vAvant = j.vitesseY
        simuler(p, j, 1f / 60f * 2, saut = true)
        assertTrue(j.vitesseY < vAvant, "un saut en plein vol ne doit pas relancer le joueur")
    }

    @Test fun unObstacleArreteLeJoueur() {
        val p = Physique(); sol(p)
        p.obstacles.add(Boite(Vec3(2f, 0f, -1f), Vec3(3f, 2f, 1f)))
        val j = joueur()
        simuler(p, j, 2f, Vec3(1f, 0f, 0f))
        assertTrue(j.position.x <= 2f - j.rayon + 1e-3f, "le joueur traverse la boîte : x=${j.position.x}")
        assertTrue(j.position.x > 2f - j.rayon - .1f, "le joueur s'arrête trop tôt : x=${j.position.x}")
    }

    @Test fun onGlisseLeLongDUnMur() {
        val p = Physique(); sol(p)
        p.obstacles.add(Boite(Vec3(2f, 0f, -5f), Vec3(3f, 2f, 5f)))
        val j = joueur()
        val diagonale = Vec3(1f, 0f, 1f).normalized()
        simuler(p, j, 1.5f, diagonale)
        assertTrue(j.position.x <= 2f - j.rayon + 1e-3f)
        assertTrue(j.position.z > 3f, "le mouvement le long du mur est perdu : z=${j.position.z}")
    }

    @Test fun onMonteSurUneBoiteBasseEnSautant() {
        val p = Physique(); sol(p)
        p.obstacles.add(Boite(Vec3(1f, 0f, -1f), Vec3(8f, .8f, 1f)))
        val j = joueur()
        simuler(p, j, .2f)
        simuler(p, j, 1.2f, Vec3(1f, 0f, 0f), saut = true)
        assertTrue(j.position.x > 1f, "le joueur n'est pas monté : x=${j.position.x}")
        simuler(p, j, .5f)
        assertEquals(.8f, j.position.y, 1e-4f)
        assertTrue(j.auSol)
    }

    @Test fun uneMarcheTropHauteEstRefusee() {
        val p = Physique()
        sol(p, 0f, -10f, 2f)
        sol(p, 1f, 2f, 10f)
        val j = joueur()
        simuler(p, j, 2f, Vec3(1f, 0f, 0f))
        assertTrue(j.position.x < 2f, "marche de 1 m franchie sans sauter : x=${j.position.x}")
        assertEquals(0f, j.position.y, 1e-4f)
    }

    @Test fun unePetiteMarcheEstFranchie() {
        val p = Physique()
        sol(p, 0f, -10f, 2f)
        sol(p, .2f, 2f, 10f)
        val j = joueur()
        simuler(p, j, 2f, Vec3(1f, 0f, 0f))
        assertTrue(j.position.x > 4f)
        assertEquals(.2f, j.position.y, 1e-4f)
    }

    @Test fun reapparaitApresUneChuteDansLeVide() {
        val p = Physique()
        val j = joueur(y = 2f)
        var plusBas = Float.MAX_VALUE
        var revenu = false
        var precedent = j.position.y
        simuler(p, j, 3f) {
            plusBas = minOf(plusBas, it.position.y)
            if (it.position.y > precedent + 1f) revenu = true
            precedent = it.position.y
        }
        assertTrue(revenu, "le joueur n'est jamais revenu au départ")
        assertTrue(plusBas >= -6.5f, "le joueur est tombé trop bas : $plusBas")
    }

    @Test fun laSimulationNeDependPasDeLaCadence() {
        fun finale(fps: Int): Vec3 {
            val p = Physique(); sol(p)
            val j = joueur(y = 2f)
            repeat(fps * 2) { p.avancer(j, Vec3(1f, 0f, 0f), it == fps, 1f / fps) }
            return j.position
        }
        val a = finale(30); val b = finale(120); val c = finale(240)
        assertTrue(a.distanceTo(b) < 1e-3f && b.distanceTo(c) < 1e-3f, "positions $a / $b / $c")
    }

    @Test fun laCameraNeTraversePasUnMur() {
        val p = Physique(); sol(p)
        p.obstacles.add(Boite(Vec3(-1f, 0f, 3f), Vec3(1f, 3f, 4f)))
        val libre = p.distanceCamera(Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f), 7f)
        assertTrue(libre < 3f - .18f, "caméra derrière le mur : $libre")
        assertEquals(7f, p.distanceCamera(Vec3(0f, 1f, 0f), Vec3(0f, 0f, -1f), 7f))
    }

    @Test fun laCameraNePassePasSousLeRelief() {
        val p = Physique()
        sol(p, 0f, -10f, 2f)
        sol(p, 5f, 2f, 10f)
        val libre = p.distanceCamera(Vec3(0f, 1f, 0f), Vec3(1f, .2f, 0f).normalized(), 7f)
        assertTrue(libre < 2.1f, "caméra dans la colline : $libre")
    }
}
