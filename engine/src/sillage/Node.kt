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
    var name: String? = null
    open var position: Vec3 = Vec3.ZERO
    var rotation: Quat = Quat.IDENTITY
    /** Uniform size factor. Set it before [dynamic] or [solid]: the collision shape is sized then. */
    var scale: Float = 1f
    var visible: Boolean = true
    /** Whether it casts a shadow on what is below it (see [Lighting.shadows]); it receives shadows either way. */
    var castsShadow: Boolean = true
    /** Free label for the game (a role, a score…); the engine never reads it. */
    var tag: Any? = null

    /** Parts of an animated model, each drawn with the node's [matrix] times the part's pose. */
    var parts: List<Part> = emptyList()
    /** The file a model node came from (for messages and the debug panel). */
    internal var source: String? = null
        internal set
    /** Plays the model's clips; null when the node is not an animated model. */
    var animator: Animator? = null
        internal set
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

    /** Rotation about the vertical axis, in degrees (0 faces −Z, 90 faces −X). */
    var yaw: Float
        get() { val f = rotation.rotate(Vec3(0f, 0f, -1f)); return Math.toDegrees(atan2(-f.x, -f.z).toDouble()).toFloat() }
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

    // ------------------------------------------------------------------ physics in one line

    /**
     * Makes it a moving rigid body: it falls, rolls, bounces, can be pushed. The shape is the primitive's
     * (box, sphere, capsule, cylinder as a box) or, for a model, its bounding box; give [shape] (in metres,
     * not scaled with the node) to choose.
     * [mass] defaults from the volume and [density] (kg/m³). [impact] is the sound it makes when it hits
     * something: [Impact.AUTO] picks a wooden knock for boxes and a rubber bounce for balls. [damping]
     * slows it in the air (a light beach ball), [rollingResistance] stops a rolling ball (null: engine default).
     */
    fun dynamic(mass: Number? = null, density: Number = 400, bounce: Number = 0, friction: Number = .6,
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

    /** A hinge at [anchor], turning about [axis] (a door, a see-saw, a wheel); limits, motor, spring on the result. */
    fun hinge(anchor: Vec3, axis: Vec3, to: Node? = null): HingeJoint = scene.physics.addHinge(to?.body, joinable(), anchor, axis)

    /** Slides along [axis] without turning (a drawer, a lift); limits, motor, spring on the result. */
    fun slider(axis: Vec3, to: Node? = null): SliderJoint = scene.physics.addSlider(to?.body, joinable(), axis)

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
    fun onUpdate(block: Node.(dt: Float) -> Unit): Node { behaviours.add(block); return this }

    /** Turns on itself, [degreesPerSecond] about [axis]. */
    fun spin(degreesPerSecond: Number = 90, axis: Vec3 = Vec3.Y): Node {
        val speed = degreesPerSecond.toFloat() * PI.toFloat() / 180f
        return onUpdate { dt -> rotation = (Quat.axisAngle(axis, speed * dt) * rotation).normalized() }
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
