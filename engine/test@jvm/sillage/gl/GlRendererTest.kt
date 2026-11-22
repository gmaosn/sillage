package sillage.gl

import sillage.Color
import sillage.Scene
import sillage.math.Mat4
import sillage.math.Vec3
import sillage.platform.EglGl
import sillage.vec
import kotlin.test.*

/**
 * The OpenGL ES renderer on a real context (EGL, without a window): every shader compiles and links, a
 * small scene is drawn without any OpenGL error, with and without cast shadows, and the picture comes
 * back the right way up. Needs EGL and OpenGL ES 3 with floating-point images; without them the test
 * says so and passes. The pictures themselves are compared with Vulkan's in the quality battery.
 */
class GlRendererTest {
    @Test fun uneScèneEstDessinée() {
        val gl = try { EglGl() } catch (e: Throwable) { println("GlRendererTest: no EGL context here (${e.message}), nothing checked"); return }
        try {
            if (!gl.floatTargets) { println("GlRendererTest: no floating-point images here, nothing checked"); return }
            val scene = Scene().apply {
                ground(color = Color(.2f, .7f, .2f)); autoTextures = false
                lighting.shadows = false; lighting.vignette = 0f
                box(1, at = vec(0, .5, 0))
            }
            val w = 96; val h = 64
            val r = GlRenderer(gl, w, h, shadows = false)
            r.present = false; r.waitForTextures = true
            try {
                val eye = Vec3(0f, 2f, 6f)
                val vp = Mat4.perspective(1f, w.toFloat() / h, .1f, 200f) * Mat4.lookAt(eye, Vec3(0f, 1f, 0f))
                var picture = ByteArray(0)
                fun frame(capture: Boolean) {
                    scene.update(1f / 60f)
                    r.draw(scene.drawList(eye), vp, scene.hud.mesh(w.toFloat() / h, 60, true), eye, scene.time, null, Vec3(0f, 0f, 0f), scene.lighting,
                        scene.frameExtras(eye), if (capture) { pw, ph, rgb -> assertEquals(w, pw); assertEquals(h, ph); picture = rgb } else null)
                    assertEquals(GL.NO_ERROR, gl.getError(), "OpenGL ES error in the frame")
                }
                repeat(3) { frame(false) }
                frame(true)
                assertEquals(3 * w * h, picture.size)
                // Green minus blue: the lawn is green, the sky is not; the first row given is the top one.
                fun green(x: Int, y: Int) = (picture[3 * (y * w + x) + 1].toInt() and 255) - (picture[3 * (y * w + x) + 2].toInt() and 255)
                assertTrue(green(4, h - 3) > 40, "the lawn is at the bottom (green − blue there: ${green(4, h - 3)})")
                assertTrue(green(4, 2) < 0, "the sky is at the top (green − blue there: ${green(4, 2)})")
                // Cast shadows turned on while the game runs: the maps are made then.
                scene.lighting.shadows = true
                repeat(5) { frame(false) }
                frame(true)
                r.forget()
                frame(true)
                r.resize(40, 30)
                assertEquals(40, r.width)
            } finally { r.close() }
            assertEquals(GL.NO_ERROR, gl.getError())
        } finally { gl.close() }
    }
}
