# Sillage guide

Sillage is a 3D game engine written in Kotlin and nothing else: its own Vulkan renderer (and a second one on OpenGL ES 3 and WebGL 2), rigid-body physics, character controller, sound mixer and window code. It has no third-party dependency. Games are written on Linux with JDK 25, and run on Linux, a Raspberry Pi, Windows, FreeBSD, in a web page and on Android ([PLATFORMS.md](PLATFORMS.md)).

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

The two demos show the whole API at work: `playground/` is made of shapes only (crates, balls, a ramp, a lift, stars to collect), `demo/` is an island built from glTF models (animated scenery, a rocking boat, shells, sea sounds, the hours of a day with lamps at night, a campfire, plants in the wind). A third game, `temple/`, shows the same engine with another look: a ruined temple at night, in first person, in smooth light.

This guide tours the engine by topic. The [tutorial](TUTORIAL.md) builds a first game step by step, [PHYSICS.md](PHYSICS.md) covers the physics engine, [REFERENCE.md](REFERENCE.md) lists the whole public API, [PLATFORMS.md](PLATFORMS.md) says how to take a game to each system, and [ARCHITECTURE.md](ARCHITECTURE.md) explains how the engine is built.

## Running

A game is a module of the project whose `module.yaml` depends on `//engine`. The `./sillage` command at the project's root makes one, runs it and ships it; it drives the Kotlin Toolchain (`./kotlin`) for compiling, then starts the JVM itself with the options the engine needs.

```
./sillage new garden          # a new game: garden/src/garden/Main.kt, garden/resources/models/
./sillage run garden          # builds what changed (nothing: under a second), then plays
./sillage dev garden          # plays, and reloads the game each time you save a file
./sillage package garden      # build/package/garden/garden-x86_64.AppImage
./sillage pad                 # try the gamepads plugged in (a DualSense: its lights, rumble and triggers)
./sillage games               # the games of the project
```

Tab captures and frees the mouse, Escape opens the pause menu (Quit ends the game). Everything after the game's name goes to the game (`./sillage run garden --headless --capture shot.png`); it reaches the engine through `game(title, args)`, and `--help` lists the options. `SILLAGE_JAVA_OPTIONS` adds options for the JVM.

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
| `--mute`, `--audio-device NAME` | sound off, or another device (an ALSA name; a path such as `/dev/dsp1` is written to as an OSS device) |
| `--quality low\|medium\|high` | what the renderer spends: `medium` halves the shadow maps' side, `low` also drops the anti-aliasing. Chosen by itself otherwise (`medium` on a Raspberry Pi, `high` elsewhere); `SILLAGE_QUALITY` does the same from the environment |
| `--renderer gl` | with `--headless`: draws with the OpenGL ES renderer (the one of the web and Android) instead of Vulkan, to compare their pictures |
| `--record-audio file.wav` | with `--headless`: records what the game would play |
| `--export-sounds dir` | writes the engine's synthesised sounds as WAV files |
| `--export-textures dir` | writes the ready-made textures as PNG files (the pattern, and the pattern on a typical colour) |
| `--validation` | Vulkan validation layers |
| `--debug physics,free,paused` | starts with the physics drawn, the free camera, or paused (see below) |

`game` returns a `RunReport` (frames drawn, CPU time per frame, GPU time per frame, time to the first frame, player position), which makes scripted checks easy.

## Units, axes, numbers

Metres, seconds, kilograms; Y points up and the default camera looks towards −Z. The scene's API measures as people do: angles in degrees, whole sizes (a box 2 m wide is `box(2)`), and any `Number`, so `box(1)`, `box(.5)` and `box(width)` all work; `vec(x, y, z)` builds a `Vec3` from any numbers.

The physics package (`sillage.physics`) and the maths (`sillage.math`) keep the units of physics engines: radians, half sizes (`Box(1f, .5f, 1f)` is 2 × 1 × 2 m), `Float`. A few helpers cross from one to the other, so that a game never has to:

```kotlin
rotation(y = 30)                    // a Quat: 30° about Y (X, then Y, then Z, as node.rotate does)
boxShape(3.4, 1.65, 3)              // a Box 3.4 × 1.65 × 3 m, for collider(), zone(), dynamic(shape = …)
sphereShape(.5); capsuleShape(.3, 1.6)
door.hinge(at, Vec3.Y, min = -30, max = 90)     // limits in degrees
crate.onGround(4, -2)               // set down on the ground there, its lowest point on it
groundAt(4, -2)                     // the point of the ground under (4, −2)
```

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
| `dynamic(mass, density, bounce, friction, …)` | a rigid body: it falls, rolls, bounces, is pushed by the player (light by default: 50 kg/m³, an empty crate; the player pushes up to about 65 kg on plain ground) |
| `solid()` | still and solid: a wall, a floor, a building |
| `kinematic()` | moved by the game (its `position` is followed smoothly) and pushes what it meets: lifts, doors |
| `collider(shape, offset, part)` | gets an extra invisible collision shape |

A primitive collides with its own shape. A model given to `dynamic` collides with its bounding box, or with the `shape` you pass (in metres, not scaled with the node). `solid()` on a model makes its triangles solid exactly as drawn, which is how terrains, roofs and rocks work; `solid("roof", "rock")` keeps only the triangles of those materials. Material names also set the surface: a triangle made of `grass`, `sand`, `wood`, `stone`, `road` or `ice` gets that grip and those footsteps.

```kotlin
box(4, .3, 2, Color.ORANGE, at = vec(0, 1, 0)).kinematic()
    .onUpdate { position = vec(0, 1 + 2 * sin(time), 0) }      // a lift that carries the player

collider(Box(1f, 1.5f, 1f), at = vec(-3, 1.5, 2))             // an invisible wall
```

Shapes besides the primitives' own: `boxShape(w, h, d)`, `sphereShape(r)`, `capsuleShape(r, h)`, a convex hull and a compound. `rock.hullShape()` wraps a node's mesh in its convex hull (at most 32 points by default), so that a rock, a bevelled crate or a barrel rolls and stacks as it looks; `Compound(Compound.Part(…), …)` joins several shapes in one body (a table: a top and four legs; a hammer). The body sits on the shape's centre of mass, the node where its mesh says.

```kotlin
val rock = model("models/rock.glb", at = vec(2, 3, 0))
rock.dynamic(shape = rock.hullShape(), density = 2600)
```

When a model is animated, a collider can follow one of its parts: `port.collider(Box(.7f, .25f, .75f), part = "boat")` gives a rocking boat a deck to stand on.

`dynamic` also takes `damping` (air drag, for a beach ball), `angularDamping`, `rollingResistance` and `impact` (the sound of a hit: `AUTO`, `WOOD`, `RUBBER`, `SILENT`). The body itself is `node.body`, with its velocity, mass and forces.

The world is `physics`, a `PhysicsWorld`: `raycast(origin, direction, distance, radius)` for rays and sphere sweeps, `overlaps(shape, position)` for what a shape would touch, `groundBelow(x, z)`, and gravity. `groundHeight(x, z)` is the height of the solid meshes below a point, handy to put things on a terrain.

The solver is a soft-step solver in the spirit of Box2D v3: stable stacks, friction patches, mass ratios handled, sleeping bodies woken by their neighbours, continuous collision for fast bodies (`body.isBullet` for projectiles).

Bodies can be joined, once dynamic:

```kotlin
val door = box(.9, 2, .06, at = vec(.45, 1, 0)).dynamic(density = 600)
door.hinge(anchor = vec(0, 1, 0), axis = Vec3.Y, min = -92, max = 0)     // opens one way (degrees)
val lamp = sphere(.15, Color.YELLOW, at = vec(3, 2, 0)).dynamic()
chain(from = vec(3, 3.5, 0), to = vec(3, 2.15, 0), links = 6, end = lamp)  // hangs on a chain
```

`cone(anchor, axis, swing = 45, twist = 30)` is a shoulder or a hip (a rag doll's joints): it turns within a cone of `swing` degrees and twists by up to `twist`. `hangFrom` swings freely, `hinge` turns about an axis, `slider` slides (a lift, a drawer), `glue` welds, `rope` and `spring` pull; each joins the node to another (`to = …`) or to the world. Hinges and sliders take limits, motors and springs (`motor(speed, maxTorque)`, `spring(hertz)`). The demo's see-saw and swing are a dozen lines each. `Vehicle(physics, car.body)` turns a box into a car with suspension, steering, brakes and tyres that skid. A player standing on anything that moves (the boat, a lift, a turning platform, a floating crate) is carried by it, and keeps its speed when jumping off. [PHYSICS.md](PHYSICS.md) tells more, including how to use the physics without a scene.

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

With a pad, the player walks with the left stick: a small tilt walks, slower than `walkSpeed`; half a tilt walks at `walkSpeed`; a full tilt runs at `runSpeed`. Cross (south) jumps, the walk keys still cap the speed. `keys` takes key and pad button codes alike (`keys.jump = intArrayOf(Key.SPACE, Pad.CROSS)`); `keys.stick` picks the stick (`Stick.LEFT`, or null for none) and `keys.pad` the pad by slot (−1, the default, for any), which gives each player of a local multiplayer game its own pad.

`effects` (true) makes a puff of dust when it lands on sand or a path and a splash when it falls into water. From the game: `move(direction, walk)` and `jump()` drive it (set `controls = false` to drive it yourself, for an AI or a cut scene), `position = …` teleports it, `respawn()`, `celebrate()`. `onGround`, `velocity`, `isMoving` and `character` (the controller, with what it stands on and what it pushes) tell you its state. `players` holds them all; `player` is the first.

## The camera

| Mode | |
|---|---|
| `CameraMode.THIRD_PERSON` | follows behind the target, rises over slopes, avoids walls and ignores thin objects |
| `CameraMode.FIRST_PERSON` | at the target's eyes; the player turns with the mouse and is not drawn |
| `CameraMode.FIXED` | set by `camera.lookAt(eye, target)` |

Choose with `player { view = … }` or `camera.mode`; `camera.switchKey = Key.V` lets the player toggle between first and third person (a pad button works too: `camera.switchKey = Pad.R3`). The right stick of a pad turns the view (`lookStick`, `stickSpeed` 160° per second at a full tilt, two thirds of that up and down, `pad` to pick one by slot); `invertY` swaps up and down for the stick and the mouse. In first person, `camera.gyroAim = true` aims with a DualSense's gyroscope: turning the pad turns the view as far (`gyroSensitivity` 1), about the vertical however the pad is held, from every report of the sensor rather than one per frame. `camera.follow(node)` follows another node. `distance`, `minDistance`, `maxDistance`, `targetHeight`, `eyeHeight`, `yaw`, `elevation`, `pitch`, `fov`, `near`, `far` and `mouseSensitivity` are all settable. `camera.moveDirection(x, z)` turns stick or key input into a direction on the ground relative to the view.

A static body made with `blocksCamera = false` (a palm trunk, a lamp post) never pulls the third-person camera in.

## Input

`Key` names keys by their position on a US QWERTY keyboard, as games do: `Key.W` is the key above `S` whatever the layout, so the default controls work as ZQSD on an AZERTY keyboard. `Key.SHIFT` means either shift key.

```kotlin
onKey(Key.R) { restart() }
onUpdate { if (input.isDown(Key.E)) openDoor() }
val turn = input.axis(Key.LEFT, Key.RIGHT)          // −1, 0 or 1
```

`input.pressed(key)` is true only on the frame the key goes down, `input.released(key)` on the frame it comes up. `input.mouseDelta`, `input.wheel` and `input.mouseCaptured` give the mouse.

On a touch screen (a phone, a tablet, a page opened on one) the engine plays a pad by itself: a finger on the left half is the left stick, which appears where the finger lands; a finger dragged on the right half turns the camera; a short tap there, or the round button at the bottom right, jumps (`Pad.CROSS`); two fingers pinching change the camera's distance. The game sees an ordinary pad, so whatever it does with `input.pad` and its actions works under the thumbs with nothing to add.

The keys come from `input.source`: the window in a game, or your own `KeySource` to script them (tests, replays, a bot), as in `input.source = KeySource { it == Key.B }`.

### Pads

A game with a window finds the computer's pads by itself, plugged in or paired at any time. A PlayStation 5 DualSense (or DualSense Edge) is read through its raw HID device, over USB or Bluetooth, with everything it has: buttons, analog triggers, the motion sensors with the pad's own calibration, the touchpad, the battery, and the light bar, the player LEDs, the mute light, rumble and adaptive triggers. Other gamepads (Xbox, 8BitDo, Switch Pro…) are read through Linux's evdev, mapped to the same layout, with rumble when they have it.

```kotlin
onUpdate {
    val pad = input.pad ?: return@onUpdate              // the first pad connected
    if (pad.pressed(Pad.CROSS)) jump()
    val aim = pad.stick(Stick.RIGHT)                    // x right, y up, −1..1, dead zone applied
    val throttle = pad.trigger(Pad.R2)                  // 0..1
}
input.onPadConnected { pad ->
    pad.lightbar = Color.ORANGE
    pad.triggerEffect(Pad.R2, TriggerEffect.resistance(start = .3, force = .8))
}
input.onPadDisconnected { pause() }
```

Buttons are codes like keys, named by place (`Pad.SOUTH`, `EAST`, `WEST`, `NORTH`, `L1`, `R1`, `L2`, `R2`, `SELECT`, `START`, `HOME`, `L3`, `R3`, `DPAD_UP`…) and by their DualSense names (`Pad.CROSS`, `CIRCLE`, `SQUARE`, `TRIANGLE`, `CREATE`, `OPTIONS`, `PS`, `TOUCHPAD`, `MUTE`; the Edge's `FN_LEFT`, `FN_RIGHT`, `PADDLE_LEFT`, `PADDLE_RIGHT`). So `input.pressed(Pad.CROSS)`, `onKey(Pad.OPTIONS) { … }` and the player's `keys` work with them, any pad counting.

`input.pads` lists the pads connected, by slot; each keeps its `slot` (player number − 1) until the game ends, even unplugged: the same pad plugging back in gets its slot and its `Pad` object back, and a scene the game goes to keeps the pads. A DualSense shows its slot with its player LEDs and a light bar colour (blue, red, green, pink as on a PlayStation; `input.padLights = false` leaves them alone).

A `Pad` has `isDown`, `pressed`, `released`; `leftStick` and `rightStick` (`x`, `y`, `amount`, and `rawX`, `rawY` before the dead zone) with a radial dead zone (`deadZone` 0.1, `fullTilt` 0.95); `trigger(Pad.L2)` and `trigger(Pad.R2)`; and, on a DualSense, `gyro` (rad/s) and `accel` (m/s²) on the pad's axes (x to its right, y up out of its face, z towards you), `touch` (two fingers, `x` and `y` from the top left, 0..1), `battery` (0..1) and `batteryState`, `headphones` and `microphone`. `rumble(low, high, seconds)` shakes it (the heavy motor, the light one, 0..1; the engine stops it when the time is up), `lightbar`, `playerLeds` (five bits) and `muteLed` light it, and `triggerEffect(Pad.R2, …)` sets an adaptive trigger: `TriggerEffect.resistance(start, force)`, `weapon(start, end, force)` (resists, then gives way with a click, between 20 % and 90 % of the travel), `vibration(start, amplitude, frequency)`, or `OFF`. `canRumble`, `hasLightbar`, `hasAdaptiveTriggers`, `hasMotion`, `hasTouchpad` and `hasBattery` say what a pad offers. When the game ends, trigger effects are freed and motors stopped.

On Linux the user must be allowed to open the pads. Gamepads are, on a desktop session (udev gives the seat's user access to joysticks). A DualSense's raw HID device needs a udev rule: Steam installs one (`60-steam-input.rules`), and some distributions ship such rules (`game-devices-udev`). Without it the DualSense is read through evdev, with its buttons, sticks, triggers and rumble only, and a message says so once. Started by Steam with Steam Input, a game sees Steam's virtual pads instead of the real ones, as SDL games do. Steam, when it runs, may also drive a DualSense's lights.

`./sillage pad` (or `--pad-test` after any game) checks the pads from a terminal, without a window: it says which pads the system shows and which it may not open, prints each pad's state live, cycles the light bar colours, lights the player LEDs, rumbles, sets resistances on the triggers (TRIANGLE changes them, CROSS rumbles, SQUARE lights the mute button), and holding PS for two seconds stops it.

### Actions

Read named actions rather than keys, and the player can change the bindings while the game runs:

```kotlin
val jump = input.action("jump", Key.SPACE, Pad.CROSS)
val fire = input.action("fire", Key.F, Pad.R2)
val move = input.axes("move", Key.A, Key.D, Key.S, Key.W, Stick.LEFT)
onUpdate {
    if (jump.pressed) player?.jump()                    // or input.pressed("jump")
    power = fire.value                                  // 0..1: R2 half pulled gives .5, a key 1
    steer(move.x, move.y)                               // within the unit circle
}
```

An `Action` has `isDown`, `pressed`, `released`, `value`, its `bindings`, and `bind`, `rebind`, `unbind`; `pad` restricts it to one pad. `Axes` has `x`, `y`, `amount`, four arrays of keys or buttons (`left`, `right`, `down`, `up`), a `stick`, and `mouse` (the mouse's movement added, per pixel). `input.actions` lists the actions for a settings menu, which can wait for `input.firstPressed()` (the key or pad button that went down this frame, or −1) and `rebind` to it. `Input.codeName(code)` names a code ("SPACE", "CROSS").

### Pads from code

A `VirtualPad` is a pad driven by code, for tests, bots and scripted runs: `input.plug(pad)`, then `press`, `release`, `stick`, `trigger`, `motion`, `touch`; the scene reads it as a real one, and what the game sent back (`rumbleLow`, `lightbar`, `triggerEffect(…)`…) can be checked. `input.padSource` says where the other pads come from (`PadSource.system()` in a game with a window, `PadSource.NONE` without).

```kotlin
val bot = VirtualPad()
val s = Scene().apply { ground(); player(); input.plug(bot) }
bot.stick(Stick.LEFT, 0, 1)                             // full tilt forward: the player runs
repeat(60) { s.step(1f / 60f) }
```

`--pad-script` does the same from the command line, in the scene's time, so a run without a window plays the same every time: `--headless --mute --pad-script "0.5 L 0,1; 2.5 L 0,0; 3 tap CROSS; 4.5 R 1,0; 5.5 R 0,0"` walks forward for two seconds, jumps and turns the camera. Orders: `L x,y` and `R x,y` (sticks), `press B`, `release B`, `tap B` (a tenth of a second), `L2 v`, `R2 v`, `gyro x,y,z`.

## The interface

`hud` draws over the scene: texts, panels, boxes laid out by themselves, buttons, text fields and menus. The canvas is 600 units high and as wide as the window's shape (960 for 16:10), so a layout keeps its proportions; anchors keep elements in their corner on any window. The interface is rebuilt only when what it shows changes, and a still interface costs nothing per frame.

```kotlin
var score = 0
hud.text({ "Score $score" }, 24, 24, 3)                          // re-evaluated every frame, redrawn when it changes
hud.text("Press R to restart", 24, 24, 1.5, anchor = Hud.Anchor.BOTTOM_RIGHT)
val banner = hud.text("YOU WIN", 0, 0, 4, Color.YELLOW, Hud.Anchor.CENTER)
banner.visible = false
```

### Fonts

Texts use the engine's pixel font unless told otherwise: capitals, small letters, digits, punctuation and French accents, in the engine's own look. Any font installed on the computer can be used instead — TrueType, OpenType (CFF outlines too) or a collection — read and rasterised by the engine itself; fonts are never downloaded nor shipped with a game.

```kotlin
hud.font = Font.sans()                                   // every text: a sans-serif family found on the computer
val title = Font.find("Noto Serif", bold = true) ?: Font.serif(bold = true)
hud.text("Les Alizés", 0, 40, 5, Color.YELLOW, Hud.Anchor.TOP, font = title)
```

`Font.find(family, bold, italic)` returns the installed face of that family closest to the weight and slant asked, or null; `Font.sans()`, `Font.serif()` and `Font.mono()` pick a family that is there (Noto, Source, DejaVu, Liberation…) and fall back to the pixel font, so a game never breaks. `Font.load(path)` reads a file. The fonts are found without fontconfig, in `~/.local/share/fonts`, `~/.fonts` and the `fonts` folders of the system's data directories; `Font.families()` lists them. A character a font lacks is taken from its `fallbacks` (wide-coverage families found on the system), then drawn as an empty box. There is no complex shaping (one character, one glyph), but pairs are kerned.

A text's `size` is its scale in every font: capitals are 7 × size units high, as in the pixel font, so changing fonts moves nothing. Texts break at `'\n'` and, with `wrap = width`, between words. `shadow` (a colour) adds a soft copy below and to the right, `outline` a ring around each letter: both keep a text readable over a bright sky. Glyphs are rasterised once per size into an atlas and drawn at whole pixels, sharp at any scale of the screen.

Every element has an `opacity` (1 by default): `hud.panel(…, opacity = .7)` lets the game show through a panel, and changing a text's opacity frame by frame fades it in or out. `hud.showFps` adds a frame counter; `hud.mouseHint` is the line shown while the mouse is free (`null` hides it).

### Layout

`hud.column` and `hud.row` place boxes from an anchor like any element; they take the size of what they hold, so there are no widths to type by hand.

```kotlin
val counter = hud.column(24, 24, Hud.Anchor.TOP_RIGHT, spacing = 10, place = Place.END) {
    panel(Color.hex(0x153D48), opacity = .72, radius = 8)          // a rounded, see-through background
    text({ "SHELLS ${found} / 6" }, 1.5)
    row(spacing = 8) { text("TIME", 1.25, Color.YELLOW); text({ clock }, 1.25) }
}
counter.opacity = .5f                                              // the whole block fades
```

A `Group` lays its children in a column or a row, `spacing` units apart, `padding` (or `paddingX`, `paddingY`) inside its edge; `place` sets how they sit across it (`START`, `CENTER`, `END`, or `STRETCH` to the box's width). A box with a `background` is a panel: `radius` rounds it, `border` outlines it, `shadow` lifts it off the scene. A widget's `width` and `height` fix its size; after a frame, `left`, `top`, `laidWidth` and `laidHeight` tell where it went.

### Buttons, menus and the pause menu

```kotlin
val start = hud.menu("MY GAME") {                       // a title screen: closed until opened
    button("Play") { close() }
    button("Options") { options.open() }
    button("Quit") { hud.quit() }
}
start.open()
```

A menu is a panel in the middle of the screen over a veil. While it is open it has the keyboard — arrows move the focus, Tab and Shift+Tab go round, Enter or Space press — and the mouse is free; with `pauses = true` (the default) the game's time and physics stand still. Escape runs its `onBack` (by default it closes). Buttons outside menus work too, with the mouse or once focused. `hud.focusToward(dx, dy)`, `focusNext()`, `focusPrevious()`, `activate()` and `cancel()` are what the keys call, so a gamepad drives the same interface. `button.enabled = false` greys a button out. While the interface has the keyboard (a menu open, a field focused), the game sees no key.

Escape opens the built-in pause menu: Resume, Restart, Sound on and off, Quit. `hud.pauseMenu("PAUSE", "Reprendre", "Recommencer", "Son : oui" to "Son : non", "Quitter")` makes it with other words; `hud.pauseMenu = myMenu` puts your own in its place; `hud.pauseMenu = null` brings back the old behaviour, where Escape quits at once. Runs without a window are not affected.

The look comes from `hud.theme`: `panel`, `text`, `accent` (titles, focus), `button`, `buttonHover`, `buttonPressed`, `field` colours, `radius`, the text `size` of buttons and fields, `titleSize`, and the `font` of laid-out texts (the HUD's font by default).

### Text input

```kotlin
hud.menu("NEW ISLAND") {
    text("Its name", 2)
    field("Type a name…", width = 360) { name -> island = name; close() }   // Enter submits
}
```

A text field takes what the player types through the desktop's keyboard layout (libxkbcommon: the compositor's layout under Wayland, the X server's under X11), with dead keys and compose sequences: `^` then `e` gives `ê` on a French keyboard. It has a caret, selection with Shift and the arrows (by word with Ctrl), Home and End, Backspace and Delete, Ctrl+A, Ctrl+C, Ctrl+X and Ctrl+V (a clipboard of the game's own, `Hud.clipboard`), and scrolls when the text is longer than the field. `text`, `select(start, end)`, `maxLength`, `onChange` and `onSubmit` drive it from the game.

The pointer and the text typed come from the window; `hud.source = UiScript()` (or your own `UiSource`) feeds them from code instead, for tests and scripted captures: `script.pointAt(x, y)`, `script.down = true`, `script.type("Été")`.

## Sound

Every sound is synthesised by the engine (`Sounds.footstep`, `jump`, `land`, `pickup`, `knock`, `bounce`, `scrape`, `waves`, `gull`), or loaded from a file with `Sound.load("sounds/door.ogg")`: Ogg Vorbis (decoded by the engine itself, a few hundred times faster than real time) or WAV.

```kotlin
play(Sounds.pickup())                                          // everywhere
play(creak, at = vec(3, 1, 0), range = 30)                     // in space, heard within 30 m
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

The ears are at the head of the player the camera follows, turned as the camera looks: what is near the player sounds near, and left and right follow the view. `listener = Listener.CAMERA` puts them at the camera instead.

## Time, events, zones

```kotlin
onUpdate { dt -> … }                                 // every frame
after(2) { … }                                       // once, in 2 seconds
every(5, randomExtra = 3) { … }                      // every 5 to 8 seconds
zone(boxShape(4, 2, 4), at = vec(10, 1, 0), onEnter = { node -> … }, onExit = { … })
pickup(cone(.3, .6, Color.YELLOW, at = vec(4, .6, 0)).spin(120)) { player -> score++ }
```

A pickup hides its node when a player touches it, plays a chime and makes the player celebrate; `pickups` tells which are collected. `restart()` puts players back at their spawn, shows the pickups again and calls the blocks given to `onRestart`. `goTo { … }` replaces the scene with another at the end of the frame (a new level); the window and the sound stay open.

`time` is the scene's clock in seconds, `fps` the frame rate.

### Scripts, conditions, tweens

What happens in turn is written in turn:

```kotlin
script {
    banner.visible = true
    wait(2)                                    // the game goes on meanwhile
    waitUntil { player!!.onGround }
    waitFor(door.moveTo(vec(0, 3, 0), over = 1.5))
    player!!.celebrate()
}
once({ score == 4 }) { banner.fadeIn() }       // the first frame it holds; restart() arms it again
whenever({ player!!.position.y > 3 }) { play(Sounds.pickup()) }    // each time it becomes true
```

A `script` runs from the next frame on, on the game's thread, at the scene's update: `wait(seconds)`, `waitUntil { … }`, `nextFrame()` and `waitFor(tween)` let the game run meanwhile, and nothing else can be called that would block. Scripts follow the scene's clock (they stop while the game is paused, F7) and can be stopped (`stop()`).

A tween spreads a change over time, after an optional delay, along an `Ease` (`LINEAR`, `IN`, `OUT`, `IN_OUT` by default, `SPRING` which goes a little past the end):

```kotlin
lift.moveTo(vec(5, 3, -10), over = 2, ease = Ease.SPRING)   // a kinematic body follows smoothly
star.scaleTo(1.5, over = .3)
title.fadeOut(over = 1.5, after = 7)                        // HUD elements: fade, fadeIn, fadeOut
tween(0, 360, over = 4) { yaw -> sign.yaw = yaw }           // any value
tween(over = 1) { k -> lighting.saturation = 1 + .3f * k }  // or the share of the way, 0 to 1
```

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

`lighting.softness` chooses how the light falls on shapes. At 0, the default, it falls in two drawn steps, like a cartoon: the island. Towards 1 it turns smoothly around them, with more of the sky's light in the shade and a faint sheen: stone, clay, plaster. The temple uses `.9f` and turns the ink lines off (`outlines = 0f`); nothing else separates the two looks.

Three settings finish the picture: `glow` (0.8), the halo around what is brighter than white (the sun, its glitter on water, lamps, embers, sparks); `outlines` (0.85), ink lines along silhouettes; `vignette` (0.22), darker corners. `saturation` (1.06) makes the colours richer or plainer.

Everything casts a shadow from the sun (or the moon) on what is below it, within `lighting.shadowRange` metres (24) of what the camera looks at; the shadows fade out beyond. `lighting.shadows = false` turns them off, `true` back on, at any time (a scene that began without them makes the shadow maps the first time they are turned on). A node that should not cast one (a cloud high in the sky, a decal) sets `castsShadow = false`; it still receives the others. With cast shadows, players also keep a light round shadow straight under them, which shows where a jump will land; without them, every moving thing gets a round shadow (`node.shadow()` adds one).

Water finds its shores by itself: wherever solid ground rises gently out of it, `water()` lays a lagoon of the `shallows` colour reaching `reach` metres out, a lacy line of foam that swells against the beach and a line of surf rolling in. Raised things standing in the water, such as a pier's deck, leave the water under them open. The sky is mirrored at grazing angles, and the sun, the moon and the lamps glitter on the small waves. The shores are looked for once the scene is built, so the order of the calls does not matter.

The water carries: what is lighter than water floats (a crate of density 500 half in, a beach ball high on the waves), what is heavier sinks slowly and comes to rest on the bottom, and both are slowed and rocked as in water. Whatever falls in splashes, with a sound fitting its size. `water(…, buoyant = false)` is only a picture; `node.waterVolume` gives the physics' water (its `level` may rise and fall, `flow` makes a current).

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

Models are glTF binary files (`.glb`), as Blender exports them: meshes with colours (`baseColorFactor`, and `COLOR_0` vertex by vertex: weathering, moss, soot painted into the model itself), a node hierarchy, node animations (translation, rotation, scale; linear and step) and:

- **Images painted on the model** (`baseColorTexture`): PNG or JPEG, inside the file, in a `data:` URI or as a file beside it, read by the engine's own decoders; the texture coordinates `TEXCOORD_0`; `alphaMode` `MASK` cuts out what is below `alphaCutoff` (leaves, fences, a sign's rounded corners; `BLEND` is drawn as `MASK` at 0.5, without sorting). An image decoded once serves every copy and every scene. Images are scaled to 1024 pixels at most. Without an image, detail textures still come from the material names (see Textures).
- **Skins** (characters bent by a skeleton): joints, inverse bind matrices, four weights per vertex. The mesh is deformed on the CPU after each pose, then sent to the GPU again (a few thousand vertices cost a fraction of a millisecond); a skinned model plays its clips like any other.

Normals: by default the engine makes its own, soft ones (each vertex takes the mean of the faces around it; edges sharper than 55° stay crisp), which suits the soft toon look. `model(path, normals = Normals.FROM_FILE)` keeps Blender's instead: flat-shaded faces stay flat, edges marked sharp stay sharp, custom normals are kept.

`node.play("clip", loop, speed, fade)` plays a clip with a cross-fade; `node.animator` gives the clip in progress and the pose of each part. Cubic-spline curves and morph targets are refused with a clear message; normal and metal/roughness maps are ignored (the toon look has no use for them).

Paths are looked up on disk first, then in the classpath resources (`resources/models/…` in the module).

### Blender as the editor

A model can carry more than what is drawn. Objects named by a few conventions (or given a custom property `sillage`) become, when the scene starts, what they stand for: the scene is laid out in Blender, the code keeps the game.

| In Blender | In the game |
|---|---|
| an object named `col_…` (or `sillage = collider`) | an invisible collision volume, fitted to the object: a box by default; `col_sphere_…`, `col_capsule_…` (standing on its Y axis), `col_hull_…` (the convex hull of its points), `col_mesh_…` (its triangles as they are), or a property `shape`. An empty works too: Blender's cube of side 2, scaled. A volume under an animated part (a boat that rocks) follows it. |
| an empty named `spawn` | where `player()` starts when it is given no `at`, facing as the empty does; the camera starts behind it |
| a point or spot light | a lamp (`light`): its colour, its range as the radius; Blender's 1000 W gives an intensity of about 1.5 |
| `sillage = dynamic` on an object | a separate node of its own, named after the object (`find("crate")`), with a moving body: properties `mass`, `density`, `bounce`, `friction`, `surface`, `shape` |
| an object named `pickup_…` (or `sillage = pickup`) | a separate node a player picks up; `onPickup("shell") { player -> … }` acts for all of `pickup_shell`, `pickup_shell.001`… |
| an object or empty named `zone_…` (or `sillage = zone`) | an invisible trigger zone: `onEnter("door") { node -> … }`, `onExit`, `zone("door")` |

Custom properties on any of them: `surface` (grass, sand, wood, stone, road, ice: friction and footsteps), `camera = false` (the camera passes through: palms, barrels, a mast), on a lamp `night = true` (it lights up at dusk), `flicker`, `intensity`, `radius`. Blender writes them when "Include › Custom Properties" is ticked in the glTF export, and its lights with "Include › Punctual Lights". The helpers are not drawn, and `solid()` leaves them out.

They take their place at the scene's first frame, where the model is then: place a model with `model(path, at, scale)`, `rotate` and `onGround`, then they follow. The island of the demo is laid out this way: its collision volumes, the sailor's start and the evening lamps are in `village.glb` and `port.glb` (written by `tools/create_assets.py`, as Blender would).

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
- **Only what the camera sees.** Each copy is tested against the view (its bounding sphere against the six planes), the haze (past `lighting.fogEnd` nothing shows) and its own `drawDistance`; only what stands in the shadow square casts into the shadow map, so a tree behind the camera still shades the ground in front. The picture is the same as when everything is drawn (`--no-cull` draws everything, for comparisons). A node that has not moved keeps its sphere from one frame to the next. In a forest of 40,000 blocks seen from inside, the GPU does four times less and the CPU 1.6 times less; the run's last line gives `drawn=` and `culled=`.
- **Levels of detail.** `node.lod(lighterMesh, from = 40)` draws a lighter mesh from 40 m on (several may follow each other; `lod("models/tree_far.glb", 80)` takes a model's file); `node.drawDistance = 120f` stops drawing it farther away. `mesh.simplified(.25)` makes such a lighter version by itself (a quarter of the triangles: edges collapsed where the surface changes least, borders, seams and materials kept; the island's village, 17,597 triangles, in half a second), and so does Blender's Decimate modifier: `node.lod(node.mesh.simplified(.3), from = 40)`.
- **One draw call per mesh.** Every copy of a mesh is drawn in one call, with its model matrices in an instance buffer. Primitives of the same size and colour share their mesh, so a thousand `box(.5)` cost one draw; for your own meshes, build one `Mesh` and give it to many nodes (`mesh(crate, at = …)`). To change one node's look, give it another mesh rather than editing a shared one.
- **Two frames in flight.** The CPU prepares the next frame while the GPU draws the last one.
- **Keys acting on the next picture.** When the driver can tell when a frame is on screen (Mesa does), the loop waits for the last frame to be shown before reading the keys: a key press reaches the screen one refresh later (16.6 ms at 60 Hz, against about 100 ms with the frames queued in FIFO mode). A tap shorter than a frame is never lost.
- **Shadows in cascades.** Three shadow squares one inside the other: the finest (`lighting.shadowRange`, 24 m) around what the camera looks at, then three times wider, then out to the haze's end, so that far things keep their shadows, softer. The finest is drawn every frame, the second every two frames and the third every four: at most two shadow passes per frame. In a forest of 3,600 pillars at dusk, shadows out to the horizon cost about 0.7 ms of GPU more than the finest square alone; `lighting.shadowCascades = 1` keeps only the finest. Each pass draws only what stands in its square.
- **The picture in linear light.** The scene is drawn in 16-bit floating point with 4× anti-aliasing, then a halo (five levels down and up) and a last pass for the ink lines, the tone and the vignette. This is what the GPU spends most on: on the island, about 0.8 ms per frame in 1280 × 800 and 2.9 ms in 2560 × 1600 (0.4 and 1.1 ms before, with plain 8-bit colour); in 2560 × 1600 the 16-bit image costs about 0.9 ms, the anti-aliasing 1 ms, the textures 0.3 ms and the halo 0.1 ms.
- **Queries through a grid.** Rays, sphere sweeps and contact queries (the player, shadows, the camera) only look at the bodies near them.
- **No garbage in the hot loops.** Collision tests, rays and sweeps work on plain numbers and create no objects; the physics step, the mixer and the frame recording reuse their buffers. The garbage collector has little to do while the game runs.

Measured on an Intel Core Ultra 7 laptop (efficiency cores, Arc B390): the demo island takes about 0.6 ms of CPU and 0.8 ms of GPU per frame in 1280 × 800 (before the pass in linear light: 0.4 ms of GPU); 10,000 crates on screen, 1.4 ms CPU and 0.4 ms GPU; a heap of 3,000 falling crates with shadows, about 2 ms per frame; 10,000 awake bodies in a heap, about 22 ms per physics step.

### Shipping a game

```
./sillage package mygame
```

makes `build/package/mygame/mygame-x86_64.AppImage`: one file that holds the game, a Java runtime cut down to the modules the game uses (`jlink`), and an AOT cache. Copy it anywhere and run it; no JDK to install. The cache comes from a training run without a window (900 frames, no sound) made while packaging; on the playground it brings the first frame from about 1.3 to 0.9 s after the program starts (measured on a loaded laptop; the run's last line gives `first-frame=`). The runtime holds only `java.base`: the engine writes its PNG files itself and reads its start time from the process, so the AppImage of the playground weighs 30 MB. The same run draws the AppImage's icon: a 256 × 256 picture of the game. The AppImage tools (`appimagetool` and the type 2 runtime, which needs no FUSE library) are fetched once at fixed versions and checked against their checksums. The AppImage runs on distributions whose C library is as recent as the one it was packaged on.

The options of the game work as usual (`mygame-x86_64.AppImage --mute`), and it keeps the folder it was started from as its working directory.

The same game goes to other systems from the same computer:

| | |
|---|---|
| `./sillage package mygame --arch aarch64` | an AppImage for a Raspberry Pi 4, 5 or 500 (64-bit OS) |
| `./sillage package mygame --os windows` | a folder and its .zip for 64-bit Windows: the game, its Java runtime, `mygame.bat` |
| `./sillage package mygame --portable` | the game's jars and a launcher, for any system that has a Java 25 of its own (FreeBSD) |
| `./sillage web mygame` | a web page, in `build/web/mygame` (`--serve` shows it at http://127.0.0.1:8080) |
| `./kotlin build -m mygame-android` | an Android application |

The web and Android builds each need a small module beside the game (`mygame-web`, `mygame-android`) holding a link to the game's `Main.kt`: copy `demo-web/` and `demo-android/`. A game's code does not change from one system to the next; what it reads from the computer (pads, a mouse) may simply not be there. [PLATFORMS.md](PLATFORMS.md) has the recipe for each system, what to install there, and what each was tried on.
