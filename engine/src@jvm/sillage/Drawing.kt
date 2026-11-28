package sillage

import sillage.gl.GlRenderer
import sillage.math.Mat4
import sillage.math.Vec3
import sillage.platform.EglGl
import sillage.platform.EglWindow
import sillage.platform.Window
import java.nio.file.Path

/** What the desktop's loop asks of what draws: the Vulkan renderer, or the OpenGL ES one through EGL (`--renderer gl`). */
internal interface Drawing : AutoCloseable {
    val width: Int
    val height: Int
    /** Whether it has the shadow maps (a Vulkan renderer made without them is made again when a scene wants shadows). */
    val shadowMaps: Boolean
    var cull: Boolean
    val drawn: Int
    val culled: Int
    /** GPU time of the last finished frame in milliseconds (0 when it cannot be measured). */
    val gpuMs: Double
    val validationErrors: Int
    /** Waits until the next frame can be drawn; false: skip this frame. */
    fun waitForFrame(): Boolean
    /** The window changed size; false while it has none (minimised). */
    fun resized(): Boolean
    fun draw(nodes: List<Node>, camera: Mat4, hud: HudMesh, capture: Path?, eye: Vec3, time: Float, hidden: Node?, focus: Vec3?, lighting: Lighting, extras: FrameExtras?)
    fun forget()
}

internal class VulkanDrawing(private val r: VulkanRenderer) : Drawing {
    init { r.ouvrir() }
    override val width get() = r.largeur
    override val height get() = r.hauteur
    override val shadowMaps get() = r.shadowMaps
    override var cull: Boolean
        get() = r.cull
        set(v) { r.cull = v }
    override val drawn get() = r.drawn
    override val culled get() = r.culled
    override val gpuMs get() = r.gpuMs
    override val validationErrors get() = r.erreursValidation
    override fun waitForFrame() = r.attendre()
    override fun resized() = r.recreateSwapchain()
    override fun draw(nodes: List<Node>, camera: Mat4, hud: HudMesh, capture: Path?, eye: Vec3, time: Float, hidden: Node?, focus: Vec3?, lighting: Lighting, extras: FrameExtras?) =
        r.dessiner(nodes, camera, hud, capture, eye, time, hidden, focus, lighting, extras)
    override fun forget() = r.oublier()
    override fun close() = r.close()
}

/**
 * The OpenGL ES 3 renderer (the one of the web and of phones) on the desktop: through EGL into the game's
 * window (Wayland or X11) when there is one, the finished picture copied to it turned the right way up; else
 * an EGL context of its own, pictures read back for captures. `SILLAGE_GL_FINISH=1` waits for the GPU at the
 * end of each frame, so that the loop's frame times include its work (for measures); `SILLAGE_GL_SCREEN=file.png`
 * also writes, with a capture, what was copied to the window (read back from it, to check the copy).
 */
internal class GlDrawing(private val window: Window?, width: Int, height: Int, shadows: Boolean) : Drawing {
    private val gl: EglGl
    private val target: EglWindow?
    private val r: GlRenderer
    private val finish = System.getenv("SILLAGE_GL_FINISH") != null
    private val screen = System.getenv("SILLAGE_GL_SCREEN")?.takeIf { it.isNotEmpty() && window != null }

    init {
        val t = window?.let { sillage.platform.eglWindowFor(it) }
        val g = try { EglGl(t) } catch (e: Throwable) { t?.close(); throw e }
        gl = g; target = t
        try {
            val size = window?.framebufferSize()?.takeIf { it[0] > 0 && it[1] > 0 } ?: intArrayOf(width, height)
            r = GlRenderer(gl, size[0], size[1], shadows)
            r.present = window != null         // into the window; without one the picture is only read back
            r.waitForTextures = window == null // without a window, the same frame from run to run (a capture always waits)
            println("SILLAGE_GL_OK GPU=" + gl.rendererName + " version=" + gl.versionName + " target=" + r.width + "x" + r.height + " msaa=" + r.samples + " context=" + gl.kind)
        } catch (e: Throwable) { gl.close(); throw e }
    }

    override val width get() = r.width
    override val height get() = r.height
    override val shadowMaps get() = true       // it makes them itself when a scene turns shadows on
    override var cull: Boolean
        get() = r.cull
        set(v) { r.cull = v }
    override val drawn get() = r.drawn
    override val culled get() = r.culled
    override val gpuMs get() = 0.0
    override val validationErrors get() = 0
    override fun waitForFrame(): Boolean { target?.waitForFrame(.1); return true }
    override fun resized(): Boolean {
        val w = window ?: return true
        val s = w.framebufferSize()
        if (s[0] <= 0 || s[1] <= 0) return false
        target?.resize(s[0], s[1])
        r.resize(s[0], s[1])
        return true
    }
    override fun draw(nodes: List<Node>, camera: Mat4, hud: HudMesh, capture: Path?, eye: Vec3, time: Float, hidden: Node?, focus: Vec3?, lighting: Lighting, extras: FrameExtras?) {
        r.draw(nodes, camera, hud, eye, time, hidden, focus, lighting, extras, if (capture == null) null else { w, h, rgb ->
            sillage.io.PngWriter.write(capture.toString(), w, h, rgb)
            println("SILLAGE_CAPTURE=" + capture.toAbsolutePath())
        })
        if (capture != null && screen != null) readScreen(screen)
        if (target != null) { target.beforeSwap(); gl.swap() }
        if (finish) gl.finish()
        val e = gl.getError()
        check(e == 0) { "OpenGL ES error 0x" + e.toString(16) + " in the frame" }
    }

    /** What the window holds before the swap (its first row in memory is the bottom one), written top row first. */
    private fun readScreen(file: String) {
        val w = r.width; val h = r.height
        val rgba = ByteArray(4 * w * h)
        gl.bindFramebuffer(sillage.gl.GL.READ_FRAMEBUFFER, 0)
        gl.readPixels(0, 0, w, h, rgba)
        gl.bindFramebuffer(sillage.gl.GL.FRAMEBUFFER, 0)
        val rgb = ByteArray(3 * w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val s = 4 * ((h - 1 - y) * w + x); val d = 3 * (y * w + x)
            rgb[d] = rgba[s]; rgb[d + 1] = rgba[s + 1]; rgb[d + 2] = rgba[s + 2]
        }
        sillage.io.PngWriter.write(file, w, h, rgb)
        println("SILLAGE_GL_SCREEN=" + Path.of(file).toAbsolutePath())
    }

    override fun forget() = r.forget()
    override fun close() { gl.finish(); r.close(); gl.close() }
}
