package sillage

// What the scene hands to whatever draws it (Vulkan on a desktop, WebGL in a page, OpenGL ES on a phone).

/** Side of the shadow map in texels. */
internal const val SHADOW_TEXELS = 2048
/** Point lights handed to the shaders each frame at most (the nearest to the camera first; frame.glsl). */
internal const val MAX_LIGHTS = 128
/**
 * Up to this many lamps on screen, every point looks at every one of them, as it always did; with more, each
 * looks only at those of its cluster of the light grid (see [RenderBatches.lights]).
 */
internal const val LOOP_LIGHTS = 16
/** The light grid: tiles across and down the picture, slices of distance from the eye, lamps per cluster at most. */
internal const val GRID_X = 16
internal const val GRID_Y = 10
internal const val GRID_Z = 16
internal const val GRID_LAMPS = 15
/** Lamps that may cast shadows at once (each a cube of six faces, a layer each), and the side of a face, at the high quality. */
internal const val LAMP_SHADOWS = 4
internal const val LAMP_TEXELS = 512
/** Shadow squares one inside the other, each with its layer in the shadow map (see Lighting.shadowCascades). */
internal const val CASCADES = 3
/** Floats per particle drawn: centre and size (negative: it glows), colour and opacity. */
internal const val PARTICLE_FLOATS = 8

/** What a frame draws besides the nodes: the point lights near the camera and the particles. */
internal class FrameExtras {
    /** Per light: position, radius, colour × intensity, unused (8 floats). */
    val lights = FloatArray(8 * MAX_LIGHTS)
    /** Per light, which lamp it is ([PointLight.id]): a lamp keeps its shadow cube from frame to frame. */
    val lightIds = IntArray(MAX_LIGHTS)
    var lightCount = 0
    /** The renderer drew things blended with the particles last frame: the particles must come sorted even if they all glow. */
    var sortParticles = false
    /** Per particle [PARTICLE_FLOATS] floats. */
    var particles = FloatArray(PARTICLE_FLOATS * 256)
    var particleCount = 0
    /** Debug lines: two vertices per segment, [LINE_FLOATS] floats each (position, colour and opacity). */
    var lines = FloatArray(LINE_FLOATS * 1024)
    var lineVertexCount = 0
}

/** Floats per debug line vertex: position, colour and opacity. */
internal const val LINE_FLOATS = 7

/** How far particle [i] of the frame is from [eye] (the particles come farthest first): where the blended copies go among them. */
internal fun particleDistance(e: FrameExtras, i: Int, eye: sillage.math.Vec3): Float {
    val o = PARTICLE_FLOATS * i
    val dx = e.particles[o] - eye.x; val dy = e.particles[o + 1] - eye.y; val dz = e.particles[o + 2] - eye.z
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}

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

/**
 * How much the renderer spends on a picture: the side of the shadow maps and the samples of the anti-aliasing.
 * Chosen once from the graphics card's name — a Raspberry Pi's (« V3D ») gets [MEDIUM] — unless the launch
 * says otherwise: `--quality low|medium|high`, the setting `sillage.quality` or the variable SILLAGE_QUALITY.
 */
internal class RenderQuality private constructor(val name: String, val shadowTexels: Int, val samples: Int,
                                                  /** Lamps with a shadow cube at once, and the side of each face. */
                                                  val lampShadows: Int, val lampTexels: Int) {
    companion object {
        val HIGH = RenderQuality("high", SHADOW_TEXELS, 4, LAMP_SHADOWS, LAMP_TEXELS)
        val MEDIUM = RenderQuality("medium", SHADOW_TEXELS / 2, 4, 2, LAMP_TEXELS / 2)
        val LOW = RenderQuality("low", SHADOW_TEXELS / 2, 1, 1, LAMP_TEXELS / 2)

        fun choose(gpuName: String): RenderQuality {
            val asked = (sillage.io.Storage.setting("sillage.quality") ?: sillage.io.Storage.environment("SILLAGE_QUALITY"))?.lowercase()
            return when (asked) {
                "high" -> HIGH; "medium" -> MEDIUM; "low" -> LOW
                null, "", "auto" -> if ("V3D" in gpuName) MEDIUM else HIGH
                else -> throw IllegalArgumentException("Quality is low, medium or high (got $asked)")
            }
        }
    }
}
