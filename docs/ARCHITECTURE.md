# How Sillage is built

This page is for those who read or change the engine itself. To make games, start with the [README](../README.md), the [tutorial](TUTORIAL.md) and the [guide](GUIDE.md).

## Principles

- **Kotlin and nothing else.** No third-party library in the engine or the games (the toolchain brings its own test runner). The engine talks to the system's C libraries (`libvulkan`, `libwayland-client`, `libxcb`, `libasound`, `libc`) through the JDK's Foreign Function & Memory API (`java.lang.foreign`). Within the JDK it uses only the runtime basics: atomics, thread parking, FFM. Assets come from files or from code: models are glTF files made by `tools/create_assets.py`, and sounds are synthesised.
- **Built with the Kotlin Toolchain** (`./kotlin`), on JDK 25, which the toolchain provides. No Gradle.
- **Deterministic physics.** The same inputs give the same bits, whatever the number of threads. Fingerprint tests guard this.
- **No garbage in hot loops.** The physics step, collision tests, queries, the mixer and frame recording work on arrays and plain numbers. The public types (`Vec3`, `Quat`, `Mat4`, `Color`, `Body`…) are immutable values marked `@ValueCandidate`, ready to become value classes with Valhalla.
- **A public API in English**, documented where it is declared (KDoc) and in `docs/`. Many test names and quality-check messages are in French, the authors' language.

## Modules

| Module | |
|---|---|
| `engine` | the engine (`product: jvm/lib`) and its unit tests (`engine/test`) |
| `playground` | a game made of shapes only: crates, balls, a ramp, a lift, stars; B drops 400 crates |
| `demo` | the island: glTF scenery, animated boat and seagulls, shells to find, sea sounds |
| `bench` | physics benches: `pyramid`, `pile N`, `solver N`, `threads N`, `determinism`, `settle` |
| `qa` | the quality battery: physics, collisions, character, rendering, loaders, API, endurance, performance |

`tools/` holds `shaders.sh` (compiles the GLSL to SPIR-V with `glslc`; the `.spv` files are committed), `create_assets.py` (writes the demo's GLB models, with no third-party Python module) and `reference/` (reference captures).

## Source map

```
engine/src/sillage/
  Game.kt            game(): options, the loop, RunReport
  Scene.kt           the scene: builders, events, the order of a frame's update
  Node.kt Player.kt Camera.kt Input.kt Hud.kt
  Lighting.kt        light, sky, the hour of the day, wind, the picture's finish
  Effects.kt         lamps (PointLight), particles (ParticleStyle, Particles, Emitter, ParticleSystem)
  Texture.kt         detail textures: Texture, the ready-made Textures and their painting tools
  Mesh.kt            Color, Mesh, MeshBuilder, primitive shapes
  Glb.kt Animation.kt   glTF loading, Model, Animator
  SceneSounds.kt     the sounds a scene makes by itself (steps, impacts, scraping)
  VulkanRenderer.kt  the renderer
  vk/                Vulkan through FFM: loader and call handles, C struct layouts, constants
  platform/          windows: Wayland, X11 (XCB), key codes
  physics/           the physics engine
  audio/             sounds, synthesis, the mixer, ALSA output
  math/ io/          Vec3, Quat, Mat4; JSON
engine/resources/shaders/   scene: toon.vert, toon.frag, water.frag, sky.frag, ombre.frag, particle.vert/.frag, shadow.vert;
                            picture: post.vert, bright.frag, down.frag, up.frag, composite.frag; hud.vert/.frag;
                            shared: frame.glsl, light.glsl, shading.glsl, texture.glsl (+ SPIR-V, tools/shaders.sh)
```

## A frame

`game()` builds the scene, opens the window (Wayland if `WAYLAND_DISPLAY` is set, else X11; `SILLAGE_PLATFORM=wayland|x11` forces one) and the renderer, then loops:

1. **Window**: events, resize. Without focus the loop waits.
2. **Waiting for the screen** (`VulkanRenderer.attendre`): until the last frame is on screen (`VK_KHR_present_wait`, when the driver offers it), then until this frame's fence and a swapchain image are free. Then the window's events are read again, mouse movement and wheel included: whatever came during the wait acts on the very next picture.
3. **Input**: `input.poll()` reads the keys from `input.source` (the window by default) and works out which went down this frame. A key that went down and up between two frames still counts as down for one frame (the windows keep it until `clearPresses`).
4. **Scene update** (`Scene.update`): key actions, the camera's switch key, timers, the scene's `onUpdate` blocks, then each node's blocks; animators advance; kinematic bodies and colliders that follow animated parts get their target velocities; **the physics steps** with the frame time, clamped to 1/240–1/30 s; dynamic nodes take their bodies' places; players update their characters (after the step, so they stand where the platforms are); blob shadows follow; the hour moves on (`Lighting.advance`); the scene's own effects (dust where players land, splashes where things fall into water), then the emitters and the particles; scene sounds update.
5. **Camera**: the view matrix, after the players moved.
6. **Drawing** (`VulkanRenderer.dessiner`): see below, with `Scene.frameExtras` (the 16 lamps nearest the eye, dimmed by day if they only light at night, wavering; the particles in flat arrays). The HUD's mesh is rebuilt only if what it shows changed.

Without a window (`--headless`), the same loop draws into an off-screen image with a fixed 1/60 s step, mixes the sound frame by frame and can save the last frame as a PNG. `Scene.step` runs steps 3–5 without any renderer, for tests.

## Rendering

- **Vulkan through FFM** (`vk/`). `Vulkan.kt` loads `libvulkan.so.1` and keeps one `static final` method handle per call signature, which the JIT inlines; the function's address is an argument. `Struct.kt` describes C structs (alignment, nested fields, arrays) and provides `NativeStack`, a native scratch stack reused every frame. `Structs.kt` and `Constants.kt` were written from the Vulkan registry and checked field by field against LWJGL's layouts before LWJGL was removed.
- **Two frames in flight.** Each has its command buffer, fence, semaphores, a persistently mapped instance buffer and HUD buffer. A frame first waits for its fence, so the CPU prepares frame n+1 while the GPU draws frame n.
- **Latency.** In FIFO mode the swapchain (4 images on Mesa under Wayland) let up to three frames wait for the screen: a key read at the start of the loop reached it about 100 ms later at 60 Hz (measured in a headless KWin). Now each present carries a number (`VkPresentIdKHR`) and the next frame waits for it to be shown (`vkWaitForPresentKHR`, at most 0.1 s, as a hidden window may never be shown) before the keys are read: 16.6 ms from the keys to the screen at 60 Hz, still 60 frames per second. The frame then has one refresh for its CPU and GPU work. Without the extension (or with `SILLAGE_PRESENT_WAIT=0`), the swapchain keeps `minImageCount` images, one fewer frame queued. `SILLAGE_LATENCE=1` prints once a second the keys-to-screen time and where a frame waits.
- **Instancing.** Nodes are grouped by `Mesh` (by identity), in `gather`, kept apart from the rest of `dessiner`: a smaller method that C2 compiles on its own (inside the large one, the loop ran up to six times slower, depending on the run). Each group's model matrices go into the instance buffer as three `vec4` rows (attributes 3–5), and the group is drawn in one call. Scene primitives with the same parameters share one `Mesh`, so a thousand identical crates cost one draw. A mesh goes to the GPU the first time it is seen.
- **The frame's light in a uniform block** (`frame.glsl`, one per frame in flight, mapped for good): the main light (the sun, or the moon at night), its colour, the shade, ambient, sky gradient, haze, the sun and moon in the sky, the wind, the camera's axes (for particles) and the lamps (16 positions and colours). Everything in `Lighting` can therefore change from one frame to the next without touching the pipelines.
- **Passes.** (1) The shadow map: a 2048 × 2048 depth image (D16) drawn from the main light by `shadow.vert` alone, through an orthographic box around the camera's focus (`light.glsl`), its centre snapped to the map's texels so that shadow edges stay still, with a slope-scaled depth bias. (2) The scene in linear light, RGBA 16-bit float with 4× MSAA when available, resolved into a single-sample image; its alpha holds the distance to the eye. Opaque nodes (`toon.vert`/`toon.frag`), water (`water.frag`), the sky dome (`sky.frag`, gradient, sun, moon, stars), blob shadows (`ombre.frag`) and particles (`particle.vert/.frag`, one square per instance facing the camera; glowing ones add, the others are blended premultiplied), the blended ones leaving the distance as it was. (3) The halo: the parts brighter than white at half size (`bright.frag`, soft knee), down four more levels (`down.frag`), then up again adding each level (`up.frag`). (4) The finished picture (`composite.frag`) into an RGBA 8-bit image: ink where the distance jumps between neighbours, the halo, a soft shoulder above white, saturation, vignette, sRGB with a little dither; then the HUD over it (`hud.frag`, blended by each element's opacity). The picture is blitted to the swapchain, or read back for a capture.
- **Toon shading** (`shading.glsl`, shared by the fragment shaders): a soft two-tone light (sun colour and shade colour), sky and ground ambient, a rim light, haze towards the sky behind, the lamps (windowed inverse-square falloff, soft toon steps) and the cast shadows: nine filtered lookups of the map through a comparison sampler, the point taken a little off the surface along its normal so that a face never shadows itself, fading out at the map's border.
- **Per-vertex effects.** A mesh may carry 8 more floats per vertex (`Mesh.effects`, vertex binding 2; a zero buffer for the others): its own light (negative: only at night), how far it sways (moved in `swayed()`, in the vertex and shadow shaders alike, so shadows sway too), its texture, tile, strength and mapping. `Node.sway`, `glow` and `texture` write them, for the triangles of the materials named (`Mesh.materials`, kept when a model is flattened); a node then gets its own copy of the mesh, except textures laid by material name or on a whole shared mesh, which are cached so that copies stay one draw.
- **Detail textures** (`Texture.kt`, `texture.glsl`). The ready-made textures are painted by Kotlin code (value noise, cells, strokes and dabs, all wrapping around), on the common pool, as soon as they are made; their mean is brought to neutral grey and every smaller level is computed on the CPU. The renderer keeps one array image, a layer per texture (as large as the largest), sent through a staging buffer when new textures appear; until all are painted, the window shows the others neutral for a few frames (without a window, and before a capture, it waits, so that frames are the same from run to run). The fragment shader reads them in the object's own space: from the three axes blended by the normal (`textureGrad` with derivatives taken outside the branches), or along the face, its true normal from screen derivatives, rows level and climbing the slope.
- **Water**: `Water.kt` lays a grid over the shores, finds the land by rays from above (ground at most 0.9 m above the water counts; a pier's deck does not), computes each vertex's distance to the nearest shore (8SSEDT, then one smoothing pass) and stores it in the vertex colour's red channel. `water.frag` draws the lagoon, the foam, the surf and the crests from that distance, the world position and the time.
- **Push constants**: 128 bytes (the minimum every device guarantees): view × projection, eye and time, the shadow map's centre and half side, the water's two colours. The scene's descriptor set holds the shadow map, the frame's uniform block and the texture array; the picture's passes have their own sets, two images each.
- **GPU time** from timestamp queries, when the device supports them.

## Windows

`platform/WaylandWindow.kt` talks to `libwayland-client` through FFM. Requests use `wl_proxy_marshal_array_flags` (arguments in an array, no variadic calls). Every event arrives through one dispatcher installed with `wl_proxy_add_dispatcher`, routed by object and event name. The interface tables of the protocols outside the library (xdg-shell, xdg-decoration, relative pointer, pointer constraints, cursor shape) are written by hand, as `wayland-scanner` would generate them; `WaylandTablesTest` checks them. The buffer scale follows the compositor (sharp at 200 %). `XcbWindow.kt` is the X11 fallback through `libxcb`. Keys are Linux evdev codes, so they name physical positions on every layout.

## Physics

See [PHYSICS.md](PHYSICS.md) for the user's view. Inside `PhysicsWorld`:

- **Data**: one slot per body in parallel arrays (position, rotation, shape, type, …) and a 20-float solver state per body (velocity, angular velocity, inverse mass, world inverse inertia, displacement and rotation during the step), contiguous so that an impulse touches one or two cache lines. A `Pose` per body (centre and axes as 12 floats) is updated in place when the body moved.
- **Step**: prepare bodies (poses, bounds widened by the distance travelled) → broad phase (uniform grid, sorted `Long` keys) → narrow phase (`Collide.kt`: one routine per pair of shapes, into a per-thread `ContactPoints`) → contact preparation (points matched with last step's by feature id and position for warm starting; friction patches) → substeps (integrate velocities, warm start, solve with bias, integrate positions, relax) → restitution → sleep (islands).
- **Threads**: `Workers.kt` is a pool of up to 8 threads per process. A job is cut into fixed chunks; each chunk writes only its own data, and results are merged in chunk order. Each job is published as an immutable task with its own counters, so a late worker can never take part in the next job. The solver colours the contact graph (at most 32 colours, the rest solved alone at the end) so that contacts of one colour share no moving body. Colouring starts only above a fixed size, so the order depends on the scene, never on the number of threads.
- **Queries**: `QueryGrid` puts bodies in a uniform grid built on demand when bodies moved. Rays walk its cells (Amanatides–Woo) and stop once no closer body can come. Shape tests (`touching`) look at the cells they cover. Results are the same as a body-by-body search, ties included.

### Changing the physics

The order of floating-point operations is part of the result. In the narrow phase and the queries, formulas are written component by component in a fixed order. `CollideTest` keeps fingerprints of thousands of contacts and rays, and the physics tests check determinism with and without threads. A refactoring must leave the fingerprints alone. A change that is meant to alter results updates them, and its commit says so. The `qa` battery then checks the behaviour that matters: stacks hold, nothing sinks or tunnels, the character never ends inside the scenery.

## Sound

`audio/Mixer.kt` mixes up to 48 voices into stereo floats: volume, pitch (resampling), pan or a position in the world (distance attenuation, constant-power panning, a low-pass for distance and for sounds behind the head), gliding volumes, fades on stop, voice stealing, and a soft limiter. The game thread changes voices and the audio thread renders; they meet under a short lock at the start of each block, and rendering allocates nothing. `audio/Audio.kt` writes 16-bit stereo blocks of 5 ms to ALSA from a high-priority thread and recovers from underruns. `audio/Synth.kt` synthesises every sound of the engine from seeded noise, filters and damped modes, so they come out the same every time; the scene pre-synthesises its common sounds in the background as soon as sound starts.

## Tests and checks

| | Command | |
|---|---|---|
| Unit tests | `./kotlin test` | maths, JSON, GLB, physics (including realism against textbook formulas, determinism, fingerprints), character, queries, triggers, scene, camera, audio, Wayland tables |
| Quality battery | `./kotlin run -m qa --jvm-args="--enable-native-access=ALL-UNNAMED -Xmx2g" -- [--rapide] [--seulement group,…]` | 64 checks; report in `build/qa/rapport.md`; speed and allocations against a base recorded on the machine (`qa/reference/performance.txt`), five reference views of the island |
| Benches | `./kotlin run -m bench -- pile 3000` | |
| Smoke run | `./kotlin run -m demo --jvm-args=--enable-native-access=ALL-UNNAMED -- --headless --smoke --frames 180 --capture build/smoke.png` | the player walks and jumps; compare the capture with `tools/reference/demo-smoke-180.png` |

Heavy runs are best capped in memory (`systemd-run --user --scope -p MemoryMax=4G …` with `-Xmx2g`). Measure speed on fixed cores (`taskset`) and compare old and new code alternately: a laptop's speed drifts with its load and temperature.

## Adding things

- **A Vulkan function**: add its signature handle in `vk/Vulkan.kt` if the shape is new, the function, and any struct in `vk/Structs.kt` with its exact layout (check it against the registry).
- **A shape**: a `Shape` subclass with volume, inertia and bounding radius; a rank and the routines against every other shape in `Collide.kt` (and against triangles); its bounds in `PhysicsWorld.prepareBody`; ray and sweep tests in `Queries.kt`; tests; then new fingerprints.
- **A scene builder**: a function in `Scene` that makes a `Node` (sharing its mesh when the parameters repeat), documented in KDoc, in the guide and in the reference, with a check in `qa/…/Api.kt`.
- **A sound**: a function in `Sounds`, built from `Synth` with a fixed seed, and listed in `--export-sounds`.
