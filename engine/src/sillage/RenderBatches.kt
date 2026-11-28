package sillage

import sillage.math.Mat4
import sillage.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

// What a renderer decides before drawing, whatever draws: which shadow squares are drawn again this frame,
// which copies the camera sees and which cast into each square, grouped by mesh; and the frame's uniform
// block. The Vulkan renderer and the OpenGL ES renderer both use it, so that the two draw the same things.

/** Floats of the frame's uniform block (frame.glsl): fourteen vectors, the shadow squares, then the lights' places and colours. */
internal const val FRAME_FLOATS = (14 + CASCADES + 2 * MAX_LIGHTS) * 4

/** Copies of one mesh to draw this frame: their model rows (12 floats each), reused from frame to frame. */
internal class Batch(val mesh: Mesh) {
    var count = 0
    var rows = FloatArray(12 * 8)
    /** Where its rows start in the frame's instance buffer (bytes). */
    var start = 0
    var water: WaterLook? = null
    /** What the renderer keeps of the mesh on the GPU (its own business). */
    var gpu: Any? = null
}

/**
 * The frame's batches. [prepare] is called for every mesh that will be drawn or cast a shadow, before it
 * is added: the renderer sends it to the GPU the first time (and again when a dynamic mesh has changed).
 */
internal class RenderBatches(private val prepare: (Mesh) -> Unit) {
    private val batches = HashMap<Mesh, Batch>()            // copies that cast a shadow
    private val quietBatches = HashMap<Mesh, Batch>()       // copies that do not (decals, unlit things…)
    private val skyBatches = HashMap<Mesh, Batch>()
    private val shadowBatches = HashMap<Mesh, Batch>()      // blob shadows
    private val waterBatches = HashMap<Mesh, Batch>()
    private val casterBatches = Array(CASCADES) { HashMap<Mesh, Batch>() }   // copies drawn into each cascade's map
    val casters = Array(CASCADES) { ArrayList<Batch>() }
    val opaque = ArrayList<Batch>()
    val quiet = ArrayList<Batch>()
    val skies = ArrayList<Batch>()
    val shadows = ArrayList<Batch>()
    val waters = ArrayList<Batch>()

    /**
     * Each mesh keeps its batches for this renderer ([Mesh.batches], valid while [Mesh.batchOwner] is
     * [token]): a world of many copies looks them up without hashing.
     */
    var token = Any()
        private set
    private val maps: Array<HashMap<Mesh, Batch>> = arrayOf(batches, quietBatches, skyBatches, shadowBatches, waterBatches, *casterBatches)

    /** The frame's shadow squares: centre x, y, z and half side per cascade. */
    val squares = FloatArray(4 * CASCADES)
    /**
     * The farther squares are drawn again less often: the finest every frame, the second every two
     * frames, the third every four (at most two shadow passes per frame). A square not drawn again keeps
     * the place it was drawn at ([drawnSquares]), so that the shaders read its map where it really is.
     */
    private val drawnSquares = FloatArray(4 * CASCADES)
    private val cascadeValid = BooleanArray(CASCADES)
    val cascadeNow = BooleanArray(CASCADES)
    private var cascadeFrames = 0L

    /** Frustum planes of the frame (a, b, c, d each, normalised; inside: a·x + b·y + c·z + d ≥ 0). */
    private val planes = FloatArray(24)
    /** Whether what the camera cannot see is left out (true); false draws everything, for comparisons. */
    var cull = true
    /** What culling left out this frame, and kept (counted per copy: a node or a part). */
    var culled = 0; private set
    var drawn = 0; private set

    /** A new scene (or new shadow maps): nothing is kept of the meshes, and every shadow map is drawn at the next frame. */
    fun forget() {
        for (m in maps) m.clear()
        for (list in casters) list.clear()
        opaque.clear(); quiet.clear(); skies.clear(); shadows.clear(); waters.clear()
        token = Any()
        cascadeValid.fill(false)
    }

    /** The shadow maps were made again: all of them are drawn at the next frame. */
    fun newShadowMaps() { cascadeValid.fill(false) }

    /**
     * Chooses the main light's squares for this frame, finest first: each one's centre (snapped to its
     * texels, so that shadows stay still while the camera moves) and half side; 0 for none ([on] false).
     */
    fun schedule(on: Boolean, lighting: Lighting, focus: Vec3?, main: Vec3, shadowSize: Int) {
        val n = cascadeFrames++
        for (c in 0 until CASCADES) {
            val half = if (on) lighting.cascadeHalf(c) else 0f
            val due = c == 0 || !cascadeValid[c] || (half > 0f) != (drawnSquares[4 * c + 3] > 0f) ||
                (c == 1 && n % 2L == 1L) || (c == 2 && n % 4L == 2L)
            cascadeNow[c] = due
            if (due) {
                val ctr = if (half > 0f) snapped(focus!!, 2f * half / shadowSize, main) else Vec3.ZERO
                drawnSquares[4 * c] = ctr.x; drawnSquares[4 * c + 1] = ctr.y; drawnSquares[4 * c + 2] = ctr.z; drawnSquares[4 * c + 3] = half
                cascadeValid[c] = true
            }
            for (k in 0..3) squares[4 * c + k] = drawnSquares[4 * c + k]
        }
    }

    /**
     * The shadow map's centre: [focus] moved to the nearest corner of a map texel, so that the shadows' edges
     * stay still while the camera moves (the basis is the one of light.glsl).
     */
    private fun snapped(focus: Vec3, texel: Float, l: Vec3): Vec3 {
        val ref = if (abs(l.y) > .99f) Vec3.Z else Vec3.Y
        val right = (ref cross l).normalized(); val up = l cross right
        val u = focus dot right; val v = focus dot up
        return focus + right * (round(u / texel) * texel - u) + up * (round(v / texel) * texel - v)
    }

    /** Adds one copy of [mesh] with the model rows of [node] (times [pose] for a part of an animated model). */
    private fun add(slot: Int, order: ArrayList<Batch>, mesh: Mesh, node: Node, pose: Mat4?): Batch? {
        if (mesh.vertices.isEmpty()) return null
        var slots = mesh.batches
        if (mesh.batchOwner !== token || slots == null) { slots = arrayOfNulls(5 + CASCADES); mesh.batches = slots; mesh.batchOwner = token }
        val b = (slots[slot] as Batch?) ?: maps[slot].getOrPut(mesh) { Batch(mesh) }.also { slots[slot] = it }
        if (b.count == 0) order.add(b)
        if (b.rows.size < 12 * (b.count + 1)) b.rows = b.rows.copyOf(b.rows.size * 2)
        val r = b.rows; val o = 12 * b.count
        // Node rows: rotation × scale, then the translation (no Mat4 is made).
        val q = node.rotation; val k = node.scale; val p = node.position
        val x = q.x; val y = q.y; val z = q.z; val w = q.w
        val a00 = (1 - 2 * (y * y + z * z)) * k; val a01 = 2 * (x * y - z * w) * k; val a02 = 2 * (x * z + y * w) * k
        val a10 = 2 * (x * y + z * w) * k; val a11 = (1 - 2 * (x * x + z * z)) * k; val a12 = 2 * (y * z - x * w) * k
        val a20 = 2 * (x * z - y * w) * k; val a21 = 2 * (y * z + x * w) * k; val a22 = (1 - 2 * (x * x + y * y)) * k
        if (pose == null) {
            r[o] = a00; r[o + 1] = a01; r[o + 2] = a02; r[o + 3] = p.x
            r[o + 4] = a10; r[o + 5] = a11; r[o + 6] = a12; r[o + 7] = p.y
            r[o + 8] = a20; r[o + 9] = a21; r[o + 10] = a22; r[o + 11] = p.z
        } else {
            // Mat4 names its coefficients column first (m30 m31 m32: the translation).
            r[o] = a00 * pose.m00 + a01 * pose.m01 + a02 * pose.m02; r[o + 1] = a00 * pose.m10 + a01 * pose.m11 + a02 * pose.m12
            r[o + 2] = a00 * pose.m20 + a01 * pose.m21 + a02 * pose.m22; r[o + 3] = a00 * pose.m30 + a01 * pose.m31 + a02 * pose.m32 + p.x
            r[o + 4] = a10 * pose.m00 + a11 * pose.m01 + a12 * pose.m02; r[o + 5] = a10 * pose.m10 + a11 * pose.m11 + a12 * pose.m12
            r[o + 6] = a10 * pose.m20 + a11 * pose.m21 + a12 * pose.m22; r[o + 7] = a10 * pose.m30 + a11 * pose.m31 + a12 * pose.m32 + p.y
            r[o + 8] = a20 * pose.m00 + a21 * pose.m01 + a22 * pose.m02; r[o + 9] = a20 * pose.m10 + a21 * pose.m11 + a22 * pose.m12
            r[o + 10] = a20 * pose.m20 + a21 * pose.m21 + a22 * pose.m22; r[o + 11] = a20 * pose.m30 + a21 * pose.m31 + a22 * pose.m32 + p.z
        }
        b.count++
        return b
    }

    private fun plane(k: Int, a: Float, b: Float, c: Float, d: Float) {
        val l = sqrt(a * a + b * b + c * c).coerceAtLeast(1e-12f)
        planes[k] = a / l; planes[k + 1] = b / l; planes[k + 2] = c / l; planes[k + 3] = d / l
    }

    private fun extractPlanes(m: Mat4) {
        // Gribb & Hartmann: the rows of view × projection added to and taken from the last one.
        plane(0, m.m03 + m.m00, m.m13 + m.m10, m.m23 + m.m20, m.m33 + m.m30)
        plane(4, m.m03 - m.m00, m.m13 - m.m10, m.m23 - m.m20, m.m33 - m.m30)
        plane(8, m.m03 + m.m01, m.m13 + m.m11, m.m23 + m.m21, m.m33 + m.m31)
        plane(12, m.m03 - m.m01, m.m13 - m.m11, m.m23 - m.m21, m.m33 - m.m31)
        // Depth goes from 0 to 1, as Vulkan's: near is row 2 alone, far is row 3 − row 2.
        plane(16, m.m02, m.m12, m.m22, m.m32)
        plane(20, m.m03 - m.m02, m.m13 - m.m12, m.m23 - m.m22, m.m33 - m.m32)
    }

    private fun inFrustum(x: Float, y: Float, z: Float, r: Float): Boolean {
        for (i in 0 until 6) { val o = 4 * i; if (planes[o] * x + planes[o + 1] * y + planes[o + 2] * z + planes[o + 3] < -r) return false }
        return true
    }

    // What the last test found: whether the copy is seen, and the cascades it casts into (one bit each).
    private var seen = true
    private var casting = 0
    // The frame's eye, haze and shadow axes, for the tests.
    private var ex = 0f; private var ey = 0f; private var ez = 0f; private var haze = 0f
    private var rx = 0f; private var ry = 0f; private var rz = 0f; private var ux = 0f; private var uy = 0f; private var uz = 0f
    private var culling = true

    private fun place(o: Node, wx: Float, wy: Float, wz: Float, r: Float) {
        val dx = wx - ex; val dy = wy - ey; val dz = wz - ez
        val dist = sqrt(dx * dx + dy * dy + dz * dz) - r
        val near = dist <= haze && dist <= o.drawDistance
        seen = near && inFrustum(wx, wy, wz, r)
        casting = 0
        if (!near) return
        for (c in 0 until CASCADES) {
            val half = squares[4 * c + 3]
            if (half <= 0f || !cascadeNow[c]) continue
            val px = wx - squares[4 * c]; val py = wy - squares[4 * c + 1]; val pz = wz - squares[4 * c + 2]
            if (abs(px * rx + py * ry + pz * rz) <= half + r && abs(px * ux + py * uy + pz * uz) <= half + r) casting = casting or (1 shl c)
        }
    }

    /** Whether a copy of [mesh] placed by node [o] and [pose] is seen, then whether it casts into the maps. */
    private fun test(mesh: Mesh, o: Node, pose: Mat4?) {
        if (!culling) { seen = true; casting = (1 shl CASCADES) - 1; return }
        if (pose == null) {
            // A node that has not moved since the last frame keeps its world sphere (its place and mesh are immutable values).
            val c = o.cullCache
            if (o.cullPosition !== o.position || o.cullRotation !== o.rotation || o.cullScale != o.scale || o.cullMesh !== mesh) {
                val sp = mesh.sphere
                val q = o.rotation; val qx = q.x; val qy = q.y; val qz = q.z; val qw = q.w
                val cx = sp[0]; val cy = sp[1]; val cz = sp[2]
                val tx = 2f * (qy * cz - qz * cy); val ty = 2f * (qz * cx - qx * cz); val tz = 2f * (qx * cy - qy * cx)
                val k = o.scale; val p = o.position
                c[0] = (cx + qw * tx + (qy * tz - qz * ty)) * k + p.x; c[1] = (cy + qw * ty + (qz * tx - qx * tz)) * k + p.y; c[2] = (cz + qw * tz + (qx * ty - qy * tx)) * k + p.z
                c[3] = sp[3] * (if (mesh.dynamic) 1.5f else 1f) * abs(k)
                o.cullPosition = o.position; o.cullRotation = o.rotation; o.cullScale = o.scale; o.cullMesh = mesh
            }
            place(o, c[0], c[1], c[2], c[3])
            return
        }
        val sp = mesh.sphere
        var r = sp[3] * (if (mesh.dynamic) 1.5f else 1f)
        val cx = pose.m00 * sp[0] + pose.m10 * sp[1] + pose.m20 * sp[2] + pose.m30
        val cy = pose.m01 * sp[0] + pose.m11 * sp[1] + pose.m21 * sp[2] + pose.m31
        val cz = pose.m02 * sp[0] + pose.m12 * sp[1] + pose.m22 * sp[2] + pose.m32
        val sx = pose.m00 * pose.m00 + pose.m01 * pose.m01 + pose.m02 * pose.m02; val sy = pose.m10 * pose.m10 + pose.m11 * pose.m11 + pose.m12 * pose.m12
        val sz = pose.m20 * pose.m20 + pose.m21 * pose.m21 + pose.m22 * pose.m22
        r *= sqrt(maxOf(sx, sy, sz))
        // The centre turned by the node's rotation (q v q*, written out: nothing allocated), scaled, moved.
        val q = o.rotation; val qx = q.x; val qy = q.y; val qz = q.z; val qw = q.w
        val tx = 2f * (qy * cz - qz * cy); val ty = 2f * (qz * cx - qx * cz); val tz = 2f * (qx * cy - qy * cx)
        val k = o.scale; val p = o.position
        val wx = (cx + qw * tx + (qy * tz - qz * ty)) * k + p.x; val wy = (cy + qw * ty + (qz * tx - qx * tz)) * k + p.y; val wz = (cz + qw * tz + (qx * ty - qy * tx)) * k + p.z
        r *= abs(k)
        place(o, wx, wy, wz, r)
    }

    /** One bit per node of the draw list: the nodes this frame visits (when the scene keeps still cells). */
    private var marks = LongArray(64)
    private var eyeSize = 0f

    /**
     * The frame's copies, grouped by mesh. With [cull], what the camera cannot see (outside the frustum of
     * [viewProj], past [fogEnd] or past its node's draw distance) is left out, and only what stands in a
     * cascade's square ([squares], seen along [light]) casts into that cascade's map. When [nodes] is a scene's
     * draw list, the cells of its still nodes ([StillCells]) are tested first: a cell left out leaves out all
     * its nodes at once, and the nodes kept are visited in the list's order, so that the batches are the same.
     */
    fun gather(nodes: List<Node>, hidden: Node?, viewProj: Mat4, eye: Vec3, fogEnd: Float, light: Vec3) {
        for (list in casters) for (b in list) b.count = 0
        for (b in opaque) b.count = 0
        for (b in quiet) b.count = 0
        for (b in skies) b.count = 0
        for (b in shadows) b.count = 0
        for (b in waters) b.count = 0
        for (list in casters) list.clear()
        opaque.clear(); quiet.clear(); skies.clear(); shadows.clear(); waters.clear()
        culled = 0; drawn = 0
        culling = cull
        if (culling) extractPlanes(viewProj)
        ex = eye.x; ey = eye.y; ez = eye.z; haze = if (fogEnd > 0f) fogEnd else Float.POSITIVE_INFINITY
        // The shadow square's axes, as light.glsl lays them.
        val ref = if (abs(light.y) > .99f) Vec3.Z else Vec3.Y
        val sRight = (ref cross light).normalized(); val sUp = light cross sRight
        rx = sRight.x; ry = sRight.y; rz = sRight.z; ux = sUp.x; uy = sUp.y; uz = sUp.z
        val still = if (culling && nodes.isNotEmpty()) nodes[0].scene.stillCellsOf(nodes) else null
        if (still == null || still.cells.isEmpty()) {
            for (k in nodes.indices) visit(nodes[k], hidden, eye)
            return
        }
        // The nodes to visit: every one outside the cells, and those of the cells not left out whole.
        val words = (nodes.size + 63) ushr 6
        if (marks.size < words) marks = LongArray(2 * words)
        val m = marks
        m.fill(0L, 0, words)
        val moving = still.moving
        for (k in 0 until moving.size) { val r = moving[k].rank; m[r ushr 6] = m[r ushr 6] or (1L shl r) }
        val cells = still.cells
        eyeSize = max(max(abs(ex), abs(ey)), abs(ez))
        for (k in 0 until cells.size) {
            val c = cells[k]
            if (c.count == 0) continue
            if (leftOut(c)) {
                // As if each of its nodes had been visited and found unseen (the hidden node is never counted).
                culled += c.count
                if (hidden != null && hidden.cell === c) culled--
                continue
            }
            val ns = c.nodes
            for (j in 0 until c.count) { val r = ns[j]!!.rank; m[r ushr 6] = m[r ushr 6] or (1L shl r) }
        }
        for (w in 0 until words) {
            var bits = m[w]
            while (bits != 0L) {
                visit(nodes[(w shl 6) + bits.countTrailingZeroBits()], hidden, eye)
                bits = bits and (bits - 1)
            }
        }
    }

    /**
     * Whether no node of [c] can be seen nor cast into a map drawn this frame: its box (which holds every node's
     * sphere) is past the haze and the nodes' draw distances, or outside the frustum and every shadow square due.
     * The tests keep a margin, so that a cell is left out only when each of its nodes surely would be.
     */
    private fun leftOut(c: StillCell): Boolean {
        val eps = 1e-3f + 2e-5f * (c.size + eyeSize)
        val dx = ex - ex.coerceIn(c.minX, c.maxX); val dy = ey - ey.coerceIn(c.minY, c.maxY); val dz = ez - ez.coerceIn(c.minZ, c.maxZ)
        if (sqrt(dx * dx + dy * dy + dz * dz) > minOf(haze, c.far) + eps) return true
        var outside = false
        for (i in 0 until 6) {
            val o = 4 * i; val a = planes[o]; val b = planes[o + 1]; val cc = planes[o + 2]
            // The box's corner farthest along the plane's normal.
            val most = a * (if (a >= 0f) c.maxX else c.minX) + b * (if (b >= 0f) c.maxY else c.minY) + cc * (if (cc >= 0f) c.maxZ else c.minZ) + planes[o + 3]
            if (most < -eps) { outside = true; break }
        }
        if (!outside) return false
        val hx = (c.maxX - c.minX) * .5f; val hy = (c.maxY - c.minY) * .5f; val hz = (c.maxZ - c.minZ) * .5f
        for (k in 0 until CASCADES) {
            val half = squares[4 * k + 3]
            if (half <= 0f || !cascadeNow[k]) continue
            val px = (c.minX + c.maxX) * .5f - squares[4 * k]; val py = (c.minY + c.maxY) * .5f - squares[4 * k + 1]; val pz = (c.minZ + c.maxZ) * .5f - squares[4 * k + 2]
            val alongR = hx * abs(rx) + hy * abs(ry) + hz * abs(rz); val alongU = hx * abs(ux) + hy * abs(uy) + hz * abs(uz)
            if (abs(px * rx + py * ry + pz * rz) <= half + alongR + eps && abs(px * ux + py * uy + pz * uz) <= half + alongU + eps) return false
        }
        return true
    }

    /** One node of the frame: tested, then added to the batches it is seen in and to the maps it casts into. */
    private fun visit(o: Node, hidden: Node?, eye: Vec3) {
        if (!o.visible || o === hidden) return
        val mesh = o.meshAt(eye)
        if (o.isSky) { prepare(mesh); add(2, skies, mesh, o, null); return }
        if (mesh.vertices.isNotEmpty()) {
            test(mesh, o, null)
            if (seen || casting != 0) prepare(mesh)
            if (o.isShadow) { if (seen) add(3, shadows, mesh, o, null) else culled++; return }
            val look = o.water
            if (look != null) { if (seen) add(4, waters, mesh, o, null)?.water = look else culled++; return }
            val casts = o.castsShadow && !mesh.unlit
            if (seen) { drawn++; add(if (casts) 0 else 1, if (casts) opaque else quiet, mesh, o, null) } else culled++
            if (casts && casting != 0) for (c in 0 until CASCADES) if (casting and (1 shl c) != 0) add(5 + c, casters[c], mesh, o, null)
        }
        val parts = o.parts
        val casts = o.castsShadow
        for (j in parts.indices) {
            val p = parts[j]
            test(p.mesh, o, p.pose)
            if (!seen && casting == 0) { culled++; continue }
            prepare(p.mesh)
            if (seen) { drawn++; add(if (casts) 0 else 1, if (casts) opaque else quiet, p.mesh, o, p.pose) } else culled++
            if (casts && casting != 0) for (c in 0 until CASCADES) if (casting and (1 shl c) != 0) add(5 + c, casters[c], p.mesh, o, p.pose)
        }
    }

    /**
     * The frame's uniform block (frame.glsl) into [u] ([FRAME_FLOATS] floats): the light of the hour, the
     * sky, the haze, the wind, the camera's axes (from view × projection: its first row is the camera's
     * right, its last its forward direction), the shadow squares and the point lights.
     */
    fun writeFrame(u: FloatArray, l: Lighting, main: Vec3, vp: Mat4, extras: FrameExtras?, shadowSize: Int) {
        var o = 0
        val lights = extras?.lightCount ?: 0
        u[o++] = main.x; u[o++] = main.y; u[o++] = main.z; u[o++] = l.night
        o = colour(u, o, l.sunColor, l.fogStart)
        o = colour(u, o, l.shadeColor, l.fogEnd)
        o = colour(u, o, l.skyAmbient, lights.toFloat())
        o = colour(u, o, l.groundAmbient, shadowSize.toFloat())
        o = colour(u, o, l.horizon, 0f); o = colour(u, o, l.skyMiddle, 0f); o = colour(u, o, l.zenith, l.softness)
        val sun = l.sunDirection.normalized()
        u[o++] = sun.x; u[o++] = sun.y; u[o++] = sun.z; u[o++] = l.sunShows()
        val moon = l.moonDirection
        u[o++] = moon.x; u[o++] = moon.y; u[o++] = moon.z; u[o++] = l.moonShows()
        u[o++] = l.wind.x; u[o++] = l.wind.z; u[o++] = 0f; u[o++] = 0f
        val rl = sqrt(vp.m00 * vp.m00 + vp.m10 * vp.m10 + vp.m20 * vp.m20).coerceAtLeast(1e-6f)
        val rx = vp.m00 / rl; val ry = vp.m10 / rl; val rz = vp.m20 / rl
        val fl = sqrt(vp.m03 * vp.m03 + vp.m13 * vp.m13 + vp.m23 * vp.m23).coerceAtLeast(1e-6f)
        val fx = vp.m03 / fl; val fy = vp.m13 / fl; val fz = vp.m23 / fl
        u[o++] = rx; u[o++] = ry; u[o++] = rz; u[o++] = 0f
        u[o++] = ry * fz - rz * fy; u[o++] = rz * fx - rx * fz; u[o++] = rx * fy - ry * fx; u[o++] = 0f
        o = colour(u, o, l.sunGlow, 0f)
        for (k in 0 until 4 * CASCADES) u[o++] = squares[k]
        // The lights' places, then their colours; those past the count keep what they had (the shaders never read them).
        val a = extras?.lights
        if (a != null) for (i in 0 until minOf(lights, MAX_LIGHTS)) {
            u[o + 4 * i] = a[8 * i]; u[o + 4 * i + 1] = a[8 * i + 1]; u[o + 4 * i + 2] = a[8 * i + 2]; u[o + 4 * i + 3] = a[8 * i + 3]
            val q = o + 4 * MAX_LIGHTS + 4 * i
            u[q] = a[8 * i + 4]; u[q + 1] = a[8 * i + 5]; u[q + 2] = a[8 * i + 6]; u[q + 3] = 0f
        }
    }

    private fun colour(u: FloatArray, at: Int, c: Color, w: Float): Int {
        u[at] = c.r; u[at + 1] = c.g; u[at + 2] = c.b; u[at + 3] = w
        return at + 4
    }
}
