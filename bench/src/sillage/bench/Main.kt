package sillage.bench

import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Physics benches. Usage: bench [pyramid [rows]] [pile [count]] [determinism].
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
