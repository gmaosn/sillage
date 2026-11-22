package sillage.physics

import sillage.fmt
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
        val a = sillage.math.toRadians(degrees.toDouble()).toFloat()
        val q = Quat.axisAngle(Vec3.X, a)
        val c = listOf(Vec3(-10f, 0f, -10f), Vec3(-10f, 0f, 10f), Vec3(10f, 0f, 10f), Vec3(10f, 0f, -10f)).map { q.rotate(it) }
        w.addMesh(TriangleMesh(floatArrayOf(c[0].x, c[0].y, c[0].z, c[1].x, c[1].y, c[1].z, c[2].x, c[2].y, c[2].z,
            c[0].x, c[0].y, c[0].z, c[2].x, c[2].y, c[2].z, c[3].x, c[3].y, c[3].z)))
    }

    @Test fun leFrottementRetientOuLaisseGlisser() {
        fun glissade(mu: Float): Float {
            val w = PhysicsWorld(); slope(w, 20f)
            val q = Quat.axisAngle(Vec3.X, sillage.math.toRadians(20.0).toFloat())
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
        val q = Quat.axisAngle(Vec3.X, sillage.math.toRadians(15.0).toFloat())
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

    /** A heap large enough for the worker threads: with or without them, the same result bit for bit. */
    @Test fun lesFilsDonnentLeMemeResultat() {
        fun heap(parallel: Boolean): PhysicsWorld {
            val w = PhysicsWorld(); w.parallel = parallel
            w.addMesh(TriangleMesh(floatArrayOf(-40f, 0f, -40f, -40f, 0f, 40f, 40f, 0f, 40f, -40f, 0f, -40f, 40f, 0f, 40f, 40f, 0f, -40f)))
            for (i in 0 until 3000) {
                val p = Vec3((i % 20) * 1.1f - 11f, 1f + (i / 400) * 1.2f, ((i / 20) % 20) * 1.1f - 11f)
                when (i % 3) {
                    0 -> w.add(Box(.4f, .3f, .35f), p, Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), i * .3f))
                    1 -> w.add(Sphere(.35f), p)
                    else -> w.add(Capsule(.25f, .3f), p, Quat.axisAngle(Vec3.X, i * .5f))
                }
            }
            return w
        }
        val a = heap(false); val b = heap(true)
        repeat(120) { a.step(1f / 60f); b.step(1f / 60f) }
        assertTrue(a.contactCount > 4000, "${a.contactCount} points : le tas doit passer par les fils")
        for (i in 0 until a.bodyCount) {
            assertEquals(a.body(i).position, b.body(i).position, "corps $i")
            assertEquals(a.body(i).rotation, b.body(i).rotation, "corps $i")
        }
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

    /**
     * L'ascenseur du terrain de jeu (boîte cinématique de 2 × 0,3 × 2 m) qui descend jusqu'au sol sur un ballon :
     * le ballon, que les contacts ne pouvaient tenir entre les deux, finissait enfoncé dans le sol. Il sort de côté.
     */
    @Test fun unBallonSousLAscenseurEstChasseDeCote() {
        for (start in listOf(Vec3(.3f, .25f, -.2f), Vec3(0f, .25f, 0f))) {
            val w = PhysicsWorld(); ground(w)
            val ball = w.add(Sphere(.25f), start, restitution = .7f)
            val lift = w.add(Box(1f, .15f, 1f), Vec3(0f, 1.5f, 0f), type = BodyType.KINEMATIC)
            var lowest = 1f
            run(w, 3f) {
                val y = max(.15f, lift.position.y - 1.2f * dt)
                lift.moveTo(Vec3(0f, y, 0f), dt)
                lowest = min(lowest, ball.position.y)
            }
            assertTrue(lowest > .2f, "le ballon s'enfonce dans le sol : centre à $lowest m")
            assertTrue(max(abs(ball.position.x), abs(ball.position.z)) > 1f + .2f, "le ballon est sorti de sous l'ascenseur : ${ball.position}")
            assertTrue(w.squeezes > 0)
            if (start.x != 0f) assertTrue(ball.position.x > 0f, "du côté où il était : ${ball.position}")
        }
    }

    /** Le ballon de la démo, poussé par le héros (capsule cinématique à 4,4 m/s). */
    private fun ballonPousse(): Pair<Float, Float> {
        val w = PhysicsWorld(); ground(w)
        val ball = w.add(Sphere(.35f), Vec3(0f, .36f, 0f), mass = .6f, friction = .5f, restitution = .65f, rollingResistance = .05f)
        ball.angularDamping = .1f
        ball.linearDamping = BALLON_AIR
        val hero = w.add(Capsule(.28f, .62f), Vec3(-2f, .9f, 0f), type = BodyType.KINEMATIC)
        var vmax = 0f; var t = 0f; var after1s = -1f; var hit = -1f
        while (t < 3f) {
            if (t < 1.2f) hero.moveTo(hero.position + Vec3(4.4f * dt, 0f, 0f), dt) else hero.velocity = Vec3.ZERO
            w.step(dt); t += dt
            val v = ball.velocity.length()
            if (v > .1f && hit < 0f) hit = t
            vmax = max(vmax, v)
            if (after1s < 0f && t > 2.2f) after1s = v          // une seconde après l'arrêt du héros
        }
        return vmax to after1s
    }

    @Test fun leHerosPousseLeBallonSansLeFaireGicler() {
        val (vmax, after) = ballonPousse()
        println("BALLON vitesse max ${"%.2f".fmt(vmax)} m/s, une seconde après l'arrêt du héros ${"%.2f".fmt(after)} m/s")
        assertTrue(vmax < 4.4f * 1.15f, "le ballon part plus vite que le héros : $vmax m/s")
        assertTrue(after < 3f, "le ballon ne ralentit pas : $after m/s")
    }

    @Test fun retirerUnCorps() {
        val w = PhysicsWorld(); w.add(Box(10f, .5f, 10f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
        val dessous = w.add(Box(.5f, .5f, .5f), Vec3(0f, .5f, 0f))
        val dessus = w.add(Box(.5f, .5f, .5f), Vec3(0f, 1.5f, 0f))
        repeat(60) { w.step(1f / 60f) }
        assertEquals(1.5f, dessus.position.y, .01f)
        w.remove(dessous)
        assertTrue(dessous.isRemoved); assertEquals(2, w.liveBodyCount)
        // Le rayon ne le voit plus, la caisse du dessus tombe à sa place.
        assertNull(w.raycast(Vec3(-5f, .5f, 0f), Vec3(1f, 0f, 0f), 10f), "un rayon à sa hauteur passe")
        repeat(90) { w.step(1f / 60f) }
        assertEquals(.5f, dessus.position.y, .01f)
        // Sa place sert au corps suivant, qui part de zéro.
        val nouveau = w.add(Sphere(.3f), Vec3(3f, 2f, 0f))
        assertEquals(dessous.id, nouveau.id); assertFalse(nouveau.isRemoved)
        assertEquals(Vec3.ZERO, nouveau.velocity)
        repeat(90) { w.step(1f / 60f) }
        assertEquals(.3f, nouveau.position.y, .01f)
        assertEquals(.5f, dessus.position.y, .01f)
    }
}

private const val BALLON_AIR = .8f
