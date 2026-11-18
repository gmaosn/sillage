package sillage.qa

import sillage.Glb
import sillage.audio.Sound
import sillage.audio.Sounds
import sillage.io.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Ce qu'a fait un chargeur d'un fichier abîmé. */
private class Lectures(var lus: Int = 0, var refuses: Int = 0, var plantages: Int = 0, var bloques: Int = 0) {
    val exemples = LinkedHashMap<String, String>()
    val total get() = lus + refuses + plantages + bloques
    override fun toString() = "$total fichiers : $lus lus, $refuses refusés proprement, $plantages plantages, $bloques bloqués" +
        if (exemples.isEmpty()) "" else " (" + exemples.entries.take(4).joinToString { "${it.key} : ${it.value}" } + ")"
}

/**
 * Essaie [lire] sur chaque fichier, dans un fil à part limité à 3 s. Refuser proprement, c'est lever
 * IllegalArgumentException ou IllegalStateException avec un message ; tout le reste est un plantage.
 */
private fun essayer(fichiers: Sequence<ByteArray>, lire: (ByteArray) -> Unit): Lectures {
    val b = Lectures()
    var fil = Executors.newSingleThreadExecutor { r -> Thread(r, "qa-chargeur").apply { isDaemon = true } }
    for (octets in fichiers) {
        val f: Future<*> = fil.submit { lire(octets) }
        try { f.get(3, TimeUnit.SECONDS); b.lus++ }
        catch (e: TimeoutException) { b.bloques++; b.exemples.putIfAbsent("bloqué", "plus de 3 s"); f.cancel(true); fil = Executors.newSingleThreadExecutor { r -> Thread(r, "qa-chargeur").apply { isDaemon = true } } }
        catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause!!
            if ((c is IllegalArgumentException || c is IllegalStateException) && !c.message.isNullOrBlank()) b.refuses++
            else { b.plantages++; b.exemples.putIfAbsent(c::class.simpleName ?: "?", (c.message ?: "").take(70)) }
        }
    }
    fil.shutdownNow()
    return b
}

/** Versions abîmées de [source] : octets inversés, coupures, longueurs fausses, nombres absurdes dans le texte. */
private fun abimer(source: ByteArray, rnd: Random, n: Int, zoneTexte: IntRange? = null): Sequence<ByteArray> = sequence {
    repeat(n) { k ->
        val o = source.copyOf()
        when (k % 5) {
            0 -> repeat(1 + rnd.nextInt(16)) { val i = rnd.nextInt(o.size); o[i] = (o[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
            1 -> { yield(source.copyOf(rnd.nextInt(source.size))); return@repeat }
            2 -> { // un entier de 32 bits remplacé par une valeur extrême (longueurs, tailles)
                val i = (rnd.nextInt(o.size / 4)) * 4
                val v = intArrayOf(-1, 0, Int.MAX_VALUE, Int.MIN_VALUE, 1 shl 30, rnd.nextInt())[rnd.nextInt(6)]
                ByteBuffer.wrap(o).order(ByteOrder.LITTLE_ENDIAN).putInt(i, v)
            }
            3 -> if (zoneTexte != null) { // un nombre du texte remplacé par un nombre absurde
                val texte = String(o, zoneTexte.first, zoneTexte.last - zoneTexte.first + 1, Charsets.ISO_8859_1)
                val nombres = Regex("\\d+").findAll(texte).toList()
                if (nombres.isNotEmpty()) {
                    val m = nombres[rnd.nextInt(nombres.size)]
                    val nouveau = listOf("-1", "0", "4294967296", "2147483647", "99999999999999999999", "1e309", "-0")[rnd.nextInt(7)]
                    val avant = String(o, Charsets.ISO_8859_1)
                    val debut = zoneTexte.first + m.range.first; val fin = zoneTexte.first + m.range.last + 1
                    val apres = avant.substring(0, debut) + nouveau + avant.substring(fin)
                    // Garde la taille du morceau JSON cohérente : on complète ou on tronque par des espaces.
                    val brut = apres.toByteArray(Charsets.ISO_8859_1)
                    yield(if (brut.size >= o.size) brut.copyOf(o.size) else brut + ByteArray(o.size - brut.size) { ' '.code.toByte() }); return@repeat
                }
            } else repeat(4) { val i = rnd.nextInt(o.size); o[i] = rnd.nextInt(256).toByte() }
            else -> { // des octets au hasard sur une plage
                val i = rnd.nextInt(o.size); val l = minOf(o.size - i, 1 + rnd.nextInt(64)); for (j in i until i + l) o[j] = rnd.nextInt(256).toByte()
            }
        }
        yield(o)
    }
}

private fun ressource(chemin: String) = Glb::class.java.getResourceAsStream("/$chemin")!!.use { it.readAllBytes() }

fun Qa.chargeurs() {
    val rnd = Random(graine + 300)

    controle("GLB abîmés : refusés proprement", "aucun plantage, aucun blocage") {
        val total = Lectures()
        for (nom in listOf("terrain", "village", "marin", "palmiers", "port", "caisse", "ballon")) {
            val source = ressource("models/$nom.glb")
            val longueurJson = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
            val b = essayer(abimer(source, rnd, cas(150, 1200), 20 until 20 + longueurJson)) { Glb.lireModele(it) }
            total.lus += b.lus; total.refuses += b.refuses; total.plantages += b.plantages; total.bloques += b.bloques
            b.exemples.forEach { (k, v) -> total.exemples.putIfAbsent(k, "$nom, $v") }
        }
        total.toString() to (total.plantages == 0 && total.bloques == 0)
    }

    controle("JSON abîmés et pièges : refusés proprement", "aucun plantage, aucun blocage") {
        val source = ressource("models/caisse.glb").let { g -> val l = ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN).getInt(12); g.copyOfRange(20, 20 + l) }
        val pieges = listOf("[".repeat(200_000), "{\"a\":".repeat(100_000), "\"\\u12", "\"\\uZZZZ\"", "1e999999", "-", "{\"a\" 1}", "[1,]", "\"\u0001\"",
            "{\"a\":1,\"a\":2}", "9".repeat(100_000), "[" + "1,".repeat(100_000) + "1]", "", "nul", "tru", "\"abc").map { it.toByteArray() }
        val b = essayer(abimer(source, rnd, cas(500, 4000), source.indices) + pieges.asSequence()) { Json.parse(String(it, Charsets.UTF_8)) }
        b.toString() to (b.plantages == 0 && b.bloques == 0)
    }

    controle("WAV abîmés : refusés proprement", "aucun plantage, aucun blocage") {
        val source = Sounds.pickup().toWav()
        val b = essayer(abimer(source, rnd, cas(400, 3000))) { Sound.wav(it) }
        b.toString() to (b.plantages == 0 && b.bloques == 0)
    }
}
