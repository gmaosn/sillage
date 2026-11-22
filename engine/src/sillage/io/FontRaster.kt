package sillage.io

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns outlines into coverage: each pixel gets the exact area of the shape inside it, accumulated as
 * signed areas along the edges then summed along the rows (the method of font-rs and of stb_truetype 2).
 * Curves are cut into lines finely enough (at most 1/50 of a pixel off). The fill follows the non-zero
 * rule for contours that do not overlap, and caps overlapping ones at full coverage.
 */
internal class FontRaster {
    private var acc = FloatArray(0)
    private var w = 0; private var h = 0

    /**
     * Rasterises [path] (font units, y up) scaled by [scale] pixels per unit, the font's origin at pixel
     * ([originX], [originY]) of a [width] × [height] image (y down), into [out] (one byte per pixel, row
     * by row from [outOffset] with [stride] bytes per row).
     */
    fun draw(path: GlyphPath, scale: Float, originX: Float, originY: Float, width: Int, height: Int,
             out: ByteArray, outOffset: Int = 0, stride: Int = width) {
        w = width; h = height
        val n = w * h + w + 2
        if (acc.size < n) acc = FloatArray(n) else acc.fill(0f, 0, n)
        val c = path.coords
        var k = 0; var p = 0
        var sx = 0f; var sy = 0f; var x = 0f; var y = 0f
        fun px(v: Float) = originX + v * scale
        fun py(v: Float) = originY - v * scale
        for (i in 0 until path.count) {
            when (path.commands[i].toInt()) {
                GlyphPath.MOVE -> { if (k > 0) line(x, y, sx, sy); sx = px(c[p]); sy = py(c[p + 1]); x = sx; y = sy; p += 2 }
                GlyphPath.LINE -> { val nx = px(c[p]); val ny = py(c[p + 1]); line(x, y, nx, ny); x = nx; y = ny; p += 2 }
                GlyphPath.QUAD -> {
                    val cx = px(c[p]); val cy = py(c[p + 1]); val ex = px(c[p + 2]); val ey = py(c[p + 3])
                    val ddx = x - 2 * cx + ex; val ddy = y - 2 * cy + ey
                    // A parabola stays within |p0 − 2p1 + p2| / (8 n²) of n equal chords.
                    val segs = max(1, ceil(sqrt(sqrt(ddx * ddx + ddy * ddy) / (8 * TOLERANCE))).toInt())
                    var lx = x; var ly = y
                    for (s in 1..segs) {
                        val t = s.toFloat() / segs; val u = 1 - t
                        val nx = u * u * x + 2 * u * t * cx + t * t * ex; val ny = u * u * y + 2 * u * t * cy + t * t * ey
                        line(lx, ly, nx, ny); lx = nx; ly = ny
                    }
                    x = ex; y = ey; p += 4
                }
                GlyphPath.CUBIC -> {
                    val ax = px(c[p]); val ay = py(c[p + 1]); val bx = px(c[p + 2]); val by = py(c[p + 3]); val ex = px(c[p + 4]); val ey = py(c[p + 5])
                    val d1x = x - 2 * ax + bx; val d1y = y - 2 * ay + by; val d2x = ax - 2 * bx + ex; val d2y = ay - 2 * by + ey
                    val dd = sqrt(max(d1x * d1x + d1y * d1y, d2x * d2x + d2y * d2y))
                    // |B''| ≤ 6·dd, and a chord strays at most |B''| / (8 n²).
                    val segs = max(1, ceil(sqrt(6 * dd / (8 * TOLERANCE))).toInt())
                    var lx = x; var ly = y
                    for (s in 1..segs) {
                        val t = s.toFloat() / segs; val u = 1 - t
                        val a = u * u * u; val b = 3 * u * u * t; val cc = 3 * u * t * t; val e = t * t * t
                        val nx = a * x + b * ax + cc * bx + e * ex; val ny = a * y + b * ay + cc * by + e * ey
                        line(lx, ly, nx, ny); lx = nx; ly = ny
                    }
                    x = ex; y = ey; p += 6
                }
            }
            k++
        }
        if (k > 0) line(x, y, sx, sy)
        // Sum along the rows: a closed contour's contributions cancel at the end of every row.
        var sum = 0f
        for (row in 0 until h) {
            val o = outOffset + row * stride; val a = row * w
            for (col in 0 until w) {
                sum += acc[a + col]
                val v = min(1f, abs(sum))
                out[o + col] = (v * 255f + .5f).toInt().toByte()
            }
        }
    }

    /** One edge: the area it closes to its right is added to the pixels it crosses (font-rs). */
    private fun line(x0in: Float, y0in: Float, x1in: Float, y1in: Float) {
        if (y0in == y1in) return
        val dir: Float; val ax: Float; val ay: Float; val bx: Float; val by: Float
        if (y0in < y1in) { dir = 1f; ax = x0in; ay = y0in; bx = x1in; by = y1in } else { dir = -1f; ax = x1in; ay = y1in; bx = x0in; by = y0in }
        // Keep the edge inside the image across: pixels to the left of x = 0 would wrap to the row above.
        val dxdy = (bx - ax) / (by - ay)
        var x = ax
        var yStart = ay
        if (yStart < 0f) { x -= yStart * dxdy; yStart = 0f }
        val yEnd = min(by, h.toFloat())
        if (yStart >= yEnd) return
        var row = floor(yStart).toInt()
        while (row < yEnd) {
            val top = max(row.toFloat(), yStart); val bottom = min(row + 1f, yEnd)
            val dy = bottom - top
            val xNext = x + dxdy * dy
            val d = dy * dir
            val linestart = row * w
            val xa = max(0f, min(x, xNext)); val xb = max(0f, max(x, xNext))
            val x0floor = floor(xa); val x0i = x0floor.toInt()
            val x1ceil = ceil(xb); val x1i = x1ceil.toInt()
            if (x1i <= x0i + 1) {
                val xmf = .5f * (x + xNext) - x0floor
                acc[linestart + x0i] += d - d * xmf
                acc[linestart + x0i + 1] += d * xmf
            } else {
                val s = 1f / (xb - xa)
                val x0f = xa - x0floor
                val a0 = .5f * s * (1 - x0f) * (1 - x0f)
                val x1f = xb - x1ceil + 1
                val am = .5f * s * x1f * x1f
                acc[linestart + x0i] += d * a0
                if (x1i == x0i + 2) acc[linestart + x0i + 1] += d * (1 - a0 - am)
                else {
                    val a1 = s * (1.5f - x0f)
                    acc[linestart + x0i + 1] += d * (a1 - a0)
                    for (xi in x0i + 2 until x1i - 1) acc[linestart + xi] += d * s
                    val a2 = a1 + (x1i - x0i - 3) * s
                    acc[linestart + x1i - 1] += d * (1 - a2 - am)
                }
                acc[linestart + x1i] += d * am
            }
            x = xNext
            row++
        }
    }

    companion object {
        /** How far a curve may stray from the lines that replace it, in pixels. */
        const val TOLERANCE = .02f
    }
}
