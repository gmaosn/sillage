package sillage.bench

import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Physics benches. Usage: bench [pyramid [rows]] [pile [count]] [determinism] [doors [count]] …
 * Times are wall-clock per 1/60 s step on this machine, after a warm-up.
 */
fun main(args: Array<String>) {
    val list = if (args.isEmpty()) listOf("pyramid", "pile", "determinism") else args.toList()
    var i = 0
    while (i < list.size) {
        val name = list[i]; val n = list.getOrNull(i + 1)?.toIntOrNull()
        when (name) {
            "pyramid" -> pyramid(n ?: 20)
            "pile" -> pile(n ?: 10_000)
            "determinism" -> determinism()
            "threads" -> threadsDeterminism(n ?: 6000)
            "settle" -> settle(n ?: 1000)
            "solver" -> solverCost(n ?: 3000)
            "doors" -> doors(n ?: 12)
            "ragdolls" -> ragdolls(n ?: 10)
            "hulls" -> hulls(n ?: 1000)
            "hullsettle" -> hullSettle(n ?: 300)
            "rockpit" -> rockPit(n ?: 70)
            else -> if (name.toIntOrNull() == null) error("Banc inconnu : $name")
        }
        i += if (n != null) 2 else 1
    }
}

private const val DT = 1f / 60f

private fun ground(w: PhysicsWorld, size: Float) =
    w.addMesh(TriangleMesh(floatArrayOf(-size, 0f, -size, -size, 0f, size, size, 0f, size, -size, 0f, -size, size, 0f, size, size, 0f, -size)))

private class Timing {
    val samples = ArrayList<Long>()
    fun report(label: String) {
        val s = samples.sorted()
        fun ms(ns: Long) = "%.2f".format(ns / 1e6)
        println("  $label : médiane ${ms(s[s.size / 2])} ms, p95 ${ms(s[(s.size * 95) / 100])} ms, max ${ms(s.last())} ms par pas (${s.size} pas)")
    }
}

private fun timed(w: PhysicsWorld, steps: Int, t: Timing? = null) = repeat(steps) {
    val t0 = System.nanoTime(); w.step(DT); t?.samples?.add(System.nanoTime() - t0)
}

/** 2D pyramid of boxes (Box2D's classic stacking test), one box deep. */
private fun pyramid(rows: Int) {
    println("Pyramide de $rows rangées (${rows * (rows + 1) / 2} caisses de 1 m) :")
    val w = PhysicsWorld(); ground(w, 50f)
    val boxes = ArrayList<Body>(); val start = ArrayList<Vec3>()
    for (r in 0 until rows) for (k in 0 until rows - r) {
        val p = Vec3(k - (rows - r - 1) / 2f, .5f + r, 0f)
        boxes.add(w.add(Box(.5f, .5f, .5f), p)); start.add(p)
    }
    val t = Timing()
    var asleepAt = -1f
    for (s in 0 until 60 * 20) {
        val t0 = System.nanoTime(); w.step(DT); if (s >= 60) t.samples.add(System.nanoTime() - t0)
        if (asleepAt < 0f && boxes.all { it.isSleeping }) asleepAt = (s + 1) * DT
    }
    var drift = 0f; var sink = 0f
    for ((i, b) in boxes.withIndex()) {
        drift = max(drift, hypot(b.position.x - start[i].x, b.position.z - start[i].z)); sink = max(sink, start[i].y - b.position.y)
    }
    println("  après 20 s : glissement max ${"%.1f".format(drift * 1000)} mm, enfoncement max ${"%.1f".format(sink * 1000)} mm, " +
        if (asleepAt > 0) "endormie à ${"%.2f".format(asleepAt)} s" else "pas endormie")
    t.report("temps")
}

/** [count] mixed bodies dropped into a walled pit. */
private fun pile(count: Int) {
    val side = 20; val layers = (count + side * side - 1) / (side * side)
    println("Tas de $count corps (boîtes, sphères, capsules) dans une fosse de 26 m, $layers couches :")
    val w = PhysicsWorld(); ground(w, 60f)
    for ((x, z, hx, hz) in listOf(arrayOf(-13f, 0f, .5f, 13.5f), arrayOf(13f, 0f, .5f, 13.5f), arrayOf(0f, -13f, 13.5f, .5f), arrayOf(0f, 13f, 13.5f, .5f)))
        w.add(Box(hx, 4f, hz), Vec3(x, 4f, z), type = BodyType.STATIC)
    var n = 0
    outer@ for (l in 0 until layers) for (i in 0 until side) for (j in 0 until side) {
        if (n == count) break@outer
        val p = Vec3((i - side / 2) * 1.2f + .3f * (l % 2), 1f + l * 1.2f, (j - side / 2) * 1.2f)
        when (n % 3) {
            0 -> w.add(Box(.4f, .3f, .35f), p, Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), n * .37f))
            1 -> w.add(Sphere(.35f), p)
            else -> w.add(Capsule(.22f, .3f), p, Quat.axisAngle(Vec3.X, n * .5f))
        }
        n++
    }
    val rt = Runtime.getRuntime()
    val warm = Timing(); val fall = Timing(); val rest = Timing()
    timed(w, 60, warm)
    timed(w, 60 * 4, fall)
    w.profiling = true
    var points = 0L; var pairs = 0L
    repeat(60 * 6) { val t0 = System.nanoTime(); w.step(DT); rest.samples.add(System.nanoTime() - t0); points += w.contactCount; pairs += w.pairCount }
    val names = listOf("préparation des corps", "paires", "contacts", "préparation des contacts", "démarrage à chaud", "résolution", "détente", "sommeil")
    println("  phases du tassement (moyenne par pas) : " + names.indices.joinToString { "${names[it]} ${"%.1f".format(w.phaseNanos[it] / 1e6 / 360)} ms" })
    val passes = 2.0 * w.substeps
    println("  par point de contact : résolution ${"%.0f".format(w.phaseNanos[5] / passes * 2 / points)} ns par passe, contacts ${"%.0f".format(w.phaseNanos[2].toDouble() / pairs)} ns par paire, paires ${"%.0f".format(w.phaseNanos[1].toDouble() / 360 / w.bodyCount)} ns par corps")
    println("  ${w.pairCount} paires, ${w.contactCount} points de contact")
    val asleep = (0 until w.bodyCount).count { w.body(it).isSleeping }
    val lowest = (0 until w.bodyCount).minOf { w.body(it).position.y }
    val escaped = (0 until w.bodyCount).count { val p = w.body(it).position; abs(p.x) > 13f || abs(p.z) > 13f }
    warm.report("première seconde (JIT)")
    fall.report("chute, 1 à 5 s")
    rest.report("tassement, 5 à 11 s")
    println("  à 11 s : $asleep / ${w.bodyCount} endormis, plus bas ${"%.3f".format(lowest)} m, $escaped hors de la fosse, " +
        "mémoire utilisée ${(rt.totalMemory() - rt.freeMemory()) / (1 shl 20)} Mo")
}

private fun determinism() {
    fun scene(): PhysicsWorld {
        val w = PhysicsWorld(); ground(w, 30f)
        for (i in 0 until 1000) {
            val p = Vec3((i % 10) * 1.1f - 5f, 1f + (i / 100) * 1.2f, ((i / 10) % 10) * 1.1f - 5f)
            when (i % 3) {
                0 -> w.add(Box(.4f, .3f, .35f), p, Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), i * .3f))
                1 -> w.add(Sphere(.35f), p)
                else -> w.add(Capsule(.25f, .3f), p, Quat.axisAngle(Vec3.X, i * .5f))
            }
        }
        return w
    }
    fun digest(w: PhysicsWorld): Long {
        var h = 1125899906842597L
        for (i in 0 until w.bodyCount) {
            val b = w.body(i)
            for (f in floatArrayOf(b.position.x, b.position.y, b.position.z, b.rotation.x, b.rotation.y, b.rotation.z, b.rotation.w))
                h = 31 * h + f.toRawBits()
        }
        return h
    }
    val a = scene(); val b = scene()
    repeat(600) { a.step(DT) }; repeat(600) { b.step(DT) }
    val da = digest(a); val db = digest(b)
    println("Déterminisme, 1000 corps, 10 s : ${if (da == db) "identique au bit près" else "DIFFÉRENT"} (empreinte ${java.lang.Long.toHexString(da)})")
}

/** Diagnostics: how a pile settles, second by second. */
private fun settle(count: Int) {
    val w = PhysicsWorld(); ground(w, 60f)
    for ((x, z, hx, hz) in listOf(arrayOf(-13f, 0f, .5f, 13.5f), arrayOf(13f, 0f, .5f, 13.5f), arrayOf(0f, -13f, 13.5f, .5f), arrayOf(0f, 13f, 13.5f, .5f)))
        w.add(Box(hx, 4f, hz), Vec3(x, 4f, z), type = BodyType.STATIC)
    val side = 20
    for (n in 0 until count) {
        val l = n / (side * side); val i = (n / side) % side; val j = n % side
        val p = Vec3((i - side / 2) * 1.2f + .3f * (l % 2), 1f + l * 1.2f, (j - side / 2) * 1.2f)
        when (n % 3) {
            0 -> w.add(Box(.4f, .3f, .35f), p, Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), n * .37f))
            1 -> w.add(Sphere(.35f), p)
            else -> w.add(Capsule(.22f, .3f), p, Quat.axisAngle(Vec3.X, n * .5f))
        }
    }
    for (sec in 1..15) {
        repeat(60) { w.step(DT) }
        val bodies = (0 until w.bodyCount).map { w.body(it) }.filter { it.type == BodyType.DYNAMIC }
        val v = bodies.maxOf { it.velocity.length() }; val om = bodies.maxOf { it.angularVelocity.length() }
        val fast = bodies.count { it.velocity.length() + it.shape.boundingRadius * it.angularVelocity.length() > w.sleepLinearSpeed }
        if (sec == 15) bodies.sortedByDescending { it.velocity.length() + it.shape.boundingRadius * it.angularVelocity.length() }.take(8).forEach {
            println("    ${it.shape::class.simpleName} p=${it.position} v=${"%.3f".format(it.velocity.length())} ω=${it.angularVelocity}")
        }
        println("  t=$sec s : ${bodies.count { it.isSleeping }} endormis, $fast au-dessus du seuil, v max ${"%.3f".format(v)} m/s, ω max ${"%.3f".format(om)} rad/s, ${w.pairCount} paires, ${w.contactCount} points")
    }
}

/** Cost of each phase per contact point, all bodies kept awake (sleep off), at a given heap size. */
private fun solverCost(count: Int) {
    val w = PhysicsWorld(); ground(w, 60f)
    w.timeToSleep = 1e9f
    w.parallel = System.getProperty("sillage.serial") == null
    val side = kotlin.math.sqrt(count / 3.0).toInt().coerceAtLeast(4)
    for ((x, z, hx, hz) in listOf(arrayOf(-side * .6f - .5f, 0f, .5f, side * .6f + 1f), arrayOf(side * .6f + .5f, 0f, .5f, side * .6f + 1f),
            arrayOf(0f, -side * .6f - .5f, side * .6f + 1f, .5f), arrayOf(0f, side * .6f + .5f, side * .6f + 1f, .5f)))
        w.add(Box(hx, 4f, hz), Vec3(x, 4f, z), type = BodyType.STATIC)
    for (n in 0 until count) {
        val l = n / (side * side); val i = (n / side) % side; val j = n % side
        val p = Vec3((i - side / 2) * 1.2f + .3f * (l % 2), 1f + l * 1.2f, (j - side / 2) * 1.2f)
        when (n % 3) {
            0 -> w.add(Box(.4f, .3f, .35f), p, Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), n * .37f))
            1 -> w.add(Sphere(.35f), p)
            else -> w.add(Capsule(.22f, .3f), p, Quat.axisAngle(Vec3.X, n * .5f))
        }
    }
    repeat(60 * 4) { w.step(DT) }
    w.profiling = true
    var points = 0L; var pairs = 0L; val steps = System.getProperty("sillage.steps")?.toInt() ?: 300
    val t0 = System.nanoTime()
    repeat(steps) { w.step(DT); points += w.contactCount; pairs += w.pairCount }
    val total = (System.nanoTime() - t0) / 1e6 / steps
    val ns = w.phaseNanos
    fun per(k: Int, n: Long, passes: Int = 1) = "%.0f".format(ns[k].toDouble() / passes / n)
    println("Solveur sur voies SIMD : " + w.lastStepVectorized + ", fils : " + w.parallel)
    println("Coût par élément, $count corps éveillés (${points / steps} points, ${pairs / steps} paires, ${"%.2f".format(total)} ms par pas) :")
    println("  paires ${per(1, steps.toLong() * w.bodyCount)} ns/corps, contacts ${per(2, pairs)} ns/paire, préparation ${per(3, points)} ns/point, " +
        "démarrage à chaud ${per(4, points, w.substeps)} ns/point, résolution ${per(5, points, w.substeps)} ns/point, détente ${per(6, points, w.substeps)} ns/point")
}

/** The same heap stepped without and with the worker threads must end bit for bit identical. */
private fun threadsDeterminism(count: Int) {
    fun scene(parallel: Boolean): PhysicsWorld {
        val w = PhysicsWorld(); ground(w, 60f); w.parallel = parallel
        val side = 30
        for (i in 0 until count) {
            val p = Vec3((i % side) * 1.1f - side * .55f, 1f + (i / (side * side)) * 1.2f, ((i / side) % side) * 1.1f - side * .55f)
            when (i % 3) {
                0 -> w.add(Box(.4f, .3f, .35f), p, Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), i * .3f))
                1 -> w.add(Sphere(.35f), p)
                else -> w.add(Capsule(.25f, .3f), p, Quat.axisAngle(Vec3.X, i * .5f))
            }
        }
        return w
    }
    fun digest(w: PhysicsWorld): Long {
        var h = 1125899906842597L
        for (i in 0 until w.bodyCount) {
            val b = w.body(i)
            for (f in floatArrayOf(b.position.x, b.position.y, b.position.z, b.rotation.x, b.rotation.y, b.rotation.z, b.rotation.w))
                h = 31 * h + f.toRawBits()
        }
        return h
    }
    val a = scene(false); val b = scene(true)
    for (s in 1..300) {
        a.step(DT); b.step(DT)
        if (digest(a) != digest(b)) { println("Fils : DIFFÉRENT dès le pas $s (${a.contactCount} et ${b.contactCount} points)"); return }
    }
    println("Fils : $count corps, 300 pas, identiques au bit près avec et sans fils (${a.contactCount} points), empreinte ${java.lang.Long.toHexString(digest(a))}")
}

/**
 * Diagnostics of hinge drift: doors with limits hit at random, as in qa's « portes à butées » check. Prints,
 * per door, the worst distance of the hinge from its anchor and the step where it happened (just after a
 * hit, at a stop, or later).
 */
private fun doors(count: Int) {
    val rnd = java.util.Random(901)
    var worst = 0f
    repeat(count) { k ->
        val w = PhysicsWorld()
        val door = w.add(Box(.45f, 1f, .03f), Vec3(.45f, 1f, 0f), density = 300f + rnd.nextFloat() * 600f)
        val low = -2.5f + rnd.nextFloat() * 2f; val high = .2f + rnd.nextFloat() * 1.8f
        val g = w.addHinge(null, door, Vec3(0f, 1f, 0f), Vec3.Y); g.limits(low, high)
        var drift = 0f; var at = -1; var sinceHit = 0; var atStop = false; var spinBefore = 0f; var spinAfter = 0f; var spin = 0f
        repeat(240) { i ->
            val before = door.angularVelocity.length()
            if (i % 30 == 0) { door.applyImpulse(Vec3(0f, 0f, -60f + rnd.nextFloat() * 120f), Vec3(.9f, .2f + rnd.nextFloat() * 1.6f, 0f)); sinceHit = 0 }
            val after = door.angularVelocity.length()
            w.step(DT); sinceHit++
            val d = (g.anchorB - Vec3(0f, 1f, 0f)).length()
            if (d > drift) { drift = d; at = sinceHit; atStop = g.angle <= low + .01f || g.angle >= high - .01f; spinBefore = before; spinAfter = after; spin = door.angularVelocity.length() }
        }
        worst = max(worst, drift)
        println("  porte $k (${"%.0f".format(door.mass)} kg) : gonds ${"%.2f".format(drift * 1000)} mm, ${at} pas après un coup" + (if (atStop) ", en butée" else "") +
            ", ω ${"%.1f".format(spinBefore)} → ${"%.1f".format(spinAfter)} → ${"%.1f".format(spin)} rad/s")
    }
    println("Portes : pire ${"%.2f".format(worst * 1000)} mm")
}


/** A ragdoll of 11 bodies: cone joints at the neck, shoulders and hips, hinges at the elbows and knees. */
private fun ragdoll(w: PhysicsWorld, p: Vec3, turn: Quat): List<Body> {
    fun a(x: Float, y: Float) = p + turn.rotate(Vec3(x, y, 0f))
    val down = turn.rotate(Vec3(0f, -1f, 0f)); val up = turn.rotate(Vec3.Y); val side = turn.rotate(Vec3.X)
    val torso = w.add(Box(.18f, .3f, .1f), a(0f, 1.2f), turn, mass = 30f)
    val head = w.add(Sphere(.11f), a(0f, 1.65f), turn, mass = 5f)
    w.addConeTwist(torso, head, a(0f, 1.52f), up, .6f, .4f, -.5f, .5f, across = side)
    val bodies = arrayListOf(torso, head)
    for (sx in floatArrayOf(-1f, 1f)) {
        val upper = w.add(Capsule(.05f, .13f), a(sx * .25f, 1.32f), turn, mass = 2.5f)
        w.addConeTwist(torso, upper, a(sx * .25f, 1.5f), down, 1.2f, .8f, -.6f, .6f, across = side)
        val fore = w.add(Capsule(.04f, .12f), a(sx * .25f, .96f), turn, mass = 1.5f)
        w.addHinge(upper, fore, a(sx * .25f, 1.14f), side).limits(0f, 2.5f)
        val thigh = w.add(Capsule(.07f, .16f), a(sx * .1f, .67f), turn, mass = 8f)
        w.addConeTwist(torso, thigh, a(sx * .1f, .9f), down, 1f, .5f, -.4f, .4f, across = side)
        val shin = w.add(Capsule(.05f, .17f), a(sx * .1f, .22f), turn, mass = 4f)
        w.addHinge(thigh, shin, a(sx * .1f, .44f), side).limits(-2.4f, 0f)
        bodies += listOf(upper, fore, thigh, shin)
    }
    return bodies
}

/** Diagnostics of ragdolls hit at random: the worst joint gap, and whether a body of that joint was pressed by another body. */
private fun ragdolls(count: Int) {
    val rnd = java.util.Random(77)
    fun f(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
    fun dir(): Vec3 { while (true) { val v = Vec3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)); val l = v.length(); if (l in .1f..1f) return v / l } }
    repeat(count) { k ->
        val w = PhysicsWorld()
        w.addMesh(TriangleMesh(floatArrayOf(-60f, 0f, -60f, -60f, 0f, 60f, 60f, 0f, 60f, -60f, 0f, -60f, 60f, 0f, 60f, 60f, 0f, -60f)))
        val bodies = ragdoll(w, Vec3(f(-1f, 1f), f(1f, 3f), f(-1f, 1f)), Quat.axisAngle(dir(), f(0f, 6.28f)))
        bodies[0].angularVelocity = dir() * f(0f, 6f)
        repeat(180) { w.step(DT) }
        var worst = 0f; var what = ""
        repeat(20) {
            val b = bodies[rnd.nextInt(bodies.size)]
            b.applyImpulse(dir() * (b.mass * f(0f, 8f)), b.position + dir() * .05f)
            repeat(18) {
                w.step(DT)
                for (j in w.jointList) {
                    val g = (j.anchorA - j.anchorB).length()
                    if (g > worst) {
                        worst = g
                        val pressed = w.contacts().filter { c -> c.a != null && (c.a == j.b || c.b == j.b || c.a == j.a || c.b == j.a) && c.normalImpulse > 0f }
                        what = "${j::class.simpleName} ${j.b.shape::class.simpleName}, ${pressed.size} contacts entre membres (impulsion ${"%.2f".format(pressed.sumOf { it.normalImpulse.toDouble() })})"
                    }
                }
            }
        }
        repeat(240) { w.step(DT) }
        val rest = w.jointList.maxBy { (it.anchorA - it.anchorB).length() }
        val pressedRest = w.contacts().count { c -> c.a != null && (c.a == rest.b || c.b == rest.b) && c.normalImpulse > 0f }
        println("  pantin $k : coups ${"%.1f".format(worst * 1000)} mm ($what) ; repos ${"%.2f".format((rest.anchorA - rest.anchorB).length() * 1000)} mm " +
            "(${rest::class.simpleName} ${rest.b.shape::class.simpleName}, $pressedRest contacts entre membres)")
    }
}

/** A crate with bevelled corners as a hull (24 vertices, 26 faces). */
private fun bevelled(h: Float, b: Float): ConvexHull {
    val out = ArrayList<Vec3>()
    for (k in 0 until 8) {
        val sx = if (k and 1 == 0) -1f else 1f; val sy = if (k and 2 == 0) -1f else 1f; val sz = if (k and 4 == 0) -1f else 1f
        out += Vec3(sx * h, sy * (h - b), sz * (h - b)); out += Vec3(sx * (h - b), sy * h, sz * (h - b)); out += Vec3(sx * (h - b), sy * (h - b), sz * h)
    }
    return ConvexHull(out)
}

/** A rock: the hull of [points] random points on a squashed sphere, at most [max] vertices. */
private fun rock(seed: Long, points: Int, max: Int): ConvexHull {
    val r = java.util.Random(seed)
    return ConvexHull((0 until points).map {
        val v = Vec3(r.nextFloat() - .5f, r.nextFloat() - .5f, r.nextFloat() - .5f).normalized()
        Vec3(v.x * .4f, v.y * .28f, v.z * .35f) * (.8f + .2f * r.nextFloat())
    }, max)
}

/**
 * Hulls against boxes: a pile of [count] bodies settling in a pit, all boxes, all bevelled crates (24 vertices)
 * or all rocks (16 and 32 vertices), the same places; time per step once settled and the narrow phase per pair.
 */
private fun hulls(count: Int) {
    val kinds = listOf<Pair<String, () -> Shape>>("boîtes" to { Box(.35f, .3f, .3f) }, "caisses biseautées (24 sommets)" to { bevelled(.32f, .06f) },
        "rochers (16 sommets)" to { rock(1, 60, 16) }, "rochers (32 sommets)" to { rock(1, 60, 32) })
    for ((name, make) in kinds) {
        val shape = make()
        val w = PhysicsWorld(); ground(w, 60f)
        val side = 14
        for ((x, z, hx, hz) in listOf(arrayOf(-side * .45f, 0f, .5f, side * .5f), arrayOf(side * .45f, 0f, .5f, side * .5f), arrayOf(0f, -side * .45f, side * .5f, .5f), arrayOf(0f, side * .45f, side * .5f, .5f)))
            w.add(Box(hx, 4f, hz), Vec3(x, 4f, z), type = BodyType.STATIC)
        for (n in 0 until count) {
            val l = n / (side * side); val i = (n / side) % side; val j = n % side
            w.add(shape, Vec3((i - side / 2) * .85f + .2f * (l % 2), .6f + l * .8f, (j - side / 2) * .85f), Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), n * .37f))
        }
        val sleepy = PhysicsWorld().timeToSleep
        repeat(60 * 5) { w.step(DT) }
        val asleep = (0 until w.bodyCount).count { w.body(it).isSleeping }
        println("  $name : $asleep / $count endormis à 5 s (sommeil après ${sleepy} s)")
        // Awake again, kept awake: the cost of a pile in motion.
        for (b in 0 until w.bodyCount) if (w.body(b).type == BodyType.DYNAMIC) w.body(b).velocity = w.body(b).velocity
        w.timeToSleep = 1e9f
        repeat(60) { w.step(DT) }
        w.profiling = true; java.util.Arrays.fill(w.phaseNanos, 0L)
        val t = Timing(); var pairs = 0L; var points = 0L
        repeat(180) { val t0 = System.nanoTime(); w.step(DT); t.samples.add(System.nanoTime() - t0); pairs += w.pairCount; points += w.contactCount }
        val lowest = (0 until w.bodyCount).filter { w.body(it).type == BodyType.DYNAMIC }.minOf { w.body(it).position.y }
        println("  $name : ${"%.0f".format(w.phaseNanos[2].toDouble() / pairs)} ns de phase fine par paire (corps et sol), ${pairs / 180} paires, ${points / 180} points, plus bas ${"%.2f".format(lowest)} m")
        t.report("    pas, $count corps éveillés")
    }
}

/** Diagnostics: how a pile of bevelled hull crates settles, second by second (who stays awake, how fast). */
private fun hullSettle(count: Int) {
    val shape = if (System.getProperty("rock") != null) rock(1, 60, 32) else bevelled(.32f, .06f)
    val w = PhysicsWorld(); ground(w, 60f)
    val side = if (count > 400) 14 else 10
    for ((x, z, hx, hz) in listOf(arrayOf(-side * .45f, 0f, .5f, side * .5f), arrayOf(side * .45f, 0f, .5f, side * .5f), arrayOf(0f, -side * .45f, side * .5f, .5f), arrayOf(0f, side * .45f, side * .5f, .5f)))
        w.add(Box(hx, 4f, hz), Vec3(x, 4f, z), type = BodyType.STATIC)
    for (n in 0 until count) {
        val l = n / (side * side); val i = (n / side) % side; val j = n % side
        w.add(shape, Vec3((i - side / 2) * .85f + .2f * (l % 2), .6f + l * .8f, (j - side / 2) * .85f), Quat.axisAngle(Vec3(1f, 2f, 3f).normalized(), n * .37f))
    }
    for (sec in 1..12) {
        repeat(60) { w.step(DT) }
        val bodies = (0 until w.bodyCount).map { w.body(it) }.filter { it.type == BodyType.DYNAMIC }
        val awake = bodies.filter { !it.isSleeping }
        val v = awake.maxOfOrNull { it.velocity.length() } ?: 0f; val om = awake.maxOfOrNull { it.angularVelocity.length() } ?: 0f
        println("  t=$sec s : ${awake.size} éveillés, v max ${"%.3f".format(v)} m/s, ω max ${"%.3f".format(om)} rad/s, ${w.contactCount} points")
        if (sec == 12) awake.sortedByDescending { it.velocity.length() + .4f * it.angularVelocity.length() }.take(5).forEach {
            println("    corps ${it.id} p=${it.position} v=${it.velocity} ω=${it.angularVelocity}")
        }
    }
}

/** Diagnostics: random rocks (hulls of 8 to 58 points, 16 or 32 vertices) dropped in a pit, who is still awake, second by second. */
private fun rockPit(count: Int) {
    val r = java.util.Random(11)
    fun f(a: Float, b: Float) = a + r.nextFloat() * (b - a)
    val w = PhysicsWorld(); w.add(Box(30f, .5f, 30f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
    for ((x, z) in listOf(3.5f to 0f, -3.5f to 0f, 0f to 3.5f, 0f to -3.5f))
        w.add(Box(if (x == 0f) 4f else .5f, 1.5f, if (z == 0f) 4f else .5f), Vec3(x, 1.5f, z), type = BodyType.STATIC)
    val bodies = (0 until count).map { k ->
        val a = f(.15f, .6f); val b = f(.15f, .6f); val c = f(.15f, .6f)
        val h = ConvexHull((0 until 8 + r.nextInt(50)).map { val d = Vec3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)).normalized(); Vec3(d.x * a, d.y * b, d.z * c) * f(.7f, 1f) }, if (r.nextBoolean()) 16 else 32)
        val shape: Shape = if (System.getProperty("compounds") != null && k % 3 == 0) Compound((0 until 2 + r.nextInt(3)).map {
            val d = Vec3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)).normalized() * f(0f, .35f)
            val part: Shape = when (r.nextInt(4)) { 0 -> Sphere(f(.08f, .3f)); 1 -> Box(f(.05f, .3f), f(.05f, .3f), f(.05f, .3f)); 2 -> Capsule(f(.05f, .2f), f(.05f, .3f)); else -> h.scaled(.6f) }
            Compound.Part(part, d, Quat(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)).normalized(), density = f(.5f, 3f))
        }) else h
        w.add(shape, Vec3(f(-2.5f, 2.5f), 1f + k * .25f, f(-2.5f, 2.5f)), Quat(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f), f(-1f, 1f)).normalized(), density = f(200f, 2500f), friction = f(.3f, .9f))
    }
    for (sec in 1..40) {
        repeat(60) { w.step(DT) }
        if (sec % 5 == 0) {
            val awake = bodies.filter { !it.isSleeping }
            println("  t=$sec s : ${awake.size} éveillés" + (awake.maxByOrNull { it.velocity.length() + it.shape.boundingRadius * it.angularVelocity.length() }?.let {
                ", le plus vif v=${"%.3f".format(it.velocity.length())} ω=${"%.3f".format(it.angularVelocity.length())} ${it.shape}" } ?: ""))
        }
    }
}
