package sillage.platform

import sillage.gl.Gl
import sillage.platform.Native.fn
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/*
 * OpenGL ES 3 on the desktop, without any window: an EGL context (Mesa's surfaceless platform when it is
 * there, else a small pbuffer) and the calls of libGLESv2 through the JDK's Foreign Function & Memory API.
 * It is there to try the engine's OpenGL ES renderer (the one of the web and of phones) on a desktop and
 * to compare its pictures with the Vulkan renderer's.
 *
 * One downcall handle per function, bound to its address and held in a static final field: the JIT
 * treats it as a constant and inlines the call. The calls that carry arrays read and write the Kotlin
 * arrays in place (critical downcalls: no copy).
 */
@Suppress("PropertyName")
private object Gles {
    private val linker = Linker.nativeLinker()
    private val lib = Native.library("libGLESv2.so.2", "libGLESv2.so")
    private val I = JAVA_INT; private val F = JAVA_FLOAT; private val P = ADDRESS; private val J = JAVA_LONG; private val B = JAVA_BYTE
    private fun f(name: String, ret: MemoryLayout?, vararg args: MemoryLayout): MethodHandle = linker.downcallHandle(lib.fn(name),
        if (ret == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(ret, *args))
    /** A call whose pointer arguments may be Kotlin arrays. */
    private fun heap(name: String, vararg args: MemoryLayout): MethodHandle = linker.downcallHandle(lib.fn(name),
        FunctionDescriptor.ofVoid(*args), Linker.Option.critical(true))

    @JvmField val viewport = f("glViewport", null, I, I, I, I)
    @JvmField val scissor = f("glScissor", null, I, I, I, I)
    @JvmField val enable = f("glEnable", null, I)
    @JvmField val disable = f("glDisable", null, I)
    @JvmField val clearColor = f("glClearColor", null, F, F, F, F)
    @JvmField val clearDepthf = f("glClearDepthf", null, F)
    @JvmField val clear = f("glClear", null, I)
    @JvmField val depthFunc = f("glDepthFunc", null, I)
    @JvmField val depthMask = f("glDepthMask", null, B)
    @JvmField val colorMask = f("glColorMask", null, B, B, B, B)
    @JvmField val blendFuncSeparate = f("glBlendFuncSeparate", null, I, I, I, I)
    @JvmField val polygonOffset = f("glPolygonOffset", null, F, F)
    @JvmField val pixelStorei = f("glPixelStorei", null, I, I)

    @JvmField val createShader = f("glCreateShader", I, I)
    @JvmField val shaderSource = f("glShaderSource", null, I, I, P, P)
    @JvmField val compileShader = f("glCompileShader", null, I)
    @JvmField val getShaderiv = f("glGetShaderiv", null, I, I, P)
    @JvmField val getShaderInfoLog = f("glGetShaderInfoLog", null, I, I, P, P)
    @JvmField val deleteShader = f("glDeleteShader", null, I)
    @JvmField val createProgram = f("glCreateProgram", I)
    @JvmField val attachShader = f("glAttachShader", null, I, I)
    @JvmField val linkProgram = f("glLinkProgram", null, I)
    @JvmField val getProgramiv = f("glGetProgramiv", null, I, I, P)
    @JvmField val getProgramInfoLog = f("glGetProgramInfoLog", null, I, I, P, P)
    @JvmField val deleteProgram = f("glDeleteProgram", null, I)
    @JvmField val useProgram = f("glUseProgram", null, I)
    @JvmField val getUniformLocation = f("glGetUniformLocation", I, I, P)
    @JvmField val uniform1i = f("glUniform1i", null, I, I)
    @JvmField val uniform1f = f("glUniform1f", null, I, F)
    @JvmField val uniform2f = f("glUniform2f", null, I, F, F)
    @JvmField val uniform4f = f("glUniform4f", null, I, F, F, F, F)
    @JvmField val uniform4fv = heap("glUniform4fv", I, I, P)
    @JvmField val uniformMatrix4fv = heap("glUniformMatrix4fv", I, I, B, P)
    @JvmField val getUniformBlockIndex = f("glGetUniformBlockIndex", I, I, P)
    @JvmField val uniformBlockBinding = f("glUniformBlockBinding", null, I, I, I)

    @JvmField val genBuffers = f("glGenBuffers", null, I, P)
    @JvmField val deleteBuffers = f("glDeleteBuffers", null, I, P)
    @JvmField val bindBuffer = f("glBindBuffer", null, I, I)
    @JvmField val bufferData = heap("glBufferData", I, J, P, I)
    @JvmField val bufferSubData = heap("glBufferSubData", I, J, J, P)
    @JvmField val bindBufferBase = f("glBindBufferBase", null, I, I, I)

    @JvmField val genVertexArrays = f("glGenVertexArrays", null, I, P)
    @JvmField val deleteVertexArrays = f("glDeleteVertexArrays", null, I, P)
    @JvmField val bindVertexArray = f("glBindVertexArray", null, I)
    @JvmField val enableVertexAttribArray = f("glEnableVertexAttribArray", null, I)
    @JvmField val disableVertexAttribArray = f("glDisableVertexAttribArray", null, I)
    @JvmField val vertexAttribPointer = f("glVertexAttribPointer", null, I, I, I, B, I, J)
    @JvmField val vertexAttribDivisor = f("glVertexAttribDivisor", null, I, I)
    @JvmField val vertexAttrib4f = f("glVertexAttrib4f", null, I, F, F, F, F)
    @JvmField val drawArrays = f("glDrawArrays", null, I, I, I)
    @JvmField val drawArraysInstanced = f("glDrawArraysInstanced", null, I, I, I, I)

    @JvmField val genTextures = f("glGenTextures", null, I, P)
    @JvmField val deleteTextures = f("glDeleteTextures", null, I, P)
    @JvmField val activeTexture = f("glActiveTexture", null, I)
    @JvmField val bindTexture = f("glBindTexture", null, I, I)
    @JvmField val texParameteri = f("glTexParameteri", null, I, I, I)
    @JvmField val texStorage2D = f("glTexStorage2D", null, I, I, I, I, I)
    @JvmField val texStorage3D = f("glTexStorage3D", null, I, I, I, I, I, I)
    @JvmField val texSubImage2D = heap("glTexSubImage2D", I, I, I, I, I, I, I, I, P)
    @JvmField val texSubImage3D = heap("glTexSubImage3D", I, I, I, I, I, I, I, I, I, I, P)
    @JvmField val generateMipmap = f("glGenerateMipmap", null, I)

    @JvmField val genFramebuffers = f("glGenFramebuffers", null, I, P)
    @JvmField val deleteFramebuffers = f("glDeleteFramebuffers", null, I, P)
    @JvmField val bindFramebuffer = f("glBindFramebuffer", null, I, I)
    @JvmField val framebufferTexture2D = f("glFramebufferTexture2D", null, I, I, I, I, I)
    @JvmField val framebufferTextureLayer = f("glFramebufferTextureLayer", null, I, I, I, I, I)
    @JvmField val framebufferRenderbuffer = f("glFramebufferRenderbuffer", null, I, I, I, I)
    @JvmField val genRenderbuffers = f("glGenRenderbuffers", null, I, P)
    @JvmField val deleteRenderbuffers = f("glDeleteRenderbuffers", null, I, P)
    @JvmField val bindRenderbuffer = f("glBindRenderbuffer", null, I, I)
    @JvmField val renderbufferStorage = f("glRenderbufferStorage", null, I, I, I, I)
    @JvmField val renderbufferStorageMultisample = f("glRenderbufferStorageMultisample", null, I, I, I, I, I)
    @JvmField val checkFramebufferStatus = f("glCheckFramebufferStatus", I, I)
    @JvmField val blitFramebuffer = f("glBlitFramebuffer", null, I, I, I, I, I, I, I, I, I, I)
    @JvmField val readPixels = heap("glReadPixels", I, I, I, I, I, I, P)

    @JvmField val getIntegerv = f("glGetIntegerv", null, I, P)
    @JvmField val getString = f("glGetString", P, I)
    @JvmField val getError = f("glGetError", I)
    @JvmField val finish = f("glFinish", null)
}

@Suppress("PropertyName")
private object Egl {
    private val linker = Linker.nativeLinker()
    private val lib = Native.library("libEGL.so.1", "libEGL.so")
    private val I = JAVA_INT; private val P = ADDRESS
    private fun f(name: String, ret: MemoryLayout, vararg args: MemoryLayout): MethodHandle =
        linker.downcallHandle(lib.fn(name), FunctionDescriptor.of(ret, *args))

    @JvmField val queryString = f("eglQueryString", P, P, I)
    @JvmField val getDisplay = f("eglGetDisplay", P, P)
    /** EGL 1.5; absent from older libraries, which then have no surfaceless platform either. */
    @JvmField val getPlatformDisplay: MethodHandle? = lib.find("eglGetPlatformDisplay").map { linker.downcallHandle(it, FunctionDescriptor.of(P, I, P, P)) }.orElse(null)
    @JvmField val initialize = f("eglInitialize", I, P, P, P)
    @JvmField val bindApi = f("eglBindAPI", I, I)
    @JvmField val chooseConfig = f("eglChooseConfig", I, P, P, P, I, P)
    @JvmField val getConfigAttrib = f("eglGetConfigAttrib", I, P, P, I, P)
    @JvmField val createContext = f("eglCreateContext", P, P, P, P, P)
    @JvmField val createPbufferSurface = f("eglCreatePbufferSurface", P, P, P, P)
    @JvmField val createWindowSurface = f("eglCreateWindowSurface", P, P, P, P, P)
    /** EGL 1.5, with a display from [getPlatformDisplay]. */
    @JvmField val createPlatformWindowSurface: MethodHandle? = lib.find("eglCreatePlatformWindowSurface").map { linker.downcallHandle(it, FunctionDescriptor.of(P, P, P, P, P)) }.orElse(null)
    @JvmField val makeCurrent = f("eglMakeCurrent", I, P, P, P, P)
    @JvmField val swapBuffers = f("eglSwapBuffers", I, P, P)
    @JvmField val swapInterval = f("eglSwapInterval", I, P, I)
    @JvmField val destroyContext = f("eglDestroyContext", I, P, P)
    @JvmField val destroySurface = f("eglDestroySurface", I, P, P)
    @JvmField val terminate = f("eglTerminate", I, P)
    @JvmField val getError = f("eglGetError", I)

    const val EXTENSIONS = 0x3055; const val VENDOR = 0x3053; const val VERSION = 0x3054
    const val PLATFORM_SURFACELESS_MESA = 0x31DD
    const val OPENGL_ES_API = 0x30A0
    const val NONE = 0x3038
    const val SURFACE_TYPE = 0x3033; const val PBUFFER_BIT = 1; const val WINDOW_BIT = 4
    const val RENDERABLE_TYPE = 0x3040; const val OPENGL_ES3_BIT = 0x40
    const val RED_SIZE = 0x3024; const val GREEN_SIZE = 0x3023; const val BLUE_SIZE = 0x3022; const val ALPHA_SIZE = 0x3021
    const val SAMPLE_BUFFERS = 0x3032; const val NATIVE_VISUAL_ID = 0x302E; const val CONFIG_CAVEAT = 0x3027; const val SLOW_CONFIG = 0x3050
    const val CONTEXT_CLIENT_VERSION = 0x3098
    const val WIDTH = 0x3057; const val HEIGHT = 0x3056
}

/**
 * An OpenGL ES 3 context, current on the thread that makes it (every call must come from that thread): of its
 * own, without any window, or drawing into the window [target] describes. [close] frees it.
 */
internal class EglGl(private val target: EglWindow? = null) : Gl, AutoCloseable {
    private val arena = Arena.ofConfined()
    private val scratch = arena.allocate(JAVA_INT, 16)
    private var display = MemorySegment.NULL
    private var context = MemorySegment.NULL
    private var surface = MemorySegment.NULL
    /** The driver's name for its GPU (« Mesa Intel(R) Arc… », « llvmpipe… »). */
    val rendererName: String
    val versionName: String
    /** EGL's own version and vendor (« 1.5 », « Mesa Project »). */
    val eglVersion: String
    /** How the context was made: « surfaceless », « pbuffer », or the window's platform (« Wayland », « X11 (XCB) »…). */
    val kind: String
    override val floatTargets: Boolean
    override val maxSamples: Int

    init {
        try {
            val client = Native.string(Egl.queryString.invokeExact(MemorySegment.NULL, Egl.EXTENSIONS) as MemorySegment)
            val platform = Egl.getPlatformDisplay
            var platformDisplay = false
            var windowKind = ""
            if (target == null) {
                if (platform != null && "EGL_MESA_platform_surfaceless" in client)
                    display = platform.invokeExact(Egl.PLATFORM_SURFACELESS_MESA, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
                if (display == MemorySegment.NULL) display = Egl.getDisplay.invokeExact(MemorySegment.NULL) as MemorySegment
            } else {
                // The window's own platform, the first this EGL offers among those the window can be drawn through.
                for (p in target.platforms) {
                    if (platform == null || p.extensions.none { it in client }) continue
                    display = platform.invokeExact(p.id, p.nativeDisplay, attribs(*p.attributes, Egl.NONE)) as MemorySegment
                    if (display != MemorySegment.NULL) { platformDisplay = true; windowKind = p.name; target.chosen = p; break }
                }
                if (display == MemorySegment.NULL) {
                    val p = target.platforms.last()
                    display = Egl.getDisplay.invokeExact(p.legacyDisplay) as MemorySegment
                    windowKind = p.name + ", eglGetDisplay"; target.chosen = p
                }
            }
            check(display != MemorySegment.NULL) { "EGL: no display" + (if (target != null) " for the window" else "") }
            egl(Egl.initialize.invokeExact(display, MemorySegment.NULL, MemorySegment.NULL) as Int, "eglInitialize")
            egl(Egl.bindApi.invokeExact(Egl.OPENGL_ES_API) as Int, "eglBindAPI")
            val extensions = Native.string(Egl.queryString.invokeExact(display, Egl.EXTENSIONS) as MemorySegment)
            eglVersion = Native.string(Egl.queryString.invokeExact(display, Egl.VERSION) as MemorySegment)
            val config = if (target == null) pbufferConfig() else windowConfig(target.chosen!!.visual)
            context = Egl.createContext.invokeExact(display, config, MemorySegment.NULL, ints(Egl.CONTEXT_CLIENT_VERSION, 3, Egl.NONE)) as MemorySegment
            check(context != MemorySegment.NULL) { "EGL: no OpenGL ES 3 context (error 0x" + (Egl.getError.invokeExact() as Int).toString(16) + ")" }
            if (target != null) {
                val p = target.chosen!!
                val create = Egl.createPlatformWindowSurface
                surface = if (platformDisplay && create != null) create.invokeExact(display, config, p.platformWindow(arena), MemorySegment.NULL) as MemorySegment
                    else Egl.createWindowSurface.invokeExact(display, config, p.legacyWindow(), MemorySegment.NULL) as MemorySegment
                check(surface != MemorySegment.NULL) { "EGL could not draw into the window (error 0x" + (Egl.getError.invokeExact() as Int).toString(16) + ")" }
                kind = windowKind
            } else if ("EGL_KHR_surfaceless_context" in extensions) kind = "surfaceless"
            else {
                surface = Egl.createPbufferSurface.invokeExact(display, config, ints(Egl.WIDTH, 16, Egl.HEIGHT, 16, Egl.NONE)) as MemorySegment
                check(surface != MemorySegment.NULL) { "EGL: no pbuffer" }
                kind = "pbuffer"
            }
            egl(Egl.makeCurrent.invokeExact(display, surface, surface, context) as Int, "eglMakeCurrent")
            // A window is paced by its platform (Wayland: the compositor's frame callbacks; X11: the screen's refresh).
            if (target != null) Egl.swapInterval.invokeExact(display, target.chosen!!.swapInterval) as Int
            rendererName = Native.string(Gles.getString.invokeExact(0x1F01) as MemorySegment)
            versionName = Native.string(Gles.getString.invokeExact(0x1F02) as MemorySegment)
            val gl = Native.string(Gles.getString.invokeExact(0x1F03) as MemorySegment)
            floatTargets = "GL_EXT_color_buffer_float" in gl || "GL_EXT_color_buffer_half_float" in gl || Regex("OpenGL ES 3\\.[2-9]").containsMatchIn(versionName)
            Gles.getIntegerv.invokeExact(0x8D57 /* GL_MAX_SAMPLES */, scratch)
            maxSamples = scratch.get(JAVA_INT, 0)
            // Rows of any width, for the one-byte texels of the glyph atlas and the pictures read back.
            Gles.pixelStorei.invokeExact(0x0CF5 /* GL_UNPACK_ALIGNMENT */, 1)
            Gles.pixelStorei.invokeExact(0x0D05 /* GL_PACK_ALIGNMENT */, 1)
        } catch (e: Throwable) { close(); throw e }
    }

    private fun ints(vararg v: Int): MemorySegment = arena.allocateFrom(JAVA_INT, *v)
    /** An EGLAttrib list (EGL 1.5: pointer-sized numbers, not ints). */
    private fun attribs(vararg v: Int): MemorySegment = arena.allocateFrom(JAVA_LONG, *LongArray(v.size) { v[it].toLong() })
    private fun egl(result: Int, what: String) {
        check(result != 0) { what + " failed (EGL error 0x" + (Egl.getError.invokeExact() as Int).toString(16) + ")" }
    }

    /** A configuration with 8-bit colour and alpha for a pbuffer (a context without any window). */
    private fun pbufferConfig(): MemorySegment {
        val config = arena.allocate(ADDRESS)
        val count = arena.allocate(JAVA_INT)
        val wanted = ints(Egl.SURFACE_TYPE, Egl.PBUFFER_BIT, Egl.RENDERABLE_TYPE, Egl.OPENGL_ES3_BIT,
            Egl.RED_SIZE, 8, Egl.GREEN_SIZE, 8, Egl.BLUE_SIZE, 8, Egl.ALPHA_SIZE, 8, Egl.NONE)
        egl(Egl.chooseConfig.invokeExact(display, wanted, config, 1, count) as Int, "eglChooseConfig")
        check(count.get(JAVA_INT, 0) > 0) { "EGL: no OpenGL ES 3 configuration" }
        return config.get(ADDRESS, 0)
    }

    /**
     * The configuration for a window: 8-bit colour, no alpha (the compositor would see through it), no
     * multisampling (the picture is copied in by a blit, which OpenGL ES 3.0 refuses into a multisampled
     * screen), and on X11 the window's own visual.
     */
    private fun windowConfig(visual: Int): MemorySegment {
        val configs = arena.allocate(ADDRESS, 64)
        val count = arena.allocate(JAVA_INT)
        val wanted = ints(Egl.SURFACE_TYPE, Egl.WINDOW_BIT, Egl.RENDERABLE_TYPE, Egl.OPENGL_ES3_BIT,
            Egl.RED_SIZE, 8, Egl.GREEN_SIZE, 8, Egl.BLUE_SIZE, 8, Egl.NONE)
        egl(Egl.chooseConfig.invokeExact(display, wanted, configs, 64, count) as Int, "eglChooseConfig")
        fun attribute(c: MemorySegment, name: Int): Int {
            Egl.getConfigAttrib.invokeExact(display, c, name, scratch) as Int
            return scratch.get(JAVA_INT, 0)
        }
        var best = MemorySegment.NULL; var bestScore = Int.MIN_VALUE
        for (i in 0 until count.get(JAVA_INT, 0)) {
            val c = configs.getAtIndex(ADDRESS, i.toLong())
            if (attribute(c, Egl.SAMPLE_BUFFERS) != 0) continue
            var score = 0
            if (visual != 0) score += if (attribute(c, Egl.NATIVE_VISUAL_ID) == visual) 1000 else -1000
            if (attribute(c, Egl.ALPHA_SIZE) == 0) score += 100
            if (attribute(c, Egl.RED_SIZE) == 8 && attribute(c, Egl.GREEN_SIZE) == 8 && attribute(c, Egl.BLUE_SIZE) == 8) score += 10
            if (attribute(c, Egl.CONFIG_CAVEAT) == Egl.SLOW_CONFIG) score -= 50
            if (score > bestScore) { bestScore = score; best = c }
        }
        check(best != MemorySegment.NULL) { "EGL offers no OpenGL ES 3 configuration for a window (8-bit colour, no multisampling)" }
        return best
    }

    /** Shows the picture drawn in the window. */
    fun swap() { egl(Egl.swapBuffers.invokeExact(display, surface) as Int, "eglSwapBuffers") }

    override fun close() {
        if (display != MemorySegment.NULL) {
            Egl.makeCurrent.invokeExact(display, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) as Int
            if (context != MemorySegment.NULL) Egl.destroyContext.invokeExact(display, context) as Int
            if (surface != MemorySegment.NULL) Egl.destroySurface.invokeExact(display, surface) as Int
            Egl.terminate.invokeExact(display) as Int
            display = MemorySegment.NULL; context = MemorySegment.NULL; surface = MemorySegment.NULL
        }
        target?.close()
        if (arena.scope().isAlive) arena.close()
    }

    /** Waits until the GPU has done everything asked so far (for measures). */
    fun finish() { Gles.finish.invokeExact() }

    private fun flag(b: Boolean): Byte = if (b) 1 else 0
    private fun floats(data: FloatArray, from: Int, count: Int): MemorySegment = MemorySegment.ofArray(data).asSlice(4L * from, 4L * count)

    // ---- state
    override fun viewport(x: Int, y: Int, width: Int, height: Int) { Gles.viewport.invokeExact(x, y, width, height) }
    override fun scissor(x: Int, y: Int, width: Int, height: Int) { Gles.scissor.invokeExact(x, y, width, height) }
    override fun enable(capability: Int) { Gles.enable.invokeExact(capability) }
    override fun disable(capability: Int) { Gles.disable.invokeExact(capability) }
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) { Gles.clearColor.invokeExact(r, g, b, a) }
    override fun clearDepth(depth: Float) { Gles.clearDepthf.invokeExact(depth) }
    override fun clear(mask: Int) { Gles.clear.invokeExact(mask) }
    override fun depthFunc(function: Int) { Gles.depthFunc.invokeExact(function) }
    override fun depthMask(write: Boolean) { Gles.depthMask.invokeExact(flag(write)) }
    override fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) { Gles.colorMask.invokeExact(flag(r), flag(g), flag(b), flag(a)) }
    override fun blendFuncSeparate(srcColor: Int, dstColor: Int, srcAlpha: Int, dstAlpha: Int) { Gles.blendFuncSeparate.invokeExact(srcColor, dstColor, srcAlpha, dstAlpha) }
    override fun polygonOffset(factor: Float, units: Float) { Gles.polygonOffset.invokeExact(factor, units) }

    // ---- programs
    private fun shader(type: Int, source: String, what: String): Int = Arena.ofConfined().use { a ->
        val s = Gles.createShader.invokeExact(type) as Int
        val text = a.allocateFrom(source)
        Gles.shaderSource.invokeExact(s, 1, a.allocateFrom(ADDRESS, text), MemorySegment.NULL)
        Gles.compileShader.invokeExact(s)
        Gles.getShaderiv.invokeExact(s, 0x8B81 /* GL_COMPILE_STATUS */, scratch)
        if (scratch.get(JAVA_INT, 0) == 0) {
            val log = a.allocate(8192)
            Gles.getShaderInfoLog.invokeExact(s, 8191, MemorySegment.NULL, log)
            Gles.deleteShader.invokeExact(s)
            throw IllegalStateException("OpenGL ES refused the $what shader:\n" + log.getString(0))
        }
        s
    }

    override fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val v = shader(0x8B31, vertexSource, "vertex")
        val f = try { shader(0x8B30, fragmentSource, "fragment") } catch (e: Throwable) { Gles.deleteShader.invokeExact(v); throw e }
        val p = Gles.createProgram.invokeExact() as Int
        Gles.attachShader.invokeExact(p, v); Gles.attachShader.invokeExact(p, f)
        Gles.linkProgram.invokeExact(p)
        Gles.deleteShader.invokeExact(v); Gles.deleteShader.invokeExact(f)
        Gles.getProgramiv.invokeExact(p, 0x8B82 /* GL_LINK_STATUS */, scratch)
        if (scratch.get(JAVA_INT, 0) == 0) Arena.ofConfined().use { a ->
            val log = a.allocate(8192)
            Gles.getProgramInfoLog.invokeExact(p, 8191, MemorySegment.NULL, log)
            Gles.deleteProgram.invokeExact(p)
            throw IllegalStateException("OpenGL ES could not link the shaders:\n" + log.getString(0))
        }
        return p
    }
    override fun deleteProgram(program: Int) { Gles.deleteProgram.invokeExact(program) }
    override fun useProgram(program: Int) { Gles.useProgram.invokeExact(program) }
    override fun uniformLocation(program: Int, name: String): Int = Arena.ofConfined().use { a -> Gles.getUniformLocation.invokeExact(program, a.allocateFrom(name)) as Int }
    override fun uniform1i(location: Int, v: Int) { Gles.uniform1i.invokeExact(location, v) }
    override fun uniform1f(location: Int, v: Float) { Gles.uniform1f.invokeExact(location, v) }
    override fun uniform2f(location: Int, x: Float, y: Float) { Gles.uniform2f.invokeExact(location, x, y) }
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) { Gles.uniform4f.invokeExact(location, x, y, z, w) }
    override fun uniform4fv(location: Int, values: FloatArray, from: Int, count: Int) { Gles.uniform4fv.invokeExact(location, count, floats(values, from, 4 * count)) }
    override fun uniformMatrix4(location: Int, m: FloatArray, from: Int) { Gles.uniformMatrix4fv.invokeExact(location, 1, 0.toByte(), floats(m, from, 16)) }
    override fun uniformBlockBinding(program: Int, blockName: String, binding: Int) {
        val index = Arena.ofConfined().use { a -> Gles.getUniformBlockIndex.invokeExact(program, a.allocateFrom(blockName)) as Int }
        if (index != -1 /* GL_INVALID_INDEX */) Gles.uniformBlockBinding.invokeExact(program, index, binding)
    }

    // ---- buffers
    private fun made(gen: MethodHandle): Int { gen.invokeExact(1, scratch); return scratch.get(JAVA_INT, 0) }
    private fun gone(delete: MethodHandle, name: Int) { scratch.set(JAVA_INT, 0, name); delete.invokeExact(1, scratch) }
    override fun createBuffer(): Int = made(Gles.genBuffers)
    override fun deleteBuffer(buffer: Int) = gone(Gles.deleteBuffers, buffer)
    override fun bindBuffer(target: Int, buffer: Int) { Gles.bindBuffer.invokeExact(target, buffer) }
    override fun bufferData(target: Int, bytes: Int, usage: Int) { Gles.bufferData.invokeExact(target, bytes.toLong(), MemorySegment.NULL, usage) }
    override fun bufferData(target: Int, data: FloatArray, from: Int, count: Int, usage: Int) { Gles.bufferData.invokeExact(target, 4L * count, floats(data, from, count), usage) }
    override fun bufferSubData(target: Int, offsetBytes: Int, data: FloatArray, from: Int, count: Int) { Gles.bufferSubData.invokeExact(target, offsetBytes.toLong(), 4L * count, floats(data, from, count)) }
    override fun bindBufferBase(target: Int, index: Int, buffer: Int) { Gles.bindBufferBase.invokeExact(target, index, buffer) }

    // ---- vertex arrays
    override fun createVertexArray(): Int = made(Gles.genVertexArrays)
    override fun deleteVertexArray(array: Int) = gone(Gles.deleteVertexArrays, array)
    override fun bindVertexArray(array: Int) { Gles.bindVertexArray.invokeExact(array) }
    override fun enableVertexAttribArray(index: Int) { Gles.enableVertexAttribArray.invokeExact(index) }
    override fun disableVertexAttribArray(index: Int) { Gles.disableVertexAttribArray.invokeExact(index) }
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) {
        Gles.vertexAttribPointer.invokeExact(index, size, type, flag(normalized), stride, offset.toLong())
    }
    override fun vertexAttribDivisor(index: Int, divisor: Int) { Gles.vertexAttribDivisor.invokeExact(index, divisor) }
    override fun vertexAttrib4f(index: Int, x: Float, y: Float, z: Float, w: Float) { Gles.vertexAttrib4f.invokeExact(index, x, y, z, w) }
    override fun drawArrays(mode: Int, first: Int, count: Int) { Gles.drawArrays.invokeExact(mode, first, count) }
    override fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int) { Gles.drawArraysInstanced.invokeExact(mode, first, count, instances) }

    // ---- textures
    override fun createTexture(): Int = made(Gles.genTextures)
    override fun deleteTexture(texture: Int) = gone(Gles.deleteTextures, texture)
    override fun activeTexture(unit: Int) { Gles.activeTexture.invokeExact(unit) }
    override fun bindTexture(target: Int, texture: Int) { Gles.bindTexture.invokeExact(target, texture) }
    override fun texParameteri(target: Int, name: Int, value: Int) { Gles.texParameteri.invokeExact(target, name, value) }
    override fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int) { Gles.texStorage2D.invokeExact(target, levels, internalFormat, width, height) }
    override fun texStorage3D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int, depth: Int) { Gles.texStorage3D.invokeExact(target, levels, internalFormat, width, height, depth) }
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteArray, from: Int) {
        Gles.texSubImage2D.invokeExact(target, level, x, y, width, height, format, type, MemorySegment.ofArray(data).asSlice(from.toLong()))
    }
    override fun texSubImage3D(target: Int, level: Int, x: Int, y: Int, z: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, data: ByteArray, from: Int) {
        Gles.texSubImage3D.invokeExact(target, level, x, y, z, width, height, depth, format, type, MemorySegment.ofArray(data).asSlice(from.toLong()))
    }
    override fun generateMipmap(target: Int) { Gles.generateMipmap.invokeExact(target) }

    // ---- framebuffers
    override fun createFramebuffer(): Int = made(Gles.genFramebuffers)
    override fun deleteFramebuffer(framebuffer: Int) = gone(Gles.deleteFramebuffers, framebuffer)
    override fun bindFramebuffer(target: Int, framebuffer: Int) { Gles.bindFramebuffer.invokeExact(target, framebuffer) }
    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) { Gles.framebufferTexture2D.invokeExact(target, attachment, textureTarget, texture, level) }
    override fun framebufferTextureLayer(target: Int, attachment: Int, texture: Int, level: Int, layer: Int) { Gles.framebufferTextureLayer.invokeExact(target, attachment, texture, level, layer) }
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbuffer: Int) { Gles.framebufferRenderbuffer.invokeExact(target, attachment, 0x8D41 /* GL_RENDERBUFFER */, renderbuffer) }
    override fun createRenderbuffer(): Int = made(Gles.genRenderbuffers)
    override fun deleteRenderbuffer(renderbuffer: Int) = gone(Gles.deleteRenderbuffers, renderbuffer)
    override fun bindRenderbuffer(renderbuffer: Int) { Gles.bindRenderbuffer.invokeExact(0x8D41, renderbuffer) }
    override fun renderbufferStorage(samples: Int, internalFormat: Int, width: Int, height: Int) {
        if (samples > 1) Gles.renderbufferStorageMultisample.invokeExact(0x8D41, samples, internalFormat, width, height)
        else Gles.renderbufferStorage.invokeExact(0x8D41, internalFormat, width, height)
    }
    override fun checkFramebufferStatus(target: Int): Int = Gles.checkFramebufferStatus.invokeExact(target) as Int
    override fun blitFramebuffer(srcX0: Int, srcY0: Int, srcX1: Int, srcY1: Int, dstX0: Int, dstY0: Int, dstX1: Int, dstY1: Int, mask: Int, filter: Int) {
        Gles.blitFramebuffer.invokeExact(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter)
    }
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, out: ByteArray) {
        require(out.size >= 4 * width * height) { "readPixels: 4 bytes per pixel" }
        Gles.readPixels.invokeExact(x, y, width, height, 0x1908 /* GL_RGBA */, 0x1401 /* GL_UNSIGNED_BYTE */, MemorySegment.ofArray(out))
    }

    override fun getError(): Int = Gles.getError.invokeExact() as Int
}
