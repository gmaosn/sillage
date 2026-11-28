package sillage.gl

import sillage.Batch
import sillage.CASCADES
import sillage.EFFECT_FLOATS
import sillage.FRAME_FLOATS
import sillage.FrameExtras
import sillage.GRID_WIDTH
import sillage.GRID_Z
import sillage.HudMesh
import sillage.LAMP_SHADOWS
import sillage.LAMP_TEXELS
import sillage.LINE_FLOATS
import sillage.Lighting
import sillage.Mesh
import sillage.Node
import sillage.PARTICLE_FLOATS
import sillage.RenderBatches
import sillage.Renderer
import sillage.SHADOW_TEXELS
import sillage.SURFACE_FLOATS
import sillage.particleDistance
import sillage.Texture
import sillage.WaterLook
import sillage.math.Mat4
import sillage.math.Vec3
import sillage.ui.GlyphAtlas
import sillage.ui.HUD_FLOATS
import kotlin.math.roundToInt

/** Levels of the glow: half the picture's size, then each half the last. */
private const val BLOOM_LEVELS = 5
// Binding points of the uniform blocks: the frame's (frame.glsl), what Vulkan passes as push constants to
// the scene's shaders, to the picture's passes and to the interface.
private const val BLOCK_FRAME = 0
private const val BLOCK_PUSH = 1
private const val BLOCK_POST = 2
private const val BLOCK_HUD = 3
/** The shadow maps are drawn by the vertex shader alone; OpenGL ES still wants a fragment shader. */
private const val NOTHING_FRAG = "#version 300 es\nprecision highp float;\nvoid main() {}\n"

/**
 * The engine's second renderer, on OpenGL ES 3.0 and WebGL 2 through [Gl]: the same passes as the Vulkan
 * renderer, with the same shaders ([GlShaders], made from the Vulkan sources) and the same choices of what
 * is drawn ([RenderBatches]). A frame: the shadow maps (three layers of a depth texture array, the farther
 * ones drawn again less often); the scene in linear light, 16-bit float with the distance to the eye in
 * alpha, multisampled in renderbuffers then resolved into a texture; the glow, down and up a chain of
 * smaller images; and the finished picture (ink, glow, tone, vignette) with the interface over it.
 *
 * Clip space stays Vulkan's in the shaders (y down, depth 0 to 1, remapped at the end of each vertex
 * shader): every image is therefore drawn with its top row first in memory, as Vulkan's are, so all the
 * texture coordinates and pixel positions of the shaders mean the same. The picture is turned the right
 * way up once, when it is copied to the screen ([present]); a capture reads it as it is.
 *
 * Every call must come from the thread that owns the context of [gl].
 */
internal class GlRenderer(private val gl: Gl, width: Int, height: Int, shadows: Boolean = true) : Renderer {
    override var width = width; private set
    override var height = height; private set
    /** Samples per pixel of the scene (4 when the context can, as the Vulkan renderer). */
    val samples: Int = if (gl.maxSamples >= 4) 4 else 1
    /** Whether the finished picture is copied to the screen (framebuffer 0), turned the right way up. False without a screen. */
    var present = true
    /**
     * Whether a frame waits for the textures still being painted (true: the same frame from run to run;
     * false: they show neutral for a few frames, the first picture comes sooner). A capture always waits.
     */
    var waitForTextures = false

    private val batches = RenderBatches(::prepare)
    override var cull: Boolean
        get() = batches.cull
        set(v) { batches.cull = v }
    override val drawn: Int get() = batches.drawn
    override val culled: Int get() = batches.culled

    // ---- programs
    private val programs = ArrayList<Int>()
    private val toon: Int; private val water: Int; private val sky: Int; private val ombre: Int
    private val particles: Int; private val lines: Int; private val linesXray: Int
    private val shadow: Int; private val shadowCut: Int; private val lampShadow: Int; private val lampShadowCut: Int
    private val bright: Int; private val down: Int; private val up: Int; private val composite: Int; private val hudProgram: Int

    // ---- what the shaders read each frame
    private val frameData = FloatArray(FRAME_FLOATS)
    private val pushData = FloatArray(32)
    private val postData = FloatArray(8)
    private val hudData = FloatArray(4)
    private val frameBlock: Int; private val pushBlock: Int; private val postBlock: Int; private val hudBlock: Int

    // ---- cast shadows: a depth texture array seen from the main light, a layer and a framebuffer per cascade
    private var shadowSize = 0
    private var shadowMap = 0
    private val shadowFramebuffers = IntArray(CASCADES)
    // ---- the lamps' shadow cubes: six layers per lamp of a depth texture array, a framebuffer per layer; the light grid
    private var lampSize = 0
    private var lampMap = 0                                      // the faces the shaders read
    private val lampFramebuffers = IntArray(6 * LAMP_SHADOWS)
    private var lampCache = 0                                    // each face's cache: what stands still around its lamp
    private val lampCacheFramebuffers = IntArray(6 * LAMP_SHADOWS)
    private val lightGrid: Int

    // ---- detail textures (Texture): one layer each, every level, in one array texture
    private var textures = 0
    private var textureCount = -1               // textures in the array (-1: none yet)
    private var texturesPainted = false         // all of them painted, none left neutral
    private var scratch = ByteArray(0)

    // ---- the passes' images at the current size
    private var sceneFramebuffer = 0            // where the scene is drawn (multisampled renderbuffers, or the texture itself)
    private var sceneColour = 0; private var sceneDepth = 0
    private var hdr = 0; private var hdrFramebuffer = 0          // the scene resolved, read by the later passes
    private var colour = 0; private var colourFramebuffer = 0    // the finished picture
    private val bloomDown = IntArray(BLOOM_LEVELS); private val downFramebuffers = IntArray(BLOOM_LEVELS)
    private val bloomUp = IntArray(BLOOM_LEVELS - 1); private val upFramebuffers = IntArray(BLOOM_LEVELS - 1)

    // ---- meshes
    /** A mesh on the GPU: its vertex array (the mesh, the instance rows, its effects, its material maps) and buffers. */
    private class MeshGpu(val array: Int, val vertices: Int, val effects: Int, val surface: Int) { var version = -1 }
    private val meshes = HashMap<Mesh, MeshGpu>()
    private var instanceBuffer = 0
    private var instances = FloatArray(16 * 1024)
    private val particleArray: Int
    private val lineArray: Int

    // ---- the interface
    private val hudArray: Int; private val hudBuffer: Int
    private var hudSent: HudMesh? = null
    private var atlas = 0; private var atlasWidth = 0; private var atlasHeight = 0
    private var atlasSent: GlyphAtlas? = null; private var atlasVersion = -1

    private var closed = false

    init {
        check(gl.floatTargets) { "Sillage draws the scene into a floating-point image, which this OpenGL ES / WebGL 2 context cannot do (EXT_color_buffer_float or EXT_color_buffer_half_float is missing)" }
        toon = program("toon.vert", "toon.frag"); water = program("toon.vert", "water.frag")
        sky = program("toon.vert", "sky.frag"); ombre = program("toon.vert", "ombre.frag")
        particles = program("particle.vert", "particle.frag")
        lines = program("lines.vert", "lines.frag"); linesXray = program("lines.vert", "lines_xray.frag")
        shadow = program("shadow.vert", null); shadowCut = program("shadow.vert", "shadow.frag")
        lampShadow = program("lamp_shadow.vert", null); lampShadowCut = program("lamp_shadow.vert", "shadow.frag")
        bright = program("post.vert", "bright.frag"); down = program("post.vert", "down.frag")
        up = program("post.vert", "up.frag"); composite = program("post.vert", "composite.frag")
        hudProgram = program("hud.vert", "hud.frag", BLOCK_HUD)
        frameBlock = block(BLOCK_FRAME, FRAME_FLOATS); pushBlock = block(BLOCK_PUSH, 32)
        postBlock = block(BLOCK_POST, 8); hudBlock = block(BLOCK_HUD, 4)
        // What a mesh without effects or material maps reads as them: zeros (no light of its own, no sway, no texture, opaque).
        for (i in 6..10) gl.vertexAttrib4f(i, 0f, 0f, 0f, 0f)
        instanceBuffer = gl.createBuffer()
        gl.bindBuffer(GL.ARRAY_BUFFER, instanceBuffer); gl.bufferData(GL.ARRAY_BUFFER, 4 * instances.size, GL.STREAM_DRAW)
        // Particles: per instance its centre and size, its colour and opacity. Debug lines: per vertex its position, its colour and opacity.
        particleArray = gl.createVertexArray(); gl.bindVertexArray(particleArray)
        for (i in 0..1) { gl.enableVertexAttribArray(i); gl.vertexAttribDivisor(i, 1) }
        lineArray = gl.createVertexArray(); gl.bindVertexArray(lineArray)
        for (i in 0..1) gl.enableVertexAttribArray(i)
        // The interface: per vertex its place, texture or local coordinates, colour, shape and its parameters (ui/Painter.kt).
        hudArray = gl.createVertexArray(); gl.bindVertexArray(hudArray)
        hudBuffer = gl.createBuffer(); gl.bindBuffer(GL.ARRAY_BUFFER, hudBuffer)
        val sizes = intArrayOf(2, 2, 4, 4, 4); val at = intArrayOf(0, 8, 16, 32, 48)
        for (i in 0..4) { gl.enableVertexAttribArray(i); gl.vertexAttribPointer(i, sizes[i], GL.FLOAT, false, 4 * HUD_FLOATS, at[i]) }
        gl.bindVertexArray(0)
        // The light grid: four RGBA texels per cluster, a row per slice of distance, read texel by texel.
        lightGrid = gl.createTexture()
        gl.activeTexture(GL.TEXTURE0 + 3); gl.bindTexture(GL.TEXTURE_2D, lightGrid)
        gl.texStorage2D(GL.TEXTURE_2D, 1, GL.RGBA8, GRID_WIDTH, GRID_Z)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.NEAREST); gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.NEAREST)
        gl.activeTexture(GL.TEXTURE0)
        createShadowMaps(if (shadows) SHADOW_TEXELS else 1)
        makeAtlas(4, 4)
        syncTextures(false)
        createTargets()
        gl.disable(GL.SCISSOR_TEST); gl.disable(GL.CULL_FACE)
        expectNoError("setting up")
    }

    /** A program from two generated shaders, its blocks and samplers tied by name. */
    private fun program(vertex: String, fragment: String?, push: Int = BLOCK_PUSH): Int {
        val p = try { gl.createProgram(GlShaders.source(vertex), if (fragment == null) NOTHING_FRAG else GlShaders.source(fragment)) }
            catch (e: IllegalStateException) { throw IllegalStateException("$vertex + ${fragment ?: "nothing"}: ${e.message}") }
        programs.add(p)
        gl.uniformBlockBinding(p, "Frame", BLOCK_FRAME); gl.uniformBlockBinding(p, "Push", push); gl.uniformBlockBinding(p, "Post", BLOCK_POST)
        gl.useProgram(p)
        // Texture units: 0 the shadow maps or a pass's first image or the glyph atlas, 1 the detail textures or a pass's second image.
        for (name in UNIT0) gl.uniformLocation(p, name).let { if (it >= 0) gl.uniform1i(it, 0) }
        for (name in UNIT1) gl.uniformLocation(p, name).let { if (it >= 0) gl.uniform1i(it, 1) }
        // 2 the lamps' shadow cubes, 3 the light grid.
        for (name in UNIT2) gl.uniformLocation(p, name).let { if (it >= 0) gl.uniform1i(it, 2) }
        for (name in UNIT3) gl.uniformLocation(p, name).let { if (it >= 0) gl.uniform1i(it, 3) }
        return p
    }

    private fun block(binding: Int, floats: Int): Int {
        val b = gl.createBuffer()
        gl.bindBuffer(GL.UNIFORM_BUFFER, b); gl.bufferData(GL.UNIFORM_BUFFER, 4 * floats, GL.DYNAMIC_DRAW)
        gl.bindBufferBase(GL.UNIFORM_BUFFER, binding, b)
        return b
    }

    private fun expectNoError(doing: String) {
        val e = gl.getError()
        check(e == GL.NO_ERROR) { "OpenGL ES error 0x" + e.toString(16) + " while " + doing }
    }

    // ------------------------------------------------------------------ images

    private fun texture2D(format: Int, w: Int, h: Int): Int {
        val t = gl.createTexture()
        gl.activeTexture(GL.TEXTURE0); gl.bindTexture(GL.TEXTURE_2D, t)
        gl.texStorage2D(GL.TEXTURE_2D, 1, format, w, h)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR); gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.CLAMP_TO_EDGE); gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
        return t
    }

    private fun framebufferOn(texture: Int, what: String): Int {
        val f = gl.createFramebuffer()
        gl.bindFramebuffer(GL.FRAMEBUFFER, f)
        gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, texture, 0)
        complete(what)
        return f
    }

    private fun complete(what: String) {
        val s = gl.checkFramebufferStatus(GL.FRAMEBUFFER)
        check(s == GL.FRAMEBUFFER_COMPLETE) { "OpenGL ES cannot draw into $what (framebuffer status 0x" + s.toString(16) + ")" }
    }

    /**
     * The shadow maps: a depth texture of [size] texels and one layer per cascade, read through a
     * comparison (filtered), and a framebuffer per layer. A renderer made without cast shadows keeps a
     * map of one texel, and makes the real ones the first time a scene asks for shadows.
     */
    private fun createShadowMaps(size: Int) {
        if (shadowMap != 0) { gl.deleteTexture(shadowMap); for (f in shadowFramebuffers) gl.deleteFramebuffer(f) }
        shadowSize = size
        shadowMap = gl.createTexture()
        gl.activeTexture(GL.TEXTURE0); gl.bindTexture(GL.TEXTURE_2D_ARRAY, shadowMap)
        gl.texStorage3D(GL.TEXTURE_2D_ARRAY, 1, GL.DEPTH_COMPONENT16, size, size, CASCADES)
        gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_MIN_FILTER, GL.LINEAR); gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_WRAP_S, GL.CLAMP_TO_EDGE); gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
        gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_COMPARE_MODE, GL.COMPARE_REF_TO_TEXTURE)
        gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_COMPARE_FUNC, GL.LEQUAL)
        for (c in 0 until CASCADES) {
            val f = gl.createFramebuffer(); shadowFramebuffers[c] = f
            gl.bindFramebuffer(GL.FRAMEBUFFER, f)
            gl.framebufferTextureLayer(GL.FRAMEBUFFER, GL.DEPTH_ATTACHMENT, shadowMap, 0, c)
            complete("the shadow map")
        }
        // The lamps' cubes, the same way: six layers per lamp, read through a comparison; one texel each without shadows.
        if (lampMap != 0) {
            gl.deleteTexture(lampMap); gl.deleteTexture(lampCache)
            for (f in lampFramebuffers) gl.deleteFramebuffer(f); for (f in lampCacheFramebuffers) gl.deleteFramebuffer(f)
        }
        lampSize = if (size > 1) LAMP_TEXELS else 1
        for (cache in booleanArrayOf(false, true)) {
            val t = gl.createTexture()
            gl.activeTexture(GL.TEXTURE0 + 2); gl.bindTexture(GL.TEXTURE_2D_ARRAY, t)
            gl.texStorage3D(GL.TEXTURE_2D_ARRAY, 1, GL.DEPTH_COMPONENT16, lampSize, lampSize, 6 * LAMP_SHADOWS)
            gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_MIN_FILTER, GL.LINEAR); gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
            gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_WRAP_S, GL.CLAMP_TO_EDGE); gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
            gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_COMPARE_MODE, GL.COMPARE_REF_TO_TEXTURE)
            gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_COMPARE_FUNC, GL.LEQUAL)
            val fbs = if (cache) lampCacheFramebuffers else lampFramebuffers
            for (k in 0 until 6 * LAMP_SHADOWS) {
                val f = gl.createFramebuffer(); fbs[k] = f
                gl.bindFramebuffer(GL.FRAMEBUFFER, f)
                gl.framebufferTextureLayer(GL.FRAMEBUFFER, GL.DEPTH_ATTACHMENT, t, 0, k)
                complete("a lamp's shadow")
            }
            if (cache) lampCache = t else lampMap = t
        }
        gl.activeTexture(GL.TEXTURE0)
        batches.lampShadows(if (size > 1) LAMP_SHADOWS else 0, lampSize)
        batches.newShadowMaps()
    }

    private fun levelWidth(i: Int) = maxOf(1, width shr (i + 1))
    private fun levelHeight(i: Int) = maxOf(1, height shr (i + 1))

    private fun destroyTargets() {
        if (sceneFramebuffer != 0 && sceneFramebuffer != hdrFramebuffer) gl.deleteFramebuffer(sceneFramebuffer)
        if (hdrFramebuffer != 0) gl.deleteFramebuffer(hdrFramebuffer)
        if (colourFramebuffer != 0) gl.deleteFramebuffer(colourFramebuffer)
        if (sceneColour != 0) gl.deleteRenderbuffer(sceneColour)
        if (sceneDepth != 0) gl.deleteRenderbuffer(sceneDepth)
        if (hdr != 0) gl.deleteTexture(hdr)
        if (colour != 0) gl.deleteTexture(colour)
        sceneFramebuffer = 0; hdrFramebuffer = 0; colourFramebuffer = 0; sceneColour = 0; sceneDepth = 0; hdr = 0; colour = 0
        for (i in 0 until BLOOM_LEVELS) {
            if (downFramebuffers[i] != 0) gl.deleteFramebuffer(downFramebuffers[i]); if (bloomDown[i] != 0) gl.deleteTexture(bloomDown[i])
            downFramebuffers[i] = 0; bloomDown[i] = 0
        }
        for (i in 0 until BLOOM_LEVELS - 1) {
            if (upFramebuffers[i] != 0) gl.deleteFramebuffer(upFramebuffers[i]); if (bloomUp[i] != 0) gl.deleteTexture(bloomUp[i])
            upFramebuffers[i] = 0; bloomUp[i] = 0
        }
    }

    /** The images of every pass at the current size and their framebuffers; made again when the size changes. */
    private fun createTargets() {
        destroyTargets()
        hdr = texture2D(GL.RGBA16F, width, height); hdrFramebuffer = framebufferOn(hdr, "a floating-point image")
        sceneDepth = gl.createRenderbuffer()
        gl.bindRenderbuffer(sceneDepth); gl.renderbufferStorage(samples, GL.DEPTH_COMPONENT32F, width, height)
        if (samples > 1) {
            sceneColour = gl.createRenderbuffer()
            gl.bindRenderbuffer(sceneColour); gl.renderbufferStorage(samples, GL.RGBA16F, width, height)
            sceneFramebuffer = gl.createFramebuffer()
            gl.bindFramebuffer(GL.FRAMEBUFFER, sceneFramebuffer)
            gl.framebufferRenderbuffer(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, sceneColour)
        } else {
            sceneFramebuffer = hdrFramebuffer
            gl.bindFramebuffer(GL.FRAMEBUFFER, sceneFramebuffer)
        }
        gl.framebufferRenderbuffer(GL.FRAMEBUFFER, GL.DEPTH_ATTACHMENT, sceneDepth)
        complete("the scene's image (floating point, $samples samples)")
        colour = texture2D(GL.RGBA8, width, height); colourFramebuffer = framebufferOn(colour, "the picture")
        for (i in 0 until BLOOM_LEVELS) {
            bloomDown[i] = texture2D(GL.RGBA16F, levelWidth(i), levelHeight(i)); downFramebuffers[i] = framebufferOn(bloomDown[i], "the glow")
            if (i < BLOOM_LEVELS - 1) { bloomUp[i] = texture2D(GL.RGBA16F, levelWidth(i), levelHeight(i)); upFramebuffers[i] = framebufferOn(bloomUp[i], "the glow") }
        }
        gl.bindFramebuffer(GL.FRAMEBUFFER, 0)
    }

    override fun resize(width: Int, height: Int) {
        if (width == this.width && height == this.height || width <= 0 || height <= 0) return
        this.width = width; this.height = height
        createTargets()
    }

    /**
     * Sends the detail textures to the GPU when new ones have been made since the last time (rare: when a
     * scene is built): one array texture, a layer per texture with every level, the layers as large as the
     * largest texture (a smaller one is repeated pixel by pixel). Before any texture, a neutral grey layer.
     * Unless [wait], those not painted yet stay neutral and everything is sent again once they all are.
     */
    private fun syncTextures(wait: Boolean) {
        val count = Texture.count()
        if (count == textureCount && texturesPainted) return
        val all = Texture.all()
        val painted = all.all { it.ready }
        if (count == textureCount && !painted && !wait) return
        texturesPainted = wait || painted
        val size = all.maxOfOrNull { it.size } ?: 4
        val levels = 31 - size.countLeadingZeroBits() + 1
        val layers = maxOf(1, all.size)
        if (textures != 0) gl.deleteTexture(textures)
        textures = gl.createTexture()
        gl.activeTexture(GL.TEXTURE0 + 1); gl.bindTexture(GL.TEXTURE_2D_ARRAY, textures)
        gl.texStorage3D(GL.TEXTURE_2D_ARRAY, levels, GL.RGBA8, size, size, layers)
        gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_MIN_FILTER, GL.LINEAR_MIPMAP_LINEAR); gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_WRAP_S, GL.REPEAT); gl.texParameteri(GL.TEXTURE_2D_ARRAY, GL.TEXTURE_WRAP_T, GL.REPEAT)
        if (scratch.size < 4 * size * size) scratch = ByteArray(4 * size * size)
        val tmp = scratch
        for (l in 0 until layers) {
            val t = all.getOrNull(l)?.takeIf { wait || it.ready }
            val px = t?.pixels
            // The texture's own level 0 is the array's level shift (a smaller texture is repeated below it).
            val shift = if (t == null) 0 else 31 - (size / t.size).countLeadingZeroBits()
            var offset = 0
            for (j in 0 until levels) {
                val side = size shr j
                if (t == null || px == null) { tmp.fill(128.toByte(), 0, 4 * side * side); gl.texSubImage3D(GL.TEXTURE_2D_ARRAY, j, 0, 0, l, side, side, 1, GL.RGBA, GL.UNSIGNED_BYTE, tmp, 0) }
                else if (j >= shift) {
                    gl.texSubImage3D(GL.TEXTURE_2D_ARRAY, j, 0, 0, l, side, side, 1, GL.RGBA, GL.UNSIGNED_BYTE, px, offset)
                    offset += 4 * side * side
                } else {
                    val k = 1 shl (shift - j)
                    for (y in 0 until side) for (x in 0 until side) {
                        val src = 4 * ((y / k) * t.size + x / k); val dst = 4 * (y * side + x)
                        tmp[dst] = px[src]; tmp[dst + 1] = px[src + 1]; tmp[dst + 2] = px[src + 2]; tmp[dst + 3] = px[src + 3]
                    }
                    gl.texSubImage3D(GL.TEXTURE_2D_ARRAY, j, 0, 0, l, side, side, 1, GL.RGBA, GL.UNSIGNED_BYTE, tmp, 0)
                }
            }
        }
        gl.activeTexture(GL.TEXTURE0)
        textureCount = all.size
    }

    // ------------------------------------------------------------------ meshes

    /**
     * A mesh goes to the GPU the first time it is drawn: its vertices (position, normal, colour: attributes
     * 0 to 2), room for one model matrix per copy as three rows (3 to 5, set at each draw), and per vertex its
     * own light and sway, its texture ([Mesh.effects]: 6 to 8; without them the attributes read zeros). A mesh
     * whose vertices change (skinned parts) is sent again when its version moved.
     */
    private fun prepare(m: Mesh) {
        if (m.vertices.isEmpty()) return
        if (m.dynamic) {
            val g = meshes[m] ?: upload(m, GL.DYNAMIC_DRAW)
            val v = m.version
            if (g.version != v) {
                if (g.version != -1) { gl.bindBuffer(GL.ARRAY_BUFFER, g.vertices); gl.bufferData(GL.ARRAY_BUFFER, m.vertices, 0, m.vertices.size, GL.DYNAMIC_DRAW) }
                g.version = v
            }
            return
        }
        if (m.preparedBy === batches.token) return
        if (!meshes.containsKey(m)) upload(m, GL.STATIC_DRAW)
        m.preparedBy = batches.token
    }

    private fun upload(m: Mesh, usage: Int): MeshGpu {
        val array = gl.createVertexArray(); gl.bindVertexArray(array)
        val vertices = gl.createBuffer()
        gl.bindBuffer(GL.ARRAY_BUFFER, vertices); gl.bufferData(GL.ARRAY_BUFFER, m.vertices, 0, m.vertices.size, usage)
        for (i in 0..2) { gl.enableVertexAttribArray(i); gl.vertexAttribPointer(i, 3, GL.FLOAT, false, 36, 12 * i) }
        for (i in 3..5) { gl.enableVertexAttribArray(i); gl.vertexAttribDivisor(i, 1) }
        var effects = 0
        val e = m.effects
        if (e != null) {
            effects = gl.createBuffer()
            gl.bindBuffer(GL.ARRAY_BUFFER, effects); gl.bufferData(GL.ARRAY_BUFFER, e, 0, e.size, GL.STATIC_DRAW)
            for (i in 6..8) { gl.enableVertexAttribArray(i); gl.vertexAttribPointer(i, 4, GL.FLOAT, false, 4 * EFFECT_FLOATS, 16 * (i - 6)) }
        }
        // A model's material maps (Mesh.surface): 9 to 10, read as zeros without.
        var surface = 0
        val su = m.surface
        if (su != null) {
            surface = gl.createBuffer()
            gl.bindBuffer(GL.ARRAY_BUFFER, surface); gl.bufferData(GL.ARRAY_BUFFER, su, 0, su.size, GL.STATIC_DRAW)
            for (i in 9..10) { gl.enableVertexAttribArray(i); gl.vertexAttribPointer(i, 4, GL.FLOAT, false, 4 * SURFACE_FLOATS, 16 * (i - 9)) }
        }
        gl.bindVertexArray(0)
        return MeshGpu(array, vertices, effects, surface).also { meshes[m] = it }
    }

    private fun free(g: MeshGpu) {
        gl.deleteVertexArray(g.array); gl.deleteBuffer(g.vertices)
        if (g.effects != 0) gl.deleteBuffer(g.effects)
        if (g.surface != 0) gl.deleteBuffer(g.surface)
    }

    override fun forget() {
        for (g in meshes.values) free(g)
        meshes.clear()
        batches.forget()             // what meshes remember of this renderer is forgotten with it
        hudSent = null
    }

    // ------------------------------------------------------------------ the interface's glyphs

    /** A new atlas texture of [w] × [h] texels (8 bits each), its texels undefined until the next copy. */
    private fun makeAtlas(w: Int, h: Int) {
        if (atlas != 0) gl.deleteTexture(atlas)
        atlas = texture2D(GL.R8, w, h); atlasWidth = w; atlasHeight = h
        hudData[0] = w.toFloat(); hudData[1] = h.toFloat(); hudData[2] = 1f / w; hudData[3] = 1f / h
        gl.bindBuffer(GL.UNIFORM_BUFFER, hudBlock); gl.bufferSubData(GL.UNIFORM_BUFFER, 0, hudData, 0, 4)
    }

    /** The rows of [a] changed since the last frame go to the atlas texture. */
    private fun syncAtlas(a: GlyphAtlas?) {
        if (a == null) return
        var top = 0; var bottom = 0
        if (a !== atlasSent || a.width != atlasWidth || a.height != atlasHeight) {
            makeAtlas(a.width, a.height)
            atlasSent = a
            a.takeDirty()
            bottom = a.height
        } else if (a.version != atlasVersion) {
            val dirty = a.takeDirty(); top = dirty[0]; bottom = dirty[1]
        }
        atlasVersion = a.version
        if (bottom <= top || a.pixels.size < bottom * a.width) return
        gl.activeTexture(GL.TEXTURE0); gl.bindTexture(GL.TEXTURE_2D, atlas)
        gl.texSubImage2D(GL.TEXTURE_2D, 0, 0, top, a.width, bottom - top, GL.RED, GL.UNSIGNED_BYTE, a.pixels, top * a.width)
    }

    // ------------------------------------------------------------------ a frame

    private fun push(vp: Mat4, eye: Vec3, time: Float, c: Int, look: WaterLook?) {
        val p = pushData; val sq = batches.squares
        // Column by column, as GLSL reads a mat4 (Mat4 names its coefficients column first).
        p[0] = vp.m00; p[1] = vp.m01; p[2] = vp.m02; p[3] = vp.m03
        p[4] = vp.m10; p[5] = vp.m11; p[6] = vp.m12; p[7] = vp.m13
        p[8] = vp.m20; p[9] = vp.m21; p[10] = vp.m22; p[11] = vp.m23
        p[12] = vp.m30; p[13] = vp.m31; p[14] = vp.m32; p[15] = vp.m33
        p[16] = eye.x; p[17] = eye.y; p[18] = eye.z; p[19] = time
        p[20] = sq[4 * c]; p[21] = sq[4 * c + 1]; p[22] = sq[4 * c + 2]; p[23] = sq[4 * c + 3]
        if (look != null) {
            p[24] = look.deep.r; p[25] = look.deep.g; p[26] = look.deep.b
            p[28] = look.shallow.r; p[29] = look.shallow.g; p[30] = look.shallow.b; p[31] = look.reach
        }
        gl.bindBuffer(GL.UNIFORM_BUFFER, pushBlock); gl.bufferSubData(GL.UNIFORM_BUFFER, 0, p, 0, 32)
    }

    /**
     * Draws every copy of a batch in one call: its mesh's vertex array, the instance rows from where the batch's start;
     * or only its copy [one] (blended copies are drawn one by one, in order).
     */
    private fun draw(b: Batch, one: Int = -1) {
        val m = b.mesh
        var g = b.gpu as MeshGpu?
        if (g == null) { g = meshes[m]!!; b.gpu = g }
        gl.bindVertexArray(g.array)
        // No base instance in WebGL 2: the rows' place in the instance buffer (bound) is given with the attributes.
        val start = b.start + (if (one < 0) 0 else 48 * one)
        gl.vertexAttribPointer(3, 4, GL.FLOAT, false, 48, start)
        gl.vertexAttribPointer(4, 4, GL.FLOAT, false, 48, start + 16)
        gl.vertexAttribPointer(5, 4, GL.FLOAT, false, 48, start + 32)
        gl.drawArraysInstanced(GL.TRIANGLES, 0, m.vertices.size / 9, if (one < 0) b.count else 1)
    }

    private fun blend(on: Boolean, premultiplied: Boolean = false) {
        if (!on) { gl.disable(GL.BLEND); return }
        gl.enable(GL.BLEND)
        // The alpha of the scene image holds the distance to the eye, which blended things leave as it was.
        gl.blendFuncSeparate(if (premultiplied) GL.ONE else GL.SRC_ALPHA, GL.ONE_MINUS_SRC_ALPHA, GL.ZERO, GL.ONE)
    }

    private fun depth(test: Boolean, write: Boolean) {
        if (test) { gl.enable(GL.DEPTH_TEST); gl.depthFunc(GL.LEQUAL) } else gl.disable(GL.DEPTH_TEST)
        gl.depthMask(write)
    }

    /** One of the picture's passes: a triangle over the whole of [framebuffer], reading [first] and [second]. */
    private fun pass(framebuffer: Int, w: Int, h: Int, program: Int, first: Int, second: Int,
                     tx: Float, ty: Float, a: Float, b: Float, c: Float, d: Float, z: Float = 0f) {
        gl.bindFramebuffer(GL.FRAMEBUFFER, framebuffer)
        gl.viewport(0, 0, w, h)
        gl.useProgram(program)
        gl.activeTexture(GL.TEXTURE0 + 1); gl.bindTexture(GL.TEXTURE_2D, second)
        gl.activeTexture(GL.TEXTURE0); gl.bindTexture(GL.TEXTURE_2D, first)
        val p = postData
        p[0] = tx; p[1] = ty; p[2] = z; p[3] = 0f; p[4] = a; p[5] = b; p[6] = c; p[7] = d
        gl.bindBuffer(GL.UNIFORM_BUFFER, postBlock); gl.bufferSubData(GL.UNIFORM_BUFFER, 0, p, 0, 8)
        gl.drawArrays(GL.TRIANGLES, 0, 3)
    }

    override fun draw(nodes: List<Node>, viewProjection: Mat4, hud: HudMesh, eye: Vec3, time: Float, hidden: Node?,
                      focus: Vec3?, lighting: Lighting, extras: FrameExtras?, capture: ((Int, Int, ByteArray) -> Unit)?) {
        check(!closed) { "This renderer is closed" }
        // Cast shadows turned on in a scene that began without them: the maps are made now, once.
        if (lighting.shadows && shadowSize == 1) createShadowMaps(SHADOW_TEXELS)
        syncTextures(waitForTextures || capture != null)
        val main = lighting.mainLight()
        batches.schedule(shadowSize > 1 && lighting.shadows && focus != null, lighting, focus, main, shadowSize)
        // The lamps the shaders get (their grid when there are many), those that cast shadows, then the copies.
        batches.lights(extras, viewProjection, eye, lighting.fogEnd, width, height, shadowSize > 1 && lighting.shadows)
        batches.gather(nodes, hidden, viewProjection, eye, lighting.fogEnd, main)
        extras?.sortParticles = batches.blendedCount > 0
        val lampStatic = batches.lampStatic; val lampLive = batches.lampLive; val lampDirect = batches.lampDirect
        if (hud !== hudSent) {
            gl.bindBuffer(GL.ARRAY_BUFFER, hudBuffer); gl.bufferData(GL.ARRAY_BUFFER, hud.vertices, 0, hud.vertices.size, GL.DYNAMIC_DRAW)
            hudSent = hud
        }
        syncAtlas(hud.atlas)

        // Instance rows of every batch, side by side, then the particles and the debug lines: one buffer, sent once.
        val particleCount = extras?.particleCount ?: 0
        val lineVertices = extras?.lineVertexCount ?: 0
        var total = PARTICLE_FLOATS * particleCount + LINE_FLOATS * lineVertices
        for (list in batches.casters) for (b in list) total += 12 * b.count
        for (k in lampStatic.indices) {
            if (lampStatic[k] || lampDirect[k]) for (b in batches.lampCasters[k]) total += 12 * b.count
            if (lampLive[k] || lampDirect[k]) for (b in batches.lampMovers[k]) total += 12 * b.count
        }
        for (b in batches.opaque) total += 12 * b.count
        for (b in batches.quiet) total += 12 * b.count
        for (b in batches.skies) total += 12 * b.count
        for (b in batches.shadows) total += 12 * b.count
        for (b in batches.waters) total += 12 * b.count
        for (b in batches.blended) total += 12 * b.count
        if (total > instances.size) instances = FloatArray(total.takeHighestOneBit() * 2)
        var at = 0
        for (list in batches.casters) for (b in list) at = put(b, at)
        for (k in lampStatic.indices) {
            if (lampStatic[k] || lampDirect[k]) for (b in batches.lampCasters[k]) at = put(b, at)
            if (lampLive[k] || lampDirect[k]) for (b in batches.lampMovers[k]) at = put(b, at)
        }
        for (b in batches.opaque) at = put(b, at)
        for (b in batches.quiet) at = put(b, at)
        for (b in batches.skies) at = put(b, at)
        for (b in batches.shadows) at = put(b, at)
        for (b in batches.waters) at = put(b, at)
        for (b in batches.blended) at = put(b, at)
        val particleStart = 4 * at
        if (particleCount > 0) extras!!.particles.copyInto(instances, at, 0, PARTICLE_FLOATS * particleCount)
        val lineStart = particleStart + 4 * PARTICLE_FLOATS * particleCount
        if (lineVertices > 0) extras!!.lines.copyInto(instances, lineStart / 4, 0, LINE_FLOATS * lineVertices)
        gl.bindBuffer(GL.ARRAY_BUFFER, instanceBuffer)
        // A new store each frame: the GPU may still be reading the last one.
        if (total > 0) gl.bufferData(GL.ARRAY_BUFFER, instances, 0, total, GL.STREAM_DRAW)
        batches.writeFrame(frameData, lighting, main, viewProjection, extras, shadowSize)
        gl.bindBuffer(GL.UNIFORM_BUFFER, frameBlock); gl.bufferSubData(GL.UNIFORM_BUFFER, 0, frameData, 0, FRAME_FLOATS)

        // The shadow maps first, one cascade per layer (only cleared when a cascade is off: the shaders then never read it).
        gl.colorMask(true, true, true, true)
        gl.activeTexture(GL.TEXTURE0 + 1); gl.bindTexture(GL.TEXTURE_2D_ARRAY, textures)
        gl.activeTexture(GL.TEXTURE0); gl.bindTexture(GL.TEXTURE_2D_ARRAY, 0)       // not read while it is drawn into
        blend(false); depth(true, true)
        val squares = batches.squares
        for (c in 0 until CASCADES) {
            if (!batches.cascadeNow[c]) continue                  // drawn a frame or three ago: its map is kept
            gl.bindFramebuffer(GL.FRAMEBUFFER, shadowFramebuffers[c])
            gl.viewport(0, 0, shadowSize, shadowSize)
            gl.clearDepth(1f); gl.clear(GL.DEPTH_BUFFER_BIT)
            val casters = batches.casters[c]
            if (squares[4 * c + 3] > 0f && casters.isNotEmpty()) {
                // A slope-scaled bias keeps lit faces from shadowing themselves.
                gl.enable(GL.POLYGON_OFFSET_FILL); gl.polygonOffset(2f, 1.5f)
                gl.useProgram(shadow)
                push(viewProjection, eye, time, c, null)
                var cut = false
                for (b in casters) if (b.mesh.cutOut) cut = true else draw(b)
                if (cut) {
                    gl.useProgram(shadowCut)
                    for (b in casters) if (b.mesh.cutOut) draw(b)
                }
                gl.disable(GL.POLYGON_OFFSET_FILL)
            }
        }
        // The lamps' cube faces (RenderBatches.lights), each through its own view from its lamp: the caches whose still
        // casters changed, then the faces the shaders read, copied from their caches with the moving casters over them.
        gl.activeTexture(GL.TEXTURE0 + 2); gl.bindTexture(GL.TEXTURE_2D_ARRAY, 0)
        fun drawLampCasters(casters: ArrayList<Batch>, k: Int) {
            if (casters.isEmpty()) return
            gl.viewport(0, 0, lampSize, lampSize)
            gl.enable(GL.POLYGON_OFFSET_FILL); gl.polygonOffset(2f, 1.5f)
            gl.useProgram(lampShadow)
            push(batches.lampFace(k), eye, time, 0, null)
            var cut = false
            for (b in casters) if (b.mesh.cutOut) cut = true else draw(b)
            if (cut) {
                gl.useProgram(lampShadowCut)
                for (b in casters) if (b.mesh.cutOut) draw(b)
            }
            gl.disable(GL.POLYGON_OFFSET_FILL)
        }
        for (k in lampDirect.indices) {
            if (!lampDirect[k]) continue                                   // its lamp moved: drawn whole, no cache
            gl.bindFramebuffer(GL.FRAMEBUFFER, lampFramebuffers[k])
            gl.viewport(0, 0, lampSize, lampSize)
            gl.clearDepth(1f); gl.clear(GL.DEPTH_BUFFER_BIT)
            drawLampCasters(batches.lampCasters[k], k); drawLampCasters(batches.lampMovers[k], k)
        }
        for (k in lampStatic.indices) {
            if (!lampStatic[k]) continue
            gl.bindFramebuffer(GL.FRAMEBUFFER, lampCacheFramebuffers[k])
            gl.viewport(0, 0, lampSize, lampSize)
            gl.clearDepth(1f); gl.clear(GL.DEPTH_BUFFER_BIT)
            drawLampCasters(batches.lampCasters[k], k)
        }
        for (k in lampLive.indices) {
            if (!lampLive[k]) continue
            gl.bindFramebuffer(GL.READ_FRAMEBUFFER, lampCacheFramebuffers[k]); gl.bindFramebuffer(GL.DRAW_FRAMEBUFFER, lampFramebuffers[k])
            gl.blitFramebuffer(0, 0, lampSize, lampSize, 0, 0, lampSize, lampSize, GL.DEPTH_BUFFER_BIT, GL.NEAREST)
            gl.bindFramebuffer(GL.FRAMEBUFFER, lampFramebuffers[k])
            drawLampCasters(batches.lampMovers[k], k)
        }
        gl.bindTexture(GL.TEXTURE_2D_ARRAY, lampMap)
        // The light grid of this frame, when the shaders use it.
        gl.activeTexture(GL.TEXTURE0 + 3); gl.bindTexture(GL.TEXTURE_2D, lightGrid)
        if (batches.gridOn) gl.texSubImage2D(GL.TEXTURE_2D, 0, 0, 0, GRID_WIDTH, GRID_Z, GL.RGBA, GL.UNSIGNED_BYTE, batches.grid, 0)
        gl.activeTexture(GL.TEXTURE0)
        gl.bindTexture(GL.TEXTURE_2D_ARRAY, shadowMap)

        // The scene in linear light; the finest square in the push block (its w > 0 says shadows are on), all of
        // them in the frame block. The clear alpha is "very far" for the ink.
        gl.bindFramebuffer(GL.FRAMEBUFFER, sceneFramebuffer)
        gl.viewport(0, 0, width, height)
        gl.clearColor(lighting.horizon.r, lighting.horizon.g, lighting.horizon.b, 10000f)
        gl.clearDepth(1f); gl.clear(GL.COLOR_BUFFER_BIT or GL.DEPTH_BUFFER_BIT)
        gl.useProgram(toon)
        push(viewProjection, eye, time, 0, null)
        for (b in batches.opaque) draw(b)
        for (b in batches.quiet) draw(b)
        if (batches.waters.isNotEmpty()) {
            gl.useProgram(water)
            for (b in batches.waters) { push(viewProjection, eye, time, 0, b.water); draw(b) }
            push(viewProjection, eye, time, 0, null)
        }
        if (batches.skies.isNotEmpty()) {
            gl.useProgram(sky); depth(true, false)
            for (b in batches.skies) draw(b)
        }
        if (batches.shadows.isNotEmpty()) {
            gl.useProgram(ombre); depth(true, false); blend(true)
            for (b in batches.shadows) draw(b)
        }
        // What is blended, farthest first: the particles (sorted already) and the blended copies among them.
        var drawnParticles = 0
        fun particlesTo(end: Int) {
            if (end <= drawnParticles) return
            gl.useProgram(particles); depth(true, false); blend(true, premultiplied = true)
            gl.bindVertexArray(particleArray)
            gl.vertexAttribPointer(0, 4, GL.FLOAT, false, 4 * PARTICLE_FLOATS, particleStart + 4 * PARTICLE_FLOATS * drawnParticles)
            gl.vertexAttribPointer(1, 4, GL.FLOAT, false, 4 * PARTICLE_FLOATS, particleStart + 4 * PARTICLE_FLOATS * drawnParticles + 16)
            gl.drawArraysInstanced(GL.TRIANGLES, 0, 6, end - drawnParticles)
            drawnParticles = end
        }
        for (i in 0 until batches.blendedCount) {
            val d = batches.blendedDistanceAt(i)
            var end = drawnParticles
            while (end < particleCount && particleDistance(extras!!, end, eye) > d) end++
            particlesTo(end)
            gl.useProgram(toon); depth(true, false); blend(true, premultiplied = true)
            draw(batches.blendedBatchAt(i), batches.blendedIndexAt(i))
        }
        particlesTo(particleCount)
        // Debug lines: faint through the scenery first, then plain where they are seen; red, green and blue only.
        if (lineVertices > 0) {
            gl.bindVertexArray(lineArray)
            gl.vertexAttribPointer(0, 3, GL.FLOAT, false, 4 * LINE_FLOATS, lineStart)
            gl.vertexAttribPointer(1, 4, GL.FLOAT, false, 4 * LINE_FLOATS, lineStart + 12)
            gl.colorMask(true, true, true, false); blend(true)
            gl.useProgram(linesXray); depth(false, false)
            gl.drawArrays(GL.LINES, 0, lineVertices)
            gl.useProgram(lines); depth(true, false)
            gl.drawArrays(GL.LINES, 0, lineVertices)
            gl.colorMask(true, true, true, true)
        }
        blend(false); depth(false, false)
        if (sceneFramebuffer != hdrFramebuffer) {
            gl.bindFramebuffer(GL.READ_FRAMEBUFFER, sceneFramebuffer); gl.bindFramebuffer(GL.DRAW_FRAMEBUFFER, hdrFramebuffer)
            gl.blitFramebuffer(0, 0, width, height, 0, 0, width, height, GL.COLOR_BUFFER_BIT, GL.NEAREST)
        }

        // The glow: the bright parts at half size, down the levels, then up again, each adding the level below.
        gl.bindVertexArray(0)
        pass(downFramebuffers[0], levelWidth(0), levelHeight(0), bright, hdr, hdr, 1f / width, 1f / height, 1f, 0f, 0f, 0f)
        for (i in 1 until BLOOM_LEVELS)
            pass(downFramebuffers[i], levelWidth(i), levelHeight(i), down, bloomDown[i - 1], bloomDown[i - 1], 1f / levelWidth(i - 1), 1f / levelHeight(i - 1), 0f, 0f, 0f, 0f)
        for (i in BLOOM_LEVELS - 2 downTo 0)
            pass(upFramebuffers[i], levelWidth(i), levelHeight(i), up, if (i == BLOOM_LEVELS - 2) bloomDown[i + 1] else bloomUp[i + 1], bloomDown[i],
                1f / levelWidth(i + 1), 1f / levelHeight(i + 1), 0f, 0f, 0f, 0f)
        // The finished picture, then the interface over it.
        pass(colourFramebuffer, width, height, composite, hdr, bloomUp[0], 1f / width, 1f / height,
            lighting.glow / BLOOM_LEVELS, lighting.outlines, lighting.vignette, lighting.saturation, maxOf(1, (height / 900f).roundToInt()).toFloat())
        if (hud.vertices.isNotEmpty()) {
            gl.useProgram(hudProgram); blend(true)
            gl.bindTexture(GL.TEXTURE_2D, atlas)
            gl.bindVertexArray(hudArray)
            gl.drawArrays(GL.TRIANGLES, 0, hud.vertices.size / HUD_FLOATS)
            gl.bindVertexArray(0); blend(false)
        }
        if (capture != null) {
            // The picture's first row in memory is its top row (see the class's note): read as it is.
            val rgba = ByteArray(4 * width * height)
            gl.bindFramebuffer(GL.READ_FRAMEBUFFER, colourFramebuffer)
            gl.readPixels(0, 0, width, height, rgba)
            val rgb = ByteArray(3 * width * height)
            for (i in 0 until width * height) { rgb[3 * i] = rgba[4 * i]; rgb[3 * i + 1] = rgba[4 * i + 1]; rgb[3 * i + 2] = rgba[4 * i + 2] }
            capture(width, height, rgb)
        }
        if (present) {
            // To the screen, turned the right way up (OpenGL's first row is the bottom one).
            gl.bindFramebuffer(GL.READ_FRAMEBUFFER, colourFramebuffer); gl.bindFramebuffer(GL.DRAW_FRAMEBUFFER, 0)
            gl.blitFramebuffer(0, 0, width, height, 0, height, width, 0, GL.COLOR_BUFFER_BIT, GL.NEAREST)
        }
        gl.bindFramebuffer(GL.FRAMEBUFFER, 0)
    }

    private fun put(b: Batch, at: Int): Int {
        b.start = 4 * at
        b.rows.copyInto(instances, at, 0, 12 * b.count)
        return at + 12 * b.count
    }

    override fun close() {
        if (closed) return
        closed = true
        for (g in meshes.values) free(g)
        meshes.clear()
        destroyTargets()
        gl.deleteTexture(shadowMap); for (f in shadowFramebuffers) gl.deleteFramebuffer(f)
        gl.deleteTexture(lampMap); for (f in lampFramebuffers) gl.deleteFramebuffer(f)
        gl.deleteTexture(lampCache); for (f in lampCacheFramebuffers) gl.deleteFramebuffer(f)
        gl.deleteTexture(lightGrid)
        if (textures != 0) gl.deleteTexture(textures)
        if (atlas != 0) gl.deleteTexture(atlas)
        for (b in intArrayOf(instanceBuffer, hudBuffer, frameBlock, pushBlock, postBlock, hudBlock)) gl.deleteBuffer(b)
        for (a in intArrayOf(particleArray, lineArray, hudArray)) gl.deleteVertexArray(a)
        for (p in programs) gl.deleteProgram(p)
    }

    private companion object {
        val UNIT0 = arrayOf("shadowMap", "source", "smaller", "scene", "atlas")
        val UNIT1 = arrayOf("detailMaps", "level", "bloom")
        val UNIT2 = arrayOf("lampShadows")
        val UNIT3 = arrayOf("lightGrid")
    }
}
