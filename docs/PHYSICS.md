# The physics engine

`sillage.physics` is a rigid-body engine written in Kotlin: boxes, spheres, capsules, convex hulls, compound bodies and static triangle meshes, with friction, bounce, rolling resistance, stacking, sleep, water that floats things, rays, sphere sweeps, trigger zones and a character controller. A `Scene` uses it as `scene.physics`, and the [guide](GUIDE.md) shows the one-line forms (`dynamic()`, `solid()`, `kinematic()`). This page is about the engine itself. It needs nothing from the scene or the renderer, so it also serves for a server, a test or a tool.

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
| `ConvexHull(points, maxVertices = 32)` | the smallest convex shape holding the points | rocks, bevelled crates, the collision volumes of models |
| `Compound(parts)` | `Compound.Part(shape, position, rotation, density)` each | one body of several shapes: a table, a boat and its keel |
| `TriangleMesh(triangles, …)` | 9 floats per triangle | static only: terrain, buildings |

A shape is centred on its body's position, which is also its centre of mass. Shapes are immutable and can be shared by many bodies. `shape.scaled(k)` gives the same shape `k` times larger.

A `ConvexHull` is built from points (a `FloatArray` of x, y, z or a list of `Vec3`) by quickhull, in double precision; triangles that lie in one plane are merged into polygons (a cube gives 6 squares, a cylinder of 2 × 16 points its two discs and 16 sides) and points in the middle of an edge are dropped. It keeps at most `maxVertices` vertices (4 to 255): quickhull adds the point farthest from the hull so far at each round, so when it stops early it has kept those that matter, and `simplificationError` says how far outside the hull the farthest point left out lies. Fewer than four points, points at one place, on a line or in a plane (a hull needs a volume), and values that are not finite are refused with an `IllegalArgumentException` that says which. Its volume, centre of mass and inertia are those of the solid polyhedron, exactly; its inertia is diagonal about `principalAxes` (a turned box given by its corners finds its own axes), which the solver handles. The hull is recentred on its centre of mass: `centre` is where that was among the points, so a body at `position + rotation.rotate(hull.centre)` puts the points where they were given. `vertex(i)`, `face(f)`, `faceNormal(f)`, `triangles()` (to draw it) and `contains(point)` read it.

```kotlin
val rock = ConvexHull(points)                                   // points around (2, 0, 1), say
val body = world.add(rock, Vec3(2f, 3f, 1f) + rock.centre)      // the hull's points over where they were
```

A `Compound` makes one body of several parts: spheres, boxes, capsules and hulls, each placed and turned in the compound's frame, with a density relative to the others (a lead keel of density 10 under a wooden hull of density 1). Its mass is the body's density (or the mass given) times the parts' volumes weighted by their densities; its centre of mass and inertia are those of all the parts together, about principal axes as for a hull. It is recentred like a hull (`centre`, `parts` about the centre of mass); a hull part sits on its own centre of mass, so put it at `hull.centre` to keep its points where they were. Parts may not be compounds, and should not overlap much (their volumes and masses add up). Each pair of parts is collided on its own, then the points with one normal are kept to four (the widest), so a table on four thin legs stands on one point per foot.

```kotlin
val table = Compound(listOf(Compound.Part(Box(.6f, .03f, .4f), Vec3(0f, .73f, 0f))) +
    listOf(-1f, 1f).flatMap { x -> listOf(-1f, 1f).map { z -> Compound.Part(Box(.025f, .35f, .025f), Vec3(x * .55f, .35f, z * .35f)) } })
world.add(table, Vec3(0f, 0f, -3f) + table.centre, density = 600f)
```

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

A kinematic body is driven with `moveTo(target, dt, targetRotation)`, which sets the velocities that reach the target in `dt` seconds. Call it before each step: a lift moved this way carries what stands on it, and a door pushes what is in its way. A dynamic body caught between a kinematic body coming at it and what does not move (a ball under a lift going down to the floor) cannot be held by both: it is moved out sideways, across the squeeze and away from the kinematic body's centre, until it is out from under it, kept out of the floor meanwhile (`squeezes` counts the steps this happened).

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

Queries look only at the bodies near them, through two trees of boxes (one for what does not move or is moved by the game, one for the dynamic bodies), each body in a box a little larger than itself. Only the bodies that moved out of their box since the last query are moved in the tree, so a large world of scenery costs nothing to keep, and a sweep of any radius finds its bodies the same way. The answers are those a body-by-body search would give, ties included. In a world of 40,000 still pillars, 60 rays take 0.18 ms and ten sweeps of 1 m spheres 0.02 ms. Queries are not reentrant: a filter must not query the world again.

## Trigger zones

```kotlin
val water = world.addTrigger(Box(10f, 1f, 10f), Vec3(0f, -1f, 0f))
water.onEnter = { body -> body.linearDamping = 2f }
water.onExit = { body -> body.linearDamping = 0f }
```

A trigger notices bodies coming in and going out after each step, without touching them. By default it counts every body that is not static, characters included (`filter` changes that). `inside` lists the bodies within; `enabled = false` makes it forget them; `world.removeTrigger(t)` deletes it.

## Water

```kotlin
val lake = world.addWater(level = 0f)                                   // everywhere below y = 0
val pool = world.addWater(level = .5f, minX = -4f, minZ = -2f, maxX = 4f, maxZ = 2f)
pool.flow = Vec3(1f, 0f, 0f)                                            // a current
lake.onEnter = { e -> println("splash at ${e.point}, ${e.verticalSpeed} m/s") }
val crate = world.add(Box(.5f, .5f, .5f), Vec3(0f, 3f, 0f), density = 500f)   // floats half under
```

A `WaterVolume` holds up the dynamic bodies whose centre is within its horizontal bounds: the weight of the water they displace (`density`, 1000 kg/m³) pushes them up at the centre of what they displace (Archimedes), in every substep like gravity, so that a wide boat with a low centre of mass rights itself and a long plank floats flat. The volume under the surface is exact for spheres (a spherical cap), boxes and hulls (the polyhedron cut by the surface); a capsule is summed over 24 slices across its axis, each an exact disc segment, scaled so that the whole capsule is exact; a compound adds up its parts. Nothing is allocated per step.

The water slows what is in it, implicitly (stable at any setting): `linearDrag` (0.5 /s) and `angularDrag` (0.5 /s) times the fraction under water, a quadratic drag ½ ρ C_d A v² with `dragCoefficient` C_d (1) on the cross-section of a ball of the body's volume (what stops a fast body going in: a ball dropped from 2 m does not jump back out), and `waveDamping` (6 /s) for the waves a body makes bobbing and rolling: on its vertical speed and its turning about horizontal axes, most when half under, nothing once all under. A floating crate stops bobbing within a few seconds; one sliding along the water is not slowed by the waves. `flow` carries bodies along. A body floating free falls asleep only at rest in the water: its weight carried and its centre of buoyancy over its centre of mass (else it slept at the top of a slow bob). Changing `level`, `density`, `flow`, `enabled` or the bounds wakes the bodies around.

`body.submergedFraction` says how much of a body is under water (0 to 1). `world.waterEntries` lists the bodies that touched a water during the last step coming from out of it: a `WaterEntry` with the `body`, the `water`, the `point` on the surface above the centre of what first went under, and the `verticalSpeed` just before (negative: falling) — for splashes, sounds and ripples. `onEnter` and `onExit` are called after the step, like a trigger's.

Measured against Archimedes: a cube of density 500 floats with half its volume under water, of 250 a quarter, of 750 three quarters (it settles on its stable side, a corner up, as a real cube that dense does), within 0.2 %; a slab of density 250 sinks by a quarter of its thickness and rests without a tremor; a ball's waterline within 2 mm of the spherical cap's; a box barge rolls at the period its metacentric height gives (1.23 s) within 0.06 s and comes back upright; a hull boat with a lead keel rights itself from 60° of heel; random shapes, densities and poses float with the fraction under water at their density ratio within 0.1 %; a body dropped into still water touches it at the speed of its fall within 1.5 %. Cost: about 1 µs per floating body and step for a ball, 3 µs for a hull of 24 vertices or a capsule (4 substeps).

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
| `addConeTwist(a, b, anchor, axis, swing, swing2, twistLower, twistUpper, across)` | a ball joint whose axis leans at most `swing` (towards ±`across`) and `swing2` (across that) from a's, an elliptical cone, and twists within limits: ragdolls | `limits(swing, swing2, twistLower, twistUpper)`, `friction` (N·m); `swingAngle`, `twistAngle` |
| `addHinge(a, b, anchor, axis)` | the anchors together, turning about one axis only | `limits(lower, upper)` (radians), `motor(speed, maxTorque)`, `spring(hertz, dampingRatio, angle)`; `angle`, `speed` |
| `addSlider(a, b, axis)` | `b` moves along the axis, without turning | `limits(lower, upper)` (m), `motor(speed, maxForce)`, `spring(hertz, dampingRatio, position)`; `translation`, `speed` |
| `addFixed(a, b)` | the two bodies as one | |
| `addDistance(a, anchorA, b, anchorB, length)` | the anchors at a distance, like a rod | `spring(hertz, dampingRatio)`, then `limits(min, max)` |
| `addSpring(…, hertz, dampingRatio, length)` | a spring between the anchors | `limits(min, max)` |
| `addRope(a, anchorA, b, anchorB, length)` | never farther than the length, slack below it | |
| `addChain(from, to, links, radius, start, end)` | capsules joined end to end by ball joints | returns the links |

A ragdoll is bodies joined by cone joints at the neck, shoulders and hips and hinges at the elbows and knees:

```kotlin
val torso = world.add(Box(.18f, .3f, .1f), Vec3(0f, 1.2f, 0f), mass = 30f)
val arm = world.add(Capsule(.05f, .13f), Vec3(.25f, 1.32f, 0f), mass = 2.5f)
world.addConeTwist(torso, arm, anchor = Vec3(.25f, 1.5f, 0f), axis = Vec3(0f, -1f, 0f),     // the arm hangs down
                   swing = 1.2f, swing2 = .8f, twistLower = -.6f, twistUpper = .6f, across = Vec3.X)
```

The swing is the angle between a's axis and b's (the bone), at most `swing` when b leans towards ±`across` and `swing2` when it leans across that, an ellipse in between; the twist is b's turn about its bone, measured once the swing is undone. Like a hinge's, the limits turn the bodies about the anchor. `friction` resists every turn (a limp body comes to rest). Measured: an arm hit at random for 10 s stays in its cone and its twist range within 0.03 rad, 4 mm from its shoulder; an elliptical cone stops at each of its half-angles within 0.03 rad; ten ragdolls of 11 bodies dropped spinning and struck at random (up to 8 m/s at once on a limb) stay within their limits by 0.07 rad under the hits and 0.01 rad at rest, their joints within 2.3 cm under the hits and 3 mm at rest.

Joined bodies do not collide with each other unless `collideConnected` is true (distance joints and ropes collide by default). `enabled = false` lets go for a while; `world.removeJoint(j)` for good, and removing a body removes its joints. `force` is what the joint held during the last step, in newtons: a game breaks a rope with `if (rope.force > 2000f) world.removeJoint(rope)`. Joined bodies sleep together and wake together, however long the chain.

The joints are solved with the contacts, in the same substeps and the same soft steps (Box2D v3's solver carried to 3D), joints first, so that contacts have the last word: nothing is pulled into the ground. A spring is soft by nature, at the stiffness asked: its frequency does not depend on the masses. A rigid joint is soft too, at `jointHertz` (60 Hz, at most a quarter of the substep rate, beyond which joints overshoot and explode): a light body holding a load gives a little, as a spring would (a 2 m beam of four welded boxes sags 5 cm; eight substeps with `jointHertz = 120` make it four times stiffer, at twice the cost), until the load is heavy (see below). The softness is for the position errors only: each substep first runs a rigid velocity pass over the joints (springs and motors sit it out), so that a sudden hit, an impulse or a contact is stopped at once at the joint, as by a real pin, instead of leaking through the soft step. A hinge's limits, motor, spring and axis rows turn the bodies about the anchor, each with the linear impulse that keeps the anchors together, so a door slammed against its stop does not pull at its hinges. Measured against physics: a pendulum's period within 0.01 % of the compound pendulum's, a spring's frequency within 1 % from 0.1 to 1000 kg, motors at their speed, hinge limits overshot by less than 0.01 rad under hard knocks; a 40 kg door struck beyond its edge hard enough to spin at 24 rad/s leaves its hinges by 1.2 mm (2.5 mm before these two changes).

**Heavy loads.** Soft joints let a heavy load on light bodies give way by a lot: the load creeps from joint to joint and each one stretches (a chain of ten 0.7 kg links holding a 500 kg crate stretched by 61 cm). So when a joint carries more than 20 times the weight of the lighter of its two bodies (at 9.81 m/s², whatever the world's gravity), the whole group of bodies joined to it is solved exactly, at once: the rigid rows of its joints (anchors together, axes aligned, rotations locked, a slider's sides, a rod's length) as one linear system over the tree, eliminated from the leaves in so that it fills in nothing (after D. Baraff's linear-time method, in the joints' own space), in the rigid, biased and relax passes of every substep, the rows taken where the bodies are. Limits, motors and springs stay with the iterative passes. A light link held taut between heavy loads would ring with waves faster than a substep, so the group's bodies see, in that solve, the geometric stiffness of what they carry added to their inertia (h² × force × lever, after Andrews, Teichmann and Kry, 2017). A group stays solved this way while a kinematic body touches it (a character on a bridge) or while one of its joints carried more than 10 times that weight within the last second; then it goes back to the soft joints. Groups with a cone (a ragdoll), a joint at or near one of its limits, two joined bodies that touch each other or a loop of joints stay iterative as a whole, and a group a kinematic body touches is not stiffened (its contacts have the last word).

Measured: the chain of ten links holding 50 to 2,000 kg stretches by less than 0.1 mm, and swung hard keeps its links within 0.5 mm; a chain of 20 links released from the horizontal hangs within 0.1 mm of its length (2.7 cm longer with soft joints). A bridge of ten 5 kg planks on hinges, hung with a little slack between two posts, carries a 70 kg character in its middle with its hinges within 0.5 mm (8.8 mm with soft joints), its planks where an inextensible bridge's would be (the funicular polygon, within 2 cm). Stretched taut between its posts, it sags 0.5 cm alone (25 cm with soft joints) and 8 cm under the character (48 cm), 3 cm with 8 substeps. The exact solve costs: that bridge steps in 0.2 to 0.36 ms instead of 0.07 to 0.15. Determinism holds: the solve is single-threaded, in a fixed order, in double precision.

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

Contacts are found a little ahead (the speculative margin grows with the speed), which stops most fast bodies. Behind it, continuous collision is a safety net: a body that moved more than its core (half its smallest half-size) during a step has its whole shape (sphere, capsule, box, hull, compound) swept at its final orientation, from where it was to where it is, in its motion relative to each obstacle: the meshes' triangles, from their front, and the static and kinematic bodies. The time of impact comes from GJK distances and conservative advancement along the line (the distance along a straight motion is convex, so each step stays before the impact). If the motion went on into the obstacle until the body's core met it, the body is put back just before the first touch, its velocity kept, and the next step's contacts stop it; landing a little into the ground is left to the contacts. `body.isBullet = true` sweeps it against every body: a bullet stops on a crate instead of crossing it. A 22 cm ball, a 50 cm crate, a plank, a capsule, a rock or a table thrown at 20 to 1,500 m/s never crosses a wall 5 cm thick, a box or a closed mesh; a 2 cm ball stops on a 2 cm slab, a mesh floor or a moving plank at 3,000 m/s.

A wall of triangles thinner than a body holds too: a body in front of a face, near it, is not pushed by a face behind it that looks the other way (a ball within two radii behind a triangle is pushed out through it, for one pressed under a floor; on a thin wall, that pushed it through). `continuous = false` turns it off; `continuousHits` counts the bodies it put back.

## Determinism and threads

The same world, stepped the same way, gives the same result to the bit: from one run to the next, from one process to the next, and whatever the number of threads. Large scenes (about 2,000 bodies, 500 pairs or 1,000 contact points and more) spread their work over a pool of worker threads, one pool per process, at most 8 threads (`-Dsillage.threads=N` to choose). Pairs, contacts and the solver are split in fixed chunks that are merged in order, and the solver works by graph colouring: contacts that share no moving body are solved together, the order being set by the scene, not by the threads. `parallel = false` keeps everything on the caller's thread.

This makes replays, lockstep networking and regression tests possible. The engine's tests hold fingerprints of thousands of contacts and rays: a change that alters a result by one bit shows.

The world is not thread-safe: step it and query it from one thread at a time.

## Performance

**On SIMD lanes.** Run with `--add-modules jdk.incubator.vector` (a JDK module still in incubation: the JVM says so once at start) and large scenes solve their contacts 8 at a time with AVX2 (4 with NEON, 16 with AVX-512): the contacts of one colour share no moving body, so a batch of them goes one per lane. Each lane does the scalar solver's very operations in the same order, so the result stays the same to the bit, with or without the module. Without it, nothing changes: the scalar solver runs. Measured on 3,000 awake bodies: 18.3 → 14.3 ms per step on one thread (the solve and relax passes 1.85 times faster), 6.2 → 5.9 ms on 8 threads; 10,000 bodies on 8 threads 18.2 → 17.1 ms. With threads, the other phases and the memory weigh more.

On a laptop's efficiency cores (Intel Core Ultra 7): a pile of 1,000 bodies settling takes 2 to 4 ms per step, 10,000 awake bodies in a heap about 22 ms (8 threads), the demo island's physics, character and camera about 0.15 ms per frame. Sleeping bodies cost almost nothing.

**Hulls.** The narrow phase per pair (bodies and ground), measured in a pit of 600 awake bodies: boxes 0.2 µs, bevelled crates of 24 vertices 1.1 µs, rocks of 16 vertices 0.8 µs, of 32 vertices 1.9 µs; a step of those 600 bodies takes 3.6, 5.5, 5.8 and 10.5 ms. Piles of hulls settle and sleep as piles of boxes do.

`profiling = true` fills `phaseNanos` with the time spent per phase (bodies, pairs, contacts, preparation, warm start, solve, relax, sleep). The `bench` module measures pyramids, piles, the solver and thread determinism: `./kotlin run -m bench -- pile 3000`.

## How it works

- **Broad phase**: a uniform grid for the bodies that are not static. Each is entered in the cells its bounds overlap, as one sorted array of 64-bit keys. A pair is reported only in the cell holding the lowest corner of the two bounds' overlap, so no pair comes twice. The static bodies stay in their tree of boxes, where the awake dynamic bodies find theirs; those pairs are put in the very order the cells would give. Large bodies are tested apart. A world of 40,000 still pillars finds its pairs in 0.6 ms per step instead of 58.
- **Narrow phase**: one routine per pair of shapes. Spheres and capsules are solved analytically. Convex hulls (and a box against a hull, the box as a polytope) use the separating axis test over the faces of both and the edge pairs that make a face of their Minkowski difference (D. Gregorius's Gauss map test, with edges that face away from the other shape skipped by a bound on their normal cones), with relative tolerances so that small and large hulls choose alike; then the incident face, around the vertex deepest along the reference normal, is clipped by the reference face's sides, its other vertices within 2 cm of the ground are added (a rock with a faceted bottom stands on all of them), and four points are kept: of the touching ones, the one farthest from their centre, the farthest from it, and the two that span the widest area — the same four each step, whatever the rounding, so that hulls stack as still as boxes. Spheres and capsules against a hull use its closest features (the face it is farthest in front of, else the nearest edge), triangles against a hull the triangle's front, the hull's faces and the active triangle edges. Compounds collide part by part. Boxes use separating axes, then clip the incident face against the reference face (at most four points kept, the deepest and the widest). Capsules against boxes and triangles clip their segment by the face's sides, as Box2D does. Each point has its own normal and a feature id, which matches it with the same point at the next step for warm starting. The code works on plain floats and allocates nothing.
- **Solver**: soft step after Box2D v3 (Erin Catto). Each substep integrates velocities, warm starts, solves contacts as soft springs with a bias, integrates positions, then relaxes without bias; restitution comes last. Friction acts at the centre of each contact patch (two directions plus a twist), not per point, so a crate on a slope slides straight. Contacts within the speculative distance are solved before they touch. A heavy body resting on a light one sees it as at least 1/`supportRatio` of its mass, and the load this hides is given back to the light body's friction: stacks of very different masses hold.
- **Sleep**: islands of touching bodies, after Box2D v3's rule.

## Limits

- Continuous collision sweeps the whole shape in a straight line at the body's final orientation: a long body spinning fast (hundreds of radians per second) can swing part of itself through a thin wall within a step. Non-bullets are swept only against what does not move.
- Joints are soft below a heavy load (see Joints), and a limb crushed between a heavy body and the ground parts from its joint for a moment (the contacts have the last word). A bridge stretched taut is nearly a mechanism: a character walking on it makes it ring (its hinges within about 1 cm at 4 substeps); give a bridge a little slack. Ragdolls (cones and twists) are never solved as trees. No gear or pulley joints.
- There are no moving triangle meshes. A model's dynamic body is its bounding box or a shape you give (a hull of its points, a compound).
- Hull against hull costs about the product of their edge counts, after pruning: keep collision hulls to 16–32 vertices. A body balanced on a single vertex of a hull spins on it without friction (a point has no grip about the normal).
- A tall stack of compounds whose parts touch by small faces settles more slowly than one of boxes.
