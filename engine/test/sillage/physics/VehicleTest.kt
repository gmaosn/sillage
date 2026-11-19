package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.*
import kotlin.test.*

/** Le véhicule à roues : posé, il tient à sa hauteur ; il accélère, freine, tourne, glisse, tient en pente. */
class VehicleTest {
    private val dt = 1f / 60f

    private fun world(slope: Float = 0f): PhysicsWorld = PhysicsWorld().also {
        // Un grand sol, incliné de [slope] radians autour de x (il monte vers +z).
        val s = 200f; val t = tan(slope)
        it.addMesh(TriangleMesh(floatArrayOf(-s, -s * t, -s, -s, s * t, s, s, s * t, s, -s, -s * t, -s, s, s * t, s, s, -s * t, -s)))
    }

    /** Une petite voiture : 1,8 × 0,5 × 4 m, 1 200 kg, roues de 35 cm. */
    private fun car(w: PhysicsWorld, at: Vec3 = Vec3(0f, 1.2f, 0f), rotation: Quat = Quat.IDENTITY): Vehicle {
        val body = w.add(Box(.9f, .25f, 2f), at, rotation, mass = 1200f)
        return Vehicle(w, body).also { v ->
            for (x in floatArrayOf(-.8f, .8f)) for (z in floatArrayOf(-1.3f, 1.3f))
                v.addWheel(Vec3(x, -.15f, z), radius = .35f, travel = .3f, steered = z > 0f)
        }
    }

    private fun run(w: PhysicsWorld, v: Vehicle, seconds: Float, each: () -> Unit = {}) =
        repeat((seconds / dt).roundToInt()) { v.update(dt); w.step(dt); each() }

    private fun heading(v: Vehicle) = v.chassis.rotation.rotate(Vec3.Z).let { atan2(it.x, it.z) }

    @Test fun poseeElleTientASaHauteurEtSEndort() {
        val w = world(); val v = car(w)
        run(w, v, 4f)
        assertEquals(4, v.wheelsOnGround)
        // Au repos, chaque ressort porte un quart du poids : x = g / (2π f)².
        val expected = 9.81f / (2 * PI.toFloat() * v.suspensionHertz).pow(2)
        for (wheel in v.wheels) assertEquals(expected, wheel.compression, .01f, "enfoncement d'une roue")
        assertTrue(v.chassis.rotation.rotate(Vec3.Y).y > .9999f, "la caisse penche")
        assertEquals(1200f * 9.81f, v.wheels.sumOf { it.load.toDouble() }.toFloat(), 1200f * 9.81f * .02f)
        run(w, v, 2f)
        assertTrue(v.chassis.isSleeping, "garée, elle devrait s'endormir")
    }

    @Test fun elleAccelereToutDroitPuisFreine() {
        val w = world(); val v = car(w)
        run(w, v, 1f)
        v.throttle = 1f
        run(w, v, 3f)
        val fast = v.speed
        assertTrue(fast > 11f, "trop lente après 3 s : $fast m/s")
        assertTrue(abs(v.chassis.position.x) < .1f && abs(heading(v)) < .01f, "elle dévie : ${v.chassis.position}, cap ${heading(v)}")
        assertTrue(v.wheels.all { abs(it.spinSpeed * it.radius - fast) < .3f }, "les roues roulent à sa vitesse")
        v.throttle = 0f; v.brake = 1f
        val from = v.chassis.position.z
        run(w, v, 4f)
        val distance = v.chassis.position.z - from
        // Freinage à 0,9 g : d = v² / (2 · 0,9 g).
        val ideal = fast * fast / (2 * .9f * 9.81f)
        assertTrue(abs(v.speed) < .05f, "elle devrait être arrêtée : ${v.speed}")
        assertTrue(distance < ideal * 1.25f, "freinage trop long : $distance m (idéal $ideal)")
    }

    @Test fun elleTourneDuCoteDemande() {
        for (side in floatArrayOf(1f, -1f)) {
            val w = world(); val v = car(w)
            run(w, v, 1f)
            v.throttle = .5f
            run(w, v, 2f)
            v.steering = side
            var turned = 0f; var last = heading(v); var lowest = 1f
            run(w, v, 6f) {
                val h = heading(v); var d = h - last
                if (d > PI) d -= 2 * PI.toFloat() else if (d < -PI) d += 2 * PI.toFloat()
                turned += d; last = h
                lowest = min(lowest, v.chassis.rotation.rotate(Vec3.Y).y)
            }
            // À droite (vers −x quand elle regarde +z) : le cap autour de y diminue.
            assertTrue(-side * turned > PI.toFloat() / 2, "côté $side : elle n'a tourné que de $turned rad")
            assertTrue(lowest > .9f, "elle verse en virage : $lowest")
        }
    }

    @Test fun enPenteLeFreinAMainLaRetient() {
        val w = world(slope = .17f)
        val v = car(w, Vec3(0f, 1.2f, 0f), Quat.axisAngle(Vec3.X, -.17f))
        v.handbrake = true
        run(w, v, 1f)
        val parked = v.chassis.position
        run(w, v, 3f)
        assertTrue((v.chassis.position - parked).length() < .05f, "le frein à main lâche : ${v.chassis.position - parked}")
        v.handbrake = false
        run(w, v, 3f)
        assertTrue(v.chassis.position.z < parked.z - 1f, "sans frein, elle descend la pente : ${v.chassis.position}")
    }

    @Test fun troppViteEnVirageElleGlisse() {
        val w = world(); val v = car(w)
        v.topSpeed = 40f
        run(w, v, 1f)
        v.throttle = 1f
        run(w, v, 6f)
        v.throttle = 0f; v.steering = 1f; v.maxSteer = .9f
        var slip = 0f
        run(w, v, 1f) { slip = max(slip, v.wheels.maxOf { it.slip }) }
        assertTrue(slip > .5f, "à ${v.speed} m/s braquée, les pneus devraient glisser : $slip")
        assertTrue(v.chassis.position.isFinite())
    }

    @Test fun ellePousseUneCaisse() {
        val w = world(); val v = car(w)
        val crate = w.add(Box(.4f, .4f, .4f), Vec3(0f, .4f, 6f), mass = 40f)
        run(w, v, 1f)
        v.throttle = .6f
        run(w, v, 4f)
        assertTrue(crate.position.z > 7f, "la caisse devrait être poussée : ${crate.position}")
    }
}
