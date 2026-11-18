package sillage.qa

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Contrôle qualité de Sillage : une batterie de vérifications du moteur physique, du personnage, des
 * requêtes de collision, du rendu 3D, des chargeurs de fichiers, de l'API publique, de l'endurance et des performances.
 * Chaque contrôle donne une mesure, son seuil et son état ; le rapport va dans build/qa/rapport.md.
 *
 *   ./kotlin run -m qa --jvm-args="--enable-native-access=ALL-UNNAMED -Xmx2g" -- [--rapide] [--seulement physique,rendu]
 *       [--graine N] [--refaire-references] [--rapport chemin.md]
 */
fun main(args: Array<String>) {
    fun option(flag: String) = args.indexOf(flag).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val groupes = linkedMapOf<String, Qa.() -> Unit>(
        "physique" to { physique() },
        "collisions" to { collisions() },
        "personnage" to { personnage() },
        "rendu" to { rendu() },
        "chargeurs" to { chargeurs() },
        "api" to { api() },
        "endurance" to { endurance() },
        "performance" to { performance() },
    )
    val choisis = option("--seulement")?.split(',')?.map { it.trim() } ?: groupes.keys.toList()
    for (g in choisis) require(g in groupes) { "Groupe inconnu « $g » (groupes : ${groupes.keys.joinToString()})" }
    val qa = Qa(rapide = "--rapide" in args, graine = option("--graine")?.toLong() ?: 1L, refaire = "--refaire-references" in args)
    val debut = System.nanoTime()
    for ((nom, bloc) in groupes) if (nom in choisis) qa.groupe(nom, bloc)
    val duree = (System.nanoTime() - debut) / 1e9
    val chemin = Path.of(option("--rapport") ?: "build/qa/rapport.md")
    qa.rapport(chemin, duree)
    val echecs = qa.resultats.count { it.etat == Etat.ECHEC }
    println("\nQA_FIN controles=${qa.resultats.size} echecs=$echecs duree=${"%.0f".format(duree)}s rapport=$chemin")
    exitProcess(if (echecs == 0) 0 else 1)
}

enum class Etat { OK, ECHEC, INFO }

class Resultat(val groupe: String, val nom: String, val mesure: String, val seuil: String, val etat: Etat, val secondes: Double)

class Qa(val rapide: Boolean, val graine: Long, val refaire: Boolean) {
    val resultats = ArrayList<Resultat>()
    private var groupe = ""
    private val titres = mapOf("physique" to "Physique : invariants", "collisions" to "Collisions et requêtes : comparées à une référence indépendante",
        "personnage" to "Personnage : parcours aléatoires et cas limites", "rendu" to "Rendu 3D", "chargeurs" to "Chargeurs : fichiers abîmés", "api" to "API publique : parties réelles",
        "endurance" to "Endurance", "performance" to "Performance")

    /** Nombre de cas tirés au sort : peu en mode rapide. */
    fun cas(rapide: Int, complet: Int) = if (this.rapide) rapide else complet

    fun groupe(nom: String, bloc: Qa.() -> Unit) {
        groupe = nom
        println("\n== ${titres[nom] ?: nom}")
        try { bloc() } catch (e: Throwable) {
            ajouter("groupe interrompu", "exception : ${e::class.simpleName} ${e.message?.take(200)}", "aucune", Etat.ECHEC, System.nanoTime())
            e.printStackTrace()
        }
    }

    /** Un contrôle : le bloc rend la mesure lisible et si elle tient le [seuil]. Une exception est un échec. */
    fun controle(nom: String, seuil: String, bloc: () -> Pair<String, Boolean>) {
        val t = System.nanoTime()
        val (mesure, ok) = try { bloc() } catch (e: Throwable) {
            e.printStackTrace(); "exception : ${e::class.simpleName} ${e.message?.take(200)}" to false
        }
        ajouter(nom, mesure, seuil, if (ok) Etat.OK else Etat.ECHEC, t)
    }

    /** Une mesure sans seuil : une limite connue, un chiffre à suivre. */
    fun info(nom: String, bloc: () -> String) {
        val t = System.nanoTime()
        try { ajouter(nom, bloc(), "—", Etat.INFO, t) } catch (e: Throwable) {
            e.printStackTrace(); ajouter(nom, "exception : ${e::class.simpleName} ${e.message?.take(200)}", "—", Etat.ECHEC, t)
        }
    }

    private fun ajouter(nom: String, mesure: String, seuil: String, etat: Etat, debut: Long) {
        val r = Resultat(groupe, nom, mesure, seuil, etat, (System.nanoTime() - debut) / 1e9)
        resultats.add(r)
        val marque = when (etat) { Etat.OK -> "ok   "; Etat.ECHEC -> "ÉCHEC"; Etat.INFO -> "info " }
        println("  $marque ${nom.padEnd(52)} ${mesure}   [seuil : $seuil] ${"%.1f".format(r.secondes)} s")
    }

    fun rapport(chemin: Path, duree: Double) {
        chemin.parent?.let { Files.createDirectories(it) }
        val commit = runCatching {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("?")
        val propre = runCatching {
            ProcessBuilder("git", "status", "--porcelain", "--untracked-files=no").start().inputStream.bufferedReader().readText().isBlank()
        }.getOrDefault(true)
        val ok = resultats.count { it.etat == Etat.OK }; val ko = resultats.count { it.etat == Etat.ECHEC }; val inf = resultats.count { it.etat == Etat.INFO }
        val s = StringBuilder()
        s.append("# Contrôle qualité de Sillage\n\n")
        s.append("${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}, commit $commit${if (propre) "" else " (avec des modifications non commitées)"}, " +
            "JDK ${Runtime.version()}, ${Runtime.getRuntime().availableProcessors()} cœurs, mode ${if (rapide) "rapide" else "complet"}, graine $graine, ${"%.0f".format(duree)} s.\n\n")
        s.append("**${if (ko == 0) "Tout passe" else "$ko échec${if (ko > 1) "s" else ""}"}** : ${resultats.size} contrôles, $ok réussis, $ko en échec, $inf mesures informatives.\n\n")
        for (g in resultats.map { it.groupe }.distinct()) {
            s.append("## ${titres[g] ?: g}\n\n| Contrôle | Mesure | Seuil | État |\n|---|---|---|---|\n")
            for (r in resultats.filter { it.groupe == g }) {
                val etat = when (r.etat) { Etat.OK -> "réussi"; Etat.ECHEC -> "**ÉCHEC**"; Etat.INFO -> "info" }
                s.append("| ${r.nom} | ${r.mesure.replace("|", "/")} | ${r.seuil.replace("|", "/")} | $etat |\n")
            }
            s.append("\n")
        }
        Files.writeString(chemin, s)
    }
}

fun f1(x: Number) = "%.1f".format(x.toDouble())
fun f2(x: Number) = "%.2f".format(x.toDouble())
fun f3(x: Number) = "%.3f".format(x.toDouble())
fun cm(x: Number) = "%.1f cm".format(x.toDouble() * 100)
fun mm(x: Number) = "%.1f mm".format(x.toDouble() * 1000)
fun pct(x: Number) = "%.2f %%".format(x.toDouble() * 100)
