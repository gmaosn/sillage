# API reference

Everything public in the engine, by package. The [guide](GUIDE.md) shows how the pieces fit together; this page is for looking things up. The source comments (KDoc) say the same at the point of use.

Conventions:

- **Units**: metres, seconds, kilograms. Y points up, and the default view looks towards −Z.
- **Angles**: degrees in the scene API (`rotate`, `spin`, `yaw`, camera angles, `fov`, slopes). Radians in `sillage.math` (`Quat.axisAngle`, `Mat4.perspective`) and for `Player.turnSpeed`.
- **Numbers**: scene builders take any `Number` (`box(1)`, `box(.5)`, `box(size)`). The physics and math packages take `Float`.
- **Chaining**: most `Node` calls return the node: `box(1).at(0, 3, 0).dynamic().shadow()`.
- **Values**: `Vec3`, `Quat`, `Mat4`, `Color`, `Material`, the shapes and `Body` are immutable values. Compare them with `==`, never with `===`: they are meant to become value classes when the JVM has them (`@ValueCandidate`).

Packages: [`sillage`](#sillage) (games and scenes), [`sillage.math`](#sillagemath), [`sillage.physics`](#sillagephysics), [`sillage.audio`](#sillageaudio), [`sillage.io`](#sillageio).

---

## sillage

### `game`

```kotlin
fun game(title: String = "Sillage", args: Array<String> = emptyArray(), setup: Scene.() -> Unit): RunReport
```

Opens a window (Wayland, or X11 if there is no Wayland display), builds the scene with `setup`, then runs the loop until the window closes or Escape is pressed. `args` are the program's arguments; the engine reads its options from them (`--help` lists them; see the guide). Returns a `RunReport`:

| Property | |
|---|---|
| `frames` | frames drawn |
| `meanMs`, `p99Ms`, `maxMs` | CPU time per frame: mean, 99th percentile, worst (the first second left out) |
| `gpuMs` | median GPU time per frame (0 when the GPU cannot time) |
| `firstFrameMs` | time from the JVM's start to the first frame |
| `playerPosition` | where the first player ended |

### `Scene`

The world being played. Inside `game { }` you are in one. `Scene().apply { … }` makes one without a window, advanced with `step`.

| Member | |
|---|---|
| **Making things** | |
| `box(width, height = width, depth = width, color = WOOD, round = 0, at)` | box centred on `at`; `round` rounds edges and corners (metres) |
| `sphere(radius = .5, color = RED, at)` | |
| `capsule(radius = .3, height = 1.6, color = ORANGE, at)` | standing on Y; `height` from tip to tip |
| `cylinder(radius = .5, height = 1, color = GREY, at)` | standing on Y; collides as a box |
| `cone(radius = .5, height = 1, color = YELLOW, at)` | standing on Y |
| `plane(width = 10, depth = width, color = GRASS, at)` | flat, facing up, not solid |
| `ground(size = 400, color = GRASS, surface = Material.GRASS, at)` | flat solid floor, its edge lost in the haze |
| `water(level = 0, color = WATER, size = 300, shallows = null, reach = 6)` | sea or lake, not solid: a lagoon of `shallows` colour (lighter than `color` by default) `reach` metres out from its shores, foam, surf and drifting crests |
| `model(path, at, scale = 1)` | a `.glb` model, from disk or the resources; animated models keep their parts and clips |
| `mesh(mesh, at)` | a `Mesh` of your own |
| `chain(from, to, links, radius = .03, color = GREY, start = null, end = null, density = 2000)` | a chain of dynamic capsule nodes joined by ball joints, hanging from `start` (or the world), holding `end`; the links |
| `player(model = null, at, radius = .28, height = 1.8, setup = {})` | a `Player`; without a model, a capsule |
| `collider(shape, at, rotation, blocksCamera = true, surface = "stone")` | an invisible static `Body` |
| `zone(shape, at, onEnter = {}, onExit = {})` | a `Zone` noticing nodes coming in and out |
| `pickup(node, radius = .8, celebrate = true, onPickup = {})` | a `Pickup`: the node disappears with a chime when a player reaches it |
| `remove(node)` | takes a node and its bodies out |
| **What is there** | |
| `nodes`, `players`, `player`, `pickups` | the nodes in creation order; the players; the first player (followed by the camera); the pickups |
| `physics` | the `PhysicsWorld` underneath |
| `camera`, `lighting`, `hud`, `input` | see below |
| `debug` | the debugging tools (F5–F8), see `Debug` below |
| `audio` | the `Audio` output, null when muted; set it to hear or record a scene run with `step` |
| `time`, `fps` | seconds since the start; frames per second over the last half second |
| `fallLimit` | a player below this height (−10) respawns |
| `groundHeight(x, z)` | height of the solid meshes under a point, 0 where there are none |
| `autoTextures` | models (and `ground`) get ready-made textures from their material names (true); set it before adding them |
| **Lamps and particles** | |
| `light(color = warm, intensity = 1.5, radius = 6, at, onlyAtNight = false, flicker = 0)` | a `PointLight` |
| `lights`, `removeLight(light)` | the lamps; the 16 nearest the camera are drawn |
| `emit(style, at, rate = 20)`, `removeEmitter(emitter)` | an `Emitter`: `rate` particles per second |
| `burst(style, at, count = 12, direction = style.direction)` | particles all at once |
| **Time and events** | |
| `onUpdate { dt -> … }` | every frame, with the frame's duration |
| `onKey(key) { … }` | when a key goes down |
| `after(seconds) { … }`, `every(seconds, randomExtra = 0) { … }` | once later; repeatedly, with a random extra delay each time |
| `onRestart { … }`, `restart()` | players to their spawn, pickups back, then the `onRestart` blocks |
| `goTo { … }` | replaces the scene by a new one at the end of the frame; window and sound stay |
| `step(dt)` | reads the keys and advances everything but the drawing (tests, servers) |
| **Sound** | |
| `play(sound, at = null, volume = 1, pitch = 1, range = 60)` | once, in space (heard within `range` m) or everywhere; returns the `Voice`, null when muted |
| `ambience(sound, at = null, volume = .5, range = 60)` | a loop: waves, wind, a fountain; returns an `Ambience` whose `volume` can change at any time |
| `reverb` | how the place sounds (a `Reverb`, or null) |
| `reverbZone(shape, at, reverb, rotation = IDENTITY)` | while the camera is inside, sounds echo as in `reverb` |
| `toggleMute()` | |

`Zone`: `trigger` (the physics `Trigger`), `filter: (Node) -> Boolean`, `enabled`.
`Pickup`: `node`, `collected`.

### `Node`

Something in the scene: a mesh, or an animated model drawn as parts.

| Member | |
|---|---|
| `position`, `rotation`, `scale`, `yaw` | placement; `yaw` in degrees about Y (0 faces −Z) |
| `visible`, `name`, `tag` | `tag` is yours: the engine never reads it |
| `castsShadow` | whether it casts a shadow from the sun (true); it receives shadows either way |
| `mesh` | what is drawn; replace it to change the look (primitives share meshes: do not edit one in place) |
| `model`, `parts`, `animator` | for a model: where it came from, its drawable parts, its clip player |
| `body` | its rigid body, once it takes part in the physics |
| `matrix()` | translation × rotation × scale |
| `at(x, y, z)`, `at(vec)` | places it (and its body) |
| `rotate(x = 0, y = 0, z = 0)` | turns it by degrees from its current rotation (X, then Y, then Z) |
| `scaled(factor)` | size factor; set it before `dynamic` or `solid` |
| `dynamic(mass, density = 400, bounce = 0, friction = .6, shape, surface = "wood", impact = AUTO, damping = 0, angularDamping = 0, rollingResistance)` | a moving rigid body. Shape: the primitive's, a model's bounding box, or `shape` (in metres, not scaled) |
| `solid(vararg materials, blocksCamera = true, surface)` | still and solid; a model collides with its triangles (only those of `materials` if given), each with the surface of its material name |
| `kinematic()` | moved by the game: set `position`/`rotation`, the body follows smoothly and pushes what it meets |
| `collider(shape, offset, part, surface = "wood", blocksCamera = true)` | an extra collision shape; with `part`, it follows that part of an animated model |
| `sound(sound, part = null, volume = 1, pitch = 1, range = 40, loop = false)` | plays from the node (or its animated `part`) and follows it while playing; the `Voice`, null without sound |
| `hangFrom(anchor, to = null)`, `hinge(anchor, axis, to = null)`, `slider(axis, to = null)`, `glue(to = null)`, `rope(from, at = position, to = null, length)`, `spring(from, hertz, at = position, to = null, dampingRatio = .5)` | joints to node `to`, or to the world (null, or a node without a body of its own); dynamic first. They return the `Joint` (limits, motors, springs) |
| `shadow(radius)` | a soft round shadow under it, drawn when the cast shadows are off (moving bodies get one by themselves) |
| `play(clip, loop = true, speed = 1, fade = .15)` | plays a clip of its model, cross-fading |
| `onUpdate { dt -> … }` | every frame, with the node as receiver |
| `spin(degreesPerSecond = 90, axis = Y)` | turns on itself |
| `texture(texture, vararg materials, tile, strength)` | lays a `Texture` (null: none) on it, or on the triangles of `materials` |
| `glow(color, vararg materials, intensity = 1, atNight = false)` | its own light (above 1 it blooms), or only at night |
| `sway(metres = .05, vararg materials, reach = 1)` | moves in the wind, less near the ground; materials may end with `*` |
| `light(color, intensity, radius, offset, onlyAtNight, flicker)` | a `PointLight` that follows it |
| `emit(style, rate = 20, offset)` | an `Emitter` that follows it |
| `remove()` | also removes its lamps and emitters |

`Impact` is the sound a body makes when it hits something: `AUTO` (a wooden knock for boxes, a rubber bounce for balls), `WOOD`, `RUBBER`, `SILENT`.

### `Debug`

What `scene.debug` offers; the keys work in every game while `keys` is true.

| Member | |
|---|---|
| `keys` | whether F5 (physics), F6 (free camera), F7 (pause), F8 (one frame) do anything (true) |
| `showPhysics` | shapes, static meshes near the camera, zones, joints, contacts, speeds drawn over the scene, with the figures' bar and the panel about what is under the cross |
| `showContacts`, `meshRadius` | contacts drawn with the shapes (true); static meshes within this distance of the camera (25 m) |
| `freeCamera`, `freeSpeed`, `freePosition`, `freeYaw`, `freePitch` | the free camera (the player waits while it flies), its speed in m/s (6, ×4 with Shift), where it is and looks (degrees) |
| `paused`, `step()` | the game's time stands still; one frame forward |
| `describe(eye, direction)` | what a ray meets first, in a few lines (node, place and distance, body, surface, mesh) |

### `Player`

A `Node` that walks, runs, jumps, climbs low steps, stands on and pushes things, driven by the keys and the camera. Underneath it is a physics `Character`.

| Member | |
|---|---|
| `runSpeed`, `walkSpeed`, `jumpSpeed` | 4.4 m/s, 1.8 m/s, 8 m/s upwards |
| `turnSpeed` | how fast it faces where it goes (14 rad/s) |
| `controls` | true: keys and camera drive it; false: drive it with `move` and `jump` |
| `keys` | a `PlayerKeys`: `forward`, `back`, `left`, `right`, `jump`, `walk` (arrays of `Key` codes) |
| `animations` | a `PlayerAnimations`: clip names `idle`, `walk`, `run`, `jump`, `fall`, `land` (after `landAfter` s in the air), `push`, `celebrate`, `wave` (after `idleFor` s still); `null` skips a clip |
| `sounds` | footsteps, jump and landing sounds |
| `effects` | dust when it lands on sand or a path, a splash when it falls into water |
| `view` | the camera mode, as a shortcut |
| `position` | the point between the feet; setting it teleports |
| `spawn` | where `respawn` and `restart` put it |
| `onGround`, `velocity`, `isMoving` | its state |
| `character` | the physics `Character` (what it stands on, what it pushes) |
| `move(direction, walk = false)` | moves this frame along a horizontal direction of length up to 1 |
| `jump()` | jumps if on the ground or just off a ledge; an early press is kept a moment |
| `respawn()`, `celebrate(seconds = .9)` | |

### `Camera` and `CameraMode`

`CameraMode.THIRD_PERSON` follows behind and above, turned by the mouse, keeping out of walls and hills and ignoring thin things. `FIRST_PERSON` sees from the eyes; the mouse turns the head and the player walks where it looks. `FIXED` is set by `lookAt`.

| Member | |
|---|---|
| `mode`, `switchKey` | the mode; a key toggling first and third person (null: none) |
| `target` | the node followed (the first player by default) |
| `distance`, `minDistance`, `maxDistance` | third person (7, 2.5, 12 m); the wheel zooms |
| `targetHeight`, `elevation` | third person: the point looked at above the feet (1.1 m); the camera's height as an angle |
| `eyeHeight`, `pitch` | first person: eyes above the feet (from the player's height if null); looking up or down (degrees) |
| `yaw` | turn about the vertical (degrees, 0 looks towards −Z) |
| `fov`, `near`, `far`, `mouseSensitivity` | 58°, 0.08 m, 200 m, 1 |
| `position`, `forward` | where it is and looks, updated every frame |
| `lookAt(eye, at)` | fixed camera |
| `follow(node, mode = THIRD_PERSON)` | |
| `moveDirection(x, z)` | a ground direction from the view: x to the right, z forward |

### `Input`, `Key`, `KeySource`

`Key` holds key codes named by their place on a US QWERTY keyboard (Linux evdev codes): `Key.W` is the key above `S` whatever the layout. Letters, digits, `F1`–`F12`, arrows, `SPACE`, `ENTER`, `ESCAPE`, `TAB`, `BACKSPACE`, `LEFT_SHIFT`, `RIGHT_SHIFT`, `LEFT_CTRL`, `RIGHT_CTRL`, `LEFT_ALT`, `RIGHT_ALT`, punctuation, `HOME`, `END`, `PAGE_UP`, `PAGE_DOWN`, `INSERT`, `DELETE`. `Key.SHIFT` means either shift key.

| `Input` member | |
|---|---|
| `isDown(key)` | held now |
| `pressed(key)` | went down this frame |
| `axis(negative, positive)` | −1, 0 or 1 |
| `mouseDelta`, `wheel`, `mouseCaptured` | this frame's mouse movement and wheel; whether the window holds the mouse |
| `source` | where keys come from: the window, or your `KeySource` (`fun interface KeySource { fun isDown(key: Int): Boolean }`) for tests, replays and bots |

### `Hud`

Texts and panels over the scene, on a canvas 600 units high whose width follows the window's shape. Each element is placed from an `Anchor` (`TOP_LEFT`, `TOP`, `TOP_RIGHT`, `LEFT`, `CENTER`, `RIGHT`, `BOTTOM_LEFT`, `BOTTOM`, `BOTTOM_RIGHT`), its `x` and `y` measured inwards from it.

| Member | |
|---|---|
| `text(value, x, y, size = 2, color = WHITE, anchor = TOP_LEFT, align = AUTO)` | a `Hud.Text`; `value` is a `String` or a `() -> String` evaluated every frame; `size` is the height of a font pixel (a letter is 7 high) |
| `panel(x, y, width, height, color, anchor = TOP_LEFT, opacity = 1)` | a `Hud.Panel`; below 1 the scene shows through |
| `remove(element)`, `clear()` | |
| `showFps` | a frame counter in the top right corner |
| `mouseHint`, `hintPanelColor`, `hintTextColor` | the line shown while the mouse is free (null: none) |
| `Hud.textWidth(text, size)` | width in canvas units |

Elements (`Hud.Element`) have `x`, `y`, `anchor`, `visible`, `opacity` (0 to 1, for see-through panels and fades); a `Text` also `value`, `size`, `color`, `align` (`AUTO`, `LEFT`, `CENTER`, `RIGHT`); a `Panel` `width`, `height`, `color`. The font has capitals, small letters, digits, punctuation and French accents. The HUD is rebuilt only when what it shows changes.

### `Lighting`

Light and air of a scene. Every value can change at any time; turning `shadows` on or off takes effect in the next scene.

| Property | |
|---|---|
| `sunDirection`, `sunColor` | towards the sun (any length) |
| `shadeColor` | colour of the sides turned away from the sun |
| `skyAmbient`, `groundAmbient` | light from above and from below |
| `zenith`, `skyMiddle`, `horizon` | the sky's gradient (the horizon colour is also the haze) |
| `sunGlow` | the glow around the sun, and its glitter on water |
| `fogStart`, `fogEnd` | the haze begins and hides everything beyond (45 m, 150 m) |
| `shadows`, `shadowRange` | cast shadows (true), drawn within this many metres of what the camera looks at (24) |
| `time` | the hour, 0 to 24 (null: none, the values above stay as set); once set, the sun, the moon and the colours follow it |
| `dayLength` | seconds of play for 24 hours (0: the hour stands still) |
| `sunrise`, `sunTilt` | where the sun rises (a direction on the ground) and how far from the zenith it passes at noon (30°) |
| `night`, `moonDirection` | read only: 0 by day, 1 at night; towards the moon |
| `wind` | on the ground plane (x, z), its length the strength (0 calm, 1 a strong breeze) |
| `glow`, `outlines`, `vignette`, `saturation` | halo around what is brighter than white (0.8), ink along silhouettes (0.85), darker corners (0.22), colour richness (1.06) |

### Lamps and particles

`PointLight`: `color`, `intensity`, `radius`, `position` (in the world, or in its node's space), `node`, `onlyAtNight`, `flicker` (0 steady, 1 a flame), `enabled`, `worldPosition`.

`Emitter`: `style`, `rate` (per second), `position` (in the world or its node's space), `node`, `enabled`.

`ParticleStyle(color, endColor = color, size = .1, endSize = size, life = 1, lifeSpread = .3, speed = 1, speedSpread = .5, direction = Y, spread = 30, area = 0, gravity = 0, drag = 0, wander = 0, wind = 0, opacity = 1, glow = false, brightness = 1, twinkle = 0)`: sizes are radii in metres; `spread` the half-angle of the cone (degrees, 180 every way); `area` the radius of the disc they are born in; `gravity` in m/s² downwards (negative: they rise); `drag` per second; `wander` a random drift (m/s); `wind` how much `Lighting.wind` carries them; glowing particles add their light (`brightness` above 1 blooms), the others are lit like the scene.

`Particles`: `FIRE`, `SMOKE`, `SPARKS`, `SPLASH`, `SPRAY`, `DUST`, `FIREFLIES`, `SPARKLE`.

### `Texture` and `Textures`

`Texture`: a detail pattern that shades the colour it lies on (mid-grey: unchanged), or an image painted on a model (`Mapping.UV`). `name`, `size` (pixels, a power of two), `tile` (metres per repeat), `strength`, `mapping` (`Texture.Mapping.SIDES`: from the three axes, blended by the slope; `FACES`: along each flat face, rows level). `Texture.paint(name, tile = 1, strength = 1, size = 256, mapping = SIDES) { u, v -> color }` paints one from a function (u, v from 0 to 1; it must join up at the edges). `Texture.image(name, image)` and `Texture.image(name, encodedBytes)` make an image texture (the second decodes a PNG or JPEG once per program). Textures are painted in the background and sent to the GPU once; they live as long as the program.

`Textures`: `GRASS`, `SAND`, `WOOD`, `PLANKS`, `ROOF_TILES`, `STONE`, `ROCK`, `PLASTER`, `FABRIC`, `LEAVES`, `BARK`, `ALL`; `named(material)` picks one from a material name in English or French (case and accents ignored), or null.

### `Color`, `Mesh`, `MeshBuilder`, `vec`

`Color(r, g, b)` is in linear light. `Color.hex(0xRRGGBB)` takes a colour as seen on screen (sRGB). `mix(other, t)`, `times(factor)`. The palette: `WHITE`, `BLACK`, `GREY`, `RED`, `ORANGE`, `YELLOW`, `GREEN`, `BLUE`, `PURPLE`, `PINK`, `BROWN`, `GRASS`, `SAND`, `WOOD`, `STONE`, `WATER`, `SKY`.

`Mesh(vertices)` holds triangles without indices: per vertex, position, normal and colour (9 floats). A zero normal draws the vertex unlit. Members: `triangleCount`, `bounds` (min and max corners), `smooth(angle = 55)` (normals averaged between faces less than `angle` degrees apart), `colored(color)`, `transformed(matrix)`, `plus(other)`. Ready shapes: `Mesh.box(width, height, depth, color, round)`, `sphere`, `capsule`, `cylinder(…, topRadius)`, `cone`, `plane`, `disk`, and `Mesh.EMPTY`.

`MeshBuilder` assembles a mesh with flat normals: `triangle(a, b, c, color)` (counter-clockwise seen from the front), `quad(a, b, c, d, color)`, `box(x, y, z, width, height, depth, color)` (base centred on the point), `disk(x, y, z, radius, color, segments)`, `add(mesh)`, `build()`.

`vec(x, y, z)` makes a `Vec3` from any numbers.

### Models and animation

`Glb` loads glTF 2.0 binary files: `load(path)` gives the model in its rest pose as one `Mesh`, and `loadModel(path)` the `Model` with its hierarchy, clips, textures and skins. `read(bytes)` and `readModel(bytes, beside)` do the same from bytes (`beside` gives the bytes of a file the GLB names, an image beside it). Paths are looked up on disk, then in the resources. Cubic-spline curves and morph targets are refused with a message.

| Class | |
|---|---|
| `Model` | `nodes` (`ModelNode`s), `roots`, `clips` (by name), `materials` (names by index), `textures` (each material's image texture or null), `skins`, `order` (parents before children); `flatten()` (rest pose as one mesh, skins included), `surfaces()` (world triangles and their material indices, for collision), `parts()` |
| `ModelNode` | `name`, `parent`, `children`, `translation`, `rotation`, `scale`, `matrix` (when given instead of TRS), `local`, `skin` (index in `skins`, −1: none) |
| `Skin` | `joints` (node indices), `inverseBind` (one `Mat4` per joint) |
| `Clip` | `name`, `channels`, `duration` |
| `Channel` | `node`, `target` (`ChannelTarget.TRANSLATION`, `ROTATION`, `SCALE`), `times`, `values`, `step` |
| `Part` | a drawable piece: `node`, `mesh` (its own copy once textured, glowing or swaying), `pose` |
| `Animator` | plays clips on one instance: `model`, `time`, `speed`, `current`, `play(name, loop = true, fade = .15, speed)`, `advance(dt)`, `nodePose(name)` (a part's current matrix in model space) |

---

## sillage.math

### `Vec3`

`Vec3(x, y, z)`. Operators `+`, `-`, `*` (by a `Float`, either side), `/`, unary `-`, `[axis]`. `dot`, `cross` (infix), `length()`, `lengthSquared()`, `distanceTo(o)`, `normalized()` (zero stays zero), `lerp(to, t)`, `scaled(o)` (component-wise), `plus(dx, dy, dz)`, `withX`, `withY`, `withZ`, `isFinite()`. Constants `ZERO`, `ONE`, `X`, `Y`, `Z`, `UP`.

### `Quat`

A rotation `Quat(x, y, z, w)`, in glTF's component order. `*` composes (`a * b` turns by `b`, then by `a`). `rotate(v)` turns a vector (the quaternion must be unit length). Also `normalized()`, `conjugate()`, `dot`, `lengthSquared()`, `slerp(to, t)`. Companion: `IDENTITY`, `axisAngle(axis, radians)`, `rotationY(radians)`, `fromAxes(x, y, z)` (from three orthonormal axes), `slerpInto(…)` (on flat arrays, for animation).

### `Mat4`

A 4 × 4 matrix whose coefficients are named column first: `m30`, `m31`, `m32` are the translation. `*`, `transformPoint(p)`, `transformDirection(d)`, `translation`, `determinant3x3()`, `writeTo(array, offset)` and `toFloatArray()` (column-major, as Vulkan takes it). Companion: `IDENTITY`, `translation(t)`, `scaling(s)`, `rotation(q)`, `rotationY(radians)`, `trs(t, r, s)`, `trsY(t, radians, s)`, `perspective(fovY, aspect, near, far)` (Vulkan's depth range and Y), `lookAt(eye, target, up)`, `fromColumnMajor(array, offset)`.

### `@ValueCandidate`

Marks immutable classes without identity that should become value classes. Do not compare them with `===` or synchronise on them.

---

## sillage.physics

See [PHYSICS.md](PHYSICS.md) for a tour.

### `PhysicsWorld`

`PhysicsWorld(gravity = (0, −9.81, 0))`.

| Member | |
|---|---|
| `add(shape, position, rotation = IDENTITY, type = DYNAMIC, density = 1000, mass = null, friction = .6, restitution = 0, rollingResistance = null)` | a new `Body` |
| `addMesh(mesh)`, `addMesh(mesh, friction)` | static triangles |
| `remove(body)` | takes a body out; its id may be reused |
| `body(id)`, `bodyCount`, `liveBodyCount` | |
| `applyForce(body, force)` | at the centre, during the next step |
| `addBall(a, b, anchor, collideConnected = false)`, `addHinge(a, b, anchor, axis, …)`, `addSlider(a, b, axis, anchor = b.position, …)`, `addFixed(a, b, anchor = b.position, …)` | joints; `a` null: the world |
| `addDistance(a, anchorA, b, anchorB, length = null, collideConnected = true)`, `addSpring(a, anchorA, b, anchorB, hertz, dampingRatio = .5, length = null)`, `addRope(a, anchorA, b, anchorB, length = null)` | `DistanceJoint`s |
| `addChain(from, to, links, radius = .03, start = null, end = null, density = 2000, friction = .6)` | capsule links joined by ball joints |
| `removeJoint(joint)`, `jointList` | |
| `step(dt)` | advances, then tells the triggers who came in and who left |
| `contacts()`, `pairCount`, `contactCount` | the last step's contacts |
| `raycast(origin, direction, maxDistance, radius = 0, meshes = true, filter = null)` | a `RayHit` or null; with a radius, a sphere sweep |
| `groundBelow(x, z, fromY = 1000, depth = 2000, filter = null)` | a downward ray |
| `overlaps(shape, position, rotation = IDENTITY, margin = 0, exclude = null)` | `Overlap`s of a shape placed there |
| `addTrigger(shape, position, rotation = IDENTITY)`, `removeTrigger(trigger)` | trigger zones |
| `gravity`, `substeps`, `contactHertz`, `contactDampingRatio`, `contactSpeed`, `restitutionThreshold`, `speculativeDistance`, `supportRatio`, `sleepLinearSpeed`, `timeToSleep`, `gyroscopic`, `parallel`, `vectorized`, `jointHertz`, `jointDampingRatio`, `continuous` | settings (see PHYSICS.md) |
| `continuousHits` | bodies put back by continuous collision so far |
| `lastStepVectorized` | whether the last step solved its contacts on SIMD lanes (`--add-modules jdk.incubator.vector`) |
| `profiling`, `phaseNanos` | time per phase, accumulated while `profiling` |

### `Body` and `BodyType`

`BodyType`: `STATIC`, `DYNAMIC`, `KINEMATIC`. `Body` is a handle (`id`), equal to any other handle on the same body.

| Member | |
|---|---|
| `position`, `rotation`, `velocity`, `angularVelocity` | read and write; writing wakes the body |
| `mass` | infinite for static and kinematic bodies |
| `friction`, `restitution`, `rollingResistance`, `linearDamping`, `angularDamping`, `gravityScale` | |
| `type`, `shape`, `isSleeping`, `isRemoved`, `tag` | |
| `applyImpulse(impulse, point = position)` | dynamic bodies only |
| `applyForce(force, point = position)` | during the next step, spread over its substeps |
| `isBullet` | swept against every body, not only against what does not move |
| `moveTo(target, dt, targetRotation = null)` | kinematic bodies: velocities that reach the target in `dt` |

### Shapes

`Shape` (sealed): `volume`, `boundingRadius`, `inertia(mass)`. `Sphere(radius)`; `Box(halfX, halfY, halfZ)` with `half(axis)`; `Capsule(radius, halfHeight)` along local Y.

### `TriangleMesh`

`TriangleMesh(triangles, materialIds = null, palette = [DEFAULT])` or `TriangleMesh(triangles, material)`. Nine floats per triangle; one-sided, the front being counter-clockwise. `count` is the number of triangles kept. `query(minX, minY, minZ, maxX, maxY, maxZ, out, stack)` lists the triangles whose bounds meet a box, into an `IntList` (a growable `Int` list: `add`, `get`, `size`, `clear`, `sort`, `pop`).

### `Material`

`Material(friction = .6, rollingResistance = 0, bounce = 1, name = "default")`. Presets `DEFAULT`, `ROAD`, `GRASS`, `SAND`, `WOOD`, `STONE`, `ICE`; `Material.named(name)` from an English or French material name.

### Query results

- `RayHit`: `distance`, `point`, `normal`, `body` (null for a mesh), `material` (for a mesh).
- `Overlap`: `body`, `material`, `point`, `normal` (from the obstacle to the shape), `separation` (negative: overlap).
- `Contact`: `a` (null for a mesh), `b`, `point`, `normal` (from a to b), `separation`, `normalImpulse`.

### Joints

`Joint` (sealed): `a` (null: the world), `b`, `anchorA`, `anchorB` (where they are now), `collideConnected`, `enabled`, `isRemoved`, `force` (N, during the last step).

- `BallJoint`.
- `HingeJoint`: `angle`, `speed`, `axis`; `limits(lower, upper)`, `motor(speed, maxTorque)`, `spring(hertz, dampingRatio = 1, angle = 0)`; their settings read back (`lowerAngle`, `motorSpeed`, `springHertz`…).
- `SliderJoint`: `translation`, `speed`, `axis`; `limits(lower, upper)`, `motor(speed, maxForce)`, `spring(hertz, dampingRatio = 1, position = 0)`.
- `FixedJoint`.
- `DistanceJoint`: `length` (settable), `currentLength`, `rope`, `minLength`, `maxLength`, `springHertz`; `spring(hertz, dampingRatio = .5)`, `limits(min, max)`.

A `null` in `limits` or `motor` turns them off.

### `Vehicle`

`Vehicle(world, chassis, forward = Vec3.Z, up = Vec3.Y)`: a raycast vehicle on a dynamic chassis; `update(dt)` before each step.

| Member | |
|---|---|
| `addWheel(mount, radius = .35, travel = .3, steered = false, driven = true, handbraked = !steered)` | a `Wheel` hanging at `mount` (chassis frame) when the spring is out |
| `throttle` (−1..1), `brake` (0..1), `steering` (−1 left..1 right), `handbrake` | the driver |
| `driveForce`, `topSpeed`, `reverseSpeed`, `brakeForce`, `maxSteer`, `steerSpeed`, `suspensionHertz`, `suspensionDamping`, `grip`, `rollingResistance`, `rollInfluence` | its make |
| `speed`, `wheels`, `wheelsOnGround` | |

`Wheel`: `mount`, `radius`, `travel`, `steered`, `driven`, `handbraked`; after each update `compression`, `onGround`, `contact`, `normal`, `ground`, `load` (N), `slip` (m/s), `spin`, `spinSpeed`, `steer`, `center` and `rotation` (where to draw it).

### `Trigger`

Made by `addTrigger`. `world`, `shape`, `position`, `rotation`, `filter: (Body) -> Boolean` (bodies that are not static, by default), `enabled`, `onEnter`, `onExit`, `inside`.

### `Character`

`Character(world, feet, radius = .28, height = 1.8, mass = 70)`: a walking capsule moved by the game.

| Member | |
|---|---|
| `update(walk, jumpSpeed, dt)` | after `world.step`: `walk` is the wanted horizontal velocity, `jumpSpeed` > 0 jumps |
| `teleport(feet)` | |
| `position`, `velocity`, `onGround`, `groundNormal`, `groundBody`, `groundMaterial`, `pushing` | state after the last update |
| `platformVelocity`, `platformTurn`, `walkVelocity` | what the ground carried it by during the last update, and its own steps |
| `gravity` (−22 m/s²), `maxSlope` (50°), `stepHeight` (0.35 m), `snapDistance` (0.3 m), `pushForce` (400 N), `mass` | settings |
| `body` | its kinematic capsule in the world |

---

## sillage.audio

### `Sound`

`Sound(data, channels = 1, rate = 48000)`: float samples in −1..1, mono or interleaved stereo. `frames`, `duration`, `toWav()`, `save(path)`. `Sound.load(path)` reads an Ogg Vorbis or WAV file from disk or the resources; `Sound.decode(bytes)` from bytes, either kind; `Sound.wav(bytes)` a WAV (8, 16, 24 or 32-bit integers, or 32-bit floats).

### `Vorbis`

`Vorbis.decode(bytes)`: an Ogg Vorbis file into a `Sound`, by the engine's own decoder (Ogg pages and checksums, Vorbis I: codebooks, floor 1, residues 0 to 2, channel coupling, inverse MDCT by FFT), the same as libvorbis to 3·10⁻⁷. Mono and stereo; floor 0 (unused since 2001) and more than two channels are refused, damaged files too, with an `IllegalArgumentException`. `Vorbis.isOgg(bytes)`. The whole file is decoded into memory (a stereo minute at 44.1 kHz takes 21 MB).

### `Sounds`

The engine's synthesised sounds; each call builds the sound again, so keep what you reuse. `footstep(surface, variant)` (grass, sand, wood, stone, road, ice), `jump()`, `land()`, `pickup()`, `knock(variant)`, `bounce(variant)`, `scrape()` (a loop), `waves(variant)` (a loop of about 9 s), `gull(variant)`.

### `Synth`, `Noise`, `Biquad`, `OnePole`

Tools to compute your own sounds. `Synth.sound(seconds) { i, t -> sample }`, `normalize(sound, peak)`, `mix(vararg sounds)`, `loop(sound, crossfade)`, `fade(data, rate, seconds)`, `decay(t, tau, start)`, `modes(t, vararg (hz, amplitude, tau))` (a struck object). `Noise(seed)` gives reproducible white noise (`next()`, `unit()`). `Biquad(rate)` filters (`lowPass`, `highPass`, `bandPass`, then `process(x)`). `OnePole(rate, hz)` smooths (`low(x)`, `high(x)`).

### `Mixer`, `Voice`, `Audio`

`Mixer(rate = 48000, maxVoices = 48)` mixes voices into interleaved stereo floats: `play(sound, volume, pitch, pan, loop, offset, reverb = 0)`, `playAt(sound, position, volume, pitch, loop, referenceDistance = 2, maxDistance = 50, offset, reverb = 1)`, `listen(position, forward, up)`, `masterVolume`, `reverb` (a `Reverb` or null), `doppler` (1: real, 0: none), `speedOfSound` (343 m/s), `playing`, `stopAll(seconds)`, `render(out, frames)`. Volumes and settings glide and nothing clicks. `render` allocates nothing.

`Reverb(decay, wet, damping = .5)`: seconds to fall by 60 dB, loudness of the echo, how much the highs fade (0..1). Presets `OUTDOOR`, `ROOM`, `HALL`, `CAVE`. The mixer's is a feedback delay network of 8 lines in stereo, its decay time exact to about 10 %.

A `Voice` is a playing sound: `isPlaying`, `volume(v)`, `pitch(v)`, `pan(v)`, `position(p)`, `stop(seconds)`.

`Audio` is a mixer plus the system's output (ALSA, which hands the sound to PipeWire or PulseAudio): `Audio.open(device = "default", rate, latencyMs = 40)`, `Audio.offline(rate)` (no output: call `mixer.render` yourself), `device`, `latency`, `volume`, `play`, `playAt`, `listen`, `close()`.

---

## sillage.io

### `Json`

A strict JSON reader (RFC 8259), used for glTF and handy for settings files. `Json.parse(text)` gives a `Json`:

- `Json.Obj`: `fields`, `[key]`, `has`, `obj`, `arr`, `num`, `int`, `float`, `string`, `bool`, each null when missing and an error when of another type.
- `Json.Arr`: iterable; `size`, `[i]`, `obj(i)`, `floats()`, `ints()`.
- `Json.Num`: keeps its text; `toDouble()`, `toFloat()` (one rounding from the decimal text), `toLong()`, `toInt()`.
- `Json.Str` (`value`), `Json.Bool` (`TRUE`, `FALSE`), `Json.Null`.

Nesting deeper than `Json.MAX_DEPTH` (256) is refused.

### `Image`, `Png`, `Jpeg`

`Image(width, height, rgba)`: pixels as 4 bytes each (red, green, blue, alpha), rows from the top; `argb(x, y)`. `Image.decode(bytes)` reads a PNG or a JPEG, found from its first bytes. `Png.decode`: every colour type and bit depth, transparency, interlacing, checksums verified (its own inflate). `Jpeg.decode`: baseline and progressive, restart intervals, chroma subsampling (upsampled by centred interpolation), greyscale, RGB; CMYK, lossless and arithmetic-coded files are refused. Both refuse damaged files with an `IllegalArgumentException` and images over 64 million pixels.

---

## sillage.vk

`VulkanException(operation, result)` is thrown when a Vulkan call fails; `result` is the `VkResult` code. The rest of the Vulkan layer is internal.
