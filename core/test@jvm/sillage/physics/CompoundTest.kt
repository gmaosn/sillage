package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import java.util.Random
import kotlin.math.*
import kotlin.test.*

/** Les corps composés : masses, centre et inertie réunis, collisions par partie, requêtes, personnage. */
class CompoundTest {
    private val dt = 1f / 60f
    private fun Random.f(a: Float, b: Float) = a + nextFloat() * (b - a)
    private fun Random.dir(): Vec3 { while (true) { val v = Vec3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)); val l = v.length(); if (l in .1f..1f) return v / l } }
    private fun Random.rot(): Quat = Quat(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)).normalized()
    private fun ground(w: PhysicsWorld, size: Float = 20f) =
        w.addMesh(TriangleMesh(floatArrayOf(-size, 0f, -size, -size, 0f, size, size, 0f, size, -size, 0f, -size, size, 0f, size, size, 0f, -size)))
    private fun run(w: PhysicsWorld, seconds: Float, each: () -> Unit = {}) = repeat((seconds / dt).roundToInt()) { w.step(dt); each() }

    /** A table: a top (1.2 × 0.06 × 0.8 m) on four legs (0.05 m square, 0.7 m high), in the frame of its feet. */
    private fun table() = Compound(
        listOf(Compound.Part(Box(.6f, .03f, .4f), Vec3(0f, .73f, 0f))) +
            listOf(-1f, 1f).flatMap { x -> listOf(-1f, 1f).map { z -> Compound.Part(Box(.025f, .35f, .025f), Vec3(x * .55f, .35f, z * .35f)) } })

    @Test fun deuxBoitesReunissentMasseCentreEtInertie() {
        // Two cubes of 1 m side, 2 m apart, the second twice as dense.
        val c = Compound(Compound.Part(Box(.5f, .5f, .5f), Vec3(0f, 0f, 0f)), Compound.Part(Box(.5f, .5f, .5f), Vec3(2f, 0f, 0f), density = 2f))
        assertEquals(2f, c.volume, 1e-5f); assertEquals(3f, c.massVolume, 1e-5f)
        assertEquals(Vec3(4f / 3, 0f, 0f).x, c.centre.x, 1e-5f)
        val w = PhysicsWorld(gravity = Vec3.ZERO)
        val b = w.add(c, c.centre, density = 100f)
        assertEquals(300f, b.mass, 1e-3f)
        // Parallel axes: about x, each cube m/6; about y and z, plus m d² for each.
        val i = c.inertia(300f)
        assertEquals(Quat.IDENTITY, c.principalAxes)
        assertEquals(300f / 6, i.x, 1e-3f)
        val d1 = 4f / 3; val d2 = 2f / 3
        assertEquals(300f / 6 + 100f * d1 * d1 + 200f * d2 * d2, i.y, 1e-2f)
        assertEquals(i.y, i.z, 1e-3f)
        assertEquals(Vec3(-4f / 3, 0f, 0f).x, c.parts[0].position.x, 1e-5f)
    }

    @Test fun uneBarreEnDiagonaleTrouveSesAxesPrincipaux() {
        // Two spheres on a tilted line: the long axis is turned, so is the inertia.
        val d = Vec3(1f, 1f, 0f).normalized()
        val c = Compound(Compound.Part(Sphere(.2f), d * -.8f), Compound.Part(Sphere(.2f), d * .8f))
        val axis = c.principalAxes
        val i = c.inertia(2f)
        // The smallest moment is about the bar (two spheres' own, 2/5 m r² each).
        val k = (0..2).minBy { i[it] }
        val along = axis.rotate(listOf(Vec3.X, Vec3.Y, Vec3.Z)[k])
        assertTrue(abs(along dot d) > .9999f, "axe de la barre : $along")
        assertEquals(2f * .4f * .04f, i[k], 1e-4f)
        assertEquals(2f * .4f * .04f + 2f * .64f, i[(k + 1) % 3], 1e-3f)
    }

    @Test fun lesPartiesSontVerifiees() {
        assertFailsWith<IllegalArgumentException> { Compound(emptyList()) }
        val inner = Compound(Compound.Part(Sphere(.1f)))
        assertContains(assertFailsWith<IllegalArgumentException> { Compound(Compound.Part(inner)) }.message!!, "cannot be compounds")
        assertFailsWith<IllegalArgumentException> { Compound.Part(Sphere(.1f), density = 0f) }
    }

    @Test fun uneTableSePoseSurSesQuatrePiedsEtSEndort() {
        val w = PhysicsWorld(); ground(w)
        val t = table()
        val b = w.add(t, Vec3(0f, .2f, 0f) + t.centre, Quat.axisAngle(Vec3(1f, 0f, .3f).normalized(), .1f), density = 600f)
        w.timeToSleep = 1.5f
        run(w, 1.2f)
        // The four legs carry it, settled (before it sleeps: a sleeping body keeps no contacts).
        val legs = w.contacts().filter { it.a == null && it.normalImpulse > 0f }.map { it.point }.map { p -> Pair(p.x > 0f, p.z > 0f) }.toSet()
        assertEquals(4, legs.size, "les quatre pieds portent")
        run(w, 3.4f)
        val feet = b.position - b.rotation.rotate(t.centre)
        assertEquals(0f, feet.y, .01f, "les pieds au sol")
        assertTrue(b.rotation.rotate(Vec3.Y).y > .9995f, "la table est droite")
        assertTrue(b.isSleeping)
    }

    /** Four legs on a table each brought four points (sixteen springs over four feet): a stack of tables shivered. Now one point a foot. */
    @Test fun desTablesEmpileesTiennent() {
        val w = PhysicsWorld(); ground(w)
        val t = table()
        val tables = (0 until 4).map { k -> w.add(t, Vec3(0f, k * .77f, 0f) + t.centre, Quat.rotationY(k * .1f), density = 600f) }
        run(w, 6f)
        val top = tables.last().position - tables.last().rotation.rotate(t.centre)
        assertTrue(hypot(top.x, top.z) < .02f, "le haut de la pile a glissé : $top")
        assertTrue(tables.all { it.isSleeping })
    }

    @Test fun unRayonTouchePartieParPartie() {
        val w = PhysicsWorld(gravity = Vec3.ZERO)
        val t = table()
        w.add(t, t.centre, type = BodyType.STATIC)
        // Through the gap between the legs, then onto a leg, then onto the top.
        assertNull(w.raycast(Vec3(0f, .3f, -3f), Vec3(0f, 0f, 1f), 6f), "entre les pieds")
        assertEquals(3f - .375f, w.raycast(Vec3(.55f, .3f, -3f), Vec3(0f, 0f, 1f), 6f)!!.distance, 1e-4f)
        val top = w.raycast(Vec3(.1f, 3f, .1f), Vec3(0f, -1f, 0f), 6f)!!
        assertEquals(3f - .76f, top.distance, 1e-4f); assertEquals(1f, top.normal.y, 1e-5f)
        assertEquals(3f - .76f - .1f, w.raycast(Vec3(.1f, 3f, .1f), Vec3(0f, -1f, 0f), 6f, radius = .1f)!!.distance, 1e-4f)
    }

    @Test fun unPersonnageMonteSurUneTableBasseEtButeSurSesPieds() {
        val w = PhysicsWorld(); ground(w)
        // A low platform on legs (a stage, 30 cm high) and a table to walk into.
        val stage = Compound(Compound.Part(Box(1.5f, .05f, 1.5f), Vec3(0f, .25f, 0f)),
            *listOf(-1f, 1f).flatMap { x -> listOf(-1f, 1f).map { z -> Compound.Part(Box(.05f, .1f, .05f), Vec3(x * 1.4f, .1f, z * 1.4f)) } }.toTypedArray())
        w.add(stage, Vec3(0f, 0f, -4f) + stage.centre, type = BodyType.STATIC)
        val c = Character(w, Vec3(0f, 0f, -1.5f))
        repeat(60) { w.step(dt); c.update(Vec3(0f, 0f, -2f), 0f, dt) }
        assertTrue(c.onGround && abs(c.position.y - .3f) < .02f, "debout sur l'estrade : ${c.position}")
        // Under a table the character is blocked by its top (0.76 m, the character is 1.8 m).
        val t = table()
        w.add(t, Vec3(4f, 0f, 0f) + t.centre, type = BodyType.STATIC)
        c.teleport(Vec3(2f, 0f, 0f))
        repeat(120) { w.step(dt); c.update(Vec3(2f, 0f, 0f), 0f, dt) }
        assertTrue(c.position.x < 4f - .6f - .28f + .02f, "le plateau arrête le personnage : ${c.position}")
    }

    @Test fun unCorpsComposeEstVuParUneZone() {
        val w = PhysicsWorld(); ground(w)
        val zone = w.addTrigger(Box(.3f, .3f, .3f), Vec3(.55f, .3f, .35f))     // around one leg only
        var entered = 0; zone.onEnter = { entered++ }
        val t = table()
        w.add(t, Vec3(0f, 1f, 0f) + t.centre, density = 600f)
        run(w, 2f)
        assertEquals(1, entered)
    }

    @Test fun unHalteresRapideNeTraversePasUneDalle() {
        val w = PhysicsWorld()
        w.add(Box(3f, .02f, 3f), Vec3.ZERO, type = BodyType.STATIC)
        val c = Compound(Compound.Part(Sphere(.08f), Vec3(-.3f, 0f, 0f)), Compound.Part(Capsule(.03f, .25f), Vec3.ZERO, Quat.axisAngle(Vec3.Z, PI.toFloat() / 2)),
            Compound.Part(Sphere(.08f), Vec3(.3f, 0f, 0f)))
        val b = w.add(c, Vec3(0f, 5f, 0f)); b.velocity = Vec3(0f, -150f, 0f)
        run(w, 1f)
        assertTrue(b.position.y > 0f, "l'haltère a traversé : ${b.position}")
    }

    @Test fun lesContactsDesCorpsComposesNeChangentPas() {
        val rnd = Random(23); val out = ContactPoints(); var h = 1125899906842597L
        fun add(v: Int) { h = 31 * h + v }
        val hull = ConvexHull((0 until 10).map { rnd.dir() * rnd.f(.2f, .6f) })
        val shapes = listOf(table(), Compound(Compound.Part(Box(.4f, .1f, .4f)), Compound.Part(hull, Vec3(0f, .5f, 0f)), Compound.Part(Sphere(.2f), Vec3(.3f, -.4f, 0f))),
            Compound(Compound.Part(Capsule(.1f, .4f), Vec3(0f, 0f, .2f), Quat.axisAngle(Vec3.X, 1.2f)), Compound.Part(Sphere(.25f), Vec3(.3f, .1f, 0f), density = 3f)))
        fun other(): Shape = when (rnd.nextInt(5)) { 0 -> Sphere(rnd.f(.1f, 1f)); 1 -> Capsule(rnd.f(.1f, .6f), rnd.f(0f, 1f)); 2 -> Box(rnd.f(.05f, 1f), rnd.f(.05f, 1f), rnd.f(.05f, 1f)); 3 -> hull; else -> shapes[rnd.nextInt(3)] }
        var touching = 0
        repeat(8000) {
            val a = shapes[rnd.nextInt(3)]; val b = other()
            val qa = rnd.rot(); val qb = rnd.rot()
            val pa = Vec3(rnd.f(-3f, 3f), rnd.f(-3f, 3f), rnd.f(-3f, 3f))
            val pb = pa + rnd.dir() * ((a.boundingRadius + b.boundingRadius) * rnd.f(.05f, 1.05f))
            out.clear()
            if (rnd.nextBoolean()) collide(a, Pose().set(pa.x, pa.y, pa.z, qa.x, qa.y, qa.z, qa.w), b, Pose().set(pb.x, pb.y, pb.z, qb.x, qb.y, qb.z, qb.w), rnd.f(0f, .3f), out)
            else collide(b, Pose().set(pb.x, pb.y, pb.z, qb.x, qb.y, qb.z, qb.w), a, Pose().set(pa.x, pa.y, pa.z, qa.x, qa.y, qa.z, qa.w), rnd.f(0f, .3f), out)
            add(out.count)
            for (i in 0 until out.count) { add(out.x[i].toRawBits()); add(out.y[i].toRawBits()); add(out.z[i].toRawBits()); add(out.nx[i].toRawBits()); add(out.ny[i].toRawBits()); add(out.nz[i].toRawBits()); add(out.sep[i].toRawBits()); add(out.id[i]) }
            if (out.count > 0) touching++
        }
        val v = ArrayList<Float>()
        for (i in 0 until 5) for (j in 0 until 5) {
            fun p(a: Int, b: Int) = listOf(a - 2.5f, ((a * 7 + b * 3) % 5) * .1f, b - 2.5f)
            v += p(i, j); v += p(i, j + 1); v += p(i + 1, j + 1); v += p(i, j); v += p(i + 1, j + 1); v += p(i + 1, j)
        }
        val mesh = TriangleMesh(v.toFloatArray())
        repeat(4000) {
            val s = shapes[rnd.nextInt(3)]; val q = rnd.rot(); val t = rnd.nextInt(mesh.count)
            val c = Vec3((mesh.v[9 * t] + mesh.v[9 * t + 3] + mesh.v[9 * t + 6]) / 3, (mesh.v[9 * t + 1] + mesh.v[9 * t + 4] + mesh.v[9 * t + 7]) / 3, (mesh.v[9 * t + 2] + mesh.v[9 * t + 5] + mesh.v[9 * t + 8]) / 3)
            val p = c + rnd.dir() * (s.boundingRadius * rnd.f(0f, 1.2f))
            out.clear()
            collideTriangle(mesh, t, s, Pose().set(p.x, p.y, p.z, q.x, q.y, q.z, q.w), rnd.f(0f, .3f), out)
            add(out.count)
            for (i in 0 until out.count) { add(out.x[i].toRawBits()); add(out.y[i].toRawBits()); add(out.z[i].toRawBits()); add(out.nx[i].toRawBits()); add(out.ny[i].toRawBits()); add(out.nz[i].toRawBits()); add(out.sep[i].toRawBits()); add(out.id[i]) }
        }
        assertTrue(touching > 2500, "trop peu de contacts : $touching")
        println("EMPREINTE composés $h")
        assertEquals(COMPOUND_FINGERPRINT, h, "les contacts des corps composés ont changé")
    }

    @Test fun desTablesEnTasSontDeterministesAvecEtSansFils() {
        fun heap(parallel: Boolean): PhysicsWorld {
            val w = PhysicsWorld(); w.parallel = parallel; ground(w, 40f)
            val t = table()
            val dumbbell = Compound(Compound.Part(Sphere(.12f), Vec3(-.25f, 0f, 0f)), Compound.Part(Box(.2f, .03f, .03f)), Compound.Part(Sphere(.12f), Vec3(.25f, 0f, 0f)))
            for (i in 0 until 500) {
                val p = Vec3((i % 10) * 1.3f - 6f, .6f + (i / 100) * 1f, ((i / 10) % 10) * 1.3f - 6f)
                w.add(if (i % 3 == 0) t else dumbbell, p, Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), i * .3f), density = 500f)
            }
            return w
        }
        val a = heap(false); val b = heap(true)
        var most = 0
        repeat(90) { a.step(dt); b.step(dt); most = max(most, a.contactCount) }
        assertTrue(most > 1024, "trop peu de contacts pour les fils : $most")
        for (i in 0 until a.bodyCount) {
            assertEquals(a.body(i).position, b.body(i).position, "corps $i")
            assertEquals(a.body(i).rotation, b.body(i).rotation, "corps $i")
        }
    }
}

/** Fingerprint of [CompoundTest.lesContactsDesCorpsComposesNeChangentPas]. */
private const val COMPOUND_FINGERPRINT = -6631759607180827762L
