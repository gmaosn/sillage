package sillage.bench

import sillage.*

/**
 * Bancs du rendu, sans fenêtre et sans son : le temps processeur d'une image (mise à jour de la scène comprise),
 * après une première partie de mise en route dans la même JVM (le JIT compile tard ce qui ne sert qu'une fois par
 * image). `crates` : 10 000 caisses vues de haut ; `forest` : 40 000 blocs vus de l'intérieur (qa `mondes`) ;
 * `heap` : un tas de 3 000 caisses en jeu ; `island` et `night` : l'île vue de haut, de jour et de nuit (vues
 * « ile » et « nuit » du qa) ; `temple` : le temple au départ du joueur. Le nombre est celui des images mesurées.
 * BANC_FENETRE=1 : dans une fenêtre, au rythme de l'écran (dans un KWin isolé : tools/kwin_isole.sh).
 */
internal fun render(name: String, frames: Int) {
    val (args, scene) = when (name) {
        "crates" -> listOf("--look-at", "0,40,70,0,0,0") to fun Scene.() {
            ground()
            for (i in 0 until 100) for (j in 0 until 100) box(.6, color = if ((i + j) % 2 == 0) Color.WOOD else Color.ORANGE, at = vec(i - 50, .3, j - 50))
        }
        "forest" -> listOf("--look-at", "0,4,8,30,2,-60") to fun Scene.() {
            ground(size = 1200)
            lighting.fogStart = 40f; lighting.fogEnd = 140f
            val pillar = Mesh.box(1f, 6f, 1f, Color.STONE)
            val crate = Mesh.box(1.2f, 1.2f, 1.2f, Color.WOOD)
            for (i in 0 until 200) for (j in 0 until 200) {
                val x = -500f + i * 5f + (j % 3); val z = -500f + j * 5f + (i % 4)
                if ((i - 100) * (i - 100) + (j - 100) * (j - 100) < 9) continue
                mesh(if ((i + j) % 3 == 0) crate else pillar, at = vec(x, if ((i + j) % 3 == 0) .6 else 3, z))
            }
        }
        "heap" -> listOf("--look-at", "0,22,30,0,0,0") to fun Scene.() {
            ground()
            for ((x, z, w, d) in listOf(arrayOf(-13f, 0f, .5f, 13.5f), arrayOf(13f, 0f, .5f, 13.5f), arrayOf(0f, -13f, 13.5f, .5f), arrayOf(0f, 13f, 13.5f, .5f)))
                box(w * 2, 4, d * 2, Color.STONE, at = vec(x, 2, z)).solid()
            for (n in 0 until 3000) { val l = n / 400; val i = (n / 20) % 20; val j = n % 20; box(.6, at = vec((i - 10) * 1.2f, 1 + l * 1.2f, (j - 10) * 1.2f)).dynamic() }
        }
        "island" -> listOf("--look-at", "0,14,20,0,1,0") to sillage.demo.island
        "night" -> listOf("--hour", "21.5", "--look-at", "12.5,3.4,8.5,2,1.6,-1") to sillage.demo.island
        "temple" -> emptyList<String>() to sillage.temple.oracle
        else -> error("Banc de rendu inconnu : $name")
    }
    // BANC_FENETRE=1 : dans une fenêtre (un KWin isolé, tools/kwin_isole.sh), au rythme de l'écran, mise en route plus courte.
    val window = System.getenv("BANC_FENETRE") == "1"
    fun play(n: Int) = game("Banc", ((if (window) emptyList() else listOf("--headless")) + listOf("--mute", "--frames", n.toString()) + args).toTypedArray(), scene)
    play(if (window) 1200 else 4000)
    val r = play(frames)
    println("  $name : $frames images, processeur ${"%.3f".format(r.meanMs)} ms par image (99e centile ${"%.2f".format(r.p99Ms)}), GPU ${"%.2f".format(r.gpuMs)} ms")
}
