package sillage.demo

import sillage.*
import sillage.audio.Reverb
import sillage.audio.Sound
import sillage.audio.Sounds
import sillage.math.Vec3
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

    // Le feu de camp : toujours allumé, sa lumière compte surtout le soir.
    val fire = groundAt(8, 2.4)
    val glow = light(Color.hex(0xFF9440), intensity = 1.6, radius = 7, at = fire + vec(0, .55, 0), flicker = 1)
    emit(Particles.FIRE, at = fire + vec(0, .1, 0), rate = 70)
    emit(Particles.SPARKS, at = fire + vec(0, .25, 0), rate = 5)
    emit(Particles.SMOKE, at = fire + vec(0, .75, 0), rate = 4)
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
private fun Scene.interfaceAlizes() {
    val ink = Color.hex(0x153D48); val paper = Color.hex(0xFFEDBE); val gold = Color.hex(0xFFCE6E)
    hud.theme.panel = ink; hud.theme.text = paper; hud.theme.accent = gold
    val title = hud.column(0, 46, Hud.Anchor.TOP, spacing = 15, place = Place.CENTER) {
        text("LES ALIZES", 5, gold).shadow = ink
        text("UNE ILE, SIX COQUILLAGES", 1.75, paper).shadow = ink
    }

    // Le compteur, et l'heure refaite seulement quand la minute change (le texte est relu à chaque image).
    var minute = -1; var hour = ""
    val counter = hud.column(24, 24, Hud.Anchor.TOP_RIGHT, spacing = 11, place = Place.END) {
        panel(ink, opacity = .72, radius = 8); paddingX = 18f; paddingY = 12f
        text({ "COQUILLAGES  ${pickups.count { it.collected }} / ${pickups.size}" }, 1.5, paper)
        text({
            val m = ((lighting.time ?: 12f) * 60).toInt()
            if (m != minute) { minute = m; hour = "HEURE  %02d:%02d".format(m / 60 % 24, m % 60) }
            hour
        }, 1.25, gold)
    }

    val help = hud.column(24, 24, Hud.Anchor.BOTTOM_LEFT, spacing = 7) {
        panel(ink, opacity = .7, radius = 8); paddingX = 18f; paddingY = 12f
        text("ZQSD / WASD  BOUGER    MAJ  MARCHER    ESPACE  SAUTER    SOURIS  CAMERA", 1.5, paper)
        text("MOLETTE  ZOOM    V  VUE    T  HEURE    M  SON    R  RECOMMENCER    TAB  SOURIS    ECHAP  PAUSE", 1.25, gold)
    }
    val helpKey = hud.text("H  AIDE", 24, 24, 1.25, paper, Hud.Anchor.BOTTOM_RIGHT)
    val treasure = hud.column(0, -68, Hud.Anchor.CENTER, spacing = 14, place = Place.CENTER) {
        panel(ink, opacity = .85, radius = 10, padding = 22)
        text("TRESOR DES ALIZES", 3, gold)
        text("TOUS LES COQUILLAGES SONT RETROUVES", 1.5, paper)
    }
    hud.mouseHint = "TAB POUR CAPTURER LA SOURIS"
    hud.pauseMenu("PAUSE", "REPRENDRE", "RECOMMENCER", "SON : OUI" to "SON : NON", "QUITTER")

    // Le titre s'efface après quelques secondes, l'aide après quatorze ; H la rappelle ou la range.
    var helpUntil = 14f
    onKey(Key.H) { helpUntil = if (helpUntil > time) 0f else Float.MAX_VALUE }
    onKey(Key.F3) { hud.showFps = !hud.showFps }
    onUpdate {
        val fadeTitle = ((7f - time) / 1.5f).coerceIn(0f, 1f)
        title.opacity = fadeTitle; title.visible = fadeTitle > 0f
        val fadeHelp = ((helpUntil - time) / .6f).coerceIn(0f, 1f)
        help.opacity = fadeHelp; help.visible = fadeHelp > 0f
        helpKey.visible = fadeHelp < 1f
        counter.root.background = ink.mix(Color.hex(0x3D6E7C), Math.round(lighting.night * 16) / 16f)   // la nuit, le panneau se détache du ciel
        val all = pickups.isNotEmpty() && pickups.all { it.collected }
        treasure.visible = all
        hud.mouseHint = if (all) null else "TAB POUR CAPTURER LA SOURIS"
    }
}
