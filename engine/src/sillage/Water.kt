package sillage

import sillage.math.Vec3
import sillage.physics.BodyType
import sillage.physics.PhysicsWorld
import sillage.physics.overlaps
import sillage.physics.raycast
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Ground at most this high above the water counts as shore (a beach); higher things standing in the water do not. */
private const val SHORE_HEIGHT = .9f
/** A face steeper than this (its normal's height) where it cuts the water is a wall: a cliff, a rock, a post (foam at its foot). */
private const val WALL_SLOPE = .7f
/** How far from such a wall the distance is kept (m): the foam needs less than half a metre. */
private const val WALL_REACH = 1.5f
/** Open water this long in front of a wall (m) brings the waves in full; under 3 m none (a basin, a well). */
private const val FETCH = 14f
private val DI = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
private val DJ = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

/** How a water surface looks: the open water's colour, the lagoon's colour and how far from the shore the lagoon reaches (m). */
internal class WaterLook(val deep: Color, val shallow: Color, val reach: Float)

/**
 * The mesh of a water surface at [level], [size] metres across, centred on (0, 0): a grid of [cell]-metre
 * squares over every shore (wherever solid things rise above the water) and wide quads beyond. Each
 * vertex carries in its red channel its distance to the nearest shore in metres, from which the water
 * shader draws the lagoon, the foam and the surf; in its green channel 1 + its distance to the nearest place
 * where a steep solid thing cuts the water (a cliff, a rock, a post: [walls]), 0 when none is near, for the
 * foam at their feet. Built once, when the scene's solid things are in place.
 */
internal fun waterMesh(physics: PhysicsWorld, level: Float, size: Float, reach: Float, cell: Float = .5f): Mesh {
    val half = size / 2
    val far = 1000f
    // Where land may be: the static things near or above the water.
    var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
    for (m in physics.meshes) {
        val v = m.v
        var k = 0
        while (k < v.size) {
            if (v[k + 1] >= level - .5f) { x0 = min(x0, v[k]); x1 = max(x1, v[k]); z0 = min(z0, v[k + 2]); z1 = max(z1, v[k + 2]) }
            k += 3
        }
    }
    for (i in 0 until physics.bodyCount) {
        val b = physics.body(i)
        if (b.isRemoved || b.type != BodyType.STATIC) continue
        val p = b.position; val r = b.shape.boundingRadius
        if (p.y + r < level - .5f) continue
        x0 = min(x0, p.x - r); x1 = max(x1, p.x + r); z0 = min(z0, p.z - r); z1 = max(z1, p.z + r)
    }
    val g = MeshBuilder()
    val flat = Color(far, 0f, 0f)
    if (x0 > x1) {
        g.quad(vec(-half, level, -half), vec(-half, level, half), vec(half, level, half), vec(half, level, -half), flat)
        return Mesh(g.build().vertices.also { upNormals(it) })
    }
    // The grid: around the shores, with room for the lagoon and the surf, within the water.
    val margin = reach + 4f
    x0 = max(-half, x0 - margin); z0 = max(-half, z0 - margin); x1 = min(half, x1 + margin); z1 = min(half, z1 + margin)
    val step = max(cell, max(x1 - x0, z1 - z0) / 320f)
    val nx = ceil((x1 - x0) / step).toInt(); val nz = ceil((z1 - z0) / step).toInt()
    x1 = x0 + nx * step; z1 = z0 + nz * step
    val w = nx + 1
    // Shore: the first solid surface from above rises gently out of the water there (meshes and static
    // bodies). Higher things standing in the water (a pier's deck, a roof) leave the water under them open.
    val dist = FloatArray(w * (nz + 1)) { Float.MAX_VALUE }
    val above = BooleanArray(w * (nz + 1))                    // something solid at or above the water there (land, a wall's top)
    val down = Vec3(0f, -1f, 0f)
    for (j in 0..nz) for (i in 0..nx) {
        val hit = physics.raycast(Vec3(x0 + i * step, level + 200f, z0 + j * step), down, 200f, filter = { it.type == BodyType.STATIC })
        if (hit != null && hit.point.y >= level - .02f && hit.point.y <= level + SHORE_HEIGHT) dist[j * w + i] = 0f
        if (hit != null && hit.point.y >= level - .02f) above[j * w + i] = true
    }
    // Distance to the nearest land: each vertex keeps the offset to its nearest land vertex, passed on to its
    // neighbours in two sweeps (8SSEDT), then the field is smoothed once so that its contours stay round.
    val cells = w * (nz + 1)
    val ox = IntArray(cells) { if (dist[it] == 0f) 0 else 1 shl 14 }
    val oz = IntArray(cells) { if (dist[it] == 0f) 0 else 1 shl 14 }
    fun len2(k: Int) = ox[k].toLong() * ox[k] + oz[k].toLong() * oz[k]
    fun take(k: Int, i: Int, j: Int, di: Int, dj: Int) {
        if (i + di !in 0..nx || j + dj !in 0..nz) return
        val n = (j + dj) * w + i + di
        if (ox[n] == 1 shl 14) return
        val ax = ox[n] - di; val az = oz[n] - dj
        if (ax.toLong() * ax + az.toLong() * az < len2(k)) { ox[k] = ax; oz[k] = az }
    }
    for (j in 0..nz) {
        for (i in 0..nx) { val k = j * w + i; take(k, i, j, -1, 0); take(k, i, j, -1, -1); take(k, i, j, 0, -1); take(k, i, j, 1, -1) }
        for (i in nx downTo 0) take(j * w + i, i, j, 1, 0)
    }
    for (j in nz downTo 0) {
        for (i in nx downTo 0) { val k = j * w + i; take(k, i, j, 1, 0); take(k, i, j, 1, 1); take(k, i, j, 0, 1); take(k, i, j, -1, 1) }
        for (i in 0..nx) take(j * w + i, i, j, -1, 0)
    }
    for (k in 0 until cells) dist[k] = if (ox[k] == 1 shl 14) far else min(far, sqrt(len2(k).toFloat()) * step)
    val sharp = dist.copyOf()
    for (j in 1 until nz) for (i in 1 until nx) {
        val k = j * w + i
        if (sharp[k] == 0f) continue
        dist[k] = (sharp[k] * 4 + sharp[k - 1] + sharp[k + 1] + sharp[k - w] + sharp[k + w]) / 8
    }
    val wall = walls(physics, level, x0, z0, step, nx, nz)
    // How open the water is in front of a wall: the longest stretch of water from it, out of eight directions (a basin
    // between walls stays calm, a cliff on the open sea takes the waves). In the blue: 0 sheltered … 1 open.
    val exposed = FloatArray(w * (nz + 1))
    for (j in 0..nz) for (i in 0..nx) {
        val o = j * w + i
        if (wall[o] >= WALL_REACH) continue
        var longest = 0
        for (dir in 0 until 8) {
            val di = DI[dir]; val dj = DJ[dir]
            var n = 1
            while (n * step < FETCH) {
                val a = i + di * n; val b = j + dj * n
                if (a !in 0..nx || b !in 0..nz) { n = (FETCH / step).toInt(); break }      // the grid's edge: the open sea beyond
                if (above[b * w + a] || wall[b * w + a] < step * .5f) break          // land, or a wall thinner than the grid
                n++
            }
            longest = max(longest, n)
        }
        exposed[o] = ((longest * step - 3f) / (FETCH - 3f)).coerceIn(0f, 1f)
    }
    val v = FloatArray(nx * nz * 54)
    var k = 0
    fun vertex(i: Int, j: Int) {
        v[k] = x0 + i * step; v[k + 1] = level; v[k + 2] = z0 + j * step
        v[k + 4] = 1f; v[k + 6] = dist[j * w + i]; v[k + 7] = wall[j * w + i].let { if (it < WALL_REACH) 1f + it else 0f }
        v[k + 8] = exposed[j * w + i]
        k += 9
    }
    for (j in 0 until nz) for (i in 0 until nx) {
        vertex(i, j); vertex(i, j + 1); vertex(i + 1, j + 1)
        vertex(i, j); vertex(i + 1, j + 1); vertex(i + 1, j)
    }
    // The open water around the grid.
    fun band(ax: Float, az: Float, bx: Float, bz: Float) {
        if (bx <= ax || bz <= az) return
        g.quad(vec(ax, level, az), vec(ax, level, bz), vec(bx, level, bz), vec(bx, level, az), flat)
    }
    band(-half, -half, half, z0); band(-half, z1, half, half)
    band(-half, z0, x0, z1); band(x1, z0, half, z1)
    val open = g.build().vertices.also { upNormals(it) }
    return Mesh(v + open)
}

/**
 * Per vertex of the grid (x0, z0, [step], nx × nz cells), its distance to where a steep solid thing cuts the water at
 * [level] (the beaches have their shore distance instead): the lines where the static meshes' steep triangles cross
 * the water, and the static bodies' outlines at the water (a shape test around each vertex). Far: past [WALL_REACH].
 */
private fun walls(physics: PhysicsWorld, level: Float, x0: Float, z0: Float, step: Float, nx: Int, nz: Int): FloatArray {
    val w = nx + 1
    val d = FloatArray(w * (nz + 1)) { Float.MAX_VALUE }
    // The vertices within reach of a segment get their distance to it (the nearest segment wins).
    fun segment(ax: Float, az: Float, bx: Float, bz: Float) {
        val i0 = ((min(ax, bx) - WALL_REACH - x0) / step).toInt().coerceIn(0, nx); val i1 = ceil((max(ax, bx) + WALL_REACH - x0) / step).toInt().coerceIn(0, nx)
        val j0 = ((min(az, bz) - WALL_REACH - z0) / step).toInt().coerceIn(0, nz); val j1 = ceil((max(az, bz) + WALL_REACH - z0) / step).toInt().coerceIn(0, nz)
        val ex = bx - ax; val ez = bz - az; val len2 = ex * ex + ez * ez
        for (j in j0..j1) for (i in i0..i1) {
            val px = x0 + i * step - ax; val pz = z0 + j * step - az
            val t = if (len2 > 1e-12f) ((px * ex + pz * ez) / len2).coerceIn(0f, 1f) else 0f
            val qx = px - ex * t; val qz = pz - ez * t
            val dd = sqrt(qx * qx + qz * qz)
            val o = j * w + i
            if (dd < d[o]) d[o] = dd
        }
    }
    for (m in physics.meshes) {
        val v = m.v
        var k = 0
        while (k + 9 <= v.size) {
            val ay = v[k + 1]; val by = v[k + 4]; val cy = v[k + 7]
            if (min(ay, min(by, cy)) < level && max(ay, max(by, cy)) > level) {
                // Steep only: a beach's gentle slope keeps its own shore, as it always did.
                val ux = v[k + 3] - v[k]; val uy = by - ay; val uz = v[k + 5] - v[k + 2]
                val wx = v[k + 6] - v[k]; val wy = cy - ay; val wz = v[k + 8] - v[k + 2]
                val nX = uy * wz - uz * wy; val nY = uz * wx - ux * wz; val nZ = ux * wy - uy * wx
                val nl = sqrt(nX * nX + nY * nY + nZ * nZ)
                if (nl > 1e-12f && abs(nY) / nl < WALL_SLOPE) {
                    // Where the triangle's edges cross the water: two points, the segment the water touches.
                    var n = 0; var px0 = 0f; var pz0 = 0f; var px1 = 0f; var pz1 = 0f
                    for (e in 0..2) {
                        val a = k + 3 * e; val b = k + 3 * ((e + 1) % 3)
                        val ya = v[a + 1]; val yb = v[b + 1]
                        if ((ya < level) == (yb < level)) continue
                        val t = (level - ya) / (yb - ya)
                        val x = v[a] + (v[b] - v[a]) * t; val z = v[a + 2] + (v[b + 2] - v[a + 2]) * t
                        if (n == 0) { px0 = x; pz0 = z } else { px1 = x; pz1 = z }
                        n++
                    }
                    if (n >= 2) segment(px0, pz0, px1, pz1)
                }
            }
            k += 9
        }
    }
    // Static bodies standing in the water: their distance at the water, by a small shape test around each vertex near them.
    val probe = sillage.physics.Sphere(WALL_REACH)
    for (b in 0 until physics.bodyCount) {
        val body = physics.body(b)
        if (body.isRemoved || body.type != BodyType.STATIC) continue
        val p = body.position; val r = body.shape.boundingRadius
        if (p.y - r > level || p.y + r < level || r > 30f) continue                 // a sea floor of a slab: its sides are far
        val i0 = ((p.x - r - WALL_REACH - x0) / step).toInt().coerceIn(0, nx); val i1 = ceil((p.x + r + WALL_REACH - x0) / step).toInt().coerceIn(0, nx)
        val j0 = ((p.z - r - WALL_REACH - z0) / step).toInt().coerceIn(0, nz); val j1 = ceil((p.z + r + WALL_REACH - z0) / step).toInt().coerceIn(0, nz)
        for (j in j0..j1) for (i in i0..i1) {
            val o = j * w + i
            for (t in physics.overlaps(probe, Vec3(x0 + i * step, level, z0 + j * step), margin = 0f)) {
                if (t.body?.id != body.id) continue
                val dd = max(0f, t.separation + WALL_REACH)
                if (dd < d[o]) d[o] = dd
            }
        }
    }
    return d
}

/** Normals straight up (MeshBuilder gives flat normals from the winding; water is always seen from above). */
private fun upNormals(v: FloatArray) {
    var k = 0
    while (k < v.size) { v[k + 3] = 0f; v[k + 4] = 1f; v[k + 5] = 0f; k += 9 }
}
