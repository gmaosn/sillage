package sillage

import kotlin.concurrent.Volatile

import sillage.math.Mat4
import sillage.math.Vec3
import kotlin.math.*

/**
 * A colour in linear light, as the shader uses it. Write colours the way they look on screen with
 * [hex] (`Color.hex(0xFF8800)`); the named colours form a soft palette that suits the toon look.
 */
data class Color(val r: Float, val g: Float, val b: Float) {
    /** Linear blend towards [other]: 0 gives this colour, 1 gives [other]. */
    fun mix(other: Color, t: Float) = Color(r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t)
    /** Brighter (factor above 1) or darker (below 1). */
    fun times(factor: Float) = Color(r * factor, g * factor, b * factor)

    companion object {
        /** A colour as seen on screen (sRGB, like in a paint program), e.g. `Color.hex(0x3A7BD5)`. */
        fun hex(rgb: Int): Color {
            fun c(v: Int) = (v / 255f).pow(2.2f)
            return Color(c(rgb shr 16 and 255), c(rgb shr 8 and 255), c(rgb and 255))
        }
        val WHITE = hex(0xF7F3EA); val BLACK = hex(0x1C1C24); val GREY = hex(0x9A9A9E)
        val RED = hex(0xE0533F); val ORANGE = hex(0xF29A3A); val YELLOW = hex(0xF4D35E)
        val GREEN = hex(0x6DBE5A); val BLUE = hex(0x3F84D6); val PURPLE = hex(0x8A63C9); val PINK = hex(0xEE8FB0)
        val BROWN = hex(0x8C5A3C); val GRASS = hex(0x7CC35A); val SAND = hex(0xEDCB8A); val WOOD = hex(0xB7834E)
        val STONE = hex(0x8E8A84); val WATER = hex(0x2AA0C0); val SKY = hex(0x8CC8EC)
    }
}

/**
 * Triangles to draw, not indexed: per vertex its position, normal and colour (9 floats), three vertices
 * per triangle. Build one with [MeshBuilder] or the shapes in the companion ([box], [sphere]…), or load
 * a model. A vertex with a zero normal is unlit (drawn in its plain colour, like the sky or the HUD).
 */
/** Floats per vertex of [Mesh.effects]. */
internal const val EFFECT_FLOATS = 12

class Mesh(val vertices: FloatArray) {
    init { require(vertices.size % 27 == 0) { "A mesh is made of whole triangles (27 floats each)" } }

    val triangleCount: Int get() = vertices.size / 27
    /**
     * Per vertex, [EFFECT_FLOATS] floats: its own light (r, g, b; all negative: only at night, see
     * [Lighting.night]), how far it sways in the wind (m), its texture (its [Texture.id], 0: none), the
     * texture's tile (m), strength and mapping (0 sides, 1 faces, 2 texture coordinates), the texture
     * coordinates u, v, the alpha below which it is cut out (0: opaque), one spare. Null: none (most meshes).
     */
    internal var effects: FloatArray? = null
        private set
    /** Per triangle, its material's index in the model it comes from (−1: none); null for meshes made by hand. */
    internal var materials: IntArray? = null
        private set
    /**
     * Its vertices change in place, every frame (a skinned part): the renderer sends them again when
     * [version] has moved since. A copy that shares the array ([withEffects]…) stays dynamic.
     */
    internal var dynamic = false
        private set
    @Volatile internal var version = 0
    /** The renderer's batches of this mesh and whose they are (see VulkanRenderer.add); what it last uploaded it for. */
    internal var batches: Array<Any?>? = null
    internal var batchOwner: Any? = null
    internal var preparedBy: Any? = null
    /** The same triangles with [effects] ([EFFECT_FLOATS] per vertex), keeping the materials. */
    internal fun withEffects(effects: FloatArray): Mesh {
        require(effects.size == vertices.size / 9 * EFFECT_FLOATS) { "$EFFECT_FLOATS effect floats per vertex" }
        return Mesh(vertices).also { it.effects = effects; it.materials = materials; it.dynamic = dynamic }
    }
    /** The same mesh, knowing the material of each triangle. */
    internal fun withMaterials(materials: IntArray): Mesh {
        require(materials.size == triangleCount) { "One material per triangle" }
        return Mesh(vertices).also { it.materials = materials; it.effects = effects; it.dynamic = dynamic }
    }
    /** The same triangles with these normals (3 floats per vertex), keeping materials and effects. */
    internal fun withNormals(normals: FloatArray): Mesh {
        require(normals.size == vertices.size / 3) { "3 normal floats per vertex" }
        val v = vertices.copyOf()
        for (k in 0 until v.size / 9) { v[9 * k + 3] = normals[3 * k]; v[9 * k + 4] = normals[3 * k + 1]; v[9 * k + 5] = normals[3 * k + 2] }
        return Mesh(v).also { it.materials = materials; it.effects = effects; it.dynamic = dynamic }
    }

    /** A copy of its own (vertices copied, materials and effects shared) whose vertices change every frame. */
    internal fun dynamicCopy(): Mesh = Mesh(vertices.copyOf()).also { it.materials = materials; it.effects = effects; it.dynamic = true }
    /** Part of it is cut out by its image's alpha: its shadow needs the image too (other shadows are depth alone, with no fragment shader). */
    internal val cutOut: Boolean by lazy {
        val e = effects; var cut = false
        if (e != null) { var o = 0; while (o < e.size && !cut) { cut = e[o + 4] > .5f && e[o + 7] > 1.5f && e[o + 10] > 0f; o += EFFECT_FLOATS } }
        cut
    }
    /** Drawn in its plain colours (zero normals: the sky, the HUD); such a mesh casts no shadow. */
    internal val unlit: Boolean by lazy { vertices.size >= 9 && vertices[3] == 0f && vertices[4] == 0f && vertices[5] == 0f }

    /** A sphere holding the mesh (the centre of its bounds, half their diagonal): x, y, z, radius. */
    internal val sphere: FloatArray by lazy {
        val (lo, hi) = bounds
        val dx = (hi.x - lo.x) / 2; val dy = (hi.y - lo.y) / 2; val dz = (hi.z - lo.z) / 2
        floatArrayOf((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2, sqrt(dx * dx + dy * dy + dz * dz))
    }

    /** Smallest box holding the mesh: min and max corners. */
    val bounds: Pair<Vec3, Vec3> by lazy {
        if (vertices.isEmpty()) Vec3.ZERO to Vec3.ZERO
        else {
            var lx = Float.MAX_VALUE; var ly = Float.MAX_VALUE; var lz = Float.MAX_VALUE
            var hx = -Float.MAX_VALUE; var hy = -Float.MAX_VALUE; var hz = -Float.MAX_VALUE
            for (i in vertices.indices step 9) {
                lx = min(lx, vertices[i]); ly = min(ly, vertices[i + 1]); lz = min(lz, vertices[i + 2])
                hx = max(hx, vertices[i]); hy = max(hy, vertices[i + 1]); hz = max(hz, vertices[i + 2])
            }
            Vec3(lx, ly, lz) to Vec3(hx, hy, hz)
        }
    }

    /**
     * Smooth normals: each vertex takes the area-weighted mean of the normals of the faces sharing it,
     * only among faces less than [angle] degrees apart. Round shapes turn soft, sharp edges (boxes,
     * roofs) stay crisp, the two sides of a sail do not mix.
     */
    fun smooth(angle: Float = 55f): Mesh {
        val n = vertices.size / 27
        if (n == 0) return this
        val limit = cos(sillage.math.toRadians(angle.toDouble())).toFloat()
        val fn = FloatArray(3 * n); val area = FloatArray(n)
        for (t in 0 until n) {
            val o = 27 * t
            val ux = vertices[o + 9] - vertices[o]; val uy = vertices[o + 10] - vertices[o + 1]; val uz = vertices[o + 11] - vertices[o + 2]
            val vx = vertices[o + 18] - vertices[o]; val vy = vertices[o + 19] - vertices[o + 1]; val vz = vertices[o + 20] - vertices[o + 2]
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            val l = sqrt(cx * cx + cy * cy + cz * cz)
            area[t] = l
            if (l > 0f) { fn[3 * t] = cx / l; fn[3 * t + 1] = cy / l; fn[3 * t + 2] = cz / l }
        }
        // Faces around each position (positions compared exactly, as written by the builders).
        fun key(o: Int): Long = (vertices[o].toRawBits().toLong() * 73856093L) xor (vertices[o + 1].toRawBits().toLong() * 19349663L) xor
            (vertices[o + 2].toRawBits().toLong() * 83492791L)
        val faces = HashMap<Long, MutableList<Int>>()
        for (t in 0 until n) for (k in 0..2) faces.getOrPut(key(27 * t + 9 * k)) { ArrayList(6) }.add(t)
        val out = vertices.copyOf()
        for (t in 0 until n) {
            if (vertices[27 * t + 3] == 0f && vertices[27 * t + 4] == 0f && vertices[27 * t + 5] == 0f) continue  // unlit
            for (k in 0..2) {
                val o = 27 * t + 9 * k
                var sx = 0f; var sy = 0f; var sz = 0f
                for (u in faces.getValue(key(o))) {
                    // Same position only (the key may collide): compare the vertex of u that matches.
                    var same = false
                    for (j in 0..2) { val q = 27 * u + 9 * j; if (vertices[q] == vertices[o] && vertices[q + 1] == vertices[o + 1] && vertices[q + 2] == vertices[o + 2]) same = true }
                    if (!same) continue
                    val d = fn[3 * t] * fn[3 * u] + fn[3 * t + 1] * fn[3 * u + 1] + fn[3 * t + 2] * fn[3 * u + 2]
                    if (d < limit) continue
                    sx += fn[3 * u] * area[u]; sy += fn[3 * u + 1] * area[u]; sz += fn[3 * u + 2] * area[u]
                }
                val l = sqrt(sx * sx + sy * sy + sz * sz)
                if (l > 0f) { out[o + 3] = sx / l; out[o + 4] = sy / l; out[o + 5] = sz / l }
            }
        }
        return Mesh(out)
    }

    /** The same triangles in another colour. */
    fun colored(color: Color): Mesh {
        val out = vertices.copyOf()
        for (i in out.indices step 9) { out[i + 6] = color.r; out[i + 7] = color.g; out[i + 8] = color.b }
        return Mesh(out)
    }

    /** The same mesh moved by [matrix] (normals turned with it). */
    fun transformed(matrix: Mat4): Mesh {
        val out = vertices.copyOf()
        for (i in out.indices step 9) {
            val p = matrix.transformPoint(Vec3(out[i], out[i + 1], out[i + 2]))
            out[i] = p.x; out[i + 1] = p.y; out[i + 2] = p.z
            if (out[i + 3] != 0f || out[i + 4] != 0f || out[i + 5] != 0f) {
                val n = matrix.transformDirection(Vec3(out[i + 3], out[i + 4], out[i + 5])).normalized()
                out[i + 3] = n.x; out[i + 4] = n.y; out[i + 5] = n.z
            }
        }
        return Mesh(out)
    }

    /** Both meshes in one. */
    operator fun plus(other: Mesh) = Mesh(vertices + other.vertices)

    companion object {
        /** An empty mesh (a node that only holds animated parts, or nothing to draw). */
        val EMPTY = Mesh(FloatArray(0))

        /**
         * A box centred on its origin. With [round] > 0 its edges and corners are rounded by that many
         * metres (a friendlier toy look); 0 keeps them sharp.
         */
        fun box(width: Float = 1f, height: Float = width, depth: Float = width, color: Color = Color.WOOD, round: Float = 0f): Mesh {
            val hx = width / 2; val hy = height / 2; val hz = depth / 2
            val r = round.coerceIn(0f, min(hx, min(hy, hz)))
            if (r <= 0f) return MeshBuilder().box(0f, -hy, 0f, width, height, depth, color).build()
            // A subdivided cube whose points are pushed onto the rounded box (the inner box grown by r).
            val g = MeshBuilder(); val steps = 6
            val ix = hx - r; val iy = hy - r; val iz = hz - r
            fun onto(p: Vec3): Vec3 {
                val c = Vec3(p.x.coerceIn(-ix, ix), p.y.coerceIn(-iy, iy), p.z.coerceIn(-iz, iz))
                val d = p - c
                return if (d.lengthSquared() < 1e-12f) p else c + d.normalized() * r
            }
            for (axis in 0..2) for (sign in floatArrayOf(-1f, 1f)) {
                val u = (axis + 1) % 3; val v = (axis + 2) % 3
                val h = floatArrayOf(hx, hy, hz)
                fun point(i: Int, j: Int): Vec3 {
                    val c = FloatArray(3); c[axis] = sign * h[axis]
                    c[u] = -h[u] + 2 * h[u] * i / steps; c[v] = -h[v] + 2 * h[v] * j / steps
                    return onto(Vec3(c[0], c[1], c[2]))
                }
                for (i in 0 until steps) for (j in 0 until steps) {
                    val a = point(i, j); val b = point(i + 1, j); val c = point(i + 1, j + 1); val d = point(i, j + 1)
                    if (sign > 0f) g.quad(a, b, c, d, color) else g.quad(a, d, c, b, color)
                }
            }
            return g.build().smooth(80f)
        }

        /** A sphere centred on its origin. */
        fun sphere(radius: Float = .5f, color: Color = Color.RED, segments: Int = 24, rings: Int = 14): Mesh {
            val g = MeshBuilder()
            fun p(i: Int, j: Int): Vec3 {
                val t = PI.toFloat() * i / rings; val a = 2 * PI.toFloat() * j / segments
                return Vec3(sin(t) * cos(a) * radius, cos(t) * radius, sin(t) * sin(a) * radius)
            }
            for (i in 0 until rings) for (j in 0 until segments) {
                val a = p(i, j); val b = p(i + 1, j); val c = p(i + 1, j + 1); val d = p(i, j + 1)
                g.triangle(a, d, c, color); g.triangle(a, c, b, color)
            }
            return g.build().smooth(80f)
        }

        /** A capsule standing on Y, centred on its origin: [height] from tip to tip (at least 2 × [radius]). */
        fun capsule(radius: Float = .3f, height: Float = 1.6f, color: Color = Color.ORANGE, segments: Int = 20): Mesh {
            val half = max(0f, height / 2 - radius)
            val g = MeshBuilder(); val rings = 8
            fun p(i: Int, j: Int): Vec3 {   // i in 0..2·rings+1: top cap, then bottom cap
                val top = i <= rings
                val t = PI.toFloat() / 2 * (if (top) i.toFloat() / rings else (i - 1).toFloat() / rings)
                val a = 2 * PI.toFloat() * j / segments
                return Vec3(sin(t) * cos(a) * radius, cos(t) * radius + (if (top) half else -half), sin(t) * sin(a) * radius)
            }
            for (i in 0..2 * rings) for (j in 0 until segments) {
                val a = p(i, j); val b = p(i + 1, j); val c = p(i + 1, j + 1); val d = p(i, j + 1)
                g.triangle(a, d, c, color); g.triangle(a, c, b, color)
            }
            return g.build().smooth(80f)
        }

        /** A cylinder standing on Y, centred on its origin; [topRadius] 0 makes a cone. */
        fun cylinder(radius: Float = .5f, height: Float = 1f, color: Color = Color.GREY, topRadius: Float = radius, segments: Int = 24): Mesh {
            val g = MeshBuilder(); val h = height / 2
            for (j in 0 until segments) {
                val a = 2 * PI.toFloat() * j / segments; val b = 2 * PI.toFloat() * (j + 1) / segments
                val p0 = Vec3(cos(a) * radius, -h, sin(a) * radius); val p1 = Vec3(cos(b) * radius, -h, sin(b) * radius)
                val q0 = Vec3(cos(a) * topRadius, h, sin(a) * topRadius); val q1 = Vec3(cos(b) * topRadius, h, sin(b) * topRadius)
                g.triangle(p0, q0, q1, color); g.triangle(p0, q1, p1, color)
                g.triangle(Vec3(0f, -h, 0f), p0, p1, color)
                if (topRadius > 0f) g.triangle(Vec3(0f, h, 0f), q1, q0, color)
            }
            return g.build().smooth(60f)
        }

        /** A cone standing on Y, centred on its origin. */
        fun cone(radius: Float = .5f, height: Float = 1f, color: Color = Color.YELLOW, segments: Int = 24) =
            cylinder(radius, height, color, 0f, segments)

        /** A flat rectangle facing up, centred on its origin. */
        fun plane(width: Float = 10f, depth: Float = width, color: Color = Color.GRASS): Mesh {
            val hx = width / 2; val hz = depth / 2
            return MeshBuilder().quad(Vec3(-hx, 0f, -hz), Vec3(-hx, 0f, hz), Vec3(hx, 0f, hz), Vec3(hx, 0f, -hz), color).build()
        }

        /** A flat disc facing up, centred on its origin. */
        fun disk(radius: Float = 1f, color: Color = Color.GREY, segments: Int = 48) = MeshBuilder().disk(0f, 0f, 0f, radius, color, segments).build()
    }
}

/** Builds a [Mesh] triangle by triangle, with flat normals (call [Mesh.smooth] for round shapes). */
class MeshBuilder {
    // A float array that grows (an ArrayList<Float> would box every number).
    private var data = FloatArray(1024)
    private var size = 0
    /** Triangles added so far. */
    val triangleCount: Int get() = size / 27
    private fun put(p: Vec3, n: Vec3, c: Color) {
        if (size + 9 > data.size) data = data.copyOf(data.size * 2)
        data[size] = p.x; data[size + 1] = p.y; data[size + 2] = p.z
        data[size + 3] = n.x; data[size + 4] = n.y; data[size + 5] = n.z
        data[size + 6] = c.r; data[size + 7] = c.g; data[size + 8] = c.b
        size += 9
    }
    /** One triangle, counter-clockwise when seen from its front. Degenerate triangles are skipped. */
    fun triangle(a: Vec3, b: Vec3, c: Vec3, color: Color): MeshBuilder {
        val cross = (b - a) cross (c - a)
        if (cross.lengthSquared() < 1e-12f) return this
        val n = cross.normalized()
        put(a, n, color); put(b, n, color); put(c, n, color)
        return this
    }
    /** One triangle with a colour at each corner (the colours blend across it). */
    fun triangle(a: Vec3, b: Vec3, c: Vec3, colorA: Color, colorB: Color, colorC: Color): MeshBuilder {
        val cross = (b - a) cross (c - a)
        if (cross.lengthSquared() < 1e-12f) return this
        val n = cross.normalized()
        put(a, n, colorA); put(b, n, colorB); put(c, n, colorC)
        return this
    }
    fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3, color: Color): MeshBuilder { triangle(a, b, c, color); triangle(a, c, d, color); return this }
    /** A box with its base centred on (x, y, z). */
    fun box(x: Float, y: Float, z: Float, width: Float, height: Float, depth: Float, color: Color): MeshBuilder {
        val p = Array(8) { i -> Vec3(x + (if ((i and 1) == 0) -1f else 1f) * width / 2,
            y + (if ((i and 2) == 0) 0f else height), z + (if ((i and 4) == 0) -1f else 1f) * depth / 2) }
        for (f in arrayOf(intArrayOf(0, 4, 6, 2), intArrayOf(1, 3, 7, 5), intArrayOf(0, 1, 5, 4),
            intArrayOf(2, 6, 7, 3), intArrayOf(0, 2, 3, 1), intArrayOf(4, 5, 7, 6))) quad(p[f[0]], p[f[1]], p[f[2]], p[f[3]], color)
        return this
    }
    /** A flat disc facing up. */
    fun disk(x: Float, y: Float, z: Float, radius: Float, color: Color, segments: Int = 48): MeshBuilder {
        for (i in 0 until segments) {
            val a = i * 2 * PI.toFloat() / segments; val b = (i + 1) * 2 * PI.toFloat() / segments
            triangle(Vec3(x, y, z), Vec3(x + cos(b) * radius, y, z + sin(b) * radius), Vec3(x + cos(a) * radius, y, z + sin(a) * radius), color)
        }
        return this
    }
    /** Adds a whole mesh. */
    fun add(mesh: Mesh): MeshBuilder {
        if (size + mesh.vertices.size > data.size) data = data.copyOf(max(data.size * 2, size + mesh.vertices.size))
        mesh.vertices.copyInto(data, size, 0, 0 + mesh.vertices.size); size += mesh.vertices.size
        return this
    }
    fun build() = Mesh(data.copyOf(size))
}

/** A point, written with any numbers: `vec(1, 2.5, 0)`. */
fun vec(x: Number, y: Number, z: Number) = Vec3(x.toFloat(), y.toFloat(), z.toFloat())
