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
    /** Per triangle, the normals of a, b, c as the file gives them (9 floats); empty when it has none. */
    internal val normals: FloatArray = FloatArray(0),
    /** Per triangle, the colours of a, b, c (9 floats) when the file gives a colour per vertex (COLOR_0); empty otherwise. */
    internal val tints: FloatArray = FloatArray(0),
    /** Per triangle, the tangents of a, b, c (12 floats: x, y, z, handedness) for a normal map; empty when no material has one. */
    internal val tangents: FloatArray = FloatArray(0),
    /** The skin that deforms its mesh ([Model.skins]), −1: none. */
    val skin: Int = -1,
    /** Per triangle, per vertex 4 joints (indices in the skin) then 4 weights (24 floats); empty without a skin. */
    internal val skinWeights: FloatArray = FloatArray(0),
    /** Its custom properties (glTF `extras`: Blender's custom properties when exported): strings, numbers (Double), booleans. */
    val properties: Map<String, Any?> = emptyMap(),
    /** The lamp it carries (KHR_lights_punctual: Blender's lights), or null. */
    val light: ModelLight? = null,
    /** Its morph targets' weights at rest (the node's, else its mesh's), one per target; empty without targets. */
    internal val morphWeights: FloatArray = FloatArray(0),
    /** Per target, per triangle, how far a, b, c move at weight 1 (9 floats); [triangles] already hold the rest weights. */
    internal val morphPositions: FloatArray = FloatArray(0),
    /** The same for the file's normals (9 floats per triangle and target), empty when the targets have none. */
    internal val morphNormals: FloatArray = FloatArray(0),
) {
    /**
     * What it is for in a scene, from its name and its `sillage` property (see [Role]): Blender's way of
     * saying "this box is a collision volume", "the player starts here", "this crate moves".
     */
    val role: Role = roleOf(name, properties, light)
    val local: Mat4 get() = matrix ?: Mat4.trs(translation, rotation, scale)
}

/**
 * A skeleton: the nodes that act as its [joints] and, for each, the inverse of its place in the model
 * when the mesh was bound to it. A skinned vertex goes where its joints take it, by their weights.
 */
class Skin internal constructor(val joints: IntArray, val inverseBind: List<Mat4>)

/** A lamp of a model: its kind ("point", "spot", "directional"), colour, glTF intensity (candela for a point), range in metres. */
class ModelLight internal constructor(val type: String, val color: Color, val intensity: Float, val range: Float?, val name: String?)

/**
 * What a node of a model is for in a scene. Blender sets it by the object's name (a prefix) or by a
 * custom property `sillage` (its value in brackets):
 *
 * - [COLLIDER] `col_…` (`collider`): an invisible collision volume;
 * - [SPAWN] `spawn` (`spawn`): where the player starts, facing the object's −Z (Blender's −Y… as exported);
 * - [DYNAMIC] (`dynamic`): a separate object that moves, falls and can be pushed;
 * - [ZONE] `zone_…` (`zone`): an invisible trigger zone, found by name;
 * - [PICKUP] `pickup_…` (`pickup`): a separate object a player picks up;
 * - [LAMP]: a light;
 * - [DRAWN]: everything else, drawn as part of the model.
 */
enum class Role { DRAWN, COLLIDER, SPAWN, DYNAMIC, ZONE, PICKUP, LAMP }

internal fun roleOf(name: String?, properties: Map<String, Any?>, light: ModelLight?): Role {
    when ((properties["sillage"] as? String)?.lowercase()) {
        "collider" -> return Role.COLLIDER; "spawn" -> return Role.SPAWN; "dynamic" -> return Role.DYNAMIC
        "zone" -> return Role.ZONE; "pickup" -> return Role.PICKUP; "drawn", "none" -> return Role.DRAWN
    }
    val n = name?.lowercase() ?: ""
    return when {
        n.startsWith("col_") || n == "col" -> Role.COLLIDER
        n == "spawn" || n.startsWith("spawn.") || n.startsWith("spawn_") -> Role.SPAWN
        n.startsWith("zone_") -> Role.ZONE
        n.startsWith("pickup_") -> Role.PICKUP
        light != null -> Role.LAMP
        else -> Role.DRAWN
    }
}

/** Whose normals a model's meshes use (see [Scene.model]). */
enum class Normals {
    /** The engine's own, soft: each vertex takes the mean of the faces around it, edges sharper than 55° stay crisp (the default). */
    SMOOTH,
    /** The file's, as Blender exported them: flat faces stay flat, edges marked sharp stay sharp, custom normals are kept. A mesh without normals in the file stays soft. */
    FROM_FILE
}

/** What a channel animates. */
enum class ChannelTarget(val components: Int) { TRANSLATION(3), ROTATION(4), SCALE(3) }

/** One animated property of one node: key times and values, linear (slerp for rotations) or step. */
class Channel internal constructor(val node: Int, val target: ChannelTarget, val times: FloatArray, val values: FloatArray, val step: Boolean,
                                   /** glTF's CUBICSPLINE: per key, its in-tangent then its out-tangent (2 × components floats); null for linear or step. */
                                   internal val tangents: FloatArray? = null) {
    init {
        require(times.isNotEmpty()) { "Animation channel without keys" }
        require(values.size == times.size * target.components) { "Animation channel: ${values.size} values for ${times.size} keys" }
        require(tangents == null || tangents.size == 2 * values.size) { "Animation channel: ${tangents?.size} tangents for ${times.size} keys" }
        for (i in 1 until times.size) require(times[i] > times[i - 1]) { "Animation key times must increase" }
    }
}

/**
 * The morph target weights of one node, animated: key times, then [count] weights per key; linear, step, or a
 * cubic spline (per key, [count] in-tangents then [count] out-tangents).
 */
internal class WeightChannel(val node: Int, val count: Int, val times: FloatArray, val values: FloatArray, val step: Boolean, val tangents: FloatArray?) {
    init {
        require(times.isNotEmpty()) { "Animation channel without keys" }
        require(values.size == times.size * count) { "GLB: ${values.size} morph weights for ${times.size} keys of $count targets" }
        for (i in 1 until times.size) require(times[i] > times[i - 1]) { "Animation key times must increase" }
    }
}

/** A glTF animation: several channels sharing one timeline. */
class Clip internal constructor(val name: String, val channels: List<Channel>,
                                /** Its channels of morph target weights. */
                                internal val weights: List<WeightChannel> = emptyList()) {
    val duration: Float = maxOf(channels.maxOfOrNull { it.times.last() } ?: 0f, weights.maxOfOrNull { it.times.last() } ?: 0f)
}

/**
 * The value of a cubic Hermite spline between keys [lo] and [hi] at [u] ∈ [0, 1] (glTF's CUBICSPLINE): component [j]
 * of [k], the values in [v], the tangents in [tan] (per key, in then out), [span] seconds between the keys.
 */
internal fun hermite(v: FloatArray, tan: FloatArray, k: Int, j: Int, lo: Int, hi: Int, u: Float, span: Float): Float {
    val u2 = u * u; val u3 = u2 * u
    val p0 = v[lo * k + j]; val p1 = v[hi * k + j]
    val m0 = tan[(2 * lo + 1) * k + j] * span; val m1 = tan[2 * hi * k + j] * span     // out-tangent of lo, in-tangent of hi
    return (2f * u3 - 3f * u2 + 1f) * p0 + (u3 - 2f * u2 + u) * m0 + (-2f * u3 + 3f * u2) * p1 + (u3 - u2) * m1
}

/** Floats per model triangle: 9 positions, r, g, b, material index. */
internal const val TRI = 13

/** Adds the triangle at [k] of [n]'s triangles to [g], placed at a, b, c: with its vertices' own colours when the file gave some, its one colour otherwise. */
private fun tinted(g: MeshBuilder, n: ModelNode, k: Int, a: Vec3, b: Vec3, c: Vec3, mirrored: Boolean) {
    val t = n.triangles
    if (n.tints.isEmpty()) { val color = Color(t[k + 9], t[k + 10], t[k + 11]); if (mirrored) g.triangle(a, c, b, color) else g.triangle(a, b, c, color); return }
    val q = n.tints; val o = k / TRI * 9
    val ca = Color(q[o], q[o + 1], q[o + 2]); val cb = Color(q[o + 3], q[o + 4], q[o + 5]); val cc = Color(q[o + 6], q[o + 7], q[o + 8])
    if (mirrored) g.triangle(a, c, b, ca, cc, cb) else g.triangle(a, b, c, ca, cb, cc)
}

/** A GLB scene kept as a hierarchy, with its animations. */
class Model internal constructor(val nodes: List<ModelNode>, val roots: IntArray, val clips: Map<String, Clip>,
                                 /** Material names of the GLB, by index (as named in Blender). */
                                 val materials: List<String> = emptyList(),
                                 /** The base colour texture of each material (null: plain colour). */
                                 val textures: List<Texture?> = emptyList(),
                                 /** Per material, the alpha below which it is cut out (0: opaque). */
                                 internal val cutoffs: FloatArray = FloatArray(0),
                                 /** The skeletons of the model's skinned meshes. */
                                 val skins: List<Skin> = emptyList(),
                                 /** Whose normals its meshes use: the engine's soft ones, or the file's. */
                                 val normals: Normals = Normals.SMOOTH,
                                 /** What its materials give beyond their base colours: normal maps, emission, blending. */
                                 internal val looks: MaterialLooks? = null) {
    /**
     * The normals of [mesh]: the engine's soft ones ([Normals.SMOOTH]), or the file's ([Normals.FROM_FILE]):
     * triangle t of the mesh came from triangle [keptTri] of node [keptNode], placed by [place] (null: as
     * is), written a, c, b when [mirrored]. Triangles of a node without normals in the file keep soft ones.
     */
    private fun normalsOf(mesh: Mesh, keptNode: List<Int>, keptTri: List<Int>, mirrored: List<Boolean>?, place: (Int) -> Mat4?): Mesh {
        if (normals == Normals.SMOOTH || keptNode.all { nodes[it].normals.isEmpty() }) return mesh.smooth()
        val soft by lazy { mesh.smooth().vertices }
        val inverses = HashMap<Int, Mat4?>()
        val out = FloatArray(keptNode.size * 9)
        for (t in keptNode.indices) {
            val i = keptNode[t]; val k = keptTri[t]; val src = nodes[i].normals
            if (src.isEmpty()) { for (v in 0..2) for (c in 0..2) out[9 * t + 3 * v + c] = soft[27 * t + 9 * v + 3 + c]; continue }
            // Normals go through the inverse transpose of the place's 3×3 (right under a stretch).
            val inv = inverses.getOrPut(i) { place(i)?.inverseAffine() }
            val flip = mirrored?.get(t) == true
            for (v in 0..2) {
                val sv = if (!flip) v else intArrayOf(0, 2, 1)[v]
                var n = Vec3(src[9 * k + 3 * sv], src[9 * k + 3 * sv + 1], src[9 * k + 3 * sv + 2])
                if (inv != null) n = Vec3(inv.m00 * n.x + inv.m01 * n.y + inv.m02 * n.z, inv.m10 * n.x + inv.m11 * n.y + inv.m12 * n.z, inv.m20 * n.x + inv.m21 * n.y + inv.m22 * n.z)
                val l = n.length()
                if (l > 1e-12f) n = n * (1f / l)
                out[9 * t + 3 * v] = n.x; out[9 * t + 3 * v + 1] = n.y; out[9 * t + 3 * v + 2] = n.z
            }
        }
        return mesh.withNormals(out)
    }

    /** Nodes that are not drawn as part of the model (helpers: collision volumes, zones, separate objects, spawn), with their children. */
    internal val hidden: BooleanArray = BooleanArray(nodes.size).also { h ->
        fun hide(i: Int) { h[i] = true; for (c in nodes[i].children) hide(c) }
        for (i in nodes.indices) if (nodes[i].role != Role.DRAWN && nodes[i].role != Role.LAMP) hide(i)
    }

    /** The helpers of the model (every node whose [ModelNode.role] is not DRAWN), parents first. */
    val helpers: List<Int> get() = order.filter { nodes[it].role != Role.DRAWN && (nodes[it].parent < 0 || !hidden[nodes[it].parent]) }

    /** A node's place in the model at rest. */
    fun restPlace(i: Int): Mat4 = restWorld[i]

    /** The vertices of node [i]'s own mesh in its own space (3 floats each; none for an empty). */
    fun localPoints(i: Int): FloatArray {
        val t = nodes[i].triangles
        return FloatArray(t.size / TRI * 9) { k -> t[(k / 9) * TRI + k % 9] }
    }

    /** Node [i] and its children as one mesh in node [i]'s space, its scale left out (a separate object: see [Role.DYNAMIC]). */
    fun subtreeMesh(i: Int): Mesh {
        val g = MeshBuilder(); val mats = ArrayList<Int>()
        val keptNode = ArrayList<Int>(); val keptTri = ArrayList<Int>(); val keptMirrored = ArrayList<Boolean>(); val places = HashMap<Int, Mat4>()
        fun visit(j: Int, m: Mat4) {
            val n = nodes[j]; val t = n.triangles
            val mirrored = m.determinant3x3() < 0
            places[j] = m
            for (k in t.indices step TRI) {
                val a = m.transformPoint(Vec3(t[k], t[k + 1], t[k + 2])); val b = m.transformPoint(Vec3(t[k + 3], t[k + 4], t[k + 5]))
                val c = m.transformPoint(Vec3(t[k + 6], t[k + 7], t[k + 8]))
                val before = g.triangleCount
                tinted(g, n, k, a, b, c, mirrored)
                if (g.triangleCount > before) { mats.add(t[k + 12].toInt()); keptNode.add(j); keptTri.add(k / TRI); keptMirrored.add(mirrored) }
            }
            for (c in n.children) visit(c, m * nodes[c].local)
        }
        val s = nodes[i].scale
        visit(i, Mat4.trs(Vec3.ZERO, Quat.IDENTITY, s))
        return normalsOf(g.build(), keptNode, keptTri, keptMirrored) { places[it] }.withMaterials(mats.toIntArray())
    }

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
        val looks = looks
        if (textures.all { it == null } && (looks == null || mats.none { looks.any(it) })) return null
        var any = false
        val e = FloatArray(kept.size * 3 * EFFECT_FLOATS)
        for (t in kept.indices) {
            val m = mats[t]
            val tex = if (m in textures.indices) textures[m] else null
            // A material's own light (emission), and the texture coordinates its maps need even without a colour image.
            val glow = looks?.emission?.getOrNull(m)
            val maps = looks?.maps(m) == true
            if (tex == null && glow == null && !maps) continue
            any = true
            if (glow != null) for (k in 0..2) { val o = (3 * t + k) * EFFECT_FLOATS; e[o] = glow.r; e[o + 1] = glow.g; e[o + 2] = glow.b }
            val uv = uvs(t) ?: continue; val at = uvAt(t)
            for (k in 0..2) {
                val o = (3 * t + k) * EFFECT_FLOATS
                if (tex != null) { e[o + 4] = tex.id.toFloat(); e[o + 5] = 1f; e[o + 6] = 1f; e[o + 7] = 2f }
                e[o + 8] = uv[at + 2 * k]; e[o + 9] = uv[at + 2 * k + 1]; e[o + 10] = cutoffs.getOrElse(m) { 0f }
            }
        }
        return if (any) e else null
    }

    /**
     * The material maps of the triangles of a mesh ([Mesh.surface]): tangents ([tangent]: triangle, corner → x, y, z,
     * handedness, null: none), normal and emission maps, the opacity of blended materials (when [blendable]: not
     * for a part deformed every frame — skinned, or its morph weights animated —, whose mesh changes and is not split); null when no material has any.
     */
    private fun surfaceOf(mats: IntArray, blendable: Boolean, tangent: (Int, Int) -> FloatArray?): FloatArray? {
        val looks = looks ?: return null
        fun used(m: Int) = looks.maps(m) || (blendable && m in looks.blended.indices && looks.blended[m])
        if (mats.none { used(it) }) return null
        val s = FloatArray(mats.size * 3 * SURFACE_FLOATS)
        for (t in mats.indices) {
            val m = mats[t]
            if (!used(m)) continue
            val normal = looks.normalMap[m]; val glow = if (looks.emission[m] != null) looks.emissionMap[m] else null
            for (k in 0..2) {
                val o = (3 * t + k) * SURFACE_FLOATS
                if (normal != null) {
                    tangent(t, k)?.let { g -> s[o] = g[0]; s[o + 1] = g[1]; s[o + 2] = g[2]; s[o + 3] = g[3] }
                    s[o + 4] = normal.id.toFloat(); s[o + 5] = looks.normalScale[m]
                }
                if (glow != null) s[o + 6] = glow.id.toFloat()
                if (blendable && looks.blended[m]) s[o + 7] = looks.opacity[m].coerceIn(1e-3f, 1f)
            }
        }
        return s
    }

    /** Tangent [k] of triangle [tri] of node [i] turned by [place] (null: as it is), written a, c, b when [mirrored]. */
    private fun tangentOf(i: Int, tri: Int, k: Int, place: Mat4?, mirrored: Boolean): FloatArray? {
        val src = nodes[i].tangents
        if (src.isEmpty()) return null
        val o = 12 * tri + 4 * (if (!mirrored) k else intArrayOf(0, 2, 1)[k])
        var x = src[o]; var y = src[o + 1]; var z = src[o + 2]
        if (place != null) {
            val d = place.transformDirection(Vec3(x, y, z)).normalized(); x = d.x; y = d.y; z = d.z
        }
        return floatArrayOf(x, y, z, if (mirrored) -src[o + 3] else src[o + 3])
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

    /**
     * A node's triangles as a mesh in its space (in its bind space when skinned), per vertex its 4 joints and 4 weights,
     * and its morph targets (per target, 3 floats per vertex of the mesh; positions, then the file's normals).
     */
    private class LocalMesh(val mesh: Mesh, val weights: FloatArray?, val morph: FloatArray? = null, val morphNormals: FloatArray? = null)

    /** Each node's triangles as a mesh in node space (knowing each triangle's material), shared by every instance of the model. */
    private val localMeshes: Map<Int, LocalMesh> by lazy {
        order.filter { nodes[it].triangles.isNotEmpty() && !hidden[it] }.associateWith { i ->
            val g = MeshBuilder(); val t = nodes[i].triangles; val mats = ArrayList<Int>(); val kept = ArrayList<Int>()
            for (k in t.indices step TRI) {
                val before = g.triangleCount
                tinted(g, nodes[i], k, Vec3(t[k], t[k + 1], t[k + 2]), Vec3(t[k + 3], t[k + 4], t[k + 5]), Vec3(t[k + 6], t[k + 7], t[k + 8]), false)
                if (g.triangleCount > before) { mats.add(t[k + 12].toInt()); kept.add(k / TRI) }
            }
            val mesh = normalsOf(g.build(), List(kept.size) { i }, kept, null) { null }.withMaterials(mats.toIntArray())
            val uvs = nodes[i].uvs
            val imaged = imageEffects(kept.toIntArray(), mats.toIntArray(), { if (uvs.isEmpty()) null else uvs }, { 6 * kept[it] })?.let { mesh.withEffects(it) } ?: mesh
            // A part deformed every frame (skinned, or its morph weights animated) is not split for blending; its tangents stay those at rest.
            val textured = surfaceOf(mats.toIntArray(), nodes[i].skin < 0 && !morphAnimated[i]) { t, k -> tangentOf(i, kept[t], k, null, false) }?.let { imaged.withSurface(it) } ?: imaged
            val sw = nodes[i].skinWeights
            LocalMesh(textured, if (nodes[i].skin < 0) null else FloatArray(kept.size * 24) { j -> sw[24 * kept[j / 24] + j % 24] },
                keptMorph(nodes[i], nodes[i].morphPositions, kept), keptMorph(nodes[i], nodes[i].morphNormals, kept))
        }
    }

    /** A node's morph targets ([deltas]: per target, 9 floats per triangle) for the triangles kept in its mesh; null without. */
    private fun keptMorph(n: ModelNode, deltas: FloatArray, kept: List<Int>): FloatArray? {
        if (deltas.isEmpty()) return null
        val tris = n.triangles.size / TRI; val targets = n.morphWeights.size
        return FloatArray(targets * kept.size * 9) { j ->
            val t = j / (kept.size * 9); val q = (j / 9) % kept.size
            deltas[(t * tris + kept[q]) * 9 + j % 9]
        }
    }

    /** Nodes whose morph target weights some clip animates: their parts are deformed at every pose. */
    private val morphAnimated: BooleanArray by lazy {
        BooleanArray(nodes.size).also { a -> for (c in clips.values) for (w in c.weights) if (w.node in a.indices) a[w.node] = true }
    }

    /** The whole scene in its rest pose as one world-space mesh (static models), knowing each triangle's material. */
    fun flatten(): Mesh = flat

    private val flat: Mesh by lazy {
        val g = MeshBuilder(); val mats = ArrayList<Int>()
        val keptNode = ArrayList<Int>(); val keptTri = ArrayList<Int>(); val keptMirrored = ArrayList<Boolean>()
        fun visit(i: Int, parent: Mat4) {
            if (hidden[i]) return
            val n = nodes[i]
            val world = parent * n.local
            val mirrored = world.determinant3x3() < 0
            val t = n.triangles
            val skinned = n.skin >= 0
            for (k in t.indices step TRI) {
                val a = if (skinned) skinnedRest(i, k / TRI, 0) else world.transformPoint(Vec3(t[k], t[k + 1], t[k + 2]))
                val b = if (skinned) skinnedRest(i, k / TRI, 1) else world.transformPoint(Vec3(t[k + 3], t[k + 4], t[k + 5]))
                val c = if (skinned) skinnedRest(i, k / TRI, 2) else world.transformPoint(Vec3(t[k + 6], t[k + 7], t[k + 8]))
                // Face normals recomputed, also after a negative scale; degenerate triangles are skipped.
                val before = g.triangleCount
                tinted(g, n, k, a, b, c, mirrored)
                if (g.triangleCount > before) { mats.add(t[k + 12].toInt()); keptNode.add(i); keptTri.add(k / TRI); keptMirrored.add(mirrored) }
            }
            for (e in n.children) visit(e, world)
        }
        for (r in roots) visit(r, Mat4.IDENTITY)
        val mesh = normalsOf(g.build(), keptNode, keptTri, keptMirrored) { restWorld[it] }.withMaterials(mats.toIntArray())
        // A mirrored triangle was written a, c, b: its texture coordinates too.
        val uv = FloatArray(6)
        val imaged = imageEffects(IntArray(mats.size) { it }, mats.toIntArray(), { t ->
            val src = nodes[keptNode[t]].uvs; val o = 6 * keptTri[t]
            if (src.isEmpty()) null else {
                if (keptMirrored[t]) { uv[0] = src[o]; uv[1] = src[o + 1]; uv[2] = src[o + 4]; uv[3] = src[o + 5]; uv[4] = src[o + 2]; uv[5] = src[o + 3] }
                else for (j in 0..5) uv[j] = src[o + j]
                uv
            }
        }, { 0 })?.let { mesh.withEffects(it) } ?: mesh
        // Tangents turned with their triangles (a skinned node's as they are: the shader squares them to the normal).
        surfaceOf(mats.toIntArray(), true) { t, k ->
            val i = keptNode[t]
            tangentOf(i, keptTri[t], k, if (nodes[i].skin >= 0) null else restWorld[i], keptMirrored[t])
        }?.let { imaged.withSurface(it) } ?: imaged
    }

    /**
     * The scene in its rest pose as world-space triangles for collision (9 floats each, same winding
     * as [flatten]) and, for each triangle, its material index in [materials] (−1: none).
     */
    fun surfaces(): Pair<FloatArray, IntArray> {
        val pos = ArrayList<Float>(); val mat = ArrayList<Int>()
        fun visit(i: Int, parent: Mat4) {
            if (hidden[i]) return
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

    /**
     * New parts for one instance: one per node that has triangles; a skinned one, or one whose morph target weights
     * are animated, gets its own mesh, deformed every frame.
     */
    fun parts(): List<Part> = order.filter { it in localMeshes }.map { i ->
        val local = localMeshes.getValue(i)
        val w = local.weights
        val morph = local.morph?.takeIf { morphAnimated[i] }
        if (w == null && morph == null) Part(i, local.mesh)
        else Part(i, local.mesh.dynamicCopy()).also {
            if (w != null) it.skin = SkinBinding(nodes[i].skin, local.mesh.vertices, w)
            if (morph != null) it.morph = MorphBinding(nodes[i].morphWeights, local.mesh.vertices, morph, local.morphNormals)
        }
    }
}

/**
 * What deforms a part by its morph targets: the weights at rest (those its [bind] vertices already hold), and per
 * target how far each vertex moves at weight 1 (3 floats per vertex), its normal too when the file gives it.
 */
internal class MorphBinding(val rest: FloatArray, val bind: FloatArray, val positions: FloatArray, val normals: FloatArray?) {
    /** The morphed vertices, for a part that is also skinned (scratch). */
    var morphed = FloatArray(0)
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
    internal var morph: MorphBinding? = null
    /**
     * Its pose's 16 coefficients, column first as [Mat4] names them (m00, m01… m33), written in place by its
     * animator at every pose: the renderer reads them, and [pose] is made from them only when asked for.
     */
    internal val m = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    internal var version = 0
    private var made: Mat4 = Mat4.IDENTITY
    private var madeAt = 0
    var pose: Mat4
        get() {
            if (madeAt != version) { made = mat4(m, 0); madeAt = version }
            return made
        }
        internal set(v) { copyMat4(v, m, 0); version++ }
    // What the renderer last saw of it (its pose's version, its node's place) and for how many frames it has not changed.
    internal var seenVersion = -1
    internal var seenPosition: Vec3? = null
    internal var seenRotation: Quat? = null
    internal var seenScale = Float.NaN
    internal var stillFrames = 0
}

/** The matrix of the 16 coefficients of [a] from [o], column first (as [copyMat4] lays them). */
internal fun mat4(a: FloatArray, o: Int) = Mat4(a[o], a[o + 1], a[o + 2], a[o + 3], a[o + 4], a[o + 5], a[o + 6], a[o + 7],
    a[o + 8], a[o + 9], a[o + 10], a[o + 11], a[o + 12], a[o + 13], a[o + 14], a[o + 15])

/** [m]'s 16 coefficients into [a] from [o], column first (m00, m01, m02, m03, m10… m33). */
internal fun copyMat4(m: Mat4, a: FloatArray, o: Int) {
    a[o] = m.m00; a[o + 1] = m.m01; a[o + 2] = m.m02; a[o + 3] = m.m03
    a[o + 4] = m.m10; a[o + 5] = m.m11; a[o + 6] = m.m12; a[o + 7] = m.m13
    a[o + 8] = m.m20; a[o + 9] = m.m21; a[o + 10] = m.m22; a[o + 11] = m.m23
    a[o + 12] = m.m30; a[o + 13] = m.m31; a[o + 14] = m.m32; a[o + 15] = m.m33
}

/**
 * [a] (at [ao]) × [b] (at [bo]) into [out] (at [o]), 16 coefficients each laid as [copyMat4] lays them: the very
 * operations of [Mat4.times], in the same order, so that the result is the same to the bit.
 */
internal fun timesInto(a: FloatArray, ao: Int, b: FloatArray, bo: Int, out: FloatArray, o: Int) {
    val m00 = a[ao]; val m01 = a[ao + 1]; val m02 = a[ao + 2]; val m03 = a[ao + 3]
    val m10 = a[ao + 4]; val m11 = a[ao + 5]; val m12 = a[ao + 6]; val m13 = a[ao + 7]
    val m20 = a[ao + 8]; val m21 = a[ao + 9]; val m22 = a[ao + 10]; val m23 = a[ao + 11]
    val m30 = a[ao + 12]; val m31 = a[ao + 13]; val m32 = a[ao + 14]; val m33 = a[ao + 15]
    val b00 = b[bo]; val b01 = b[bo + 1]; val b02 = b[bo + 2]; val b03 = b[bo + 3]
    val b10 = b[bo + 4]; val b11 = b[bo + 5]; val b12 = b[bo + 6]; val b13 = b[bo + 7]
    val b20 = b[bo + 8]; val b21 = b[bo + 9]; val b22 = b[bo + 10]; val b23 = b[bo + 11]
    val b30 = b[bo + 12]; val b31 = b[bo + 13]; val b32 = b[bo + 14]; val b33 = b[bo + 15]
    out[o] = m00 * b00 + m10 * b01 + m20 * b02 + m30 * b03
    out[o + 1] = m01 * b00 + m11 * b01 + m21 * b02 + m31 * b03
    out[o + 2] = m02 * b00 + m12 * b01 + m22 * b02 + m32 * b03
    out[o + 3] = m03 * b00 + m13 * b01 + m23 * b02 + m33 * b03
    out[o + 4] = m00 * b10 + m10 * b11 + m20 * b12 + m30 * b13
    out[o + 5] = m01 * b10 + m11 * b11 + m21 * b12 + m31 * b13
    out[o + 6] = m02 * b10 + m12 * b11 + m22 * b12 + m32 * b13
    out[o + 7] = m03 * b10 + m13 * b11 + m23 * b12 + m33 * b13
    out[o + 8] = m00 * b20 + m10 * b21 + m20 * b22 + m30 * b23
    out[o + 9] = m01 * b20 + m11 * b21 + m21 * b22 + m31 * b23
    out[o + 10] = m02 * b20 + m12 * b21 + m22 * b22 + m32 * b23
    out[o + 11] = m03 * b20 + m13 * b21 + m23 * b22 + m33 * b23
    out[o + 12] = m00 * b30 + m10 * b31 + m20 * b32 + m30 * b33
    out[o + 13] = m01 * b30 + m11 * b31 + m21 * b32 + m31 * b33
    out[o + 14] = m02 * b30 + m12 * b31 + m22 * b32 + m32 * b33
    out[o + 15] = m03 * b30 + m13 * b31 + m23 * b32 + m33 * b33
}

/**
 * [Mat4.trs] of the translation, rotation and scale at [t], [r], [s] (3, 4 and 3 floats) into [out] at [o], the same
 * operations in the same order.
 */
internal fun trsInto(t: FloatArray, to: Int, r: FloatArray, ro: Int, s: FloatArray, so: Int, out: FloatArray, o: Int) {
    val rx = r[ro]; val ry = r[ro + 1]; val rz = r[ro + 2]; val rw = r[ro + 3]
    val xx = rx * rx; val yy = ry * ry; val zz = rz * rz
    val xy = rx * ry; val xz = rx * rz; val yz = ry * rz
    val wx = rw * rx; val wy = rw * ry; val wz = rw * rz
    val sx = s[so]; val sy = s[so + 1]; val sz = s[so + 2]
    out[o] = (1f - 2f * (yy + zz)) * sx; out[o + 1] = 2f * (xy + wz) * sx; out[o + 2] = 2f * (xz - wy) * sx; out[o + 3] = 0f
    out[o + 4] = 2f * (xy - wz) * sy; out[o + 5] = (1f - 2f * (xx + zz)) * sy; out[o + 6] = 2f * (yz + wx) * sy; out[o + 7] = 0f
    out[o + 8] = 2f * (xz + wy) * sz; out[o + 9] = 2f * (yz - wx) * sz; out[o + 10] = (1f - 2f * (xx + yy)) * sz; out[o + 11] = 0f
    out[o + 12] = t[to]; out[o + 13] = t[to + 1]; out[o + 14] = t[to + 2]; out[o + 15] = 1f
}

/**
 * Plays the clips of a [Model] on one instance's parts. Switching clip cross-fades from the pose
 * reached at that moment. The pose lives in flat arrays (translation, rotation, scale per node).
 */
class Animator(val model: Model, private val parts: List<Part>) {
    private val n = model.nodes.size
    private val pt = FloatArray(3 * n); private val pr = FloatArray(4 * n); private val ps = FloatArray(3 * n)
    private val ft = FloatArray(3 * n); private val fr = FloatArray(4 * n); private val fs = FloatArray(3 * n)
    // Each node's pose in the model's space, 16 coefficients column first (see copyMat4), worked out in place:
    // posing a model makes no matrix. Then the local one, and a joint's matrix for a skin.
    private val world = FloatArray(16 * n)
    private val local = FloatArray(16)
    private val joint = FloatArray(16)
    // The morph target weights of each node that has targets (null for the others), and as frozen for a cross-fade.
    private val weights: Array<FloatArray?> = Array(n) { model.nodes[it].morphWeights.takeIf { w -> w.isNotEmpty() }?.copyOf() }
    private val frozenWeights: Array<FloatArray?> = Array(n) { weights[it]?.copyOf() }

    /** Node [node]'s morph target weights as posed now (null: it has none). */
    internal fun morphWeights(node: Int): FloatArray? = weights[node]
    /** The inverse bind matrices of each skin, 16 coefficients per joint. */
    private val inverseBinds: Array<FloatArray> = Array(model.skins.size) { k ->
        val s = model.skins[k]; FloatArray(16 * s.inverseBind.size).also { a -> for (j in s.inverseBind.indices) copyMat4(s.inverseBind[j], a, 16 * j) }
    }
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
    fun nodePose(name: String): Mat4? = nodeIndex(name).takeIf { it >= 0 }?.let { mat4(world, 16 * it) }

    /** The index of the node called [name] (−1: none). */
    internal fun nodeIndex(name: String): Int = model.nodes.indexOfFirst { it.name == name }

    /** Each node's pose (16 coefficients from 16 × its index, column first), up to date after each [advance]. */
    internal val poses: FloatArray get() = world

    /** Starts [name] unless it is already playing; [fade] seconds of cross-fade from the current pose. */
    fun play(name: String, loop: Boolean = true, fade: Float = .15f, speed: Float = this.speed) {
        val c = model.clips[name] ?: throw IllegalArgumentException(
            "No animation « $name » in the model (it has: ${model.clips.keys.joinToString()})")
        this.speed = speed
        this.loop = loop
        if (c === clip) return
        pt.copyInto(ft); pr.copyInto(fr); ps.copyInto(fs)
        for (i in 0 until n) weights[i]?.copyInto(frozenWeights[i]!!)
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
            weights[i]?.let { nd.morphWeights.copyInto(it) }
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
        for (channel in c.weights) sample(channel, x)
    }

    /** Morph target weights at [x]: linear, step or a cubic spline, between the two keys around it. */
    private fun sample(c: WeightChannel, x: Float) {
        val dst = weights[c.node] ?: return
        val k = c.count; val t = c.times; val v = c.values
        val m = minOf(k, dst.size)
        if (t.size == 1 || x <= t[0]) { v.copyInto(dst, 0, 0, m); return }
        if (x >= t[t.size - 1]) { v.copyInto(dst, 0, (t.size - 1) * k, (t.size - 1) * k + m); return }
        var lo = 0; var hi = t.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (t[mid] <= x) lo = mid else hi = mid }
        if (c.step) { v.copyInto(dst, 0, lo * k, lo * k + m); return }
        val span = t[hi] - t[lo]; val u = (x - t[lo]) / span
        val tan = c.tangents
        for (j in 0 until m) dst[j] = if (tan != null) hermite(v, tan, k, j, lo, hi, u, span) else v[lo * k + j] + (v[hi * k + j] - v[lo * k + j]) * u
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
        val tan = c.tangents
        if (tan != null) {
            // glTF's cubic spline (Hermite, tangents scaled by the keys' spacing); a rotation is brought back to unit length.
            val span = t[hi] - t[lo]
            for (j in 0 until k) dst[base + j] = hermite(v, tan, k, j, lo, hi, u, span)
            if (c.target == ChannelTarget.ROTATION) {
                val l = kotlin.math.sqrt(dst[base] * dst[base] + dst[base + 1] * dst[base + 1] + dst[base + 2] * dst[base + 2] + dst[base + 3] * dst[base + 3])
                if (l > 0f) for (j in 0..3) dst[base + j] /= l
            }
            return
        }
        if (c.target == ChannelTarget.ROTATION) slerpInto(v, lo * 4, v, hi * 4, u, dst, base)
        else for (j in 0 until k) dst[base + j] = v[lo * k + j] + (v[hi * k + j] - v[lo * k + j]) * u
    }

    /** Pose = frozen pose → sampled pose at [u] ∈ [0, 1]. */
    private fun blend(u: Float) {
        for (j in pt.indices) pt[j] = ft[j] + (pt[j] - ft[j]) * u
        for (j in ps.indices) ps[j] = fs[j] + (ps[j] - fs[j]) * u
        for (i in 0 until n) slerpInto(fr, 4 * i, pr, 4 * i, u, pr, 4 * i)
        for (i in 0 until n) { val w = weights[i] ?: continue; val f = frozenWeights[i]!!; for (j in w.indices) w[j] = f[j] + (w[j] - f[j]) * u }
    }

    private fun pose() {
        // As Mat4.trs and Mat4.times would, operation for operation, without making a matrix.
        for (i in model.order) {
            val nd = model.nodes[i]
            val fixed = nd.matrix
            if (fixed != null) copyMat4(fixed, local, 0) else trsInto(pt, 3 * i, pr, 4 * i, ps, 3 * i, local, 0)
            if (nd.parent < 0) local.copyInto(world, 16 * i) else timesInto(world, 16 * nd.parent, local, 0, world, 16 * i)
        }
        for (k in parts.indices) {
            val p = parts[k]
            val s = p.skin; val mb = p.morph
            // Morph targets first (glTF's order), then the skin.
            if (mb != null) {
                if (s != null && mb.morphed.size < mb.bind.size) mb.morphed = mb.bind.copyOf()
                morph(mb, weights[p.node]!!, if (s == null) p.mesh.vertices else mb.morphed)
                if (s == null) p.mesh.version++
            }
            if (s == null) world.copyInto(p.m, 0, 16 * p.node, 16 * p.node + 16) else { copyMat4(Mat4.IDENTITY, p.m, 0); deform(p, s, if (mb != null) mb.morphed else s.bind) }
            p.version++
        }
    }

    /**
     * A skinned part's vertices where its joints are now: each the weighted sum of what its joints'
     * matrices (joint's pose × inverse bind matrix) make of it, normals likewise (uniform scales).
     */
    private fun deform(p: Part, s: SkinBinding, src: FloatArray) {
        val skin = model.skins[s.skin]
        val n = skin.joints.size
        if (s.joints.size < 12 * n) s.joints = FloatArray(12 * n)
        val j = s.joints
        for (k in 0 until n) {
            // Mat4 names its coefficients column first: mCR. Rows of the affine part, in 12 floats.
            val m = joint; val o = 12 * k
            timesInto(world, 16 * skin.joints[k], inverseBinds[s.skin], 16 * k, m, 0)
            j[o] = m[0]; j[o + 1] = m[4]; j[o + 2] = m[8]; j[o + 3] = m[12]
            j[o + 4] = m[1]; j[o + 5] = m[5]; j[o + 6] = m[9]; j[o + 7] = m[13]
            j[o + 8] = m[2]; j[o + 9] = m[6]; j[o + 10] = m[10]; j[o + 11] = m[14]
        }
        val dst = p.mesh.vertices; val w = s.weights
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

    /**
     * A part's vertices moved by its morph targets: each the bound one (at the rest weights) plus, per target, how far
     * it moves times the weight's change from rest; normals likewise when the file gives them, back to unit length.
     */
    private fun morph(mb: MorphBinding, w: FloatArray, dst: FloatArray) {
        val src = mb.bind; val d = mb.positions; val dn = mb.normals
        val count = minOf(w.size, mb.rest.size); val verts = src.size / 9
        for (v in 0 until verts) {
            val i = 9 * v
            var x = src[i]; var y = src[i + 1]; var z = src[i + 2]
            var a = src[i + 3]; var b = src[i + 4]; var c = src[i + 5]
            for (t in 0 until count) {
                val f = w[t] - mb.rest[t]
                if (f == 0f) continue
                val o = 3 * (t * verts + v)
                x += f * d[o]; y += f * d[o + 1]; z += f * d[o + 2]
                if (dn != null) { a += f * dn[o]; b += f * dn[o + 1]; c += f * dn[o + 2] }
            }
            dst[i] = x; dst[i + 1] = y; dst[i + 2] = z
            if (dn != null) {
                val l = kotlin.math.sqrt(a * a + b * b + c * c)
                if (l > 0f) { dst[i + 3] = a / l; dst[i + 4] = b / l; dst[i + 5] = c / l }
            }
        }
    }
}
