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

The two demos show the whole API at work: `playground/` is made of shapes only (crates, balls, a ramp, a lift, stars to collect), `demo/` is an island built from glTF models (animated scenery, a rocking boat, shells, sea sounds, the hours of a day with lamps at night, a campfire, plants in the wind).

This guide tours the engine by topic. The [tutorial](TUTORIAL.md) builds a first game step by step, [PHYSICS.md](PHYSICS.md) covers the physics engine, [REFERENCE.md](REFERENCE.md) lists the whole public API, and [ARCHITECTURE.md](ARCHITECTURE.md) explains how the engine is built.

## Running

A game is a module of the project whose `module.yaml` depends on `//engine`. The `./sillage` command at the project's root makes one, runs it and ships it; it drives the Kotlin Toolchain (`./kotlin`) for compiling, then starts the JVM itself with the options the engine needs.

```
./sillage new garden          # a new game: garden/src/garden/Main.kt, garden/resources/models/
./sillage run garden          # builds what changed (nothing: under a second), then plays
./sillage dev garden          # plays, and reloads the game each time you save a file
./sillage package garden      # build/package/garden/garden-x86_64.AppImage
./sillage games               # the games of the project
```

Tab captures and frees the mouse, Escape quits. Everything after the game's name goes to the game (`./sillage run garden --headless --capture shot.png`); it reaches the engine through `game(title, args)`, and `--help` lists the options. `SILLAGE_JAVA_OPTIONS` adds options for the JVM.

### Changing a game while it runs

`./sillage dev GAME` keeps the game on screen while you work on it:

- **Save a Kotlin file of the game**: it is compiled again (about a second: the Kotlin compiler stays warm in the game's JVM) and its scene replaces the one on screen. The player, the camera and the hour stay where they were, unless your change moved the spawn point, the camera's settings or the hour: then the new values win.
- **Save a model, an image or a sound** in the game's `resources/` folder (from Blender, say): the scene is built again with it, in a fraction of a second. While `dev` runs, files are read from that folder rather than from the build.
- **A mistake** (a compilation error, an exception while the scene is built or in an `onUpdate`) shows in a panel at the top of the screen; the game waits, and the next save that fixes it carries on. Nothing is lost: the window, the sound and the player stay.
- **Change the engine itself** (`engine/`): the game stops, the engine is built again and the game starts afresh (a minute or so).

A reload builds the game's first scene again: a game that went elsewhere with `goTo` comes back to its first scene.

### Seeing what the engine does

Every game has these keys (a shipped game may turn them off with `debug.keys = false`):

| Key | |
|---|---|
| **F5** | the physics drawn over the scene: the bodies' shapes (static blue-grey, moving orange, asleep brown, kinematic violet), the static meshes within 25 m (`debug.meshRadius`), the trigger zones (green), the joints (magenta), the contact points and their normals (yellow), a white line for the speed of what moves. What the scenery hides shows faintly through it. A bar at the top gives the time of the last physics step, the bodies (and how many are awake), the contacts, the nodes and the frame rate; a cross in the middle of the screen and a panel on the left say what is under it: the node (its name, or the model it came from), where and how far, its body (type, shape, mass, speed, friction, bounce, surface) and its mesh. |
| **F6** | a free camera: WASD or the arrows, Space up, Ctrl down, Shift four times faster, the mouse turns it. The player waits where it is; F6 again comes back to the game's camera. |
| **F7** | pause: the game's time stands still, the picture, the free camera and F5 go on. |
| **F8** | one frame forward (it pauses first if needed). |

The same from code: `debug.showPhysics`, `debug.freeCamera`, `debug.paused`, `debug.step()`; and `debug.describe(eye, direction)` gives the panel's lines for any ray. From the command line, `--debug physics,free,paused` starts that way (handy for captures).

| Option | What it does |
|---|---|
| `--headless --frames N --capture shot.png` | runs without a window and saves the last frame |
| `--look-at ex,ey,ez,tx,ty,tz` | fixed camera for captures |
| `--hour H` | starts at hour H (0 to 24), for scenes that keep the time of day |
| `--size WxH` | size of the off-screen image |
| `--smoke` | scripted check: the player walks and jumps, the run fails if it cannot |
| `--route "x,z x,z j w p2"` | the player follows points (`j` jumps, `w` toggles walking, `pN` waits N s) |
| `--trace [--trace-every N]` | prints the player's state |
| `--mute`, `--audio-device NAME` | sound off, or another ALSA device |
| `--record-audio file.wav` | with `--headless`: records what the game would play |
| `--export-sounds dir` | writes the engine's synthesised sounds as WAV files |
| `--export-textures dir` | writes the ready-made textures as PNG files (the pattern, and the pattern on a typical colour) |
| `--validation` | Vulkan validation layers |
| `--debug physics,free,paused` | starts with the physics drawn, the free camera, or paused (see below) |

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
| `water(level, color, size, shallows, reach)` | a sea or lake (not solid): a light lagoon along its shores, foam against the beaches, surf rolling in, wave crests drifting on the open water |
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

### Textures

A texture in Sillage is a detail pattern: it shades the colour of what it lies on, mid-grey leaving it as it is. A model keeps its colours and gains grain, planks, tiles or blades of grass; nothing has to be painted in a separate image. The engine paints its ready-made textures itself, in the background, when they are first used:

| `Textures.` | | `Textures.` | |
|---|---|---|---|
| `GRASS` | blades in tufts, light and dark patches | `ROCK` | patches, cracks, level strata |
| `SAND` | grains, wind ripples, specks of shell | `PLASTER` | soft patches, speckle, faint streaks |
| `WOOD` | grain without seams | `FABRIC` | woven threads |
| `PLANKS` | planks with seams and staggered joints | `LEAVES` | dabs of light and shade |
| `ROOF_TILES` | rows of rounded clay tiles | `BARK` | fibres and rings |
| `STONE` | cobbles with dark joints | | |

Models get them by themselves from their material names, in English or French: a material called `grass`, `Herbe`, `roof.001`, `bois_clair` or `galet` gets grass, roof tiles, wood or rock (`Textures.named(name)` tells which). `ground()` gets the texture of its surface. Set `autoTextures = false` before adding models to keep plain colours.

```kotlin
box(1, at = vec(0, .5, 0)).texture(Textures.PLANKS)                   // a whole node
model("models/house.glb").texture(Textures.STONE, "Wall", tile = 2)   // only the triangles of one material
model("models/cart.glb").texture(null, "wheel")                       // none
```

`tile` is how many metres one repeat covers, `strength` how marked the pattern is (each texture has its own). A texture lies on the object itself, so it moves with it, in one of two ways (`Texture.mapping`): `SIDES` looks from the three axes and blends by the slope, without seams on round shapes (grass on hills, rocks, plaster); `FACES` runs along each flat face, rows level and climbing the slope (roof tiles on any roof, planks on walls). Textured copies of the same mesh stay one draw call.

`Texture.paint(name, tile, strength, size) { u, v -> color }` makes your own from a function of the place in the square (u and v from 0 to 1; the pattern must join up at the edges). `--export-textures dir` writes the ready-made ones as images to look at.

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

The solver is a soft-step solver in the spirit of Box2D v3: stable stacks, friction patches, mass ratios handled, sleeping bodies woken by their neighbours, continuous collision for fast bodies (`body.isBullet` for projectiles).

Bodies can be joined, once dynamic:

```kotlin
val door = box(.9, 2, .06, at = vec(.45, 1, 0)).dynamic(density = 600)
door.hinge(anchor = vec(0, 1, 0), axis = Vec3.Y).limits(-1.6f, 0f)       // opens one way
val lamp = sphere(.15, Color.YELLOW, at = vec(3, 2, 0)).dynamic()
chain(from = vec(3, 3.5, 0), to = vec(3, 2.15, 0), links = 6, end = lamp)  // hangs on a chain
```

`hangFrom` swings freely, `hinge` turns about an axis, `slider` slides (a lift, a drawer), `glue` welds, `rope` and `spring` pull; each joins the node to another (`to = …`) or to the world. Hinges and sliders take limits, motors and springs (`motor(speed, maxTorque)`, `spring(hertz)`). The demo's see-saw and swing are a dozen lines each. `Vehicle(physics, car.body)` turns a box into a car with suspension, steering, brakes and tyres that skid. A player standing on anything that moves (the boat, a lift, a turning platform, a floating crate) is carried by it, and keeps its speed when jumping off. [PHYSICS.md](PHYSICS.md) tells more, including how to use the physics without a scene.

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

`effects` (true) makes a puff of dust when it lands on sand or a path and a splash when it falls into water. From the game: `move(direction, walk)` and `jump()` drive it (set `controls = false` to drive it yourself, for an AI or a cut scene), `position = …` teleports it, `respawn()`, `celebrate()`. `onGround`, `velocity`, `isMoving` and `character` (the controller, with what it stands on and what it pushes) tell you its state. `players` holds them all; `player` is the first.

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

Every element has an `opacity` (1 by default): `hud.panel(…, opacity = .7)` lets the game show through a panel, and changing a text's opacity frame by frame fades it in or out. The interface is rebuilt only when what it shows changes. `hud.showFps` adds a frame counter; `hud.mouseHint` is the line shown while the mouse is free (`null` hides it).

## Sound

Every sound is synthesised by the engine (`Sounds.footstep`, `jump`, `land`, `pickup`, `knock`, `bounce`, `scrape`, `waves`, `gull`), or loaded from a file with `Sound.load("sounds/door.ogg")`: Ogg Vorbis (decoded by the engine itself, a few hundred times faster than real time) or WAV.

```kotlin
play(Sounds.pickup())                                          // everywhere
play(door, at = vec(3, 1, 0), range = 30)                      // in space, heard within 30 m
ambience(Sounds.waves(), at = vec(0, 0, 12), volume = .5)      // a loop placed in the world
```

```kotlin
gull.sound(cry, part = "wing")                                 // from a moving node: the sound follows it
val song = ambience(Sound.load("music/fire.ogg"), at = fire, range = 30)
onUpdate { song.volume = lighting.night }                      // an ambience's volume can change any time
reverb = Reverb.OUTDOOR                                         // how the place sounds
reverbZone(Box(4f, 3f, 6f), at = vec(10, 2, 0), reverb = Reverb.CAVE)   // inside, while the camera is there
```

Sounds placed in the world are softer and duller far away, come from their side, shift in pitch as they come and go (the Doppler effect: `audio.mixer.doppler`, 1 by default) and echo in the place: `Reverb.OUTDOOR`, `ROOM`, `HALL`, `CAVE`, or your own `Reverb(decay, wet, damping)`. Far sounds keep more of their echo than of themselves, as in a real room. Sounds that are not placed (interface, music) stay dry.

The scene makes its own sounds too: footsteps that follow the walk and run clips and the ground underfoot, jumps and landings, bodies hitting things (louder when harder), a crate scraping as it is pushed. `toggleMute()` turns sound off and on. The mixer runs on its own thread with 48 voices, and plays through ALSA.

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

## Light, sky and the hour

```kotlin
lighting.sunDirection = vec(-.3, .6, .5)
lighting.sunColor = Color(1f, .8f, .6f)
lighting.fogStart = 30f; lighting.fogEnd = 90f
```

By default a scene has a clear day that stands still: the sun, its colour, the shade colour, the ambient light from the sky and the ground, the sky's gradient and the haze are in `lighting`. Everything can change at any time, during the game too.

Give the scene an hour and the light follows it:

```kotlin
lighting.time = 16.5f          // half past four in the afternoon
lighting.dayLength = 600f      // a whole day in ten minutes of play; 0 (the default): the hour stands still
```

The sun rises at 6 on the side of `sunrise`, stands highest at noon (`sunTilt` degrees from the zenith) and sets at 18; the colours go through dawn, day, a warm sunset and the blue of night, when the moon takes over as the main light and the stars come out. `lighting.night` goes from 0 by day to 1 at night: lamps that light only at night follow it, and a game can too (`if (lighting.night > .5f) …`).

Three settings finish the picture: `glow` (0.8), the halo around what is brighter than white (the sun, its glitter on water, lamps, embers, sparks); `outlines` (0.85), ink lines along silhouettes; `vignette` (0.22), darker corners. `saturation` (1.06) makes the colours richer or plainer.

Everything casts a shadow from the sun (or the moon) on what is below it, within `lighting.shadowRange` metres (24) of what the camera looks at; the shadows fade out beyond. `lighting.shadows = false` turns them off (for the next scene). A node that should not cast one (a cloud high in the sky, a decal) sets `castsShadow = false`; it still receives the others. With cast shadows, players also keep a light round shadow straight under them, which shows where a jump will land; without them, every moving thing gets a round shadow (`node.shadow()` adds one).

Water finds its shores by itself: wherever solid ground rises gently out of it, `water()` lays a lagoon of the `shallows` colour reaching `reach` metres out, a lacy line of foam that swells against the beach and a line of surf rolling in. Raised things standing in the water, such as a pier's deck, leave the water under them open. The sky is mirrored at grazing angles, and the sun, the moon and the lamps glitter on the small waves. The shores are looked for once the scene is built, so the order of the calls does not matter.

## Lamps, glow and wind

```kotlin
light(Color.hex(0xFFC870), intensity = 1.5, radius = 6, at = vec(0, 2.5, 3), onlyAtNight = true)
lantern.light(offset = vec(0, .3, 0), flicker = .2)          // a lamp that follows its node
house.glow(Color.hex(0xFFB85C), "window", intensity = 2.4, atNight = true)
village.sway(.07, "grass", "flower*", reach = .3)
```

A lamp brightens what is around it, falling off with the square of the distance and gone at `radius` metres; it casts no shadow. `intensity` 1 lights a white wall a metre away about as much as daylight. `flicker` makes it waver (1 for a flame), `onlyAtNight` lights it as night falls. The 16 lamps nearest the camera are drawn; `lights` lists them, `removeLight` or the lamp's `enabled` turns one off.

`glow` gives triangles their own light: they shine without lighting anything around them (add a lamp for that); above 1 they bloom. With `atNight`, only at night: lit windows, a lighthouse lamp.

`sway` makes plants move in `lighting.wind` (a direction on the ground, its length the strength): each point moves up to `metres` in a breeze of strength 1, less near the ground (with the square of its height, up to `reach` metres). For a model, the material names say what sways; a trailing `*` matches the start of a name.

## Particles

```kotlin
emit(Particles.FIRE, at = vec(8, 1, 2), rate = 70)
emit(Particles.SMOKE, at = vec(-2, 4.8, -2.6), rate = 4)
torch.emit(Particles.SPARKS, rate = 5, offset = vec(0, .4, 0))
burst(Particles.SPARKLE, at = shell.position, count = 28)
```

An emitter sends particles from a place or a node, so many per second; `burst` sends some at once. `Particles` has ready-made styles: `FIRE`, `SMOKE`, `SPARKS`, `SPLASH`, `SPRAY`, `DUST`, `FIREFLIES`, `SPARKLE`. A `ParticleStyle` of your own says how they look (colour and size from birth to death) and move (speed, cone, gravity, drag, wander, how much the wind carries them); glowing ones add their light to what is behind them and bloom, the others are lit like the scene. Particles live in flat arrays (4096 at most) and are drawn in one call.

The scene makes some by itself: a puff of dust where a player lands on sand or a path, a splash where a player or a body falls into water (`player.effects = false` turns a player's off).

## Models

Models are glTF binary files (`.glb`), as Blender exports them: meshes with colours (`baseColorFactor`, vertex colours), a node hierarchy, node animations (translation, rotation, scale; linear and step) and:

- **Images painted on the model** (`baseColorTexture`): PNG or JPEG, inside the file, in a `data:` URI or as a file beside it, read by the engine's own decoders; the texture coordinates `TEXCOORD_0`; `alphaMode` `MASK` cuts out what is below `alphaCutoff` (leaves, fences, a sign's rounded corners; `BLEND` is drawn as `MASK` at 0.5, without sorting). An image decoded once serves every copy and every scene. Images are scaled to 1024 pixels at most. Without an image, detail textures still come from the material names (see Textures).
- **Skins** (characters bent by a skeleton): joints, inverse bind matrices, four weights per vertex. The mesh is deformed on the CPU after each pose, then sent to the GPU again (a few thousand vertices cost a fraction of a millisecond); a skinned model plays its clips like any other.

`node.play("clip", loop, speed, fade)` plays a clip with a cross-fade; `node.animator` gives the clip in progress and the pose of each part. Cubic-spline curves and morph targets are refused with a clear message; normal and metal/roughness maps are ignored (the toon look has no use for them).

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

`step(dt)` reads the keys from `input.source` and advances everything but the drawing. The engine's own tests (`./sillage test`) and the quality battery (`./sillage qa`) work this way.

## Performance

What the engine does by itself:

- **Physics on several threads.** Large scenes use a pool of up to 8 threads, one per process: broad phase, contacts and the solver (by graph colouring: contacts that share no moving body are solved together). The result is the same bit for bit with or without threads, whatever their number; `physics.parallel = false` keeps everything on the game's thread. Small scenes always run on one.
- **Physics on SIMD lanes, if you ask.** With `--add-modules jdk.incubator.vector` among the JVM's options, large scenes solve their contacts 8 at a time (AVX2), still the same to the bit: a quarter faster on one thread, a few per cent with all of them (PHYSICS.md).
- **One draw call per mesh.** Every copy of a mesh is drawn in one call, with its model matrices in an instance buffer. Primitives of the same size and colour share their mesh, so a thousand `box(.5)` cost one draw; for your own meshes, build one `Mesh` and give it to many nodes (`mesh(crate, at = …)`). To change one node's look, give it another mesh rather than editing a shared one.
- **Two frames in flight.** The CPU prepares the next frame while the GPU draws the last one.
- **Keys acting on the next picture.** When the driver can tell when a frame is on screen (Mesa does), the loop waits for the last frame to be shown before reading the keys: a key press reaches the screen one refresh later (16.6 ms at 60 Hz, against about 100 ms with the frames queued in FIFO mode). A tap shorter than a frame is never lost.
- **Shadows in one extra pass.** The shadow map is drawn from the sun with the same instance data, in a depth-only pass; on the demo island it costs about 0.2 ms of CPU per frame.
- **The picture in linear light.** The scene is drawn in 16-bit floating point with 4× anti-aliasing, then a halo (five levels down and up) and a last pass for the ink lines, the tone and the vignette. This is what the GPU spends most on: on the island, about 0.8 ms per frame in 1280 × 800 and 2.9 ms in 2560 × 1600 (0.4 and 1.1 ms before, with plain 8-bit colour); in 2560 × 1600 the 16-bit image costs about 0.9 ms, the anti-aliasing 1 ms, the textures 0.3 ms and the halo 0.1 ms.
- **Queries through a grid.** Rays, sphere sweeps and contact queries (the player, shadows, the camera) only look at the bodies near them.
- **No garbage in the hot loops.** Collision tests, rays and sweeps work on plain numbers and create no objects; the physics step, the mixer and the frame recording reuse their buffers. The garbage collector has little to do while the game runs.

Measured on an Intel Core Ultra 7 laptop (efficiency cores, Arc B390): the demo island takes about 0.6 ms of CPU and 0.8 ms of GPU per frame in 1280 × 800 (before the pass in linear light: 0.4 ms of GPU); 10,000 crates on screen, 1.4 ms CPU and 0.4 ms GPU; a heap of 3,000 falling crates with shadows, about 2 ms per frame; 10,000 awake bodies in a heap, about 22 ms per physics step.

### Shipping a game

```
./sillage package mygame
```

makes `build/package/mygame/mygame-x86_64.AppImage`: one file that holds the game, a Java runtime cut down to the modules the game uses (`jlink`), and an AOT cache. Copy it anywhere and run it; no JDK to install. The cache comes from a training run without a window (900 frames, no sound) made while packaging; on the playground it brings the first frame from about 0.85 to 0.45 s after the start (measured under load). The same run draws the AppImage's icon: a 256 × 256 picture of the game. The AppImage tools (`appimagetool` and the type 2 runtime, which needs no FUSE library) are fetched once at fixed versions and checked against their checksums. The AppImage runs on distributions whose C library is as recent as the one it was packaged on.

The options of the game work as usual (`mygame-x86_64.AppImage --mute`), and it keeps the folder it was started from as its working directory.
