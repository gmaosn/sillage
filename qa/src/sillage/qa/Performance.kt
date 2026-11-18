package sillage.qa

import sillage.Color
import sillage.game
import sillage.vec

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
/** Bytes allocated by every thread of the process (the physics worker threads included). */
private val octetsAlloues: () -> Long = run {
    val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    { bean.totalThreadAllocatedBytes }
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

    // Scènes lourdes : 10 000 caisses à dessiner (une par appel avant les instances), puis un tas de 3 000 en jeu.
    run {
        val r = game("QA", arrayOf("--headless", "--mute", "--frames", "300", "--look-at", "0,40,70,0,0,0")) {
            ground()
            for (i in 0 until 100) for (j in 0 until 100) box(.6, color = if ((i + j) % 2 == 0) Color.WOOD else Color.ORANGE, at = vec(i - 50, .3, j - 50))
        }
        mesure("rendu.caisses10000", "image de 10 000 caisses (processeur)", "ms", r.meanMs, " (GPU ${f2(r.gpuMs)} ms, 99e centile ${f2(r.p99Ms)})")
    }
    run {
        val r = game("QA", arrayOf("--headless", "--mute", "--frames", "360", "--look-at", "0,22,30,0,0,0")) {
            ground()
            for ((x, z, w, d) in listOf(arrayOf(-13f, 0f, .5f, 13.5f), arrayOf(13f, 0f, .5f, 13.5f), arrayOf(0f, -13f, 13.5f, .5f), arrayOf(0f, 13f, 13.5f, .5f)))
                box(w * 2, 4, d * 2, Color.STONE, at = vec(x, 2, z)).solid()
            for (n in 0 until 3000) { val l = n / 400; val i = (n / 20) % 20; val j = n % 20; box(.6, at = vec((i - 10) * 1.2f, 1 + l * 1.2f, (j - 10) * 1.2f)).dynamic() }
        }
        mesure("jeu.tas3000", "image d'un tas de 3 000 caisses en jeu (physique, ombres, rendu)", "ms", r.meanMs, " (GPU ${f2(r.gpuMs)} ms, 99e centile ${f2(r.p99Ms)})")
    }

    val manquantes = nouvelles.keys.filter { it !in base }
    if (base.isEmpty() || refaire || manquantes.isNotEmpty()) {
        // Les mesures nouvelles s'ajoutent à la base sans toucher aux anciennes (sauf avec --refaire-references).
        val ecrire = if (base.isEmpty() || refaire) nouvelles else LinkedHashMap(base).apply { for (k in manquantes) put(k, nouvelles.getValue(k)) }
        Files.createDirectories(chemin.parent)
        Files.writeString(chemin, "# Base de vitesse de cette machine, écrite par le contrôle qualité (ms).\n" + ecrire.entries.joinToString("\n") { "${it.key}=${"%.4f".format(java.util.Locale.ROOT, it.value)}" } + "\n")
    }
}
