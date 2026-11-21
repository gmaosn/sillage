package sillage.qa

import sillage.*
import sillage.math.Vec3
import java.util.Random
import kotlin.math.*

private const val DT = 1f / 60f

/**
 * Les manettes vues du jeu, par l'API publique : le joueur au stick (marche, course), le saut, la caméra
 * au stick droit et au gyroscope, une partie sans fenêtre pilotée par --pad-script, les branchements à
 * chaud au hasard, et le coût par image. Les manettes réelles ne sont pas ouvertes ici (elles le sont
 * par les essais du moteur, sur un vrai noyau par /dev/uinput, et par --pad-test pour le propriétaire).
 */
fun Qa.manette() {
    fun scene(): Pair<Scene, VirtualPad> {
        val v = VirtualPad("QA")
        val s = Scene().apply { ground(); player(at = vec(0, 0, 0)); input.plug(v) }
        repeat(30) { s.step(DT) }
        return s to v
    }
    fun vitesse(s: Scene) = s.player!!.character.walkVelocity.let { sqrt(it.x * it.x + it.z * it.z) }

    controle("joueur au stick : vitesse selon l'inclinaison", "à 3 % de la courbe (marche à mi-course, course à fond)") {
        val (s, v) = scene(); val p = s.player!!
        var pire = 0f; val mesures = ArrayList<String>()
        for (t in listOf(.2f, .35f, .5f, .6f, .8f, 1f)) {
            v.stick(Stick.LEFT, 0, t); repeat(45) { s.step(DT) }
            val a = ((t - .1f) / (.95f - .1f)).coerceIn(0f, 1f)
            val voulu = if (a < .5f) p.walkSpeed * a / .5f else p.walkSpeed + (p.runSpeed - p.walkSpeed) * (a - .5f) / .5f
            val ecart = abs(vitesse(s) - voulu) / voulu
            pire = max(pire, ecart); mesures.add("${f2(t)} → ${f2(vitesse(s))} m/s")
        }
        "${mesures.joinToString(", ")} ; pire écart ${f1(pire * 100)} %" to (pire <= .03f)
    }

    controle("saut à la croix, caméra au stick droit", "saut > 1 m, 160 °/s à fond (à 2 °)") {
        val (s, v) = scene(); val p = s.player!!
        v.press(Pad.CROSS); s.step(DT); v.release(Pad.CROSS)
        var haut = 0f; repeat(60) { s.step(DT); haut = max(haut, p.position.y) }
        repeat(60) { s.step(DT) }
        val y0 = s.camera.yaw
        v.stick(Stick.RIGHT, -1, 0); repeat(60) { s.step(DT) }; v.stick(Stick.RIGHT, 0, 0)
        val tour = s.camera.yaw - y0
        "saut de ${f2(haut)} m, ${f1(tour)} ° en une seconde vers la gauche" to (haut > 1f && abs(tour - 160f) <= 2f)
    }

    controle("visée au gyroscope : un tour de manette, un tour de vue", "360 ° à 1 ° près, manette tenue penchée de 35 °") {
        val (s, v) = scene()
        s.camera.mode = CameraMode.FIRST_PERSON; s.camera.gyroAim = true
        val a = Math.toRadians(35.0).toFloat(); val haut = Vec3(0f, cos(a), sin(a))
        val y0 = s.camera.yaw
        v.motion(haut * (2 * PI.toFloat()), haut * 9.80665f); repeat(60) { s.step(DT) }; v.motion(Vec3.ZERO)
        val tour = s.camera.yaw - y0
        "${f2(tour)} °, tangage ${f2(s.camera.pitch)} °" to (abs(tour - 360f) <= 1f && abs(s.camera.pitch) < .5f)
    }

    controle("partie sans fenêtre menée par --pad-script", "le joueur avance de 7 m au moins, saute, la caméra tourne de 160 °") {
        var scene: Scene? = null; var haut = 0f
        val r = game("QA", arrayOf("--headless", "--mute", "--frames", "330", "--size", "320x200",
            "--pad-script", "0.5 L 0,1; 2.5 L 0,0; 3 tap CROSS; 4.5 R 1,0; 5.5 R 0,0")) {
            ground(); player(at = vec(0, 0, 0)); scene = this
            onUpdate { haut = max(haut, player!!.position.y) }
        }
        val s = scene!!
        val avance = -r.playerPosition.z
        "${r.frames} images, arrivé en (${f2(r.playerPosition.x)} ; ${f2(r.playerPosition.z)}), saut ${f2(haut)} m, caméra à ${f1(s.camera.yaw)} °" to
            (r.frames == 330 && avance >= 7f && abs(r.playerPosition.x) < .1f && haut > 1f && abs(s.camera.yaw + 160f) <= 2f)
    }

    controle("branchements à chaud au hasard", "une manette revient à sa place, jamais deux à la même, autant d'arrivées que de départs") {
        val rnd = Random(graine + 1200)
        val s = Scene()
        val pads = List(6) { VirtualPad("P$it", id = "qa-$it") }
        val dedans = BooleanArray(6)
        val places = HashMap<String, Int>()
        var arrivees = 0; var departs = 0; var fautes = 0
        s.input.onPadConnected { p -> arrivees++; val avant = places.put(p.name, p.slot); if (avant != null && avant != p.slot) fautes++ }
        s.input.onPadDisconnected { departs++ }
        val tours = cas(400, 4000)
        repeat(tours) {
            val k = rnd.nextInt(6)
            if (dedans[k]) s.input.unplug(pads[k]) else s.input.plug(pads[k])
            dedans[k] = !dedans[k]
            if (rnd.nextInt(3) == 0) pads[rnd.nextInt(6)].press(Pad.CROSS)
            s.step(DT)
            val slots = s.input.pads.map { it.slot }
            if (slots.toSet().size != slots.size || s.input.pads.size != dedans.count { it }) fautes++
        }
        val encore = dedans.count { it }
        "$tours changements, $arrivees arrivées, $departs départs, $encore branchées, ${places.size} places, $fautes fautes" to
            (fautes == 0 && arrivees - departs == encore && places.values.toSet().size == 6)
    }

    controle("quatre manettes et des actions : coût par image", "pas un octet de plus qu'une scène sans manette") {
        val octets = { (java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).getThreadAllocatedBytes(Thread.currentThread().threadId()) }
        fun mesure(n: Int): Double {
            val s = Scene()
            val pads = List(n) { VirtualPad("P$it") }
            pads.forEach(s.input::plug)
            val saut = s.input.action("jump", Key.SPACE, Pad.CROSS); val tir = s.input.action("fire", Pad.R2)
            val marche = s.input.axes("move", Key.A, Key.D, Key.S, Key.W, Stick.LEFT)
            var puits = 0f
            val boites = Array<Number>(32) { sin(it * .2f) }
            fun image(k: Int) {
                if (n > 0) { pads[k % n].stick(Stick.LEFT, boites[k % 32], boites[(k + 8) % 32]); pads[(k + 1) % n].set(Pad.CROSS, k % 3 == 0) }
                s.step(DT)
                if (saut.pressed) puits += 1f
                puits += tir.value + marche.x + marche.y
                val p = s.input.pad; if (p != null) puits += p.leftStick.x
            }
            repeat(4000) { image(it) }
            val a = octets(); repeat(4000) { image(it) }
            return (octets() - a) / 4000.0 + puits * 0.0
        }
        val sans = mesure(0); val avec = mesure(4)
        "${f1(sans)} octets par image sans manette, ${f1(avec)} avec quatre" to (avec - sans <= 16.0)
    }
}
