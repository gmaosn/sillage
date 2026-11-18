package sillage

import sillage.math.Vec3
import sillage.physics.BodyType
import sillage.physics.PhysicsWorld
import sillage.physics.raycast
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Ground at most this high above the water counts as shore (a beach); higher things standing in the water do not. */
private const val SHORE_HEIGHT = .9f

/** How a water surface looks: the open water's colour, the lagoon's colour and how far from the shore the lagoon reaches (m). */
internal class WaterLook(val deep: Color, val shallow: Color, val reach: Float)

/**
 * The mesh of a water surface at [level], [size] metres across, centred on (0, 0): a grid of [cell]-metre
 * squares over every shore (wherever solid things rise above the water) and wide quads beyond. Each
 * vertex carries in its red channel its distance to the nearest shore in metres, from which the water
 * shader draws the lagoon, the foam and the surf. Built once, when the scene's solid things are in place.
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
    val down = Vec3(0f, -1f, 0f)
    for (j in 0..nz) for (i in 0..nx) {
        val hit = physics.raycast(Vec3(x0 + i * step, level + 200f, z0 + j * step), down, 200f, filter = { it.type == BodyType.STATIC })
        if (hit != null && hit.point.y >= level - .02f && hit.point.y <= level + SHORE_HEIGHT) dist[j * w + i] = 0f
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
    val v = FloatArray(nx * nz * 54)
    var k = 0
    fun vertex(i: Int, j: Int) {
        v[k] = x0 + i * step; v[k + 1] = level; v[k + 2] = z0 + j * step
        v[k + 4] = 1f; v[k + 6] = dist[j * w + i]
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

/** Normals straight up (MeshBuilder gives flat normals from the winding; water is always seen from above). */
private fun upNormals(v: FloatArray) {
    var k = 0
    while (k < v.size) { v[k + 3] = 0f; v[k + 4] = 1f; v[k + 5] = 0f; k += 9 }
}
