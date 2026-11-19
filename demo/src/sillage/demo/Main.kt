package sillage.demo

import sillage.*
import sillage.audio.Reverb
import sillage.audio.Sound
import sillage.audio.Sounds
import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.Sphere
import kotlin.math.*

/** Les Alizés : une petite île à parcourir, six coquillages à retrouver. Tout le jeu tient dans ce fichier. */
fun main(args: Array<String>) { game("Sillage - Les Alizés", args, island) }

/** L'île, aussi utilisée par le contrôle qualité (module qa). */
val island: Scene.() -> Unit = {
    fallLimit = -6f
    // L'heure de l'île : fin d'après-midi au départ, une journée entière en dix minutes (T fait filer le temps).
    lighting.time = 16.5f
    lighting.dayLength = 600f
    water(level = -.22, color = Color.hex(0x3AAFD0), shallows = Color.hex(0xA6EDE2), reach = 7)
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
    model("models/enseigne.glb", at = vec(-3.55, groundHeight(-3.55, 7.0), 7.0)).rotate(y = 160)
    block(-3.55f, 7.0f, 1.7f, 1.9f, .25f, thin = true)
    val sky = model("models/ciel.glb").play("vol")
    sky.castsShadow = false                          // nuages et mouettes : trop haut pour une ombre nette
    // La barque se visite et tangue avec la houle : son pont elliptique en deux boîtes, son mât.
    port.collider(Box(.7f, .25f, .75f), vec(0, -.04, 0), part = "bateau")
    port.collider(Box(.42f, .25f, 1.4f), vec(0, -.04, 0), part = "bateau")
    port.collider(Capsule(.07f, 1.75f), vec(0, 1.85, .2), part = "bateau", blocksCamera = false)

    // Volumes de collision du village (le modèle est d'un seul tenant) : x, z, largeur, hauteur, profondeur.
    // L'avant-toit est à 1,65 m : le marin (1,8 m) ne passe pas dessous, la maison va jusqu'au bord du toit.
    block(-3f, -2f, 3.4f, 1.65f, 3.0f, base = 1.45f)                       // maison
    block(-2f, -2.6f, .5f, 1.5f, .5f, base = 3.2f)                         // cheminée
    block(4f, -3f, 1.4f, 4.5f, 1.4f, base = 2.7f)                          // phare
    for ((x, z) in listOf(-5f to -1f, -6f to 3f, 6f to 1f, 1f to -5f, -3f to -4f))
        block(x, z, .4f, 3f, .4f, base = 1.45f, thin = true)               // palmiers
    for ((x, z) in listOf(-1.25f to -1.5f, -1.2f to -2.15f)) block(x, z, .55f, .75f, .55f, thin = true)   // tonneaux
    block(-1.3f, -2.9f, .55f, .95f, .55f, thin = true)                     // caisses empilées
    block(-.75f, -3f, .4f, .4f, .4f, thin = true)                          // petite caisse
    block(-4f, -.35f, 1f, .45f, .34f, thin = true)                         // banc
    block(4.6f, 8.4f, .9f, .45f, .9f, thin = true)                         // château de sable
    for ((x, z) in listOf(-2f to -4.25f, 5.5f to -4.6f, 2.6f to 1.3f, -.3f to -3.7f, -6.4f to -1.8f, 6.6f to .1f))
        block(x, z, .9f, .65f, .8f, thin = true)                           // buissons
    block(-.85f, 12.35f, .2f, 1.8f, .2f, base = 1.09f, thin = true)        // lanterne du ponton

    player("models/marin.glb", at = vec(-1, 2.2, 4)) { runSpeed = 4.4f; jumpSpeed = 8f }

    // Six coquillages à ramasser : ils tournent sur eux-mêmes et brillent au-dessus du sol.
    val shell = shellMesh()
    for ((k, xz) in listOf(-4f to 4f, 3f to 4f, 7f to -2f, -7f to -3f, 2f to -6f, -1.8f to 9.3f).withIndex()) {
        val (x, z) = xz
        val at = vec(x, groundHeight(x, z) + .65f, z)
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
    onKey(Key.R) { restart() }
    onKey(Key.M) { toggleMute() }; onKey(Key.SEMICOLON) { toggleMute() }
    camera.switchKey = Key.V

    interfaceAlizes()
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
    light(warm, intensity = 1.6, radius = 6, at = vec(-.85, 2.5, 12.35), onlyAtNight = true, flicker = .15)    // lanterne du ponton
    light(Color.hex(0xFFB060), intensity = 1.2, radius = 5, at = vec(-3, 2.4, -.3), onlyAtNight = true)        // fenêtres de la maison

    // Le feu de camp : toujours allumé, sa lumière compte surtout le soir.
    val fire = vec(8, groundHeight(8, 2.4), 2.4)
    val glow = light(Color.hex(0xFF9440), intensity = 1.6, radius = 7, at = fire + vec(0, .55, 0), flicker = 1)
    emit(Particles.FIRE, at = fire + vec(0, .1, 0), rate = 70)
    emit(Particles.SPARKS, at = fire + vec(0, .25, 0), rate = 5)
    emit(Particles.SMOKE, at = fire + vec(0, .75, 0), rate = 4)
    block(8f, 2.4f, .95f, .5f, .95f, thin = true)
    emit(Particles.SMOKE, at = vec(-2.0, 4.75, -2.6), rate = 3.5)                     // cheminée

    // La guitare du feu de camp, à la nuit tombante : un fichier Ogg Vorbis (tools/create_music.py) lu par le
    // décodeur du moteur, à côté du jeu (un dixième de seconde) ; elle résonne un peu en plein air.
    val song = java.util.concurrent.CompletableFuture.supplyAsync { Sound.load("sons/guitare.ogg") }
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

/** Un volume de collision invisible posé sur le terrain (ou à la hauteur [base]). */
private fun Scene.block(x: Float, z: Float, width: Float, height: Float, depth: Float, base: Float? = null, thin: Boolean = false) =
    collider(Box(width / 2, height / 2, depth / 2), vec(x, (base ?: groundHeight(x, z)) + height / 2, z), blocksCamera = !thin)

/** Une caisse de [size] mètres posée sur le sol (ou [lift] mètres au-dessus), qu'on peut pousser. */
private fun Scene.crate(x: Float, z: Float, lift: Float = 0f, size: Float = .7f, yaw: Float = 0f) =
    model("models/caisse.glb", at = vec(x, groundHeight(x, z) + size / 2 + lift + .01f, z), scale = size)
        .rotate(y = yaw).dynamic(mass = 40f * size * size * size, friction = .7)

/**
 * Une bascule : une planche sur une charnière, au sommet d'un chevalet ; elle penche sous le marin, butée
 * de chaque côté. [yaw] (degrés) tourne la planche autour de la verticale.
 */
private fun Scene.seeSaw(x: Float, z: Float, yaw: Float = 0f) {
    val ground = groundHeight(x, z)
    val turn = Quat.rotationY(yaw * PI.toFloat() / 180f)
    val pivot = vec(x, ground + .45f, z)
    box(.3, .42, .34, Color.hex(0x8A5A3C), round = .03, at = vec(x, ground + .21f, z)).rotate(y = yaw).solid()
    val plank = box(3.0, .07, .36, Color.hex(0xC98B4E), round = .02, at = pivot + vec(0, .045, 0)).rotate(y = yaw)
    plank.dynamic(density = 450, friction = .8)
    for (side in floatArrayOf(-1f, 1f))                                     // poignées, collées à la planche
        cylinder(.035, .32, Color.hex(0x6E4A30), at = pivot + turn.rotate(vec(side * 1.25f, .24f, 0)))
            .dynamic(density = 700, impact = Impact.SILENT).glue(plank)
    // Pivot un peu décentré : au repos, elle penche d'un côté, un bout sur le sable, comme une vraie.
    plank.hinge(pivot + turn.rotate(vec(.08, 0, 0)), turn.rotate(Vec3.Z)).limits(-.3f, .3f)
}

/** Une balançoire : un portique de bois, un siège pendu à deux chaînes ; le marin peut monter dessus. */
private fun Scene.swing(x: Float, z: Float, yaw: Float = 0f) {
    val ground = groundHeight(x, z)
    val turn = Quat.rotationY(yaw * PI.toFloat() / 180f)
    fun at(dx: Float, y: Float, dz: Float = 0f) = vec(x, ground + y, z) + turn.rotate(vec(dx, 0, dz))
    val wood = Color.hex(0x7A5034)
    for (side in floatArrayOf(-1f, 1f)) for (lean in floatArrayOf(-1f, 1f))
        box(.12, 2.5, .12, wood, round = .02, at = at(side * .85f, 1.22f, lean * .35f))
            .also { it.rotation = turn * Quat.axisAngle(Vec3.X, -lean * .28f) }.solid(blocksCamera = false)   // en A, vers la poutre
    val beam = box(1.95, .14, .14, wood, round = .02, at = at(0f, 2.42f)).rotate(y = yaw).solid(blocksCamera = false)
    val seat = box(.7, .06, .3, Color.hex(0xD9A05B), round = .02, at = at(0f, .55f)).rotate(y = yaw)
    seat.dynamic(density = 500, friction = .8, angularDamping = .3)
    for (side in floatArrayOf(-1f, 1f))
        chain(at(side * .3f, 2.35f), at(side * .3f, .58f), links = 7, radius = .022, color = Color.hex(0x9DA3A8), start = beam, end = seat)
}

/** Un ballon de plage léger et rebondissant, qui freine vite dans l'air. */
private fun Scene.beachBall(x: Float, z: Float, radius: Float = .35f) =
    model("models/ballon.glb", at = vec(x, groundHeight(x, z) + radius + .01f, z), scale = radius)
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
 * début (puis sur H), et le message du trésor. Les panneaux laissent voir le jeu au travers.
 */
private fun Scene.interfaceAlizes() {
    val ink = Color.hex(0x153D48); val paper = Color.hex(0xFFEDBE); val gold = Color.hex(0xFFCE6E)
    fun shadowed(text: String, y: Number, size: Number, color: Color) = listOf(
        hud.text(text, 2, y.toFloat() + 2, size, ink, Hud.Anchor.TOP).also { it.opacity = .45f },
        hud.text(text, 0, y, size, color, Hud.Anchor.TOP))
    val title = shadowed("LES ALIZES", 46, 5, gold) + shadowed("UNE ILE, SIX COQUILLAGES", 96, 1.75, paper)

    val counter = hud.panel(24, 24, 196, 56, ink, Hud.Anchor.TOP_RIGHT, opacity = .72)
    hud.text({ "COQUILLAGES  ${pickups.count { it.collected }} / ${pickups.size}" }, 42, 36.75, 1.5, paper, Hud.Anchor.TOP_RIGHT)
    // L'heure, refaite seulement quand la minute change (le texte est relu à chaque image).
    var minute = -1; var hour = ""
    hud.text({
        val m = ((lighting.time ?: 12f) * 60).toInt()
        if (m != minute) { minute = m; hour = "HEURE  %02d:%02d".format(m / 60 % 24, m % 60) }
        hour
    }, 42, 61, 1.25, gold, Hud.Anchor.TOP_RIGHT)

    val help = listOf(hud.panel(24, 24, 912, 48, ink, Hud.Anchor.BOTTOM_LEFT, opacity = .7),
        hud.text("ZQSD / WASD  BOUGER    MAJ  MARCHER    ESPACE  SAUTER    SOURIS  CAMERA", 42, 49.5, 1.5, paper, Hud.Anchor.BOTTOM_LEFT),
        hud.text("MOLETTE  ZOOM    V  VUE    T  HEURE    M  SON    R  RECOMMENCER    TAB  SOURIS    ECHAP  QUITTER", 42, 33.25, 1.25, gold, Hud.Anchor.BOTTOM_LEFT))
    val helpKey = hud.text("H  AIDE", 24, 24, 1.25, paper, Hud.Anchor.BOTTOM_RIGHT)
    val treasure = listOf(hud.panel(0, -68, 520, 84, ink, Hud.Anchor.CENTER, opacity = .85),
        hud.text("TRESOR DES ALIZES", 0, -81.5, 3, gold, Hud.Anchor.CENTER),
        hud.text("TOUS LES COQUILLAGES SONT RETROUVES", 0, -46.75, 1.5, paper, Hud.Anchor.CENTER))
    hud.mouseHint = "TAB POUR CAPTURER LA SOURIS"

    // Le titre s'efface après quelques secondes, l'aide après quatorze ; H la rappelle ou la range.
    var helpUntil = 14f
    onKey(Key.H) { helpUntil = if (helpUntil > time) 0f else Float.MAX_VALUE }
    onKey(Key.F3) { hud.showFps = !hud.showFps }
    onUpdate {
        val fadeTitle = ((7f - time) / 1.5f).coerceIn(0f, 1f)
        title.forEachIndexed { k, t -> t.opacity = fadeTitle * (if (k % 2 == 0) .45f else 1f); t.visible = fadeTitle > 0f }
        val fadeHelp = ((helpUntil - time) / .6f).coerceIn(0f, 1f)
        help.forEachIndexed { k, e -> e.opacity = fadeHelp * (if (k == 0) .7f else 1f); e.visible = fadeHelp > 0f }
        helpKey.visible = fadeHelp < 1f
        counter.color = ink.mix(Color.hex(0x3D6E7C), Math.round(lighting.night * 16) / 16f)   // la nuit, le panneau se détache du ciel
        val all = pickups.isNotEmpty() && pickups.all { it.collected }
        treasure.forEach { it.visible = all }
        hud.mouseHint = if (all) null else "TAB POUR CAPTURER LA SOURIS"
    }
}
