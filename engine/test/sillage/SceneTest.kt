package sillage

import sillage.math.Vec3
import sillage.physics.Box
import sillage.physics.Sphere
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

class SceneTest {
    private val dt = 1f / 60f
    private fun root(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("project.yaml"))) dir = dir.parent
        return dir
    }
    private fun model(name: String) = root().resolve("demo/resources/models/$name.glb").toString()

    /** Runs [frames] frames, the first player (if any) walking along [move] and jumping at frame [jumpAt]. */
    private fun Scene.run(frames: Int, move: Vec3 = Vec3.ZERO, jumpAt: Int = -1, each: (Int) -> Unit = {}) = repeat(frames) { f ->
        player?.let { p -> p.controls = false; if (move != Vec3.ZERO) p.move(move); if (f == jumpAt) p.jump() }
        update(dt); camera.view(dt); each(f)
    }

    // ------------------------------------------------------------------ the API without models

    @Test fun uneCaisseTombeSurLeSolEtSEndort() {
        val s = Scene()
        s.ground()
        val crate = s.box(.6f, at = vec(0, 3, 0)).dynamic()
        val ball = s.sphere(.3f, at = vec(2, 2, 0)).dynamic(bounce = .5)
        s.run(240)
        assertEquals(.3f, crate.position.y, .01f); assertTrue(crate.body!!.isSleeping)
        assertEquals(.3f, ball.position.y, .02f)
        assertEquals(crate.body!!.position, crate.position, "le nœud suit son corps")
    }

    @Test fun unModeleSansAnimationTombeSurSaBoiteEnglobante() {
        val s = Scene(); s.ground()
        // La caisse de la démo : son origine est au centre de sa boîte (0,7 m de côté).
        val c = s.model(model("caisse"), at = vec(0, 2, 0)).dynamic()
        s.run(180)
        assertEquals(.5f, c.position.y, .02f)
    }

    /** Une forme donnée est en mètres : elle ne suit pas l'échelle du modèle (le ballon de la démo s'enfonçait). */
    @Test fun uneFormeDonneeNeSuitPasLEchelle() {
        val s = Scene(); s.ground()
        val ball = s.model(model("ballon"), at = vec(0, 1, 0), scale = .35).dynamic(shape = Sphere(.35f))
        val big = s.sphere(.5f, at = vec(3, 2, 0)).scaled(2).dynamic()
        s.run(180)
        assertEquals(.35f, ball.position.y, .01f); assertEquals(1f, big.position.y, .01f)
    }

    @Test fun retirerUnNoeudRetireSonCorps() {
        val s = Scene(); s.ground()
        val bottom = s.box(1f, at = vec(0, .5, 0)).dynamic(); val top = s.box(1f, at = vec(0, 1.5, 0)).dynamic()
        s.run(60)
        bottom.remove()
        assertTrue(bottom !in s.nodes)
        s.run(90)
        assertEquals(.5f, top.position.y, .02f)
    }

    @Test fun zonesEtMinuteries() {
        val s = Scene(); s.ground()
        var entrees = 0; var sorties = 0
        s.zone(Box(1f, 1f, 1f), vec(0, 1, 0), onEnter = { entrees++ }, onExit = { sorties++ })
        val ball = s.sphere(.25f, at = vec(-4, .25, 0)).dynamic(friction = 0)
        ball.body!!.velocity = vec(4, 0, 0)
        var once = 0; var often = 0
        s.after(1) { once++ }
        s.every(.5) { often++ }
        s.run(150)
        assertEquals(1, entrees); assertEquals(1, sorties)
        assertEquals(1, once); assertEquals(4, often, "toutes les 0,5 s pendant 2,5 s")
    }

    @Test fun lesObjetsARamasserEtRecommencer() {
        val s = Scene(); s.ground()
        val p = s.player(at = vec(0, 0, 0))
        var pris = 0
        val shell = s.sphere(.2f, Color.YELLOW, at = vec(2, .6, 0)); s.pickup(shell) { pris++ }
        s.pickup(s.sphere(.2f, at = vec(-6, .6, 0)))
        s.run(90, move = vec(1, 0, 0))
        assertEquals(1, pris); assertTrue(s.pickups[0].collected); assertFalse(shell.visible); assertFalse(s.pickups[1].collected)
        s.restart(); s.run(2)
        assertTrue(s.pickups.none { it.collected }); assertTrue(shell.visible); assertEquals(0f, p.position.x, .01f)
    }

    @Test fun premierePersonneAuChoix() {
        val s = Scene(); s.ground()
        val p = s.player(at = vec(0, 0, 0)) { view = CameraMode.FIRST_PERSON }
        s.run(30)
        assertEquals(CameraMode.FIRST_PERSON, s.camera.mode)
        assertEquals(1.65f, s.camera.position.y, .02f, "les yeux à la hauteur du joueur moins 15 cm")
        assertTrue(p !in s.drawList(s.camera.position), "le joueur ne se voit pas lui-même")
        // On avance où l'on regarde : un quart de tour à gauche, puis tout droit.
        s.camera.yaw = 90f
        s.run(30, move = s.camera.moveDirection(0f, 1f))
        assertTrue(p.position.x < -1f && abs(p.position.z) < .05f, "parti vers −X : ${p.position}")
        assertEquals(90f, p.yaw, 1f, "le corps regarde comme la caméra")
        // Une touche pour basculer, si on la donne.
        s.camera.switchKey = Key.V
        var v = false
        s.input.down = { k -> k == Key.V && v }
        v = true; s.input.poll(); s.update(dt); v = false; s.input.poll()
        assertEquals(CameraMode.THIRD_PERSON, s.camera.mode)
    }

    @Test fun lInterfaceSeRefaitQuandSonTexteChange() {
        val s = Scene()
        var score = 0
        s.hud.text({ "Score : $score" }, 24, 24)
        val a = s.hud.mesh(1.6f, 60, true); val b = s.hud.mesh(1.6f, 60, true)
        assertSame(a, b, "rien n'a changé : même maillage")
        score = 3
        val c = s.hud.mesh(1.6f, 60, true)
        assertNotSame(a, c); assertTrue(c.triangleCount > 0)
        // Minuscules et accents ont leurs glyphes.
        for (ch in "aàéèçôù!?") assertNotNull(Font.glyph(ch), "glyphe $ch")
    }

    // ------------------------------------------------------------------ the demo island

    /** Signalé le 18 novembre : on tombait à travers la barque de la démo. */
    @Test fun onSeTientSurLaBarqueQuiTangue() {
        val s = Scene()
        val port = s.model(model("port")).play("vie")
        port.collider(Box(.7f, .25f, .75f), vec(0, -.04, 0), part = "bateau")
        port.collider(Box(.42f, .25f, 1.4f), vec(0, -.04, 0), part = "bateau")
        // Le marin tombe d'un mètre sur le pont (barque en (−4 ; 10,7), pont à 0,21 m au-dessus du nœud).
        val p = s.player(model("marin"), at = vec(-4, 1.5, 10.4))
        var bas = 9f; var haut = -9f
        s.run(300) { f -> if (f > 60) { bas = min(bas, p.position.y); haut = max(haut, p.position.y) } }
        assertTrue(p.onGround, "pas posé : ${p.position}")
        assertSame(port, p.character.groundBody?.tag, "posé sur un des corps de la barque")
        assertTrue(bas > .16f + .21f - .07f && haut < .16f + .21f + .07f && haut - bas > .05f, "hauteurs $bas … $haut")
    }

    /** La maison de la démo : toit solide, volume jusqu'au bord du toit, cheminée, tonneaux. */
    private fun house(): Scene {
        val s = Scene()
        s.model(model("terrain")).solid()
        s.model(model("village")).solid("toit")
        s.collider(Box(1.7f, .825f, 1.5f), vec(-3, 1.45 + .825, -2))
        s.collider(Box(.25f, .75f, .25f), vec(-2, 3.2 + .75, -2.6))
        for ((x, z) in listOf(-1.25f to -1.5f, -1.2f to -2.15f)) s.collider(Box(.275f, .375f, .275f), vec(x, s.groundHeight(x, z) + .375f, z), blocksCamera = false)
        return s
    }

    private fun roofTop(x: Float, z: Float): Float? = if (x in -4.7f..-1.3f && z in -3.5f..-.5f) 4.2f - abs(x + 3f) / 1.7f * 1.1f else null

    /** Deepest point of the player's capsule inside the house and its roof (0 if none). */
    private fun sinking(p: Player): Float {
        val q = p.position; val r = p.character.radius * .95f
        var worst = 0f; var h = .05f
        while (h <= 1.75f) {
            for (k in 0 until 8) {
                val a = k * PI.toFloat() / 4
                val rr = if (h < .28f || h > 1.52f) sqrt(max(0f, r * r - (if (h < .28f) .28f - h else h - 1.52f).pow(2))) else r
                val x = q.x + cos(a) * rr; val z = q.z + sin(a) * rr; val y = q.y + h
                val top = roofTop(x, z) ?: continue
                if (y in 1.45f..top) worst = max(worst, minOf(top - y, x + 4.7f, -1.3f - x, z + 3.5f, -.5f - z))
            }
            h += .05f
        }
        return worst
    }

    /** Signalé le 18 novembre (capture) : debout sur un tonneau, le marin passait la tête à travers l'avant-toit. */
    @Test fun leMarinNeTraverseJamaisLeToit() {
        val s = house()
        val p = s.player(model("marin"), at = vec(0, 3, 4))
        val tries = listOf(vec(-1.05, 0, -1.5) to vec(-1, 0, 0), vec(-1.05, 0, -1.5) to Vec3(-1f, 0f, -.6f).normalized(),
            vec(-1, 0, -1) to Vec3(-.3f, 0f, -1f).normalized(), vec(-3, 0, .2) to Vec3(.2f, 0f, -1f).normalized())
        var worst = 0f; var frames = 0
        for ((start, dir) in tries) for (jump in listOf(-1, 12, 40)) {
            p.position = Vec3(start.x, s.groundHeight(start.x, start.z) + 1.2f, start.z)
            s.run(40)
            s.run(150, move = dir, jumpAt = jump) { worst = max(worst, sinking(p)); frames++ }
        }
        assertTrue(frames > 1500)
        assertTrue(worst < .03f, "le marin s'enfonce de ${"%.2f".format(worst)} m dans la maison ou son toit")
    }

    @Test fun depuisLeTonneauOnSauteSurLeToit() {
        val s = house()
        val p = s.player(model("marin"), at = vec(-1.05, s.groundHeight(-1.25f, -1.5f) + 1.5f, -1.5))
        s.run(60)
        assertEquals(s.groundHeight(-1.25f, -1.5f) + .75f, p.position.y, .03f, "debout sur le tonneau")
        repeat(90) { f -> p.controls = false; if (p.position.x > -2.2f) p.move(vec(-1, 0, 0)); if (f == 3) p.jump(); s.update(dt) }
        assertTrue(p.onGround && p.position.y > 3.1f, "pas sur le toit : ${p.position}")
        assertEquals(roofTop(p.position.x, p.position.z)!!, p.position.y, .06f, "posé sur la pente du toit : ${p.position}")
    }
}
