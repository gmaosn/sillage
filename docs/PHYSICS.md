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
| `vectorized` | true | large scenes solve their contacts on SIMD lanes when the JDK's vector module is loaded (see below); `lastStepVectorized` tells whether the last step did |

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

## Joints

```kotlin
val lamp = world.add(Sphere(.15f), Vec3(0f, 2f, 0f))
world.addBall(null, lamp, anchor = Vec3(0f, 3f, 0f))                    // hangs from the world, swings freely

val door = world.add(Box(.45f, 1f, .03f), Vec3(.45f, 1f, 0f), density = 600f)
val hinge = world.addHinge(null, door, anchor = Vec3(0f, 1f, 0f), axis = Vec3.Y)
hinge.limits(-1.6f, 0f)                                                  // opens one way, up to 92°
hinge.spring(hertz = .5f)                                                // and closes by itself

val lift = world.addSlider(null, cabin, axis = Vec3.Y)
lift.limits(0f, 6f); lift.motor(speed = 1f, maxForce = 20000f)           // up at 1 m/s, stops at 6 m

val links = world.addChain(from = Vec3(0f, 5f, 0f), to = Vec3(0f, 2f, 0f), links = 12)
```

A joint ties body `b` to body `a`, or to the world when `a` is null. Anchors and axes are given in world coordinates, where the bodies are when joined; at least one of the two bodies must be dynamic.

| Joint | Holds | Options |
|---|---|---|
| `addBall(a, b, anchor)` | the anchors together; the bodies turn freely about it | |
| `addHinge(a, b, anchor, axis)` | the anchors together, turning about one axis only | `limits(lower, upper)` (radians), `motor(speed, maxTorque)`, `spring(hertz, dampingRatio, angle)`; `angle`, `speed` |
| `addSlider(a, b, axis)` | `b` moves along the axis, without turning | `limits(lower, upper)` (m), `motor(speed, maxForce)`, `spring(hertz, dampingRatio, position)`; `translation`, `speed` |
| `addFixed(a, b)` | the two bodies as one | |
| `addDistance(a, anchorA, b, anchorB, length)` | the anchors at a distance, like a rod | `spring(hertz, dampingRatio)`, then `limits(min, max)` |
| `addSpring(…, hertz, dampingRatio, length)` | a spring between the anchors | `limits(min, max)` |
| `addRope(a, anchorA, b, anchorB, length)` | never farther than the length, slack below it | |
| `addChain(from, to, links, radius, start, end)` | capsules joined end to end by ball joints | returns the links |

Joined bodies do not collide with each other unless `collideConnected` is true (distance joints and ropes collide by default). `enabled = false` lets go for a while; `world.removeJoint(j)` for good, and removing a body removes its joints. `force` is what the joint held during the last step, in newtons: a game breaks a rope with `if (rope.force > 2000f) world.removeJoint(rope)`. Joined bodies sleep together and wake together, however long the chain.

The joints are solved with the contacts, in the same substeps and the same soft steps (Box2D v3's solver carried to 3D), joints first, so that contacts have the last word: nothing is pulled into the ground. A spring is soft by nature, at the stiffness asked: its frequency does not depend on the masses. A rigid joint is soft too, at `jointHertz` (60 Hz, at most a quarter of the substep rate, beyond which joints overshoot and explode): a light body holding a heavy load gives a little. A chain of 20 links sags 2.7 cm over its 3 m, 2 mm at the top joint that carries everything; a 2 m beam of four welded boxes sags 5 cm. Eight substeps with `jointHertz = 120` make them four times stiffer, at twice the cost. The softness is for the position errors only: each substep first runs a rigid velocity pass over the joints (springs and motors sit it out), so that a sudden hit, an impulse or a contact is stopped at once at the joint, as by a real pin, instead of leaking through the soft step. A hinge's limits, motor, spring and axis rows turn the bodies about the anchor, each with the linear impulse that keeps the anchors together, so a door slammed against its stop does not pull at its hinges. Measured against physics: a pendulum's period within 0.01 % of the compound pendulum's, a spring's frequency within 1 % from 0.1 to 1000 kg, motors at their speed, hinge limits overshot by less than 0.01 rad under hard knocks; a 40 kg door struck beyond its edge hard enough to spin at 24 rad/s leaves its hinges by 1.2 mm (2.5 mm before these two changes).

## Vehicles

```kotlin
val car = world.add(Box(.9f, .25f, 2f), Vec3(0f, 1.2f, 0f), mass = 1200f)
val vehicle = Vehicle(world, car)                       // front: +Z, top: +Y (the chassis's own axes)
for (x in listOf(-.8f, .8f)) for (z in listOf(-1.3f, 1.3f))
    vehicle.addWheel(Vec3(x, -.15f, z), radius = .35f, travel = .3f, steered = z > 0f)

vehicle.throttle = 1f; vehicle.steering = -.5f          // each frame, from the keys
vehicle.update(dt); world.step(dt)
```

A raycast vehicle, as games make them: each wheel is a sphere swept down from its mount; a spring and a damper hold the chassis up (`suspensionHertz`, `suspensionDamping`), and the tyres drive it (`driveForce`, fading to nothing at `topSpeed`), brake it (`brakeForce`, `handbrake` on the rear wheels) and steer it (`maxSteer`, less at speed). The tyre forces are limited by `grip` × the wheel's load: past it, the vehicle skids and drifts (`Wheel.slip` says how much). `rollInfluence` sets how much the turns lean the chassis. Throttle against the motion brakes first, then reverses. Call `update` before each `step`. The wheels are not bodies: draw them at `Wheel.center` and `Wheel.rotation`; `compression`, `load`, `onGround`, `contact`, `ground` and `spin` tell the rest. A vehicle pushes what it rolls on, and the bodies it drives into.

The suspension and the tyres act as forces spread over the substeps, like gravity, so a parked vehicle stands still and falls asleep. Measured: at rest, each spring is pushed in by g/(2πf)² within 1 mm and carries a quarter of the weight within 1 %; braking from 14 m/s takes 11.2 m where v²/(2 · 0.9 g) gives 11.4; the handbrake holds on a 10° slope; random driving over bumpy ground for 20 s keeps the wheels down 98 % of the time and never overturns.

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

What it stands on carries it: a kinematic platform, a boat, a lift, a turning roundabout, a dynamic raft. Its place on the platform follows the platform, turns included (`platformTurn` tells the game how far, to turn the hero's facing), and `walkVelocity` is its own steps without the platform's. Leaving a platform, by a jump or a step off its edge, it keeps the platform's velocity until it lands, so a jump on a moving boat lands on the same plank.

Call `update` after `world.step`: the character then stands where moving platforms really are. `walk` is the wanted horizontal velocity; a `jumpSpeed` above zero starts a jump at that vertical speed (the game decides when jumping is allowed: on the ground, with a little grace after leaving it). `position` is the point between the feet; `velocity`, `onGround`, `groundNormal`, `groundBody`, `groundMaterial` and `pushing` say what happened; `teleport(feet)` moves it at once. Its kinematic capsule, `body`, is what other bodies bump into.

## Continuous collision

Contacts are found a little ahead (the speculative margin grows with the speed), which stops most fast bodies. Behind it, continuous collision is a safety net: a body that moved more than its core (half its smallest half-size) during a step is swept, its core as a sphere, from where it was to where it is, against the meshes and the static and kinematic bodies; if it went through one, it is put back just before it, its velocity kept, and the next step's contacts stop it. `body.isBullet = true` sweeps it against every body: a bullet stops on a crate instead of crossing it. A 2 cm ball stops on a 2 cm slab, a mesh floor or a moving plank at 3,000 m/s. `continuous = false` turns it off; `continuousHits` counts the bodies it put back.

## Determinism and threads

The same world, stepped the same way, gives the same result to the bit: from one run to the next, from one process to the next, and whatever the number of threads. Large scenes (about 2,000 bodies, 500 pairs or 1,000 contact points and more) spread their work over a pool of worker threads, one pool per process, at most 8 threads (`-Dsillage.threads=N` to choose). Pairs, contacts and the solver are split in fixed chunks that are merged in order, and the solver works by graph colouring: contacts that share no moving body are solved together, the order being set by the scene, not by the threads. `parallel = false` keeps everything on the caller's thread.

This makes replays, lockstep networking and regression tests possible. The engine's tests hold fingerprints of thousands of contacts and rays: a change that alters a result by one bit shows.

The world is not thread-safe: step it and query it from one thread at a time.

## Performance

**On SIMD lanes.** Run with `--add-modules jdk.incubator.vector` (a JDK module still in incubation: the JVM says so once at start) and large scenes solve their contacts 8 at a time with AVX2 (4 with NEON, 16 with AVX-512): the contacts of one colour share no moving body, so a batch of them goes one per lane. Each lane does the scalar solver's very operations in the same order, so the result stays the same to the bit, with or without the module. Without it, nothing changes: the scalar solver runs. Measured on 3,000 awake bodies: 18.3 → 14.3 ms per step on one thread (the solve and relax passes 1.85 times faster), 6.2 → 5.9 ms on 8 threads; 10,000 bodies on 8 threads 18.2 → 17.1 ms. With threads, the other phases and the memory weigh more.

On a laptop's efficiency cores (Intel Core Ultra 7): a pile of 1,000 bodies settling takes 2 to 4 ms per step, 10,000 awake bodies in a heap about 22 ms (8 threads), the demo island's physics, character and camera about 0.15 ms per frame. Sleeping bodies cost almost nothing.

`profiling = true` fills `phaseNanos` with the time spent per phase (bodies, pairs, contacts, preparation, warm start, solve, relax, sleep). The `bench` module measures pyramids, piles, the solver and thread determinism: `./kotlin run -m bench -- pile 3000`.

## How it works

- **Broad phase**: a uniform grid. Each body is entered in the cells its bounds overlap, as one sorted array of 64-bit keys. A pair is reported only in the cell holding the lowest corner of the two bounds' overlap, so no pair comes twice. Large bodies are tested apart.
- **Narrow phase**: one routine per pair of shapes. Spheres and capsules are solved analytically. Boxes use separating axes, then clip the incident face against the reference face (at most four points kept, the deepest and the widest). Capsules against boxes and triangles clip their segment by the face's sides, as Box2D does. Each point has its own normal and a feature id, which matches it with the same point at the next step for warm starting. The code works on plain floats and allocates nothing.
- **Solver**: soft step after Box2D v3 (Erin Catto). Each substep integrates velocities, warm starts, solves contacts as soft springs with a bias, integrates positions, then relaxes without bias; restitution comes last. Friction acts at the centre of each contact patch (two directions plus a twist), not per point, so a crate on a slope slides straight. Contacts within the speculative distance are solved before they touch. A heavy body resting on a light one sees it as at least 1/`supportRatio` of its mass, and the load this hides is given back to the light body's friction: stacks of very different masses hold.
- **Sleep**: islands of touching bodies, after Box2D v3's rule.

## Limits

- Continuous collision sweeps a body's core, not its whole shape: a fast long box can still clip a corner, and a fast spinning one is swept only along its path. Non-bullets are swept only against what does not move.
- Joints are soft (see Joints): a light body holding a heavy load gives a little. No cone or twist limits on ball joints yet (ragdolls swing freely), no gear or pulley joints.
- There are no convex hulls and no moving triangle meshes. A model's dynamic body is its bounding box or a shape you give.
- A sphere swept with a radius above 0.5 m looks at every body, not through the grid.
