package sillage

import sillage.math.Vec3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

class CameraTest {
    private fun racine(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("project.yaml"))) dir = dir.parent
        return dir
    }
    private val haut = Vec3(0f, 1f, 0f)

    @Test fun unObjetFinEntreLeHerosEtLaCameraNeLaRapprochePas() {
        val p = Physique()
        p.obstacles.add(Boite(Vec3(-.2f, 0f, 2f), Vec3(.2f, 3f, 2.4f), camera = false))
        assertEquals(7f, p.distanceCamera(haut, Vec3(0f, 0f, 1f), 7f))
    }

    @Test fun laCameraReculeDerriereUnObjetFinPlutotQueDeTomberDedans() {
        val p = Physique()
        p.obstacles.add(Boite(Vec3(-.2f, 0f, 6.8f), Vec3(.2f, 3f, 7.2f), camera = false))
        val libre = p.distanceCamera(haut, Vec3(0f, 0f, 1f), 7f)
        assertTrue(libre > 7.2f + .18f && libre <= 8f, "caméra à $libre")
    }

    @Test fun unBatimentRapprocheToujoursLaCamera() {
        val p = Physique()
        p.obstacles.add(Boite(Vec3(-2f, 0f, 3f), Vec3(2f, 4f, 4f)))
        assertTrue(p.distanceCamera(haut, Vec3(0f, 0f, 1f), 7f) < 3f)
    }

    /**
     * Le défaut signalé le 18 novembre : en glissant contre un palmier ou un coin de bâtiment puis en
     * continuant d'avancer, l'obstacle passait entre le héros et la caméra, qui se collait à sa tête.
     * Ici, sur l'île de la démo, la caméra ne doit jamais passer sous 2 m (réglée à 7 m).
     */
    @Test fun glisserContreLesObstaclesDeLaDemoNeColleJamaisLaCamera() {
        val dir = racine()
        val m = Monde()
        m.terrain(dir.resolve("demo/resources/models/terrain.glb").toString())
        m.obstacle(-3f, 1.45f, -2f, 2.7f, 2.8f, 2.4f)
        m.obstacle(4f, 2.7f, -3f, 1.4f, 4.5f, 1.4f)
        for ((x, z) in listOf(-5f to -1f, -6f to 3f, 6f to 1f, 1f to -5f, -3f to -4f)) m.obstacle(x, 1.45f, z, .4f, 3f, .4f, camera = false)
        for ((x, z) in listOf(-1.25f to -1.5f, -1.2f to -2.15f)) m.obstacleAuSol(x, z, .55f, .75f, .55f, camera = false)
        m.heros(dir.resolve("demo/resources/models/marin.glb").toString(), 0f, 3f, 4f)
        val boites = m.physique.obstacles.toList()
        var images = 0; var pire = 99f
        for (b in boites) {
            val c = Vec3((b.min.x + b.max.x) / 2, 0f, (b.min.z + b.max.z) / 2)
            for (i in 0 until 8) for (cote in listOf(-.35f, .35f)) {
                m.camera.azimut = i * PI.toFloat() / 4; m.camera.distance = 7f; m.camera.elevation = .38f
                val avant = m.camera.mouvement(0f, 1f)
                val depart = c - avant * 3.5f + Vec3(-avant.z, 0f, avant.x) * cote
                if (boites.any { depart.x > it.min.x - .4f && depart.x < it.max.x + .4f && depart.z > it.min.z - .4f && depart.z < it.max.z + .4f }) continue
                m.joueur.position = Vec3(depart.x, m.sol(depart.x, depart.z) + .05f, depart.z); m.joueur.vitesseY = 0f
                m.camera.position = m.joueur.position.plus(0f, 1.1f, 0f) + Vec3(sin(m.camera.azimut), .37f, cos(m.camera.azimut)) * 6.5f
                repeat(180) { f ->
                    m.miseAJour(m.camera.mouvement(0f, 1f), false, 1f / 60f)
                    m.camera.vue(m, 1f / 60f)
                    if (f > 30 && m.joueur.position.y > .9f) {
                        images++; pire = minOf(pire, m.camera.position.distanceTo(m.joueur.position.plus(0f, 1.1f, 0f)))
                    }
                }
            }
        }
        assertTrue(images > 10_000, "$images images mesurées")
        assertTrue(pire >= 2f, "caméra à ${"%.2f".format(pire)} m du héros")
    }

    @Test fun leReliefLeveLaCameraAuLieuDeLaRapprocher() {
        val dir = racine()
        val m = Monde()
        val g = Geometrie()
        // Sol plat en z < 2, puis une pente raide (45°) qui monte derrière le héros.
        g.quad(Vec3(-10f, 0f, -10f), Vec3(-10f, 0f, 2f), Vec3(10f, 0f, 2f), Vec3(10f, 0f, -10f), Couleur(1f, 1f, 1f))
        g.quad(Vec3(-10f, 0f, 2f), Vec3(-10f, 8f, 10f), Vec3(10f, 8f, 10f), Vec3(10f, 0f, 2f), Couleur(1f, 1f, 1f))
        m.physique.surfaces.addAll(g.construire().triangles)
        m.heros(dir.resolve("demo/resources/models/marin.glb").toString(), 0f, .1f, 0f)
        repeat(120) { m.miseAJour(Vec3.ZERO, false, 1f / 60f); m.camera.vue(m, 1f / 60f) }
        val d = m.camera.position.distanceTo(m.joueur.position.plus(0f, 1.1f, 0f))
        assertTrue(d > 6f, "caméra rapprochée à $d m au lieu de monter")
        assertTrue(m.camera.position.y > 4f, "caméra trop basse : ${m.camera.position.y}")
    }
}
