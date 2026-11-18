package sillage.qa

import sillage.Scene
import sillage.audio.Audio
import sillage.demo.island
import sillage.math.Vec3
import java.util.Random
import kotlin.math.*

private const val DT = 1f / 60f

/** Un joueur robot : il vise des points de l'île au hasard, saute parfois, marche ou court. */
class Robot(private val rnd: Random) {
    private var cible = Vec3.ZERO; private var reste = 0f
    var marche = false; private set
    fun commande(p: Vec3): Pair<Vec3, Boolean> {
        reste -= DT
        val d = Vec3(cible.x - p.x, 0f, cible.z - p.z)
        if (reste <= 0f || d.length() < .4f) {
            val a = rnd.f(0f, 2 * PI.toFloat()); val r = sqrt(rnd.nextFloat()) * 10.5f
            cible = Vec3(cos(a) * r, 0f, sin(a) * r); reste = rnd.f(3f, 8f); marche = rnd.nextFloat() < .2f
        }
        val saut = rnd.nextFloat() < .4f * DT
        return (if (d.length() < .4f) Vec3.ZERO else d.normalized()) to saut
    }
}

fun utiliseeMo(): Double { repeat(3) { System.gc(); Thread.sleep(30) }; val r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) / 1048576.0 }

fun Qa.endurance() {
    val rnd = Random(graine + 400)
    val minutes = cas(2, 10)
    val m = Scene().apply(island)
    val audio = Audio.offline(); m.audio = audio
    val joueur = m.player!!; joueur.controls = false
    val son = FloatArray(2 * 800)
    val robot = Robot(rnd)
    var pire = 0f; var ou = ""; var nonFinis = 0; var chutes = 0; var creteSon = 0f; var sonNonFini = 0
    var memoireDepart = 0.0
    val images = minutes * 3600
    for (i in 0 until images) {
        if (i == 600) memoireDepart = utiliseeMo()
        val avant = joueur.position
        val (dir, saut) = robot.commande(avant)
        joueur.move(dir, robot.marche); if (saut) joueur.jump()
        m.step(DT)
        audio.mixer.render(son, 800)
        for (x in son) { if (!x.isFinite()) sonNonFini++; creteSon = max(creteSon, abs(x)) }
        val p = joueur.position
        if (!p.isFinite()) { nonFinis++; m.restart(); continue }
        if (avant.y < -5f && (p - joueur.spawn).length() < .01f) chutes++
        if (i % 3 == 0) { val (e, q) = enfoncementDetaille(joueur.character); if (e > pire) { pire = e; ou = "$q, héros en $p, au sol ${joueur.onGround}" } }
    }
    val memoireFin = utiliseeMo()
    controle("$minutes min de jeu par un robot sur l'île", "rien de non fini, enfoncement ≤ 3 cm") {
        "$images images, ${m.pickups.count { it.collected }} coquillages, $chutes chutes à la mer, enfoncement max ${mm(pire)}${if (pire > .03f) " ($ou)" else ""}, $nonFinis non finis" to (nonFinis == 0 && pire <= .03f)
    }
    controle("mémoire stable pendant la partie", "+≤ 16 Mo après échauffement") {
        "${f1(memoireDepart)} → ${f1(memoireFin)} Mo (${"%+.1f".format(memoireFin - memoireDepart)})" to (memoireFin - memoireDepart <= 16)
    }
    controle("son de la partie : jamais saturé ni faux", "crête ≤ 1, aucune valeur non finie") {
        "crête ${f3(creteSon)}, $sonNonFini non finies" to (creteSon <= 1f && sonNonFini == 0)
    }
}
