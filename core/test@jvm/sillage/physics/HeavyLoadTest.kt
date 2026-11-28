package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.*
import kotlin.test.*

/** Joints under heavy loads on light bodies: a suspended bridge that carries the character, chains holding heavy crates. */
class HeavyLoadTest {
    private val dt = 1f / 60f
    private fun gap(w: PhysicsWorld) = w.jointList.maxOf { (it.anchorA - it.anchorB).length() }

    /** A bridge of 10 planks of 5 kg, 0.5 m each, hinged end to end, between posts [span] apart; landings to walk from. */
    private fun bridge(w: PhysicsWorld, span: Float): List<Body> {
        w.addMesh(TriangleMesh(floatArrayOf(-30f, -3f, -30f, -30f, -3f, 30f, 30f, -3f, 30f, -30f, -3f, -30f, 30f, -3f, 30f, 30f, -3f, -30f)))
        w.add(Box(1f, 1f, 1f), Vec3(-span / 2 - 1.05f, -1.025f, 0f), type = BodyType.STATIC)
        w.add(Box(1f, 1f, 1f), Vec3(span / 2 + 1.05f, -1.025f, 0f), type = BodyType.STATIC)
        // Laid as a V of 2.5 m a side (flat when taut), left to hang in its own curve.
        val drop = sqrt(max(0f, 6.25f - span * span / 4f)); val slope = drop / (span / 2f)
        fun at(s: Float) = if (s <= 2.5f) Vec3(-span / 2 + s * span / 5f, -s * drop / 2.5f, 0f) else Vec3(span / 2 - (5f - s) * span / 5f, -(5f - s) * drop / 2.5f, 0f)
        val planks = (0 until 10).map { k -> w.add(Box(.25f, .025f, .4f), at(.25f + .5f * k), Quat.axisAngle(Vec3.Z, atan(if (k < 5) -slope else slope)), mass = 5f) }
        w.addHinge(null, planks[0], at(0f), Vec3.Z)
        for (k in 1 until 10) w.addHinge(planks[k - 1], planks[k], at(.5f * k), Vec3.Z)
        w.addHinge(null, planks[9], at(5f), Vec3.Z)
        return planks
    }

    /**
     * How deep the middle hinge of an inextensible bridge of 10 bars of 0.5 m and [plank] kg hangs between anchors [span]
     * apart, with [load] kg on that hinge: the funicular polygon (each bar's weight half on each of its hinges, the same
     * horizontal tension along it), its tension found by bisection on the span. An answer worked out apart from the engine.
     */
    private fun funicular(span: Double, plank: Double, load: Double): Double {
        val g = 9.81; val p = DoubleArray(10) { i -> if (i == 0) 0.0 else plank * g + if (i == 5) load * g else 0.0 }
        val total = p.sum()
        fun slopes(h: Double): DoubleArray { var c = 0.0; return DoubleArray(10) { k -> c += p[k]; -total / (2 * h) + c / h } }
        fun width(h: Double) = slopes(h).sumOf { .5 * cos(atan(it)) }
        var lo = 1e-3; var hi = 1e7
        repeat(200) { val mid = sqrt(lo * hi); if (width(mid) < span) lo = mid else hi = mid }
        val s = slopes(sqrt(lo * hi))
        return -(0 until 5).sumOf { .5 * sin(atan(s[it])) }
    }

    @Test fun unPontSuspenduPorteLeMarinSansSAffaisser() {
        val w = PhysicsWorld(); val planks = bridge(w, 4.8f)
        val middle = w.jointList[5]
        // Laid as a V, it would swing for a long while into its curve: damped meanwhile (nothing static changes).
        for (p in planks) { p.linearDamping = 2f; p.angularDamping = 2f }
        repeat(360) { w.step(dt) }
        val rest = -middle.anchorA.y
        val restIdeal = funicular(4.8, 5.0, 0.0)
        assertEquals(restIdeal, rest.toDouble(), .015, "le pont seul pend comme une chaîne inextensible")
        assertTrue(gap(w) < .001f, "charnières au repos : ${gap(w) * 1000} mm")
        // The character walks onto the bridge from the left landing and stops in its middle.
        val hero = Character(w, feet = Vec3(-3.1f, .03f, 0f))
        var worst = 0f; var fastest = 0f; var deepest = 0f
        for (s in 0 until 60 * 10) {
            w.step(dt)
            if (s >= 60 * 6) { worst = max(worst, gap(w)); fastest = max(fastest, planks.maxOf { it.velocity.length() }); deepest = max(deepest, -middle.anchorA.y) }
            hero.update(if (hero.position.x < 0f) Vec3(1.5f, 0f, 0f) else Vec3.ZERO, 0f, dt)
        }
        assertTrue(hero.onGround && abs(hero.position.x) < .1f, "le marin est au milieu du pont : ${hero.position}")
        val loaded = -middle.anchorA.y
        val loadedIdeal = funicular(4.8, 5.0, 70.0)
        // The drop under him is that of an inextensible bridge (its shape changes), not the joints giving way.
        assertEquals(loadedIdeal, loaded.toDouble(), .02, "sous le marin : ${loaded * 100} cm, ${loadedIdeal * 100} pour un pont qui ne s'allonge pas")
        assertTrue(deepest - loadedIdeal < .03, "au plus bas ${deepest * 100} cm")
        assertTrue(worst < .003f, "charnières ouvertes de ${worst * 1000} mm sous le marin")
        assertTrue(fastest < .6f, "planches calmes : ${fastest} m/s")
    }

    @Test fun unPontTenduPorteUneCaisseEtLeMarinSansExploser() {
        // Taut between its posts, the bridge cannot sag without its joints stretching: they must hold.
        val w = PhysicsWorld(); val planks = bridge(w, 5f)
        repeat(120) { w.step(dt) }
        assertTrue(-planks.minOf { it.position.y } < .02f, "seul, il reste droit : ${-planks.minOf { it.position.y } * 100} cm")
        val crate = w.add(Box(.2f, .2f, .2f), Vec3(.1f, .25f, 0f), mass = 70f)
        var worst = 0f; var deepest = 0f
        repeat(300) { s -> w.step(dt); if (s > 60) { worst = max(worst, gap(w)); deepest = max(deepest, -planks.minOf { it.position.y }) } }
        assertTrue(deepest < .02f, "une caisse de 70 kg au milieu : ${deepest * 100} cm (48 cm avant les arbres d'articulations)")
        assertTrue(worst < .002f, "charnières ouvertes de ${worst * 1000} mm")
        w.remove(crate)
        val hero = Character(w, feet = Vec3(.05f, .03f, 0f))
        var heroWorst = 0f; var heroDeepest = 0f
        repeat(300) { s -> w.step(dt); hero.update(Vec3.ZERO, 0f, dt); if (s > 60) { heroWorst = max(heroWorst, gap(w)); heroDeepest = max(heroDeepest, -planks.minOf { it.position.y }) } }
        assertTrue(planks.all { it.position.length().isFinite() && it.velocity.length() < 5f }, "rien n'explose")
        // The character (a kinematic capsule whose contacts have the last word) makes a taut bridge ring: it holds, no more.
        assertTrue(heroDeepest < .3f, "le marin au milieu : ${heroDeepest * 100} cm (48 cm avant)")
        assertTrue(heroWorst < .05f, "charnières ouvertes de ${heroWorst * 1000} mm sous le marin")
    }

    @Test fun uneChaineTientUneCaisseLourde() {
        for (load in floatArrayOf(50f, 200f, 500f, 2000f)) {
            val w = PhysicsWorld()
            val links = w.addChain(Vec3(0f, 5f, 0f), Vec3(0f, 3.5f, 0f), 10, radius = .03f)
            val crate = w.add(Box(.3f, .3f, .3f), Vec3(0f, 3.2f, 0f), mass = load)
            w.addBall(links.last(), crate, Vec3(0f, 3.5f, 0f))
            repeat(240) { w.step(dt) }
            assertTrue(3.2f - crate.position.y < .002f, "$load kg sur 7 kg de chaîne : la caisse descend de ${(3.2f - crate.position.y) * 1000} mm")
            assertTrue(gap(w) < .001f, "$load kg : maillons écartés de ${gap(w) * 1000} mm")
            // Pushed aside, it swings on its chain and the chain holds.
            crate.applyImpulse(Vec3(load * 3f, 0f, load * 1f))
            var worst = 0f
            repeat(300) { w.step(dt); worst = max(worst, gap(w)) }
            assertTrue(worst < .005f, "$load kg en balançant : maillons écartés de ${worst * 1000} mm")
            assertTrue(links.all { it.position.length().isFinite() })
        }
    }

    @Test fun lesArbresDArticulationsSontDeterministesAvecEtSansFils() {
        fun run(parallel: Boolean): List<Int> {
            val w = PhysicsWorld(); w.parallel = parallel
            val planks = bridge(w, 4.8f)
            for (k in 0 until 40) {
                val links = w.addChain(Vec3(-8f + k * .4f, 6f, 3f), Vec3(-8f + k * .4f, 4f, 3f), 8, radius = .04f)
                w.add(Box(.15f, .15f, .15f), Vec3(-8f + k * .4f, 3.8f, 3f), mass = 40f).also { w.addBall(links.last(), it, Vec3(-8f + k * .4f, 4f, 3f)) }
            }
            for (k in 0 until 600) w.add(Sphere(.1f), Vec3((k % 30) * .3f - 4.5f, 2f + (k / 30) * .3f, -2f))
            repeat(180) { w.step(dt) }
            planks[5].applyImpulse(Vec3(0f, -200f, 0f))
            repeat(60) { w.step(dt) }
            return (0 until w.bodyCount).flatMap { val b = w.body(it); listOf(b.position.x, b.position.y, b.position.z, b.rotation.w).map { f -> f.toRawBits() } }
        }
        assertEquals(run(false), run(true))
    }
}
