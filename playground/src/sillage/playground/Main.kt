package sillage.playground

import sillage.*
import sillage.audio.Sounds
import sillage.physics.Box
import kotlin.math.cos

/** A playground made of shapes only: push the crates, roll the balls, ride the lift, collect the stars. */
fun main(args: Array<String>) { game("Sillage Playground", args, playground) }

val playground: Scene.() -> Unit = {
    ground()

    // A ramp up to a platform, with a finish pad on top.
    // Stone, crates of planks: ready-made textures, painted by the engine (the lawn got its grass by itself).
    box(6, .4, 3, Color.STONE, at = vec(6, .9, -4)).rotate(z = 18).solid().texture(Textures.STONE)
    box(4, 2, 4, Color.STONE, round = .1, at = vec(10.5, 1, -4)).solid().texture(Textures.STONE)
    val pad = cylinder(1.4, .12, Color.GREEN, at = vec(10.5, 2.06, -4))
    zone(Box(1.4f, 1f, 1.4f), at = vec(10.5, 3, -4), onEnter = { if (it is Player) { pad.mesh = Mesh.cylinder(1.4f, .12f, Color.YELLOW); play(Sounds.pickup()) } })

    // A lift going up and down: a kinematic body moved by the game, it carries the player.
    box(2, .3, 2, Color.ORANGE, round = .05, at = vec(-6, .15, -6)).kinematic()
        .onUpdate { position = vec(-6, .15 + 1.5 * (1 - cos(time * .8f)), -6) }
    box(3, 3, 3, Color.STONE, at = vec(-6, 1.5, -9.6)).solid().texture(Textures.ROCK)

    // A wall of crates and a few balls.
    for (row in 0 until 4) for (col in 0 until 3) box(.8, round = .06, at = vec(-1.2 + col * .82, .4 + row * .81, -8)).texture(Textures.PLANKS).dynamic()
    val colors = listOf(Color.RED, Color.BLUE, Color.YELLOW, Color.GREEN, Color.PURPLE)
    for ((k, c) in colors.withIndex()) sphere(.3 + k * .06, c, at = vec(3 + k * 1.2, .5, 3)).dynamic(bounce = .6)

    // Stars to collect, and a ball falling from the sky every 3 seconds.
    for (p in listOf(vec(4, .6, 0), vec(-6, 3.8, -6), vec(10.5, 2.8, -2.5), vec(-3, .6, 5))) pickup(cone(.25, .5, Color.YELLOW, at = p).spin(120))
    every(3) { val b = sphere(.25, colors.random(random), at = vec(random.nextFloat() * 6 - 3, 9, random.nextFloat() * 6 - 3)).dynamic(bounce = .7); after(20) { b.remove() } }

    // B drops a heap of 400 crates in front of the player: the physics spreads them over the cores.
    val dropped = ArrayList<Node>()
    onKey(Key.B) {
        val p = player!!.position
        for (n in 0 until 400) {
            val x = n % 8; val z = (n / 8) % 8; val y = n / 64
            dropped += box(.5, color = if ((x + y + z) % 2 == 0) Color.WOOD else Color.ORANGE, round = .04,
                at = vec(p.x + (x - 3.5) * .55, 6 + y * .55, p.z - 6 + (z - 3.5) * .55)).texture(Textures.PLANKS).dynamic()
        }
    }
    onRestart { dropped.forEach { it.remove() }; dropped.clear() }

    player(at = vec(0, 0, 6))
    camera.switchKey = Key.V
    onKey(Key.R) { restart() }
    hud.text({ "Stars ${pickups.count { it.collected }} / ${pickups.size}" }, 24, 24, 3)
    hud.text({ "${dropped.size} crates" }, 24, 24, 2, anchor = Hud.Anchor.TOP_RIGHT)
    hud.text("WASD move   SPACE jump   SHIFT walk   V first person   B crates   R restart   TAB mouse", 24, 24, 1.5, anchor = Hud.Anchor.BOTTOM_LEFT)
}
