package sillage.temple

import sillage.*
import sillage.audio.Noise
import sillage.audio.OnePole
import sillage.audio.Reverb
import sillage.audio.Sound
import sillage.audio.Sounds
import sillage.audio.Synth
import sillage.math.Quat
import sillage.math.Vec3
import kotlin.math.*

/**
 * L'oracle endormi : un temple dorique en ruine sur une île, une nuit de lune et de brume. Cinq braseros à
 * rallumer ; au cinquième, l'oracle s'éveille, la brume se retire et la colonne tombée se relève.
 * On le parcourt à la première personne. Tout le jeu tient dans ce fichier ; les modèles viennent de
 * tools/create_temple.py.
 */
fun main(args: Array<String>) { game("Sillage - L'oracle endormi", args, oracle) }

private const val LAKE = -.35f
private val TEAL = Color.hex(0x5FE6D2)
private val GOLD = Color.hex(0xFFD9A0)

val oracle: Scene.() -> Unit = {
    fallLimit = -8f
    night()
    water(level = LAKE, color = Color.hex(0x050D14), shallows = Color.hex(0x12343A), reach = 5)
    reverb = Reverb.HALL                              // la pierre et le vide : chaque pas résonne un peu

    // Le sol et le temple sont solides tels qu'ils sont dessinés (les degrés font 30 cm : on les monte en marchant).
    model("models/ile.glb").solid().texture(SLABS, "dalle")
    val temple = model("models/temple.glb")
    temple.solid("marbre", "marbre_use", "marbre_sombre", "bronze_vert")
    temple.texture(Textures.LEAVES, "lierre")
    temple.texture(MARBLE, "marbre", "marbre_use", "marbre_sombre", "parement", "parement_clair", "gravat")
    temple.sway(.05, "touffe", "fleur_*", reach = .4)
    temple.sway(.02, "lierre", reach = .25)

    grove()
    val p = player(at = groundAt(0, 29.5)) { runSpeed = 3.4f; walkSpeed = 1.6f; jumpSpeed = 6.5f }
    p.view = CameraMode.FIRST_PERSON

    val stage = Stage()
    val fires = listOf(vec(-2.9, 0, 27.5), vec(2.9, 0, 27.5), vec(-6.3, 0, 13.0), vec(6.3, 0, 13.0), vec(2.1, .9, -3.2))
        .mapIndexed { k, at -> brazier(k, if (at.y > 0f) at else groundAt(at.x, at.z), stage) }
    mist()
    voices(stage)
    awakening(stage, fires)
    inscriptions(stage, fires)

    onKey(Key.R) { if (!stage.awake) restart() }
    onKey(Key.M) { toggleMute() }; onKey(Key.SEMICOLON) { toggleMute() }
    onKey(Key.F3) { hud.showFps = !hud.showFps }
}

// Deux textures peintes pour ce lieu (elles se raccordent à leurs bords : sommes de sinus à fréquences entières).
private fun ripple(u: Float, v: Float, a: Int, b: Int, phase: Float) = sin(2 * PI.toFloat() * (a * u + b * v) + phase)

/** Un marbre : un grain fin, de larges nuages, quelques veines sombres qui serpentent. */
private val MARBLE: Texture by lazy {
    Texture.paint("marbre", tile = 2.6, strength = .85, size = 512) { u, v ->
        val cloud = .5f * ripple(u, v, 1, 2, 0f) * ripple(u, v, 2, -1, 1.3f) + .3f * ripple(u, v, 3, 1, 2.1f)
        val wander = .9f * ripple(u, v, 2, 3, .7f) + .5f * ripple(u, v, 5, -2, 2.9f)
        val vein = abs(sin(2 * PI.toFloat() * (2 * u + v) + wander * 1.6f)).pow(.35f)          // 0 sur la veine
        val fine = .5f * ripple(u, v, 23, 17, 1f) * ripple(u, v, 19, -29, 2f) + .5f * ripple(u, v, 41, 37, .4f)
        val k = (.5f + .05f * cloud + .018f * fine - .13f * (1f - vein).coerceAtLeast(0f).pow(3)).coerceIn(.2f, .8f)
        Color(k, k, k * 1.01f)
    }
}

/** Un dallage de grandes dalles inégales à joints sombres. */
private val SLABS: Texture by lazy {
    Texture.paint("dalles", tile = 2.4, strength = .9, size = 512, mapping = Texture.Mapping.FACES) { u, v ->
        val row = (v * 3).toInt().coerceIn(0, 2)
        val cuts = arrayOf(floatArrayOf(0f, .46f, 1f), floatArrayOf(.22f, .7f, 1.22f), floatArrayOf(.1f, .58f, 1.1f))[row]
        val x = if (u < cuts[0]) u + 1f else u
        val slab = if (x < cuts[1]) 0 else 1
        val edge = minOf(abs(v * 3 - row), abs(v * 3 - row - 1)) / 3f
        val joint = minOf(edge, abs(x - cuts[0]), abs(x - cuts[1]), abs(x - cuts[2]))
        val tone = .5f + .07f * sin(row * 2.3f + slab * 4.1f + 1f) + .025f * ripple(u, v, 7, 5, 1f) * ripple(u, v, 11, -6, 2f) + .015f * ripple(u, v, 31, 27, .5f)
        val k = tone * (1f - .5f * (1f - (joint / .012f).coerceIn(0f, 1f)))
        Color(k, k, k)
    }
}

/** Ce que les parties de la scène se disent. */
private class Stage {
    var lit = 0                                       // braseros allumés
    var awake = false                                 // l'oracle s'est éveillé
    var drone: Ambience? = null
}

/**
 * La lumière, réglée à la main (pas d'horloge) : la lune haute pour seule clarté, froide ; des ombres presque
 * noires, un ciel d'encre où l'horizon se perd dans une brume verte ; pas de trait d'encre, peu de couleur,
 * beaucoup de halo. L'autre démo est un dessin au soleil ; celle-ci, une gravure dans le noir.
 */
private fun Scene.night() {
    lighting.sunDirection = Vec3(.42f, .62f, .66f).normalized()
    lighting.sunColor = Color(.58f, .70f, 1.0f)
    lighting.shadeColor = Color(.02f, .035f, .075f)
    lighting.skyAmbient = Color(.03f, .05f, .09f)
    lighting.groundAmbient = Color(.03f, .045f, .05f)
    lighting.zenith = Color(.003f, .006f, .022f)
    lighting.skyMiddle = Color(.012f, .028f, .06f)
    lighting.horizon = Color(.055f, .10f, .115f)
    lighting.sunGlow = Color(.35f, .48f, .75f)
    lighting.night = 1f                               // les étoiles, les lueurs de nuit
    lighting.fogStart = 3f; lighting.fogEnd = 40f
    lighting.outlines = 0f; lighting.saturation = .8f; lighting.vignette = .55f; lighting.glow = 1.5f
    lighting.softness = .9f                           // la lumière tourne autour des formes : de la pierre, pas un dessin
    lighting.wind = Vec3(.22f, 0f, .1f)
    lighting.shadowRange = 26f; lighting.shadowCascades = 2            // la brume cache le lointain : deux carrés d'ombre suffisent
}

/** Les cyprès de la voie, des oliviers sur les pentes, deux statues sans tête qui gardent l'entrée. */
private fun Scene.grove() {
    for ((k, z) in listOf(31.5f, 26.5f, 12.4f).withIndex()) for (side in floatArrayOf(-1f, 1f)) {
        val x = side * (if (k == 2) 8.6f else 5.6f + k % 2 * .5f)          // les derniers s'écartent : les braseros du parvis sont là
        model("models/cypres.glb", scale = 1f + .12f * ((k * 3 + if (side > 0) 1 else 0) % 3)).onGround(x, z).rotate(y = k * 47)
            .also { it.texture(Textures.LEAVES, "cypres"); it.texture(Textures.BARK, "tronc"); it.sway(.06, "cypres", reach = 5); it.collider(capsuleShape(.3, 3), vec(0, 1.5, 0), blocksCamera = false) }
    }
    for ((k, xz) in listOf(-13f to 6f, 14f to 10f, -15f to -9f, 12f to -14f, -9f to 22f, 16f to 24f, 3f to -19f, -19f to 16f).withIndex()) {
        if (k % 3 == 2) model("models/cypres.glb", scale = 1.2f).onGround(xz.first, xz.second)
            .also { it.texture(Textures.LEAVES, "cypres"); it.sway(.06, "cypres", reach = 5); it.collider(capsuleShape(.3, 3), vec(0, 1.5, 0), blocksCamera = false) }
        else model("models/olivier.glb", scale = 1f + k % 3 * .15f).onGround(xz.first, xz.second).rotate(y = k * 71)
            .also { it.texture(Textures.LEAVES, "olivier"); it.texture(Textures.BARK, "tronc"); it.sway(.04, "olivier", reach = 2.5); it.collider(capsuleShape(.28, 2), vec(0, 1, 0), blocksCamera = false) }
    }
    for (side in floatArrayOf(-1f, 1f))
        model("models/statue.glb").onGround(side * 4.3f, 23.6f).rotate(y = if (side < 0) 90 else -90)
            .also { it.texture(MARBLE, "marbre", "marbre_use", "marbre_sombre"); it.solid() }
}

/** La flamme d'un brasero : un feu grégeois, vert d'eau, qui ne chauffe pas. */
private val FLAME = ParticleStyle(Color(.75f, 1f, .92f), Color(.05f, .45f, .55f), size = .11f, endSize = .02f, life = .8f, speed = 1.1f,
    spread = 12f, area = .14f, gravity = -1.5f, drag = 1.5f, wander = .3f, wind = .2f, glow = true, brightness = 2.8f)
private val EMBERS = ParticleStyle(Color(.7f, 1f, .9f), Color(.2f, .7f, .8f), size = .028f, endSize = .012f, life = 2.2f, speed = 1.6f,
    spread = 28f, gravity = -.5f, drag = .8f, wander = .8f, wind = .4f, glow = true, brightness = 4f, twinkle = .6f)

private class Brazier(val at: Vec3, val node: Node) { var lit = false }

/**
 * Un brasero éteint : on s'en approche, il s'allume. Chacun donne sa note (une gamme qui monte), sa lumière
 * et ses flammes ; le chiffre de l'interface suit.
 */
private fun Scene.brazier(k: Int, at: Vec3, stage: Stage): Brazier {
    val node = model("models/brasero.glb", at = at).rotate(y = k * 40)
    node.collider(capsuleShape(.3, 1.2), vec(0, .6, 0), surface = "stone", blocksCamera = false)
    val b = Brazier(at, node)
    val notes = floatArrayOf(1f, 1.125f, 1.25f, 1.5f, 1.6667f)       // ré, mi, fa dièse, la, si : une gamme sans demi-ton
    zone(sphereShape(1.7), at + vec(0, 1, 0), onEnter = { who ->
        if (who is Player && !b.lit) {
            b.lit = true; stage.lit++
            node.glow(TEAL, "braise", intensity = 5)
            emit(FLAME, at + vec(0, 1.2, 0), rate = 55); emit(EMBERS, at + vec(0, 1.3, 0), rate = 4)
            burst(EMBERS, at + vec(0, 1.3, 0), 30)
            val l = light(TEAL, intensity = 0, radius = 8, at = at + vec(0, 1.9, 0), flicker = .5)
            tween(0, 1.1, over = 1.2, ease = Ease.OUT) { l.intensity = it }
            play(chime, at = at + vec(0, 1.2, 0), volume = .8, pitch = notes[k % notes.size], range = 60)
            stage.drone?.volume = .12f + .1f * stage.lit
        }
    })
    return b
}

/** La brume qui traîne au ras du sol entre les colonnes, et quelques lueurs qui errent : on n'est pas tout à fait seul. */
private fun Scene.mist() {
    val veil = ParticleStyle(Color(.30f, .40f, .44f), Color(.22f, .30f, .36f), size = 1.4f, endSize = 3.2f, life = 11f, lifeSpread = .3f, speed = .12f,
        spread = 80f, area = 13f, gravity = -.01f, drag = .4f, wander = .06f, wind = .5f, opacity = .075f)
    for (at in listOf(vec(0, 1.2, 2), vec(0, .5, 19), vec(-9, .4, 8), vec(10, .4, -6), vec(0, .3, 30))) emit(veil, at, rate = 1.1)
    val wisp = ParticleStyle(TEAL, Color(.3f, .6f, 1f), size = .045f, endSize = .02f, life = 7f, lifeSpread = .4f, speed = .25f, spread = 180f,
        area = 9f, drag = .5f, wander = .5f, glow = true, brightness = 4.5f, twinkle = .9f)
    emit(wisp, vec(0, 2.6, 0), rate = 2.2)
    emit(wisp, vec(0, 1.2, 19), rate = 1)
}

// Les sons, synthétisés ici : une cloche de bronze pour chaque feu, le vent, le bourdon de l'oracle, une chouette.
private val chime: Sound by lazy {
    Synth.normalize(Synth.sound(4.5f) { _, t ->
        Synth.modes(t, Triple(293.7f, 1f, 1.9f), Triple(293.7f * 2.76f, .5f, 1.1f), Triple(293.7f * 5.4f, .25f, .5f), Triple(293.7f * 8.93f, .12f, .25f)) *
            min(t / .004f, 1f)
    }, .8f)
}
private val wind: Sound by lazy {
    val n = Noise(11); val low = OnePole(Synth.RATE, 420f); val slow = OnePole(Synth.RATE, 1100f)
    Synth.loop(Synth.normalize(Synth.sound(12f) { _, t ->
        val gust = .55f + .3f * sin(t * 2 * PI.toFloat() / 6f) + .15f * sin(t * 2 * PI.toFloat() / 2.4f + 1f)
        (low.low(n.next()) * .8f + slow.low(n.next()) * .2f) * gust
    }, .6f), 1.5f)
}
private val drone: Sound by lazy {
    Synth.loop(Synth.normalize(Synth.sound(8f) { _, t ->
        val w = 2 * PI.toFloat() * t
        (sin(w * 73.42f) + .6f * sin(w * 110.0f + .3f * sin(w * .25f)) + .35f * sin(w * 146.83f) + .2f * sin(w * 220.2f)) * (.8f + .2f * sin(w * .125f))
    }, .5f), 1f)
}
private val owl: Sound by lazy {
    Synth.normalize(Synth.sound(1.5f) { _, t ->
        fun hoot(at: Float, hz: Float, len: Float): Float { val u = t - at; return if (u < 0f || u > len) 0f else sin(2 * PI.toFloat() * hz * u) * sin(PI.toFloat() * u / len).pow(2) }
        hoot(0f, 392f, .28f) + hoot(.42f, 349f, .7f)
    }, .5f)
}

/** Le vent autour de l'île, le bourdon qui monte avec les feux, une chouette de loin en loin. */
private fun Scene.voices(stage: Stage) {
    ambience(wind, volume = .32)
    stage.drone = ambience(drone, at = vec(0, 2, -4.6), volume = .12, range = 70)
    var hoots = 0
    every(19) { if (!stage.awake) { val a = hoots++ * 2.3f; play(owl, at = vec(cos(a) * 24f, 5, sin(a) * 24f), volume = .5, pitch = 1 - hoots % 3 * .04, range = 90) } }
}

/**
 * L'éveil : la vasque de l'autel s'illumine, la brume recule jusqu'au lac, et les cinq tambours tombés devant
 * la façade se relèvent un à un pour refaire leur colonne, le chapiteau en dernier.
 */
private fun Scene.awakening(stage: Stage, fires: List<Brazier>) {
    // Les tambours gisent où ils sont tombés ; leur place dans la colonne est au-dessus de (1 ; 10,5).
    val foot = vec(1.0, .9, 10.5)
    val lying = listOf(vec(2.6, 0, 13.4) to rotation(z = 90, y = 20), vec(4.1, 0, 15.2) to rotation(z = 90, y = -35), vec(.4, 0, 14.6) to rotation(x = 90, y = 10),
        vec(-1.9, 0, 13.2) to rotation(z = 84, y = 60), vec(7.4, 0, 15.6) to rotation(x = 12, y = 0))
    val drums = lying.mapIndexed { k, (xz, turn) ->
        val h = 1.08f
        model("models/tambour.glb", scale = 1f).also { d ->
            d.texture(MARBLE, "marbre", "marbre_sombre")
            d.rotation = turn
            d.at(groundAt(xz.x, xz.z) + vec(0, if (k == 4) h / 2 else .5, 0)); d.kinematic()
        }
    }
    val capital = model("models/chapiteau.glb").also { c ->
        c.texture(MARBLE, "marbre")
        c.rotation = rotation(x = 100, y = 30); c.at(groundAt(3.2, 16.9) + vec(0, .45, 0)); c.kinematic()
    }
    val pillar = ParticleStyle(GOLD, TEAL, size = .09f, endSize = .02f, life = 3.2f, speed = 3.2f, spread = 6f, area = .35f, gravity = -1.2f, drag = .2f,
        wander = .3f, glow = true, brightness = 5f, twinkle = .5f)
    val dust = ParticleStyle(Color(.5f, .5f, .48f), size = .12f, endSize = .5f, life = 1.6f, speed = 1.2f, spread = 85f, area = .5f, gravity = .4f, drag = 2.2f, opacity = .3f)

    /** Un bloc s'élève, tourne droit, vient au-dessus de sa place et s'y pose. */
    suspend fun Script.raise(n: Node, to: Vec3) {
        val from = n.position; val turn = n.rotation
        val above = vec(to.x, to.y + 1.6f, to.z)
        waitFor(tween(2.0, Ease.IN_OUT) { s -> n.position = from.lerp(vec(from.x, above.y, from.z), s); n.rotation = turn.slerp(Quat.IDENTITY, s) })
        waitFor(tween(1.3, Ease.IN_OUT) { s -> n.position = vec(from.x, above.y, from.z).lerp(above, s) })
        waitFor(tween(.55, Ease.IN) { s -> n.position = above.lerp(to, s) })
        burst(dust, to - vec(0, .5, 0), 16); play(Sounds.knock(1), at = to, volume = 1, pitch = .45, range = 70)
    }

    once({ fires.all { it.lit } }) {
        stage.awake = true
        script {
            wait(1.4)
            val altar = vec(0, 2.25, -4.6)
            val glowing = light(GOLD, intensity = 0, radius = 16, at = altar + vec(0, .6, 0), flicker = .15)
            tween(0, 2.2, over = 4) { glowing.intensity = it }
            emit(pillar, altar, rate = 60)
            play(chime, at = altar, volume = 1, pitch = .5, range = 120); play(chime, at = altar, volume = .7, pitch = .75, range = 120)
            // La brume recule, la nuit s'éclaircit d'un cran.
            tween(40, 150, over = 9) { lighting.fogEnd = it }; tween(3, 22, over = 9) { lighting.fogStart = it }
            tween(.8, 1.0, over = 9) { lighting.saturation = it }
            wait(3)
            for ((k, d) in drums.withIndex()) { raise(d, foot + vec(0, .54f + 1.08f * k, 0)); wait(.15) }
            raise(capital, foot + vec(0, 5.4f + .22f, 0))
            play(chime, at = foot + vec(0, 5, 0), volume = 1, pitch = 1f, range = 120); play(chime, at = foot + vec(0, 5, 0), volume = .8, pitch = 1.5f, range = 120)
            burst(pillar, foot + vec(0, 5.9, 0), 120)
        }
    }
    // Les tambours font 1,08 m une fois debout : cinq font le fût de 5,4 m.
    for (d in drums) d.scale = 1.08f
}

/** L'interface, discrète : le titre à l'arrivée, le compte des feux, une ligne quand l'oracle s'éveille. */
private fun Scene.inscriptions(stage: Stage, fires: List<Brazier>) {
    val bone = Color.hex(0xE9E2CF); val ink = Color.hex(0x05090D)
    hud.theme.panel = ink; hud.theme.text = bone; hud.theme.accent = TEAL
    hud.font = Font.find("Noto Serif") ?: Font.serif()
    val bold = Font.find("Noto Serif", bold = true) ?: hud.font
    val nb = ' '
    val title = hud.column(0, 150, Hud.Anchor.TOP, spacing = 16, place = Place.CENTER) {
        text("L’oracle endormi", 4.4, bone, bold).shadow = ink
        text("Cinq feux à rallumer", 1.7, TEAL).also { it.outline = ink; it.outlineWidth = .4f }
    }
    hud.text({ "Feux$nb$nb${stage.lit}$nb/$nb${fires.size}" }, 28, 26, 1.5, bone, Hud.Anchor.TOP_RIGHT).also { it.outline = ink; it.outlineWidth = .4f }
    val help = hud.text("ZQSD$nb/${nb}WASD marcher   ·   Souris regarder   ·   Espace sauter   ·   M son   ·   R recommencer   ·   Échap pause", 0, 26, 1.2, bone, Hud.Anchor.BOTTOM)
        .also { it.outline = ink; it.outlineWidth = .4f }
    val woken = hud.column(0, 150, Hud.Anchor.TOP, spacing = 14, place = Place.CENTER) {
        text("L’oracle s’éveille", 3.6, GOLD, bold).shadow = ink
        text("La colonne se relève", 1.6, bone).also { it.outline = ink; it.outlineWidth = .4f }
    }
    woken.visible = false
    hud.mouseHint = "Tab pour capturer la souris"
    hud.pauseMenu("Pause", "Reprendre", "Recommencer", "Son${nb}: oui" to "Son${nb}: non", "Quitter")
    once({ stage.awake }) { after(1.5) { woken.fadeIn(1.2); woken.fadeOut(1.5, after = 9) } }
    onUpdate {
        val t = min((time - .8f) / 1.5f, (9f - time) / 2f).coerceIn(0f, 1f)
        title.opacity = t; title.visible = t > 0f
        val h = ((16f - time) / 1.5f).coerceIn(0f, 1f)
        help.opacity = h; help.visible = h > 0f
    }
}
