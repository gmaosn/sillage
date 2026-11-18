# The physics engine

`sillage.physics` is a rigid-body engine written in Kotlin: boxes, spheres, capsules and static triangle meshes, with friction, bounce, rolling resistance, stacking, sleep, rays, sphere sweeps, trigger zones and a character controller. A `Scene` uses it as `scene.physics`, and the [guide](GUIDE.md) shows the one-line forms (`dynamic()`, `solid()`, `kinematic()`). This page is about the engine itself. It needs nothing from the scene or the renderer, so it also serves for a server, a test or a tool.

## A world in ten lines

```kotlin
import sillage.math.Vec3
import sillage.physics.*

fun main() {
    val world = PhysicsWorld()                                   // gravity: 9.81 m/s² downwards
    val floor = floatArrayOf(-20f, 0f, -20f, -20f, 0f, 20f, 20f, 0f, 20f,
                             -20f, 0f, -20f, 20f, 0f, 20f, 20f, 0f, -20f)
    world.addMesh(TriangleMesh(floor, Material.GRASS))
    val crate = world.add(Box(.5f, .5f, .5f), Vec3(0f, 3f, 0f), density = 400f)
    val ball = world.add(Sphere(.3f), Vec3(.2f, 6f, 0f), density = 100f, restitution = .6f)
    repeat(180) { world.step(1f / 60f) }
    println("crate at ${crate.position}, ball at ${ball.position}, asleep: ${crate.isSleeping}")
}
```

Units are metres, seconds and kilograms; Y points up. Positions and velocities live in arrays inside the world, and a `Body` is only a handle on them. Once the world has warmed up, a step creates almost no objects: collision tests and queries work on plain numbers.

## Shapes

| Shape | Size | Notes |
|---|---|---|
| `Sphere(radius)` | | |
| `Box(halfX, halfY, halfZ)` | **half** extents | `Box(.5f, .5f, .5f)` is a 1 m cube |
| `Capsule(radius, halfHeight)` | segment from −halfHeight to +halfHeight on local Y, swept by the radius | total height 2·(halfHeight + radius) |
| `TriangleMesh(triangles, …)` | 9 floats per triangle | static only: terrain, buildings |

A shape is centred on its body's position, which is also its centre of mass. Shapes are immutable and can be shared by many bodies.

A `TriangleMesh` is a triangle soup with a bounding volume hierarchy. Triangles are **one-sided**: the front is the side from which the corners turn counter-clockwise, the normal being (b − a) × (c − a). Bodies are pushed out towards the front, and a body behind a triangle, inside a closed mesh, is not thrown through it. Degenerate triangles are dropped. Each triangle may have its own `Material`:

```kotlin
TriangleMesh(triangles, materialIds, palette = listOf(Material.GRASS, Material.ROAD))   // one index per triangle
TriangleMesh(triangles, Material.STONE)                                                // one material for all
```

Edges between flat or concave neighbours are marked inactive, so a ball rolls and a crate slides across a triangulated floor without catching on the seams.

## Bodies

```kotlin
val body = world.add(shape, position, rotation = Quat.IDENTITY, type = BodyType.DYNAMIC,
                     density = 1000f, mass = null, friction = .6f, restitution = 0f, rollingResistance = null)
```

| Type | Moves | Mass |
|---|---|---|
| `DYNAMIC` | by gravity, contacts, forces and impulses | from `mass`, or `density` × volume (default 1000 kg/m³, water) |
| `STATIC` | never | infinite |
| `KINEMATIC` | by its velocity, set by the game; pushes dynamic bodies and is never pushed | infinite |

`Body` gives `position`, `rotation`, `velocity`, `angularVelocity` (read and write), `mass`, `friction`, `restitution`, `linearDamping`, `angularDamping`, `gravityScale`, `rollingResistance`, `isSleeping`, `isRemoved`, `shape`, `type` and `tag` (yours: the engine never reads it). `applyImpulse(impulse, point)` changes momentum at once; `world.applyForce(body, force)` acts during the next step. Setting a position, rotation or velocity wakes the body.

A kinematic body is driven with `moveTo(target, dt, targetRotation)`, which sets the velocities that reach the target in `dt` seconds. Call it before each step: a lift moved this way carries what stands on it, and a door pushes what is in its way.

`world.remove(body)` takes a body out. Bodies that rested on it wake up. Its id may be given to the next body added, so drop the handle. `body(id)` makes a handle from an id, `bodyCount` counts ids ever used, `liveBodyCount` the bodies in the world.

## Surfaces

When two bodies touch, the friction is √(μ₁·μ₂), the restitution is the larger of the two (a kinematic body imposes its own: a character is soft, a paddle may be bouncy), and the rolling resistance is the larger of the two. A static mesh brings its `Material`:

| Material | Friction | Rolling resistance | Bounce |
|---|---|---|---|
| `ROAD` | 0.8 | 0.015 | ×1 |
| `GRASS` | 0.7 | 0.12 | ×0.6 |
| `SAND` | 0.6 | 0.3 | ×0.3 |
| `WOOD` | 0.45 | 0.02 | ×0.9 |
| `STONE` | 0.7 | 0.02 | ×1 |
| `ICE` | 0.03 | 0.005 | ×1 |
| `DEFAULT` | 0.6 | 0 | ×1 |

`Material.named("Herbe.001")` picks a preset from a material name, in English or French, as a Blender artist would type it; this is how glTF models get their surfaces. `bounce` scales the body's restitution: grass swallows half a ball's bounce. Rolling resistance stops balls and capsules: on sand a ball soon stops, on a road it rolls far.

## Stepping

`world.step(dt)` advances by `dt` seconds. A step is split in `substeps` (4); a fixed 1/60 s is the usual choice, and any `dt` between 1/240 and 1/30 works. The scene steps with the frame time, clamped to that range.

| Setting | Default | What it does |
|---|---|---|
| `gravity` | (0, −9.81, 0) | m/s² |
| `substeps` | 4 | more is stiffer and slower |
| `contactHertz`, `contactDampingRatio` | 30, 10 | stiffness of the soft contacts that push overlapping bodies apart |
| `contactSpeed` | 3 | fastest speed at which an overlap is undone (m/s) |
| `restitutionThreshold` | 1 | slower impacts do not bounce (m/s) |
| `speculativeDistance` | 0.02 | contacts are found this far before they touch (m) |
| `supportRatio` | 2 | a body resting on a lighter one treats it as at least half its mass |
| `sleepLinearSpeed`, `timeToSleep` | 0.05, 0.5 | a still group sleeps after this long below this speed |
| `gyroscopic` | true | spinning bodies keep their angular momentum |
| `parallel` | true | large scenes use the worker threads |

Bodies resting together fall asleep as a group (an island). A sleeping body costs almost nothing. It wakes when something touches it, when you set its state, or when a body under it is removed.

## Queries

```kotlin
val hit = world.raycast(origin, direction, maxDistance = 50f)                     // a ray
val sweep = world.raycast(origin, direction, 10f, radius = .3f)                   // a sphere swept along the ray
val ground = world.groundBelow(x = 2f, z = -4f)                                    // straight down from high above
val near = world.overlaps(Sphere(1f), Vec3(0f, 1f, 0f), margin = .1f)              // what a shape would touch
```

A `RayHit` gives the `distance` travelled, the `point` touched (on the surface), the surface `normal`, and the `body` hit or, for a static mesh, its `material`. A ray starting inside a shape stops at distance 0. A sweep is exact against boxes: the box grown by the radius, with rounded edges and corners. `filter = { it.id != me.id }` skips bodies; `meshes = false` ignores static meshes. Triangles are seen from the front only.

`overlaps` lists the contacts a shape would have if it stood there, without adding it: each `Overlap` has the body (null for a mesh) or the mesh material, a point, a normal pointing from the obstacle to the shape, and a separation (negative is an overlap). This is how a game checks a spawn point or a door's way.

Queries look only at the bodies near them, through a grid that is rebuilt when bodies have moved. They are not reentrant: a filter must not query the world again.

## Trigger zones

```kotlin
val water = world.addTrigger(Box(10f, 1f, 10f), Vec3(0f, -1f, 0f))
water.onEnter = { body -> body.linearDamping = 2f }
water.onExit = { body -> body.linearDamping = 0f }
```

A trigger notices bodies coming in and going out after each step, without touching them. By default it counts every body that is not static, characters included (`filter` changes that). `inside` lists the bodies within; `enabled = false` makes it forget them; `world.removeTrigger(t)` deletes it.

## Contacts

`world.contacts()` returns the contact points of the last step: the two bodies (`a` is null for a static mesh), the point, the normal from `a` to `b`, the separation, and the normal impulse that the solver applied. Use it for debug drawing, for gameplay (who stands on what, how hard something hit) and for checks. `pairCount` and `contactCount` are cheaper counts.

## The character controller

```kotlin
val hero = Character(world, feet = Vec3(0f, 0f, 5f))      // radius 0.28 m, height 1.8 m, 70 kg
repeat(120) {
    world.step(1f / 60f)
    hero.update(walk = Vec3(0f, 0f, -3f), jumpSpeed = 0f, dt = 1f / 60f)   // walks towards −Z at 3 m/s
}
```

`Character` is a capsule moved by the game, not by forces, in the spirit of Jolt's virtual character. It slides along walls, climbs steps up to `stepHeight` (0.35 m), walks slopes up to `maxSlope` (50°) and slides down steeper ones, keeps to the ground going downhill (`snapDistance`), stands on moving bodies and weighs on them, and pushes movable bodies with at most `pushForce` (400 N), so a heavy crate slows it down and a ball does not. It has its own `gravity` (−22 m/s², which makes jumps snappy).

Call `update` after `world.step`: the character then stands where moving platforms really are. `walk` is the wanted horizontal velocity; a `jumpSpeed` above zero starts a jump at that vertical speed (the game decides when jumping is allowed: on the ground, with a little grace after leaving it). `position` is the point between the feet; `velocity`, `onGround`, `groundNormal`, `groundBody`, `groundMaterial` and `pushing` say what happened; `teleport(feet)` moves it at once. Its kinematic capsule, `body`, is what other bodies bump into.

## Determinism and threads

The same world, stepped the same way, gives the same result to the bit: from one run to the next, from one process to the next, and whatever the number of threads. Large scenes (about 2,000 bodies, 500 pairs or 1,000 contact points and more) spread their work over a pool of worker threads, one pool per process, at most 8 threads (`-Dsillage.threads=N` to choose). Pairs, contacts and the solver are split in fixed chunks that are merged in order, and the solver works by graph colouring: contacts that share no moving body are solved together, the order being set by the scene, not by the threads. `parallel = false` keeps everything on the caller's thread.

This makes replays, lockstep networking and regression tests possible. The engine's tests hold fingerprints of thousands of contacts and rays: a change that alters a result by one bit shows.

The world is not thread-safe: step it and query it from one thread at a time.

## Performance

On a laptop's efficiency cores (Intel Core Ultra 7): a pile of 1,000 bodies settling takes 2 to 4 ms per step, 10,000 awake bodies in a heap about 22 ms (8 threads), the demo island's physics, character and camera about 0.15 ms per frame. Sleeping bodies cost almost nothing.

`profiling = true` fills `phaseNanos` with the time spent per phase (bodies, pairs, contacts, preparation, warm start, solve, relax, sleep). The `bench` module measures pyramids, piles, the solver and thread determinism: `./kotlin run -m bench -- pile 3000`.

## How it works

- **Broad phase**: a uniform grid. Each body is entered in the cells its bounds overlap, as one sorted array of 64-bit keys. A pair is reported only in the cell holding the lowest corner of the two bounds' overlap, so no pair comes twice. Large bodies are tested apart.
- **Narrow phase**: one routine per pair of shapes. Spheres and capsules are solved analytically. Boxes use separating axes, then clip the incident face against the reference face (at most four points kept, the deepest and the widest). Capsules against boxes and triangles clip their segment by the face's sides, as Box2D does. Each point has its own normal and a feature id, which matches it with the same point at the next step for warm starting. The code works on plain floats and allocates nothing.
- **Solver**: soft step after Box2D v3 (Erin Catto). Each substep integrates velocities, warm starts, solves contacts as soft springs with a bias, integrates positions, then relaxes without bias; restitution comes last. Friction acts at the centre of each contact patch (two directions plus a twist), not per point, so a crate on a slope slides straight. Contacts within the speculative distance are solved before they touch. A heavy body resting on a light one sees it as at least 1/`supportRatio` of its mass, and the load this hides is given back to the light body's friction: stacks of very different masses hold.
- **Sleep**: islands of touching bodies, after Box2D v3's rule.

## Limits

- There is no continuous collision: a very fast small body can pass through thin geometry. Measured: a 10 cm ball goes through neither a 10 cm slab nor a mesh floor up to 320 m/s.
- There are no joints, no convex hulls and no moving triangle meshes. A model's dynamic body is its bounding box or a shape you give.
- A sphere swept with a radius above 0.5 m looks at every body, not through the grid.
