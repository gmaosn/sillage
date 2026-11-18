package sillage

import sillage.math.Vec3
import sillage.physics.Box
import sillage.physics.BodyType
import sillage.physics.TriangleMesh
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

class CameraTest {
    private fun root(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("project.yaml"))) dir = dir.parent
        return dir
    }
    private val up = Vec3(0f, 1f, 0f)
    private fun box(s: Scene, min: Vec3, max: Vec3, thin: Boolean = false) =
        s.collider(Box((max.x - min.x) / 2, (max.y - min.y) / 2, (max.z - min.z) / 2), (min + max) / 2f, blocksCamera = !thin)

    @Test fun unObjetFinEntreLeHerosEtLaCameraNeLaRapprochePas() {
        val s = Scene(); box(s, Vec3(-.2f, 0f, 2f), Vec3(.2f, 3f, 2.4f), thin = true)
        assertEquals(7f, s.camera.cameraDistance(up, Vec3(0f, 0f, 1f), 7f))
    }

    @Test fun laCameraReculeDerriereUnObjetFinPlutotQueDeTomberDedans() {
        val s = Scene(); box(s, Vec3(-.2f, 0f, 6.8f), Vec3(.2f, 3f, 7.2f), thin = true)
        val free = s.camera.cameraDistance(up, Vec3(0f, 0f, 1f), 7f)
        assertTrue(free > 7.2f + .18f && free <= 8f, "caméra à $free")
    }

    @Test fun unBatimentRapprocheToujoursLaCamera() {
        val s = Scene(); box(s, Vec3(-2f, 0f, 3f), Vec3(2f, 4f, 4f))
        assertTrue(s.camera.cameraDistance(up, Vec3(0f, 0f, 1f), 7f) < 3f)
    }

    @Test fun leReliefArreteLaCamera() {
        val s = Scene()
        s.physics.addMesh(TriangleMesh(floatArrayOf(2f, 0f, -10f, 2f, 0f, 10f, 10f, 8f, 10f, 2f, 0f, -10f, 10f, 8f, 10f, 10f, 8f, -10f)))
        // Pente y = x − 2 ; le rayon y = 1 + 0,2 x la touche en x = 3,75, à 3,82 m : la caméra s'arrête 30 cm avant.
        val free = s.camera.cameraDistance(up, Vec3(1f, .2f, 0f).normalized(), 7f)
        assertEquals(3.82f - .3f, free, .03f, "caméra dans la colline : $free")
    }

    /**
     * Le défaut signalé le 18 novembre : en glissant contre un palmier ou un coin de bâtiment puis en
     * continuant d'avancer, l'obstacle passait entre le héros et la caméra, qui se collait à sa tête.
     * Ici, sur l'île de la démo, la caméra ne doit jamais passer sous 2 m (réglée à 7 m).
     */
    @Test fun glisserContreLesObstaclesDeLaDemoNeColleJamaisLaCamera() {
        val dir = root()
        val s = Scene()
        s.model(dir.resolve("demo/resources/models/terrain.glb").toString()).solid()
        fun obstacle(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, thin: Boolean = false) =
            s.collider(Box(w / 2, h / 2, d / 2), Vec3(x, y + h / 2, z), blocksCamera = !thin)
        obstacle(-3f, 1.45f, -2f, 3.4f, 1.65f, 3.0f)
        obstacle(-2f, 3.2f, -2.6f, .5f, 1.5f, .5f)
        obstacle(4f, 2.7f, -3f, 1.4f, 4.5f, 1.4f)
        for ((x, z) in listOf(-5f to -1f, -6f to 3f, 6f to 1f, 1f to -5f, -3f to -4f)) obstacle(x, 1.45f, z, .4f, 3f, .4f, thin = true)
        for ((x, z) in listOf(-1.25f to -1.5f, -1.2f to -2.15f)) obstacle(x, s.groundHeight(x, z), z, .55f, .75f, .55f, thin = true)
        val p = s.player(dir.resolve("demo/resources/models/marin.glb").toString(), at = Vec3(0f, 3f, 4f))
        // Boîtes statiques, alignées sur les axes : (min, max).
        val boxes = (0 until s.physics.bodyCount).map { s.physics.body(it) }.filter { it.type == BodyType.STATIC }.map {
            val b = it.shape as Box
            Pair(it.position - Vec3(b.halfX, b.halfY, b.halfZ), it.position + Vec3(b.halfX, b.halfY, b.halfZ))
        }
        var frames = 0; var worst = 99f
        for ((bmin, bmax) in boxes) {
            val c = Vec3((bmin.x + bmax.x) / 2, 0f, (bmin.z + bmax.z) / 2)
            for (i in 0 until 8) for (side in listOf(-.35f, .35f)) {
                s.camera.yaw = i * 45f; s.camera.distance = 7f; s.camera.elevation = .38f * 180f / PI.toFloat()
                val ahead = s.camera.moveDirection(0f, 1f)
                val start = c - ahead * 3.5f + Vec3(-ahead.z, 0f, ahead.x) * side
                if (boxes.any { (lo, hi) -> start.x > lo.x - .4f && start.x < hi.x + .4f && start.z > lo.z - .4f && start.z < hi.z + .4f }) continue
                p.position = Vec3(start.x, s.groundHeight(start.x, start.z) + .05f, start.z)
                repeat(180) { f ->
                    p.controls = false; p.move(s.camera.moveDirection(0f, 1f))
                    s.update(1f / 60f); s.camera.view(1f / 60f)
                    if (f > 30 && p.position.y > .9f) { frames++; worst = min(worst, s.camera.position.distanceTo(p.position.plus(0f, 1.1f, 0f))) }
                }
            }
        }
        assertTrue(frames > 10_000, "$frames images mesurées")
        assertTrue(worst >= 2f, "caméra à ${"%.2f".format(worst)} m du héros")
    }

    @Test fun leReliefLeveLaCameraAuLieuDeLaRapprocher() {
        val s = Scene()
        // Sol plat en z < 2, puis une pente raide (45°) qui monte derrière le héros.
        val g = MeshBuilder()
        g.quad(Vec3(-10f, 0f, -10f), Vec3(-10f, 0f, 2f), Vec3(10f, 0f, 2f), Vec3(10f, 0f, -10f), Color(1f, 1f, 1f))
        g.quad(Vec3(-10f, 0f, 2f), Vec3(-10f, 8f, 10f), Vec3(10f, 8f, 10f), Vec3(10f, 0f, 2f), Color(1f, 1f, 1f))
        s.mesh(g.build()).solid()
        val p = s.player(root().resolve("demo/resources/models/marin.glb").toString(), at = Vec3(0f, .1f, 0f))
        repeat(120) { s.update(1f / 60f); s.camera.view(1f / 60f) }
        val d = s.camera.position.distanceTo(p.position.plus(0f, 1.1f, 0f))
        assertTrue(d > 6f, "caméra rapprochée à $d m au lieu de monter")
        assertTrue(s.camera.position.y > 4f, "caméra trop basse : ${s.camera.position.y}")
    }
}
