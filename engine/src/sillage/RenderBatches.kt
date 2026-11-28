package sillage

import sillage.math.Mat4
import sillage.math.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

// What a renderer decides before drawing, whatever draws: which shadow squares are drawn again this frame,
// which copies the camera sees and which cast into each square, grouped by mesh; which lamps the shaders get,
// their light grid and their shadow cubes; the blended copies in the order they are drawn among the particles;
// and the frame's uniform block. The Vulkan renderer and the OpenGL ES renderer both use it, so that the two
// draw the same things.

/** Floats of the frame's uniform block (frame.glsl): fourteen vectors, the shadow squares, the light grid's two, then the lights' places and colours. */
internal const val FRAME_FLOATS = (14 + CASCADES + 2 + 2 * MAX_LIGHTS) * 4
/** Bytes of the light grid (an RGBA image of [GRID_WIDTH] × [GRID_Z] texels: four per cluster). */
internal const val GRID_WIDTH = GRID_X * GRID_Y * 4
internal const val GRID_BYTES = GRID_WIDTH * GRID_Z * 4

/** A mesh's batches ([Mesh.batches]): the five kinds, the cascades, the blended copies, then the faces of the lamps' cubes (still, moving). */
private const val BLENDED = 5 + CASCADES
private const val LAMP_FACES = BLENDED + 1
private const val LAMP_MOVERS = LAMP_FACES + 6 * LAMP_SHADOWS
private const val BATCH_SLOTS = LAMP_MOVERS + 6 * LAMP_SHADOWS
/** A copy that has not moved for this many frames is still: drawn once into its lamps' cached faces. */
private const val STILL_FRAMES = 8

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
 * A lamp's shadow cube: which lamp holds it ([PointLight.id], −1: free), where it is drawn from, and per face
 * whether its cache (what stands still) was drawn for this lamp, what it showed then (a hash of the lamp and of
 * those copies), and whether the face read by the shaders holds moving things over the cache.
 */
private class LampCube {
    var id = -1
    var x = 0f; var y = 0f; var z = 0f; var r = 0f
    var importance = 0f
    var used = false
    var moved = false                          // its lamp moved this frame: every face must be drawn again
    val drawn = BooleanArray(6)                // the face read by the shaders is right for where the lamp is
    val cached = BooleanArray(6)               // the cache holds what stands still (what [shown] says)
    val shown = LongArray(6)
    val live = BooleanArray(6)
    val faces = arrayOfNulls<Mat4>(6)
    fun ready(): Boolean { for (d in drawn) if (!d) return false; return true }
    fun reset() { drawn.fill(false); cached.fill(false); live.fill(false) }
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
    private val blendedBatches = HashMap<Mesh, Batch>()     // the blended parts of meshes (a glTF material in BLEND mode)
    private val lampBatches = Array(6 * LAMP_SHADOWS) { HashMap<Mesh, Batch>() }   // copies drawn into each face of the lamps' cubes
    private val moverBatches = Array(6 * LAMP_SHADOWS) { HashMap<Mesh, Batch>() }  // the moving ones, drawn over the cache
    val casters = Array(CASCADES) { ArrayList<Batch>() }
    val opaque = ArrayList<Batch>()
    val quiet = ArrayList<Batch>()
    val skies = ArrayList<Batch>()
    val shadows = ArrayList<Batch>()
    val waters = ArrayList<Batch>()
    val blended = ArrayList<Batch>()
    /** What stands still and casts into face f of cube c (at 6 c + f): drawn into the face's cache when it changes. */
    val lampCasters = Array(6 * LAMP_SHADOWS) { ArrayList<Batch>() }
    /** What moves and casts into face f of cube c: drawn every frame over a copy of the cache. */
    val lampMovers = Array(6 * LAMP_SHADOWS) { ArrayList<Batch>() }

    /**
     * Each mesh keeps its batches for this renderer ([Mesh.batches], valid while [Mesh.batchOwner] is
     * [token]): a world of many copies looks them up without hashing.
     */
    var token = Any()
        private set
    private val maps: Array<HashMap<Mesh, Batch>> = arrayOf(batches, quietBatches, skyBatches, shadowBatches, waterBatches, *casterBatches, blendedBatches,
        *lampBatches, *moverBatches)

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
        for (list in lampCasters) list.clear()
        for (list in lampMovers) list.clear()
        opaque.clear(); quiet.clear(); skies.clear(); shadows.clear(); waters.clear(); blended.clear()
        blendedCount = 0
        token = Any()
        cascadeValid.fill(false)
        for (c in cubes) { c.id = -1; c.reset() }
    }

    /** The shadow maps were made again: all of them are drawn at the next frame. */
    fun newShadowMaps() { cascadeValid.fill(false); for (c in cubes) { c.reset() } }

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

    // ------------------------------------------------------------------ lamps

    /** Lamps that may have a shadow cube this frame (0: none), from the renderer's quality; the side of a face in texels. */
    private var lampLimit = 0
    var lampTexels = LAMP_TEXELS; private set
    /** Triangles drawn into the cubes' faces in a frame, at most (a face that does not fit waits for the next frame). */
    private var lampBudget = 0
    private val cubes = Array(LAMP_SHADOWS) { LampCube() }
    /** Whether the cache of face f of cube c (at 6 c + f) is drawn again this frame, with [lampCasters]. */
    val lampStatic = BooleanArray(6 * LAMP_SHADOWS)
    /** Whether the face the shaders read is made again this frame: its cache copied, then [lampMovers] drawn over it. */
    val lampLive = BooleanArray(6 * LAMP_SHADOWS)
    /** Whether the face the shaders read is drawn whole this frame (its lamp moved): [lampCasters] then [lampMovers], no cache. */
    val lampDirect = BooleanArray(6 * LAMP_SHADOWS)
    /** The view × projection face k is drawn with. */
    fun lampFace(k: Int): Mat4 = cubes[k / 6].faces[k % 6]!!

    /**
     * How many lamps may cast shadows at once and the side of their cubes' faces (see [RenderQuality]); the
     * variable SILLAGE_LAMP_SHADOWS=0 turns lamps' shadows off (comparisons, small GPUs).
     */
    fun lampShadows(count: Int, texels: Int) {
        val asked = sillage.io.Storage.environment("SILLAGE_LAMP_SHADOWS")?.toIntOrNull()
        lampLimit = min(asked ?: count, min(count, LAMP_SHADOWS)).coerceAtLeast(0)
        if (texels != lampTexels) for (c in cubes) c.reset()
        lampTexels = texels
        lampBudget = 30_000 * max(lampLimit, 1)
        for (c in lampLimit until LAMP_SHADOWS) cubes[c].id = -1
    }

    /** The lamps the shaders get this frame: their places in the scene's list (FrameExtras.lights), the nearest first. */
    private val shown = IntArray(MAX_LIGHTS)
    var shownCount = 0; private set
    /** Per lamp shown, 1 + its shadow cube (0: none, or not drawn whole yet). */
    private val shownCube = IntArray(MAX_LIGHTS)
    /** The light grid, RGBA bytes ([GRID_WIDTH] × [GRID_Z]): per cluster its number of lamps, then their places among those shown. */
    val grid = ByteArray(GRID_BYTES)
    /** Whether the shaders use the grid this frame (more than [LOOP_LIGHTS] lamps shown): the renderer sends [grid] then. */
    var gridOn = false; private set
    private var tileWidth = 1f; private var tileHeight = 1f
    private var sliceNear = 1.5f; private var slicesPerDoubling = 1f

    private fun slice(d: Float): Int =
        if (d < sliceNear) 0 else min(GRID_Z - 1, 1 + floor(ln(d / sliceNear) / LN2 * slicesPerDoubling).toInt())

    /**
     * The frame's lamps, from the scene's list (the nearest first, [FrameExtras.lights]): up to [LOOP_LIGHTS]
     * all of them, as always; past that only those whose light can reach what the camera sees (their reach on
     * water, two and a half radii, outside the frustum or beyond the haze: left out), and a light grid tells each
     * point of the picture which of them reach it (16 × 10 tiles, 16 slices of distance from the eye, 15 lamps
     * per cluster at most, the nearest). Then the lamps that cast shadows: the most important ones ([lampLimit]),
     * near the eye and bright, a lamp keeping its cube from frame to frame; each cube face is drawn again only
     * when what it shows changed, within a budget of triangles per frame. Called after [schedule], before [gather].
     */
    fun lights(extras: FrameExtras?, viewProj: Mat4, eye: Vec3, fogEnd: Float, width: Int, height: Int, shadows: Boolean) {
        shownCount = 0; gridOn = false
        val n = extras?.lightCount ?: 0
        val a = extras?.lights
        val far = if (fogEnd > 0f) fogEnd else Float.POSITIVE_INFINITY
        if (n <= LOOP_LIGHTS || a == null) for (i in 0 until n) shown[shownCount++] = i
        else {
            extractPlanes(viewProj)
            for (i in 0 until n) {
                val o = 8 * i; val reach = a[o + 3] * 2.5f
                val dx = a[o] - eye.x; val dy = a[o + 1] - eye.y; val dz = a[o + 2] - eye.z
                if (cull && (sqrt(dx * dx + dy * dy + dz * dz) - reach > far || !inFrustum(a[o], a[o + 1], a[o + 2], reach))) continue
                shown[shownCount++] = i
            }
            if (shownCount > LOOP_LIGHTS) buildGrid(a, viewProj, eye, far, width, height)
        }
        for (j in 0 until shownCount) shownCube[j] = 0
        chooseCubes(if (shadows && a != null) extras else null, viewProj, eye)
    }

    private fun buildGrid(a: FloatArray, vp: Mat4, eye: Vec3, haze: Float, width: Int, height: Int) {
        gridOn = true
        tileWidth = ceil(width.toFloat() / GRID_X); tileHeight = ceil(height.toFloat() / GRID_Y)
        var farthest = 0f
        for (j in 0 until shownCount) {
            val o = 8 * shown[j]
            val dx = a[o] - eye.x; val dy = a[o + 1] - eye.y; val dz = a[o + 2] - eye.z
            farthest = max(farthest, sqrt(dx * dx + dy * dy + dz * dz) + a[o + 3])
        }
        sliceNear = 1.5f
        val end = min(farthest, haze).coerceAtLeast(4f * sliceNear)
        slicesPerDoubling = (GRID_Z - 1) / (ln(end / sliceNear) / LN2)
        grid.fill(0)
        for (j in 0 until shownCount) {
            val o = 8 * shown[j]
            val x = a[o]; val y = a[o + 1]; val z = a[o + 2]; val r = a[o + 3]
            val dx = x - eye.x; val dy = y - eye.y; val dz = z - eye.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val s0 = slice(max(d - r, 0f)); val s1 = slice(d + r)
            // The tiles its sphere covers: the corners of the box around it, projected (all of them if one is behind the eye).
            var tx0 = 0; var tx1 = GRID_X - 1; var ty0 = 0; var ty1 = GRID_Y - 1
            var lx = Float.MAX_VALUE; var hx = -Float.MAX_VALUE; var ly = Float.MAX_VALUE; var hy = -Float.MAX_VALUE
            var behind = false
            for (c in 0 until 8) {
                val px = if (c and 1 == 0) x - r else x + r; val py = if (c and 2 == 0) y - r else y + r; val pz = if (c and 4 == 0) z - r else z + r
                val w = vp.m03 * px + vp.m13 * py + vp.m23 * pz + vp.m33
                if (w <= 1e-3f) { behind = true; break }
                val cx = (vp.m00 * px + vp.m10 * py + vp.m20 * pz + vp.m30) / w; val cy = (vp.m01 * px + vp.m11 * py + vp.m21 * pz + vp.m31) / w
                lx = min(lx, cx); hx = max(hx, cx); ly = min(ly, cy); hy = max(hy, cy)
            }
            if (!behind) {
                tx0 = floor((lx * .5f + .5f) * width / tileWidth).toInt().coerceIn(0, GRID_X - 1)
                tx1 = floor((hx * .5f + .5f) * width / tileWidth).toInt().coerceIn(0, GRID_X - 1)
                ty0 = floor((ly * .5f + .5f) * height / tileHeight).toInt().coerceIn(0, GRID_Y - 1)
                ty1 = floor((hy * .5f + .5f) * height / tileHeight).toInt().coerceIn(0, GRID_Y - 1)
            }
            for (s in s0..s1) for (ty in ty0..ty1) for (tx in tx0..tx1) {
                val at = 4 * GRID_WIDTH * s + 16 * (ty * GRID_X + tx)
                val count = grid[at].toInt()
                if (count >= GRID_LAMPS) continue
                grid[at + 1 + count] = j.toByte()
                grid[at] = (count + 1).toByte()
            }
        }
    }

    /** The lamps that get a shadow cube this frame (none when [extras] is null), and which faces of each are drawn again. */
    private fun chooseCubes(extras: FrameExtras?, vp: Mat4, eye: Vec3) {
        for (c in cubes) { c.used = false; c.moved = false }
        lampStatic.fill(false); lampLive.fill(false); lampDirect.fill(false)
        if (extras == null || lampLimit == 0) { for (c in cubes) c.id = -1; return }
        val a = extras.lights
        if (cull) extractPlanes(vp)
        // The most important lamps: bright, and near the eye for their size (a lamp holding a cube counts a little more, so that two
        // lamps of about the same importance do not take turns).
        var picked = 0
        for (j in 0 until shownCount) {
            val i = shown[j]; val o = 8 * i
            val power = a[o + 4] + a[o + 5] + a[o + 6]
            val r = a[o + 3]
            if (power < .02f || r <= .2f) continue
            if (cull && !inFrustum(a[o], a[o + 1], a[o + 2], r)) continue
            val dx = a[o] - eye.x; val dy = a[o + 1] - eye.y; val dz = a[o + 2] - eye.z
            val d = max(sqrt(dx * dx + dy * dy + dz * dz), r * .5f)
            var importance = power * (r / d) * (r / d)
            val id = extras.lightIds[i]
            if (isCostly(id)) continue
            if (cubes.any { it.id == id }) importance *= 1.3f
            // Among the [lampLimit] best so far (candidates in shownCube's slots, by insertion).
            var k = picked
            if (k == lampLimit) { if (importance <= pickedImportance[k - 1]) continue; k-- } else picked++
            while (k > 0 && pickedImportance[k - 1] < importance) { pickedImportance[k] = pickedImportance[k - 1]; pickedLamp[k] = pickedLamp[k - 1]; k-- }
            pickedImportance[k] = importance; pickedLamp[k] = j
        }
        // Only lamps that matter in the picture: half as important as a bright lamp at its own radius, a tenth of the first.
        while (picked > 0 && (pickedImportance[picked - 1] < .5f || pickedImportance[picked - 1] < .1f * pickedImportance[0])) picked--
        // Those that hold a cube keep it; the others take a free one.
        for (p in 0 until picked) {
            val j = pickedLamp[p]; val id = extras.lightIds[shown[j]]
            for (c in 0 until lampLimit) if (cubes[c].id == id) { cubes[c].used = true; cubes[c].importance = pickedImportance[p]; pickedLamp[p] = -1 - c }
        }
        for (p in 0 until picked) {
            val j = pickedLamp[p]
            if (j < 0) continue
            val c = (0 until lampLimit).first { !cubes[it].used }
            val cube = cubes[c]
            cube.id = extras.lightIds[shown[j]]; cube.used = true; cube.importance = pickedImportance[p]; cube.reset(); cube.r = -1f
            pickedLamp[p] = -1 - c
        }
        for (c in cubes) if (!c.used) c.id = -1
        // Where each cube is drawn from: its lamp, now (the faces' views change only when it moves).
        for (j in 0 until shownCount) {
            val o = 8 * shown[j]; val id = extras.lightIds[shown[j]]
            for (c in 0 until lampLimit) {
                val cube = cubes[c]
                if (cube.id != id) continue
                shownCube[j] = -1 - c                            // ready or not: decided once the faces are known
                if (cube.x != a[o] || cube.y != a[o + 1] || cube.z != a[o + 2] || cube.r != a[o + 3]) {
                    if (cube.r >= 0f) cube.moved = true           // a cube just taken (r −1) fills its caches face by face instead
                    cube.cached.fill(false)
                    cube.x = a[o]; cube.y = a[o + 1]; cube.z = a[o + 2]; cube.r = a[o + 3]
                    for (f in 0 until 6) cube.faces[f] = faceView(f, cube.x, cube.y, cube.z, cube.r)
                }
            }
        }
    }
    private val pickedImportance = FloatArray(LAMP_SHADOWS + 1)
    private val pickedLamp = IntArray(LAMP_SHADOWS + 1)

    /**
     * View × projection of face [f] of the cube around a lamp at x, y, z of radius [r]: axes as shading.glsl's
     * lampLight takes them, a little wider than a right angle (a border of three texels), depth from the near
     * plane to the radius (0 to 1).
     */
    internal fun faceView(f: Int, x: Float, y: Float, z: Float, r: Float): Mat4 {
        // Forward, right, up of each face.
        val fx = FACES[9 * f]; val fy = FACES[9 * f + 1]; val fz = FACES[9 * f + 2]
        val rx = FACES[9 * f + 3]; val ry = FACES[9 * f + 4]; val rz = FACES[9 * f + 5]
        val ux = FACES[9 * f + 6]; val uy = FACES[9 * f + 7]; val uz = FACES[9 * f + 8]
        val s = 1f - 6f / lampTexels
        val near = (r * .1f).coerceIn(.15f, .8f)
        val a = r / (r - near); val b = -r * near / (r - near)
        val fl = fx * x + fy * y + fz * z; val rl = rx * x + ry * y + rz * z; val ul = ux * x + uy * y + uz * z
        // Rows: s·right, s·up, a·forward + b, forward (each against the lamp's place); Mat4 takes them column by column.
        return Mat4(s * rx, s * ux, a * fx, fx,
            s * ry, s * uy, a * fy, fy,
            s * rz, s * uz, a * fz, fz,
            -s * rl, -s * ul, -a * fl + b, -fl)
    }

    /**
     * After [gather]: what of the cubes is drawn this frame, and which lamps' cubes are whole (their shadows used).
     * A face's cache holds what stands still: drawn again when that changed (a copy came, went or moved, the lamp
     * moved), within [lampBudget] triangles per frame, the most important cube first; a face that does not fit
     * waits. A lamp that moved needs all its faces at once: if they do not fit, it casts no shadow this frame
     * rather than shadows from where it was. What moves is drawn every frame over a copy of the cache.
     */
    private fun scheduleFaces() {
        lampFrames++
        var spent = 0L
        for (rank in 0 until lampLimit) {
            var best = -1
            for (c in 0 until lampLimit) if (cubes[c].id >= 0 && !cubeRanked[c] && (best < 0 || cubes[c].importance > cubes[best].importance)) best = c
            if (best < 0) break
            cubeRanked[best] = true
            val cube = cubes[best]
            // Each face's hash of what stands still in it, and its triangles (what moves counted apart).
            var all = 0L
            for (f in 0 until 6) {
                val k = 6 * best + f
                var h = (cube.x.toRawBits().toLong() * 31L + cube.y.toRawBits()) * 31L + cube.z.toRawBits()
                h = h * 31L + cube.r.toRawBits()
                var triangles = 0L
                for (b in lampCasters[k]) {
                    h = h * 1099511628211L + b.mesh.hashCode()
                    h = h * 31L + b.count
                    val rows = b.rows
                    for (i in 0 until 12 * b.count) h = h * 31L + rows[i].toRawBits()
                    triangles += b.mesh.triangleCount.toLong() * b.count
                }
                var moving = 0L
                for (b in lampMovers[k]) moving += b.mesh.triangleCount.toLong() * b.count
                faceHash[f] = h; faceTriangles[f] = triangles; faceMoving[f] = moving
                all += triangles + moving
            }
            if (cube.moved) {
                // The lamp moved: every face drawn again at once, straight into the faces read (a cache would be stale next frame).
                // If they do not fit, it casts no shadow, and leaves its cube to another lamp for a while.
                if (spent + all > lampBudget) { cube.drawn.fill(false); costly[best] = cube.id; costlyFrames[best] = lampFrames; continue }
                spent += all
                for (f in 0 until 6) {
                    val k = 6 * best + f
                    lampDirect[k] = true; cube.drawn[f] = true; cube.cached[f] = false; cube.live[f] = lampMovers[k].isNotEmpty()
                }
                continue
            }
            for (f in 0 until 6) {
                val k = 6 * best + f
                if ((!cube.cached[f] || cube.shown[f] != faceHash[f]) && (spent == 0L || spent + faceTriangles[f] <= lampBudget)) {
                    spent += faceTriangles[f]
                    lampStatic[k] = true; cube.cached[f] = true; cube.shown[f] = faceHash[f]
                }
                // The face read by the shaders: made again from the cache when the cache changed, when something moves in it,
                // or when something moved in it last frame.
                val movers = lampMovers[k].isNotEmpty()
                if (cube.cached[f] && (lampStatic[k] || movers || cube.live[f] || !cube.drawn[f])) {
                    lampLive[k] = true; cube.drawn[f] = true; cube.live[f] = movers
                    spent += faceMoving[f]
                }
            }
        }
        cubeRanked.fill(false)
        for (j in 0 until shownCount) {
            val c = -1 - shownCube[j]
            shownCube[j] = if (shownCube[j] < 0 && cubes[c].ready()) 1 + c else 0
        }
        if (traceLamps && lampFrames % 30 == 0L) println("SILLAGE_LAMPES lamps=$shownCount grid=$gridOn caches=${lampStatic.count { it }} faces=${lampLive.count { it }} direct=${lampDirect.count { it }} triangles=$spent cubes=" +
            cubes.filter { it.id >= 0 }.joinToString { "#${it.id}(${"%.2f".fmt(it.importance)}, r ${it.r}${if (it.ready()) "" else ", not ready"})" })
    }
    private val faceHash = LongArray(6)
    private val faceTriangles = LongArray(6)
    private val faceMoving = LongArray(6)
    /** Lamps that moved and cost more than the budget (by cube, with the frame): they get no cube for a second. */
    private val costly = IntArray(LAMP_SHADOWS) { -1 }
    private val costlyFrames = LongArray(LAMP_SHADOWS)
    private fun isCostly(id: Int): Boolean {
        for (c in 0 until LAMP_SHADOWS) if (costly[c] == id) { if (lampFrames - costlyFrames[c] < 60) return true; costly[c] = -1 }
        return false
    }
    /** SILLAGE_LAMPES=1: twice a second, the lamps, their cubes and what was drawn of them. */
    private val traceLamps = sillage.io.Storage.environment("SILLAGE_LAMPES") != null
    private var lampFrames = 0L
    private val cubeRanked = BooleanArray(LAMP_SHADOWS)

    // ------------------------------------------------------------------ copies

    /**
     * Adds one copy of [mesh] with the model rows of [node] (times [pose] for a part of an animated model: its
     * 16 coefficients, column first, see [Part.m]).
     */
    private fun add(slot: Int, order: ArrayList<Batch>, mesh: Mesh, node: Node, pose: FloatArray?): Batch? {
        if (mesh.vertices.isEmpty()) return null
        var slots = mesh.batches
        if (mesh.batchOwner !== token || slots == null) { slots = arrayOfNulls(BATCH_SLOTS); mesh.batches = slots; mesh.batchOwner = token }
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
            // The pose is laid column first: pose[12], pose[13], pose[14] are its translation.
            r[o] = a00 * pose[0] + a01 * pose[1] + a02 * pose[2]; r[o + 1] = a00 * pose[4] + a01 * pose[5] + a02 * pose[6]
            r[o + 2] = a00 * pose[8] + a01 * pose[9] + a02 * pose[10]; r[o + 3] = a00 * pose[12] + a01 * pose[13] + a02 * pose[14] + p.x
            r[o + 4] = a10 * pose[0] + a11 * pose[1] + a12 * pose[2]; r[o + 5] = a10 * pose[4] + a11 * pose[5] + a12 * pose[6]
            r[o + 6] = a10 * pose[8] + a11 * pose[9] + a12 * pose[10]; r[o + 7] = a10 * pose[12] + a11 * pose[13] + a12 * pose[14] + p.y
            r[o + 8] = a20 * pose[0] + a21 * pose[1] + a22 * pose[2]; r[o + 9] = a20 * pose[4] + a21 * pose[5] + a22 * pose[6]
            r[o + 10] = a20 * pose[8] + a21 * pose[9] + a22 * pose[10]; r[o + 11] = a20 * pose[12] + a21 * pose[13] + a22 * pose[14] + p.z
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

    // What the last test found: whether the copy is seen, the cascades it casts into (one bit each), the faces of
    // the lamps' cubes it casts into (bit 6 c + f).
    private var seen = true
    private var casting = 0
    private var lampCasting = 0
    // The frame's eye, haze and shadow axes, for the tests.
    private var ex = 0f; private var ey = 0f; private var ez = 0f; private var haze = 0f
    private var rx = 0f; private var ry = 0f; private var rz = 0f; private var ux = 0f; private var uy = 0f; private var uz = 0f
    private var culling = true
    private var cubesOn = 0                          // bit c: cube c has a lamp this frame

    private fun place(o: Node, wx: Float, wy: Float, wz: Float, r: Float) {
        val dx = wx - ex; val dy = wy - ey; val dz = wz - ez
        val dist = sqrt(dx * dx + dy * dy + dz * dz) - r
        val near = dist <= haze && dist <= o.drawDistance
        seen = near && inFrustum(wx, wy, wz, r)
        casting = 0; lampCasting = 0
        if (!near) return
        for (c in 0 until CASCADES) {
            val half = squares[4 * c + 3]
            if (half <= 0f || !cascadeNow[c]) continue
            val px = wx - squares[4 * c]; val py = wy - squares[4 * c + 1]; val pz = wz - squares[4 * c + 2]
            if (abs(px * rx + py * ry + pz * rz) <= half + r && abs(px * ux + py * uy + pz * uz) <= half + r) casting = casting or (1 shl c)
        }
        if (cubesOn != 0) lampTest(wx, wy, wz, r)
    }

    /** The faces of the lamps' cubes a sphere at x, y, z of radius [r] stands in (their pyramids, a little wider than a right angle). */
    private fun lampTest(x: Float, y: Float, z: Float, r: Float) {
        val widen = 1f / (1f - 6f / lampTexels)
        val k = r * sqrt(1f + widen * widen)
        for (c in 0 until lampLimit) {
            if (cubesOn and (1 shl c) == 0) continue
            val cube = cubes[c]
            val dx = x - cube.x; val dy = y - cube.y; val dz = z - cube.z
            val reach = cube.r + r
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue
            for (f in 0 until 6) {
                // Along the face's axis, and the two across it (their signs do not matter).
                val along: Float; val p: Float; val q: Float
                when (f) {
                    0 -> { along = dx; p = dz; q = dy }; 1 -> { along = -dx; p = dz; q = dy }
                    2 -> { along = dy; p = dx; q = dz }; 3 -> { along = -dy; p = dx; q = dz }
                    4 -> { along = dz; p = dx; q = dy }; else -> { along = -dz; p = dx; q = dy }
                }
                if (along < -r) continue
                if (abs(p) - widen * along > k || abs(q) - widen * along > k) continue
                lampCasting = lampCasting or (1 shl (6 * c + f))
            }
        }
    }

    /** Whether a copy of [mesh] placed by node [o] and [pose] is seen, then whether it casts into the maps. */
    private fun test(mesh: Mesh, o: Node, pose: FloatArray?) {
        if (!culling) {
            seen = true; casting = (1 shl CASCADES) - 1
            lampCasting = 0
            for (c in 0 until lampLimit) if (cubesOn and (1 shl c) != 0) lampCasting = lampCasting or (63 shl (6 * c))
            return
        }
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
                o.stillFrames = 0
            } else if (o.stillFrames < STILL_FRAMES) o.stillFrames++
            place(o, c[0], c[1], c[2], c[3])
            return
        }
        val sp = mesh.sphere
        var r = sp[3] * (if (mesh.dynamic) 1.5f else 1f)
        val cx = pose[0] * sp[0] + pose[4] * sp[1] + pose[8] * sp[2] + pose[12]
        val cy = pose[1] * sp[0] + pose[5] * sp[1] + pose[9] * sp[2] + pose[13]
        val cz = pose[2] * sp[0] + pose[6] * sp[1] + pose[10] * sp[2] + pose[14]
        val sx = pose[0] * pose[0] + pose[1] * pose[1] + pose[2] * pose[2]; val sy = pose[4] * pose[4] + pose[5] * pose[5] + pose[6] * pose[6]
        val sz = pose[8] * pose[8] + pose[9] * pose[9] + pose[10] * pose[10]
        r *= sqrt(maxOf(sx, sy, sz))
        // The centre turned by the node's rotation (q v q*, written out: nothing allocated), scaled, moved.
        val q = o.rotation; val qx = q.x; val qy = q.y; val qz = q.z; val qw = q.w
        val tx = 2f * (qy * cz - qz * cy); val ty = 2f * (qz * cx - qx * cz); val tz = 2f * (qx * cy - qy * cx)
        val k = o.scale; val p = o.position
        val wx = (cx + qw * tx + (qy * tz - qz * ty)) * k + p.x; val wy = (cy + qw * ty + (qz * tx - qx * tz)) * k + p.y; val wz = (cz + qw * tz + (qx * ty - qy * tx)) * k + p.z
        r *= abs(k)
        place(o, wx, wy, wz, r)
    }

    /** The copies that cast into the lamps' cube faces the last test found: into their caches if [moving] is false. */
    private fun castIntoLamps(mesh: Mesh, o: Node, pose: FloatArray?, moving: Boolean) {
        var bits = lampCasting
        while (bits != 0) {
            val k = bits.countTrailingZeroBits()
            bits = bits and (bits - 1)
            if (moving) add(LAMP_MOVERS + k, lampMovers[k], mesh, o, pose) else add(LAMP_FACES + k, lampCasters[k], mesh, o, pose)
        }
    }

    /** Whether a part has moved in the last frames: its pose ([Part.version]) or its node's place changed (a skinned part always moves). */
    private fun partMoving(p: Part, o: Node): Boolean {
        if (p.mesh.dynamic || p.seenVersion != p.version || p.seenPosition !== o.position || p.seenRotation !== o.rotation || p.seenScale != o.scale) {
            p.seenVersion = p.version; p.seenPosition = o.position; p.seenRotation = o.rotation; p.seenScale = o.scale; p.stillFrames = 0
        } else if (p.stillFrames < STILL_FRAMES) p.stillFrames++
        return p.stillFrames < STILL_FRAMES
    }

    // The blended copies, farthest from the eye first: their batch, their place in it, their distance.
    private var blendedBatch = arrayOfNulls<Batch>(16)
    private var blendedIndex = IntArray(16)
    private var blendedDistance = FloatArray(16)
    /** Blended copies this frame (see [blendedAt]). */
    var blendedCount = 0; private set
    fun blendedBatchAt(i: Int): Batch = blendedBatch[i]!!
    fun blendedIndexAt(i: Int): Int = blendedIndex[i]
    fun blendedDistanceAt(i: Int): Float = blendedDistance[i]

    /** A blended copy: drawn alone, among the particles, in the order of the distances. */
    private fun addBlended(part: Mesh, o: Node, pose: FloatArray?) {
        val b = add(BLENDED, blended, part, o, pose) ?: return
        if (blendedCount == blendedBatch.size) {
            blendedBatch = blendedBatch.copyOf(2 * blendedCount); blendedIndex = blendedIndex.copyOf(2 * blendedCount)
            blendedDistance = blendedDistance.copyOf(2 * blendedCount)
        }
        // Its own centre in the world (the blended part's, not the whole mesh's: a window in a wall is where the window is).
        val sp = part.sphere
        var cx = sp[0]; var cy = sp[1]; var cz = sp[2]
        if (pose != null) {
            val px = pose[0] * cx + pose[4] * cy + pose[8] * cz + pose[12]; val py = pose[1] * cx + pose[5] * cy + pose[9] * cz + pose[13]
            cz = pose[2] * cx + pose[6] * cy + pose[10] * cz + pose[14]; cx = px; cy = py
        }
        val w = o.position + o.rotation.rotate(Vec3(cx, cy, cz) * o.scale)
        val dx = w.x - ex; val dy = w.y - ey; val dz = w.z - ez
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        // Insertion, farthest first (few blended copies).
        var i = blendedCount++
        while (i > 0 && blendedDistance[i - 1] < d) { blendedBatch[i] = blendedBatch[i - 1]; blendedIndex[i] = blendedIndex[i - 1]; blendedDistance[i] = blendedDistance[i - 1]; i-- }
        blendedBatch[i] = b; blendedIndex[i] = b.count - 1; blendedDistance[i] = d
    }

    /** One bit per node of the draw list: the nodes this frame visits (when the scene keeps still cells). */
    private var marks = LongArray(64)
    private var eyeSize = 0f

    /**
     * The frame's copies, grouped by mesh. With [cull], what the camera cannot see (outside the frustum of
     * [viewProj], past [fogEnd] or past its node's draw distance) is left out, and only what stands in a
     * cascade's square ([squares], seen along [light]) casts into that cascade's map, only what stands in a
     * face of a lamp's cube casts into it. A mesh with blended triangles is drawn in two: the others with the
     * opaque copies, the blended ones alone, farthest first ([blendedCount]); it casts shadows whole. When
     * [nodes] is a scene's draw list, the cells of its still nodes ([StillCells]) are tested first: a cell left out
     * leaves out all its nodes at once, and the nodes kept are visited in the list's order, so that the batches
     * are the same.
     */
    fun gather(nodes: List<Node>, hidden: Node?, viewProj: Mat4, eye: Vec3, fogEnd: Float, light: Vec3) {
        for (list in casters) for (b in list) b.count = 0
        for (list in lampCasters) for (b in list) b.count = 0
        for (list in lampMovers) for (b in list) b.count = 0
        for (b in opaque) b.count = 0
        for (b in quiet) b.count = 0
        for (b in skies) b.count = 0
        for (b in shadows) b.count = 0
        for (b in waters) b.count = 0
        for (b in blended) b.count = 0
        for (list in casters) list.clear()
        for (list in lampCasters) list.clear()
        for (list in lampMovers) list.clear()
        opaque.clear(); quiet.clear(); skies.clear(); shadows.clear(); waters.clear(); blended.clear()
        for (i in 0 until blendedCount) blendedBatch[i] = null
        blendedCount = 0
        culled = 0; drawn = 0
        culling = cull
        if (culling) extractPlanes(viewProj)
        cubesOn = 0
        for (c in 0 until lampLimit) if (cubes[c].id >= 0) cubesOn = cubesOn or (1 shl c)
        ex = eye.x; ey = eye.y; ez = eye.z; haze = if (fogEnd > 0f) fogEnd else Float.POSITIVE_INFINITY
        // The shadow square's axes, as light.glsl lays them.
        val ref = if (abs(light.y) > .99f) Vec3.Z else Vec3.Y
        val sRight = (ref cross light).normalized(); val sUp = light cross sRight
        rx = sRight.x; ry = sRight.y; rz = sRight.z; ux = sUp.x; uy = sUp.y; uz = sUp.z
        val still = if (culling && nodes.isNotEmpty()) nodes[0].scene.stillCellsOf(nodes) else null
        // When the cells left out whole hold few nodes, visiting every node costs less than choosing them.
        if (still == null || 8 * cellsLeftOut(still) < nodes.size) visitAll(nodes, hidden, eye)
        else visitKept(nodes, still, hidden, eye)
        if (lampLimit > 0) scheduleFaces()
    }

    // Each loop in a method of its own, compiled on its own (see ARCHITECTURE: a loop inside a large method ran slower).

    private fun visitAll(nodes: List<Node>, hidden: Node?, eye: Vec3) {
        for (k in nodes.indices) visit(nodes[k], hidden, eye)
    }

    /** Marks the cells left out whole this frame ([StillCell.out]); how many nodes they hold. */
    private fun cellsLeftOut(still: StillCells): Int {
        val cells = still.cells
        eyeSize = max(max(abs(ex), abs(ey)), abs(ez))
        var left = 0
        for (k in 0 until cells.size) {
            val c = cells[k]
            c.out = c.count > 0 && leftOut(c)
            if (c.out) left += c.count
        }
        return left
    }

    /** Visits, in the list's order, every node outside the cells and those of the cells not left out whole. */
    private fun visitKept(nodes: List<Node>, still: StillCells, hidden: Node?, eye: Vec3) {
        val words = (nodes.size + 63) ushr 6
        if (marks.size < words) marks = LongArray(2 * words)
        val m = marks
        m.fill(0L, 0, words)
        val moving = still.moving
        for (k in 0 until moving.size) { val r = moving[k].rank; m[r ushr 6] = m[r ushr 6] or (1L shl r) }
        val cells = still.cells
        for (k in 0 until cells.size) {
            val c = cells[k]
            if (c.count == 0) continue
            if (c.out) {
                // As if each of its nodes had been visited and found unseen (the hidden node is never counted).
                culled += c.count
                if (hidden != null && hidden.cell === c) culled--
                continue
            }
            val rs = c.ranks
            for (j in 0 until c.count) { val r = rs[j]; m[r ushr 6] = m[r ushr 6] or (1L shl r) }
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
     * sphere) is past the haze and the nodes' draw distances, or outside the frustum, every shadow square due and
     * every lamp's reach. The tests keep a margin, so that a cell is left out only when each of its nodes surely would be.
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
        // Within a lamp's reach, a node out of sight may still cast into its shadow cube.
        for (k in 0 until lampLimit) {
            if (cubesOn and (1 shl k) == 0) continue
            val cube = cubes[k]
            val lx = cube.x - cube.x.coerceIn(c.minX, c.maxX); val ly = cube.y - cube.y.coerceIn(c.minY, c.maxY); val lz = cube.z - cube.z.coerceIn(c.minZ, c.maxZ)
            if (lx * lx + ly * ly + lz * lz <= (cube.r + eps) * (cube.r + eps)) return false
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
            val casts = o.castsShadow && !mesh.unlit
            if (!casts) lampCasting = 0
            if (seen || casting != 0 || lampCasting != 0) prepare(mesh)
            if (o.isShadow) { if (seen) add(3, shadows, mesh, o, null) else culled++; return }
            val look = o.water
            if (look != null) { if (seen) add(4, waters, mesh, o, null)?.water = look else culled++; return }
            if (seen) {
                drawn++
                val split = mesh.blendSplit
                if (split == null) add(if (casts) 0 else 1, if (casts) opaque else quiet, mesh, o, null)
                else {
                    prepare(split.first); prepare(split.second)
                    add(if (casts) 0 else 1, if (casts) opaque else quiet, split.first, o, null)
                    addBlended(split.second, o, null)
                }
            } else culled++
            if (casts && casting != 0) for (c in 0 until CASCADES) if (casting and (1 shl c) != 0) add(5 + c, casters[c], mesh, o, null)
            if (casts && lampCasting != 0) castIntoLamps(mesh, o, null, !culling || mesh.dynamic || o.stillFrames < STILL_FRAMES)
        }
        val parts = o.parts
        val casts = o.castsShadow
        for (j in parts.indices) {
            val p = parts[j]
            test(p.mesh, o, p.m)
            if (!casts) lampCasting = 0
            if (!seen && casting == 0 && lampCasting == 0) { culled++; continue }
            prepare(p.mesh)
            if (seen) {
                drawn++
                val split = p.mesh.blendSplit
                if (split == null) add(if (casts) 0 else 1, if (casts) opaque else quiet, p.mesh, o, p.m)
                else {
                    prepare(split.first); prepare(split.second)
                    add(if (casts) 0 else 1, if (casts) opaque else quiet, split.first, o, p.m)
                    addBlended(split.second, o, p.m)
                }
            } else culled++
            if (casts && casting != 0) for (c in 0 until CASCADES) if (casting and (1 shl c) != 0) add(5 + c, casters[c], p.mesh, o, p.m)
            if (casts && lampCasting != 0) castIntoLamps(p.mesh, o, p.m, partMoving(p, o) || !culling)
        }
    }

    /**
     * The frame's uniform block (frame.glsl) into [u] ([FRAME_FLOATS] floats): the light of the hour, the
     * sky, the haze, the wind, the camera's axes (from view × projection: its first row is the camera's
     * right, its last its forward direction), the shadow squares, the light grid and the lamps shown ([lights]).
     */
    fun writeFrame(u: FloatArray, l: Lighting, main: Vec3, vp: Mat4, extras: FrameExtras?, shadowSize: Int) {
        var o = 0
        val lights = shownCount
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
        // The light grid (x 0: off), its slices, the side of a lamp's shadow face.
        u[o++] = if (gridOn) GRID_X.toFloat() else 0f; u[o++] = GRID_Y.toFloat(); u[o++] = 1f / tileWidth; u[o++] = 1f / tileHeight
        u[o++] = sliceNear; u[o++] = slicesPerDoubling; u[o++] = GRID_Z.toFloat(); u[o++] = lampTexels.toFloat()
        // The lights' places, then their colours; those past the count keep what they had (the shaders never read them).
        val a = extras?.lights
        if (a != null) for (j in 0 until min(lights, MAX_LIGHTS)) {
            val i = shown[j]
            u[o + 4 * j] = a[8 * i]; u[o + 4 * j + 1] = a[8 * i + 1]; u[o + 4 * j + 2] = a[8 * i + 2]; u[o + 4 * j + 3] = a[8 * i + 3]
            val q = o + 4 * MAX_LIGHTS + 4 * j
            u[q] = a[8 * i + 4]; u[q + 1] = a[8 * i + 5]; u[q + 2] = a[8 * i + 6]; u[q + 3] = shownCube[j].toFloat()
        }
    }

    private fun colour(u: FloatArray, at: Int, c: Color, w: Float): Int {
        u[at] = c.r; u[at + 1] = c.g; u[at + 2] = c.b; u[at + 3] = w
        return at + 4
    }

    private companion object {
        val LN2 = ln(2f)
        /** Per face of a lamp's cube: its forward, right and up axes (shading.glsl's lampLight reads them the same way). */
        val FACES = floatArrayOf(
            1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f,
            -1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f,
            0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
            0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f,
            0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f)
    }
}
