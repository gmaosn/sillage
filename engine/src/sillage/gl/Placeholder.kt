package sillage

import sillage.gl.GL
import sillage.gl.Gl
import sillage.math.Mat4
import sillage.math.Vec3

/** What draws on an OpenGL ES 3 / WebGL 2 context. */
internal fun platformRenderer(gl: Gl, width: Int, height: Int): Renderer = ClearRenderer(gl, width, height)

/** A stand-in until the OpenGL ES renderer is plugged in: the sky's colour, nothing else. */
private class ClearRenderer(private val gl: Gl, override var width: Int, override var height: Int) : Renderer {
    override fun resize(width: Int, height: Int) { this.width = width; this.height = height }
    override fun draw(nodes: List<Node>, viewProjection: Mat4, hud: HudMesh, eye: Vec3, time: Float, hidden: Node?, focus: Vec3?, lighting: Lighting,
                      extras: FrameExtras?, capture: ((Int, Int, ByteArray) -> Unit)?) {
        gl.bindFramebuffer(GL.FRAMEBUFFER, 0); gl.viewport(0, 0, width, height)
        val c = lighting.horizon; gl.clearColor(c.r, c.g, c.b, 1f); gl.clear(GL.COLOR_BUFFER_BIT)
        drawn = nodes.size
    }
    override fun forget() {}
    override fun close() {}
    override var cull = true
    override var drawn = 0; private set
    override val culled = 0
}
