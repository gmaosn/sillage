package sillage.gl

/**
 * The part of OpenGL ES 3.0 that the engine's second renderer uses, the same calls as WebGL 2: a page
 * implements it over its canvas, a phone over its screen, a desktop over EGL. Objects are numbers (0: none),
 * as in OpenGL; constants are in [GL]. Every call is made on the thread that owns the context.
 */
interface Gl {
    // ---- state
    fun viewport(x: Int, y: Int, width: Int, height: Int)
    fun scissor(x: Int, y: Int, width: Int, height: Int)
    fun enable(capability: Int)
    fun disable(capability: Int)
    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clearDepth(depth: Float)
    fun clear(mask: Int)
    fun depthFunc(function: Int)
    fun depthMask(write: Boolean)
    fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean)
    fun blendFuncSeparate(srcColor: Int, dstColor: Int, srcAlpha: Int, dstAlpha: Int)
    fun polygonOffset(factor: Float, units: Float)

    // ---- programs
    /** Compiles and links; throws IllegalStateException with the compiler's log when a shader is refused. */
    fun createProgram(vertexSource: String, fragmentSource: String): Int
    fun deleteProgram(program: Int)
    fun useProgram(program: Int)
    /** −1 when the program has no such uniform (optimised away). */
    fun uniformLocation(program: Int, name: String): Int
    fun uniform1i(location: Int, v: Int)
    fun uniform1f(location: Int, v: Float)
    fun uniform2f(location: Int, x: Float, y: Float)
    fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float)
    /** [count] vec4 from [values] at [from]. */
    fun uniform4fv(location: Int, values: FloatArray, from: Int, count: Int)
    /** A column-major 4 × 4 matrix: 16 floats of [m] from [from]. */
    fun uniformMatrix4(location: Int, m: FloatArray, from: Int = 0)
    /** Ties the uniform block [blockName] of [program] to the binding point [binding] (see [bindBufferBase]); nothing if it has no such block. */
    fun uniformBlockBinding(program: Int, blockName: String, binding: Int)

    // ---- buffers
    fun createBuffer(): Int
    fun deleteBuffer(buffer: Int)
    fun bindBuffer(target: Int, buffer: Int)
    /** Storage of [bytes] bytes, contents undefined. */
    fun bufferData(target: Int, bytes: Int, usage: Int)
    /** Storage holding [count] floats of [data] from [from]. */
    fun bufferData(target: Int, data: FloatArray, from: Int, count: Int, usage: Int)
    /** Writes [count] floats of [data] from [from] at [offsetBytes] of the bound buffer. */
    fun bufferSubData(target: Int, offsetBytes: Int, data: FloatArray, from: Int, count: Int)
    fun bindBufferBase(target: Int, index: Int, buffer: Int)

    // ---- vertex arrays
    fun createVertexArray(): Int
    fun deleteVertexArray(array: Int)
    fun bindVertexArray(array: Int)
    fun enableVertexAttribArray(index: Int)
    fun disableVertexAttribArray(index: Int)
    /** Float attributes from the bound ARRAY_BUFFER. */
    fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int)
    fun vertexAttribDivisor(index: Int, divisor: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)
    fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int)

    // ---- textures
    fun createTexture(): Int
    fun deleteTexture(texture: Int)
    fun activeTexture(unit: Int)
    fun bindTexture(target: Int, texture: Int)
    fun texParameteri(target: Int, name: Int, value: Int)
    fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int)
    fun texStorage3D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int, depth: Int)
    /** Bytes of [data] from [from] (unpack alignment 1) into a rectangle of level [level]. */
    fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteArray, from: Int = 0)
    fun texSubImage3D(target: Int, level: Int, x: Int, y: Int, z: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, data: ByteArray, from: Int = 0)
    fun generateMipmap(target: Int)

    // ---- framebuffers
    fun createFramebuffer(): Int
    fun deleteFramebuffer(framebuffer: Int)
    /** 0: the screen (the canvas, the window's surface). */
    fun bindFramebuffer(target: Int, framebuffer: Int)
    fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int)
    fun framebufferTextureLayer(target: Int, attachment: Int, texture: Int, level: Int, layer: Int)
    fun framebufferRenderbuffer(target: Int, attachment: Int, renderbuffer: Int)
    fun createRenderbuffer(): Int
    fun deleteRenderbuffer(renderbuffer: Int)
    fun bindRenderbuffer(renderbuffer: Int)
    /** Storage for the bound renderbuffer; [samples] 0 or 1: not multisampled. */
    fun renderbufferStorage(samples: Int, internalFormat: Int, width: Int, height: Int)
    fun checkFramebufferStatus(target: Int): Int
    fun blitFramebuffer(srcX0: Int, srcY0: Int, srcX1: Int, srcY1: Int, dstX0: Int, dstY0: Int, dstX1: Int, dstY1: Int, mask: Int, filter: Int)
    /** RGBA bytes of the bound READ framebuffer's rectangle into [out] (4 per pixel, bottom row first). */
    fun readPixels(x: Int, y: Int, width: Int, height: Int, out: ByteArray)

    // ---- what this context can do
    /** Whether floating-point images can be drawn into (EXT_color_buffer_float or half_float; always on OpenGL ES 3.2). */
    val floatTargets: Boolean
    val maxSamples: Int
    fun getError(): Int
}

/** OpenGL ES 3.0 constants (the same numbers in WebGL 2). */
object GL {
    const val DEPTH_BUFFER_BIT = 0x100; const val COLOR_BUFFER_BIT = 0x4000
    const val TRIANGLES = 4; const val LINES = 1
    const val DEPTH_TEST = 0x0B71; const val BLEND = 0x0BE2; const val SCISSOR_TEST = 0x0C11; const val CULL_FACE = 0x0B44
    const val POLYGON_OFFSET_FILL = 0x8037; const val SAMPLE_ALPHA_TO_COVERAGE = 0x809E
    const val LESS = 0x0201; const val LEQUAL = 0x0203; const val ALWAYS = 0x0207
    const val ZERO = 0; const val ONE = 1; const val SRC_ALPHA = 0x0302; const val ONE_MINUS_SRC_ALPHA = 0x0303
    const val ARRAY_BUFFER = 0x8892; const val UNIFORM_BUFFER = 0x8A11
    const val STATIC_DRAW = 0x88E4; const val DYNAMIC_DRAW = 0x88E8; const val STREAM_DRAW = 0x88E0
    const val FLOAT = 0x1406; const val UNSIGNED_BYTE = 0x1401; const val HALF_FLOAT = 0x140B; const val UNSIGNED_SHORT = 0x1403; const val UNSIGNED_INT = 0x1405
    const val TEXTURE_2D = 0x0DE1; const val TEXTURE_2D_ARRAY = 0x8C1A; const val TEXTURE0 = 0x84C0
    const val TEXTURE_MIN_FILTER = 0x2801; const val TEXTURE_MAG_FILTER = 0x2800; const val TEXTURE_WRAP_S = 0x2802; const val TEXTURE_WRAP_T = 0x2803
    const val TEXTURE_COMPARE_MODE = 0x884C; const val TEXTURE_COMPARE_FUNC = 0x884D; const val COMPARE_REF_TO_TEXTURE = 0x884E
    const val TEXTURE_MAX_LEVEL = 0x813D
    const val NEAREST = 0x2600; const val LINEAR = 0x2601; const val LINEAR_MIPMAP_LINEAR = 0x2703; const val NEAREST_MIPMAP_NEAREST = 0x2700
    const val REPEAT = 0x2901; const val CLAMP_TO_EDGE = 0x812F; const val MIRRORED_REPEAT = 0x8370
    const val RGBA = 0x1908; const val RED = 0x1903; const val RG = 0x8227; const val DEPTH_COMPONENT = 0x1902
    const val RGBA8 = 0x8058; const val SRGB8_ALPHA8 = 0x8C43; const val R8 = 0x8229; const val RGBA16F = 0x881A; const val R11F_G11F_B10F = 0x8C3A
    const val DEPTH_COMPONENT16 = 0x81A5; const val DEPTH_COMPONENT24 = 0x81A6; const val DEPTH_COMPONENT32F = 0x8CAC
    const val FRAMEBUFFER = 0x8D40; const val READ_FRAMEBUFFER = 0x8CA8; const val DRAW_FRAMEBUFFER = 0x8CA9; const val RENDERBUFFER = 0x8D41
    const val COLOR_ATTACHMENT0 = 0x8CE0; const val DEPTH_ATTACHMENT = 0x8D00; const val FRAMEBUFFER_COMPLETE = 0x8CD5
    const val NO_ERROR = 0
}
