package sillage.physics

import sillage.math.Vec3
import kotlin.test.*

class TriggerTest {
    @Test fun unBallonQuiTraverseUneZoneEntrePuisSort() {
        val w = PhysicsWorld()
        w.add(Box(5f, .5f, 5f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
        val ball = w.add(Sphere(.2f), Vec3(-3f, .2f, 0f), mass = 1f, friction = 0f)
        ball.velocity = Vec3(3f, 0f, 0f)
        val zone = w.addTrigger(Box(.5f, 1f, .5f), Vec3(0f, 1f, 0f))
        val log = ArrayList<String>()
        zone.onEnter = { log.add("entre"); assertEquals(ball, it) }
        zone.onExit = { log.add("sort") }
        var dedans = 0
        repeat(150) { w.step(1f / 60f); if (zone.inside.isNotEmpty()) dedans++ }
        assertEquals(listOf("entre", "sort"), log, "le sol statique ne compte pas, le ballon une fois")
        // Le ballon touche la zone sur 1 m + 2 × 0,2 m à environ 3 m/s : ~28 images.
        assertTrue(dedans in 22..32, "$dedans images dedans")
    }

    @Test fun unPersonnageRamasseUnePiece() {
        val w = PhysicsWorld()
        w.add(Box(10f, .5f, 10f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
        val c = Character(w, Vec3(0f, 0f, 0f))
        val piece = w.addTrigger(Sphere(.5f), Vec3(3f, .6f, 0f))
        piece.filter = { it.tag is Character }
        var ramassees = 0
        piece.onEnter = { ramassees++; piece.enabled = false }
        repeat(120) { c.update(Vec3(3f, 0f, 0f), 0f, 1f / 60f); w.step(1f / 60f) }
        assertEquals(1, ramassees)
        assertTrue(c.position.x > 4f, "le marin passe à travers la pièce sans être gêné : ${c.position.x}")
    }

    @Test fun uneZoneRemiseEnServiceRevoitCeQuiEstDedans() {
        val w = PhysicsWorld()
        val b = w.add(Sphere(.3f), Vec3.ZERO, type = BodyType.KINEMATIC)
        val zone = w.addTrigger(Sphere(1f), Vec3.ZERO)
        var n = 0
        zone.onEnter = { n++ }
        w.step(1f / 60f); zone.enabled = false; w.step(1f / 60f); zone.enabled = true; w.step(1f / 60f)
        assertEquals(2, n)
        assertEquals(listOf(b), zone.inside)
    }
}
