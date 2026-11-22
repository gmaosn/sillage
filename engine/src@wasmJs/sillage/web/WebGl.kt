@file:OptIn(ExperimentalWasmJsInterop::class)

package sillage.web

import sillage.gl.GL
import sillage.gl.Gl

// WebGL 2 behind the engine's Gl interface. Objects are numbers on the Kotlin side: indices in S.o
// (buffers, textures, programs…) and S.u (uniform locations), 0 for none.

@JsFun("(x, y, w, h) => S.gl.viewport(x, y, w, h)") private external fun jViewport(x: Int, y: Int, w: Int, h: Int)
@JsFun("(x, y, w, h) => S.gl.scissor(x, y, w, h)") private external fun jScissor(x: Int, y: Int, w: Int, h: Int)
@JsFun("(c) => S.gl.enable(c)") private external fun jEnable(c: Int)
@JsFun("(c) => S.gl.disable(c)") private external fun jDisable(c: Int)
@JsFun("(r, g, b, a) => S.gl.clearColor(r, g, b, a)") private external fun jClearColor(r: Float, g: Float, b: Float, a: Float)
@JsFun("(d) => S.gl.clearDepth(d)") private external fun jClearDepth(d: Float)
@JsFun("(m) => S.gl.clear(m)") private external fun jClear(m: Int)
@JsFun("(f) => S.gl.depthFunc(f)") private external fun jDepthFunc(f: Int)
@JsFun("(w) => S.gl.depthMask(w)") private external fun jDepthMask(w: Boolean)
@JsFun("(r, g, b, a) => S.gl.colorMask(r, g, b, a)") private external fun jColorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean)
@JsFun("(a, b, c, d) => S.gl.blendFuncSeparate(a, b, c, d)") private external fun jBlend(a: Int, b: Int, c: Int, d: Int)
@JsFun("(f, u) => S.gl.polygonOffset(f, u)") private external fun jPolygonOffset(f: Float, u: Float)

@JsFun("""(vs, fs) => {
  const gl = S.gl; S.log = '';
  const make = (type, src, what) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) { S.log += what + ' shader: ' + gl.getShaderInfoLog(s) + '\n'; gl.deleteShader(s); return null } return s };
  const v = make(gl.VERTEX_SHADER, vs, 'vertex'), f = make(gl.FRAGMENT_SHADER, fs, 'fragment');
  if (!v || !f) return 0;
  const p = gl.createProgram(); gl.attachShader(p, v); gl.attachShader(p, f); gl.linkProgram(p); gl.deleteShader(v); gl.deleteShader(f);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) { S.log += 'link: ' + gl.getProgramInfoLog(p); gl.deleteProgram(p); return 0 }
  return S.o.push(p) - 1;
}""")
private external fun jProgram(vs: String, fs: String): Int
@JsFun("() => S.log") private external fun jProgramLog(): String
@JsFun("(p) => { S.gl.deleteProgram(S.o[p]); S.o[p] = null }") private external fun jDeleteProgram(p: Int)
@JsFun("(p) => S.gl.useProgram(S.o[p])") private external fun jUseProgram(p: Int)
@JsFun("(p, name) => { const l = S.gl.getUniformLocation(S.o[p], name); return l ? S.u.push(l) - 1 : -1 }") private external fun jUniformLocation(p: Int, name: String): Int
@JsFun("(l, v) => S.gl.uniform1i(S.u[l], v)") private external fun jUniform1i(l: Int, v: Int)
@JsFun("(l, v) => S.gl.uniform1f(S.u[l], v)") private external fun jUniform1f(l: Int, v: Float)
@JsFun("(l, x, y) => S.gl.uniform2f(S.u[l], x, y)") private external fun jUniform2f(l: Int, x: Float, y: Float)
@JsFun("(l, x, y, z, w) => S.gl.uniform4f(S.u[l], x, y, z, w)") private external fun jUniform4f(l: Int, x: Float, y: Float, z: Float, w: Float)
@JsFun("(l, p, n) => S.gl.uniform4fv(S.u[l], new Float32Array(S.m.buffer, p, 4 * n))") private external fun jUniform4fv(l: Int, p: Int, n: Int)
@JsFun("(l, p) => S.gl.uniformMatrix4fv(S.u[l], false, new Float32Array(S.m.buffer, p, 16))") private external fun jUniformMatrix4(l: Int, p: Int)
@JsFun("(p, name, b) => { const gl = S.gl, i = gl.getUniformBlockIndex(S.o[p], name); if (i !== gl.INVALID_INDEX) gl.uniformBlockBinding(S.o[p], i, b) }")
private external fun jUniformBlockBinding(p: Int, name: String, b: Int)

@JsFun("() => S.o.push(S.gl.createBuffer()) - 1") private external fun jCreateBuffer(): Int
@JsFun("(b) => { S.gl.deleteBuffer(S.o[b]); S.o[b] = null }") private external fun jDeleteBuffer(b: Int)
@JsFun("(t, b) => S.gl.bindBuffer(t, S.o[b])") private external fun jBindBuffer(t: Int, b: Int)
@JsFun("(t, n, u) => S.gl.bufferData(t, n, u)") private external fun jBufferSize(t: Int, n: Int, u: Int)
@JsFun("(t, p, n, u) => S.gl.bufferData(t, new Float32Array(S.m.buffer, p, n), u)") private external fun jBufferData(t: Int, p: Int, n: Int, u: Int)
@JsFun("(t, o, p, n) => S.gl.bufferSubData(t, o, new Float32Array(S.m.buffer, p, n))") private external fun jBufferSubData(t: Int, o: Int, p: Int, n: Int)
@JsFun("(t, i, b) => S.gl.bindBufferBase(t, i, S.o[b])") private external fun jBindBufferBase(t: Int, i: Int, b: Int)

@JsFun("() => S.o.push(S.gl.createVertexArray()) - 1") private external fun jCreateVertexArray(): Int
@JsFun("(a) => { S.gl.deleteVertexArray(S.o[a]); S.o[a] = null }") private external fun jDeleteVertexArray(a: Int)
@JsFun("(a) => S.gl.bindVertexArray(S.o[a])") private external fun jBindVertexArray(a: Int)
@JsFun("(i) => S.gl.enableVertexAttribArray(i)") private external fun jEnableAttrib(i: Int)
@JsFun("(i) => S.gl.disableVertexAttribArray(i)") private external fun jDisableAttrib(i: Int)
@JsFun("(i, s, t, n, st, o) => S.gl.vertexAttribPointer(i, s, t, n, st, o)") private external fun jAttribPointer(i: Int, s: Int, t: Int, n: Boolean, st: Int, o: Int)
@JsFun("(i, d) => S.gl.vertexAttribDivisor(i, d)") private external fun jAttribDivisor(i: Int, d: Int)
@JsFun("(m, f, c) => S.gl.drawArrays(m, f, c)") private external fun jDrawArrays(m: Int, f: Int, c: Int)
@JsFun("(m, f, c, n) => S.gl.drawArraysInstanced(m, f, c, n)") private external fun jDrawArraysInstanced(m: Int, f: Int, c: Int, n: Int)

@JsFun("() => S.o.push(S.gl.createTexture()) - 1") private external fun jCreateTexture(): Int
@JsFun("(t) => { S.gl.deleteTexture(S.o[t]); S.o[t] = null }") private external fun jDeleteTexture(t: Int)
@JsFun("(u) => S.gl.activeTexture(u)") private external fun jActiveTexture(u: Int)
@JsFun("(t, x) => S.gl.bindTexture(t, S.o[x])") private external fun jBindTexture(t: Int, x: Int)
@JsFun("(t, n, v) => S.gl.texParameteri(t, n, v)") private external fun jTexParameteri(t: Int, n: Int, v: Int)
@JsFun("(t, l, f, w, h) => S.gl.texStorage2D(t, l, f, w, h)") private external fun jTexStorage2D(t: Int, l: Int, f: Int, w: Int, h: Int)
@JsFun("(t, l, f, w, h, d) => S.gl.texStorage3D(t, l, f, w, h, d)") private external fun jTexStorage3D(t: Int, l: Int, f: Int, w: Int, h: Int, d: Int)
@JsFun("(t, l, x, y, w, h, f, ty, p, n) => S.gl.texSubImage2D(t, l, x, y, w, h, f, ty, new Uint8Array(S.m.buffer, p, n))")
private external fun jTexSubImage2D(t: Int, l: Int, x: Int, y: Int, w: Int, h: Int, f: Int, ty: Int, p: Int, n: Int)
@JsFun("(t, l, x, y, z, w, h, d, f, ty, p, n) => S.gl.texSubImage3D(t, l, x, y, z, w, h, d, f, ty, new Uint8Array(S.m.buffer, p, n))")
private external fun jTexSubImage3D(t: Int, l: Int, x: Int, y: Int, z: Int, w: Int, h: Int, d: Int, f: Int, ty: Int, p: Int, n: Int)
@JsFun("(t) => S.gl.generateMipmap(t)") private external fun jGenerateMipmap(t: Int)

@JsFun("() => S.o.push(S.gl.createFramebuffer()) - 1") private external fun jCreateFramebuffer(): Int
@JsFun("(f) => { S.gl.deleteFramebuffer(S.o[f]); S.o[f] = null }") private external fun jDeleteFramebuffer(f: Int)
@JsFun("(t, f) => S.gl.bindFramebuffer(t, S.o[f])") private external fun jBindFramebuffer(t: Int, f: Int)
@JsFun("(t, a, tt, x, l) => S.gl.framebufferTexture2D(t, a, tt, S.o[x], l)") private external fun jFramebufferTexture2D(t: Int, a: Int, tt: Int, x: Int, l: Int)
@JsFun("(t, a, x, l, ly) => S.gl.framebufferTextureLayer(t, a, S.o[x], l, ly)") private external fun jFramebufferTextureLayer(t: Int, a: Int, x: Int, l: Int, ly: Int)
@JsFun("(t, a, r) => S.gl.framebufferRenderbuffer(t, a, S.gl.RENDERBUFFER, S.o[r])") private external fun jFramebufferRenderbuffer(t: Int, a: Int, r: Int)
@JsFun("() => S.o.push(S.gl.createRenderbuffer()) - 1") private external fun jCreateRenderbuffer(): Int
@JsFun("(r) => { S.gl.deleteRenderbuffer(S.o[r]); S.o[r] = null }") private external fun jDeleteRenderbuffer(r: Int)
@JsFun("(r) => S.gl.bindRenderbuffer(S.gl.RENDERBUFFER, S.o[r])") private external fun jBindRenderbuffer(r: Int)
@JsFun("(s, f, w, h) => { const gl = S.gl; if (s > 1) gl.renderbufferStorageMultisample(gl.RENDERBUFFER, s, f, w, h); else gl.renderbufferStorage(gl.RENDERBUFFER, f, w, h) }")
private external fun jRenderbufferStorage(s: Int, f: Int, w: Int, h: Int)
@JsFun("(t) => S.gl.checkFramebufferStatus(t)") private external fun jCheckFramebuffer(t: Int): Int
@JsFun("(a, b, c, d, e, f, g, h, m, fl) => S.gl.blitFramebuffer(a, b, c, d, e, f, g, h, m, fl)")
private external fun jBlit(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, m: Int, fl: Int)
@JsFun("(x, y, w, h, p) => S.gl.readPixels(x, y, w, h, S.gl.RGBA, S.gl.UNSIGNED_BYTE, new Uint8Array(S.m.buffer, p, 4 * w * h))")
private external fun jReadPixels(x: Int, y: Int, w: Int, h: Int, p: Int)
@JsFun("() => S.gl.getParameter(S.gl.MAX_SAMPLES)") private external fun jMaxSamples(): Int
@JsFun("() => S.gl.getError()") private external fun jGetError(): Int

/** WebGL 2 on the page's canvas. */
internal class WebGl(override val floatTargets: Boolean) : Gl {
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = jViewport(x, y, width, height)
    override fun scissor(x: Int, y: Int, width: Int, height: Int) = jScissor(x, y, width, height)
    override fun enable(capability: Int) = jEnable(capability)
    override fun disable(capability: Int) = jDisable(capability)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = jClearColor(r, g, b, a)
    override fun clearDepth(depth: Float) = jClearDepth(depth)
    override fun clear(mask: Int) = jClear(mask)
    override fun depthFunc(function: Int) = jDepthFunc(function)
    override fun depthMask(write: Boolean) = jDepthMask(write)
    override fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) = jColorMask(r, g, b, a)
    override fun blendFuncSeparate(srcColor: Int, dstColor: Int, srcAlpha: Int, dstAlpha: Int) = jBlend(srcColor, dstColor, srcAlpha, dstAlpha)
    override fun polygonOffset(factor: Float, units: Float) = jPolygonOffset(factor, units)

    override fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val p = jProgram(vertexSource, fragmentSource)
        check(p != 0) { "WebGL refused a shader:\n" + jProgramLog() }
        return p
    }
    override fun deleteProgram(program: Int) = jDeleteProgram(program)
    override fun useProgram(program: Int) = jUseProgram(program)
    override fun uniformLocation(program: Int, name: String): Int = jUniformLocation(program, name)
    override fun uniform1i(location: Int, v: Int) { if (location >= 0) jUniform1i(location, v) }
    override fun uniform1f(location: Int, v: Float) { if (location >= 0) jUniform1f(location, v) }
    override fun uniform2f(location: Int, x: Float, y: Float) { if (location >= 0) jUniform2f(location, x, y) }
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) { if (location >= 0) jUniform4f(location, x, y, z, w) }
    override fun uniform4fv(location: Int, values: FloatArray, from: Int, count: Int) { if (location >= 0) floatsOut(values, from, 4 * count) { jUniform4fv(location, it, count) } }
    override fun uniformMatrix4(location: Int, m: FloatArray, from: Int) { if (location >= 0) floatsOut(m, from, 16) { jUniformMatrix4(location, it) } }
    override fun uniformBlockBinding(program: Int, blockName: String, binding: Int) = jUniformBlockBinding(program, blockName, binding)

    override fun createBuffer(): Int = jCreateBuffer()
    override fun deleteBuffer(buffer: Int) = jDeleteBuffer(buffer)
    override fun bindBuffer(target: Int, buffer: Int) = jBindBuffer(target, buffer)
    override fun bufferData(target: Int, bytes: Int, usage: Int) = jBufferSize(target, bytes, usage)
    override fun bufferData(target: Int, data: FloatArray, from: Int, count: Int, usage: Int) = floatsOut(data, from, count) { jBufferData(target, it, count, usage) }
    override fun bufferSubData(target: Int, offsetBytes: Int, data: FloatArray, from: Int, count: Int) { if (count > 0) floatsOut(data, from, count) { jBufferSubData(target, offsetBytes, it, count) } }
    override fun bindBufferBase(target: Int, index: Int, buffer: Int) = jBindBufferBase(target, index, buffer)

    override fun createVertexArray(): Int = jCreateVertexArray()
    override fun deleteVertexArray(array: Int) = jDeleteVertexArray(array)
    override fun bindVertexArray(array: Int) = jBindVertexArray(array)
    override fun enableVertexAttribArray(index: Int) = jEnableAttrib(index)
    override fun disableVertexAttribArray(index: Int) = jDisableAttrib(index)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) = jAttribPointer(index, size, type, normalized, stride, offset)
    override fun vertexAttribDivisor(index: Int, divisor: Int) = jAttribDivisor(index, divisor)
    override fun drawArrays(mode: Int, first: Int, count: Int) = jDrawArrays(mode, first, count)
    override fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int) = jDrawArraysInstanced(mode, first, count, instances)

    override fun createTexture(): Int = jCreateTexture()
    override fun deleteTexture(texture: Int) = jDeleteTexture(texture)
    override fun activeTexture(unit: Int) = jActiveTexture(unit)
    override fun bindTexture(target: Int, texture: Int) = jBindTexture(target, texture)
    override fun texParameteri(target: Int, name: Int, value: Int) = jTexParameteri(target, name, value)
    override fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int) = jTexStorage2D(target, levels, internalFormat, width, height)
    override fun texStorage3D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int, depth: Int) = jTexStorage3D(target, levels, internalFormat, width, height, depth)
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteArray, from: Int) {
        val n = width * height * channels(format)
        bytesOut(data, from, n) { jTexSubImage2D(target, level, x, y, width, height, format, type, it, n) }
    }
    override fun texSubImage3D(target: Int, level: Int, x: Int, y: Int, z: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, data: ByteArray, from: Int) {
        val n = width * height * depth * channels(format)
        bytesOut(data, from, n) { jTexSubImage3D(target, level, x, y, z, width, height, depth, format, type, it, n) }
    }
    private fun channels(format: Int) = when (format) { GL.RGBA -> 4; GL.RG -> 2; GL.RED -> 1; else -> error("texSubImage: bytes of format $format are not handled") }
    override fun generateMipmap(target: Int) = jGenerateMipmap(target)

    override fun createFramebuffer(): Int = jCreateFramebuffer()
    override fun deleteFramebuffer(framebuffer: Int) = jDeleteFramebuffer(framebuffer)
    override fun bindFramebuffer(target: Int, framebuffer: Int) = jBindFramebuffer(target, framebuffer)
    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) = jFramebufferTexture2D(target, attachment, textureTarget, texture, level)
    override fun framebufferTextureLayer(target: Int, attachment: Int, texture: Int, level: Int, layer: Int) = jFramebufferTextureLayer(target, attachment, texture, level, layer)
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbuffer: Int) = jFramebufferRenderbuffer(target, attachment, renderbuffer)
    override fun createRenderbuffer(): Int = jCreateRenderbuffer()
    override fun deleteRenderbuffer(renderbuffer: Int) = jDeleteRenderbuffer(renderbuffer)
    override fun bindRenderbuffer(renderbuffer: Int) = jBindRenderbuffer(renderbuffer)
    override fun renderbufferStorage(samples: Int, internalFormat: Int, width: Int, height: Int) = jRenderbufferStorage(samples, internalFormat, width, height)
    override fun checkFramebufferStatus(target: Int): Int = jCheckFramebuffer(target)
    override fun blitFramebuffer(srcX0: Int, srcY0: Int, srcX1: Int, srcY1: Int, dstX0: Int, dstY0: Int, dstX1: Int, dstY1: Int, mask: Int, filter: Int) =
        jBlit(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, out: ByteArray) {
        val bytes = bytesIn(4 * width * height) { jReadPixels(x, y, width, height, it) }
        bytes.copyInto(out, 0, 0, minOf(out.size, bytes.size))
    }
    override val maxSamples: Int by lazy { jMaxSamples() }
    override fun getError(): Int = jGetError()
}
