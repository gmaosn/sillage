package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.*
import kotlin.test.*

/** Les articulations : chacune comparée à la physique qu'elle doit reproduire (période, angle, vitesse, longueur). */
class JointTest {
    private val dt = 1f / 60f

    private fun ground(w: PhysicsWorld, size: Float = 20f, y: Float = 0f) =
        w.addMesh(TriangleMesh(floatArrayOf(-size, y, -size, -size, y, size, size, y, size, -size, y, -size, size, y, size, size, y, -size)))

    private fun run(w: PhysicsWorld, seconds: Float, each: () -> Unit = {}) = repeat((seconds / dt).roundToInt()) { w.step(dt); each() }

    /** Période d'un mouvement, par les passages à zéro d'une mesure (montants), moyennée. */
    private fun period(w: PhysicsWorld, seconds: Float, measure: () -> Float): Float {
        val crossings = ArrayList<Float>(); var t = 0f; var last = measure()
        run(w, seconds) {
            t += dt
            val now = measure()
            if (last < 0f && now >= 0f) crossings.add(t - dt * now / (now - last))
            last = now
        }
        assertTrue(crossings.size >= 3, "trop peu d'oscillations : ${crossings.size}")
        return (crossings.last() - crossings.first()) / (crossings.size - 1)
    }

    @Test fun unPenduleBatAuRythmeDuPendulePesant() {
        val w = PhysicsWorld()
        val l = 1.2f; val r = .1f; val angle = .15f
        val anchor = Vec3(0f, 3f, 0f)
        val bob = w.add(Sphere(r), anchor + Vec3(sin(angle) * l, -cos(angle) * l, 0f))
        val j = w.addBall(null, bob, anchor)
        var worst = 0f
        val t = period(w, 12f) { worst = max(worst, abs((bob.position - anchor).length() - l)); bob.position.x }
        // Pendule pesant : T = 2π √(I / (m g L)), I = m L² + 2/5 m r² ; correction d'amplitude (1 + θ²/16).
        val expected = 2 * PI.toFloat() * sqrt((l * l + .4f * r * r) / (9.81f * l)) * (1 + angle * angle / 16)
        assertEquals(expected, t, expected * .01f, "période")
        assertTrue(worst < .002f, "la tige s'allonge de ${worst * 1000} mm")
        assertTrue(j.force > 0f)
    }

    private fun energy(w: PhysicsWorld, bodies: List<Body>) = bodies.sumOf { b ->
        (.5 * b.mass * b.velocity.lengthSquared() - b.mass * (w.gravity dot b.position)).toDouble()
    }

    @Test fun uneChaineLacheeALHorizontaleResteEntiere() {
        val w = PhysicsWorld()
        val links = w.addChain(Vec3(0f, 5f, 0f), Vec3(3f, 5f, 0f), 20, radius = .03f)
        assertEquals(20, w.jointList.size)
        val e0 = energy(w, links)
        var gap = 0f; var lowest = 5f; var gained = 0.0
        run(w, 6f) {
            gap = max(gap, w.jointList.maxOf { (it.anchorA - it.anchorB).length() })
            lowest = min(lowest, links.minOf { it.position.y })
            gained = max(gained, energy(w, links) - e0)
        }
        // Le bout fouette à 15 m/s : les articulations souples cèdent un peu, le temps du coup de fouet
        // (24 mm à 4 sous-pas, 14 à 8 ; plus de passes par sous-pas n'y change rien, mesuré).
        assertTrue(gap < .03f, "maillons écartés de ${gap * 1000} mm")
        assertTrue(lowest > 5f - 3f - .06f, "la chaîne s'allonge : point le plus bas $lowest")
        assertTrue(gained <= .01 * abs(e0) + 1e-3, "énergie créée : $gained J")
        // Amortie, elle finit par pendre droit sous son attache.
        for (l in links) { l.linearDamping = .5f; l.angularDamping = .5f }
        run(w, 25f)
        assertTrue(links.last().isSleeping, "la chaîne amortie devrait s'endormir")
        val bottom = links.last().position
        assertTrue(abs(bottom.x) < .05f, "la chaîne devrait pendre sous son attache : $bottom")
        // Au repos, chaque articulation souple cède en proportion de ce qu'elle porte : celle du haut porte les
        // vingt maillons (2 mm), la chaîne s'allonge de 2,7 cm sur 3 m (bas idéal : 5 − 19,5 × 0,15 = 2,075).
        val still = w.jointList.maxOf { (it.anchorA - it.anchorB).length() }
        assertTrue(still < .003f, "au repos, maillons écartés de ${still * 1000} mm")
        assertEquals(2.075f, bottom.y, .04f)
    }

    @Test fun unePorteNeTourneQueSurSesGondsEtDansSesButees() {
        val w = PhysicsWorld(gravity = Vec3(0f, -9.81f, 0f))
        val door = w.add(Box(.45f, 1f, .03f), Vec3(.45f, 1f, 0f), density = 600f)
        val hinge = w.addHinge(null, door, Vec3(0f, 1f, 0f), Vec3.Y)
        hinge.limits(-PI.toFloat() / 2, 0f)
        door.applyImpulse(Vec3(0f, 0f, 40f), Vec3(.9f, 1f, 0f))         // poussée vers +z : angle négatif autour de y
        var low = 0f; var high = 0f; var drift = 0f; var tilt = 0f
        run(w, 4f) {
            low = min(low, hinge.angle); high = max(high, hinge.angle)
            drift = max(drift, (hinge.anchorB - Vec3(0f, 1f, 0f)).length())
            tilt = max(tilt, 1f - (door.rotation.rotate(Vec3.Y) dot Vec3.Y))
        }
        assertTrue(low >= -PI.toFloat() / 2 - .03f, "butée basse dépassée : $low")
        assertTrue(high <= .03f, "butée haute dépassée : $high")
        assertTrue(low < -1.4f, "la porte devrait s'ouvrir jusqu'à sa butée : $low")
        assertTrue(drift < .003f, "les gonds bougent de ${drift * 1000} mm")
        assertTrue(tilt < 1e-4f, "la porte penche : $tilt")
    }

    /**
     * Une porte légère frappée loin de ses gonds (24 rad/s d'un coup) puis claquée contre ses butées : la passe
     * rigide des articulations et les lignes angulaires tenues au gond la gardent à 1,2 mm (2,5 mm avant).
     */
    @Test fun unePorteFrappeeViolemmentResteSurSesGonds() {
        val w = PhysicsWorld()
        val door = w.add(Box(.45f, 1f, .03f), Vec3(.45f, 1f, 0f), mass = 40f)
        val hinge = w.addHinge(null, door, Vec3(0f, 1f, 0f), Vec3.Y)
        hinge.limits(-1.2f, 1.2f)
        var drift = 0f; var over = 0f
        for (k in 0 until 8) {
            // Pushed at a point beyond its edge once it has turned: a large lever, a fast spin.
            door.applyImpulse(Vec3(0f, 0f, if (k % 2 == 0) 60f else -60f), Vec3(.9f, 1.8f, 0f))
            run(w, .5f) {
                drift = max(drift, (hinge.anchorB - Vec3(0f, 1f, 0f)).length())
                over = max(over, max(-1.2f - hinge.angle, hinge.angle - 1.2f))
            }
        }
        assertTrue(drift < .0018f, "les gonds bougent de ${drift * 1000} mm")
        assertTrue(over < .01f, "butée dépassée de $over rad")
    }

    @Test fun unMoteurFaitTournerUneRoueASaVitesse() {
        val w = PhysicsWorld(gravity = Vec3.ZERO)
        val wheel = w.add(Capsule(.4f, .05f), Vec3(0f, 1f, 0f), Quat.axisAngle(Vec3.X, PI.toFloat() / 2))
        val hinge = w.addHinge(null, wheel, wheel.position, Vec3.Z)
        hinge.motor(3f, maxTorque = 50f)
        run(w, 2f)
        assertEquals(3f, hinge.speed, .01f)
        // Trop peu de couple pour une roue freinée : elle n'atteint pas sa vitesse.
        hinge.motor(-3f, maxTorque = .01f)
        run(w, .5f)
        assertTrue(hinge.speed > 2f, "un couple de 0,01 N·m ne retourne pas la roue : ${hinge.speed}")
    }

    @Test fun unAscenseurMonteASaVitesseEtSArreteEnHaut() {
        val w = PhysicsWorld()
        val cabin = w.add(Box(.6f, .1f, .6f), Vec3(0f, 0f, 0f), mass = 200f)
        val slider = w.addSlider(null, cabin, Vec3.Y)
        slider.limits(0f, 2f)
        slider.motor(.5f, maxForce = 5000f)
        run(w, 2f)
        assertEquals(.5f, slider.speed, .01f)
        assertEquals(1f, slider.translation, .03f)
        run(w, 4f)
        assertEquals(2f, slider.translation, .01f, "la butée haute arrête la cabine")
        assertTrue(abs(cabin.position.x) < 1e-3f && abs(cabin.position.z) < 1e-3f)
        assertTrue(abs(cabin.rotation.w) > .99999f, "la cabine tourne : ${cabin.rotation}")
        // Moteur coupé : elle redescend jusqu'à la butée basse.
        slider.motor(null)
        run(w, 3f)
        assertEquals(0f, slider.translation, .01f)
    }

    @Test fun unRessortOscilleASaFrequence() {
        val w = PhysicsWorld(gravity = Vec3.ZERO)
        val anchor = Vec3(0f, 2f, 0f)
        for (mass in floatArrayOf(.5f, 50f)) {
            val b = w.add(Sphere(.1f), anchor + Vec3(0f, -1f, 0f), mass = mass)
            val spring = w.addSpring(null, anchor, b, b.position, hertz = 2f, dampingRatio = 0f)
            b.velocity = Vec3(0f, -.5f, 0f)
            val t = period(w, 3f) { (b.position - anchor).length() - 1f }
            assertEquals(.5f, t, .5f * .03f, "masse $mass : période")
            assertEquals(1f, spring.length)
            w.remove(b)
            assertTrue(spring.isRemoved)
        }
    }

    @Test fun uneCordeLaisseTomberPuisRetient() {
        val w = PhysicsWorld()
        val anchor = Vec3(0f, 5f, 0f)
        val b = w.add(Sphere(.1f), Vec3(0f, 4.5f, 0f))
        w.addRope(null, anchor, b, b.position, length = 2f)
        run(w, .2f)
        assertTrue(b.velocity.y < -1.5f, "corde lâche : la boule tombe librement (${b.velocity})")
        run(w, 3f)
        assertEquals(2f, (b.position - anchor).length(), .01f)
        assertTrue(b.velocity.length() < .05f)
    }

    @Test fun uneSoudureTientUnePoutreEnPorteAFaux() {
        // Articulations souples (pas souple de Box2D v3) : la poutre fléchit un peu, moins avec plus de sous-pas.
        for ((substeps, hertz, sag) in listOf(Triple(4, 60f, .06f), Triple(8, 120f, .015f), Triple(4, 240f, .06f))) {
            val w = PhysicsWorld(); w.substeps = substeps; w.jointHertz = hertz
            var previous: Body? = null
            val beams = (0 until 4).map { k ->
                val b = w.add(Box(.25f, .05f, .05f), Vec3(.25f + .5f * k, 2f, 0f), density = 500f)
                w.addFixed(previous, b, Vec3(.5f * k, 2f, 0f)); previous = b; b
            }
            run(w, 3f)
            var shake = 0f
            run(w, 1f) { shake = max(shake, beams.last().velocity.length()) }
            val tip = beams.last().position
            assertTrue(tip.y > 2f - sag, "$substeps sous-pas, $hertz Hz : la poutre de 2 m fléchit de ${(2f - tip.y) * 100} cm")
            assertEquals(1.75f, tip.x, .01f)
            assertTrue(shake < .01f, "$substeps sous-pas, $hertz Hz : la poutre tremble ($shake m/s)")
        }
    }

    @Test fun desCorpsReliesPeuventSeTraverser() {
        for (collide in listOf(false, true)) {
            val w = PhysicsWorld(gravity = Vec3.ZERO)
            val a = w.add(Box(.5f, .5f, .5f), Vec3(0f, 0f, 0f))
            val b = w.add(Box(.5f, .5f, .5f), Vec3(.6f, 0f, 0f))
            w.addBall(a, b, Vec3(.3f, 0f, 0f), collideConnected = collide)
            run(w, 1f)
            val spread = (b.position - a.position).length()
            if (collide) assertTrue(spread > .65f, "reliés mais en collision, ils s'écartent autour de la rotule : $spread")
            else assertEquals(.6f, spread, .005f, "reliés sans collision, ils restent l'un dans l'autre")
            assertEquals(0, w.contacts().size.takeIf { !collide } ?: 0)
        }
    }

    @Test fun uneChaineSEndortEtSeReveilleEnEntier() {
        val w = PhysicsWorld(); ground(w)
        val links = w.addChain(Vec3(0f, 3f, 0f), Vec3(0f, 1f, 0f), 8, radius = .04f)
        run(w, 6f)
        assertTrue(links.all { it.isSleeping }, "la chaîne pendante devrait dormir : ${links.map { it.velocity.length() }}")
        links.last().applyImpulse(Vec3(2f, 0f, 0f))
        w.step(dt)
        assertTrue(links.none { it.isSleeping }, "un coup en bas réveille toute la chaîne")
        // Une articulation retirée libère le bas, qui tombe.
        val joint = w.jointList[4]
        w.removeJoint(joint)
        run(w, 1f)
        assertTrue(links.last().position.y < .5f, "le bas de la chaîne devrait être tombé : ${links.last().position}")
        assertTrue(links.first().position.y > 2f)
    }

    @Test fun articulationsRefuseesSiMalPosees() {
        val w = PhysicsWorld()
        val s = w.add(Box(1f, 1f, 1f), Vec3.ZERO, type = BodyType.STATIC)
        val d = w.add(Sphere(.5f), Vec3(0f, 3f, 0f))
        assertFailsWith<IllegalArgumentException> { w.addBall(null, s, Vec3.ZERO) }
        assertFailsWith<IllegalArgumentException> { w.addBall(d, d, Vec3.ZERO) }
        assertFailsWith<IllegalArgumentException> { w.addHinge(null, d, Vec3.ZERO, Vec3.ZERO) }
        assertFailsWith<IllegalArgumentException> { PhysicsWorld().addBall(null, d, Vec3.ZERO) }
        assertFailsWith<IllegalArgumentException> { w.addHinge(s, d, Vec3.ZERO, Vec3.Y).limits(1f, 0f) }
    }

    // ------------------------------------------------------------------ continuous collision

    @Test fun unProjectileTresRapideNeTraverseNiDalleNiSol() {
        for (speed in floatArrayOf(100f, 400f, 1500f)) {
            val w = PhysicsWorld()
            w.add(Box(3f, .01f, 3f), Vec3.ZERO, type = BodyType.STATIC)
            val s = w.add(Sphere(.05f), Vec3(0f, 5f, 0f)); s.velocity = Vec3(0f, -speed, 0f)
            run(w, 1f)
            assertTrue(s.position.y > 0f, "$speed m/s : la balle a traversé la dalle de 2 cm (${s.position})")
            val m = PhysicsWorld(); ground(m)
            val t = m.add(Sphere(.05f), Vec3(.3f, 5f, .2f)); t.velocity = Vec3(1f, -speed, 0f)
            run(m, 1f)
            assertTrue(t.position.y > 0f, "$speed m/s : la balle a traversé le sol maillé (${t.position})")
            assertTrue(m.continuousHits > 0)
        }
    }

    @Test fun uneBalleTraverseUneCaisseSaufSiCestUnProjectile() {
        for (isBullet in listOf(false, true)) {
            val w = PhysicsWorld(gravity = Vec3.ZERO)
            val crate = w.add(Box(.02f, .5f, .5f), Vec3(0f, 0f, 0f), mass = 5f)
            val b = w.add(Sphere(.02f), Vec3(-5f, 0f, 0f), mass = .05f); b.isBullet = isBullet
            b.velocity = Vec3(600f, 0f, 0f)
            run(w, .1f)
            if (isBullet) {
                assertTrue(b.position.x < crate.position.x, "un projectile s'arrête sur la planche (${b.position}, planche ${crate.position})")
                assertTrue(crate.velocity.x > 0f, "et la pousse")
            } else assertTrue(b.position.x > 1f, "sans le drapeau, seuls les corps immobiles sont balayés (${b.position})")
        }
    }

    @Test fun unRouleauRapideSurLeSolNEstPasArretteParLeSol() {
        // Le balayage part du noyau de la boule : glisser vite sur le sol ne la colle pas au sol.
        val w = PhysicsWorld(); ground(w)
        val s = w.add(Sphere(.2f), Vec3(0f, .2f, 0f)); s.velocity = Vec3(40f, 0f, 0f)
        run(w, .25f)
        assertTrue(s.position.x > 8f, "la boule devrait filer : ${s.position}")
    }
}
