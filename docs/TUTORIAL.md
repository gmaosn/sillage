# Your first game: a star garden

This tutorial builds a small game in eight steps: a garden with crates to push, blocks to climb, a lift, four stars to collect against the clock, a victory banner and a key to start again. It takes about sixty lines. Each step adds a few lines to the same `game { }` block, and the whole program is at the end.

You need the Sillage project (see the [README](../README.md)). A game is a module of the project.

## 0. A module for the game

At the root of the project:

```
./sillage new stargarden
```

This makes `stargarden/` (its `module.yaml`, `src/stargarden/Main.kt`, a `resources/models/` folder) and adds it to `project.yaml`. Replace `stargarden/src/stargarden/Main.kt` with:

```kotlin
package stargarden

import sillage.*
import sillage.audio.Sounds
import kotlin.math.sin

fun main(args: Array<String>) {
    game("Star garden", args) {
        // the steps below go here
    }
}
```

Start it, and leave it running:

```
./sillage dev stargarden
```

A window opens on an empty sky. Escape opens the pause menu, where Quit ends the game. `game` opens the window, runs the loop and gives you a `Scene` to fill: everything inside the braces is a call on that scene. Each time you save `Main.kt`, the game is compiled again and the new scene appears in about a second, the player where it stood: add the steps one by one and watch the garden grow. A mistake shows at the top of the window until the next save fixes it. (`./sillage run stargarden` plays it without reloading.)

## 1. A world

```kotlin
ground()
lighting.fogStart = 30f; lighting.fogEnd = 90f
```

`ground()` is a flat, solid lawn whose edge fades into the haze. The fog starts closer than by default, which makes a small garden feel cosy. Units are metres; Y points up.

## 2. A player

```kotlin
player(at = vec(0, 0, 8))
camera.switchKey = Key.V
```

The player is a capsule you drive with WASD or the arrows (ZQSD on an AZERTY keyboard: keys are named by their position), Space to jump and Shift to walk. The camera follows from behind and keeps out of walls. V switches to first person and back. `at` is the point between the feet.

To see your player as a character, give `player` a glTF model with clips named `idle`, `walk`, `run`, `jump`, `fall`: `player("models/hero.glb", at = …)`. The demo island does that.

## 3. Things to push, things to climb

```kotlin
for (i in 0 until 4) box(1, at = vec(-3 + 2 * i, .5, 2)).dynamic()
sphere(.4, Color.RED, at = vec(4, .4, 5)).dynamic(bounce = .6, damping = .3)
box(2, 1, 2, Color.STONE, at = vec(-6, .5, -3)).solid()
box(2, 2, 2, Color.STONE, at = vec(-6, 1, -5)).solid()
```

A shape is just drawn until you say how it takes part in the physics. `dynamic()` makes a rigid body: the crates fall, slide and tip over, and the player pushes them (a heavy one barely moves). The ball bounces (`bounce`) and slows down in the air like a beach ball (`damping`). `solid()` makes still obstacles: the two stone blocks form a staircase whose steps are a metre high, too high to walk up (the player climbs 35 cm at most), so jump. Everything casts its shadow from the sun on what is below: nothing to ask for.

Builders take any numbers (`1`, `.5`, a `Float` variable) and are centred on `at`, which is why the crates sit at half their height.

## 4. Stars to collect

```kotlin
var score = 0
val spots = listOf(vec(6, 1, 0), vec(-4, 1, 6), vec(-6, 2.8, -5), vec(5, 4.4, -10))
for (p in spots) pickup(cone(.3, .6, Color.YELLOW, at = p).spin(120)) { score++ }
```

Each star is a yellow cone turning at 120° per second. `pickup` watches it: when a player comes close, the star disappears with a chime, the player celebrates, and the block runs. The third star waits on top of the staircase. The fourth one is out of reach for now.

## 5. A lift

```kotlin
box(3, .3, 3, Color.ORANGE, at = vec(5, .15, -10)).kinematic()
    .onUpdate { position = vec(5, 1.6 + 1.45 * sin(time * .8f), -10) }
```

`kinematic()` makes a body that the game moves: set its `position` and the physics follows smoothly, carrying what stands on it and pushing what is in the way. `onUpdate` runs every frame. `time` is the scene's clock in seconds, so the platform goes up and down every eight seconds or so. Ride it to reach the last star.

## 6. Score and time on screen

```kotlin
var clock = 0f
var won = false
hud.panel(16, 16, 260, 74)
hud.text({ "Stars $score / ${spots.size}" }, 30, 28, 3)
hud.text({ "%.1f s".format(clock) }, 30, 62, 2, Color.YELLOW)
val banner = hud.text("WELL DONE!", 0, 0, 5, Color.YELLOW, Hud.Anchor.CENTER)
banner.visible = false
onUpdate { dt -> if (!won) clock += dt }
```

The interface is a canvas 600 units high, top-left first. A text given as a block (`{ … }`) is evaluated again every frame, and redrawn only when it changes. The banner is anchored at the centre and hidden until the end. The scene's `onUpdate` receives the frame's duration `dt`, in seconds, so the clock counts real time.

## 7. Winning, and playing again

```kotlin
once({ score == spots.size }) {
    won = true
    banner.fadeIn(over = .4)
    player?.celebrate(2)
}
onKey(Key.R) { restart() }
onRestart { score = 0; clock = 0f; won = false; banner.visible = false }
```

`once` watches its condition at every frame and runs its block the first time it holds: when the four stars are found, the banner fades in and the player dances for two seconds. `restart()` puts the player back at the spawn point and the stars back in place, arms `once` again, then calls your `onRestart` blocks, which reset the game's own state. (`whenever` is the same, but fires each time its condition becomes true.)

## 7½. Ready, go!

```kotlin
val ready = hud.text("READY?", 0, -80, 4, Color.WHITE, Hud.Anchor.CENTER)
script {
    wait(1.5)
    ready.value = { "GO!" }
    ready.fadeOut(over = .6, after = .4)
}
```

A `script` is a block that takes its time: `wait(1.5)` lets the game run for a second and a half, then the script goes on where it stopped. No timer to set, no flag to keep: the steps are written in the order they happen. `waitUntil { … }` waits for a condition, and `fadeOut` spreads a change over time (a *tween*: `moveTo` and `scaleTo` do the same for things in the scene).

## 8. A little life

```kotlin
every(6, randomExtra = 6) { play(Sounds.gull(), at = vec(0, 8, -20)) }
```

Every six to twelve seconds, a gull calls from above the far end of the garden: the sound comes from that place, louder as you walk towards it. Footsteps, jumps, landings, the knock of crates and the bounce of the ball come by themselves. The engine synthesises all these sounds: there is no file to ship.

## The whole game

```kotlin
package stargarden

import sillage.*
import sillage.audio.Sounds
import kotlin.math.sin

fun main(args: Array<String>) {
    game("Star garden", args) {
        // 1. A world
        ground()
        lighting.fogStart = 30f; lighting.fogEnd = 90f

        // 2. A player
        player(at = vec(0, 0, 8))
        camera.switchKey = Key.V

        // 3. Things to push, things to climb
        for (i in 0 until 4) box(1, at = vec(-3 + 2 * i, .5, 2)).dynamic()
        sphere(.4, Color.RED, at = vec(4, .4, 5)).dynamic(bounce = .6, damping = .3)
        box(2, 1, 2, Color.STONE, at = vec(-6, .5, -3)).solid()
        box(2, 2, 2, Color.STONE, at = vec(-6, 1, -5)).solid()

        // 4. Stars to collect
        var score = 0
        val spots = listOf(vec(6, 1, 0), vec(-4, 1, 6), vec(-6, 2.8, -5), vec(5, 4.4, -10))
        for (p in spots) pickup(cone(.3, .6, Color.YELLOW, at = p).spin(120)) { score++ }

        // 5. A lift to the highest star
        box(3, .3, 3, Color.ORANGE, at = vec(5, .15, -10)).kinematic()
            .onUpdate { position = vec(5, 1.6 + 1.45 * sin(time * .8f), -10) }

        // 6. Score and time on screen
        var clock = 0f
        var won = false
        hud.panel(16, 16, 260, 74)
        hud.text({ "Stars $score / ${spots.size}" }, 30, 28, 3)
        hud.text({ "%.1f s".format(clock) }, 30, 62, 2, Color.YELLOW)
        val banner = hud.text("WELL DONE!", 0, 0, 5, Color.YELLOW, Hud.Anchor.CENTER)
        banner.visible = false
        onUpdate { dt -> if (!won) clock += dt }

        // 7. Winning, and playing again
        once({ score == spots.size }) {
            won = true
            banner.fadeIn(over = .4)
            player?.celebrate(2)
        }
        onKey(Key.R) { restart() }
        onRestart { score = 0; clock = 0f; won = false; banner.visible = false }

        // 7½. Ready, go!
        val ready = hud.text("READY?", 0, -80, 4, Color.WHITE, Hud.Anchor.CENTER)
        script {
            wait(1.5)
            ready.value = { "GO!" }
            ready.fadeOut(over = .6, after = .4)
        }

        // 8. A little life
        every(6, randomExtra = 6) { play(Sounds.gull(), at = vec(0, 8, -20)) }
    }
}
```

## Checking it without playing

Every game accepts the engine's options. This walks the player to three stars with no window, then saves the last frame:

```
./sillage run stargarden \
    --headless --mute --frames 1500 --route "6,0 -4,6 3,-10 p2 5,-10 p6" --capture garden.png
```

`--route` lists points to walk to (`x,z`), with `j` to jump and `pN` to wait N seconds. The program prints a `SILLAGE_RUN_OK` line with the frame times and where the player ended. Checks like this one can run in a test suite, or on a machine without a screen.

## Where to go next

- [GUIDE.md](GUIDE.md) tours everything the scene offers: models, animation, zones, the camera, sound, light.
- [PHYSICS.md](PHYSICS.md) explains the physics engine, which also works on its own.
- [REFERENCE.md](REFERENCE.md) lists every public class and function.
- `playground/` and `demo/` are two complete games to read.
