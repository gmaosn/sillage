package sillage

import sillage.io.Json
import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * glTF 2.0 binary (.glb) loader, in Kotlin. It reads the node hierarchy, the material or vertex
 * colours, the material names, the base colour textures (PNG or JPEG, in the file, in a data URI or
 * beside it; alpha masks), skins (joints, inverse bind matrices, four weights per vertex) and the node
 * animations (translation, rotation, scale; linear or step). Cubic-spline curves and morph targets are
 * refused with a message that says so. A path is looked up as a file, then in [sillage.io.Assets.roots], then in the
 * classpath resources.
 */
object Glb {
    /** The model in its rest pose as one mesh (static scenery). */
    fun load(path: String): Mesh = loadModel(path).flatten().also {
        require(it.triangleCount > 0) { "GLB without triangles: $path" } }

    /** The model with its hierarchy and animations. */
    fun loadModel(path: String): Model = readModel(bytes(path)) { uri -> bytes(Path.of(path).resolveSibling(uri).toString()) }

    /** [load] from the bytes of a file. */
    fun read(bytes: ByteArray): Mesh = readModel(bytes).flatten().also { require(it.triangleCount > 0) { "GLB without triangles" } }

    private fun bytes(path: String): ByteArray = sillage.io.Assets.bytes(path, "GLB")

    /** [loadModel] from the bytes of a file; [beside] gives the bytes of a file the GLB names (an image beside it). */
    fun readModel(bytes: ByteArray, beside: (String) -> ByteArray = { throw IllegalArgumentException("GLB: image file « $it » unavailable") }): Model {
        require(bytes.size >= 20) { "Truncated GLB" }
        val file = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(file.int == 0x46546c67 && file.int == 2) { "A glTF 2.0 GLB file is required" }
        require(file.int == bytes.size) { "Invalid GLB size" }
        var json: Json.Obj? = null
        var bin: ByteBuffer? = null
        while (file.remaining() >= 8) {
            val size = file.int; val type = file.int
            require(size >= 0 && size <= file.remaining()) { "Chunk GLB invalide" }
            val chunk = ByteArray(size); file.get(chunk)
            when (type) {
                0x4e4f534a -> json = Json.parse(chunk.toString(Charsets.UTF_8).trimEnd('\u0000', ' ')) as? Json.Obj
                    ?: throw IllegalArgumentException("The JSON of a GLB must be an object")
                0x004e4942 -> bin = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            }
        }
        val doc = requireNotNull(json) { "Missing JSON chunk" }
        val required = doc.arr("extensionsRequired")
        require(required == null || required.size == 0) { "Unsupported required GLB extensions: " + required }
        val buffers = doc.arr("buffers")
        val buffer: ByteBuffer = if (buffers == null || buffers.size == 0) ByteBuffer.allocate(0) else {
            require(buffers.size == 1 && !buffers.obj(0).has("uri")) { "The GLB must embed its buffer" }
            requireNotNull(bin) { "Missing BIN chunk" }
        }
        /** Element [i] of a glTF array, refused cleanly when the file points outside it. */
        fun Json.Arr?.at(i: Int?, quoi: String): Json.Obj {
            require(this != null && i != null && i in 0 until size) { "GLB: $quoi ${i ?: "missing"} out of range" }
            return obj(i)
        }
        fun FloatArray.taille(n: Int, quoi: String): FloatArray { require(size >= n) { "GLB: $quoi too short ($size values instead of $n)" }; return this }
        val accessors = doc.arr("accessors") ?: Json.Arr(emptyList())
        val views = doc.arr("bufferViews") ?: Json.Arr(emptyList())
        fun accessor(index: Int, expected: String): Pair<Json.Obj, Int> {
            val a = accessors.at(index, "accessor")
            require(!a.has("sparse") && a.string("type") == expected) { "Unsupported GLB accessor (expected $expected)" }
            val viewIndex = requireNotNull(a.int("bufferView")) { "GLB accessor without bufferView" }
            require(viewIndex in 0 until views.size) { "Missing GLB bufferView $viewIndex" }
            val v = views.obj(viewIndex)
            require((v.int("buffer") ?: 0) == 0) { "GLB: only one buffer is read" }
            val start = (v.int("byteOffset") ?: 0).toLong() + (a.int("byteOffset") ?: 0)
            require(start in 0..buffer.limit().toLong()) { "GLB: accessor $index outside the buffer" }
            val count = requireNotNull(a.int("count")) { "GLB: accessor $index without count" }
            require(count >= 0 && count <= buffer.limit()) { "GLB: count $count impossible for a buffer of ${buffer.limit()} bytes" }
            return a to start.toInt()
        }
        fun values(index: Int, type: String): List<FloatArray> {
            val (a, offset) = accessor(index, type)
            val components = when(type) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> error("Invalid accessor type " + type) }
            val componentType = a.int("componentType")
            val width = when(componentType) {5126 -> 4; 5121 -> 1; 5123 -> 2; else -> error("Unsupported component type")}
            val v = views.obj(a.int("bufferView")!!)
            val stride = v.int("byteStride") ?: (width * components)
            require(stride in width * components..255) { "GLB : byteStride $stride invalide" }
            val normalized = a.bool("normalized") ?: false
            return List(requireNotNull(a.int("count"))) { i -> FloatArray(components) { c ->
                val o = offset + i * stride + c * width
                require(o >= 0 && o + width <= buffer.limit()) { "Accessor outside the buffer" }
                when(componentType) {
                    5126 -> buffer.getFloat(o)
                    5121 -> (buffer.get(o).toInt() and 255).toFloat() / if(normalized) 255f else 1f
                    else -> (buffer.getShort(o).toInt() and 65535).toFloat() / if(normalized) 65535f else 1f
                }
            } }
        }
        fun floats(index: Int, type: String): FloatArray {
            require(accessors.at(index, "accessor").int("componentType") == 5126) { "GLB animations must be floats" }
            val list = values(index, type)
            return FloatArray(list.sumOf { it.size }).also { out -> var k = 0; for (v in list) for (x in v) out[k++] = x }
        }
        fun indices(index: Int): IntArray {
            val (a, offset) = accessor(index, "SCALAR")
            val type = a.int("componentType")
            val width = when(type) {5121 -> 1; 5123 -> 2; 5125 -> 4; else -> error("Indices GLB invalides")}
            return IntArray(requireNotNull(a.int("count"))) { i ->
                val o = offset + i * width
                require(o >= 0 && o + width <= buffer.limit()) { "Indices outside the buffer" }
                when(type) {5121 -> buffer.get(o).toInt() and 255; 5123 -> buffer.getShort(o).toInt() and 65535; else -> buffer.getInt(o)}
            }
        }
        // Base colour textures: one Texture per image, shared by the materials and textures that use it.
        val images = HashMap<Int, Texture>()
        fun image(index: Int): Texture = images.getOrPut(index) {
            val img = doc.arr("images").at(index, "image")
            val data: ByteArray = when {
                img.has("bufferView") -> {
                    val v = views.at(img.int("bufferView"), "bufferView")
                    val start = v.int("byteOffset") ?: 0; val len = requireNotNull(v.int("byteLength")) { "GLB: bufferView without byteLength" }
                    require(start >= 0 && len >= 0 && start.toLong() + len <= buffer.limit()) { "GLB: image outside the buffer" }
                    ByteArray(len).also { b -> for (i in 0 until len) b[i] = buffer.get(start + i) }
                }
                img.string("uri")?.startsWith("data:") == true -> {
                    val uri = img.string("uri")!!
                    require(";base64," in uri) { "GLB: data URI images must be base64" }
                    java.util.Base64.getDecoder().decode(uri.substringAfter(";base64,"))
                }
                img.string("uri") != null -> beside(java.net.URLDecoder.decode(img.string("uri")!!, Charsets.UTF_8))
                else -> throw IllegalArgumentException("GLB: image $index has no data")
            }
            Texture.image(img.string("name") ?: "image$index", data)
        }
        val materialsJson = doc.arr("materials") ?: Json.Arr(emptyList())
        val textures = List(materialsJson.size) { m ->
            val t = materialsJson.obj(m).obj("pbrMetallicRoughness")?.obj("baseColorTexture") ?: return@List null
            require((t.int("texCoord") ?: 0) == 0) { "GLB: only the first set of texture coordinates (TEXCOORD_0) is read" }
            val texture = doc.arr("textures").at(t.int("index"), "texture")
            image(requireNotNull(texture.int("source")) { "GLB: texture without an image (source)" })
        }
        // Alpha: MASK cuts out what is below alphaCutoff; BLEND is drawn as MASK at 0.5 (no sorting of transparent things).
        val cutoffs = FloatArray(materialsJson.size) { m ->
            val mat = materialsJson.obj(m)
            when (mat.string("alphaMode") ?: "OPAQUE") { "MASK" -> mat.float("alphaCutoff") ?: .5f; "BLEND" -> .5f; else -> 0f }
        }

        /**
         * Triangles of one glTF mesh in node space: 9 position floats, r, g, b, then the material index; their
         * texture coordinates (6 floats each); their skin weights (24 floats each: per vertex 4 joints, 4 weights).
         */
        fun triangles(meshIndex: Int): Triple<FloatArray, FloatArray, FloatArray> {
            val out = ArrayList<Float>(); val uvOut = ArrayList<Float>(); val skinOut = ArrayList<Float>()
            val mesh = doc.arr("meshes").at(meshIndex, "maillage")
            for (primitive in requireNotNull(mesh.arr("primitives"))) {
                val p = primitive as Json.Obj
                require((p.int("mode") ?: 4) == 4) { "Only triangles are supported" }
                val attributes = requireNotNull(p.obj("attributes"))
                val positions = values(requireNotNull(attributes.int("POSITION")), "VEC3")
                require(!p.has("targets")) { "GLB: morph targets are not supported (use a skin or separate nodes)" }
                val textured = p.int("material")?.let { it in textures.indices && textures[it] != null } == true
                val joints = attributes.int("JOINTS_0")?.let { values(it, "VEC4") }
                val weights = attributes.int("WEIGHTS_0")?.let { values(it, "VEC4") }
                require((joints == null) == (weights == null)) { "GLB: JOINTS_0 and WEIGHTS_0 go together" }
                require(joints == null || (joints.size >= positions.size && weights!!.size >= positions.size)) { "GLB: fewer skin weights than vertices" }
                val uvs = attributes.int("TEXCOORD_0")?.let { values(it, "VEC2") }
                require(!textured || uvs != null) { "GLB: a textured material needs texture coordinates (TEXCOORD_0)" }
                require(uvs == null || uvs.size >= positions.size) { "GLB: fewer texture coordinates than vertices" }
                val colors = attributes.int("COLOR_0")?.let { values(it, requireNotNull(accessors.at(it, "accessor").string("type")) { "GLB: colours without a type" }) }
                ?.onEach { require(it.size >= 3) { "GLB: incomplete vertex colours" } }
                var color = Color(0.8f,0.8f,0.8f)
                p.int("material")?.let { materialIndex ->
                    val material = doc.arr("materials").at(materialIndex, "material")
                    val pbr = material.obj("pbrMetallicRoughness")
                    pbr?.arr("baseColorFactor")?.floats()?.taille(3, "baseColorFactor")?.let { color = Color(it[0],it[1],it[2]) }
                }
                val ids = p.int("indices")?.let { indices(it) } ?: IntArray(positions.size) { it }
                require(ids.size % 3 == 0)
                for(i in ids.indices step 3) {
                    val a=ids[i]; val b=ids[i+1]; val c=ids[i+2]
                    require(a in positions.indices && b in positions.indices && c in positions.indices) { "GLB index out of range" }
                    val vc = colors?.let { cs ->
                        Color(color.r * (cs[a][0].toDouble() + cs[b][0] + cs[c][0]).toFloat()/3,
                            color.g * (cs[a][1].toDouble() + cs[b][1] + cs[c][1]).toFloat()/3,
                            color.b * (cs[a][2].toDouble() + cs[b][2] + cs[c][2]).toFloat()/3) } ?: color
                    for (v in intArrayOf(a, b, c)) for (j in 0..2) out.add(positions[v][j])
                    out.add(vc.r); out.add(vc.g); out.add(vc.b)
                    out.add((p.int("material") ?: -1).toFloat())
                    for (v in intArrayOf(a, b, c)) { uvOut.add(uvs?.get(v)?.get(0) ?: 0f); uvOut.add(uvs?.get(v)?.get(1) ?: 0f) }
                    if (joints != null) for (v in intArrayOf(a, b, c)) {
                        val w = weights!![v]; val sum = w[0] + w[1] + w[2] + w[3]
                        for (k in 0..3) skinOut.add(joints[v][k])
                        // Weights brought to a sum of 1 (exporters round them); none: all on the first joint.
                        for (k in 0..3) skinOut.add(if (sum > 0f) w[k] / sum else if (k == 0) 1f else 0f)
                    }
                }
            }
            return Triple(out.toFloatArray(), uvOut.toFloatArray(), skinOut.toFloatArray())
        }

        val nodesJson = doc.arr("nodes") ?: Json.Arr(emptyList())
        val parents = IntArray(nodesJson.size) { -1 }
        for (i in 0 until nodesJson.size) nodesJson.obj(i).arr("children")?.forEach {
            val c = (it as Json.Num).toInt()
            require(c in 0 until nodesJson.size) { "Missing GLB child $c" }
            require(parents[c] < 0 && c != i) { "Cycle or shared node in the GLB hierarchy" }
            parents[c] = i
        }
        val meshCache = HashMap<Int, Triple<FloatArray, FloatArray, FloatArray>>()
        val nodes = List(nodesJson.size) { i ->
            val node = nodesJson.obj(i)
            val matrix = node.arr("matrix")?.let { Mat4.fromColumnMajor(it.floats().taille(16, "matrix")) }
            ModelNode(
                name = node.string("name"),
                parent = parents[i],
                children = node.arr("children")?.ints() ?: IntArray(0),
                translation = node.arr("translation")?.floats()?.taille(3, "translation")?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3.ZERO,
                rotation = node.arr("rotation")?.floats()?.taille(4, "rotation")?.let { Quat(it[0], it[1], it[2], it[3]).normalized() } ?: Quat.IDENTITY,
                scale = node.arr("scale")?.floats()?.taille(3, "scale")?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3.ONE,
                matrix = matrix,
                triangles = node.int("mesh")?.let { m -> meshCache.getOrPut(m) { triangles(m) }.first } ?: FloatArray(0),
                uvs = node.int("mesh")?.let { m -> meshCache.getValue(m).second } ?: FloatArray(0),
                skin = node.int("skin")?.also { require(it in 0 until (doc.arr("skins")?.size ?: 0)) { "GLB: node $i uses a missing skin" } } ?: -1,
                skinWeights = node.int("mesh")?.let { m -> meshCache.getValue(m).third } ?: FloatArray(0),
            )
        }
        val scenes = doc.arr("scenes")
        val racines = if (scenes == null || scenes.size == 0) IntArray(nodesJson.size) { it }.filter { parents[it] < 0 }.toIntArray()
            else scenes.at(doc.int("scene") ?: 0, "scene").arr("nodes")?.ints() ?: IntArray(0)
        for (r in racines) require(r in nodes.indices && parents[r] < 0) { "Invalid GLB scene root: $r" }

        val clips = LinkedHashMap<String, Clip>()
        doc.arr("animations")?.forEachIndexed { index, a ->
            val anim = a as Json.Obj
            val samplers = requireNotNull(anim.arr("samplers")) { "GLB animation without samplers" }
            val canaux = requireNotNull(anim.arr("channels")) { "GLB animation without channels" }.map { ch ->
                val c = ch as Json.Obj
                val target = requireNotNull(c.obj("target")) { "Animation channel without a target" }
                val node = requireNotNull(target.int("node")) { "Animation channel without a node" }
                require(node in nodes.indices) { "Animation channel: no node $node" }
                require(nodes[node].matrix == null) { "An animated node must use translation/rotation/scale, not matrix" }
                val chemin = when (val path = target.string("path")) {
                    "translation" -> ChannelTarget.TRANSLATION; "rotation" -> ChannelTarget.ROTATION; "scale" -> ChannelTarget.SCALE
                    else -> throw IllegalArgumentException("Animation de « $path » non prise en charge (seulement translation, rotation, scale)")
                }
                val sampler = samplers.at(c.int("sampler"), "sampler")
                val palier = when (val interp = sampler.string("interpolation") ?: "LINEAR") {
                    "LINEAR" -> false; "STEP" -> true
                    else -> throw IllegalArgumentException("Unsupported interpolation $interp (LINEAR or STEP)")
                }
                Channel(node, chemin, floats(requireNotNull(sampler.int("input")), "SCALAR"),
                    floats(requireNotNull(sampler.int("output")), if (chemin == ChannelTarget.ROTATION) "VEC4" else "VEC3"), palier)
            }
            val nom = anim.string("name") ?: "animation$index"
            clips[nom] = Clip(nom, canaux)
        }
        val materialNames = doc.arr("materials")?.map { (it as Json.Obj).string("name") ?: "" } ?: emptyList()
        val skins = doc.arr("skins")?.mapIndexed { k, s ->
            val skin = s as Json.Obj
            val joints = requireNotNull(skin.arr("joints")?.ints()) { "GLB: skin $k without joints" }
            require(joints.isNotEmpty() && joints.all { it in nodes.indices }) { "GLB: skin $k names a missing joint" }
            val inverse = skin.int("inverseBindMatrices")?.let { a -> values(a, "MAT4").map { Mat4.fromColumnMajor(it) } } ?: List(joints.size) { Mat4.IDENTITY }
            require(inverse.size >= joints.size) { "GLB: skin $k has fewer inverse bind matrices than joints" }
            Skin(joints, inverse.take(joints.size))
        } ?: emptyList()
        for (n in nodes) if (n.skin >= 0) {
            require(n.skinWeights.size == n.triangles.size / TRI * 24) { "GLB: a skinned mesh needs JOINTS_0 and WEIGHTS_0" }
            val count = skins[n.skin].joints.size
            for (t in 0 until n.skinWeights.size / 24) for (v in 0..2) for (k in 0..3)
                require(n.skinWeights[24 * t + 8 * v + k].toInt() in 0 until count) { "GLB: a vertex names joint ${n.skinWeights[24 * t + 8 * v + k].toInt()} of a skin of $count" }
        }
        return Model(nodes, racines, clips, materialNames, textures, cutoffs, skins)
    }
}
