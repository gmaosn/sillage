package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3
import java.util.Random
import kotlin.math.*
import kotlin.test.*

/** Les enveloppes convexes : construction, masses exactes, collisions, requêtes, piles, personnage. */
class HullTest {
    private val dt = 1f / 60f
    private fun Random.f(a: Float, b: Float) = a + nextFloat() * (b - a)
    private fun Random.dir(): Vec3 { while (true) { val v = Vec3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)); val l = v.length(); if (l in .1f..1f) return v / l } }
    private fun Random.rot(): Quat = Quat(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)).normalized()

    private fun ground(w: PhysicsWorld, size: Float = 20f) =
        w.addMesh(TriangleMesh(floatArrayOf(-size, 0f, -size, -size, 0f, size, size, 0f, size, -size, 0f, -size, size, 0f, size, size, 0f, -size)))
    private fun run(w: PhysicsWorld, seconds: Float, each: () -> Unit = {}) = repeat((seconds / dt).roundToInt()) { w.step(dt); each() }

    /** The eight corners of a box of half extents (x, y, z), turned by [q] and moved by [c]. */
    private fun corners(x: Float, y: Float, z: Float, q: Quat = Quat.IDENTITY, c: Vec3 = Vec3.ZERO): List<Vec3> =
        (0 until 8).map { k -> c + q.rotate(Vec3(if (k and 1 == 0) -x else x, if (k and 2 == 0) -y else y, if (k and 4 == 0) -z else z)) }

    /** A crate with bevelled corners: 24 vertices, 26 faces. */
    private fun bevelled(h: Float, b: Float): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (k in 0 until 8) {
            val sx = if (k and 1 == 0) -1f else 1f; val sy = if (k and 2 == 0) -1f else 1f; val sz = if (k and 4 == 0) -1f else 1f
            out += Vec3(sx * h, sy * (h - b), sz * (h - b)); out += Vec3(sx * (h - b), sy * h, sz * (h - b)); out += Vec3(sx * (h - b), sy * (h - b), sz * h)
        }
        return out
    }

    // ---------------------------------------------------------------- construction

    @Test fun unCubeDonneHuitSommetsSixFacesEtSaMasseExacte() {
        val pts = corners(.5f, .5f, .5f) + listOf(Vec3(0f, 0f, 0f), Vec3(.5f, 0f, 0f), Vec3(.1f, .5f, .2f), Vec3(.5f, .5f, .5f), Vec3(0f, -.5f, .5f))
        val h = ConvexHull(pts.map { it + Vec3(3f, 1f, -2f) })
        assertEquals(8, h.vertexCount); assertEquals(6, h.faceCount); assertEquals(12, h.edgeCount)
        for (f in 0 until 6) assertEquals(4, h.face(f).size, "faces fusionnées en carrés")
        assertEquals(1f, h.volume, 1e-5f)
        assertEquals(Vec3(3f, 1f, -2f), h.centre)
        assertEquals(Quat.IDENTITY, h.principalAxes)
        val i = h.inertia(6f)                   // m s² / 6 for a cube of side s
        for (k in 0..2) assertEquals(1f, i[k], 1e-5f)
        assertEquals(sqrt(.75f), h.boundingRadius, 1e-5f)
        assertEquals(0f, h.simplificationError)
    }

    @Test fun unTetraedreRegulierATouteLaMasseAttendue() {
        val a = 1.3f
        val pts = listOf(Vec3(1f, 1f, 1f), Vec3(1f, -1f, -1f), Vec3(-1f, 1f, -1f), Vec3(-1f, -1f, 1f)).map { it * (a / (2 * sqrt(2f))) }
        val h = ConvexHull(pts)
        assertEquals(4, h.vertexCount); assertEquals(4, h.faceCount); assertEquals(6, h.edgeCount)
        assertEquals(a * a * a / (6 * sqrt(2f)), h.volume, 1e-5f)
        val i = h.inertia(10f)                  // m a² / 20 about every axis
        for (k in 0..2) assertEquals(10f * a * a / 20, i[k], 1e-4f)
    }

    @Test fun uneBoiteTourneeRetrouveSesAxesPrincipaux() {
        val rnd = Random(4)
        repeat(20) {
            val q = rnd.rot(); val hx = rnd.f(.1f, 1f); val hy = rnd.f(.1f, 1f); val hz = rnd.f(.1f, 1f)
            val h = ConvexHull(corners(hx, hy, hz, q, Vec3(rnd.f(-5f, 5f), 0f, 1f)))
            assertEquals(8 * hx * hy * hz, h.volume, 1e-4f)
            // The inertia tensor in the hull's frame, from its principal axes, against the turned box's.
            val m = 7f
            val p = h.principalAxes; val i = h.inertia(m)
            val box = Box(hx, hy, hz).inertia(m)
            for (u in listOf(Vec3.X, Vec3.Y, Vec3.Z)) for (v in listOf(Vec3.X, Vec3.Y, Vec3.Z)) {
                fun tensor(r: Quat, d: Vec3, a: Vec3, b: Vec3): Float {
                    val la = r.conjugate().rotate(a); val lb = r.conjugate().rotate(b)
                    return la.x * d.x * lb.x + la.y * d.y * lb.y + la.z * d.z * lb.z
                }
                assertEquals(tensor(q, box, u, v), tensor(p, i, u, v), 2e-4f * m, "tenseur d'inertie")
            }
        }
    }

    @Test fun lesSommetsLimitesGardentLesPointsQuiComptent() {
        // 400 points on a sphere: 32 kept, every other one within the reported error of the hull.
        val pts = (0 until 400).map { k ->
            val y = 1f - 2f * (k + .5f) / 400; val r = sqrt(1f - y * y); val a = k * 2.399963f
            Vec3(cos(a) * r, y, sin(a) * r)
        }
        val h = ConvexHull(pts, maxVertices = 32)
        assertEquals(32, h.vertexCount)
        assertTrue(h.simplificationError in 1e-3f..0.2f, "erreur ${h.simplificationError}")
        val outside = pts.maxOf { p -> (0 until h.faceCount).maxOf { f -> h.faceNormal(f) dot (p - h.centre - h.vertex(h.face(f)[0])) } }
        assertTrue(outside <= h.simplificationError + 1e-4f, "un point écarté à $outside m, au-delà de ${h.simplificationError}")
        val ball = 4f / 3 * PI.toFloat()
        assertTrue(ConvexHull(pts, 255).volume in .96f * ball..ball, "avec 255 sommets, presque la boule : ${ConvexHull(pts, 255).volume}")
        assertTrue(h.volume < ConvexHull(pts, 255).volume, "moins de sommets, moins de volume")
    }

    @Test fun desPointsAuHasardDonnentUnPolyedreConvexeBienForme() {
        val rnd = Random(9)
        repeat(30) {
            val pts = (0 until 5 + rnd.nextInt(120)).map { rnd.dir() * rnd.f(.2f, 1f) + Vec3(rnd.f(-1f, 1f), 0f, 0f) }
            val h = ConvexHull(pts, 255)
            assertEquals(2, h.vertexCount - h.edgeCount + h.faceCount, "Euler")
            for (f in 0 until h.faceCount) {
                val n = h.faceNormal(f); val d = n dot h.vertex(h.face(f)[0])
                for (vi in h.face(f)) assertEquals(d, n dot h.vertex(vi), 2e-4f, "face plane")
                for (vi in 0 until h.vertexCount) assertTrue((n dot h.vertex(vi)) <= d + 2e-4f, "convexe")
                for (p in pts) assertTrue((n dot (p - h.centre)) <= d + 2e-4f, "chaque point dedans")
            }
        }
    }

    @Test fun unCylindreGardeSesFacesPlanesEnUnSeulPolygone() {
        val pts = (0 until 32).map { k -> val a = 2 * PI.toFloat() * (k % 16) / 16; Vec3(cos(a) * .4f, if (k < 16) -.5f else .5f, sin(a) * .4f) }
        val h = ConvexHull(pts)
        assertEquals(32, h.vertexCount); assertEquals(18, h.faceCount)
        assertEquals(2, (0 until h.faceCount).count { h.face(it).size == 16 }, "deux disques")
    }

    @Test fun lesPointsAuMilieuDesAretesNeSontPasDesSommets() {
        val pts = corners(1f, 1f, 1f) + listOf(Vec3(0f, 1f, 1f), Vec3(1f, 0f, -1f), Vec3(-1f, -1f, .3f))
        val h = ConvexHull(pts)
        assertEquals(8, h.vertexCount); assertEquals(6, h.faceCount)
    }

    @Test fun lesEntreesDegenereesSontRefuseesClairement() {
        fun message(block: () -> Unit) = assertFailsWith<IllegalArgumentException> { block() }.message!!
        assertContains(message { ConvexHull(listOf(Vec3.ZERO, Vec3.X, Vec3.Y)) }, "at least 4 points")
        assertContains(message { ConvexHull(List(6) { Vec3(1f, 2f, 3f) }) }, "one place")
        assertContains(message { ConvexHull(List(6) { Vec3(it.toFloat(), 2f * it, 0f) }) }, "line")
        assertContains(message { ConvexHull(List(9) { Vec3((it % 3).toFloat(), 0f, (it / 3).toFloat()) }) }, "plane")
        assertContains(message { ConvexHull(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, Float.NaN)) }, "finite")
        assertContains(message { ConvexHull(corners(1f, 1f, 1f), maxVertices = 3) }, "4 to 255")
    }

    // ---------------------------------------------------------------- collisions

    private fun contacts(a: Shape, pa: Vec3, qa: Quat, b: Shape, pb: Vec3, qb: Quat, margin: Float = 0f): ContactPoints {
        val out = ContactPoints()
        collide(a, Pose().set(pa.x, pa.y, pa.z, qa.x, qa.y, qa.z, qa.w), b, Pose().set(pb.x, pb.y, pb.z, qb.x, qb.y, qb.z, qb.w), margin, out)
        return out
    }
    private fun ContactPoints.deepest() = (0 until count).minOfOrNull { sep[it] } ?: Float.NaN

    @Test fun uneSphereTouchantUneEnveloppeDeBoiteVoitLaMemeChoseQueContreLaBoite() {
        val rnd = Random(21)
        val hull = ConvexHull(corners(.6f, .3f, .4f)); val box = Box(.6f, .3f, .4f)
        var n = 0
        repeat(3000) {
            val q = rnd.rot(); val r = rnd.f(.05f, .6f)
            val p = rnd.dir() * rnd.f(0f, 1.3f)
            val a = contacts(Sphere(r), p, Quat.IDENTITY, box, Vec3.ZERO, q, .05f)
            val b = contacts(Sphere(r), p, Quat.IDENTITY, hull, Vec3.ZERO, q, .05f)
            assertEquals(a.count, b.count, "même nombre de points")
            if (a.count > 0) {
                n++
                assertEquals(a.sep[0], b.sep[0], 2e-5f, "séparation")
                assertEquals(a.nx[0], b.nx[0], 2e-3f); assertEquals(a.ny[0], b.ny[0], 2e-3f); assertEquals(a.nz[0], b.nz[0], 2e-3f)
            }
        }
        assertTrue(n > 1000, "trop peu de contacts : $n")
    }

    @Test fun uneEnveloppeDeBoiteSurUneBoiteDonneLesQuatreCoins() {
        // Resting flat, slightly turned: the same four points as box on box.
        val hull = ConvexHull(corners(.5f, .5f, .5f)); val box = Box(.5f, .5f, .5f)
        val q = Quat.rotationY(.3f)
        for (top in listOf<Shape>(hull, box)) {
            val c = contacts(Box(2f, .5f, 2f), Vec3.ZERO, Quat.IDENTITY, top, Vec3(.1f, .995f, 0f), q, .02f)
            assertEquals(4, c.count, "4 points pour ${top::class.simpleName}")
            for (k in 0 until 4) { assertEquals(-.005f, c.sep[k], 1e-5f); assertEquals(1f, c.ny[k], 1e-6f) }
        }
    }

    /**
     * A pointed rock resting on a floor (a large box, a mesh, a large hull) by one vertex: the depth of that
     * vertex is seen. Picking the incident face as the most opposed of all, 2 % of such rocks went through.
     */
    @Test fun uneEnveloppePoseeParUnSommetVoitSaProfondeur() {
        val rnd = Random(3)
        val floorHull = ConvexHull(corners(30f, .5f, 30f))
        val mesh = TriangleMesh(floatArrayOf(-30f, 0f, -30f, -30f, 0f, 30f, 30f, 0f, 30f, -30f, 0f, -30f, 30f, 0f, 30f, 30f, 0f, -30f))
        for (floor in 0..2) {
            var missed = 0
            repeat(1500) {
                val a = rnd.f(.1f, .6f); val b = rnd.f(.1f, .6f); val c = rnd.f(.1f, .6f)
                val h = ConvexHull((0 until 8 + rnd.nextInt(50)).map { val d = rnd.dir(); Vec3(d.x * a, d.y * b, d.z * c) * rnd.f(.7f, 1f) }, if (rnd.nextBoolean()) 16 else 32)
                val q = rnd.rot()
                val low = (0 until h.vertexCount).minOf { q.rotate(h.vertex(it)).y }
                val depth = rnd.f(0f, .03f)
                val p = Vec3(rnd.f(-2f, 2f), -low - depth, rnd.f(-2f, 2f))
                val out = ContactPoints(); val pose = Pose().set(p.x, p.y, p.z, q.x, q.y, q.z, q.w)
                when (floor) {
                    0 -> collide(Box(30f, .5f, 30f), Pose().set(0f, -.5f, 0f), h, pose, .02f, out)
                    1 -> collide(floorHull, Pose().set(0f, -.5f, 0f), h, pose, .02f, out)
                    else -> for (t in 0 until mesh.count) collideTriangle(mesh, t, h, pose, .02f, out)
                }
                val s = (0 until out.count).minOfOrNull { out.sep[it] } ?: Float.NaN
                if (!(s <= -depth + .001f)) missed++
            }
            assertEquals(0, missed, "sol ${listOf("boîte", "enveloppe", "maillage")[floor]} : sommets enfoncés non vus")
        }
    }

    @Test fun lesContactsDesEnveloppesNeChangentPas() {
        val rnd = Random(17); val out = ContactPoints(); var h = 1125899906842597L
        fun add(v: Int) { h = 31 * h + v }
        val hulls = List(6) { k -> ConvexHull((0 until 6 + 6 * k).map { rnd.dir() * rnd.f(.2f, .9f) }) } + ConvexHull(bevelled(.5f, .1f))
        fun shape(): Shape = when (rnd.nextInt(6)) {
            0 -> Sphere(rnd.f(.1f, 1f)); 1 -> Capsule(rnd.f(.1f, .6f), rnd.f(0f, 1f)); 2 -> Box(rnd.f(.05f, 1f), rnd.f(.05f, 1f), rnd.f(.05f, 1f))
            else -> hulls[rnd.nextInt(hulls.size)]
        }
        var touching = 0
        repeat(12_000) {
            val a = shape(); val b = if (rnd.nextBoolean()) hulls[rnd.nextInt(hulls.size)] else shape()
            val qa = rnd.rot(); val qb = rnd.rot()
            val pa = Vec3(rnd.f(-3f, 3f), rnd.f(-3f, 3f), rnd.f(-3f, 3f))
            val pb = pa + rnd.dir() * ((a.boundingRadius + b.boundingRadius) * rnd.f(.05f, 1.05f))
            out.clear()
            collide(a, Pose().set(pa.x, pa.y, pa.z, qa.x, qa.y, qa.z, qa.w), b, Pose().set(pb.x, pb.y, pb.z, qb.x, qb.y, qb.z, qb.w), rnd.f(0f, .3f), out)
            add(out.count)
            for (i in 0 until out.count) { add(out.x[i].toRawBits()); add(out.y[i].toRawBits()); add(out.z[i].toRawBits()); add(out.nx[i].toRawBits()); add(out.ny[i].toRawBits()); add(out.nz[i].toRawBits()); add(out.sep[i].toRawBits()); add(out.id[i]) }
            if (out.count > 0) touching++
        }
        // Triangles of a bumpy grid against hulls.
        val v = ArrayList<Float>()
        for (i in 0 until 5) for (j in 0 until 5) {
            fun p(a: Int, b: Int) = listOf(a - 2.5f, ((a * 7 + b * 3) % 5) * .1f, b - 2.5f)
            v += p(i, j); v += p(i, j + 1); v += p(i + 1, j + 1); v += p(i, j); v += p(i + 1, j + 1); v += p(i + 1, j)
        }
        val mesh = TriangleMesh(v.toFloatArray())
        repeat(6000) {
            val s: Shape = hulls[rnd.nextInt(hulls.size)]
            val q = rnd.rot(); val t = rnd.nextInt(mesh.count)
            val c = Vec3((mesh.v[9 * t] + mesh.v[9 * t + 3] + mesh.v[9 * t + 6]) / 3, (mesh.v[9 * t + 1] + mesh.v[9 * t + 4] + mesh.v[9 * t + 7]) / 3, (mesh.v[9 * t + 2] + mesh.v[9 * t + 5] + mesh.v[9 * t + 8]) / 3)
            val p = c + rnd.dir() * (s.boundingRadius * rnd.f(0f, 1.2f))
            out.clear()
            collideTriangle(mesh, t, s, Pose().set(p.x, p.y, p.z, q.x, q.y, q.z, q.w), rnd.f(0f, .3f), out)
            add(out.count)
            for (i in 0 until out.count) { add(out.x[i].toRawBits()); add(out.y[i].toRawBits()); add(out.z[i].toRawBits()); add(out.nx[i].toRawBits()); add(out.ny[i].toRawBits()); add(out.nz[i].toRawBits()); add(out.sep[i].toRawBits()); add(out.id[i]) }
        }
        assertTrue(touching > 4000, "trop peu de contacts : $touching")
        println("EMPREINTE enveloppes $h")
        assertEquals(HULL_FINGERPRINT, h, "les contacts des enveloppes ont changé")
    }

    // ---------------------------------------------------------------- queries

    @Test fun rayonsEtBalayagesContreUneEnveloppeCommeContreLaBoite() {
        val rnd = Random(31)
        val q = rnd.rot()
        val wb = PhysicsWorld(gravity = Vec3.ZERO); wb.add(Box(.6f, .3f, .4f), Vec3.ZERO, q, type = BodyType.STATIC)
        val wh = PhysicsWorld(gravity = Vec3.ZERO); wh.add(ConvexHull(corners(.6f, .3f, .4f)), Vec3.ZERO, q, type = BodyType.STATIC)
        var hits = 0
        repeat(4000) {
            val o = rnd.dir() * rnd.f(1.5f, 3f); val d = (rnd.dir() * .5f - o.normalized()).normalized()
            val r = if (rnd.nextBoolean()) 0f else rnd.f(.02f, .4f)
            val a = wb.raycast(o, d, 6f, radius = r); val b = wh.raycast(o, d, 6f, radius = r)
            assertEquals(a == null, b == null, "touché d'un seul côté (rayon $r)")
            if (a != null && b != null) {
                hits++
                assertEquals(a.distance, b.distance, 2e-4f, "distance")
                assertTrue((a.normal dot b.normal) > .999f, "normale ${a.normal} / ${b.normal}")
            }
        }
        assertTrue(hits > 600, "trop peu de touches : $hits")
        assertEquals(0f, wh.raycast(Vec3(.1f, 0f, 0f), Vec3.X, 5f)!!.distance, "un rayon parti de dedans s'arrête à 0")
    }

    // ---------------------------------------------------------------- bodies

    @Test fun uneEnveloppeDeBoiteSePoseCommeUneBoite() {
        val w = PhysicsWorld(); ground(w)
        val q = Quat.axisAngle(Vec3(1f, 0f, 1f).normalized(), .7f)
        val box = w.add(Box(.5f, .3f, .4f), Vec3(-2f, 1.5f, 0f), q)
        val hull = w.add(ConvexHull(corners(.5f, .3f, .4f)), Vec3(2f, 1.5f, 0f), q)
        run(w, 4f)
        assertEquals(box.position.y, hull.position.y, .01f, "même hauteur")
        assertEquals(box.position.x + 4f, hull.position.x, .05f); assertEquals(box.position.z, hull.position.z, .05f)
        val up = listOf(Vec3.X, Vec3.Y, Vec3.Z).maxOf { abs(hull.rotation.rotate(it).y) }
        assertTrue(up > .999f, "à plat sur une face : $up")
        assertTrue(hull.isSleeping)
    }

    @Test fun unePileDeHuitEnveloppesTientCommeUnePileDeCaisses() {
        for (shape in listOf<Shape>(Box(.5f, .5f, .5f), ConvexHull(corners(.5f, .5f, .5f)), ConvexHull(bevelled(.5f, .08f)))) {
            val w = PhysicsWorld(); ground(w)
            val bodies = (0 until 8).map { w.add(shape, Vec3(0f, .5f + it * 1.0f, 0f), Quat.rotationY(it * .2f)) }
            run(w, 6f)
            val top = bodies.last().position
            assertTrue(hypot(top.x, top.z) < .02f, "${shape::class.simpleName} : le haut de la pile a glissé : $top")
            assertEquals(7.5f, top.y, .05f)
            assertTrue(bodies.all { it.isSleeping }, "${shape::class.simpleName} : la pile devrait dormir")
        }
    }

    /**
     * A turned box given as a hull: its inertia is diagonal only about turned axes (its principal axes). It
     * spins exactly as the same box turned the same way does (the implicit gyroscopic step loses a few per
     * cent of a fast tumble in 5 s, the same for both).
     */
    @Test fun uneEnveloppeAsymetriqueTourneCommeLaBoiteQuElleEst() {
        val q = Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), .9f)
        val h = ConvexHull(corners(.6f, .2f, .35f, q))
        assertNotEquals(Quat.IDENTITY, h.principalAxes)
        val w = PhysicsWorld(gravity = Vec3.ZERO)
        val hull = w.add(h, Vec3.ZERO); val box = w.add(Box(.6f, .2f, .35f), Vec3(5f, 0f, 0f), q)
        for (b in listOf(hull, box)) b.angularVelocity = Vec3(2f, 5f, -1f)
        fun momentum(b: Body): Vec3 {
            val r = b.rotation * b.shape.principalAxes; val i = b.shape.inertia(b.mass); val l = r.conjugate().rotate(b.angularVelocity)
            return r.rotate(Vec3(i.x * l.x, i.y * l.y, i.z * l.z))
        }
        val l0 = momentum(hull)
        assertTrue((l0 - momentum(box)).length() < 1e-3f * l0.length(), "même moment cinétique au départ")
        run(w, 5f)
        assertTrue((momentum(hull) - momentum(box)).length() < .005f * l0.length(), "le même mouvement : ${momentum(hull)} / ${momentum(box)}")
        assertTrue((hull.angularVelocity - box.angularVelocity).length() < .01f * hull.angularVelocity.length(), "même rotation : ${hull.angularVelocity} / ${box.angularVelocity}")
    }

    @Test fun unePileDEnveloppesEstDeterministeAvecEtSansFils() {
        fun heap(parallel: Boolean): PhysicsWorld {
            val w = PhysicsWorld(); w.parallel = parallel; ground(w, 40f)
            val rock = ConvexHull((0 until 14).map { Random(it.toLong()).let { r -> r.dir() * r.f(.25f, .4f) } }, 16)
            val crate = ConvexHull(bevelled(.3f, .06f))
            for (i in 0 until 700) {
                val p = Vec3((i % 14) * .8f - 5.6f, .5f + (i / 196) * .8f, ((i / 14) % 14) * .8f - 5.6f)
                w.add(if (i % 2 == 0) rock else crate, p, Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), i * .3f))
            }
            return w
        }
        val a = heap(false); val b = heap(true)
        var most = 0
        repeat(90) { a.step(dt); b.step(dt); most = max(most, a.pairCount) }
        assertTrue(most > 512, "trop peu de paires pour les fils : $most")
        for (i in 0 until a.bodyCount) {
            assertEquals(a.body(i).position, b.body(i).position, "corps $i")
            assertEquals(a.body(i).rotation, b.body(i).rotation, "corps $i")
        }
    }

    @Test fun uneEnveloppeRapideNeTraversePasUnePlaqueMince() {
        val w = PhysicsWorld()
        w.add(Box(3f, .02f, 3f), Vec3.ZERO, type = BodyType.STATIC)
        val b = w.add(ConvexHull(bevelled(.1f, .02f)), Vec3(0f, 5f, 0f)); b.velocity = Vec3(0f, -200f, 0f)
        run(w, 1f)
        assertTrue(b.position.y > 0f, "l'enveloppe a traversé : ${b.position}")
    }

    @Test fun unPersonnageMarcheSurUneEnveloppePousseUneCaisseEtButeSurUnMur() {
        val w = PhysicsWorld(); ground(w)
        // A low block (a step of 30 cm) as a hull, a crate to push, a wall.
        w.add(ConvexHull(corners(1.5f, .15f, 1.5f)), Vec3(0f, .15f, -4f), type = BodyType.STATIC)
        val crate = w.add(ConvexHull(bevelled(.3f, .05f)), Vec3(3f, .3f, 0f), mass = 20f)
        w.add(ConvexHull(corners(.2f, 1.5f, 3f)), Vec3(-3f, 1.5f, 0f), type = BodyType.STATIC)
        val c = Character(w, Vec3(0f, 0f, -1.5f))
        repeat(60) { w.step(dt); c.update(Vec3(0f, 0f, -2f), 0f, dt) }
        assertTrue(c.onGround && abs(c.position.y - .3f) < .02f, "debout sur le bloc : ${c.position}")
        c.teleport(Vec3(1.5f, 0f, 0f))
        val x0 = crate.position.x
        repeat(90) { w.step(dt); c.update(Vec3(2f, 0f, 0f), 0f, dt) }
        assertTrue(crate.position.x > x0 + .5f, "la caisse avance : ${crate.position}")
        c.teleport(Vec3(-1.5f, 0f, 0f))
        repeat(120) { w.step(dt); c.update(Vec3(-3f, 0f, 0f), 0f, dt) }
        assertTrue(c.position.x > -2.8f - .01f, "le mur arrête le personnage : ${c.position}")
        assertTrue(c.position.x < -2.4f, "il va jusqu'au mur : ${c.position}")
    }

    @Test fun uneZoneVoitEntrerUneEnveloppe() {
        val w = PhysicsWorld(); ground(w)
        val zone = w.addTrigger(ConvexHull(corners(1f, .5f, 1f)), Vec3(0f, .5f, 0f))
        var entered = 0
        zone.onEnter = { entered++ }
        w.add(ConvexHull(bevelled(.2f, .04f)), Vec3(0f, 3f, 0f))
        run(w, 2f)
        assertEquals(1, entered); assertEquals(1, zone.inside.size)
    }
}

/** Fingerprint of [HullTest.lesContactsDesEnveloppesNeChangentPas]: hulls against every shape and triangles. */
private const val HULL_FINGERPRINT = 7242351510874302759L
