package sillage

import sillage.audio.Audio
import sillage.audio.Sound
import sillage.audio.Voice
import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.Body
import sillage.physics.BodyType
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.Character
import sillage.physics.Material
import sillage.physics.PhysicsWorld
import sillage.physics.Shape
import sillage.physics.Sphere
import sillage.physics.Trigger
import sillage.physics.TriangleMesh
import sillage.physics.addTrigger
import sillage.physics.raycast
import kotlin.math.*

@DslMarker annotation class SillageDsl

/**
 * A 3D world ready to play: a sky, a sun, a camera and physics are already there. Build it inside
 * [game] — `box()`, `sphere()`, `model("…")`, `player("…")` — and give it life with [onUpdate],
 * [after], [every], zones and pickups. Units are metres, seconds and kilograms; Y points up.
 *
 * A scene can also run without a window (tests, a game server): `Scene().apply { … }` then [step].
 */
@SillageDsl
class Scene {
    /** The rigid-body world under the scene; use it directly for anything the scene does not offer. */
    val physics = PhysicsWorld()
    /** Sun, shade, sky and haze. Change them while building the scene. */
    val lighting = Lighting()
    val camera = Camera(this)
    val hud = Hud()
    val input = Input()
    /** The sound output (null when muted or without sound). [game] sets it; set it yourself to hear or record a scene run with [step]. */
    var audio: Audio? = null
        set(v) { field = v; sounds.start(v) }
    /** Seconds since the scene started. */
    var time = 0f
        private set
    /** Frames drawn per second, measured over the last half second. */
    var fps = 60
        internal set
    /** A player falling below this height is put back at its spawn point. */
    var fallLimit = -10f

    private val all = ArrayList<Node>()
    /** The nodes of the scene, in the order they were made. */
    val nodes: List<Node> get() = all.filter { !it.isShadow && it !== sky }
    private val playerList = ArrayList<Player>()
    val players: List<Player> get() = playerList
    /** The first player, if any (the one the camera follows by default). */
    val player: Player? get() = playerList.firstOrNull()

    internal val sounds = SceneSounds(this)
    internal val surfaces = HashMap<Int, String>()          // body id → footstep surface
    internal val cameraIgnores = HashSet<Int>()              // body ids the camera passes through
    private val linked = ArrayList<Node>()                   // dynamic nodes, following their bodies
    private val kinematicNodes = ArrayList<Node>()
    private class Follower(val node: Node, val part: String, val offset: Vec3, val body: Body)
    private val followers = ArrayList<Follower>()
    private class Shadow(val disc: Node, val of: Node, val radius: Float)
    private val shadows = ArrayList<Shadow>()
    private val shadowMesh by lazy { shadowDisc() }
    private val lightShadowMesh by lazy { shadowDisc(.7f) }
    private val models = HashMap<String, Model>()
    private val flat = HashMap<String, Mesh>()
    private val updates = ArrayList<Scene.(Float) -> Unit>()
    private val restarts = ArrayList<() -> Unit>()
    private val keyActions = ArrayList<Pair<Int, () -> Unit>>()
    private class Timer(var at: Float, val every: Float, val spread: Float, val action: () -> Unit)
    private val timers = ArrayList<Timer>()
    private val timerCopy = ArrayList<Timer>()
    private val nodeCopy = ArrayList<Node>()
    private val pickupList = ArrayList<Pickup>()
    internal var next: (Scene.() -> Unit)? = null
    internal var forcedPlayerClip: String? = null

    /** The sky dome (drawn behind everything, following the camera). */
    internal val sky = Node(this, Mesh.EMPTY).also { it.isSky = true; it.castsShadow = false; all.add(it) }

    // ------------------------------------------------------------------ making things

    private fun add(node: Node, at: Vec3): Node { node.position = at; all.add(node); return node }

    // Primitives of the same size and colour share one mesh: the renderer then draws all their copies
    // in a single call (a thousand crates, one draw). Replace a node's mesh rather than editing a shared one.
    private data class Shared(val kind: Int, val a: Float, val b: Float, val c: Float, val d: Float, val color: Color)
    private val shared = HashMap<Shared, Mesh>()
    private fun shared(kind: Int, a: Float, b: Float, c: Float, d: Float, color: Color, make: () -> Mesh): Mesh =
        shared.getOrPut(Shared(kind, a, b, c, d, color), make)

    /** A box centred on [at]; [round] rounds its edges (metres). */
    fun box(width: Number = 1, height: Number = width, depth: Number = width, color: Color = Color.WOOD, round: Number = 0, at: Vec3 = Vec3.ZERO): Node {
        val w = width.toFloat(); val h = height.toFloat(); val d = depth.toFloat()
        val r = round.toFloat()
        return add(Node(this, shared(0, w, h, d, r, color) { Mesh.box(w, h, d, color, r) }).also { it.shape = Box(w / 2, h / 2, d / 2) }, at)
    }

    /** A ball centred on [at]. */
    fun sphere(radius: Number = .5, color: Color = Color.RED, at: Vec3 = Vec3.ZERO): Node {
        val r = radius.toFloat()
        return add(Node(this, shared(1, r, 0f, 0f, 0f, color) { Mesh.sphere(r, color) }).also { it.shape = Sphere(r) }, at)
    }

    /** A capsule standing on Y, [height] from tip to tip, centred on [at]. */
    fun capsule(radius: Number = .3, height: Number = 1.6, color: Color = Color.ORANGE, at: Vec3 = Vec3.ZERO): Node {
        val r = radius.toFloat(); val h = max(height.toFloat(), 2 * r)
        return add(Node(this, shared(2, r, h, 0f, 0f, color) { Mesh.capsule(r, h, color) }).also { it.shape = Capsule(r, h / 2 - r) }, at)
    }

    /** A cylinder standing on Y, centred on [at] (it collides as a box). */
    fun cylinder(radius: Number = .5, height: Number = 1, color: Color = Color.GREY, at: Vec3 = Vec3.ZERO): Node {
        val r = radius.toFloat(); val h = height.toFloat()
        return add(Node(this, shared(3, r, h, 0f, 0f, color) { Mesh.cylinder(r, h, color) }).also { it.shape = Box(r * .9f, h / 2, r * .9f) }, at)
    }

    /** A cone standing on Y, centred on [at]. */
    fun cone(radius: Number = .5, height: Number = 1, color: Color = Color.YELLOW, at: Vec3 = Vec3.ZERO): Node {
        val r = radius.toFloat(); val h = height.toFloat()
        return add(Node(this, shared(4, r, h, 0f, 0f, color) { Mesh.cone(r, h, color) }).also { it.shape = Box(r * .6f, h / 2, r * .6f) }, at)
    }

    /** A flat rectangle facing up. */
    fun plane(width: Number = 10, depth: Number = width, color: Color = Color.GRASS, at: Vec3 = Vec3.ZERO): Node {
        val w = width.toFloat(); val d = depth.toFloat()
        return add(Node(this, shared(5, w, d, 0f, 0f, color) { Mesh.plane(w, d, color) }).also { it.shape = Box(w / 2, .05f, d / 2) }, at)
    }

    /** A node drawing [mesh] (made with [MeshBuilder] or the shapes of [Mesh]). */
    fun mesh(mesh: Mesh, at: Vec3 = Vec3.ZERO): Node = add(Node(this, mesh), at)

    /**
     * A GLB model (a file path, or a path in the resources). A model with animations keeps its parts
     * and plays clips with [Node.play]; a model without is drawn as one mesh.
     */
    fun model(path: String, at: Vec3 = Vec3.ZERO, scale: Number = 1): Node {
        val m = models.getOrPut(path) { Glb.loadModel(path) }
        val node = Node(this, if (m.clips.isEmpty()) flat.getOrPut(path) { m.flatten().also { require(it.triangleCount > 0) { "GLB without triangles: $path" } } } else Mesh.EMPTY)
        node.model = m
        if (m.clips.isNotEmpty()) { node.parts = m.parts(); node.animator = Animator(m, node.parts) }
        node.scale = scale.toFloat()
        return add(node, at)
    }

    /** A flat solid floor of [size] metres (its edge hidden in the fog by default), its top at [at]; grass by default: the surface sets friction and footsteps. */
    fun ground(size: Number = 400, color: Color = Color.GRASS, surface: Material = Material.GRASS, at: Vec3 = Vec3.ZERO): Node {
        val s = size.toFloat() / 2
        val node = add(Node(this, Mesh.plane(2 * s, 2 * s, color)), at)
        physics.addMesh(TriangleMesh(floatArrayOf(-s, 0f, -s, -s, 0f, s, s, 0f, s, -s, 0f, -s, s, 0f, s, s, 0f, -s).also {
            for (i in it.indices step 3) { it[i] += at.x; it[i + 1] += at.y; it[i + 2] += at.z } }, surface))
        return node
    }

    /**
     * A sea or lake at height [level], [size] metres across. It is not solid: what falls in sinks. Along
     * its shores (wherever something solid rises out of it) the water turns to a lagoon of [shallows]
     * colour reaching [reach] metres out, with foam against the beach and surf rolling in; wave crests
     * drift on the open water. The shores are found when the scene is built, so add the land first or not.
     */
    fun water(level: Number = 0, color: Color = Color.WATER, size: Number = 300, shallows: Color? = null, reach: Number = 6): Node {
        val node = Node(this, Mesh.EMPTY)
        node.water = WaterLook(color, shallows ?: color.mix(Color.hex(0xB8F2E6), .6f), reach.toFloat())
        node.castsShadow = false
        pendingWater.add(Triple(node, level.toFloat(), size.toFloat()))
        return add(node, Vec3.ZERO)
    }
    // Water is shaped by the shores around it: its mesh is made once the scene is built, before the first frame.
    private val pendingWater = ArrayList<Triple<Node, Float, Float>>()

    /**
     * A player: walks, runs and jumps with the keyboard (WASD, arrows, space, shift to walk), the camera
     * follows it, its model's clips play by themselves (idle, walk, run, jump, fall…). Without a model it
     * is a plain capsule. [setup] adjusts it: speeds, keys, clip names, `view = CameraMode.FIRST_PERSON`…
     */
    fun player(model: String? = null, at: Vec3 = Vec3.ZERO, radius: Number = .28, height: Number = 1.8, setup: Player.() -> Unit = {}): Player {
        val c = Character(physics, at, radius.toFloat(), height.toFloat())
        val p = Player(this, c, at)
        if (model != null) {
            val m = models.getOrPut(model) { Glb.loadModel(model) }
            p.model = m
            if (m.clips.isEmpty()) p.mesh = flat.getOrPut(model) { m.flatten() }
            else { p.parts = m.parts(); p.animator = Animator(m, p.parts) }
        } else p.mesh = Mesh.capsule(radius.toFloat(), height.toFloat(), Color.ORANGE).transformed(Mat4.translation(Vec3(0f, height.toFloat() / 2, 0f)))
        all.add(p); playerList.add(p)
        addShadow(p, radius.toFloat() * 1.5f)
        p.setup()
        return p
    }

    // ------------------------------------------------------------------ physics behind the nodes

    /** An invisible solid [shape] at [at]: a wall, a building's volume, a tree trunk ([blocksCamera] = false). */
    fun collider(shape: Shape, at: Vec3, rotation: Quat = Quat.IDENTITY, blocksCamera: Boolean = true, surface: String = "stone"): Body {
        val b = physics.add(shape, at, rotation, type = BodyType.STATIC)
        surfaces[b.id] = surface
        if (!blocksCamera) cameraIgnores.add(b.id)
        return b
    }

    /** Height of the solid ground (static meshes) under (x, z), 0 where there is none. */
    fun groundHeight(x: Number, z: Number): Float =
        physics.raycast(Vec3(x.toFloat(), 1000f, z.toFloat()), Vec3(0f, -1f, 0f), 2000f, filter = { false })?.point?.y ?: 0f

    /** The body shape of [node]: [given] as is (in metres), else its primitive or its bounding box, both scaled with the node. */
    private fun shapeOf(node: Node, given: Shape?): Shape {
        if (given != null) return given
        val s = node.shape ?: node.mesh.let { m ->
            val (lo, hi) = if (m.triangleCount > 0) m.bounds else node.model?.flatten()?.bounds ?: (Vec3(-.5f, -.5f, -.5f) to Vec3(.5f, .5f, .5f))
            Box((hi.x - lo.x) / 2, (hi.y - lo.y) / 2, (hi.z - lo.z) / 2)
        }
        val k = node.scale
        return if (k == 1f) s else when (s) { is Sphere -> Sphere(s.radius * k); is Box -> Box(s.halfX * k, s.halfY * k, s.halfZ * k); is Capsule -> Capsule(s.radius * k, s.halfHeight * k) }
    }

    /** Offset from a model's origin to the centre of its bounding box (primitives are already centred). */
    private fun centreOf(node: Node, given: Shape?): Vec3 {
        if (given != null || node.shape != null) return Vec3.ZERO
        val m = if (node.mesh.triangleCount > 0) node.mesh else node.model?.flatten() ?: return Vec3.ZERO
        val (lo, hi) = m.bounds
        return (lo + hi) / 2f * node.scale
    }

    internal fun makeDynamic(node: Node, shape: Shape?, mass: Float?, density: Float, bounce: Float, friction: Float, surface: String, impact: Impact,
                             rolling: Float?, linearDamping: Float, angularDamping: Float) {
        require(node.body == null) { "This node already has a body" }
        val s = shapeOf(node, shape)
        val c = centreOf(node, shape)
        val b = physics.add(s, node.position + node.rotation.rotate(c), node.rotation, density = density, mass = mass, friction = friction, restitution = bounce,
            rollingResistance = rolling)
        b.linearDamping = linearDamping; b.angularDamping = angularDamping
        b.tag = node; node.body = b; node.bodyOffset = c
        surfaces[b.id] = surface
        linked.add(node)
        sounds.track(b, if (impact == Impact.AUTO) (if (s is Sphere) Impact.RUBBER else Impact.WOOD) else impact)
        if (shadows.none { it.of === node }) addShadow(node, null)
    }

    internal fun makeKinematic(node: Node) {
        require(node.body == null) { "This node already has a body" }
        val b = physics.add(shapeOf(node, null), node.position + node.rotation.rotate(centreOf(node, null)), node.rotation, type = BodyType.KINEMATIC)
        b.tag = node; node.body = b; node.bodyOffset = centreOf(node, null)
        kinematicNodes.add(node)
    }

    internal fun makeSolid(node: Node, materials: List<String>, blocksCamera: Boolean, surface: Material?) {
        val m = node.model
        if (m == null && node.shape == null && node.mesh.triangleCount > 0) {
            // A mesh made by hand: solid by its own triangles, like a model.
            val placement = node.matrix(); val v = node.mesh.vertices
            val tris = FloatArray(v.size / 3)
            for (t in 0 until v.size / 27) for (k in 0..2) {
                val q = placement.transformPoint(Vec3(v[27 * t + 9 * k], v[27 * t + 9 * k + 1], v[27 * t + 9 * k + 2]))
                tris[9 * t + 3 * k] = q.x; tris[9 * t + 3 * k + 1] = q.y; tris[9 * t + 3 * k + 2] = q.z
            }
            physics.addMesh(TriangleMesh(tris, surface ?: Material.DEFAULT))
            return
        }
        if (m == null) {
            val s = shapeOf(node, null)
            val b = physics.add(s, node.position + node.rotation.rotate(centreOf(node, null)), node.rotation, type = BodyType.STATIC,
                friction = surface?.friction ?: .6f)
            b.tag = node; node.body = b
            surfaces[b.id] = surface?.name ?: "stone"
            if (!blocksCamera) cameraIgnores.add(b.id)
            return
        }
        // A model: its own triangles, each with the surface named after its material.
        val ids = materials.map { name -> m.materials.indexOf(name).also { require(it >= 0) { "No material « $name » in the model (it has: ${m.materials.joinToString()})" } } }
        val (positions, mats) = m.surfaces()
        val placement = node.matrix()
        val v = ArrayList<Float>(); val pal = ArrayList<Int>()
        val palette = m.materials.map { surface ?: Material.named(it) } + (surface ?: Material.DEFAULT)
        for (t in mats.indices) {
            if (ids.isNotEmpty() && mats[t] !in ids) continue
            for (k in 0..2) { val p = placement.transformPoint(Vec3(positions[9 * t + 3 * k], positions[9 * t + 3 * k + 1], positions[9 * t + 3 * k + 2])); v.add(p.x); v.add(p.y); v.add(p.z) }
            pal.add(if (mats[t] >= 0) mats[t] else palette.size - 1)
        }
        require(pal.isNotEmpty()) { "No triangle to make solid" }
        physics.addMesh(TriangleMesh(v.toFloatArray(), pal.toIntArray(), palette))
    }

    internal fun addCollider(node: Node, shape: Shape, offset: Vec3, part: String?, surface: String, blocksCamera: Boolean): Body {
        val b: Body
        if (part == null) {
            val m = node.matrix()
            b = physics.add(shape, m.transformPoint(offset), node.rotation, type = BodyType.STATIC)
        } else {
            val a = requireNotNull(node.animator) { "collider(part = …) needs an animated model" }
            requireNotNull(a.nodePose(part)) { "No part « $part » (the model has: ${a.model.nodes.mapNotNull { it.name }.joinToString()})" }
            val (p, q) = partPose(node, part, offset)
            b = physics.add(shape, p, q, type = BodyType.KINEMATIC)
            followers.add(Follower(node, part, offset, b))
        }
        b.tag = node; surfaces[b.id] = surface
        if (!blocksCamera) cameraIgnores.add(b.id)
        return b
    }

    private fun partPose(node: Node, part: String, offset: Vec3): Pair<Vec3, Quat> {
        val m = node.matrix() * node.animator!!.nodePose(part)!!
        val ax = m.transformDirection(Vec3.X).normalized(); var ay = m.transformDirection(Vec3.Y)
        ay = (ay - ax * (ay dot ax)).normalized()
        return m.transformPoint(offset) to Quat.fromAxes(ax, ay, ax cross ay)
    }

    internal fun addShadow(node: Node, radius: Float?) {
        val r = radius ?: shapeOf(node, null).let { s -> when (s) { is Sphere -> s.radius * 1.1f; is Box -> max(s.halfX, s.halfZ) * 1.5f; is Capsule -> s.radius * 1.5f } }
        val disc = Node(this, shadowMesh).also { it.isShadow = true; it.scale = r }
        all.add(disc); shadows.add(Shadow(disc, node, r))
    }

    /** Takes [node] out of the scene and its bodies out of the physics. */
    fun remove(node: Node) {
        if (node.removed) return
        node.removed = true
        all.remove(node); linked.remove(node); kinematicNodes.remove(node); playerList.remove(node)
        shadows.filter { it.of === node }.forEach { all.remove(it.disc) }
        shadows.removeAll { it.of === node }
        node.body?.let { b -> sounds.untrack(b); surfaces.remove(b.id); cameraIgnores.remove(b.id); physics.remove(b) }
        followers.filter { it.node === node }.forEach { surfaces.remove(it.body.id); cameraIgnores.remove(it.body.id); physics.remove(it.body) }
        followers.removeAll { it.node === node }
        if (node is Player) { surfaces.remove(node.character.body.id); physics.remove(node.character.body) }
        lightList.removeAll { it.node === node }; emitters.removeAll { it.node === node }
        node.body = null
    }

    // ------------------------------------------------------------------ zones and pickups

    /**
     * A zone that notices what comes in and goes out (players and moving bodies; set [Zone.filter]):
     * a door, a finish line, water that slows things down.
     */
    fun zone(shape: Shape, at: Vec3, onEnter: (Node) -> Unit = {}, onExit: (Node) -> Unit = {}): Zone {
        val t = physics.addTrigger(shape, at)
        val z = Zone(t)
        t.filter = { b -> (b.tag as? Node ?: playerList.firstOrNull { it.character.body == b })?.let { z.filter(it) } ?: false }
        fun nodeOf(b: Body): Node? = b.tag as? Node ?: playerList.firstOrNull { it.character.body == b }
        t.onEnter = { b -> nodeOf(b)?.let(onEnter) }
        t.onExit = { b -> nodeOf(b)?.let(onExit) }
        return z
    }

    /**
     * Makes [node] something a player picks up by walking into it (within [radius] metres): it
     * disappears with a little tune, the player celebrates, [onPickup] runs. Restarting brings it back.
     */
    fun pickup(node: Node, radius: Number = .8, celebrate: Boolean = true, onPickup: (Player) -> Unit = {}): Pickup {
        val t = physics.addTrigger(Sphere(radius.toFloat()), node.position)
        val p = Pickup(node, t)
        t.filter = { b -> playerList.any { it.character.body == b } }
        t.onEnter = { b ->
            val who = playerList.first { it.character.body == b }
            t.enabled = false; node.visible = false; p.collected = true
            sounds.playPickup()
            if (celebrate) who.celebrate()
            onPickup(who)
        }
        pickupList.add(p)
        return p
    }

    val pickups: List<Pickup> get() = pickupList

    // ------------------------------------------------------------------ sound

    /** Plays [sound] once, at [at] in space (heard within [range] metres) or everywhere when null. */
    fun play(sound: Sound, at: Vec3? = null, volume: Number = 1, pitch: Number = 1, range: Number = 60): Voice? =
        sounds.play(sound, at, volume.toFloat(), pitch.toFloat(), range.toFloat())

    /** Plays [sound] in a loop, at [at] (heard within [range] metres) or everywhere: waves, wind, a fountain. */
    fun ambience(sound: Sound, at: Vec3? = null, volume: Number = .5, range: Number = 60) = sounds.ambience(sound, at, volume.toFloat(), range.toFloat())

    /** Mutes or unmutes the whole scene. */
    fun toggleMute() { audio?.let { it.volume = if (it.volume > 0f) 0f else 1f } }

    // ------------------------------------------------------------------ time and events

    /** Runs [block] every frame with the frame's duration in seconds. */
    fun onUpdate(block: Scene.(dt: Float) -> Unit) { updates.add(block) }

    /** Runs [block] when [key] goes down. */
    fun onKey(key: Int, block: () -> Unit) { keyActions.add(key to block) }

    /** Runs [block] once, [seconds] from now. */
    fun after(seconds: Number, block: () -> Unit) { timers.add(Timer(time + seconds.toFloat(), 0f, 0f, block)) }

    /** Runs [block] every [seconds] (plus a random extra up to [randomExtra] seconds each time). */
    fun every(seconds: Number, randomExtra: Number = 0, block: () -> Unit) {
        val s = seconds.toFloat(); val r = randomExtra.toFloat()
        timers.add(Timer(time + s + Math.random().toFloat() * r, s, r, block))
    }

    /** Runs [block] when the scene restarts (see [restart]). */
    fun onRestart(block: () -> Unit) { restarts.add(block) }

    /** Players back at their spawn points, pickups back in place, then the [onRestart] actions. */
    fun restart() {
        for (p in playerList) p.respawn()
        for (p in pickupList) { p.collected = false; p.node.visible = true; p.zone.enabled = true }
        particles.clear()
        restarts.forEach { it() }
    }

    // ------------------------------------------------------------------ lamps and particles

    private val lightList = ArrayList<PointLight>()
    private val emitters = ArrayList<Emitter>()
    internal val particles = ParticleSystem()
    private val extras = FrameExtras()
    private val nearest = IntArray(MAX_LIGHTS)
    private val nearestScore = FloatArray(MAX_LIGHTS)

    /** The lamps of the scene. */
    val lights: List<PointLight> get() = lightList

    /**
     * A lamp at [at]: it brightens what is within [radius] metres, warm by default. [onlyAtNight]: it lights
     * up as night falls (see [Lighting.time]); [flicker]: how much it wavers, 1 for a flame.
     */
    fun light(color: Color = Color(1f, .72f, .38f), intensity: Number = 1.5, radius: Number = 6, at: Vec3 = Vec3.ZERO,
              onlyAtNight: Boolean = false, flicker: Number = 0): PointLight =
        PointLight(color, intensity.toFloat(), radius.toFloat(), at, null, onlyAtNight, flicker.toFloat()).also { lightList.add(it) }

    internal fun lightOn(node: Node, light: PointLight): PointLight { lightList.add(light); return light }
    fun removeLight(light: PointLight) { lightList.remove(light) }

    /** Particles flowing from [at], [rate] per second, in [style] (see [Particles] for ready-made ones). */
    fun emit(style: ParticleStyle, at: Vec3, rate: Number = 20): Emitter =
        Emitter(style, rate.toFloat(), at, null).also { emitters.add(it) }

    internal fun emitOn(e: Emitter): Emitter { emitters.add(e); return e }
    fun removeEmitter(e: Emitter) { emitters.remove(e) }

    /** [count] particles at once from [at]: a splash, a puff of dust, sparkles. [direction]: where they go (the style's by default). */
    fun burst(style: ParticleStyle, at: Vec3, count: Int = 12, direction: Vec3 = style.direction) =
        particles.spawn(style, at.x, at.y, at.z, count, direction.x, direction.y, direction.z)

    private fun updateEffects(dt: Float) {
        for (k in 0 until emitters.size) {
            val e = emitters[k]
            if (!e.enabled || (e.node != null && !e.node.visible)) { e.owed = 0f; continue }
            e.owed += e.rate * dt
            val n = e.owed.toInt()
            if (n == 0) continue
            e.owed -= n
            val p = e.node?.let { it.position + it.rotation.rotate(e.position * it.scale) } ?: e.position
            particles.spawn(e.style, p.x, p.y, p.z, n)
        }
        particles.update(dt, lighting.wind.x, lighting.wind.z, time)
    }

    /** The lamps nearest [eye] (at most [MAX_LIGHTS], dimmed by day if they only light at night, wavering) and the particles. */
    internal fun frameExtras(eye: Vec3): FrameExtras {
        var n = 0
        val night = lighting.night
        for (k in 0 until lightList.size) {
            val l = lightList[k]
            if (!l.enabled || (l.node != null && !l.node.visible)) continue
            var power = l.intensity * (if (l.onlyAtNight) ((night - .2f) / .4f).coerceIn(0f, 1f) else 1f)
            if (l.flicker > 0f) {
                val t = time * 7f + l.seed
                power *= 1f - l.flicker * .3f * (.5f + .25f * sin(t) + .25f * sin(t * 2.3f + 1.7f))
            }
            if (power <= 0f) continue
            val p = l.worldPosition
            val score = p.distanceTo(eye) - l.radius
            if (n == MAX_LIGHTS && score >= nearestScore[n - 1]) continue
            var i = if (n < MAX_LIGHTS) n++ else n - 1
            while (i > 0 && nearestScore[i - 1] > score) { nearest[i] = nearest[i - 1]; nearestScore[i] = nearestScore[i - 1]; copyLight(i - 1, i); i-- }
            nearest[i] = k; nearestScore[i] = score
            val a = extras.lights; val o = 8 * i
            a[o] = p.x; a[o + 1] = p.y; a[o + 2] = p.z; a[o + 3] = l.radius
            a[o + 4] = l.color.r * power; a[o + 5] = l.color.g * power; a[o + 6] = l.color.b * power; a[o + 7] = 0f
        }
        extras.lightCount = n
        particles.write(extras, time, night)
        return extras
    }

    private fun copyLight(from: Int, to: Int) { System.arraycopy(extras.lights, 8 * from, extras.lights, 8 * to, 8) }

    /** Leaves this scene for a new one built by [build] (at the end of the frame): a menu, the next level. */
    fun goTo(build: Scene.() -> Unit) { next = build }

    // ------------------------------------------------------------------ one frame

    /** Advances the scene by [dt] seconds without drawing it (keys read from [Input.source], camera following): for tests and servers. */
    fun step(dt: Float) { input.poll(); update(dt); camera.view(dt) }

    internal fun update(dt: Float) {
        time += dt
        for (k in 0 until keyActions.size) { val a = keyActions[k]; if (input.pressed(a.first)) a.second() }
        camera.switchKey?.let { k -> if (input.pressed(k)) camera.mode = if (camera.mode == CameraMode.FIRST_PERSON) CameraMode.THIRD_PERSON else CameraMode.FIRST_PERSON }
        // Timers (a timer may add timers).
        // Callbacks may add or remove timers and nodes: they run over a copy kept from frame to frame.
        if (timers.isNotEmpty()) {
            timerCopy.clear(); for (k in 0 until timers.size) timerCopy.add(timers[k])
            for (k in 0 until timerCopy.size) { val t = timerCopy[k]; if (time >= t.at) { t.action(); if (t.every > 0f) t.at = time + t.every + Math.random().toFloat() * t.spread else timers.remove(t) } }
        }
        for (k in 0 until updates.size) updates[k](this, dt)
        nodeCopy.clear(); for (k in 0 until all.size) nodeCopy.add(all[k])
        for (k in 0 until nodeCopy.size) { val n = nodeCopy[k]; val bs = n.behaviours; for (j in 0 until bs.size) n.(bs[j])(dt) }
        // The world first (animated scenery, moving platforms, bodies), then the players: they stand where
        // the platforms will be drawn, without sinking into them for a frame.
        for (k in 0 until all.size) { val n = all[k]; if (n !is Player) n.animator?.advance(dt) }
        val h = dt.coerceIn(1f / 240f, 1f / 30f)
        for (f in followers) { val (p, q) = partPose(f.node, f.part, f.offset); f.body.moveTo(p, h, q) }
        for (n in kinematicNodes) n.body?.moveTo(n.position + n.rotation.rotate(n.bodyOffset), h, n.rotation)
        physics.step(h)
        for (k in 0 until linked.size) { val n = linked[k]; n.body?.let { b -> n.rotation = b.rotation; n.position = b.position - b.rotation.rotate(n.bodyOffset) } }
        for (k in 0 until playerList.size) { val p = playerList[k]; p.forcedClip = forcedPlayerClip; p.update(dt) }
        placeShadows()
        lighting.advance(dt)
        updateEffects(dt)
        sounds.update(dt)
    }

    // With cast shadows, only players keep a round shadow, a light one straight below them: it shows where a
    // jump will land. Without, every moving thing has one.
    private fun placeShadows() {
        for (s in shadows) {
            if (lighting.shadows && s.of !is Player) { s.disc.visible = false; continue }
            s.disc.mesh = if (lighting.shadows) lightShadowMesh else shadowMesh
            val p = s.of.position
            // The first thing under it (ground, the top of a crate…), except itself and characters.
            val under = physics.raycast(p.plus(0f, .25f, 0f), Vec3(0f, -1f, 0f), 30f,
                filter = { it.tag !== s.of && it.tag !is Character && it != s.of.body })
            if (under == null) { s.disc.visible = false; continue }
            val ground = under.point.y
            val high = (p.y - ground).coerceAtLeast(0f)
            s.disc.visible = s.of.visible && high < 6f && !(s.of is Player && s.of.removed)
            s.disc.position = Vec3(p.x, ground + .03f, p.z)
            s.disc.scale = s.radius * (1f + high * .12f) / (1f + high * .35f)
        }
    }

    /** What the renderer draws this frame (the sky follows the eye; a first-person player is not drawn). */
    internal fun drawList(eye: Vec3): List<Node> {
        if (sky.mesh.triangleCount == 0) sky.mesh = lighting.skyDome()
        if (pendingWater.isNotEmpty()) {
            for ((node, level, size) in pendingWater) node.mesh = waterMesh(physics, level, size, node.water!!.reach)
            pendingWater.clear()
        }
        sky.position = eye
        return all
    }

    /** The node not to draw: the first-person player (one does not see oneself). */
    internal val hiddenNode: Node? get() = if (camera.mode == CameraMode.FIRST_PERSON) camera.target else null
}

/** A zone made by [Scene.zone]. */
class Zone internal constructor(val trigger: Trigger) {
    /** Which nodes count (all moving ones by default). */
    var filter: (Node) -> Boolean = { true }
    var enabled: Boolean
        get() = trigger.enabled
        set(v) { trigger.enabled = v }
}

/** Something to pick up, made by [Scene.pickup]. */
class Pickup internal constructor(val node: Node, internal val zone: Trigger) {
    var collected = false
        internal set
}
