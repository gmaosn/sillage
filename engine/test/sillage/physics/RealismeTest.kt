package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.*
import kotlin.test.*

/**
 * Le moteur comparé à la physique du manuel, chiffres à l'appui. Chaque cas imprime
 * « REALISME cas : mesuré / théorie (écart) » pour le tableau du rapport.
 */
class RealismeTest {
    private val dt = 1f / 60f
    private val g = 9.81f

    private fun report(name: String, measured: Float, theory: Float): Float {
        val err = abs(measured - theory) / abs(theory)
        println("REALISME $name : ${"%.4f".format(measured)} / ${"%.4f".format(theory)} (${"%.1f".format(err * 100)} %)")
        return err
    }

    private fun ground(w: PhysicsWorld, friction: Float = .6f) = w.addMesh(TriangleMesh(floatArrayOf(
        -50f, 0f, -50f, -50f, 0f, 50f, 50f, 0f, 50f, -50f, 0f, -50f, 50f, 0f, 50f, 50f, 0f, -50f)), friction)

    private fun slope(w: PhysicsWorld, degrees: Float, friction: Float = 1f): Quat {
        val q = Quat.axisAngle(Vec3.X, Math.toRadians(degrees.toDouble()).toFloat())
        val c = listOf(Vec3(-30f, 0f, -30f), Vec3(-30f, 0f, 30f), Vec3(30f, 0f, 30f), Vec3(30f, 0f, -30f)).map { q.rotate(it) }
        w.addMesh(TriangleMesh(floatArrayOf(c[0].x, c[0].y, c[0].z, c[1].x, c[1].y, c[1].z, c[2].x, c[2].y, c[2].z,
            c[0].x, c[0].y, c[0].z, c[2].x, c[2].y, c[2].z, c[3].x, c[3].y, c[3].z)), friction)
        return q
    }

    @Test fun chuteLibre() {
        val w = PhysicsWorld()
        val s = w.add(Sphere(.2f), Vec3(0f, 20f, 0f))
        repeat(60) { w.step(dt) }
        assertTrue(report("chute libre, hauteur après 1 s (m)", s.position.y, 20f - g / 2) < .015f)
    }

    private fun stopDistance(direction: Vec3, mu: Float): Float {
        val w = PhysicsWorld(); ground(w, mu)          // même μ des deux côtés : √(μ·μ) = μ
        val b = w.add(Box(.5f, .5f, .5f), Vec3(0f, .5f, 0f), friction = mu)
        repeat(10) { w.step(dt) }
        b.velocity = direction.normalized() * 5f
        val start = b.position
        repeat(240) { w.step(dt) }
        return hypot(b.position.x - start.x, b.position.z - start.z)
    }

    @Test fun glissementFreineEtIsotropie() {
        val theory = 25f / (2f * .5f * g)
        val alongAxis = stopDistance(Vec3.X, .5f)
        val diagonal = stopDistance(Vec3(1f, 0f, 1f), .5f)
        val e1 = report("glissement μ=0,5 à 5 m/s le long d'un axe, distance (m)", alongAxis, theory)
        val e2 = report("même glissement en diagonale, distance (m)", diagonal, theory)
        assertTrue(e1 < .05f && e2 < .05f, "distances $alongAxis et $diagonal pour $theory")
    }

    @Test fun planIncline() {
        val w = PhysicsWorld(); val q = slope(w, 30f, .3f)
        val b = w.add(Box(.4f, .4f, .4f), q.rotate(Vec3(0f, .4f, 0f)), q, friction = .3f)
        repeat(30) { w.step(dt) }
        val v0 = b.velocity.length()
        repeat(60) { w.step(dt) }
        val a = b.velocity.length() - v0
        val theory = g * (sin(PI / 6).toFloat() - .3f * cos(PI / 6).toFloat())
        assertTrue(report("plan incliné 30°, μ=0,3, accélération (m/s²)", a, theory) < .05f)
    }

    private fun slides(degrees: Float, mu: Float): Boolean {
        val w = PhysicsWorld(); val q = slope(w, degrees, mu)
        val b = w.add(Box(.4f, .4f, .4f), q.rotate(Vec3(0f, .4f, 0f)), q, friction = mu)
        repeat(30) { w.step(dt) }
        val p = b.position
        repeat(120) { w.step(dt) }
        return b.position.distanceTo(p) > .05f
    }

    @Test fun seuilDAdherence() {
        val critical = Math.toDegrees(atan(.5)).toFloat()
        println("REALISME seuil d'adhérence μ=0,5 : théorie ${"%.2f".format(critical)}°, tient à ${critical - 2}° : ${!slides(critical - 2f, .5f)}, glisse à ${critical + 2}° : ${slides(critical + 2f, .5f)}")
        assertFalse(slides(critical - 2f, .5f)); assertTrue(slides(critical + 2f, .5f))
    }

    @Test fun billeQuiRoule() {
        val w = PhysicsWorld(); val q = slope(w, 15f, 1f)
        val s = w.add(Sphere(.4f), q.rotate(Vec3(0f, .4f, 0f)), rollingResistance = 0f)
        repeat(30) { w.step(dt) }
        val v0 = s.velocity.length()
        repeat(60) { w.step(dt) }
        val theory = 5f / 7f * g * sin(Math.toRadians(15.0)).toFloat()
        assertTrue(report("boule pleine roulant sur 15°, accélération (m/s²)", s.velocity.length() - v0, theory) < .05f)
    }

    private fun tips(degrees: Float): Boolean {
        val w = PhysicsWorld(); val q = slope(w, degrees, 1f)
        val b = w.add(Box(.2f, .6f, .2f), q.rotate(Vec3(0f, .6f, 0f)), q, friction = 1f)
        repeat(180) { w.step(dt) }
        return (b.rotation.rotate(Vec3.Y) dot q.rotate(Vec3.Y)) < .9f
    }

    @Test fun basculementDUneCaisseHaute() {
        val critical = Math.toDegrees(atan(.2 / .6)).toFloat()
        println("REALISME basculement (0,4 × 1,2 m) : théorie ${"%.2f".format(critical)}°, debout à ${critical - 2}° : ${!tips(critical - 2f)}, tombe à ${critical + 3}° : ${tips(critical + 3f)}")
        assertFalse(tips(critical - 2f)); assertTrue(tips(critical + 3f))
    }

    @Test fun chocEntreMassesInegales() {
        val w = PhysicsWorld(Vec3.ZERO)
        val a = w.add(Sphere(.3f), Vec3(-2f, 0f, 0f), mass = 2f, restitution = 1f); a.velocity = Vec3(3f, 0f, 0f)
        val b = w.add(Sphere(.3f), Vec3(0f, 0f, 0f), mass = 1f, restitution = 1f)
        repeat(90) { w.step(dt) }
        val e1 = report("choc élastique 2 kg sur 1 kg, vitesse finale de la balle lourde (m/s)", a.velocity.x, 1f)
        val e2 = report("choc élastique 2 kg sur 1 kg, vitesse finale de la balle légère (m/s)", b.velocity.x, 4f)
        val energy = .5f * 2f * a.velocity.lengthSquared() + .5f * 1f * b.velocity.lengthSquared()
        val e3 = report("énergie cinétique après le choc (J)", energy, 9f)
        assertTrue(e1 < .03f && e2 < .03f && e3 < .03f)
    }

    private fun bounceApex(e: Float): Float {
        val w = PhysicsWorld(); ground(w)
        val s = w.add(Sphere(.5f), Vec3(0f, 2.5f, 0f), restitution = e)
        var touched = false; var apex = 0f
        repeat(150) { w.step(dt); if (s.velocity.y > 0f) touched = true; if (touched) apex = max(apex, s.position.y) }
        return apex - .5f
    }

    @Test fun rebonds() {
        val e1 = report("rebond e=1, hauteur reprise sur 2 m (m)", bounceApex(1f), 2f)
        val e2 = report("rebond e=0,5, hauteur reprise sur 2 m (m)", bounceApex(.5f), .5f)
        assertTrue(e1 < .05f && e2 < .05f)
    }

    @Test fun momentCinetiqueConserve() {
        val w = PhysicsWorld(Vec3.ZERO)
        val shape = Box(.2f, .5f, 1f)
        val b = w.add(shape, Vec3.ZERO, Quat.axisAngle(Vec3(1f, 1f, 0f).normalized(), .3f), mass = 10f)
        b.angularVelocity = Vec3(1f, 3f, .5f)
        val inertia = shape.inertia(10f)
        fun momentum(): Vec3 {
            val r = b.rotation; val wl = r.conjugate().rotate(b.angularVelocity)
            return r.rotate(Vec3(inertia.x * wl.x, inertia.y * wl.y, inertia.z * wl.z))
        }
        fun energy(): Float {
            val wl = b.rotation.conjugate().rotate(b.angularVelocity)
            return .5f * (inertia.x * wl.x * wl.x + inertia.y * wl.y * wl.y + inertia.z * wl.z * wl.z)
        }
        val l0 = momentum(); val e0 = energy()
        repeat(300) { w.step(dt) }
        val dl = (momentum() - l0).length() / l0.length()
        println("REALISME moment cinétique d'une boîte qui tourne seule, 5 s : écart ${"%.1f".format(dl * 100)} %, énergie ${"%.1f".format(abs(energy() - e0) / e0 * 100)} %")
        assertTrue(dl < .02f, "moment cinétique perdu : ${dl * 100} %")
    }

    private fun heavyOnLight(ratio: Float, substeps: Int): Pair<Float, Float> {
        val w = PhysicsWorld(); w.substeps = substeps; ground(w)
        w.add(Box(.25f, .25f, .25f), Vec3(0f, .25f, 0f), mass = 1f)
        val big = w.add(Box(.5f, .5f, .5f), Vec3(0f, 1.01f, 0f), mass = ratio)
        repeat(180) { w.step(dt) }
        return (1f - big.position.y) to hypot(big.position.x, big.position.z)
    }

    @Test fun rapportsDeMasses() {
        val (s10, d10) = heavyOnLight(10f, 4)
        println("REALISME 10 kg posés sur 1 kg (4 sous-pas) : enfoncement ${"%.1f".format(s10 * 1000)} mm, dérive ${"%.1f".format(d10 * 1000)} mm")
        assertTrue(s10 < .005f && d10 < .01f)
        // Au-delà de 1:10, la souplesse des contacts se voit : il faut plus de sous-pas pour ne pas dériver.
        val (s100, d100) = heavyOnLight(100f, 8)
        println("REALISME 100 kg posés sur 1 kg (8 sous-pas) : enfoncement ${"%.1f".format(s100 * 1000)} mm, dérive ${"%.1f".format(d100 * 1000)} mm")
        assertTrue(s100 < .04f && d100 < .01f)
    }

    // ------------------------------------------------------------------ surfaces

    private fun plane(w: PhysicsWorld, material: Material) =
        w.addMesh(TriangleMesh(floatArrayOf(-60f, 0f, -60f, -60f, 0f, 60f, 60f, 0f, 60f, -60f, 0f, -60f, 60f, 0f, 60f, 60f, 0f, -60f), material))

    /** A ball (r = 0.3 m, no rolling resistance of its own) launched rolling at [v] m/s: distance until it stops. */
    private fun rollDistance(material: Material, v: Float): Float {
        val w = PhysicsWorld(); plane(w, material)
        val ball = w.add(Sphere(.3f), Vec3(-55f, .3f, 0f), rollingResistance = 0f)
        repeat(5) { w.step(dt) }
        ball.velocity = Vec3(v, 0f, 0f); ball.angularVelocity = Vec3(0f, 0f, -v / .3f)
        val start = ball.position.x
        var steps = 0
        while (steps < 60 * 60 && (steps < 10 || ball.velocity.length() > .02f)) { w.step(dt); steps++ }
        return ball.position.x - start
    }

    @Test fun uneBalleRouleMoinsLoinDansLHerbeQueSurLaRoute() {
        fun theory(rr: Float, v: Float) = v * v / (2f * 5f / 7f * rr * g)
        val road = report("boule lancée à 3 m/s sur la route, distance (m)", rollDistance(Material.ROAD, 3f), theory(.015f, 3f))
        val grass = report("boule lancée à 3 m/s dans l'herbe, distance (m)", rollDistance(Material.GRASS, 3f), theory(.12f, 3f))
        val sand = report("boule lancée à 3 m/s dans le sable, distance (m)", rollDistance(Material.SAND, 3f), theory(.3f, 3f))
        assertTrue(road < .1f && grass < .1f && sand < .1f)
    }

    @Test fun uneBalleQuiQuitteLaRoutePourLHerbe() {
        val w = PhysicsWorld()
        // Route pour x < 0, herbe pour x > 0.
        w.addMesh(TriangleMesh(floatArrayOf(
            -60f, 0f, -60f, -60f, 0f, 60f, 0f, 0f, 60f, -60f, 0f, -60f, 0f, 0f, 60f, 0f, 0f, -60f,
            0f, 0f, -60f, 0f, 0f, 60f, 60f, 0f, 60f, 0f, 0f, -60f, 60f, 0f, 60f, 60f, 0f, -60f),
            intArrayOf(0, 0, 1, 1), listOf(Material.ROAD, Material.GRASS)))
        val ball = w.add(Sphere(.3f), Vec3(-5f, .3f, 0f), rollingResistance = 0f)
        repeat(5) { w.step(dt) }
        ball.velocity = Vec3(3f, 0f, 0f); ball.angularVelocity = Vec3(0f, 0f, -10f)
        repeat(60 * 15) { w.step(dt) }
        val vAtEdge = sqrt(9f - 2f * 5f / 7f * .015f * g * 5f)
        assertTrue(report("boule passant de la route à l'herbe, arrêt après la lisière (m)", ball.position.x, vAtEdge * vAtEdge / (2f * 5f / 7f * .12f * g)) < .1f)
    }

    @Test fun uneCaisseGlisseLoinSurLaGlace() {
        fun slide(material: Material): Float {
            val w = PhysicsWorld(); plane(w, material)
            val b = w.add(Box(.4f, .4f, .4f), Vec3(-50f, .4f, 0f), friction = .6f)
            repeat(10) { w.step(dt) }
            b.velocity = Vec3(3f, 0f, 0f)
            repeat(60 * 6) { w.step(dt) }
            return b.position.x + 50f
        }
        val ice = report("caisse lancée à 3 m/s sur la glace, distance (m)", slide(Material.ICE), 9f / (2f * sqrt(.03f * .6f) * g))
        val grass = report("caisse lancée à 3 m/s sur l'herbe, distance (m)", slide(Material.GRASS), 9f / (2f * sqrt(.7f * .6f) * g))
        assertTrue(ice < .05f && grass < .05f)
    }

    @Test fun lHerbeAmortitLesRebonds() {
        fun apex(material: Material): Float {
            val w = PhysicsWorld(); plane(w, material)
            val s = w.add(Sphere(.5f), Vec3(0f, 2.5f, 0f), restitution = .8f, rollingResistance = 0f)
            var touched = false; var top = 0f
            repeat(150) { w.step(dt); if (s.velocity.y > 0f) touched = true; if (touched) top = max(top, s.position.y) }
            return top - .5f
        }
        val stone = report("rebond e=0,8 sur la pierre, hauteur reprise sur 2 m (m)", apex(Material.STONE), 2f * .8f * .8f)
        val grass = report("rebond e=0,8 dans l'herbe (×0,6), hauteur reprise (m)", apex(Material.GRASS), 2f * .48f * .48f)
        assertTrue(stone < .06f && grass < .06f)
    }

    @Test fun lesNomsDeMateriauxDeBlenderDonnentDesSurfaces() {
        assertEquals(Material.GRASS, Material.named("herbe"))
        assertEquals(Material.SAND, Material.named("Sable.001"))
        assertEquals(Material.WOOD, Material.named("bois"))
        assertEquals(Material.ROAD, Material.named("Asphalt"))
        assertEquals(Material.DEFAULT, Material.named("lin"))
    }
}
