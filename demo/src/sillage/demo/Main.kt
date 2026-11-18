package sillage.demo

import sillage.*
import sillage.audio.Sounds
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
    sea()
    model("models/terrain.glb").solid()              // herbe, sable et bois du ponton : chacun sa surface
    val village = model("models/village.glb")
    // Le toit est solide tel qu'il est dessiné : depuis les tonneaux, on peut sauter dessus et y grimper.
    village.solid("toit")
    model("models/palmiers.glb").play("vent")
    val port = model("models/port.glb").play("vie")
    model("models/ciel.glb").play("vol")
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
        pickup(mesh(shell, at = vec(x, groundHeight(x, z) + .65f, z)).rotate(y = k * 57).spin(63)) { println("Coquillages : ${pickups.count { it.collected }}/${pickups.size}") }
    }

    // Corps rigides : on peut les pousser, les faire tomber, taper dans le ballon.
    // Sur un endroit plat : le centre de l'île est bosselé, une pile y basculerait (vérifié).
    beachBall(-1.5f, 3.0f)
    for (k in 0..2) crate(-2.4f, 2.6f, lift = k * .72f, yaw = k * 14.3f)  // une pile de trois caisses
    crate(-3.4f, 1.4f, size = .55f, yaw = 34.4f)
    crate(4.2f, 6.4f, yaw = 17.2f); crate(4.9f, 6.9f, size = .5f)         // sur la plage
    crate(-1.4f, 11.1f, size = .6f, yaw = 22.9f)                           // au bout du ponton

    // Le bruit de la mer tout autour du rivage, des mouettes de temps en temps.
    val waves = List(3) { Sounds.waves(it) }
    for (k in 0 until 8) { val a = k * PI.toFloat() / 4 + .2f; ambience(waves[k % 3], at = vec(cos(a) * 12.3f, 0, sin(a) * 12.3f), volume = .55, range = 70) }
    val gulls = List(4) { Sounds.gull(it) }
    every(6, randomExtra = 10) {
        val a = Math.random() * 2 * PI; val r = 8 + Math.random() * 14
        play(gulls.random(), at = vec(cos(a) * r, 9 + Math.random() * 6, sin(a) * r), volume = .5, range = 90)
    }

    // Touches : R recommence, M coupe le son (en QWERTY et en AZERTY), V passe en première personne.
    onKey(Key.R) { restart() }
    onKey(Key.M) { toggleMute() }; onKey(Key.SEMICOLON) { toggleMute() }
    camera.switchKey = Key.V

    interfaceAlizes()
}

/** Un volume de collision invisible posé sur le terrain (ou à la hauteur [base]). */
private fun Scene.block(x: Float, z: Float, width: Float, height: Float, depth: Float, base: Float? = null, thin: Boolean = false) =
    collider(Box(width / 2, height / 2, depth / 2), vec(x, (base ?: groundHeight(x, z)) + height / 2, z), blocksCamera = !thin)

/** Une caisse de [size] mètres posée sur le sol (ou [lift] mètres au-dessus), qu'on peut pousser. */
private fun Scene.crate(x: Float, z: Float, lift: Float = 0f, size: Float = .7f, yaw: Float = 0f) =
    model("models/caisse.glb", at = vec(x, groundHeight(x, z) + size / 2 + lift + .01f, z), scale = size)
        .rotate(y = yaw).dynamic(mass = 40f * size * size * size, friction = .7)

/** Un ballon de plage léger et rebondissant, qui freine vite dans l'air. */
private fun Scene.beachBall(x: Float, z: Float, radius: Float = .35f) =
    model("models/ballon.glb", at = vec(x, groundHeight(x, z) + radius + .01f, z), scale = radius)
        .dynamic(mass = .6, friction = .5, bounce = .65, shape = Sphere(radius), damping = .8, angularDamping = .1, rollingResistance = .05)

/** La mer en aplats : un grand plan, des taches d'écume au large et un liseré sous la plage. */
private fun Scene.sea() {
    val g = MeshBuilder()
    g.quad(vec(-150, -.32, -150), vec(-150, -.32, 150), vec(150, -.32, 150), vec(150, -.32, -150), Color.hex(0x50C2D5))
    for (i in -18..18) for (j in -18..18) {
        val x = i * 3.7f + sin(j * 4f) * 1.4f; val z = j * 3.4f
        if (sqrt(x * x + z * z) < 13f) continue
        g.quad(vec(x, -.30, z), vec(x, -.30, z + .07f), vec(x + .9f, -.30, z + .07f), vec(x + .9f, -.30, z), Color.hex(0xA6E2E7))
    }
    mesh(g.build())
    mesh(MeshBuilder().disk(0f, -.22f, 0f, 12.5f, Color.hex(0xC7F0E7), 96).build())
}

/** Un coquillage doré : deux pyramides à six pans. */
private fun shellMesh(): Mesh {
    val g = MeshBuilder(); val color = Color.hex(0xFFE6A8)
    val top = Vec3(0f, .34f, 0f); val bottom = Vec3(0f, -.24f, 0f)
    val ring = Array(6) { i -> val a = i * PI.toFloat() / 3; Vec3(cos(a) * .22f, 0f, sin(a) * .22f) }
    for (i in 0..5) { g.triangle(top, ring[i], ring[(i + 1) % 6], color); g.triangle(bottom, ring[(i + 1) % 6], ring[i], color) }
    return g.build()
}

/** L'interface des Alizés : titre, compteur de coquillages, aide, et le message du trésor. */
private fun Scene.interfaceAlizes() {
    val ink = Color.hex(0x153D48); val paper = Color.hex(0xFFEDBE); val gold = Color.hex(0xFFCE6E)
    hud.panel(24, 24, 250, 82, ink); hud.panel(24, 24, 4, 82, gold)
    hud.text("SILLAGE", 42, 38, 3, paper)
    hud.text("LES ALIZES", 42, 73, 2, gold)
    hud.panel(24, 24, 250, 82, ink, Hud.Anchor.TOP_RIGHT)
    hud.text("COQUILLAGES", 256, 38, 2, paper, Hud.Anchor.TOP_RIGHT, Hud.Align.LEFT)
    hud.text({ "${pickups.count { it.collected }} / ${pickups.size}" }, 256, 73, 2, gold, Hud.Anchor.TOP_RIGHT, Hud.Align.LEFT)
    hud.text({ "$fps FPS" }, 80, 118, 1.25, ink, Hud.Anchor.TOP_RIGHT)
    hud.panel(24, 24, 912, 48, ink, Hud.Anchor.BOTTOM_LEFT)
    hud.text("ZQSD / WASD  BOUGER    MAJ  MARCHER    ESPACE  SAUTER    SOURIS  CAMERA", 42, 49.5, 1.5, paper, Hud.Anchor.BOTTOM_LEFT)
    hud.text("MOLETTE  ZOOM    V  VUE    M  SON    R  RECOMMENCER    TAB  SOURIS    ECHAP  QUITTER", 42, 33.25, 1.25, gold, Hud.Anchor.BOTTOM_LEFT)
    val treasure = listOf(hud.panel(0, -68, 520, 84, ink, Hud.Anchor.CENTER),
        hud.text("TRESOR DES ALIZES", 0, -81.5, 3, gold, Hud.Anchor.CENTER),
        hud.text("TOUS LES COQUILLAGES SONT RETROUVES", 0, -46.75, 1.5, paper, Hud.Anchor.CENTER))
    hud.mouseHint = "TAB POUR CAPTURER LA SOURIS"
    onUpdate {
        val all = pickups.isNotEmpty() && pickups.all { it.collected }
        treasure.forEach { it.visible = all }
        hud.mouseHint = if (all) null else "TAB POUR CAPTURER LA SOURIS"
    }
}
