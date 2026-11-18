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

    private fun monde(): sillage.physics.PhysicsWorld = sillage.physics.PhysicsWorld()
    private fun boite(w: sillage.physics.PhysicsWorld, min: Vec3, max: Vec3, mince: Boolean = false) {
        val b = w.add(sillage.physics.Box((max.x - min.x) / 2, (max.y - min.y) / 2, (max.z - min.z) / 2), (min + max) / 2f,
            type = sillage.physics.BodyType.STATIC)
        if (mince) b.tag = Mince
    }

    @Test fun unObjetFinEntreLeHerosEtLaCameraNeLaRapprochePas() {
        val w = monde(); boite(w, Vec3(-.2f, 0f, 2f), Vec3(.2f, 3f, 2.4f), mince = true)
        assertEquals(7f, w.distanceCamera(haut, Vec3(0f, 0f, 1f), 7f))
    }

    @Test fun laCameraReculeDerriereUnObjetFinPlutotQueDeTomberDedans() {
        val w = monde(); boite(w, Vec3(-.2f, 0f, 6.8f), Vec3(.2f, 3f, 7.2f), mince = true)
        val libre = w.distanceCamera(haut, Vec3(0f, 0f, 1f), 7f)
        assertTrue(libre > 7.2f + .18f && libre <= 8f, "caméra à $libre")
    }

    @Test fun unBatimentRapprocheToujoursLaCamera() {
        val w = monde(); boite(w, Vec3(-2f, 0f, 3f), Vec3(2f, 4f, 4f))
        assertTrue(w.distanceCamera(haut, Vec3(0f, 0f, 1f), 7f) < 3f)
    }

    @Test fun leReliefArreteLaCamera() {
        val w = monde()
        w.addMesh(sillage.physics.TriangleMesh(floatArrayOf(2f, 0f, -10f, 2f, 0f, 10f, 10f, 8f, 10f, 2f, 0f, -10f, 10f, 8f, 10f, 10f, 8f, -10f)))
        // Pente y = x − 2 ; le rayon y = 1 + 0,2 x la touche en x = 3,75, à 3,82 m : la caméra s'arrête 30 cm avant.
        val libre = w.distanceCamera(haut, Vec3(1f, .2f, 0f).normalized(), 7f)
        assertEquals(3.82f - .3f, libre, .03f, "caméra dans la colline : $libre")
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
        m.obstacle(-3f, 1.45f, -2f, 3.4f, 1.65f, 3.0f)
        m.obstacle(-2f, 3.2f, -2.6f, .5f, 1.5f, .5f)
        m.obstacle(4f, 2.7f, -3f, 1.4f, 4.5f, 1.4f)
        for ((x, z) in listOf(-5f to -1f, -6f to 3f, 6f to 1f, 1f to -5f, -3f to -4f)) m.obstacle(x, 1.45f, z, .4f, 3f, .4f, camera = false)
        for ((x, z) in listOf(-1.25f to -1.5f, -1.2f to -2.15f)) m.obstacleAuSol(x, z, .55f, .75f, .55f, camera = false)
        m.heros(dir.resolve("demo/resources/models/marin.glb").toString(), 0f, 3f, 4f)
        // Boîtes statiques de la démo, alignées sur les axes : (min, max).
        val boites = (0 until m.corps.bodyCount).map { m.corps.body(it) }.filter { it.type == sillage.physics.BodyType.STATIC }.map {
            val b = it.shape as sillage.physics.Box
            Pair(it.position - Vec3(b.halfX, b.halfY, b.halfZ), it.position + Vec3(b.halfX, b.halfY, b.halfZ))
        }
        var images = 0; var pire = 99f
        for ((bmin, bmax) in boites) {
            val c = Vec3((bmin.x + bmax.x) / 2, 0f, (bmin.z + bmax.z) / 2)
            for (i in 0 until 8) for (cote in listOf(-.35f, .35f)) {
                m.camera.azimut = i * PI.toFloat() / 4; m.camera.distance = 7f; m.camera.elevation = .38f
                val avant = m.camera.mouvement(0f, 1f)
                val depart = c - avant * 3.5f + Vec3(-avant.z, 0f, avant.x) * cote
                if (boites.any { (lo, hi) -> depart.x > lo.x - .4f && depart.x < hi.x + .4f && depart.z > lo.z - .4f && depart.z < hi.z + .4f }) continue
                m.joueur.position = Vec3(depart.x, m.sol(depart.x, depart.z) + .05f, depart.z)
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
        val v = g.construire().sommets
        m.corps.addMesh(sillage.physics.TriangleMesh(FloatArray(v.size / 3) { k -> v[(k / 3) * 9 + k % 3] }))
        m.heros(dir.resolve("demo/resources/models/marin.glb").toString(), 0f, .1f, 0f)
        repeat(120) { m.miseAJour(Vec3.ZERO, false, 1f / 60f); m.camera.vue(m, 1f / 60f) }
        val d = m.camera.position.distanceTo(m.joueur.position.plus(0f, 1.1f, 0f))
        assertTrue(d > 6f, "caméra rapprochée à $d m au lieu de monter")
        assertTrue(m.camera.position.y > 4f, "caméra trop basse : ${m.camera.position.y}")
    }
}
