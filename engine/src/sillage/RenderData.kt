package sillage

// What the scene hands to whatever draws it (Vulkan on a desktop, WebGL in a page, OpenGL ES on a phone).

/** Side of the shadow map in texels. */
internal const val SHADOW_TEXELS = 2048
/** Point lights handed to the shaders each frame (the nearest to the camera). */
internal const val MAX_LIGHTS = 16
/** Shadow squares one inside the other, each with its layer in the shadow map (see Lighting.shadowCascades). */
internal const val CASCADES = 3
/** Floats per particle drawn: centre and size (negative: it glows), colour and opacity. */
internal const val PARTICLE_FLOATS = 8

/** What a frame draws besides the nodes: the point lights near the camera and the particles. */
internal class FrameExtras {
    /** Per light: position, radius, colour × intensity, unused (8 floats). */
    val lights = FloatArray(8 * MAX_LIGHTS)
    var lightCount = 0
    /** Per particle [PARTICLE_FLOATS] floats. */
    var particles = FloatArray(PARTICLE_FLOATS * 256)
    var particleCount = 0
    /** Debug lines: two vertices per segment, [LINE_FLOATS] floats each (position, colour and opacity). */
    var lines = FloatArray(LINE_FLOATS * 1024)
    var lineVertexCount = 0
}

/** Floats per debug line vertex: position, colour and opacity. */
internal const val LINE_FLOATS = 7

/**
 * What draws a scene, whatever is under it. The loop hands it, each frame, the nodes to draw (already
 * chosen by the scene: [Scene.drawList]), the camera, the interface's triangles, the light and the extras.
 * The desktop's Vulkan renderer does the same job with its own entry points (windows, captures to files).
 */
internal interface Renderer {
    /** Size of the picture in pixels. */
    val width: Int
    val height: Int
    /** The surface changed size (a window resized, a phone turned). */
    fun resize(width: Int, height: Int)

    /**
     * Draws one frame. [viewProjection]: the camera (as [sillage.math.Mat4.perspective] × view gives it: depth 0 to 1,
     * y down, as Vulkan); [eye] and [focus]: where the camera is and what it is about (the shadows are
     * drawn around it; null: no cast shadows this frame); [hidden]: a node not to draw (the player seen
     * through its own eyes). [capture], when given, receives the finished picture: width, height, RGB bytes
     * from the top row down.
     */
    fun draw(nodes: List<Node>, viewProjection: sillage.math.Mat4, hud: HudMesh, eye: sillage.math.Vec3, time: Float, hidden: Node?,
             focus: sillage.math.Vec3?, lighting: Lighting, extras: FrameExtras?, capture: ((Int, Int, ByteArray) -> Unit)? = null)

    /** A new scene: forget the meshes and textures of the last one. */
    fun forget()
    fun close()

    /** Whether what the camera cannot see is left out (true by default). */
    var cull: Boolean
    /** Batches drawn and left out in the last frame. */
    val drawn: Int
    val culled: Int
}
