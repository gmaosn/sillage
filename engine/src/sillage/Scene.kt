package sillage

import sillage.audio.Audio
import sillage.audio.Reverb
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
    /**
     * The scene's own random numbers: `every(…, randomExtra)` draws from it, and a game may too
     * (`random.nextFloat()`). Seeded from the clock, or from `--seed N` / [seed], so that a run can be
     * played again exactly (checks, recordings).
     */
    var random: kotlin.random.Random = kotlin.random.Random(System.nanoTime())
        private set

    /** Sets [random]'s seed: the same seed, the same draws. */
    fun seed(seed: Long) { random = kotlin.random.Random(seed) }

    /** Where the sounds are heard from: the followed player's head (default) or the camera. */
    var listener = Listener.PLAYER

    /** Seeing what the engine does: F5 the physics, F6 a free camera, F7 pause, F8 one frame (see [Debug]). */
    val debug = Debug(this)
    /** Milliseconds the last physics step took (shown by [debug]). */
    internal var physicsMs = 0f
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
    private class Follower(val node: Node, val part: String, val offset: Vec3, val body: Body, val rotation: Quat = Quat.IDENTITY)
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

    internal fun add(node: Node, at: Vec3): Node { node.position = at; all.add(node); return node }

    /** Nodes with [Node.onUpdate] blocks, and animated models (not players), in the order they were made. */
    private val behaving = ArrayList<Node>()
    private val animated = ArrayList<Node>()
    internal fun animates(node: Node) {
        if (node in animated) return
        var i = animated.size
        while (i > 0 && animated[i - 1].serial > node.serial) i--
        animated.add(i, node)
    }

    internal fun behaves(node: Node) {
        if (node in behaving) return
        // Kept in the order the nodes were made, whatever the order their blocks came in: as when every node was visited.
        var i = behaving.size
        while (i > 0 && behaving[i - 1].serial > node.serial) i--
        behaving.add(i, node)
    }

    // ------------------------------------------------------------------ Blender as the editor

    internal val helpers = ModelHelpers(this)

    /** The first node called [name] (a separate object of a model is named after its Blender object), or null. */
    fun find(name: String): Node? = all.firstOrNull { it.name == name && !it.isShadow }

    /** The first zone made from a model's object `zone_NAME` (or with `sillage = zone`), once the scene has started; null before or if none. */
    fun zone(name: String): Zone? = helpers.zone(name)

    /** Runs [action] when a node enters the model zone [zone] (Blender object `zone_NAME`, NAME without ".001"). */
    fun onEnter(zone: String, action: (Node) -> Unit) = helpers.onEnter(zone, action)

    /** Runs [action] when a node leaves the model zone [zone]. */
    fun onExit(zone: String, action: (Node) -> Unit) = helpers.onExit(zone, action)

    /** Runs [action] when a player picks up a model's object `pickup_NAME` (any of them, NAME without ".001"). */
    fun onPickup(name: String, action: (Player) -> Unit) = helpers.onPickup(name, action)

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

    /**
     * A chain of [links] capsules from [from] to [to], each link a dynamic node joined to the next by a ball
     * joint: the first hangs from [start] (or the world) at [from], the last from [end] at [to] when given
     * (a swing's seat, a lamp). Returns the links.
     */
    fun chain(from: Vec3, to: Vec3, links: Int, radius: Number = .03, color: Color = Color.GREY, start: Node? = null, end: Node? = null,
              density: Number = 2000): List<Node> {
        require(links >= 1) { "A chain needs a link" }
        val r = radius.toFloat()
        val span = to - from; val length = span.length()
        require(length > 2 * r * links) { "The links would be shorter than they are wide" }
        val dir = span / length; val step = length / links
        val turn = Quat.fromTo(Vec3.Y, dir)
        val nodes = ArrayList<Node>()
        var previous = start
        for (k in 0 until links) {
            val link = capsule(r, step, color, at = from + dir * (step * (k + .5f)))
            link.rotation = turn
            link.dynamic(density = density, impact = Impact.SILENT)
            link.hangFrom(from + dir * (step * k), previous)
            nodes.add(link); previous = link
        }
        if (end != null) nodes.last().hangFrom(to, end)
        return nodes
    }

    /** A node drawing [mesh] (made with [MeshBuilder] or the shapes of [Mesh]). */
    fun mesh(mesh: Mesh, at: Vec3 = Vec3.ZERO): Node = add(Node(this, mesh), at)

    /**
     * A GLB model (a file path, or a path in the resources). A model with animations keeps its parts
     * and plays clips with [Node.play]; a model without is drawn as one mesh. [normals]: the engine's soft
     * normals (default) or the file's ([Normals.FROM_FILE]: Blender's flat faces and sharp edges kept).
     * Its helper objects (collision volumes, spawn, lamps…: see [Role]) take their place at the first frame.
     */
    /** A model's file as one mesh in its rest pose, shared like [model]'s. */
    internal fun flatModel(path: String): Mesh = flat.getOrPut(path) { models.getOrPut(path) { Glb.loadModel(path) }.flatten() }

    fun model(path: String, at: Vec3 = Vec3.ZERO, scale: Number = 1, normals: Normals = Normals.SMOOTH): Node {
        val key = if (normals == Normals.SMOOTH) path else "$path|$normals"
        val m = models.getOrPut(key) { Glb.loadModel(path, normals) }
        val node = Node(this, if (m.clips.isEmpty()) flat.getOrPut(key) { m.flatten().also { require(it.triangleCount > 0) { "GLB without triangles: $path" } } } else Mesh.EMPTY)
        node.model = m
        node.source = path
        if (m.helpers.isNotEmpty()) helpers.pending.add(node)
        if (m.clips.isNotEmpty()) { node.parts = m.parts(); node.animator = Animator(m, node.parts) }
        if (autoTextures) texturesByName(node)
        node.scale = scale.toFloat()
        return add(node, at)
    }

    /** A flat solid floor of [size] metres (its edge hidden in the fog by default), its top at [at]; grass by default: the surface sets friction and footsteps. */
    fun ground(size: Number = 400, color: Color = Color.GRASS, surface: Material = Material.GRASS, at: Vec3 = Vec3.ZERO): Node {
        val s = size.toFloat() / 2
        val node = add(Node(this, Mesh.plane(2 * s, 2 * s, color)), at)
        if (autoTextures) Textures.named(surface.name)?.let { node.texture(it) }
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
        waterAreas.add(floatArrayOf(level.toFloat(), size.toFloat() / 2))
        return add(node, Vec3.ZERO)
    }
    // Water is shaped by the shores around it: its mesh is made once the scene is built, before the first frame.
    private val pendingWater = ArrayList<Triple<Node, Float, Float>>()
    private val waterAreas = ArrayList<FloatArray>()          // level, half size

    /**
     * A player: walks, runs and jumps with the keyboard (WASD, arrows, space, shift to walk), the camera
     * follows it, its model's clips play by themselves (idle, walk, run, jump, fall…). Without a model it
     * is a plain capsule. [setup] adjusts it: speeds, keys, clip names, `view = CameraMode.FIRST_PERSON`…
     */
    fun player(model: String? = null, at: Vec3? = null, radius: Number = .28, height: Number = 1.8, setup: Player.() -> Unit = {}): Player {
        // Without [at]: where a model's `spawn` object stands, facing as it does.
        val spawn = if (at == null) helpers.spawn() else null
        val start = at ?: spawn?.first ?: Vec3.ZERO
        val c = Character(physics, start, radius.toFloat(), height.toFloat())
        val p = Player(this, c, start)
        if (spawn != null) { p.yaw = spawn.second; if (playerList.isEmpty()) camera.yaw = spawn.second }
        if (model != null) {
            val m = models.getOrPut(model) { Glb.loadModel(model) }
            p.model = m
            if (m.clips.isEmpty()) p.mesh = flat.getOrPut(model) { m.flatten() }
            else { p.parts = m.parts(); p.animator = Animator(m, p.parts) }
            if (autoTextures) texturesByName(p)
        } else p.mesh = Mesh.capsule(radius.toFloat(), height.toFloat(), Color.ORANGE).transformed(Mat4.translation(Vec3(0f, height.toFloat() / 2, 0f)))
        all.add(p); playerList.add(p)
        addShadow(p, radius.toFloat() * 1.5f)
        p.setup()
        return p
    }

    // ------------------------------------------------------------------ looks: textures, glow, wind

    /**
     * Models get ready-made textures by themselves, from their material names (see [Textures.named]):
     * "grass", "Roof", "bois_clair"…; [ground] gets the texture of its surface. Set it to false before
     * adding them to keep plain colours (a node can also drop its texture with `texture(null)`).
     */
    var autoTextures = true

    private val autoTextured = java.util.IdentityHashMap<Mesh, Mesh>()
    private val sharedTextured = java.util.IdentityHashMap<Mesh, HashMap<List<Float>, Mesh>>()
    private val groundCache = HashMap<Long, Float>()

    /** Each mesh of [node] (its own, then its parts') with its placement in the world and a way to replace it. */
    private class Target(val mesh: Mesh, val place: Mat4, val set: (Mesh) -> Unit)
    private fun targets(node: Node): List<Target> {
        val out = ArrayList<Target>()
        val m = node.matrix()
        if (node.mesh.triangleCount > 0) out.add(Target(node.mesh, m) { node.mesh = it })
        for (p in node.parts) out.add(Target(p.mesh, m * p.pose) { p.mesh = it })
        return out
    }

    /** The material indices of [node]'s model named by [names] (a trailing * matches the start of a name); null: every triangle. */
    private fun materialIds(node: Node, names: Array<out String>, what: String): Set<Int>? {
        if (names.isEmpty()) return null
        val m = requireNotNull(node.model) { "$what(…, \"${names.first()}\"): only a model has materials" }
        val ids = HashSet<Int>()
        for (n in names) {
            val found = m.materials.indices.filter { i -> if (n.endsWith("*")) m.materials[i].startsWith(n.dropLast(1)) else m.materials[i] == n }
            require(found.isNotEmpty()) { "$what: no material « $n » in the model (it has: ${m.materials.joinToString()})" }
            ids.addAll(found)
        }
        return ids
    }

    /**
     * Changes the per-vertex looks of the triangles of [node] whose material is in [ids] (all if null):
     * [apply] writes the [EFFECT_FLOATS] floats of one vertex at [o] in its effects, knowing where it is in the world.
     */
    private inline fun changeLooks(node: Node, ids: Set<Int>?, what: String, apply: (e: FloatArray, o: Int, world: Vec3) -> Unit) {
        var touched = 0
        for (t in targets(node)) {
            val mesh = t.mesh; val v = mesh.vertices; val mats = mesh.materials
            val e = mesh.effects?.copyOf() ?: FloatArray(v.size / 9 * EFFECT_FLOATS)
            var any = false
            for (tri in 0 until mesh.triangleCount) {
                if (ids != null && (mats == null || mats[tri] !in ids)) continue
                any = true; touched++
                for (k in 0..2) {
                    val vi = 3 * tri + k
                    apply(e, vi * EFFECT_FLOATS, t.place.transformPoint(Vec3(v[9 * vi], v[9 * vi + 1], v[9 * vi + 2])))
                }
            }
            if (any) t.set(mesh.withEffects(e))
        }
        require(touched > 0 || node.mesh.triangleCount == 0 && node.parts.isEmpty()) { "$what: no triangle to change" }
    }

    internal fun sway(node: Node, metres: Float, reach: Float, names: Array<out String>) {
        require(reach > 0f) { "sway: reach must be positive" }
        changeLooks(node, materialIds(node, names, "sway"), "sway") { e, o, p ->
            val key = (Math.round(p.x * 200f).toLong() shl 32) xor (Math.round(p.z * 200f).toLong() and 0xFFFFFFFFL)
            val ground = groundCache.getOrPut(key) {
                physics.raycast(Vec3(p.x, p.y + .05f, p.z), Vec3(0f, -1f, 0f), 50f, filter = { false })?.point?.y ?: (p.y - reach)
            }
            val h = ((p.y - ground) / reach).coerceIn(0f, 1f)
            e[o + 3] = metres * h * h
        }
    }

    internal fun glow(node: Node, color: Color, intensity: Float, atNight: Boolean, names: Array<out String>) {
        val k = if (atNight) -intensity else intensity
        changeLooks(node, materialIds(node, names, "glow"), "glow") { e, o, _ ->
            e[o] = color.r * k; e[o + 1] = color.g * k; e[o + 2] = color.b * k
        }
    }

    internal fun texture(node: Node, texture: Texture?, tile: Float?, strength: Float?, names: Array<out String>) {
        val id = texture?.id?.toFloat() ?: 0f
        val t = tile ?: texture?.tile ?: 1f; val k = strength ?: texture?.strength ?: 0f
        val mode = if (texture?.mapping == Texture.Mapping.FACES) 1f else 0f
        // A shared mesh (primitives, models) textured whole stays shared: thousands of textured boxes are one draw.
        val single = node.parts.isEmpty() && names.isEmpty() && node.mesh.effects == null
        if (single) {
            val source = node.mesh
            node.mesh = sharedTextured.getOrPut(source) { HashMap() }.getOrPut(listOf(id, t, k, mode)) {
                val e = FloatArray(source.vertices.size / 9 * EFFECT_FLOATS)
                for (o in e.indices step EFFECT_FLOATS) { e[o + 4] = id; e[o + 5] = t; e[o + 6] = k; e[o + 7] = mode }
                source.withEffects(e)
            }
            return
        }
        changeLooks(node, materialIds(node, names, "texture"), "texture") { e, o, _ -> e[o + 4] = id; e[o + 5] = t; e[o + 6] = k; e[o + 7] = mode }
    }

    /** The ready-made textures a model's material names call for, laid once per mesh and shared by every copy. */
    private fun texturesByName(node: Node) {
        val m = node.model ?: return
        val byMaterial = m.materials.map { Textures.named(it) }
        if (byMaterial.all { it == null }) return
        fun textured(mesh: Mesh): Mesh = autoTextured.getOrPut(mesh) {
            val mats = mesh.materials ?: return@getOrPut mesh
            val e = mesh.effects?.copyOf() ?: FloatArray(mesh.vertices.size / 9 * EFFECT_FLOATS)
            var any = false
            for (tri in 0 until mesh.triangleCount) {
                val tex = mats[tri].let { if (it in byMaterial.indices) byMaterial[it] else null } ?: continue
                if (e[3 * tri * EFFECT_FLOATS + 7] == 2f) continue          // an image painted on the model wins
                any = true
                for (k in 0..2) {
                    val o = (3 * tri + k) * EFFECT_FLOATS
                    e[o + 4] = tex.id.toFloat(); e[o + 5] = tex.tile; e[o + 6] = tex.strength
                    e[o + 7] = if (tex.mapping == Texture.Mapping.FACES) 1f else 0f
                }
            }
            if (any) mesh.withEffects(e) else mesh
        }
        if (node.mesh.triangleCount > 0) node.mesh = textured(node.mesh)
        for (p in node.parts) p.mesh = textured(p.mesh)
    }

    // ------------------------------------------------------------------ physics behind the nodes

    /** An invisible solid [shape] at [at]: a wall, a building's volume, a tree trunk ([blocksCamera] = false). */
    fun collider(shape: Shape, at: Vec3, rotation: Quat = Quat.IDENTITY, blocksCamera: Boolean = true, surface: String = "stone"): Body {
        val b = physics.add(shape, at, rotation, type = BodyType.STATIC)
        surfaces[b.id] = surface
        if (!blocksCamera) cameraIgnores.add(b.id)
        return b
    }

    /** The point of the solid ground under (x, z): `vec(x, groundHeight(x, z), z)`. */
    fun groundAt(x: Number, z: Number): Vec3 = Vec3(x.toFloat(), groundHeight(x, z), z.toFloat())

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
        return if (k == 1f) s else when (s) { is Sphere -> Sphere(s.radius * k); is Box -> Box(s.halfX * k, s.halfY * k, s.halfZ * k); is Capsule -> Capsule(s.radius * k, s.halfHeight * k); is sillage.physics.ConvexHull -> s.scaled(k); is sillage.physics.Compound -> s.scaled(k) }
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

    internal fun addCollider(node: Node, shape: Shape, offset: Vec3, part: String?, surface: String, blocksCamera: Boolean, rotation: Quat = Quat.IDENTITY): Body {
        val b: Body
        if (part == null) {
            val m = node.matrix()
            b = physics.add(shape, m.transformPoint(offset), node.rotation, type = BodyType.STATIC)
        } else {
            val a = requireNotNull(node.animator) { "collider(part = …) needs an animated model" }
            requireNotNull(a.nodePose(part)) { "No part « $part » (the model has: ${a.model.nodes.mapNotNull { it.name }.joinToString()})" }
            val (p, q) = partPose(node, part, offset)
            b = physics.add(shape, p, q * rotation, type = BodyType.KINEMATIC)
            followers.add(Follower(node, part, offset, b, rotation))
        }
        b.tag = node; surfaces[b.id] = surface
        if (!blocksCamera) cameraIgnores.add(b.id)
        return b
    }

    internal fun partPose(node: Node, part: String, offset: Vec3): Pair<Vec3, Quat> {
        val m = node.matrix() * node.animator!!.nodePose(part)!!
        val ax = m.transformDirection(Vec3.X).normalized(); var ay = m.transformDirection(Vec3.Y)
        ay = (ay - ax * (ay dot ax)).normalized()
        return m.transformPoint(offset) to Quat.fromAxes(ax, ay, ax cross ay)
    }

    internal fun addShadow(node: Node, radius: Float?) {
        val r = radius ?: shapeOf(node, null).let { s -> when (s) { is Sphere -> s.radius * 1.1f; is Box -> max(s.halfX, s.halfZ) * 1.5f; is Capsule -> s.radius * 1.5f; is sillage.physics.ConvexHull -> s.boundingRadius; is sillage.physics.Compound -> s.boundingRadius } }
        val disc = Node(this, shadowMesh).also { it.isShadow = true; it.scale = r }
        all.add(disc); shadows.add(Shadow(disc, node, r))
    }

    /** Takes [node] out of the scene and its bodies out of the physics. */
    fun remove(node: Node) {
        if (node.removed) return
        node.removed = true
        all.remove(node); linked.remove(node); kinematicNodes.remove(node); playerList.remove(node); behaving.remove(node); animated.remove(node)
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
        z.onEnter = onEnter; z.onExit = onExit
        t.filter = { b -> (b.tag as? Node ?: playerList.firstOrNull { it.character.body == b })?.let { z.filter(it) } ?: false }
        fun nodeOf(b: Body): Node? = b.tag as? Node ?: playerList.firstOrNull { it.character.body == b }
        t.onEnter = { b -> nodeOf(b)?.let { z.onEnter(it) } }
        t.onExit = { b -> nodeOf(b)?.let { z.onExit(it) } }
        return z
    }

    /**
     * Makes [node] something a player picks up by walking into it (within [radius] metres): it
     * disappears with a little tune, the player celebrates, [onPickup] runs. Restarting brings it back.
     */
    fun pickup(node: Node, radius: Number = .8, celebrate: Boolean = true, onPickup: (Player) -> Unit = {}): Pickup {
        val t = physics.addTrigger(Sphere(radius.toFloat()), node.position)
        val p = Pickup(node, t)
        p.onPickup = onPickup
        t.filter = { b -> playerList.any { it.character.body == b } }
        t.onEnter = { b ->
            val who = playerList.first { it.character.body == b }
            t.enabled = false; node.visible = false; p.collected = true
            sounds.playPickup()
            if (celebrate) who.celebrate()
            p.onPickup(who)
        }
        pickupList.add(p)
        return p
    }

    val pickups: List<Pickup> get() = pickupList

    // ------------------------------------------------------------------ sound

    /** Plays [sound] once, at [at] in space (heard within [range] metres) or everywhere when null. */
    fun play(sound: Sound, at: Vec3? = null, volume: Number = 1, pitch: Number = 1, range: Number = 60): Voice? =
        sounds.play(sound, at, volume.toFloat(), pitch.toFloat(), range.toFloat())

    /**
     * Plays [sound] in a loop, at [at] (heard within [range] metres) or everywhere: waves, wind, a fountain.
     * The result's [Ambience.volume] can change at any time (a song by the fire only at night).
     */
    fun ambience(sound: Sound, at: Vec3? = null, volume: Number = .5, range: Number = 60): Ambience = sounds.ambience(sound, at, volume.toFloat(), range.toFloat())

    /** How the place sounds: an echo for the sounds placed in it (null: none), e.g. [Reverb.OUTDOOR]. */
    var reverb: Reverb?
        get() = sounds.reverb
        set(v) { sounds.reverb = v }

    /** Where the camera is inside [shape] (placed at [at]), sounds echo as in [reverb]: a cave, a hall, a house. */
    fun reverbZone(shape: Shape, at: Vec3, reverb: Reverb, rotation: Quat = Quat.IDENTITY) = sounds.reverbZone(shape, at, rotation, reverb)

    /** Where a sound made by [node] (or by its [part], for an animated model) is. */
    internal fun soundPosition(node: Node, part: String?): Vec3 =
        if (part != null && node.animator?.nodePose(part) != null) partPose(node, part, Vec3.ZERO).first else node.position

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
        timers.add(Timer(time + s + random.nextFloat() * r, s, r, block))
    }

    /** Runs [block] when the scene restarts (see [restart]). */
    fun onRestart(block: () -> Unit) { restarts.add(block) }

    // ------------------------------------------------------------------ scripts, conditions, tweens

    private val scripts = ArrayList<Script>()
    private val scriptCopy = ArrayList<Script>()
    private class Condition(val test: () -> Boolean, val action: () -> Unit, val again: Boolean) { var held = false; var fired = false }
    private val conditions = ArrayList<Condition>()
    private val tweens = ArrayList<Tween>()
    private val tweenCopy = ArrayList<Tween>()

    /**
     * A [Script]: [block] runs from the next frame on, and may wait for time or conditions without
     * stopping the game (`wait(2)`, `waitUntil { … }`).
     */
    fun script(block: suspend Script.() -> Unit): Script = Script(this).also { it.start(block); scripts.add(it) }

    /** Runs [action] the first frame [condition] holds; [restart] arms it again. */
    fun once(condition: () -> Boolean, action: () -> Unit) { conditions.add(Condition(condition, action, again = false)) }

    /** Runs [action] each time [condition] becomes true (it must turn false before it fires again). */
    fun whenever(condition: () -> Boolean, action: () -> Unit) { conditions.add(Condition(condition, action, again = true)) }

    /**
     * Calls [step] at every frame for [over] seconds (after waiting [after]) with the share of the way done,
     * 0 to 1, shaped by [ease]; the last call gets 1.
     */
    fun tween(over: Number, ease: Ease = Ease.IN_OUT, after: Number = 0, step: (Float) -> Unit): Tween =
        addTween(over, after, ease, step) {}

    /** Calls [step] with a value going from [from] to [to] in [over] seconds. */
    fun tween(from: Number, to: Number, over: Number, ease: Ease = Ease.IN_OUT, after: Number = 0, step: (Float) -> Unit): Tween {
        val a = from.toFloat(); val b = to.toFloat()
        return addTween(over, after, ease, { k -> step(a + (b - a) * k) }) {}
    }

    /** Fades this HUD element's opacity to [to] in [over] seconds, after [after]; at 0 it ends hidden, above 0 it is shown first. */
    fun Hud.Element.fade(to: Number, over: Number = .5, after: Number = 0, ease: Ease = Ease.IN_OUT): Tween {
        val target = to.toFloat().coerceIn(0f, 1f)
        val e = this
        var from = Float.NaN
        return addTween(over, after, ease, { k ->
            if (from.isNaN()) { from = if (e.visible) e.opacity else 0f; if (target > 0f) e.visible = true }
            e.opacity = from + (target - from) * k
        }) { if (target == 0f) e.visible = false }
    }

    /** Fades this HUD element in, to full opacity. */
    fun Hud.Element.fadeIn(over: Number = .5, after: Number = 0): Tween = fade(1, over, after)

    /** Fades this HUD element out; it ends hidden. */
    fun Hud.Element.fadeOut(over: Number = .5, after: Number = 0): Tween = fade(0, over, after)

    internal fun addTween(over: Number, after: Number, ease: Ease, apply: (Float) -> Unit, finished: () -> Unit): Tween {
        val d = over.toFloat(); val wait = after.toFloat()
        require(d >= 0f && d.isFinite() && wait >= 0f && wait.isFinite()) { "A tween takes durations of 0 seconds or more (got over $over, after $after)" }
        return Tween(time + wait, d, ease, apply, finished).also { tweens.add(it) }
    }

    /** Scripts, tweens and conditions, once per frame, in the order they were made. */
    private fun runScripts() {
        if (scripts.isNotEmpty()) {
            scriptCopy.clear(); scriptCopy.addAll(scripts)
            for (k in 0 until scriptCopy.size) scriptCopy[k].tick()
            scripts.removeAll { it.done }
        }
        if (tweens.isNotEmpty()) {
            tweenCopy.clear(); tweenCopy.addAll(tweens)
            for (k in 0 until tweenCopy.size) tweenCopy[k].tick(time)
            tweens.removeAll { it.done }
        }
        for (k in 0 until conditions.size) {
            val c = conditions[k]
            if (c.fired && !c.again) continue
            val now = c.test()
            if (now && !c.held) { c.fired = true; c.action() }
            c.held = now
        }
    }

    /** Players back at their spawn points, pickups back in place, then the [onRestart] actions. */
    fun restart() {
        for (p in playerList) p.respawn()
        for (p in pickupList) { p.collected = false; p.node.visible = true; p.zone.enabled = true }
        particles.clear()
        for (c in conditions) { c.fired = false; c.held = false }
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
     * A lamp at [at]: it brightens what is around it, falling off with the square of the distance and gone
     * at [radius] metres, warm by default ([intensity] 1: a white wall a metre away about as lit as by day). [onlyAtNight]: it lights
     * up as night falls (see [Lighting.time]); [flicker]: how much it wavers, 1 for a flame.
     */
    fun light(color: Color = Color(1f, .72f, .38f), intensity: Number = 1.5, radius: Number = 6, at: Vec3 = Vec3.ZERO,
              onlyAtNight: Boolean = false, flicker: Number = 0): PointLight =
        PointLight(color, intensity.toFloat(), radius.toFloat(), at, null, onlyAtNight, flicker.toFloat()).also { it.seed = lightList.size * 37.7f; lightList.add(it) }

    internal fun lightOn(node: Node, light: PointLight): PointLight { light.seed = lightList.size * 37.7f; lightList.add(light); return light }
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

    /** What the scene does by itself: dust where players land on sand or a path, splashes where things fall into water. */
    private fun automaticEffects(dt: Float) {
        for (k in 0 until playerList.size) {
            val p = playerList[k]
            if (!p.effects) continue
            if (p.onGround) {
                if (!p.fxGrounded && p.fxAir > .3f) {
                    val surface = p.character.groundBody?.let { surfaces[it.id] } ?: p.character.groundMaterial?.name
                    if (surface == "sand" || surface == "road") {
                        val pos = p.position
                        burst(Particles.DUST, Vec3(pos.x, pos.y + .03f, pos.z), (5 + 12 * ((p.fxAir - .3f) / .6f).coerceIn(0f, 1f)).toInt())
                    }
                }
                p.fxAir = 0f
            } else p.fxAir += dt
            p.fxGrounded = p.onGround
            splashes(p, p.position.y + .3f, p.velocity.y)
        }
        for (k in 0 until linked.size) { val n = linked[k]; splashes(n, n.position.y, n.body?.velocity?.y ?: 0f) }
    }

    private fun splashes(n: Node, y: Float, vy: Float) {
        val before = n.fxY
        n.fxY = y
        if (before.isNaN() || vy > -1.5f) return
        for (k in 0 until waterAreas.size) {
            val w = waterAreas[k]
            val p = n.position
            if (before > w[0] && y <= w[0] && abs(p.x) < w[1] && abs(p.z) < w[1]) {
                val count = (8 + 3 * -vy).toInt().coerceAtMost(48)
                particles.spawn(Particles.SPLASH, p.x, w[0] + .02f, p.z, count)
            }
        }
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
        debug.fill(extras, eye)
        return extras
    }

    private fun copyLight(from: Int, to: Int) { System.arraycopy(extras.lights, 8 * from, extras.lights, 8 * to, 8) }

    /** Leaves this scene for a new one built by [build] (at the end of the frame): a menu, the next level. */
    fun goTo(build: Scene.() -> Unit) { next = build }

    // ------------------------------------------------------------------ one frame

    /** Advances the scene by [dt] seconds without drawing it (keys read from [Input.source], camera following): for tests and servers. */
    fun step(dt: Float) { input.poll(); update(dt); camera.view(dt) }

    internal fun update(dt: Float) {
        if (helpers.pending.isNotEmpty()) helpers.settle()
        time += dt
        for (k in 0 until keyActions.size) { val a = keyActions[k]; if (input.pressed(a.first)) a.second() }
        camera.switchKey?.let { k -> if (input.pressed(k)) camera.mode = if (camera.mode == CameraMode.FIRST_PERSON) CameraMode.THIRD_PERSON else CameraMode.FIRST_PERSON }
        // Timers (a timer may add timers).
        // Callbacks may add or remove timers and nodes: they run over a copy kept from frame to frame.
        if (timers.isNotEmpty()) {
            timerCopy.clear(); for (k in 0 until timers.size) timerCopy.add(timers[k])
            for (k in 0 until timerCopy.size) { val t = timerCopy[k]; if (time >= t.at) { t.action(); if (t.every > 0f) t.at = time + t.every + random.nextFloat() * t.spread else timers.remove(t) } }
        }
        for (k in 0 until updates.size) updates[k](this, dt)
        runScripts()
        // Only the nodes that have behaviours, or animations, are visited: a world of 40 000 still blocks costs nothing here.
        if (behaving.isNotEmpty()) {
            nodeCopy.clear(); for (k in 0 until behaving.size) nodeCopy.add(behaving[k])
            for (k in 0 until nodeCopy.size) { val n = nodeCopy[k]; if (n.removed) continue; val bs = n.behaviours; for (j in 0 until bs.size) n.(bs[j])(dt) }
        }
        // The world first (animated scenery, moving platforms, bodies), then the players: they stand where
        // the platforms will be drawn, without sinking into them for a frame.
        for (k in 0 until animated.size) animated[k].animator?.advance(dt)
        val h = dt.coerceIn(1f / 240f, 1f / 30f)
        for (f in followers) { val (p, q) = partPose(f.node, f.part, f.offset); f.body.moveTo(p, h, if (f.rotation === Quat.IDENTITY) q else q * f.rotation) }
        for (n in kinematicNodes) n.body?.moveTo(n.position + n.rotation.rotate(n.bodyOffset), h, n.rotation)
        val physicsStart = System.nanoTime()
        physics.step(h)
        physicsMs = (System.nanoTime() - physicsStart) / 1e6f
        for (k in 0 until linked.size) { val n = linked[k]; n.body?.let { b -> n.rotation = b.rotation; n.position = b.position - b.rotation.rotate(n.bodyOffset) } }
        for (k in 0 until playerList.size) { val p = playerList[k]; p.forcedClip = forcedPlayerClip; p.update(dt) }
        placeShadows()
        lighting.advance(dt)
        automaticEffects(dt)
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
    /** Its name, for a zone made from a model (`zone_NAME` in Blender); null otherwise. */
    var name: String? = null
        internal set
    /** What happens when a node comes in, and goes out. */
    var onEnter: (Node) -> Unit = {}
    var onExit: (Node) -> Unit = {}
    /** Which nodes count (all moving ones by default). */
    var filter: (Node) -> Boolean = { true }
    var enabled: Boolean
        get() = trigger.enabled
        set(v) { trigger.enabled = v }
}

/** Something to pick up, made by [Scene.pickup]. */
class Pickup internal constructor(val node: Node, internal val zone: Trigger) {
    /** What happens when a player picks it up (after the chime and the celebration). */
    var onPickup: (Player) -> Unit = {}
    var collected = false
        internal set
}

/** Where a scene's sounds are heard from (see [Scene.listener]). */
enum class Listener {
    /** At the head of the player the camera follows, turned as the camera looks (the camera itself when it follows no player). */
    PLAYER,
    /** At the camera, a few metres behind a player in third person. */
    CAMERA
}
