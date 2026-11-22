package sillage.demo

import sillage.*
import sillage.audio.Reverb
import sillage.audio.Sound
import sillage.audio.Noise
import sillage.audio.OnePole
import sillage.audio.Sounds
import sillage.audio.Synth
import sillage.math.Vec3
import sillage.math.toDegrees
import sillage.math.toRadians
import sillage.physics.Sphere
import kotlin.math.*

/** Les Alizés : une petite île à parcourir, six coquillages à retrouver. Tout le jeu tient dans ce fichier. */
fun main(args: Array<String>) {
    // L'arrivée en vol : avec une fenêtre, ou sur demande (--intro) ; les essais sans fenêtre gardent l'île telle quelle.
    // --finale : le spectacle du trésor sans chercher les coquillages (essais, captures).
    val plain = listOf("--headless", "--smoke", "--route", "--look-at", "--no-intro")
    game("Sillage - Les Alizés", args, alizes(arrival = "--intro" in args || args.none { it in plain }, rehearsal = "--finale" in args))
}

/** L'île sans l'arrivée en vol, aussi utilisée par le contrôle qualité (module qa). */
val island: Scene.() -> Unit = alizes()

/** Ce que l'interface, l'arrivée et le spectacle se disent : quand montrer le titre, l'aide, une ligne en bas. */
private class Stage {
    var titleFrom = -10f; var titleUntil = 7f       // en secondes de la scène
    var helpFrom = 0f; var helpUntil = 14f
    var note = ""                                   // une ligne en bas de l'écran, pendant l'arrivée et le spectacle
    var show = false                                // l'arrivée ou le feu d'artifice : le marin attend
    var fireworks = false                           // le trésor est trouvé : F tire une fusée
    var treasure: (Boolean) -> Unit = {}            // montre ou range le panneau du trésor
}

private const val SEA = -.22f

/** L'île des Alizés ; [arrival] : on y arrive en vol, du large ; [rehearsal] : le spectacle du trésor commence tout seul. */
fun alizes(arrival: Boolean = false, rehearsal: Boolean = false): Scene.() -> Unit = {
    fallLimit = -6f
    // L'heure de l'île : fin d'après-midi au départ, une journée entière en dix minutes (T fait filer le temps).
    lighting.time = 16.5f
    lighting.dayLength = 600f
    water(level = SEA, color = Color.hex(0x3AAFD0), shallows = Color.hex(0xA6EDE2), reach = 7)
    reverb = Reverb.OUTDOOR                          // en plein air : un écho court et léger
    model("models/terrain.glb").solid()              // herbe, sable et bois du ponton : chacun sa surface
    val village = model("models/village.glb")
    // Le toit est solide tel qu'il est dessiné : depuis les tonneaux, on peut sauter dessus et y grimper.
    village.solid("toit")
    model("models/palmiers.glb").play("vent")
    val port = model("models/port.glb").play("vie")
    lightsAndLife(village, port)
    // Le drapeau du phare : une toile à squelette (quatre os), qui flotte dans le sens du vent.
    model("models/drapeau.glb", at = vec(4, 7.12, -3)).rotate(y = -28).play("flotter")
    // L'enseigne du ponton : une image peinte (PNG dans le GLB), coins arrondis par découpe alpha.
    model("models/enseigne.glb").rotate(y = 160).onGround(-3.55, 7.0)
    val sky = model("models/ciel.glb").play("vol")
    sky.castsShadow = false                          // nuages et mouettes : trop haut pour une ombre nette
    // Les volumes de collision (maison, phare, palmiers, tonneaux, buissons, le pont et le mât de la barque
    // qui tangue avec elle), le départ du marin et les lampes du soir sont dans les modèles, comme Blender
    // les y mettrait : des objets « col_… », un vide « spawn », des lampes (tools/create_assets.py).

    player("models/marin.glb") { runSpeed = 4.4f; jumpSpeed = 8f }

    // Six coquillages à ramasser : ils tournent sur eux-mêmes et brillent au-dessus du sol.
    val shell = shellMesh()
    for ((k, xz) in listOf(-4f to 4f, 3f to 4f, 7f to -2f, -7f to -3f, 2f to -6f, -1.8f to 9.3f).withIndex()) {
        val (x, z) = xz
        val at = groundAt(x, z) + vec(0, .65, 0)
        pickup(mesh(shell, at = at).rotate(y = k * 57).spin(63)) {
            burst(Particles.SPARKLE, at, 28)
            println("Coquillages : ${pickups.count { it.collected }}/${pickups.size}")
        }
    }

    // Corps rigides : on peut les pousser, les faire tomber, taper dans le ballon.
    // Sur un endroit plat : le centre de l'île est bosselé, une pile y basculerait (vérifié).
    beachBall(-1.5f, 3.0f)
    for (k in 0..2) crate(-2.4f, 2.6f, lift = k * .72f, yaw = k * 14.3f)  // une pile de trois caisses
    crate(-3.4f, 1.4f, size = .55f, yaw = 34.4f)
    crate(4.2f, 6.4f, yaw = 17.2f); crate(4.9f, 6.9f, size = .5f)         // sur la plage
    crate(-1.4f, 11.1f, size = .6f, yaw = 22.9f)                           // au bout du ponton
    // Une caisse à la dérive dans le lagon, près de la barque : l'eau la porte (elle est plus légère qu'elle).
    model("models/caisse.glb", at = vec(-2.3, 0, 13.4), scale = .55).rotate(y = 31, x = 8).dynamic(mass = 18, friction = .7)

    // Des articulations : une bascule sur la plage de gauche, une balançoire à chaînes sur celle de droite.
    seeSaw(-6.4f, 5.6f, yaw = 35f)
    swing(6.9f, 5.2f, yaw = -30f)

    // Le bruit de la mer tout autour du rivage, des mouettes de temps en temps.
    val waves = List(3) { Sounds.waves(it) }
    for (k in 0 until 8) { val a = k * PI.toFloat() / 4 + .2f; ambience(waves[k % 3], at = vec(cos(a) * 12.3f, 0, sin(a) * 12.3f), volume = .55, range = 70) }
    // Les mouettes crient en vol : le cri part de l'oiseau et le suit (un peu plus aigu quand il vient vers soi).
    val gulls = List(4) { Sounds.gull(it) }
    every(6, randomExtra = 10) { sky.sound(gulls.random(), part = "mouette${(0..2).random()}", volume = .5, range = 90) }

    // Touches : R recommence, M coupe le son (en QWERTY et en AZERTY), V passe en première personne.
    val stage = Stage()
    onKey(Key.R) { if (!stage.show) restart() }
    onKey(Key.M) { toggleMute() }; onKey(Key.SEMICOLON) { toggleMute() }
    camera.switchKey = Key.V

    interfaceAlizes(stage)
    seaAndSky()
    if (arrival) arrive(stage)
    treasureShow(stage, rehearsal)
}

/**
 * Ce qui vit sur l'île : l'herbe, les fleurs et les buissons au vent ; les fenêtres, la lanterne du ponton
 * et la lampe du phare qui s'allument au crépuscule ; le feu de camp de la plage (flammes, étincelles,
 * fumée, lumière qui vacille) ; la fumée de la cheminée ; les lucioles la nuit.
 */
private fun Scene.lightsAndLife(village: Node, port: Node) {
    village.sway(.07, "touffe", "fleur_*", reach = .3)
    village.sway(.035, "buisson", "buisson_fonce", "baie", reach = .8)

    val warm = Color.hex(0xFFC870)
    village.glow(Color.hex(0xFFB85C), "vitre", intensity = 2.4, atNight = true)
    village.glow(warm, "lampe", intensity = 5, atNight = true)
    port.glow(Color.hex(0xFFE9B0), "lampe", intensity = 7, atNight = true)
    village.glow(Color.hex(0xFF6A1E), "braise", intensity = 3.5)

    // Le feu de camp : toujours allumé, sa lumière compte surtout le soir.
    val fire = groundAt(8, 2.4)
    val glow = light(Color.hex(0xFF9440), intensity = 1.6, radius = 7, at = fire + vec(0, .55, 0), flicker = 1)
    emit(Particles.FIRE, at = fire + vec(0, .1, 0), rate = 70)
    emit(Particles.SPARKS, at = fire + vec(0, .25, 0), rate = 5)
    emit(Particles.SMOKE, at = fire + vec(0, .75, 0), rate = 4)
    emit(Particles.SMOKE, at = vec(-2.0, 4.75, -2.6), rate = 3.5)                     // cheminée

    // La guitare du feu de camp, à la nuit tombante : un fichier Ogg Vorbis (tools/create_music.py) lu par le
    // décodeur du moteur, à côté du jeu (un dixième de seconde) ; elle résonne un peu en plein air.
    val song = background { Sound.load("sons/guitare.ogg") }
    var guitar: Ambience? = null

    // Les lucioles sortent à la nuit, au-dessus de l'herbe.
    val fireflies = emit(Particles.FIREFLIES, at = vec(0, groundHeight(0, 0) + .7f, 0), rate = 7)
    onUpdate {
        glow.intensity = .6f + .9f * lighting.night
        fireflies.enabled = lighting.night > .45f
        if (guitar == null && song.isDone) guitar = ambience(song.get(), at = fire + vec(0, .6, 0), volume = 0, range = 30)
        guitar?.volume = lighting.night
        if (input.isDown(Key.T)) lighting.time = (lighting.time ?: 12f) + it * 1.5f    // une heure et demie par seconde
    }
}

/** Une caisse de [size] mètres posée sur le sol (ou [lift] mètres au-dessus), qu'on peut pousser. */
private fun Scene.crate(x: Float, z: Float, lift: Float = 0f, size: Float = .7f, yaw: Float = 0f) =
    model("models/caisse.glb", scale = size).rotate(y = yaw).onGround(x, z, lift + .005f)
        .dynamic(mass = 40f * size * size * size, friction = .7)

/**
 * Une bascule : une planche sur une charnière, au sommet d'un chevalet ; elle penche sous le marin, butée
 * de chaque côté. [yaw] (degrés) tourne la planche autour de la verticale.
 */
private fun Scene.seeSaw(x: Float, z: Float, yaw: Float = 0f) {
    val ground = groundHeight(x, z)
    val turn = rotation(y = yaw)
    val pivot = vec(x, ground + .45f, z)
    box(.3, .42, .34, Color.hex(0x8A5A3C), round = .03, at = vec(x, ground + .21f, z)).rotate(y = yaw).solid()
    val plank = box(3.0, .07, .36, Color.hex(0xC98B4E), round = .02, at = pivot + vec(0, .045, 0)).rotate(y = yaw)
    plank.dynamic(density = 450, friction = .8)
    for (side in floatArrayOf(-1f, 1f))                                     // poignées, collées à la planche
        cylinder(.035, .32, Color.hex(0x6E4A30), at = pivot + turn.rotate(vec(side * 1.25f, .24f, 0)))
            .dynamic(density = 700, impact = Impact.SILENT).glue(plank)
    // Pivot un peu décentré : au repos, elle penche d'un côté, un bout sur le sable, comme une vraie.
    plank.hinge(pivot + turn.rotate(vec(.08, 0, 0)), turn.rotate(Vec3.Z), min = -17, max = 17)
}

/** Une balançoire : un portique de bois, un siège pendu à deux chaînes ; le marin peut monter dessus. */
private fun Scene.swing(x: Float, z: Float, yaw: Float = 0f) {
    val ground = groundHeight(x, z)
    val turn = rotation(y = yaw)
    fun at(dx: Float, y: Float, dz: Float = 0f) = vec(x, ground + y, z) + turn.rotate(vec(dx, 0, dz))
    val wood = Color.hex(0x7A5034)
    for (side in floatArrayOf(-1f, 1f)) for (lean in floatArrayOf(-1f, 1f))
        box(.12, 2.5, .12, wood, round = .02, at = at(side * .85f, 1.22f, lean * .35f))
            .rotate(x = -lean * 16, y = yaw).solid(blocksCamera = false)                                     // en A, vers la poutre
    val beam = box(1.95, .14, .14, wood, round = .02, at = at(0f, 2.42f)).rotate(y = yaw).solid(blocksCamera = false)
    val seat = box(.7, .06, .3, Color.hex(0xD9A05B), round = .02, at = at(0f, .55f)).rotate(y = yaw)
    seat.dynamic(density = 500, friction = .8, angularDamping = .3)
    for (side in floatArrayOf(-1f, 1f))
        chain(at(side * .3f, 2.35f), at(side * .3f, .58f), links = 7, radius = .022, color = Color.hex(0x9DA3A8), start = beam, end = seat)
}

/** Un ballon de plage léger et rebondissant, qui freine vite dans l'air. */
private fun Scene.beachBall(x: Float, z: Float, radius: Float = .35f) =
    model("models/ballon.glb", scale = radius).onGround(x, z, .005)
        .dynamic(mass = .6, friction = .5, bounce = .65, shape = Sphere(radius), damping = .8, angularDamping = .1, rollingResistance = .05)

/** Un coquillage doré : deux pyramides à six pans. */
private fun shellMesh(): Mesh {
    val g = MeshBuilder(); val color = Color.hex(0xFFE6A8)
    val top = Vec3(0f, .34f, 0f); val bottom = Vec3(0f, -.24f, 0f)
    val ring = Array(6) { i -> val a = i * PI.toFloat() / 3; Vec3(cos(a) * .22f, 0f, sin(a) * .22f) }
    for (i in 0..5) { g.triangle(top, ring[i], ring[(i + 1) % 6], color); g.triangle(bottom, ring[(i + 1) % 6], ring[i], color) }
    return g.build()
}

/**
 * L'interface des Alizés, légère : le titre le temps de découvrir l'île, un compteur discret, l'aide au
 * début (puis sur H), et le message du trésor. Les panneaux prennent la taille de ce qu'ils contiennent
 * (dans n'importe quelle police) et laissent voir le jeu au travers. Échap ouvre le menu de pause.
 */
private fun Scene.interfaceAlizes(stage: Stage) {
    val ink = Color.hex(0x153D48); val paper = Color.hex(0xFFEDBE); val gold = Color.hex(0xFFCE6E)
    hud.theme.panel = ink; hud.theme.text = paper; hud.theme.accent = gold
    // Noto Sans, choisie par le propriétaire (21 novembre) : prise parmi les polices de la machine ; sans elle,
    // la police de pixels du moteur. Un vrai texte français : capitales et minuscules, accents, espaces insécables.
    hud.font = Font.find("Noto Sans") ?: Font.sans()
    val bold = Font.find("Noto Sans", bold = true) ?: hud.font
    val nb = '\u00A0'
    val title = hud.column(0, 46, Hud.Anchor.TOP, spacing = 15, place = Place.CENTER) {
        text("Les Alizés", 5, gold, bold).shadow = ink
        // Un contour d'encre fin : lisible sur les palmes comme sur le ciel.
        text("Une île, six coquillages", 1.9, paper, bold).also { it.shadow = ink; it.outline = ink; it.outlineWidth = .45f }
    }

    // Le compteur, et l'heure refaite seulement quand la minute change (le texte est relu à chaque image).
    var minute = -1; var hour = ""
    val counter = hud.column(24, 24, Hud.Anchor.TOP_RIGHT, spacing = 11, place = Place.END) {
        panel(ink, opacity = .72, radius = 8); paddingX = 18f; paddingY = 12f
        text({ "Coquillages${nb}${nb}${pickups.count { it.collected }}${nb}/${nb}${pickups.size}" }, 1.5, paper, bold)
        text({
            val m = ((lighting.time ?: 12f) * 60).toInt()
            if (m != minute) { minute = m; hour = "%d${nb}h${nb}%02d".fmt(m / 60 % 24, m % 60) }
            hour
        }, 1.25, gold)
    }

    val help = hud.column(24, 24, Hud.Anchor.BOTTOM_LEFT, spacing = 7) {
        panel(ink, opacity = .7, radius = 8); paddingX = 18f; paddingY = 12f
        text("ZQSD${nb}/${nb}WASD bouger   ·   Maj marcher   ·   Espace sauter   ·   Souris caméra", 1.5, paper)
        text({ "Molette zoom   ·   V vue   ·   T heure   ·   M son   ·   R recommencer   ·   Tab souris   ·   Échap pause" +
            if (stage.fireworks) "   ·   F feu d'artifice" else "" }, 1.25, gold)
    }
    val helpKey = hud.text("H${nb}aide", 24, 24, 1.25, paper, Hud.Anchor.BOTTOM_RIGHT)
    val treasure = hud.column(0, -68, Hud.Anchor.CENTER, spacing = 14, place = Place.CENTER) {
        panel(ink, opacity = .85, radius = 10, padding = 22)
        text("Trésor des Alizés", 3, gold, bold)
        text("Tous les coquillages sont retrouvés", 1.5, paper)
    }
    treasure.visible = false
    stage.treasure = { on -> if (on) treasure.fadeIn(.6) else treasure.fadeOut(.9) }
    // Une ligne en bas, cernée d'encre : « Espace : passer » pendant l'arrivée et le spectacle.
    hud.text({ stage.note }, 0, 30, 1.4, paper, Hud.Anchor.BOTTOM).also { it.outline = ink; it.outlineWidth = .45f }
    val hint = "Tab pour capturer la souris"
    hud.mouseHint = hint
    hud.pauseMenu("Pause", "Reprendre", "Recommencer", "Son${nb}: oui" to "Son${nb}: non", "Quitter")

    // Le titre s'efface après quelques secondes, l'aide après quatorze ; H la rappelle ou la range.
    onKey(Key.H) { if (!stage.show) stage.helpUntil = if (stage.helpUntil > time) 0f else Float.MAX_VALUE }
    onKey(Key.F3) { hud.showFps = !hud.showFps }
    onUpdate {
        val fadeTitle = min((time - stage.titleFrom) / 1.2f, (stage.titleUntil - time) / 1.5f).coerceIn(0f, 1f)
        title.opacity = fadeTitle; title.visible = fadeTitle > 0f
        val fadeHelp = if (time < stage.helpFrom || stage.show) 0f else ((stage.helpUntil - time) / .6f).coerceIn(0f, 1f)
        help.opacity = fadeHelp; help.visible = fadeHelp > 0f
        helpKey.visible = fadeHelp < 1f && !stage.show
        counter.root.background = ink.mix(Color.hex(0x3D6E7C), (lighting.night * 16).roundToInt() / 16f)   // la nuit, le panneau se détache du ciel
        hud.mouseHint = if (stage.show || stage.fireworks) null else hint
    }
}

private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

/** Où la caméra à la troisième personne se tiendra derrière [p] (même calcul que le moteur, sans obstacle). */
private fun Scene.behind(p: Player): Pair<Vec3, Vec3> {
    val aim = p.position + vec(0, camera.targetHeight, 0)
    val az = toRadians(camera.yaw.toDouble()).toFloat(); val el = toRadians(camera.elevation.toDouble()).toFloat()
    return aim + vec(sin(az) * cos(el), sin(el), cos(az) * cos(el)) * camera.distance to aim
}

/**
 * L'arrivée : la caméra vient du large au ras de l'eau, tourne autour de l'île en s'élevant, puis descend se
 * poser derrière le marin, pendant qu'une journée passe (de l'aube à l'heure du jeu). Espace l'abrège.
 */
private fun Scene.arrive(stage: Stage) {
    val p = player ?: return
    val length = 13f
    var t = 0f; var rate = 1f; var landed = false
    var hour = Float.NaN                              // l'heure du jeu, lue à la première image (--hour compris)
    p.controls = false; stage.show = true
    stage.titleFrom = 2.4f; stage.titleUntil = length - .6f; stage.helpFrom = length; stage.helpUntil = length + 14f
    stage.note = "Espace : passer"
    onUpdate { dt ->
        if (landed) return@onUpdate
        if (hour.isNaN()) hour = lighting.time ?: 12f
        if (input.pressed(Key.SPACE) || input.pressed(Key.ENTER) || input.pressed(Pad.CROSS)) rate = 10f
        t += dt * rate
        val u = smooth(t / length)
        val (home, aim) = behind(p)
        val az = toRadians(camera.yaw.toDouble()).toFloat()
        val turn = az + 4.1f * (1 - u)                // 235° autour de l'île
        val flat = sqrt((home.x - aim.x) * (home.x - aim.x) + (home.z - aim.z) * (home.z - aim.z))
        val far = flat + (32f - flat) * (1 - u).pow(1.5f)
        val height = (1 - u) * (1 - u) * 2.2f + 2 * u * (1 - u) * 11.5f + u * u * home.y
        val eye = vec(aim.x * u + sin(turn) * far, height, aim.z * u + cos(turn) * far)
        camera.lookAt(eye, vec(.5, 2.4, -.5).lerp(aim, smooth(u)))
        // La journée défile : de l'aube à l'heure du jeu (si le jeu commence de jour).
        if (hour in 7f..20f) lighting.time = 5.5f + (hour - 5.5f) * u
        if (t >= length) {
            landed = true; camera.follow(p); p.controls = true; stage.show = false; stage.note = ""
            lighting.time = hour
            stage.titleUntil = min(stage.titleUntil, time + 1.5f); stage.helpFrom = time; stage.helpUntil = time + 14f
        }
    }
}

/**
 * La vie autour de l'île : des dauphins qui bondissent au large, là où regarde la caméra ; des papillons sur
 * les fleurs le jour ; des étoiles filantes la nuit ; le pinceau du phare qui balaie le rivage.
 */
private fun Scene.seaAndSky() {
    // Trois dauphins : chacun sort de l'eau, suit un arc et replonge ; gerbe et bruit d'eau aux deux bouts.
    val pod = List(3) { model("models/dauphin.glb", scale = 1f - .13f * it).also { d -> d.visible = false } }
    val plunge = List(2) { Sounds.splash(.5f + .25f * it, it) }
    var leaps = 0
    fun leap() {
        val f = camera.forward
        val bearing = atan2(f.x, f.z) + (leaps % 3 - 1) * .2f
        val out = vec(sin(bearing), 0, cos(bearing)); val along = vec(cos(bearing), 0, -sin(bearing)) * (if (leaps % 2 == 0) 1f else -1f)
        val heading = toDegrees(atan2(along.x, along.z).toDouble())
        leaps++
        for ((k, d) in pod.withIndex()) script {
            wait(k * .42)
            val start = out * (17f + 1.4f * k) - along * (2.2f + .7f * k)
            var under = true
            d.play("nage", speed = 1.7); d.visible = true
            val arc = tween(1.25, Ease.LINEAR) { s ->
                val y = SEA - .55f + 8.6f * s * (1 - s)                       // 1,6 m au-dessus de l'eau au sommet
                d.position = start + along * (4.6f * s) + vec(0, y, 0)
                d.rotation = rotation(x = -toDegrees(atan(8.6 * (1 - 2 * s) / 4.6)), y = heading)
                if ((y < SEA) != under) {
                    under = !under
                    val at = vec(d.position.x, SEA + .05f, d.position.z)
                    burst(Particles.SPLASH, at, if (under) 36 else 22); burst(Particles.SPRAY, at, 10)
                    play(plunge[k % 2], at = at, volume = .5, range = 80)
                }
            }
            waitFor(arc); d.visible = false
        }
    }
    script { wait(21); while (true) { leap(); wait(18 + leaps * 7 % 11) } }

    // Des papillons au-dessus des fleurs, le jour.
    val wings = listOf(0xFFE27A, 0xFFFFFF, 0xA9CCFF).map {
        ParticleStyle(Color.hex(it), size = .045f, life = 7f, lifeSpread = .4f, speed = .3f, spread = 180f, area = 2.2f, drag = 1.2f, wander = .55f)
    }
    val flowers = listOf(1.5f to 3.4f, -.6f to .6f, 4.6f to 1.4f)
    val butterflies = flowers.mapIndexed { k, (x, z) -> emit(wings[k], groundAt(x, z) + vec(0, .6, 0), rate = 1) }

    // Les étoiles filantes : un point qui file en laissant sa traînée, du côté où regarde la caméra.
    val streak = ParticleStyle(Color(.85f, .92f, 1f), Color(.35f, .55f, 1f), size = .34f, endSize = .04f, life = .5f, lifeSpread = .1f,
        speed = 0f, spread = 180f, glow = true, brightness = 6f)
    var shots = 0
    every(14) {
        if (lighting.night > .7f) {
            val f = camera.forward
            val b = atan2(f.x, f.z) + (shots % 5 - 2) * .3f; val up = toRadians(9.0 + shots % 3 * 5).toFloat()      // bas sur l'horizon : la caméra regarde un peu vers le bas
            val start = camera.position + vec(sin(b) * cos(up), sin(up), cos(b) * cos(up)) * 85f
            val way = vec(cos(b), 0, -sin(b)) * (if (shots % 2 == 0) 34f else -34f) + vec(0, -9, 0)
            shots++
            val star = emit(streak, start, rate = 260)
            val fly = tween(.75, Ease.LINEAR) { s -> star.position = start + way * (.75f * s) }
            script { waitFor(fly); removeEmitter(star) }
        }
    }

    // Le pinceau du phare, la nuit : une tache de lumière qui tourne avec la lampe (un tour en six secondes).
    val beam = light(Color.hex(0xFFF0C0), intensity = 2.2, radius = 5.5, onlyAtNight = true)
    onUpdate {
        for (e in butterflies) e.enabled = lighting.night < .25f
        val a = time * 2f * PI.toFloat() / 6f
        val x = 4f + sin(a) * 8.5f; val z = -3f + cos(a) * 8.5f
        val ground = groundHeight(x, z).let { if (it.isFinite()) it else SEA }
        beam.position = vec(x, max(ground, SEA) + 1.3f, z)
    }
}

/** Le feu d'artifice : fusées qui montent en sifflant, bouquets, lueur sur l'île, détonation qui arrive après l'éclair. */
private class Fireworks(private val scene: Scene) {
    private val palette = listOf(0xFF5A4A, 0xFFC23C, 0x6CE0FF, 0x9CFF6A, 0xFF7AD9, 0xB79CFF, 0xFFF1C8).map { Color.hex(it) }
    private val stars = palette.map {
        ParticleStyle(it, it.times(.3f), size = .17f, endSize = .05f, life = 1.7f, lifeSpread = .25f, speed = 7.5f, speedSpread = .15f,
            spread = 180f, gravity = 1.5f, drag = 1.5f, glow = true, brightness = 5f, twinkle = .3f)
    }
    private val hearts = palette.map {
        ParticleStyle(it.mix(Color.WHITE, .5f), it, size = .12f, endSize = .04f, life = 1.3f, speed = 3.4f, speedSpread = .3f,
            spread = 180f, gravity = 1.2f, drag = 1.6f, glow = true, brightness = 5f, twinkle = .2f)
    }
    private val willow = ParticleStyle(Color(1f, .80f, .38f), Color(1f, .36f, .08f), size = .11f, endSize = .03f, life = 2.8f, lifeSpread = .2f,
        speed = 6.2f, speedSpread = .2f, spread = 180f, gravity = 2.6f, drag = .9f, glow = true, brightness = 4.5f, twinkle = .7f)
    private val glitter = ParticleStyle(Color.WHITE, size = .06f, endSize = .03f, life = 2.4f, lifeSpread = .5f, speed = 5f, speedSpread = .6f,
        spread = 180f, gravity = .8f, drag = 2.2f, glow = true, brightness = 6f, twinkle = 1f)
    private val trail = ParticleStyle(Color(1f, .80f, .45f), Color(1f, .35f, .10f), size = .07f, endSize = .02f, life = .55f, speed = .5f,
        spread = 180f, gravity = 1.5f, drag = 1f, glow = true, brightness = 3.5f, twinkle = .5f)

    // Les sons, synthétisés une fois : la détonation (un coup sourd et son souffle), le crépitement, le sifflement.
    private val booms by lazy {
        List(3) { v ->
            val n = Noise(40L + v); val body = OnePole(Synth.RATE, 420f - 80f * v); val snap = OnePole(Synth.RATE, 2200f)
            Synth.normalize(Synth.sound(1.9f) { _, t ->
                val thump = sin(2 * PI.toFloat() * (44f * t + 3.4f * (1 - exp(-9f * t))))
                thump * Synth.decay(t, .30f) + body.low(n.next()) * Synth.decay(t, .45f) * 1.6f + snap.low(n.next()) * Synth.decay(t, .04f) * .7f
            }, .9f)
        }
    }
    private val crackle by lazy {
        val n = Noise(77); var spark = 0f
        Synth.normalize(Synth.sound(1.4f) { _, t ->
            if (n.unit() < .004f * Synth.decay(t, .5f)) spark = 1f
            spark *= .992f
            n.next() * spark * spark
        }, .7f)
    }
    private val whistle by lazy {
        val n = Noise(5); var phase = 0f
        Synth.normalize(Synth.sound(1.05f) { _, t ->
            phase += 2 * PI.toFloat() * (650f + 1500f * t * t) / Synth.RATE
            (sin(phase) * .6f + n.next() * .12f) * min(t / .08f, 1f) * min((1.05f - t) / .25f, 1f) * (.3f + .7f * t)
        }, .5f)
    }

    /** Une fusée de [from] jusqu'à [apex], où elle éclate ; [k] choisit ses couleurs, [weeping] un saule doré. */
    fun launch(from: Vec3, apex: Vec3, k: Int, weeping: Boolean = false) = with(scene) {
        val rocket = emit(trail, from, rate = 110)
        play(whistle, at = from, volume = .3, pitch = .9 + k % 4 * .07, range = 140)
        val climb = tween(1.15, Ease.OUT) { s -> rocket.position = from.lerp(apex, s) }
        script {
            waitFor(climb); removeEmitter(rocket)
            val c = k % palette.size
            if (weeping) burst(willow, apex, 190) else { burst(stars[c], apex, 150); burst(hearts[(c + 3) % palette.size], apex, 55) }
            burst(glitter, apex, 36)
            // L'éclair éclaire l'île, puis s'éteint ; le bruit arrive après lui (340 m/s).
            val flash = light(if (weeping) Color(1f, .75f, .4f) else palette[c], intensity = 3, radius = 44, at = apex)
            tween(3, 0, over = 1.1, ease = Ease.OUT) { flash.intensity = it }
            after(1.15) { removeLight(flash) }
            val delay = (player?.position ?: camera.position).distanceTo(apex) / 340f
            after(delay) { play(booms[k % 3], at = apex, volume = 1, pitch = .9 + k % 5 * .05, range = 220) }
            if (weeping || k % 3 == 0) after(delay + .35f) { play(crackle, at = apex, volume = .45, range = 220) }
        }
    }
}

/**
 * Le trésor : les six coquillages retrouvés, la nuit tombe, la caméra recule au large et un feu d'artifice
 * part de l'île. Espace l'abrège ; ensuite, F tire une fusée quand on veut.
 */
private fun Scene.treasureShow(stage: Stage, rehearsal: Boolean) {
    val fireworks = Fireworks(this)
    var clock = -1f                                   // secondes depuis que la caméra a quitté le marin (−1 : elle le suit)
    var leaving = -1f                                 // secondes depuis qu'elle revient
    var from = Vec3.ZERO; var look = Vec3.ZERO; var side = 0f
    var skip = false; var fired = 0
    fun orbit(t: Float) = vec(sin(side + t * .1f) * 25f, 3.2f, cos(side + t * .1f) * 25f)

    /** La fusée numéro [k] : elle part de l'île et éclate au-dessus, plus ou moins haut. */
    fun rocket(k: Int, wide: Float = 1f, weeping: Boolean = false) {
        val a = k * 2.4f; val r = (3f + k % 4 * 2f) * wide
        val x = sin(a) * r; val z = cos(a) * r * .8f
        val ground = groundHeight(x, z).let { if (it.isFinite()) max(it, SEA) else SEA }
        fireworks.launch(vec(x, ground, z), vec(x * .7f, 12f + k % 5 * 1.5f, z * .7f), k, weeping)
    }

    fun show() {
        val p = player ?: return
        if (stage.show) return
        stage.show = true; skip = false
        script {
            fun passed(seconds: Float): () -> Boolean { val until = time + seconds; return { skip || time >= until } }
            p.controls = false; p.celebrate(2.4)
            stage.treasure(true)
            waitUntil(passed(2.8f))
            // La nuit tombe (si elle n'est pas déjà là), la caméra recule au large, du côté où elle était.
            val now = lighting.time ?: 12f; val hour = now.mod(24f)
            if (hour in 4.8f..20.9f) tween(now, now - hour + 21.4f, over = 4.5) { lighting.time = it }
            from = camera.position; look = p.position + vec(0, camera.targetHeight, 0); side = atan2(from.x, from.z)
            clock = 0f; leaving = -1f
            stage.note = "Espace : reprendre"
            waitUntil(passed(1.6f)); stage.treasure(false); waitUntil(passed(2.2f))
            var k = 0
            while (k < 13 && !skip) { rocket(k); if (k % 4 == 3) rocket(k + 20, 1.3f); waitUntil(passed(.7f + k % 3 * .3f)); k++ }
            if (!skip) waitUntil(passed(.9f))
            var b = 0
            while (b < 9 && !skip) { rocket(30 + b, 1.5f, weeping = b >= 6); waitUntil(passed(.2f)); b++ }   // le bouquet
            waitUntil(passed(5f))
            stage.note = ""; leaving = 0f
            waitUntil { clock < 0f }
            p.controls = true; stage.show = false; stage.fireworks = true
            stage.helpFrom = time; stage.helpUntil = time + 9f
        }
    }
    onUpdate { dt ->
        if (clock < 0f) return@onUpdate
        val p = player ?: return@onUpdate
        if (leaving < 0f && (input.pressed(Key.SPACE) || input.pressed(Key.ENTER) || input.pressed(Pad.CROSS))) skip = true
        clock += dt
        val out = smooth(clock / 3.5f)
        var eye = from.lerp(orbit(clock), out); var at = look.lerp(vec(0, 9.4, 0), out)
        if (leaving >= 0f) {
            leaving += dt
            val (home, aim) = behind(p)
            val back = smooth(leaving / 2.4f)
            eye = eye.lerp(home, back); at = at.lerp(aim, back)
            if (leaving >= 2.4f) { clock = -1f; camera.follow(p); return@onUpdate }
        }
        camera.lookAt(eye, at)
    }
    whenever({ pickups.isNotEmpty() && pickups.all { it.collected } }) { show() }
    if (rehearsal) after(1.5) { show() }
    // Après le trésor : F tire une fusée devant le marin.
    onKey(Key.F) {
        val p = player
        if (stage.fireworks && !stage.show && p != null) {
            val f = camera.forward; val ahead = vec(f.x, 0, f.z).normalized() * 8f
            val base = p.position + ahead
            fireworks.launch(vec(base.x, max(p.position.y, SEA), base.z), vec(base.x, p.position.y + 11f + fired % 4 * 1.5f, base.z), fired, weeping = fired % 5 == 4)
            fired++
        }
    }
}
