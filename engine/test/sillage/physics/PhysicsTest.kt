package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.*
import kotlin.test.*

class PhysicsTest {
    private val dt = 1f / 60f

    private fun ground(w: PhysicsWorld, size: Float = 20f, y: Float = 0f) =
        w.addMesh(TriangleMesh(floatArrayOf(-size, y, -size, -size, y, size, size, y, size, -size, y, -size, size, y, size, size, y, -size)))

    private fun run(w: PhysicsWorld, seconds: Float, each: () -> Unit = {}) = repeat((seconds / dt).roundToInt()) { w.step(dt); each() }

    @Test fun uneSphereSePoseSurUneBoiteStatiqueEtSEndort() {
        val w = PhysicsWorld()
        w.add(Box(5f, .5f, 5f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
        val s = w.add(Sphere(.5f), Vec3(0f, 2f, 0f))
        run(w, 3f)
        assertEquals(.5f, s.position.y, .01f)
        assertTrue(s.isSleeping, "la sphère devrait dormir, v=${s.velocity}")
    }

    @Test fun uneBoiteSePoseAPlatSurLeTerrain() {
        val w = PhysicsWorld(); ground(w)
        val b = w.add(Box(.5f, .5f, .5f), Vec3(0f, 1.2f, 0f))
        run(w, 3f)
        assertEquals(.5f, b.position.y, .01f)
        assertTrue(abs(b.rotation.rotate(Vec3.Y).y) > .999f, "rotation ${b.rotation}")
        assertTrue(b.isSleeping)
    }

    @Test fun uneBoiteLacheeDeTraversFinitSurUneFace() {
        val w = PhysicsWorld(); ground(w)
        val b = w.add(Box(.5f, .3f, .4f), Vec3(0f, 1.5f, 0f), Quat.axisAngle(Vec3(1f, 0f, 1f).normalized(), .7f))
        run(w, 4f)
        val r = b.rotation
        val up = listOf(r.rotate(Vec3.X), r.rotate(Vec3.Y), r.rotate(Vec3.Z)).maxOf { abs(it.y) }
        assertTrue(up > .995f, "aucune face à plat : $up")
        assertTrue(b.position.y in .29f..0.51f, "hauteur ${b.position.y}")
    }

    @Test fun unePileDeHuitCaissesTient() {
        val w = PhysicsWorld(); ground(w)
        val boxes = (0 until 8).map { w.add(Box(.5f, .5f, .5f), Vec3(0f, .5f + it * 1.0f, 0f)) }
        run(w, 6f)
        val top = boxes.last().position
        assertTrue(hypot(top.x, top.z) < .02f, "le haut de la pile a glissé : $top")
        assertEquals(7.5f, top.y, .05f)
        assertTrue(boxes.all { it.isSleeping }, "la pile devrait dormir")
    }

    @Test fun leRebondRendLaBonneHauteur() {
        val w = PhysicsWorld(); ground(w)
        val s = w.add(Sphere(.5f), Vec3(0f, 2f, 0f), restitution = .8f)
        var touched = false; var apex = 0f
        run(w, 2.5f) {
            if (s.velocity.y > 0f) touched = true
            if (touched) apex = max(apex, s.position.y)
        }
        val expected = .5f + .8f * .8f * 1.5f
        assertEquals(expected, apex, .1f, "sommet après le rebond")
    }

    private fun slope(w: PhysicsWorld, degrees: Float) {
        val a = Math.toRadians(degrees.toDouble()).toFloat()
        val q = Quat.axisAngle(Vec3.X, a)
        val c = listOf(Vec3(-10f, 0f, -10f), Vec3(-10f, 0f, 10f), Vec3(10f, 0f, 10f), Vec3(10f, 0f, -10f)).map { q.rotate(it) }
        w.addMesh(TriangleMesh(floatArrayOf(c[0].x, c[0].y, c[0].z, c[1].x, c[1].y, c[1].z, c[2].x, c[2].y, c[2].z,
            c[0].x, c[0].y, c[0].z, c[2].x, c[2].y, c[2].z, c[3].x, c[3].y, c[3].z)))
    }

    @Test fun leFrottementRetientOuLaisseGlisser() {
        fun glissade(mu: Float): Float {
            val w = PhysicsWorld(); slope(w, 20f)
            val q = Quat.axisAngle(Vec3.X, Math.toRadians(20.0).toFloat())
            val b = w.add(Box(.5f, .5f, .5f), q.rotate(Vec3(0f, .5f, 0f)), q, friction = mu)
            run(w, .5f)
            val p0 = b.position
            run(w, 2f)
            return b.position.distanceTo(p0)
        }
        assertTrue(glissade(.6f) < .02f, "la caisse glisse alors que μ = 0,6 > tan 20°")
        assertTrue(glissade(.1f) > .5f, "la caisse ne glisse pas avec μ = 0,1")
    }

    @Test fun uneBouleRouleSansGlisser() {
        val w = PhysicsWorld(); slope(w, 15f)
        val q = Quat.axisAngle(Vec3.X, Math.toRadians(15.0).toFloat())
        val s = w.add(Sphere(.4f), q.rotate(Vec3(0f, .4f, 0f)))
        run(w, 1f)
        val v = s.velocity.length(); val om = s.angularVelocity.length()
        assertTrue(v > .5f, "la boule ne descend pas : $v")
        assertEquals(v, om * .4f, v * .1f, "roulement sans glissement")
    }

    @Test fun uneCapsuleCoucheeResteCouchee() {
        val w = PhysicsWorld(); ground(w)
        val c = w.add(Capsule(.3f, .6f), Vec3(0f, 1f, 0f), Quat.axisAngle(Vec3.Z, (PI / 2).toFloat()))
        run(w, 3f)
        assertEquals(.3f, c.position.y, .015f)
        assertTrue(abs(c.rotation.rotate(Vec3.Y).y) < .02f, "la capsule s'est redressée")
        assertTrue(c.isSleeping)
    }

    @Test fun laQuantiteDeMouvementSeConserve() {
        val w = PhysicsWorld(Vec3.ZERO)
        val a = w.add(Sphere(.5f), Vec3(-2f, 0f, 0f), restitution = 1f); a.velocity = Vec3(3f, 0f, 0f)
        val b = w.add(Sphere(.5f), Vec3(2f, 0f, 0f), restitution = 1f); b.velocity = Vec3(-1f, 0f, 0f)
        run(w, 2f)
        val p = a.velocity * a.mass + b.velocity * b.mass
        assertEquals(2f * a.mass, p.x, .01f * a.mass)
        assertEquals(-1f, a.velocity.x, .1f); assertEquals(3f, b.velocity.x, .1f)
    }

    private fun scene(): PhysicsWorld {
        val w = PhysicsWorld(); ground(w)
        for (i in 0 until 50) {
            val p = Vec3((i % 5) * 1.1f - 2f, 1f + (i / 5) * 1.2f, (i % 3) * .7f - .7f)
            when (i % 3) {
                0 -> w.add(Box(.4f, .3f, .35f), p, Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), i * .3f))
                1 -> w.add(Sphere(.35f), p)
                else -> w.add(Capsule(.25f, .3f), p, Quat.axisAngle(Vec3.X, i * .5f))
            }
        }
        return w
    }

    @Test fun laSimulationEstDeterministe() {
        val a = scene(); val b = scene()
        run(a, 5f); run(b, 5f)
        for (i in 0 until a.bodyCount) {
            assertEquals(a.body(i).position, b.body(i).position, "corps $i")
            assertEquals(a.body(i).rotation, b.body(i).rotation, "corps $i")
        }
        assertTrue((0 until a.bodyCount).all { a.body(it).position.isFinite() && a.body(it).position.y > -.01f })
    }

    @Test fun unChocReveilleUnObjetEndormi() {
        val w = PhysicsWorld(); ground(w)
        val b = w.add(Box(.5f, .5f, .5f), Vec3(0f, .5f, 0f), mass = 20f)   // une caisse vide, pas une tonne d'eau
        run(w, 2f)
        assertTrue(b.isSleeping)
        val ball = w.add(Sphere(.3f), Vec3(-3f, .5f, 0f)); ball.velocity = Vec3(8f, 0f, 0f)
        run(w, 1f)
        assertTrue(b.position.x > .05f, "la caisse n'a pas été poussée : ${b.position}")
    }

    @Test fun unCorpsCinematiquePousse() {
        val w = PhysicsWorld(); ground(w)
        val b = w.add(Box(.5f, .5f, .5f), Vec3(0f, .5f, 0f))
        val k = w.add(Capsule(.3f, .5f), Vec3(-2f, .8f, 0f), type = BodyType.KINEMATIC)
        run(w, 2f) { k.moveTo(k.position + Vec3(2f * dt, 0f, 0f), dt) }
        assertTrue(b.position.x > 1f, "la caisse n'a pas avancé : ${b.position}")
        assertEquals(.5f, b.position.y, .05f)
    }

    @Test fun unObjetRapideNeTraversePasUnePlaqueMince() {
        val w = PhysicsWorld()
        w.add(Box(3f, .05f, 3f), Vec3.ZERO, type = BodyType.STATIC)
        val s = w.add(Sphere(.1f), Vec3(0f, 5f, 0f)); s.velocity = Vec3(0f, -30f, 0f)
        run(w, 2f)
        assertTrue(s.position.y > 0f, "la sphère a traversé : ${s.position}")
    }

    @Test fun deuxBoitesParLArreteNExplosentPas() {
        val w = PhysicsWorld(); ground(w)
        w.add(Box(.5f, .5f, .5f), Vec3(0f, .5f, 0f), Quat.axisAngle(Vec3.Y, .4f))
        val top = w.add(Box(.5f, .5f, .5f), Vec3(.2f, 2f, .1f), Quat.axisAngle(Vec3(1f, 0f, 1f).normalized(), .9f))
        var vmax = 0f
        run(w, 4f) { vmax = max(vmax, top.velocity.length()) }
        assertTrue(vmax < 8f, "vitesse folle : $vmax")
        assertTrue(top.position.y > .4f && top.position.isFinite())
    }

    @Test fun unePileDeCaissesTourneesTient() {
        val w = PhysicsWorld(); ground(w)
        val boxes = (0..2).map { k -> w.add(Box(.35f, .35f, .35f), Vec3(0f, .36f + k * .72f, 0f), Quat.rotationY(k * .25f), mass = 13.7f, friction = .7f) }
        run(w, 5f)
        val top = boxes.last().position
        assertTrue(hypot(top.x, top.z) < .02f, "le haut de la pile a bougé : $top")
        assertEquals(.35f + 2 * .7f, top.y, .03f)
    }
}
