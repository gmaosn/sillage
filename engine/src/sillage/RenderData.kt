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
