# Sillage guide

Sillage is a 3D game engine written in Kotlin and nothing else: its own Vulkan renderer, rigid-body physics, character controller, sound mixer and window code (Wayland and X11). It has no third-party dependency. It needs JDK 25 and runs on Linux.

A game is a function that fills a scene. The engine opens the window, runs the loop, moves the player, steps the physics, plays the sounds and draws everything.

```kotlin
import sillage.*

fun main(args: Array<String>) {
    game("My game", args) {
        ground()
        box(1, at = vec(0, 3, -4)).dynamic()            // a crate that falls and can be pushed
        sphere(.4, Color.RED, at = vec(2, 1, -4)).dynamic(bounce = .6)
        player(at = vec(0, 0, 0))                       // WASD or arrows, Space to jump, Shift to walk
    }
}
```

The two demos show the whole API at work: `playground/` is made of shapes only (crates, balls, a ramp, a lift, stars to collect), `demo/` is an island built from glTF models (animated scenery, a rocking boat, shells, sea sounds).

This guide tours the engine by topic. The [tutorial](TUTORIAL.md) builds a first game step by step, [PHYSICS.md](PHYSICS.md) covers the physics engine, [REFERENCE.md](REFERENCE.md) lists the whole public API, and [ARCHITECTURE.md](ARCHITECTURE.md) explains how the engine is built.

## Running

The project builds with the Kotlin Toolchain (`./kotlin`); a game is a module whose `module.yaml` depends on `//engine`.

```
./kotlin run -m playground --jvm-args=--enable-native-access=ALL-UNNAMED
```

Tab captures and frees the mouse, Escape quits. The program's arguments reach the engine through `game(title, args)`; `--help` lists them. The useful ones while developing:

| Option | What it does |
|---|---|
| `--headless --frames N --capture shot.png` | runs without a window and saves the last frame |
| `--look-at ex,ey,ez,tx,ty,tz` | fixed camera for captures |
| `--size WxH` | size of the off-screen image |
| `--smoke` | scripted check: the player walks and jumps, the run fails if it cannot |
| `--route "x,z x,z j w p2"` | the player follows points (`j` jumps, `w` toggles walking, `pN` waits N s) |
| `--trace [--trace-every N]` | prints the player's state |
| `--mute`, `--audio-device NAME` | sound off, or another ALSA device |
| `--record-audio file.wav` | with `--headless`: records what the game would play |
| `--export-sounds dir` | writes the engine's synthesised sounds as WAV files |
| `--validation` | Vulkan validation layers |

`game` returns a `RunReport` (frames drawn, CPU time per frame, GPU time per frame, time to the first frame, player position), which makes scripted checks easy.

## Units, axes, numbers

Metres, seconds, kilograms; Y points up and the default camera looks towards −Z. Angles given to the API are in degrees. Parameters take any `Number`, so `box(1)`, `box(.5)` and `box(width)` all work; `vec(x, y, z)` builds a `Vec3` from any numbers.

## The scene

Inside `game { }` you are in a `Scene`. Each builder adds a `Node`, placed with `at`:

| Builder | Makes |
|---|---|
| `box(width, height, depth, color, round)` | a box centred on `at`, edges rounded by `round` metres |
| `sphere(radius, color)`, `capsule(radius, height, color)`, `cylinder(radius, height, color)`, `cone(radius, height, color)` | centred primitives |
| `plane(width, depth, color)` | a flat rectangle (not solid) |
| `ground(size, color, surface)` | a flat solid floor, its edge lost in the fog by default |
| `water(level, color)` | a flat sea or lake (not solid) |
| `model("models/house.glb", at, scale)` | a glTF binary model, from a file or from the resources |
| `mesh(mesh)` | a mesh you built with `Mesh` or `MeshBuilder` |

A node can be moved, turned and changed at any time:

```kotlin
val lamp = cylinder(.1, 2, Color.GREY, at = vec(3, 1, 0))
lamp.rotate(z = 10).scaled(1.5)
lamp.position = vec(4, 1, 0)
lamp.visible = false
lamp.mesh = Mesh.cylinder(.1f, 2f, Color.YELLOW)
lamp.onUpdate { dt -> rotate(y = 30 * dt) }        // rotate() turns from the current rotation
lamp.spin(90)                                       // the same, as a one-liner
lamp.remove()
```

`nodes` lists them; `tag` and `name` are yours to use.

### Colours and meshes

`Color(r, g, b)` is in linear light, as the shader works; `Color.hex(0xFFCE6E)` takes the colour as you see it on screen (sRGB). The palette (`Color.WHITE`, `RED`, `GRASS`, `SAND`, `WOOD`, `STONE`, `WATER`, `SKY`…) is a starting point.

`Mesh.box`, `sphere`, `capsule`, `cylinder`, `cone`, `plane`, `disk` give ready shapes; `MeshBuilder` assembles your own from triangles, quads, boxes and disks, and `smooth()` rounds normals under a crease angle. Faces are flat-shaded by default, which suits the toon look.

## Physics

A node only takes part in the physics when you say how:

| Call | The node becomes |
|---|---|
| `dynamic(mass, density, bounce, friction, …)` | a rigid body: it falls, rolls, bounces, is pushed by the player |
| `solid()` | still and solid: a wall, a floor, a building |
| `kinematic()` | moved by the game (its `position` is followed smoothly) and pushes what it meets: lifts, doors |
| `collider(shape, offset, part)` | gets an extra invisible collision shape |

A primitive collides with its own shape. A model given to `dynamic` collides with its bounding box, or with the `shape` you pass (in metres, not scaled with the node). `solid()` on a model makes its triangles solid exactly as drawn, which is how terrains, roofs and rocks work; `solid("roof", "rock")` keeps only the triangles of those materials. Material names also set the surface: a triangle made of `grass`, `sand`, `wood`, `stone`, `road` or `ice` gets that grip and those footsteps.

```kotlin
box(4, .3, 2, Color.ORANGE, at = vec(0, 1, 0)).kinematic()
    .onUpdate { position = vec(0, 1 + 2 * sin(time), 0) }      // a lift that carries the player

collider(Box(1f, 1.5f, 1f), at = vec(-3, 1.5, 2))             // an invisible wall
```

When a model is animated, a collider can follow one of its parts: `port.collider(Box(.7f, .25f, .75f), part = "boat")` gives a rocking boat a deck to stand on.

`dynamic` also takes `damping` (air drag, for a beach ball), `angularDamping`, `rollingResistance` and `impact` (the sound of a hit: `AUTO`, `WOOD`, `RUBBER`, `SILENT`). The body itself is `node.body`, with its velocity, mass and forces.

The world is `physics`, a `PhysicsWorld`: `raycast(origin, direction, distance, radius)` for rays and sphere sweeps, `overlaps(shape, position)` for what a shape would touch, `groundBelow(x, z)`, and gravity. `groundHeight(x, z)` is the height of the solid meshes below a point, handy to put things on a terrain.

The solver is a soft-step solver in the spirit of Box2D v3: stable stacks, friction patches, mass ratios handled, sleeping bodies woken by their neighbours. Fast thin objects can still tunnel (there is no continuous collision yet). [PHYSICS.md](PHYSICS.md) tells more, including how to use the physics without a scene.

## The player

```kotlin
player("models/hero.glb", at = vec(0, 0, 4)) {
    runSpeed = 5f; walkSpeed = 2f; jumpSpeed = 7f
    view = CameraMode.FIRST_PERSON
    keys.jump = intArrayOf(Key.SPACE, Key.J)
}
```

Without a model the player is a capsule. With a model, the player plays its clips by name: `idle`, `walk`, `run`, `jump`, `fall`, `land`, `push`, `celebrate`, `wave`. Rename them in `animations` (`animations.run = "sprint"`), or set one to `null` to skip it.

The player is a character controller: it climbs steps up to 35 cm, walks slopes up to 50°, stands on moving platforms, pushes dynamic bodies with a bounded force, and is never pushed into walls or squeezed through gaps. It respawns at `spawn` below `fallLimit`.

From the game: `move(direction, walk)` and `jump()` drive it (set `controls = false` to drive it yourself, for an AI or a cut scene), `position = …` teleports it, `respawn()`, `celebrate()`. `onGround`, `velocity`, `isMoving` and `character` (the controller, with what it stands on and what it pushes) tell you its state. `players` holds them all; `player` is the first.

## The camera

| Mode | |
|---|---|
| `CameraMode.THIRD_PERSON` | follows behind the target, rises over slopes, avoids walls and ignores thin objects |
| `CameraMode.FIRST_PERSON` | at the target's eyes; the player turns with the mouse and is not drawn |
| `CameraMode.FIXED` | set by `camera.lookAt(eye, target)` |

Choose with `player { view = … }` or `camera.mode`; `camera.switchKey = Key.V` lets the player toggle between first and third person. `camera.follow(node)` follows another node. `distance`, `minDistance`, `maxDistance`, `targetHeight`, `eyeHeight`, `yaw`, `elevation`, `pitch`, `fov`, `near`, `far` and `mouseSensitivity` are all settable. `camera.moveDirection(x, z)` turns stick or key input into a direction on the ground relative to the view.

A static body made with `blocksCamera = false` (a palm trunk, a lamp post) never pulls the third-person camera in.

## Input

`Key` names keys by their position on a US QWERTY keyboard, as games do: `Key.W` is the key above `S` whatever the layout, so the default controls work as ZQSD on an AZERTY keyboard. `Key.SHIFT` means either shift key.

```kotlin
onKey(Key.R) { restart() }
onUpdate { if (input.isDown(Key.E)) openDoor() }
val turn = input.axis(Key.LEFT, Key.RIGHT)          // −1, 0 or 1
```

`input.pressed(key)` is true only on the frame the key goes down. `input.mouseDelta`, `input.wheel` and `input.mouseCaptured` give the mouse.

The keys come from `input.source`: the window in a game, or your own `KeySource` to script them (tests, replays, a bot), as in `input.source = KeySource { it == Key.B }`.

## The interface

`hud` draws text and panels over the scene with a built-in pixel font (capitals, lowercase, digits, punctuation and French accents). The canvas is 600 units high and as wide as the window's shape (960 for 16:10), so a layout keeps its proportions; anchors keep elements in their corner on any window.

```kotlin
var score = 0
hud.panel(24, 24, 250, 60, Color.hex(0x153D48))
hud.text({ "Score $score" }, 42, 40, 3)                          // re-evaluated when it changes
hud.text("Press R to restart", 24, 24, 1.5, anchor = Hud.Anchor.BOTTOM_RIGHT)
val banner = hud.text("YOU WIN", 0, 0, 4, Color.YELLOW, Hud.Anchor.CENTER)
banner.visible = false
```

The interface is rebuilt only when what it shows changes. `hud.showFps` adds a frame counter; `hud.mouseHint` is the line shown while the mouse is free (`null` hides it).

## Sound

Every sound is synthesised by the engine (`Sounds.footstep`, `jump`, `land`, `pickup`, `knock`, `bounce`, `scrape`, `waves`, `gull`), or loaded from a WAV file with `Sound.load("sounds/door.wav")`.

```kotlin
play(Sounds.pickup())                                          // everywhere
play(door, at = vec(3, 1, 0), range = 30)                      // in space, heard within 30 m
ambience(Sounds.waves(), at = vec(0, 0, 12), volume = .5)      // a loop placed in the world
```

The scene makes its own sounds too: footsteps that follow the walk and run clips and the ground underfoot, jumps and landings, bodies hitting things (louder when harder), a crate scraping as it is pushed. `toggleMute()` turns sound off and on. The mixer runs on its own thread with 48 voices and distance attenuation, and plays through ALSA.

## Time, events, zones

```kotlin
onUpdate { dt -> … }                                 // every frame
after(2) { … }                                       // once, in 2 seconds
every(5, randomExtra = 3) { … }                      // every 5 to 8 seconds
zone(Box(2f, 1f, 2f), at = vec(10, 1, 0), onEnter = { node -> … }, onExit = { … })
pickup(cone(.3, .6, Color.YELLOW, at = vec(4, .6, 0)).spin(120)) { player -> score++ }
```

A pickup hides its node when a player touches it, plays a chime and makes the player celebrate; `pickups` tells which are collected. `restart()` puts players back at their spawn, shows the pickups again and calls the blocks given to `onRestart`. `goTo { … }` replaces the scene with another at the end of the frame (a new level); the window and the sound stay open.

`time` is the scene's clock in seconds, `fps` the frame rate.

## Light and sky

```kotlin
lighting.sunDirection = vec(-.3, .6, .5)
lighting.sunColor = Color(1f, .8f, .6f)
lighting.fogStart = 30f; lighting.fogEnd = 90f
```

The sun, ambient light, sky gradient and fog are compiled into the shader when the scene starts, so set them in the scene's setup (a scene reached with `goTo` can have its own).

## Models

Models are glTF binary files (`.glb`): meshes with flat colours (`baseColorFactor`), a node hierarchy and node animations (translation, rotation, scale; linear and step). Textures and skins are refused with a clear message: the toon look relies on colours, and characters are made of rigid parts animated as a hierarchy. `node.play("clip", loop, speed, fade)` plays a clip with a cross-fade; `node.animator` gives the clip in progress and the pose of each part.

Paths are looked up on disk first, then in the classpath resources (`resources/models/…` in the module).

## Without a window

A scene runs without a window, which makes tests and servers simple:

```kotlin
val s = Scene().apply {
    ground()
    box(1, at = vec(0, 3, 0)).dynamic()
}
repeat(120) { s.step(1f / 60f) }
```

`step(dt)` reads the keys from `input.source` and advances everything but the drawing. The engine's own tests (`./kotlin test -m engine`) and the quality battery (`./kotlin run -m qa …`) work this way.

## Performance

What the engine does by itself:

- **Physics on several threads.** Large scenes use a pool of up to 8 threads, one per process: broad phase, contacts and the solver (by graph colouring: contacts that share no moving body are solved together). The result is the same bit for bit with or without threads, whatever their number; `physics.parallel = false` keeps everything on the game's thread. Small scenes always run on one.
- **One draw call per mesh.** Every copy of a mesh is drawn in one call, with its model matrices in an instance buffer. Primitives of the same size and colour share their mesh, so a thousand `box(.5)` cost one draw; for your own meshes, build one `Mesh` and give it to many nodes (`mesh(crate, at = …)`). To change one node's look, give it another mesh rather than editing a shared one.
- **Two frames in flight.** The CPU prepares the next frame while the GPU draws the last one.
- **Queries through a grid.** Rays, sphere sweeps and contact queries (the player, shadows, the camera) only look at the bodies near them.
- **No garbage in the hot loops.** Collision tests, rays and sweeps work on plain numbers and create no objects; the physics step, the mixer and the frame recording reuse their buffers. The garbage collector has little to do while the game runs.

Measured on an Intel Core Ultra 7 laptop (efficiency cores, Arc B390): the demo island takes about 0.6 ms of CPU and 0.3 ms of GPU per frame; 10,000 crates on screen, 1.4 ms CPU and 0.4 ms GPU; a heap of 3,000 falling crates with shadows, 7 ms per frame; 10,000 awake bodies in a heap, about 22 ms per physics step.

### Shipping a game

`./kotlin package -m mygame -f executable-jar` makes one jar with the engine and the Kotlin library inside. Run it with:

```
java --enable-native-access=ALL-UNNAMED -jar mygame-jvm-executable.jar
```

The JDK's AOT cache (JDK 25) makes the start and the first seconds smoother: run the game once to record what it loads and compiles, then ship the cache next to the jar.

```
java --enable-native-access=ALL-UNNAMED -XX:AOTCacheOutput=mygame.aot -jar mygame-jvm-executable.jar --headless --frames 1200 --mute
java --enable-native-access=ALL-UNNAMED -XX:AOTCache=mygame.aot -jar mygame-jvm-executable.jar
```

On the demo island this brings the first frame from about 1.0 to 0.7 s and halves the time of the first frames. The default garbage collector (G1) suits the engine: its pauses are rare once the game runs; ZGC and compact object headers made no measurable difference.
