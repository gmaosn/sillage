package sillage

import sillage.gl.GlRenderer
import sillage.math.Mat4
import sillage.math.Vec3
import sillage.platform.EglGl
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
 * The OpenGL ES 3 renderer (the one of the web and of phones) on the desktop, without a window: an EGL
 * context of its own, pictures read back for captures. `SILLAGE_GL_FINISH=1` waits for the GPU at the end
 * of each frame, so that the loop's frame times include its work (for measures).
 */
internal class GlDrawing(width: Int, height: Int, shadows: Boolean) : Drawing {
    private val gl = EglGl()
    private val r: GlRenderer
    private val finish = System.getenv("SILLAGE_GL_FINISH") != null

    init {
        try {
            r = GlRenderer(gl, width, height, shadows)
            r.present = false                  // no screen: the picture is only read back
            r.waitForTextures = true           // the same frame from run to run
            println("SILLAGE_GL_OK GPU=" + gl.rendererName + " version=" + gl.versionName + " target=" + width + "x" + height + " msaa=" + r.samples + " context=" + gl.kind)
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
    override fun waitForFrame() = true
    override fun resized() = true
    override fun draw(nodes: List<Node>, camera: Mat4, hud: HudMesh, capture: Path?, eye: Vec3, time: Float, hidden: Node?, focus: Vec3?, lighting: Lighting, extras: FrameExtras?) {
        r.draw(nodes, camera, hud, eye, time, hidden, focus, lighting, extras, if (capture == null) null else { w, h, rgb ->
            sillage.io.PngWriter.write(capture.toString(), w, h, rgb)
            println("SILLAGE_CAPTURE=" + capture.toAbsolutePath())
        })
        if (finish) gl.finish()
        val e = gl.getError()
        check(e == 0) { "OpenGL ES error 0x" + e.toString(16) + " in the frame" }
    }
    override fun forget() = r.forget()
    override fun close() { gl.finish(); r.close(); gl.close() }
}
