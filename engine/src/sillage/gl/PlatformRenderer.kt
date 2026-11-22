package sillage

import sillage.gl.Gl
import sillage.gl.GlRenderer

/** What draws on an OpenGL ES 3 / WebGL 2 context: the engine's second renderer. */
internal fun platformRenderer(gl: Gl, width: Int, height: Int): Renderer = GlRenderer(gl, width, height)
