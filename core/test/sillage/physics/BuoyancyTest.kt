package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.*
import kotlin.test.*

/** L'eau qui porte : Archimède contre les valeurs des manuels, stabilité des bateaux, traînée, courant, entrées dans l'eau. */
class BuoyancyTest {
    private val dt = 1f / 60f
    private fun run(w: PhysicsWorld, seconds: Float, each: () -> Unit = {}) = repeat((seconds / dt).roundToInt()) { w.step(dt); each() }

    /** A pool: water at y = 0 above a floor at y = −5. */
    private fun pool(): PhysicsWorld {
        val w = PhysicsWorld()
        w.addMesh(TriangleMesh(floatArrayOf(-50f, -5f, -50f, -50f, -5f, 50f, 50f, -5f, 50f, -50f, -5f, -50f, 50f, -5f, 50f, 50f, -5f, -50f)))
        w.addWater(0f)
        return w
    }

    /** Settles [b] for [seconds], then checks it is still: the largest speed over the last second. */
    private fun settle(w: PhysicsWorld, b: Body, seconds: Float = 12f): Float {
        run(w, seconds - 1f)
        var most = 0f
        run(w, 1f) { most = max(most, b.velocity.length() + b.angularVelocity.length() * b.shape.boundingRadius) }
        return most
    }

    /**
     * Archimedes: a cube of density 500 floats half under, of 250 a quarter, of 750 three quarters, whatever it
     * settles on (a cube that dense is not stable face up: it tips to an edge, as a real one does; at 500 its
     * centre is then still at the waterline). A slab, stable face up, sinks by its density times its thickness,
     * and comes to rest without a tremor.
     */
    @Test fun unCubeDeDensite500FlotteAMoitieEtUnDe250AuQuart() {
        for (density in floatArrayOf(500f, 250f, 750f)) {
            val w = pool()
            val b = w.add(Box(.5f, .5f, .5f), Vec3(0f, 1f, 0f), Quat.axisAngle(Vec3(1f, 0f, 2f).normalized(), .3f), density = density)
            // It turns slowly towards its stable side (corner up at 250 and 500): only how much is under is checked.
            run(w, 16f)
            assertEquals(density / 1000f, b.submergedFraction, .002f, "cube de densité $density : fraction immergée")
            if (density == 500f) assertEquals(0f, b.position.y, .002f, "cube de densité 500 : centre à la surface")
        }
        val w = pool()
        val slab = w.add(Box(.5f, .1f, .5f), Vec3(0f, 1f, 0f), density = 250f)
        val still = settle(w, slab)
        assertEquals(.1f - .25f * .2f, slab.position.y, .002f, "dalle : 5 cm sous l'eau")
        assertTrue(slab.rotation.rotate(Vec3.Y).y > .9999f, "la dalle flotte à plat")
        assertTrue(still < .002f, "la dalle flotte sans trembler : $still m/s")
    }

    @Test fun laLigneDeFlottaisonDUneSphere() {
        // A ball of radius 0.4 m and density 300: the cap under water holds 30 % of its volume.
        val r = .4f; val ratio = .3f
        var lo = 0f; var hi = 2 * r
        repeat(60) { val m = (lo + hi) / 2; if (m * m * (3 * r - m) / (4 * r * r * r) < ratio) lo = m else hi = m }
        val h = (lo + hi) / 2                                   // depth of the cap
        val w = pool()
        val b = w.add(Sphere(r), Vec3(0f, 1f, 0f), density = 300f)
        val still = settle(w, b)
        assertEquals(h - r, -b.position.y, .002f, "ligne de flottaison (calotte de ${h} m)")
        assertEquals(ratio, b.submergedFraction, .002f)
        assertTrue(still < .002f, "la boule flotte sans trembler : $still m/s")
    }

    @Test fun unCorpsPlusDenseQueLEauCoule() {
        val w = pool()
        val b = w.add(Box(.3f, .3f, .3f), Vec3(0f, 1f, 0f), density = 2000f)
        run(w, 6f)
        assertEquals(-4.7f, b.position.y, .01f, "au fond")
        assertEquals(1f, b.submergedFraction, 1e-4f)
        // Its weight in water is half: m g (1 − 1000/2000) on the floor.
        assertTrue(b.isSleeping)
    }

    @Test fun uneLonguePoutreFlotteAPlat() {
        val w = pool()
        val b = w.add(Box(2f, .1f, .1f), Vec3(0f, 1.5f, 0f), Quat.axisAngle(Vec3.Z, 1.2f), density = 500f)
        settle(w, b, 15f)
        assertTrue(abs(b.rotation.rotate(Vec3.X).y) < .01f, "la poutre flotte à plat : ${b.rotation.rotate(Vec3.X)}")
        assertEquals(.5f, b.submergedFraction, .005f)
    }

    @Test fun uneCapsuleEtUnCorpsComposeFlottentAuBonEnfoncement() {
        // Half dense: half under water whatever its turn (a capsule is symmetric about its centre).
        for (turn in floatArrayOf(0f, .7f, 1.5707964f)) {
            val w = pool()
            val b = w.add(Capsule(.2f, .4f), Vec3(0f, 1f, 0f), Quat.axisAngle(Vec3.X, turn), density = 500f)
            b.angularDamping = 5f                                // keep it near its turn while it settles
            settle(w, b)
            assertEquals(.5f, b.submergedFraction, .003f, "capsule tournée de $turn")
        }
        // A raft of two boxes and a ball: any density below the water's floats with that fraction under.
        val raft = Compound(Compound.Part(Box(.5f, .1f, .3f), Vec3(-.6f, 0f, 0f)), Compound.Part(Box(.5f, .1f, .3f), Vec3(.6f, 0f, 0f)),
            Compound.Part(Sphere(.15f), Vec3(0f, .2f, 0f), density = .5f))
        val w = pool()
        val b = w.add(raft, Vec3(0f, 1f, 0f), density = 600f)
        settle(w, b)
        assertEquals(b.mass / (1000f * raft.volume), b.submergedFraction, .003f, "radeau")
    }

    /**
     * A box barge 2 m wide, 4 m long, 0.6 m high, density 500: draught 0.3 m, metacentric height
     * GM = KB + BM − KG = 0.15 + B²/(12 T) − 0.3 = 0.961 m, roll period 2π k / √(g GM) = 1.23 s
     * (k² = (B² + H²)/12). Tilted, it rights itself, at that period.
     */
    @Test fun uneBargeSeRedresseALaPeriodeDuMetacentre() {
        val w = pool()
        w.waterList[0].angularDrag = .2f; w.waterList[0].linearDrag = .2f; w.waterList[0].waveDamping = .2f     // little damping, to see it roll
        val b = w.add(Box(1f, .3f, 2f), Vec3(0f, 0f, 0f), Quat.axisAngle(Vec3.Z, .08f), density = 500f)
        val crossings = ArrayList<Float>(); var t = 0f; var last = 0f
        run(w, 8f) {
            t += dt
            val roll = b.rotation.rotate(Vec3.X).y
            if (last < 0f && roll >= 0f) crossings.add(t - dt * roll / (roll - last))
            last = roll
        }
        assertTrue(crossings.size >= 4, "trop peu d'oscillations : ${crossings.size}")
        val period = (crossings.last() - crossings.first()) / (crossings.size - 1)
        val k = sqrt((4f + .36f) / 12f); val gm = .15f + 4f / (12f * .3f) - .3f
        assertEquals(2 * PI.toFloat() * k / sqrt(9.81f * gm), period, .06f, "période de roulis")
        w.waterList[0].angularDrag = .5f; w.waterList[0].linearDrag = .5f; w.waterList[0].waveDamping = 6f
        run(w, 10f)
        assertTrue(abs(b.rotation.rotate(Vec3.X).y) < .01f, "la barge est droite : ${b.rotation.rotate(Vec3.X).y}")
    }

    @Test fun unBateauALestBasSeRedresseDApresUnGrandCoupDeGite() {
        // A hull wide at the deck, narrow at the keel, with a lead keel: it comes back from 60° of heel.
        val hullPoints = listOf(Vec3(-1f, .3f, -2f), Vec3(1f, .3f, -2f), Vec3(-1f, .3f, 2f), Vec3(1f, .3f, 2f),
            Vec3(-.5f, -.3f, -1.6f), Vec3(.5f, -.3f, -1.6f), Vec3(-.5f, -.3f, 1.6f), Vec3(.5f, -.3f, 1.6f), Vec3(0f, .3f, 2.6f), Vec3(0f, -.2f, 2.2f))
        val hull = ConvexHull(hullPoints)
        val boat = Compound(Compound.Part(hull, hull.centre), Compound.Part(Box(.08f, .15f, 1f), Vec3(0f, -.45f, 0f), density = 30f))
        val w = pool()
        val b = w.add(boat, Vec3(0f, .2f, 0f), Quat.axisAngle(Vec3.Z, 1.05f), density = 150f)
        run(w, 15f)
        val up = b.rotation.rotate(Vec3.Y)
        assertTrue(up.y > .995f, "le bateau s'est redressé : $up")
        assertTrue(b.submergedFraction in .05f..1f, "il flotte : ${b.submergedFraction}")
        assertTrue(b.position.y > -1f, "il n'a pas coulé : ${b.position}")
    }

    @Test fun leCourantEmporteCeQuiFlotte() {
        val w = pool(); w.waterList[0].flow = Vec3(1.5f, 0f, 0f)
        val b = w.add(Box(.3f, .3f, .3f), Vec3(0f, .5f, 0f), density = 400f)
        run(w, 8f)
        assertEquals(1.5f, b.velocity.x, .05f, "à la vitesse du courant")
        assertEquals(0f, b.velocity.z, .01f)
    }

    @Test fun horsDesBordsLEauNePortePas() {
        val w = PhysicsWorld()
        w.addMesh(TriangleMesh(floatArrayOf(-50f, -5f, -50f, -50f, -5f, 50f, 50f, -5f, 50f, -50f, -5f, -50f, 50f, -5f, 50f, 50f, -5f, -50f)))
        w.addWater(0f, -2f, -2f, 2f, 2f)
        val inside = w.add(Sphere(.3f), Vec3(0f, 1f, 0f), density = 500f)
        val outside = w.add(Sphere(.3f), Vec3(5f, 1f, 0f), density = 500f)
        run(w, 6f)
        assertEquals(0f, inside.position.y, .01f); assertEquals(-4.7f, outside.position.y, .01f)
        assertEquals(0f, outside.submergedFraction)
    }

    @Test fun uneBalleQuiTombeEntreUneFoisDansLEauASaVitesse() {
        val w = pool()
        val entries = ArrayList<WaterEntry>(); var exits = 0
        w.waterList[0].onEnter = { entries.add(it) }; w.waterList[0].onExit = { exits++ }
        val b = w.add(Sphere(.1f), Vec3(1f, 2.1f, -2f), density = 300f)
        run(w, 6f)
        assertEquals(1, entries.size, "une seule entrée (la balle flotte ensuite sans ressortir)")
        val e = entries[0]
        assertEquals(b, e.body)
        assertEquals(-sqrt(2 * 9.81f * 2f), e.verticalSpeed, .15f, "vitesse en touchant l'eau")
        assertEquals(1f, e.point.x, .02f); assertEquals(-2f, e.point.z, .02f); assertEquals(0f, e.point.y)
        assertEquals(0, exits)
        assertTrue(w.waterEntries.isEmpty(), "les entrées ne valent que pour leur pas")
        // Thrown up out of the water: it leaves, then comes back in.
        b.velocity = Vec3(0f, 8f, 0f)
        run(w, 4f)
        assertEquals(1, exits); assertEquals(2, entries.size)
    }

    @Test fun lEauEstDeterministeEtNeCreePasDEnergie() {
        fun scene(): List<Int> {
            val w = pool()
            val shapes = listOf(Box(.3f, .2f, .4f), Sphere(.25f), Capsule(.15f, .3f), ConvexHull((0 until 20).map { k -> Vec3(cos(k * 2.4f), sin(k * 1.3f) * .6f, sin(k * 2.4f)) * .3f }),
                Compound(Compound.Part(Box(.3f, .05f, .3f)), Compound.Part(Sphere(.12f), Vec3(0f, .2f, 0f))))
            val bodies = (0 until 30).map { k -> w.add(shapes[k % 5], Vec3((k % 6) * 1f - 3f, 1f + k * .3f, (k / 6) * 1f - 2f), Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), k * .4f), density = 200f + 60f * k) }
            run(w, 6f)
            return bodies.flatMap { listOf(it.position.x, it.position.y, it.position.z, it.rotation.w).map { f -> f.toRawBits() } }
        }
        assertEquals(scene(), scene())
        // Energy: a floating body let go from its waterline with no damping only oscillates (the implicit buoyancy loses a little).
        val w = pool(); w.waterList[0].linearDrag = 0f; w.waterList[0].angularDrag = 0f; w.waterList[0].waveDamping = 0f; w.waterList[0].dragCoefficient = 0f
        val b = w.add(Box(.5f, .5f, .5f), Vec3(0f, .2f, 0f), density = 500f)
        var top = -1f; var bottom = 1f
        run(w, 4f) { top = max(top, b.position.y); bottom = min(bottom, b.position.y) }
        assertTrue(top <= .2f + .002f, "jamais plus haut qu'au départ : $top")
        assertTrue(bottom < -.15f, "il oscille autour de sa ligne de flottaison : $bottom")
    }

    @Test fun uneEnveloppeFlotteSelonSaDensite() {
        for (density in floatArrayOf(200f, 500f, 800f)) {
            val w = pool()
            val h = ConvexHull((0 until 40).map { k -> val a = k * 2.399963f; val y = 1f - 2f * (k + .5f) / 40; Vec3(cos(a) * sqrt(1 - y * y) * .5f, y * .3f, sin(a) * sqrt(1 - y * y) * .4f) })
            val b = w.add(h, Vec3(0f, 1f, 0f), Quat.axisAngle(Vec3(1f, 0f, 1f).normalized(), .5f), density = density)
            settle(w, b, 15f)
            assertEquals(density / 1000f, b.submergedFraction, .002f, "rocher de densité $density")
        }
    }
}
