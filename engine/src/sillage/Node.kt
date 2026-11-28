package sillage

import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.BallJoint
import sillage.physics.Body
import sillage.physics.DistanceJoint
import sillage.physics.FixedJoint
import sillage.physics.HingeJoint
import sillage.physics.Material
import sillage.physics.Shape
import sillage.physics.SliderJoint
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Something in a [Scene]: a mesh or an animated model, placed by [position], [rotation] and [scale].
 * Nodes are made by the scene ([Scene.box], [Scene.model]…) and most calls return the node itself, so
 * they chain: `box(1f).at(0, 3, 0).dynamic()`.
 */
open class Node internal constructor(internal val scene: Scene, mesh: Mesh) {
    /** What is drawn (empty for an animated model, which draws its [parts]). */
    var mesh: Mesh = mesh
        set(v) { if (v !== field) stir(); field = v }
    var name: String? = null
    open var position: Vec3 = Vec3.ZERO
        set(v) { val f = field; if (v.x != f.x || v.y != f.y || v.z != f.z) stir(); field = v }
    var rotation: Quat = Quat.IDENTITY
        set(v) { val f = field; if (v.x != f.x || v.y != f.y || v.z != f.z || v.w != f.w) stir(); field = v }
    /** Uniform size factor. Set it before [dynamic] or [solid]: the collision shape is sized then. */
    var scale: Float = 1f
        set(v) { if (v != field) stir(); field = v }
    var visible: Boolean = true
        set(v) { if (v != field) stir(); field = v }
    /** Whether it casts a shadow on what is below it (see [Lighting.shadows]); it receives shadows either way. */
    var castsShadow: Boolean = true
    /** Free label for the game (a role, a score…); the engine never reads it. */
    var tag: Any? = null

    /** Parts of an animated model, each drawn with the node's [matrix] times the part's pose. */
    var parts: List<Part> = emptyList()
        set(v) { if (v !== field) stir(); field = v }
    /**
     * Beyond this distance from the camera (metres) it is not drawn, nor its shadow; infinite by default
     * (the haze already hides what is past [Lighting.fogEnd], and that is not drawn either).
     */
    var drawDistance: Float = Float.POSITIVE_INFINITY
        set(v) { if (v != field) stir(); field = v }

    // Its place among the still nodes (StillCells): the scene's cells hold the nodes that stopped moving, so
    // that a frame can leave out a whole cell at once. A change of what places or draws it takes it out again.
    /** Its index in the scene's draw list, kept by the scene's [StillCells]. */
    internal var rank = -1
    /** The cell holding it while it stands still (null: it is visited every frame), and its slot there. */
    internal var cell: StillCell? = null
    internal var cellSlot = -1
    /** Whether its placement or looks changed since the cells last looked; frames in a row without a change. */
    internal var stirred = false
    internal var calm = 0
    /** Whether the scene's cells know it (in a cell or among the nodes visited every frame). */
    internal var indexed = false

    /** Its placement or what it draws changed: it leaves its cell, if it was in one. */
    internal fun stir() {
        stirred = true
        if (cell != null) scene.stillCells.wake(this)
    }

    // Its sphere in the world for the renderer's culling, and what it was computed from.
    internal val cullCache = FloatArray(4)
    internal var cullPosition: Vec3? = null
    internal var cullRotation: Quat? = null
    internal var cullScale = Float.NaN
    internal var cullMesh: Mesh? = null

    /** Lighter meshes for far away: from each distance on, its mesh (sorted, nearest first). */
    private var lods: List<Pair<Float, Mesh>> = emptyList()

    /**
     * Draws [mesh] instead of its own from [from] metres away from the camera on: a lighter version of it,
     * fewer triangles for the same look from afar. Several can follow each other; not for animated models.
     */
    fun lod(mesh: Mesh, from: Number): Node {
        require(parts.isEmpty()) { "lod(): an animated model draws its parts, it cannot change its mesh with the distance" }
        lods = (lods + (from.toFloat() to mesh)).sortedBy { it.first }
        stir()
        return this
    }

    /** Its levels of detail' meshes, nearest first (none for most nodes). */
    internal val lodMeshes: List<Pair<Float, Mesh>> get() = lods

    /** [lod] with a model's file (its rest pose as one mesh). */
    fun lod(path: String, from: Number): Node = lod(scene.flatModel(path), from)

    /** The mesh to draw from [eye]: its own, or the level of detail for that distance. */
    internal fun meshAt(eye: Vec3): Mesh {
        if (lods.isEmpty()) return mesh
        val dx = position.x - eye.x; val dy = position.y - eye.y; val dz = position.z - eye.z
        val d2 = dx * dx + dy * dy + dz * dz
        var m = mesh
        for ((d, l) in lods) if (d2 >= d * d) m = l else break
        return m
    }

    /** The file a model node came from (for messages and the debug panel). */
    internal var source: String? = null
        internal set
    /** Plays the model's clips; null when the node is not an animated model. */
    var animator: Animator? = null
        internal set(v) { if (v !== field) stir(); field = v; if (v != null && this !is Player) scene.animates(this) }
    /** The model it was loaded from, if any. */
    var model: Model? = null
        internal set
    /** Its rigid body once [dynamic], [solid] or [kinematic] was called. */
    var body: Body? = null
        internal set

    internal var shape: Shape? = null            // the collision shape of a primitive
    internal var bodyOffset: Vec3 = Vec3.ZERO     // from the node's origin to its body's centre, in node space
    internal var isShadow = false                // a blob shadow, drawn blended after the opaque nodes
    internal var water: WaterLook? = null        // a water surface, drawn by the water shader
    internal var isSky = false                   // the sky dome, drawn by the sky shader
    internal var removed = false
    internal var fxY = Float.NaN                 // height at the last frame, to see it fall into water
    internal val behaviours = ArrayList<Node.(Float) -> Unit>()
    /** The physics water this node's water stands for ([Scene.water]), if it carries things. */
    var waterVolume: sillage.physics.WaterVolume? = null
        internal set
    /** Its rank among the nodes made by the program (behaviours run in this order). */
    internal val serial: Long = nextSerial++

    /** Rotation about the vertical axis, in degrees (0 faces −Z, 90 faces −X). */
    var yaw: Float
        get() { val f = rotation.rotate(Vec3(0f, 0f, -1f)); return sillage.math.toDegrees(atan2(-f.x, -f.z).toDouble()).toFloat() }
        set(v) { rotation = Quat.rotationY(v * PI.toFloat() / 180f) }

    /** Its placement as a matrix: translation × rotation × scale. */
    fun matrix(): Mat4 = Mat4.trs(position, rotation, Vec3(scale, scale, scale))

    /** Places it: `at(0, 1.5, -2)`. */
    fun at(x: Number, y: Number, z: Number): Node = at(vec(x, y, z))
    fun at(p: Vec3): Node { position = p; body?.position = p + rotation.rotate(bodyOffset); return this }

    /** Turns it by angles in degrees, about X (pitch), then Y (yaw), then Z (roll), from its current rotation. */
    fun rotate(x: Number = 0, y: Number = 0, z: Number = 0): Node {
        val d = PI.toFloat() / 180f
        rotation = (Quat.axisAngle(Vec3.Y, y.toFloat() * d) * Quat.axisAngle(Vec3.X, x.toFloat() * d) *
            Quat.axisAngle(Vec3.Z, z.toFloat() * d) * rotation).normalized()
        body?.rotation = rotation
        return this
    }

    /** Sets the size factor (before [dynamic] or [solid]). */
    fun scaled(factor: Number): Node { scale = factor.toFloat(); return this }

    /**
     * Sets it down on the solid ground at ([x], [z]), [lift] metres above it: its lowest point (as it is
     * turned and scaled now) rests on the ground. Works before or after [dynamic] and [solid].
     */
    fun onGround(x: Number, z: Number, lift: Number = 0): Node {
        val (lo, hi) = localBounds() ?: (Vec3.ZERO to Vec3.ZERO)
        var bottom = Float.MAX_VALUE
        for (i in 0 until 8) {
            val c = Vec3(if (i and 1 == 0) lo.x else hi.x, if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z)
            bottom = kotlin.math.min(bottom, rotation.rotate(c * scale).y)
        }
        return at(Vec3(x.toFloat(), scene.groundHeight(x, z) - bottom + lift.toFloat() + .005f, z.toFloat()))
    }

    /** Its mesh's bounds before scaling and turning (an animated model: of its rest pose), or null if it draws nothing. */
    private fun localBounds(): Pair<Vec3, Vec3>? {
        if (mesh.triangleCount > 0) return mesh.bounds
        val m = model ?: return null
        return m.flatten().takeIf { it.triangleCount > 0 }?.bounds
    }

    /** Moves it to [target] in [over] seconds (after [after]), the way [ease] says; a kinematic body follows smoothly. */
    fun moveTo(target: Vec3, over: Number, ease: Ease = Ease.IN_OUT, after: Number = 0): Tween {
        var from: Vec3? = null
        return scene.addTween(over, after, ease, { k ->
            val a = from ?: position.also { from = it }
            val p = a + (target - a) * k
            if (body?.type == sillage.physics.BodyType.KINEMATIC) position = p else at(p)
        }) {}
    }

    /** Changes its size factor to [factor] in [over] seconds (the look only: a body keeps its size). */
    fun scaleTo(factor: Number, over: Number, ease: Ease = Ease.IN_OUT, after: Number = 0): Tween {
        val b = factor.toFloat(); var a = Float.NaN
        return scene.addTween(over, after, ease, { k -> if (a.isNaN()) a = scale; scale = a + (b - a) * k }) {}
    }

    // ------------------------------------------------------------------ physics in one line

    /**
     * Makes it a moving rigid body: it falls, rolls, bounces, can be pushed. The shape is the primitive's
     * (box, sphere, capsule, cylinder as a box) or, for a model, its bounding box; give [shape] (in metres,
     * not scaled with the node) to choose.
     * [mass] defaults from the volume and [density] (kg/m³; the default is an empty wooden crate's: a 1 m box
     * weighs 50 kg and a player pushes it, see [sillage.physics.Character.pushForce]). [impact] is the sound it makes when it hits
     * something: [Impact.AUTO] picks a wooden knock for boxes and a rubber bounce for balls. [damping]
     * slows it in the air (a light beach ball), [rollingResistance] stops a rolling ball (null: engine default).
     */
    fun dynamic(mass: Number? = null, density: Number = 50, bounce: Number = 0, friction: Number = .6,
                shape: Shape? = null, surface: String = "wood", impact: Impact = Impact.AUTO,
                damping: Number = 0, angularDamping: Number = 0, rollingResistance: Number? = null): Node {
        scene.makeDynamic(this, shape, mass?.toFloat(), density.toFloat(), bounce.toFloat(), friction.toFloat(), surface, impact,
            rollingResistance?.toFloat(), damping.toFloat(), angularDamping.toFloat())
        return this
    }

    /**
     * Makes it solid and still: walls, floors, buildings. A primitive collides with its shape; a model
     * collides with its very triangles (those of [materials] only, if given), each with the surface
     * named after its material (grass, sand, wood…, see [Material.named]). [blocksCamera] = false lets
     * the camera pass through thin things (tree trunks, posts).
     */
    fun solid(vararg materials: String, blocksCamera: Boolean = true, surface: Material? = null): Node {
        scene.makeSolid(this, materials.toList(), blocksCamera, surface)
        return this
    }

    /**
     * Adds an extra collision [shape] to it, at [offset] in its space: a house's volume, a mast. With
     * [part] (the name of a node of an animated model), the shape follows that part as it moves (a boat
     * on the waves, a lift); one can stand on it.
     */
    fun collider(shape: Shape, offset: Vec3 = Vec3.ZERO, part: String? = null, surface: String = "wood", blocksCamera: Boolean = true): Body =
        scene.addCollider(this, shape, offset, part, surface, blocksCamera)

    /** Makes it a body moved by the game (a door, a lift): set [position] and [rotation], it pushes what it meets. */
    fun kinematic(): Node { scene.makeKinematic(this); return this }

    // ------------------------------------------------------------------ joints
    // Joints tie this node (dynamic first) to [to], or to the world when [to] is null or has no body of
    // its own (a solid model). Points and axes are in world coordinates, where things are now.

    private fun joinable(): Body = requireNotNull(body) { "Make it dynamic() before joining it" }

    /** Hangs it from [anchor]: it swings freely about that point (a lantern, a punching bag). */
    fun hangFrom(anchor: Vec3, to: Node? = null): BallJoint = scene.physics.addBall(to?.body, joinable(), anchor)

    /**
     * A hinge at [anchor], turning about [axis] (a door, a see-saw, a wheel), between [min] and [max]
     * degrees from where it is now when both are given; motor and spring on the result.
     */
    fun hinge(anchor: Vec3, axis: Vec3, to: Node? = null, min: Number? = null, max: Number? = null): HingeJoint =
        scene.physics.addHinge(to?.body, joinable(), anchor, axis).also { j ->
            if (min != null && max != null) j.limits(min.toFloat() * PI.toFloat() / 180f, max.toFloat() * PI.toFloat() / 180f)
        }

    /** Slides along [axis] without turning (a drawer, a lift), between [min] and [max] metres when both are given; motor and spring on the result. */
    fun slider(axis: Vec3, to: Node? = null, min: Number? = null, max: Number? = null): SliderJoint =
        scene.physics.addSlider(to?.body, joinable(), axis).also { j -> if (min != null && max != null) j.limits(min.toFloat(), max.toFloat()) }

    /**
     * A shoulder or a hip ([to]'s joint at [anchor], or the world's): it turns freely within a cone of [swing]
     * degrees about [axis] ([swing2] across, for an elliptic one) and twists about it by up to [twist]
     * degrees each way. For rag dolls, lamps on an arm, a fishing rod's tip.
     */
    fun cone(anchor: Vec3, axis: Vec3, to: Node? = null, swing: Number = 45, swing2: Number = swing, twist: Number = 30): sillage.physics.ConeTwistJoint {
        val d = PI.toFloat() / 180f
        return scene.physics.addConeTwist(to?.body, joinable(), anchor, axis, swing.toFloat() * d, swing2.toFloat() * d, -twist.toFloat() * d, twist.toFloat() * d)
    }

    /**
     * A convex hull of its mesh's points (as it is scaled now), for `dynamic(shape = …)`: a rock or a bevelled
     * crate that rolls and stacks as its shape says. At most [maxVertices] points are kept.
     */
    fun hullShape(maxVertices: Int = 32): sillage.physics.ConvexHull {
        val m = if (mesh.triangleCount > 0) mesh else model?.flatten() ?: error("hullShape(): this node draws no mesh")
        val v = m.vertices
        val pts = FloatArray(v.size / 3)
        for (k in 0 until v.size / 9) { pts[3 * k] = v[9 * k] * scale; pts[3 * k + 1] = v[9 * k + 1] * scale; pts[3 * k + 2] = v[9 * k + 2] * scale }
        return sillage.physics.ConvexHull(pts, maxVertices)
    }

    /** Glued to [to] (or to the world) as they are now. */
    fun glue(to: Node? = null): FixedJoint = scene.physics.addFixed(to?.body, joinable())

    /** A rope from [from] (on [to], or in the world) to [at] on this node, [length] metres long (as they are now by default). */
    fun rope(from: Vec3, at: Vec3 = position, to: Node? = null, length: Number? = null): DistanceJoint =
        scene.physics.addRope(to?.body, from, joinable(), at, length?.toFloat())

    /** A spring of [hertz] Hz from [from] (on [to], or in the world) to [at] on this node, resting at its present length. */
    fun spring(from: Vec3, hertz: Number, at: Vec3 = position, to: Node? = null, dampingRatio: Number = .5): DistanceJoint =
        scene.physics.addSpring(to?.body, from, joinable(), at, hertz.toFloat(), dampingRatio.toFloat())

    /**
     * Plays [sound] from it (or from its [part], for an animated model), and the sound follows it while it
     * plays: a gull's cry from the gull, an engine from its car, rising as it comes and falling as it goes.
     * Null without sound.
     */
    fun sound(sound: sillage.audio.Sound, part: String? = null, volume: Number = 1, pitch: Number = 1, range: Number = 40,
              loop: Boolean = false): sillage.audio.Voice? =
        scene.sounds.attach(this, part, sound, volume.toFloat(), pitch.toFloat(), range.toFloat(), loop)

    /** A soft round shadow on the ground under it ([radius] in metres, from its size if not given). */
    fun shadow(radius: Number? = null): Node { scene.addShadow(this, radius?.toFloat()); return this }

    // ------------------------------------------------------------------ behaviour

    /** Plays a clip of its animated model (as named in Blender). */
    fun play(clip: String, loop: Boolean = true, speed: Number = 1, fade: Number = .15): Node {
        requireNotNull(animator) { "This node has no animations (not an animated model)" }.play(clip, loop, fade.toFloat(), speed.toFloat())
        return this
    }

    /** Runs [block] every frame with the frame's duration in seconds. */
    fun onUpdate(block: Node.(dt: Float) -> Unit): Node { behaviours.add(block); scene.behaves(this); return this }

    /** Turns on itself, [degreesPerSecond] about [axis]. */
    fun spin(degreesPerSecond: Number = 90, axis: Vec3 = Vec3.Y): Node {
        val speed = degreesPerSecond.toFloat() * PI.toFloat() / 180f
        return onUpdate { dt ->
            // (Quat.axisAngle(axis, speed * dt) * rotation).normalized(), written out: one quaternion made, not three.
            val angle = speed * dt
            val s = kotlin.math.sin(angle * .5f); val c = kotlin.math.cos(angle * .5f)
            val ax = axis.x * s; val ay = axis.y * s; val az = axis.z * s
            val q = rotation
            val x = c * q.x + ax * q.w + ay * q.z - az * q.y
            val y = c * q.y - ax * q.z + ay * q.w + az * q.x
            val z = c * q.z + ax * q.y - ay * q.x + az * q.w
            val w = c * q.w - ax * q.x - ay * q.y - az * q.z
            val l = kotlin.math.sqrt(x * x + y * y + z * z + w * w)
            rotation = if (l == 0f) Quat.IDENTITY else Quat(x / l, y / l, z / l, w / l)
        }
    }

    /** Takes it out of the scene (and its body out of the physics). */
    fun remove() = scene.remove(this)

    /**
     * A lamp that follows this node, at [offset] in its space: see [Scene.light]. Give a lantern, a window
     * or a campfire its light.
     */
    fun light(color: Color = Color(1f, .72f, .38f), intensity: Number = 1.5, radius: Number = 6, offset: Vec3 = Vec3.ZERO,
              onlyAtNight: Boolean = false, flicker: Number = 0): PointLight =
        scene.lightOn(this, PointLight(color, intensity.toFloat(), radius.toFloat(), offset, this, onlyAtNight, flicker.toFloat()))

    /**
     * Sways in the wind ([Lighting.wind]): its points move up to [metres] in a breeze of strength 1, those
     * near the ground less (with the square of their height above it, up to [reach] metres). For a model,
     * only the triangles of [materials] (a trailing `*` matches the start of a name: `"flower*"`). Grass,
     * flowers, bushes, flags. The node gets its own copy of its mesh.
     */
    fun sway(metres: Number = .05, vararg materials: String, reach: Number = 1): Node {
        scene.sway(this, metres.toFloat(), reach.toFloat(), materials); return this }

    /**
     * Shines with its own light, [color] × [intensity] (above 1 it glows, see [Lighting.glow]); with
     * [atNight], only as night falls. For a model, only the triangles of [materials]: lit windows, a lamp,
     * embers. It lights nothing around it: add a [light] for that.
     */
    fun glow(color: Color, vararg materials: String, intensity: Number = 1, atNight: Boolean = false): Node {
        scene.glow(this, color, intensity.toFloat(), atNight, materials); return this }

    /**
     * Lays [texture] on it (null: none), repeating every [tile] metres with [strength] (the texture's own
     * by default). For a model, only the triangles of [materials]. Models already get textures from their
     * material names: see [Scene.autoTextures].
     */
    fun texture(texture: Texture?, vararg materials: String, tile: Number? = null, strength: Number? = null): Node {
        scene.texture(this, texture, tile?.toFloat(), strength?.toFloat(), materials); return this }

    /** Particles flowing from this node, at [offset] in its space: see [Scene.emit]. */
    fun emit(style: ParticleStyle, rate: Number = 20, offset: Vec3 = Vec3.ZERO): Emitter =
        scene.emitOn(Emitter(style, rate.toFloat(), offset, this))
}

/** The sound a body makes when it hits something. */
enum class Impact { AUTO, WOOD, RUBBER, SILENT }

private var nextSerial = 0L
