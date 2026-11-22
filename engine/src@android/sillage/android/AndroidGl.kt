package sillage.android

import android.opengl.GLES30
import sillage.gl.Gl
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/** OpenGL ES 3.0 on Android, behind the engine's Gl interface. Called on the thread that owns the context (the surface view's). */
internal class AndroidGl : Gl {
    private val one = IntArray(1)

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GLES30.glViewport(x, y, width, height)
    override fun scissor(x: Int, y: Int, width: Int, height: Int) = GLES30.glScissor(x, y, width, height)
    override fun enable(capability: Int) = GLES30.glEnable(capability)
    override fun disable(capability: Int) = GLES30.glDisable(capability)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES30.glClearColor(r, g, b, a)
    override fun clearDepth(depth: Float) = GLES30.glClearDepthf(depth)
    override fun clear(mask: Int) = GLES30.glClear(mask)
    override fun depthFunc(function: Int) = GLES30.glDepthFunc(function)
    override fun depthMask(write: Boolean) = GLES30.glDepthMask(write)
    override fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) = GLES30.glColorMask(r, g, b, a)
    override fun blendFuncSeparate(srcColor: Int, dstColor: Int, srcAlpha: Int, dstAlpha: Int) = GLES30.glBlendFuncSeparate(srcColor, dstColor, srcAlpha, dstAlpha)
    override fun polygonOffset(factor: Float, units: Float) = GLES30.glPolygonOffset(factor, units)

    private fun shader(type: Int, source: String, what: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, source); GLES30.glCompileShader(s)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, one, 0)
        if (one[0] == 0) { val log = GLES30.glGetShaderInfoLog(s); GLES30.glDeleteShader(s); throw IllegalStateException("OpenGL ES refused a $what shader:\n$log") }
        return s
    }
    override fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val v = shader(GLES30.GL_VERTEX_SHADER, vertexSource, "vertex"); val f = shader(GLES30.GL_FRAGMENT_SHADER, fragmentSource, "fragment")
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, one, 0)
        if (one[0] == 0) { val log = GLES30.glGetProgramInfoLog(p); GLES30.glDeleteProgram(p); throw IllegalStateException("OpenGL ES could not link a program:\n$log") }
        return p
    }
    override fun deleteProgram(program: Int) = GLES30.glDeleteProgram(program)
    override fun useProgram(program: Int) = GLES30.glUseProgram(program)
    override fun uniformLocation(program: Int, name: String): Int = GLES30.glGetUniformLocation(program, name)
    override fun uniform1i(location: Int, v: Int) { if (location >= 0) GLES30.glUniform1i(location, v) }
    override fun uniform1f(location: Int, v: Float) { if (location >= 0) GLES30.glUniform1f(location, v) }
    override fun uniform2f(location: Int, x: Float, y: Float) { if (location >= 0) GLES30.glUniform2f(location, x, y) }
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) { if (location >= 0) GLES30.glUniform4f(location, x, y, z, w) }
    override fun uniform4fv(location: Int, values: FloatArray, from: Int, count: Int) { if (location >= 0) GLES30.glUniform4fv(location, count, values, from) }
    override fun uniformMatrix4(location: Int, m: FloatArray, from: Int) { if (location >= 0) GLES30.glUniformMatrix4fv(location, 1, false, m, from) }
    override fun uniformBlockBinding(program: Int, blockName: String, binding: Int) {
        val i = GLES30.glGetUniformBlockIndex(program, blockName)
        if (i != GLES30.GL_INVALID_INDEX) GLES30.glUniformBlockBinding(program, i, binding)
    }

    override fun createBuffer(): Int { GLES30.glGenBuffers(1, one, 0); return one[0] }
    override fun deleteBuffer(buffer: Int) { one[0] = buffer; GLES30.glDeleteBuffers(1, one, 0) }
    override fun bindBuffer(target: Int, buffer: Int) = GLES30.glBindBuffer(target, buffer)
    override fun bufferData(target: Int, bytes: Int, usage: Int) = GLES30.glBufferData(target, bytes, null, usage)
    override fun bufferData(target: Int, data: FloatArray, from: Int, count: Int, usage: Int) = GLES30.glBufferData(target, 4 * count, FloatBuffer.wrap(data, from, count), usage)
    override fun bufferSubData(target: Int, offsetBytes: Int, data: FloatArray, from: Int, count: Int) { if (count > 0) GLES30.glBufferSubData(target, offsetBytes, 4 * count, FloatBuffer.wrap(data, from, count)) }
    override fun bindBufferBase(target: Int, index: Int, buffer: Int) = GLES30.glBindBufferBase(target, index, buffer)

    override fun createVertexArray(): Int { GLES30.glGenVertexArrays(1, one, 0); return one[0] }
    override fun deleteVertexArray(array: Int) { one[0] = array; GLES30.glDeleteVertexArrays(1, one, 0) }
    override fun bindVertexArray(array: Int) = GLES30.glBindVertexArray(array)
    override fun enableVertexAttribArray(index: Int) = GLES30.glEnableVertexAttribArray(index)
    override fun disableVertexAttribArray(index: Int) = GLES30.glDisableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) = GLES30.glVertexAttribPointer(index, size, type, normalized, stride, offset)
    override fun vertexAttribDivisor(index: Int, divisor: Int) = GLES30.glVertexAttribDivisor(index, divisor)
    override fun vertexAttrib4f(index: Int, x: Float, y: Float, z: Float, w: Float) = GLES30.glVertexAttrib4f(index, x, y, z, w)
    override fun drawArrays(mode: Int, first: Int, count: Int) = GLES30.glDrawArrays(mode, first, count)
    override fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int) = GLES30.glDrawArraysInstanced(mode, first, count, instances)

    override fun createTexture(): Int { GLES30.glGenTextures(1, one, 0); return one[0] }
    override fun deleteTexture(texture: Int) { one[0] = texture; GLES30.glDeleteTextures(1, one, 0) }
    override fun activeTexture(unit: Int) = GLES30.glActiveTexture(unit)
    override fun bindTexture(target: Int, texture: Int) = GLES30.glBindTexture(target, texture)
    override fun texParameteri(target: Int, name: Int, value: Int) = GLES30.glTexParameteri(target, name, value)
    override fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int) = GLES30.glTexStorage2D(target, levels, internalFormat, width, height)
    override fun texStorage3D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int, depth: Int) = GLES30.glTexStorage3D(target, levels, internalFormat, width, height, depth)
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteArray, from: Int) {
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(target, level, x, y, width, height, format, type, ByteBuffer.wrap(data, from, data.size - from))
    }
    override fun texSubImage3D(target: Int, level: Int, x: Int, y: Int, z: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, data: ByteArray, from: Int) {
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage3D(target, level, x, y, z, width, height, depth, format, type, ByteBuffer.wrap(data, from, data.size - from))
    }
    override fun generateMipmap(target: Int) = GLES30.glGenerateMipmap(target)

    override fun createFramebuffer(): Int { GLES30.glGenFramebuffers(1, one, 0); return one[0] }
    override fun deleteFramebuffer(framebuffer: Int) { one[0] = framebuffer; GLES30.glDeleteFramebuffers(1, one, 0) }
    /** 0 is the surface view's own framebuffer. */
    override fun bindFramebuffer(target: Int, framebuffer: Int) = GLES30.glBindFramebuffer(target, framebuffer)
    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) = GLES30.glFramebufferTexture2D(target, attachment, textureTarget, texture, level)
    override fun framebufferTextureLayer(target: Int, attachment: Int, texture: Int, level: Int, layer: Int) = GLES30.glFramebufferTextureLayer(target, attachment, texture, level, layer)
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbuffer: Int) = GLES30.glFramebufferRenderbuffer(target, attachment, GLES30.GL_RENDERBUFFER, renderbuffer)
    override fun createRenderbuffer(): Int { GLES30.glGenRenderbuffers(1, one, 0); return one[0] }
    override fun deleteRenderbuffer(renderbuffer: Int) { one[0] = renderbuffer; GLES30.glDeleteRenderbuffers(1, one, 0) }
    override fun bindRenderbuffer(renderbuffer: Int) = GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, renderbuffer)
    override fun renderbufferStorage(samples: Int, internalFormat: Int, width: Int, height: Int) {
        if (samples > 1) GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, internalFormat, width, height)
        else GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, internalFormat, width, height)
    }
    override fun checkFramebufferStatus(target: Int): Int = GLES30.glCheckFramebufferStatus(target)
    override fun blitFramebuffer(srcX0: Int, srcY0: Int, srcX1: Int, srcY1: Int, dstX0: Int, dstY0: Int, dstX1: Int, dstY1: Int, mask: Int, filter: Int) =
        GLES30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, out: ByteArray) {
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(x, y, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(out))
    }

    override val floatTargets: Boolean by lazy {
        val e = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        "EXT_color_buffer_float" in e || "EXT_color_buffer_half_float" in e
    }
    override val maxSamples: Int by lazy { GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, one, 0); one[0] }
    override fun getError(): Int = GLES30.glGetError()
}
