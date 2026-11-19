package sillage

import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3
import sillage.math.slerpInto

/** A node of a GLB model: rest transform, children, and its own triangles in node space. */
class ModelNode internal constructor(
    val name: String?,
    val parent: Int,
    val children: IntArray,
    val translation: Vec3,
    val rotation: Quat,
    val scale: Vec3,
    /** Set when the GLB gives a `matrix` instead of TRS; such a node cannot be animated. */
    val matrix: Mat4?,
    /** Per triangle ([TRI] floats): a, b, c (9 floats), the colour r, g, b, then the material index (−1: none). */
    internal val triangles: FloatArray,
    /** Per triangle, the texture coordinates of a, b, c (6 floats); empty when the mesh has none. */
    internal val uvs: FloatArray = FloatArray(0),
    /** The skin that deforms its mesh ([Model.skins]), −1: none. */
    val skin: Int = -1,
    /** Per triangle, per vertex 4 joints (indices in the skin) then 4 weights (24 floats); empty without a skin. */
    internal val skinWeights: FloatArray = FloatArray(0),
) {
    val local: Mat4 get() = matrix ?: Mat4.trs(translation, rotation, scale)
}

/**
 * A skeleton: the nodes that act as its [joints] and, for each, the inverse of its place in the model
 * when the mesh was bound to it. A skinned vertex goes where its joints take it, by their weights.
 */
class Skin internal constructor(val joints: IntArray, val inverseBind: List<Mat4>)

/** What a channel animates. */
enum class ChannelTarget(val components: Int) { TRANSLATION(3), ROTATION(4), SCALE(3) }

/** One animated property of one node: key times and values, linear (slerp for rotations) or step. */
class Channel internal constructor(val node: Int, val target: ChannelTarget, val times: FloatArray, val values: FloatArray, val step: Boolean) {
    init {
        require(times.isNotEmpty()) { "Animation channel without keys" }
        require(values.size == times.size * target.components) { "Animation channel: ${values.size} values for ${times.size} keys" }
        for (i in 1 until times.size) require(times[i] > times[i - 1]) { "Animation key times must increase" }
    }
}

/** A glTF animation: several channels sharing one timeline. */
class Clip internal constructor(val name: String, val channels: List<Channel>) {
    val duration: Float = channels.maxOfOrNull { it.times.last() } ?: 0f
}

/** Floats per model triangle: 9 positions, r, g, b, material index. */
internal const val TRI = 13

/** A GLB scene kept as a hierarchy, with its animations. */
class Model internal constructor(val nodes: List<ModelNode>, val roots: IntArray, val clips: Map<String, Clip>,
                                 /** Material names of the GLB, by index (as named in Blender). */
                                 val materials: List<String> = emptyList(),
                                 /** The base colour texture of each material (null: plain colour). */
                                 val textures: List<Texture?> = emptyList(),
                                 /** Per material, the alpha below which it is cut out (0: opaque). */
                                 internal val cutoffs: FloatArray = FloatArray(0),
                                 /** The skeletons of the model's skinned meshes. */
                                 val skins: List<Skin> = emptyList()) {
    /** Each node's place in the model at rest. */
    private val restWorld: Array<Mat4> by lazy {
        val out = Array(nodes.size) { Mat4.IDENTITY }
        for (i in order) out[i] = if (nodes[i].parent < 0) nodes[i].local else out[nodes[i].parent] * nodes[i].local
        out
    }

    /** Joint matrices of a skin at rest: joint's place × inverse bind matrix. */
    private val restJoints: List<Array<Mat4>> by lazy { skins.map { s -> Array(s.joints.size) { restWorld[s.joints[it]] * s.inverseBind[it] } } }

    /** Vertex [v] of triangle [t] of skinned node [i] at rest, in the model's space (the node's own place is ignored, as glTF says). */
    private fun skinnedRest(i: Int, t: Int, v: Int): Vec3 {
        val n = nodes[i]; val m = restJoints[n.skin]; val w = n.skinWeights; val o = 24 * t + 8 * v
        val p = Vec3(n.triangles[TRI * t + 3 * v], n.triangles[TRI * t + 3 * v + 1], n.triangles[TRI * t + 3 * v + 2])
        var x = 0f; var y = 0f; var z = 0f
        for (k in 0..3) {
            val weight = w[o + 4 + k]
            if (weight == 0f) continue
            val q = m[w[o + k].toInt()].transformPoint(p)
            x += q.x * weight; y += q.y * weight; z += q.z * weight
        }
        return Vec3(x, y, z)
    }
    /**
     * The texture coordinates and textures of the triangles kept in a mesh ([kept]: the source triangle of each),
     * as [Mesh.effects]; null when none of them is textured.
     */
    private fun imageEffects(kept: IntArray, mats: IntArray, uvs: (Int) -> FloatArray?, uvAt: (Int) -> Int): FloatArray? {
        if (textures.all { it == null }) return null
        var any = false
        val e = FloatArray(kept.size * 3 * EFFECT_FLOATS)
        for (t in kept.indices) {
            val m = mats[t]
            val tex = if (m in textures.indices) textures[m] else null
            if (tex == null) continue
            any = true
            val uv = uvs(t) ?: continue; val at = uvAt(t)
            for (k in 0..2) {
                val o = (3 * t + k) * EFFECT_FLOATS
                e[o + 4] = tex.id.toFloat(); e[o + 5] = 1f; e[o + 6] = 1f; e[o + 7] = 2f
                e[o + 8] = uv[at + 2 * k]; e[o + 9] = uv[at + 2 * k + 1]; e[o + 10] = cutoffs.getOrElse(m) { 0f }
            }
        }
        return if (any) e else null
    }
    /** Node indices with every parent before its children, in scene order. */
    val order: IntArray = ArrayList<Int>().also { out ->
        val seen = BooleanArray(nodes.size)
        fun visit(i: Int) {
            require(!seen[i]) { "Cycle or shared node in the GLB hierarchy" }
            seen[i] = true; out.add(i)
            for (e in nodes[i].children) visit(e)
        }
        for (r in roots) visit(r)
    }.toIntArray()

    /** A node's triangles as a mesh in its space (in its bind space when skinned), and per vertex its 4 joints and 4 weights. */
    private class LocalMesh(val mesh: Mesh, val weights: FloatArray?)

    /** Each node's triangles as a mesh in node space (knowing each triangle's material), shared by every instance of the model. */
    private val localMeshes: Map<Int, LocalMesh> by lazy {
        order.filter { nodes[it].triangles.isNotEmpty() }.associateWith { i ->
            val g = MeshBuilder(); val t = nodes[i].triangles; val mats = ArrayList<Int>(); val kept = ArrayList<Int>()
            for (k in t.indices step TRI) {
                val before = g.triangleCount
                g.triangle(Vec3(t[k], t[k + 1], t[k + 2]), Vec3(t[k + 3], t[k + 4], t[k + 5]), Vec3(t[k + 6], t[k + 7], t[k + 8]),
                    Color(t[k + 9], t[k + 10], t[k + 11]))
                if (g.triangleCount > before) { mats.add(t[k + 12].toInt()); kept.add(k / TRI) }
            }
            val mesh = g.build().smooth().withMaterials(mats.toIntArray())
            val uvs = nodes[i].uvs
            val textured = imageEffects(kept.toIntArray(), mats.toIntArray(), { if (uvs.isEmpty()) null else uvs }, { 6 * kept[it] })?.let { mesh.withEffects(it) } ?: mesh
            val sw = nodes[i].skinWeights
            LocalMesh(textured, if (nodes[i].skin < 0) null else FloatArray(kept.size * 24) { j -> sw[24 * kept[j / 24] + j % 24] })
        }
    }

    /** The whole scene in its rest pose as one world-space mesh (static models), knowing each triangle's material. */
    fun flatten(): Mesh = flat

    private val flat: Mesh by lazy {
        val g = MeshBuilder(); val mats = ArrayList<Int>()
        val keptNode = ArrayList<Int>(); val keptTri = ArrayList<Int>(); val keptMirrored = ArrayList<Boolean>()
        fun visit(i: Int, parent: Mat4) {
            val n = nodes[i]
            val world = parent * n.local
            val mirrored = world.determinant3x3() < 0
            val t = n.triangles
            val skinned = n.skin >= 0
            for (k in t.indices step TRI) {
                val a = if (skinned) skinnedRest(i, k / TRI, 0) else world.transformPoint(Vec3(t[k], t[k + 1], t[k + 2]))
                val b = if (skinned) skinnedRest(i, k / TRI, 1) else world.transformPoint(Vec3(t[k + 3], t[k + 4], t[k + 5]))
                val c = if (skinned) skinnedRest(i, k / TRI, 2) else world.transformPoint(Vec3(t[k + 6], t[k + 7], t[k + 8]))
                val color = Color(t[k + 9], t[k + 10], t[k + 11])
                // Face normals recomputed, also after a negative scale; degenerate triangles are skipped.
                val before = g.triangleCount
                if (mirrored) g.triangle(a, c, b, color) else g.triangle(a, b, c, color)
                if (g.triangleCount > before) { mats.add(t[k + 12].toInt()); keptNode.add(i); keptTri.add(k / TRI); keptMirrored.add(mirrored) }
            }
            for (e in n.children) visit(e, world)
        }
        for (r in roots) visit(r, Mat4.IDENTITY)
        val mesh = g.build().smooth().withMaterials(mats.toIntArray())
        // A mirrored triangle was written a, c, b: its texture coordinates too.
        val uv = FloatArray(6)
        imageEffects(IntArray(mats.size) { it }, mats.toIntArray(), { t ->
            val src = nodes[keptNode[t]].uvs; val o = 6 * keptTri[t]
            if (src.isEmpty()) null else {
                if (keptMirrored[t]) { uv[0] = src[o]; uv[1] = src[o + 1]; uv[2] = src[o + 4]; uv[3] = src[o + 5]; uv[4] = src[o + 2]; uv[5] = src[o + 3] }
                else for (j in 0..5) uv[j] = src[o + j]
                uv
            }
        }, { 0 })?.let { mesh.withEffects(it) } ?: mesh
    }

    /**
     * The scene in its rest pose as world-space triangles for collision (9 floats each, same winding
     * as [flatten]) and, for each triangle, its material index in [materials] (−1: none).
     */
    fun surfaces(): Pair<FloatArray, IntArray> {
        val pos = ArrayList<Float>(); val mat = ArrayList<Int>()
        fun visit(i: Int, parent: Mat4) {
            val n = nodes[i]
            val world = parent * n.local
            val mirrored = world.determinant3x3() < 0
            val t = n.triangles
            for (k in t.indices step TRI) {
                val pts = (0..2).map { v -> if (n.skin >= 0) skinnedRest(i, k / TRI, v) else world.transformPoint(Vec3(t[k + 3 * v], t[k + 3 * v + 1], t[k + 3 * v + 2])) }
                for (p in if (mirrored) listOf(pts[0], pts[2], pts[1]) else pts) { pos.add(p.x); pos.add(p.y); pos.add(p.z) }
                mat.add(t[k + 12].toInt())
            }
            for (e in n.children) visit(e, world)
        }
        for (r in roots) visit(r, Mat4.IDENTITY)
        return pos.toFloatArray() to mat.toIntArray()
    }

    /** New parts for one instance: one per node that has triangles; a skinned one gets its own mesh, deformed every frame. */
    fun parts(): List<Part> = order.filter { it in localMeshes }.map { i ->
        val local = localMeshes.getValue(i)
        val w = local.weights
        if (w == null) Part(i, local.mesh)
        else Part(i, local.mesh.dynamicCopy()).also { it.skin = SkinBinding(nodes[i].skin, local.mesh.vertices, w) }
    }
}

/** What deforms a skinned part: its skin, its vertices as bound (9 floats each), per vertex 4 joints and 4 weights. */
internal class SkinBinding(val skin: Int, val bind: FloatArray, val weights: FloatArray) {
    var joints = FloatArray(0)                 // per joint, the 12 floats of its matrix this frame (scratch)
}

/** A drawable piece of an animated node; [pose] places it in the node's space. */
class Part internal constructor(val node: Int, mesh: Mesh) {
    /** Its triangles (in its node's space; in the model's space for a skinned part, deformed every frame). */
    var mesh: Mesh = mesh
        internal set
    internal var skin: SkinBinding? = null
    var pose: Mat4 = Mat4.IDENTITY
        internal set
}

/**
 * Plays the clips of a [Model] on one instance's parts. Switching clip cross-fades from the pose
 * reached at that moment. The pose lives in flat arrays (translation, rotation, scale per node).
 */
class Animator(val model: Model, private val parts: List<Part>) {
    private val n = model.nodes.size
    private val pt = FloatArray(3 * n); private val pr = FloatArray(4 * n); private val ps = FloatArray(3 * n)
    private val ft = FloatArray(3 * n); private val fr = FloatArray(4 * n); private val fs = FloatArray(3 * n)
    private val world = arrayOfNulls<Mat4>(n)
    private var clip: Clip? = null
    private var loop = true
    private var fade = 0f
    private var fadeTotal = 0f
    /** Time in the current clip, in seconds of clip time. */
    var time = 0f
        private set
    /** Playback rate: 1 is the authored speed. */
    var speed = 1f
    /** Name of the clip playing, if any. */
    val current: String? get() = clip?.name

    init { rest(); pose() }

    /** Current pose of the node named [name] in the model's space, or null when the model has no such node. */
    fun nodePose(name: String): Mat4? = model.nodes.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { world[it] }

    /** Starts [name] unless it is already playing; [fade] seconds of cross-fade from the current pose. */
    fun play(name: String, loop: Boolean = true, fade: Float = .15f, speed: Float = this.speed) {
        val c = model.clips[name] ?: throw IllegalArgumentException(
            "No animation « $name » in the model (it has: ${model.clips.keys.joinToString()})")
        this.speed = speed
        this.loop = loop
        if (c === clip) return
        pt.copyInto(ft); pr.copyInto(fr); ps.copyInto(fs)
        this.fade = fade; fadeTotal = fade
        clip = c; time = 0f
    }

    fun advance(dt: Float) {
        time += dt * speed
        sample()
        if (fade > 0f) {
            fade -= dt
            if (fade > 0f) blend(1f - fade / fadeTotal)
        }
        pose()
    }

    private fun rest() {
        for (i in 0 until n) {
            val nd = model.nodes[i]
            pt[3 * i] = nd.translation.x; pt[3 * i + 1] = nd.translation.y; pt[3 * i + 2] = nd.translation.z
            pr[4 * i] = nd.rotation.x; pr[4 * i + 1] = nd.rotation.y; pr[4 * i + 2] = nd.rotation.z; pr[4 * i + 3] = nd.rotation.w
            ps[3 * i] = nd.scale.x; ps[3 * i + 1] = nd.scale.y; ps[3 * i + 2] = nd.scale.z
        }
    }

    private fun sample() {
        rest()
        val c = clip ?: return
        val x = when {
            c.duration <= 0f -> 0f
            loop -> time.mod(c.duration)
            else -> minOf(time, c.duration)
        }
        for (channel in c.channels) sample(channel, x)
    }

    private fun sample(c: Channel, x: Float) {
        val k = c.target.components
        val dst = when (c.target) { ChannelTarget.TRANSLATION -> pt; ChannelTarget.ROTATION -> pr; ChannelTarget.SCALE -> ps }
        val base = c.node * k
        val t = c.times; val v = c.values
        if (t.size == 1 || x <= t[0]) { v.copyInto(dst, base, 0, k); return }
        if (x >= t[t.size - 1]) { v.copyInto(dst, base, (t.size - 1) * k, t.size * k); return }
        var lo = 0; var hi = t.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (t[mid] <= x) lo = mid else hi = mid }
        if (c.step) { v.copyInto(dst, base, lo * k, lo * k + k); return }
        val u = (x - t[lo]) / (t[hi] - t[lo])
        if (c.target == ChannelTarget.ROTATION) slerpInto(v, lo * 4, v, hi * 4, u, dst, base)
        else for (j in 0 until k) dst[base + j] = v[lo * k + j] + (v[hi * k + j] - v[lo * k + j]) * u
    }

    /** Pose = frozen pose → sampled pose at [u] ∈ [0, 1]. */
    private fun blend(u: Float) {
        for (j in pt.indices) pt[j] = ft[j] + (pt[j] - ft[j]) * u
        for (j in ps.indices) ps[j] = fs[j] + (ps[j] - fs[j]) * u
        for (i in 0 until n) slerpInto(fr, 4 * i, pr, 4 * i, u, pr, 4 * i)
    }

    private fun pose() {
        for (i in model.order) {
            val nd = model.nodes[i]
            val local = nd.matrix ?: Mat4.trs(Vec3(pt[3 * i], pt[3 * i + 1], pt[3 * i + 2]),
                Quat(pr[4 * i], pr[4 * i + 1], pr[4 * i + 2], pr[4 * i + 3]), Vec3(ps[3 * i], ps[3 * i + 1], ps[3 * i + 2]))
            world[i] = if (nd.parent < 0) local else world[nd.parent]!! * local
        }
        for (p in parts) {
            val s = p.skin
            if (s == null) p.pose = world[p.node]!! else { p.pose = Mat4.IDENTITY; deform(p, s) }
        }
    }

    /**
     * A skinned part's vertices where its joints are now: each the weighted sum of what its joints'
     * matrices (joint's pose × inverse bind matrix) make of it, normals likewise (uniform scales).
     */
    private fun deform(p: Part, s: SkinBinding) {
        val skin = model.skins[s.skin]
        val n = skin.joints.size
        if (s.joints.size < 12 * n) s.joints = FloatArray(12 * n)
        val j = s.joints
        for (k in 0 until n) {
            // Mat4 names its coefficients column first: mCR. Rows of the affine part, in 12 floats.
            val m = world[skin.joints[k]]!! * skin.inverseBind[k]; val o = 12 * k
            j[o] = m.m00; j[o + 1] = m.m10; j[o + 2] = m.m20; j[o + 3] = m.m30
            j[o + 4] = m.m01; j[o + 5] = m.m11; j[o + 6] = m.m21; j[o + 7] = m.m31
            j[o + 8] = m.m02; j[o + 9] = m.m12; j[o + 10] = m.m22; j[o + 11] = m.m32
        }
        val src = s.bind; val dst = p.mesh.vertices; val w = s.weights
        for (v in 0 until src.size / 9) {
            val i = 9 * v; val wo = 8 * v
            val px = src[i]; val py = src[i + 1]; val pz = src[i + 2]; val nx = src[i + 3]; val ny = src[i + 4]; val nz = src[i + 5]
            var x = 0f; var y = 0f; var z = 0f; var a = 0f; var b = 0f; var c = 0f
            for (k in 0..3) {
                val weight = w[wo + 4 + k]
                if (weight == 0f) continue
                val o = 12 * w[wo + k].toInt()
                x += weight * (j[o] * px + j[o + 1] * py + j[o + 2] * pz + j[o + 3])
                y += weight * (j[o + 4] * px + j[o + 5] * py + j[o + 6] * pz + j[o + 7])
                z += weight * (j[o + 8] * px + j[o + 9] * py + j[o + 10] * pz + j[o + 11])
                a += weight * (j[o] * nx + j[o + 1] * ny + j[o + 2] * nz)
                b += weight * (j[o + 4] * nx + j[o + 5] * ny + j[o + 6] * nz)
                c += weight * (j[o + 8] * nx + j[o + 9] * ny + j[o + 10] * nz)
            }
            dst[i] = x; dst[i + 1] = y; dst[i + 2] = z
            val l = kotlin.math.sqrt(a * a + b * b + c * c)
            if (l > 0f) { dst[i + 3] = a / l; dst[i + 4] = b / l; dst[i + 5] = c / l }
        }
        p.mesh.version++
    }
}
