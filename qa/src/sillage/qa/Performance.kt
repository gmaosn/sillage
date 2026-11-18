package sillage.qa

import sillage.Scene
import sillage.demo.island
import sillage.math.Vec3
import sillage.physics.BodyType
import sillage.physics.Box
import sillage.physics.PhysicsWorld
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

private const val DT = 1f / 60f

private fun mediane(xs: List<Double>) = xs.sorted()[xs.size / 2]
private fun p99(xs: List<Double>) = xs.sorted()[(xs.size * .99).toInt().coerceAtMost(xs.size - 1)]
private val octetsAlloues: () -> Long = run {
    val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    { bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) }
}

/**
 * Mesures de vitesse comparées à une base enregistrée sur cette machine (qa/reference/performance.txt) :
 * plus de 50 % plus lent que la base est un échec. La première fois, ou avec --refaire-references, la base est écrite.
 */
fun Qa.performance() {
    val chemin = Path.of("qa/reference/performance.txt")
    val base = if (Files.exists(chemin) && !refaire) Files.readAllLines(chemin).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').toDouble() } else emptyMap()
    val nouvelles = LinkedHashMap<String, Double>()

    fun mesure(cle: String, nom: String, unite: String, valeur: Double, detail: String = "") {
        nouvelles[cle] = valeur
        val b = base[cle]
        if (b == null) info(nom) { "${f2(valeur)} $unite$detail (base enregistrée)" }
        else controle(nom, "≤ 1,5 × base (${f2(b)} $unite)") {
            "${f2(valeur)} $unite$detail, ${"%.0f".format(valeur / b * 100)} % de la base" to (valeur <= 1.5 * b + .05)
        }
    }

    // Pile de 1000 corps en pleine mêlée.
    run {
        val rnd = Random(graine + 500)
        val w = PhysicsWorld(); w.add(Box(30f, .5f, 30f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
        ajouterCorps(w, rnd, 1000, 6f, .5f, 25f)
        val temps = ArrayList<Double>(); var alloue = 0L
        repeat(60) { w.step(DT) }
        repeat(150) {
            val a = octetsAlloues(); val t = System.nanoTime(); w.step(DT); temps.add((System.nanoTime() - t) / 1e6); alloue += octetsAlloues() - a
        }
        mesure("physique.pile1000", "pas physique, pile de ${w.bodyCount - 1} corps", "ms", mediane(temps), " (p99 ${f2(p99(temps))})")
        mesure("physique.pile1000.alloc", "allocations par pas physique (pile)", "Kio", alloue / 150 / 1024.0, " (objectif de l'étape 7 : zéro)")
    }

    run {
        // Sans sommeil : on mesure 55 caisses vraiment éveillées (sinon la mesure dépend de quand elles s'endorment).
        val w = PhysicsWorld(); w.timeToSleep = Float.MAX_VALUE; w.add(Box(30f, .5f, 30f), Vec3(0f, -.5f, 0f), type = BodyType.STATIC)
        for (rang in 0 until 10) for (i in 0 until 10 - rang) w.add(Box(.5f, .5f, .5f), Vec3(i * 1.0f - (9 - rang) * .5f, .5f + rang * 1.0f, 0f))
        val temps = ArrayList<Double>()
        repeat(20) { w.step(DT) }
        repeat(40) { val t = System.nanoTime(); w.step(DT); temps.add((System.nanoTime() - t) / 1e6) }
        mesure("physique.pyramide", "pas physique, pyramide de 55 caisses éveillées", "ms", mediane(temps))
    }

    run {
        val m = Scene().apply(island); val robot = Robot(Random(graine + 501)); val joueur = m.player!!
        joueur.controls = false
        val temps = ArrayList<Double>(); var alloue = 0L
        repeat(1500) { i ->
            val a = octetsAlloues(); val t = System.nanoTime()
            val (d, s) = robot.commande(joueur.position)
            joueur.move(d, robot.marche); if (s) joueur.jump(); m.step(DT)
            if (i >= 300) { temps.add((System.nanoTime() - t) / 1e6); alloue += octetsAlloues() - a }
        }
        mesure("ile.miseajour", "mise à jour d'une image de l'île (physique, héros, caméra)", "ms", mediane(temps), " (p99 ${f2(p99(temps))})")
        mesure("ile.miseajour.alloc", "allocations par image de l'île", "Kio", alloue / 1200 / 1024.0)
    }

    for ((cle, taille) in listOf("rendu.1280" to "1280x800", "rendu.2560" to "2560x1600")) {
        System.gc()
        val gc0 = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }
        val b = rendreIle(null, 460, taille = taille)
        val gc = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime } - gc0
        mesure("$cle.moyenne", "image rendue en ${taille.replace('x', '×')} (moyenne)", "ms", b.meanMs,
            " (99e centile ${f2(b.p99Ms)}, pire ${f1(b.maxMs)}, ramasse-miettes $gc ms sur la partie)")
    }

    if (base.isEmpty() || refaire) {
        Files.createDirectories(chemin.parent)
        Files.writeString(chemin, "# Base de vitesse de cette machine, écrite par le contrôle qualité (ms).\n" + nouvelles.entries.joinToString("\n") { "${it.key}=${"%.4f".format(java.util.Locale.ROOT, it.value)}" } + "\n")
    }
}
