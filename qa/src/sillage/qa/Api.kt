package sillage.qa

import sillage.*
import sillage.demo.island
import sillage.math.Vec3
import sillage.physics.BodyType
import sillage.physics.Sphere
import sillage.physics.overlaps
import sillage.playground.playground
import java.util.Random
import kotlin.math.*

private const val DT = 1f / 60f

/** L'API publique sur de vraies parties : première personne, terrain de jeu fait de formes, changement de scène. */
fun Qa.api() {
    controle("première personne : un robot parcourt l'île", "les yeux jamais à plus de 1 cm dans le décor") {
        val m = Scene().apply(island); val p = m.player!!
        p.controls = false; p.view = CameraMode.FIRST_PERSON
        val robot = Robot(Random(graine + 900))
        val frames = cas(1800, 7200)
        var pire = 0f; var ou = ""
        repeat(frames) {
            val (d, saut) = robot.commande(p.position)
            // On regarde où l'on va, comme un joueur à la souris.
            if (d != Vec3.ZERO) m.camera.yaw = Math.toDegrees(atan2(-d.x, -d.z).toDouble()).toFloat()
            p.move(m.camera.moveDirection(0f, if (d == Vec3.ZERO) 0f else 1f), robot.marche); if (saut) p.jump()
            m.step(DT)
            val eye = m.camera.position
            for (o in m.physics.overlaps(Sphere(m.camera.near), eye, exclude = p.character.body)) {
                if (o.body != null && o.body!!.type == BodyType.DYNAMIC) continue      // une caisse poussée contre la tête : elle cède
                if (-o.separation > pire) { pire = -o.separation; ou = "yeux en $eye, pieds en ${p.position}, au sol ${p.onGround}, ${o.body?.let { "corps ${it.id} ${it.shape} en ${it.position}" } ?: "maillage ${o.material?.name}"}, point ${o.point}, normale ${o.normal}" }
            }
        }
        "$frames images, pire enfoncement de la sphère proche ${mm(pire)}${if (pire > .01f) " ($ou)" else ""}" to (pire <= .01f)
    }

    controle("terrain de jeu en formes simples : un robot y joue", "rien sous le sol ni de non fini, ballons tombés retirés") {
        val m = Scene().apply(playground); val p = m.player!!; p.controls = false
        val robot = Robot(Random(graine + 901))
        val frames = cas(2400, 9000)
        val depart = m.physics.liveBodyCount
        var sous = 0; var nonFinis = 0; var plus = 0
        repeat(frames) {
            val (d, saut) = robot.commande(p.position)
            p.move(d, robot.marche); if (saut) p.jump()
            m.step(DT)
            for (n in m.nodes) {
                val b = n.body ?: continue
                if (b.type != BodyType.DYNAMIC) continue
                if (!b.position.isFinite()) nonFinis++ else if (b.position.y < 0f) sous++
            }
            if (!p.position.isFinite()) nonFinis++ else if (p.position.y < -.05f) sous++
            plus = max(plus, m.physics.liveBodyCount - depart)
        }
        // Un ballon toutes les 3 s, retiré après 20 s : jamais plus de 7 à la fois.
        "$frames images, $sous corps sous le sol, $nonFinis non finis, ${p.position.let { "joueur en (%.1f ; %.1f ; %.1f)".format(it.x, it.y, it.z) }}, jusqu'à $plus ballons tombés à la fois" to
            (sous == 0 && nonFinis == 0 && plus in 6..7)
    }

    controle("terrain de jeu : B cinq fois, 2000 caisses lâchées", "rien sous le sol ni de non fini, une image ≤ 33 ms en moyenne") {
        val m = Scene().apply(playground); val p = m.player!!; p.controls = false
        var frame = 0
        m.input.source = KeySource { k -> k == Key.B && frame % 60 == 10 && frame < 300 }
        val temps = ArrayList<Double>()
        repeat(600) {
            val t = System.nanoTime(); m.step(DT); if (frame >= 300) temps.add((System.nanoTime() - t) / 1e6)
            frame++
        }
        val caisses = m.nodes.filter { it.body?.type == BodyType.DYNAMIC && it.body?.shape is sillage.physics.Box }
        val sous = caisses.count { !it.position.isFinite() || it.position.y < 0f }
        val moyenne = temps.average()
        "${caisses.size} caisses, $sous sous le sol ou non finies, ${f2(moyenne)} ms par image (physique et scène, ${m.physics.contactCount} points)" to
            (caisses.size >= 2000 && sous == 0 && moyenne <= 33.0)
    }

    controle("changement de scène et d'éclairage en cours de partie", "la nouvelle scène tourne, son joueur est le bon") {
        var images = 0
        val r = game("QA", arrayOf("--headless", "--mute", "--frames", "120")) {
            ground(); box(1, at = vec(0, 3, 0)).dynamic(); player(at = vec(0, 0, 4))
            after(.5) {
                goTo {
                    lighting.fogEnd = 80f; lighting.sunColor = Color(1f, .8f, .6f)
                    ground(color = Color.SAND); sphere(.5, at = vec(0, 2, 0)).dynamic(); player(at = vec(2, 0, 2))
                    onUpdate { images++ }
                }
            }
        }
        "${r.frames} images dont $images dans la deuxième scène, joueur en ${r.playerPosition}" to
            (r.frames == 120 && images in 85..92 && r.playerPosition.distanceTo(vec(2, 0, 2)) < .05f)
    }
}
