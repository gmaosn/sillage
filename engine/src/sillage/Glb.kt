package sillage

import sillage.io.Le

import sillage.io.Json
import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3

/**
 * glTF 2.0 binary (.glb) loader, in Kotlin. It reads the node hierarchy, the material or vertex
 * colours, the material names, the base colour textures (PNG or JPEG, in the file, in a data URI or
 * beside it; alpha masks), skins (joints, inverse bind matrices, four weights per vertex) and the node
 * animations (translation, rotation, scale, morph target weights; linear, step or cubic spline), morph targets
 * (positions and normals, deformed on the processor like the skins) and KHR_texture_transform (applied to the
 * texture coordinates as they are read). A path is looked up as a file, then in [sillage.io.Assets.roots], then in
 * the classpath resources.
 */
object Glb {
    /** The model in its rest pose as one mesh (static scenery). */
    fun load(path: String): Mesh = loadModel(path).flatten().also {
        require(it.triangleCount > 0) { "GLB without triangles: $path" } }

    /** The model with its hierarchy and animations. */
    fun loadModel(path: String, normals: Normals = Normals.SMOOTH): Model =
        readModel(bytes(path), { uri -> sillage.io.Assets.bytes(if ('/' in path) path.substringBeforeLast('/') + "/" + uri else uri, "Texture") }, normals)

    /** [load] from the bytes of a file. */
    fun read(bytes: ByteArray): Mesh = readModel(bytes).flatten().also { require(it.triangleCount > 0) { "GLB without triangles" } }

    private fun bytes(path: String): ByteArray = sillage.io.Assets.bytes(path, "GLB")

    /** [loadModel] from the bytes of a file; [beside] gives the bytes of a file the GLB names (an image beside it). */
    fun readModel(bytes: ByteArray, beside: (String) -> ByteArray = { throw IllegalArgumentException("GLB: image file « $it » unavailable") },
                  normals: Normals = Normals.SMOOTH): Model {
        require(bytes.size >= 20) { "Truncated GLB" }
        require(Le.i32(bytes, 0) == 0x46546c67 && Le.i32(bytes, 4) == 2) { "A glTF 2.0 GLB file is required" }
        require(Le.i32(bytes, 8) == bytes.size) { "Invalid GLB size" }
        var json: Json.Obj? = null
        var bin: LeBuffer? = null
        var at = 12
        while (bytes.size - at >= 8) {
            val size = Le.i32(bytes, at); val type = Le.i32(bytes, at + 4); at += 8
            require(size >= 0 && size <= bytes.size - at) { "Chunk GLB invalide" }
            val chunk = bytes.copyOfRange(at, at + size); at += size
            when (type) {
                0x4e4f534a -> json = Json.parse(chunk.decodeToString().trimEnd('\u0000', ' ')) as? Json.Obj
                    ?: throw IllegalArgumentException("The JSON of a GLB must be an object")
                0x004e4942 -> bin = LeBuffer(chunk)
            }
        }
        val doc = requireNotNull(json) { "Missing JSON chunk" }
        val required = doc.arr("extensionsRequired")?.map { (it as? Json.Str)?.value ?: it.toString() }.orEmpty()
            .filter { it != "KHR_lights_punctual" && it != "KHR_texture_transform" }
        require(required.isEmpty()) { "Unsupported required GLB extensions: " + required }
        val buffers = doc.arr("buffers")
        val buffer: LeBuffer = if (buffers == null || buffers.size == 0) LeBuffer(ByteArray(0)) else {
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
                    base64(uri.substringAfter(";base64,"))
                }
                img.string("uri") != null -> beside(percentDecode(img.string("uri")!!))
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
        // Normal maps, emission and blending (MaterialMaps.kt): their textures from the same images.
        val looks = materialLooks(materialsJson) { t ->
            require((t.int("texCoord") ?: 0) == 0) { "GLB: only the first set of texture coordinates (TEXCOORD_0) is read" }
            image(requireNotNull(doc.arr("textures").at(t.int("index"), "texture").int("source")) { "GLB: texture without an image (source)" })
        }
        // KHR_texture_transform of each material's base colour texture, applied to the texture coordinates as they are read
        // (the one set of coordinates its other textures share).
        val uvTransforms = List(materialsJson.size) { m -> textureTransform(materialsJson.obj(m).obj("pbrMetallicRoughness")?.obj("baseColorTexture")) }
        // Alpha: MASK cuts out what is below alphaCutoff; BLEND is blended (Model), its shadow cut out at 0.5.
        val cutoffs = FloatArray(materialsJson.size) { m ->
            val mat = materialsJson.obj(m)
            when (mat.string("alphaMode") ?: "OPAQUE") { "MASK" -> mat.float("alphaCutoff") ?: .5f; "BLEND" -> .5f; else -> 0f }
        }

        /**
         * Triangles of one glTF mesh in node space: 9 position floats, r, g, b, then the material index; their
         * texture coordinates (6 floats each); their skin weights (24 floats each: per vertex 4 joints, 4 weights).
         */
        class Read(val triangles: FloatArray, val uvs: FloatArray, val skin: FloatArray, val normals: FloatArray, val tints: FloatArray, val tangents: FloatArray,
                   val morph: MorphRead? = null)
        fun triangles(meshIndex: Int): Read {
            val out = ArrayList<Float>(); val uvOut = ArrayList<Float>(); val skinOut = ArrayList<Float>(); val nOut = ArrayList<Float>()
            val tintOut = ArrayList<Float>(); var tinted = false; var triangleCount = 0
            val tanOut = ArrayList<Float>(); var tangented = false
            var everyNormal = true
            val mesh = doc.arr("meshes").at(meshIndex, "maillage")
            val morph = MorphRead.of(mesh)
            for (primitive in requireNotNull(mesh.arr("primitives"))) {
                val p = primitive as Json.Obj
                require((p.int("mode") ?: 4) == 4) { "Only triangles are supported" }
                val attributes = requireNotNull(p.obj("attributes"))
                val positions = values(requireNotNull(attributes.int("POSITION")), "VEC3")
                val targets = morph?.targetsOf(p, positions.size) { index, type -> values(index, type) }
                val textured = p.int("material")?.let { it in textures.indices && textures[it] != null } == true
                val joints = attributes.int("JOINTS_0")?.let { values(it, "VEC4") }
                val weights = attributes.int("WEIGHTS_0")?.let { values(it, "VEC4") }
                require((joints == null) == (weights == null)) { "GLB: JOINTS_0 and WEIGHTS_0 go together" }
                require(joints == null || (joints.size >= positions.size && weights!!.size >= positions.size)) { "GLB: fewer skin weights than vertices" }
                val uvs = attributes.int("TEXCOORD_0")?.let { values(it, "VEC2") }
                val fileNormals = attributes.int("NORMAL")?.let { values(it, "VEC3") }?.takeIf { it.size >= positions.size }
                if (fileNormals == null) everyNormal = false
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
                // Tangents for a normal map: the file's (TANGENT), else made from the positions and texture coordinates.
                val tangents = if (uvs == null || p.int("material")?.let { looks.normalMap.getOrNull(it) } == null) null
                    else attributes.int("TANGENT")?.let { values(it, "VEC4") }?.takeIf { it.size >= positions.size } ?: tangentsOf(positions, fileNormals, uvs, ids).toList()
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
                    // Vertex colours (COLOR_0) keep one colour per vertex: gradients stay smooth across a face.
                    if (colors != null) {
                        if (!tinted) { tinted = true; repeat(triangleCount) { for (q in 0..2) { tintOut.add(out[13 * it + 9]); tintOut.add(out[13 * it + 10]); tintOut.add(out[13 * it + 11]) } } }
                        for (v in intArrayOf(a, b, c)) { tintOut.add(color.r * colors[v][0]); tintOut.add(color.g * colors[v][1]); tintOut.add(color.b * colors[v][2]) }
                    } else if (tinted) repeat(3) { tintOut.add(vc.r); tintOut.add(vc.g); tintOut.add(vc.b) }
                    if (tangents != null) {
                        if (!tangented) { tangented = true; repeat(12 * (triangleCount)) { tanOut.add(0f) } }
                        for (v in intArrayOf(a, b, c)) for (j in 0..3) tanOut.add(tangents[v][j])
                    } else if (tangented) repeat(12) { tanOut.add(0f) }
                    triangleCount++
                    val uvt = p.int("material")?.let { uvTransforms.getOrNull(it) }
                    for (v in intArrayOf(a, b, c)) {
                        val u0 = uvs?.get(v)?.get(0) ?: 0f; val v0 = uvs?.get(v)?.get(1) ?: 0f
                        if (uvt == null) { uvOut.add(u0); uvOut.add(v0) }
                        else { uvOut.add(uvt[0] * u0 + uvt[1] * v0 + uvt[2]); uvOut.add(uvt[3] * u0 + uvt[4] * v0 + uvt[5]) }
                    }
                    if (morph != null) morph.addTriangle(targets, a, b, c)
                    if (fileNormals != null) for (v in intArrayOf(a, b, c)) for (j in 0..2) nOut.add(fileNormals[v][j])
                    if (joints != null) for (v in intArrayOf(a, b, c)) {
                        val w = weights!![v]; val sum = w[0] + w[1] + w[2] + w[3]
                        for (k in 0..3) skinOut.add(joints[v][k])
                        // Weights brought to a sum of 1 (exporters round them); none: all on the first joint.
                        for (k in 0..3) skinOut.add(if (sum > 0f) w[k] / sum else if (k == 0) 1f else 0f)
                    }
                }
            }
            // Normals from the file only when every primitive of the mesh has them.
            return Read(out.toFloatArray(), uvOut.toFloatArray(), skinOut.toFloatArray(), if (everyNormal) nOut.toFloatArray() else FloatArray(0), tintOut.toFloatArray(),
                tanOut.toFloatArray(), morph)
        }

        val nodesJson = doc.arr("nodes") ?: Json.Arr(emptyList())
        val parents = IntArray(nodesJson.size) { -1 }
        for (i in 0 until nodesJson.size) nodesJson.obj(i).arr("children")?.forEach {
            val c = (it as Json.Num).toInt()
            require(c in 0 until nodesJson.size) { "Missing GLB child $c" }
            require(parents[c] < 0 && c != i) { "Cycle or shared node in the GLB hierarchy" }
            parents[c] = i
        }
        // Lamps (KHR_lights_punctual, as Blender exports its lights) and custom properties (extras).
        val lightsJson = doc.obj("extensions")?.obj("KHR_lights_punctual")?.arr("lights")
        val lights = List(lightsJson?.size ?: 0) { k ->
            val l = lightsJson!!.obj(k)
            val c = l.arr("color")?.floats()?.taille(3, "light color") ?: floatArrayOf(1f, 1f, 1f)
            ModelLight(l.string("type") ?: "point", Color(c[0], c[1], c[2]), l.float("intensity") ?: 1f,
                l.float("range"), l.string("name"))
        }
        fun plain(j: Json): Any? = when (j) {
            is Json.Str -> j.value; is Json.Num -> j.toDouble(); is Json.Bool -> j.value; is Json.Null -> null
            is Json.Arr -> j.items.map { plain(it) }; is Json.Obj -> j.fields.mapValues { plain(it.value) }
        }
        val meshCache = HashMap<Int, Read>()
        val nodes = List(nodesJson.size) { i ->
            val node = nodesJson.obj(i)
            node.int("mesh")?.let { m -> meshCache.getOrPut(m) { triangles(m) } }
            val rest = node.int("mesh")?.let { m -> val r = meshCache.getValue(m); r.morph?.atRest(node.arr("weights")?.floats(), r.triangles, r.normals) }
            val matrix = node.arr("matrix")?.let { Mat4.fromColumnMajor(it.floats().taille(16, "matrix")) }
            ModelNode(
                name = node.string("name"),
                parent = parents[i],
                children = node.arr("children")?.ints() ?: IntArray(0),
                translation = node.arr("translation")?.floats()?.taille(3, "translation")?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3.ZERO,
                rotation = node.arr("rotation")?.floats()?.taille(4, "rotation")?.let { Quat(it[0], it[1], it[2], it[3]).normalized() } ?: Quat.IDENTITY,
                scale = node.arr("scale")?.floats()?.taille(3, "scale")?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3.ONE,
                matrix = matrix,
                triangles = rest?.triangles ?: node.int("mesh")?.let { m -> meshCache.getValue(m).triangles } ?: FloatArray(0),
                uvs = node.int("mesh")?.let { m -> meshCache.getValue(m).uvs } ?: FloatArray(0),
                normals = rest?.normals ?: node.int("mesh")?.let { m -> meshCache.getValue(m).normals } ?: FloatArray(0),
                tints = node.int("mesh")?.let { m -> meshCache.getValue(m).tints } ?: FloatArray(0),
                tangents = node.int("mesh")?.let { m -> meshCache.getValue(m).tangents } ?: FloatArray(0),
                skin = node.int("skin")?.also { require(it in 0 until (doc.arr("skins")?.size ?: 0)) { "GLB: node $i uses a missing skin" } } ?: -1,
                skinWeights = node.int("mesh")?.let { m -> meshCache.getValue(m).skin } ?: FloatArray(0),
                properties = node.obj("extras")?.fields?.mapValues { plain(it.value) } ?: emptyMap(),
                light = node.obj("extensions")?.obj("KHR_lights_punctual")?.int("light")?.let { k ->
                    require(k in lights.indices) { "GLB: node $i uses a missing light $k" }; lights[k] },
                morphWeights = rest?.weights ?: FloatArray(0),
                morphPositions = rest?.positions ?: FloatArray(0),
                morphNormals = rest?.morphNormals ?: FloatArray(0),
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
            val morphs = ArrayList<WeightChannel>()
            val canaux = requireNotNull(anim.arr("channels")) { "GLB animation without channels" }.mapNotNull { ch ->
                val c = ch as Json.Obj
                val target = requireNotNull(c.obj("target")) { "Animation channel without a target" }
                val node = requireNotNull(target.int("node")) { "Animation channel without a node" }
                require(node in nodes.indices) { "Animation channel: no node $node" }
                val sampler = samplers.at(c.int("sampler"), "sampler")
                val interp = sampler.string("interpolation") ?: "LINEAR"
                require(interp == "LINEAR" || interp == "STEP" || interp == "CUBICSPLINE") { "Unsupported interpolation $interp (LINEAR, STEP or CUBICSPLINE)" }
                val times = floats(requireNotNull(sampler.int("input")), "SCALAR")
                val path = target.string("path")
                if (path == "weights") {
                    // Morph target weights: one per target and key (three times as many for a cubic spline).
                    val count = nodes[node].morphWeights.size
                    require(count > 0) { "GLB: animation of the morph weights of node $node, which has no morph targets" }
                    val out = floats(requireNotNull(sampler.int("output")), "SCALAR")
                    val (values, tangents) = splitKeys(out, times.size, count, interp == "CUBICSPLINE")
                    morphs.add(WeightChannel(node, count, times, values, interp == "STEP", tangents))
                    return@mapNotNull null
                }
                require(nodes[node].matrix == null) { "An animated node must use translation/rotation/scale, not matrix" }
                val chemin = when (path) {
                    "translation" -> ChannelTarget.TRANSLATION; "rotation" -> ChannelTarget.ROTATION; "scale" -> ChannelTarget.SCALE
                    else -> throw IllegalArgumentException("Animation de « $path » non prise en charge (seulement translation, rotation, scale, weights)")
                }
                val out = floats(requireNotNull(sampler.int("output")), if (chemin == ChannelTarget.ROTATION) "VEC4" else "VEC3")
                val (values, tangents) = splitKeys(out, times.size, chemin.components, interp == "CUBICSPLINE")
                Channel(node, chemin, times, values, interp == "STEP", tangents)
            }
            val nom = anim.string("name") ?: "animation$index"
            clips[nom] = Clip(nom, canaux, morphs)
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
        return Model(nodes, racines, clips, materialNames, textures, cutoffs, skins, normals, looks)
    }
}

// ---- KHR_texture_transform, cubic splines and morph targets (kept apart from the rest of the loader)

/**
 * The affine map of a texture's KHR_texture_transform (the extension's own formula, uv' = T · R · S · uv): u' = a·u +
 * b·v + c, v' = d·u + e·v + f as (a, b, c, d, e, f); null without the extension. Only TEXCOORD_0 is read.
 */
internal fun textureTransform(info: Json.Obj?): FloatArray? {
    val t = info?.obj("extensions")?.obj("KHR_texture_transform") ?: return null
    require((t.int("texCoord") ?: info.int("texCoord") ?: 0) == 0) { "GLB: only the first set of texture coordinates (TEXCOORD_0) is read" }
    val offset = t.arr("offset")?.floats() ?: floatArrayOf(0f, 0f)
    val scale = t.arr("scale")?.floats() ?: floatArrayOf(1f, 1f)
    require(offset.size >= 2 && scale.size >= 2) { "GLB: KHR_texture_transform needs two values for offset and scale" }
    val r = t.float("rotation") ?: 0f
    val c = kotlin.math.cos(r); val s = kotlin.math.sin(r)
    // R = (cos −sin / sin cos): its columns (cos, sin) and (−sin, cos), as the extension's mat3 is written.
    return floatArrayOf(c * scale[0], -s * scale[1], offset[0], s * scale[0], c * scale[1], offset[1])
}

/**
 * An animation sampler's output split into its key values and, for CUBICSPLINE (per key: in-tangent, value,
 * out-tangent), its tangents: per key its in-tangent then its out-tangent ([k] floats each).
 */
internal fun splitKeys(out: FloatArray, keys: Int, k: Int, cubic: Boolean): Pair<FloatArray, FloatArray?> {
    if (!cubic) return out to null
    require(out.size == 3 * keys * k) { "GLB: a cubic spline needs three values per key (${out.size} for $keys keys of $k)" }
    val values = FloatArray(keys * k); val tangents = FloatArray(2 * keys * k)
    for (key in 0 until keys) for (j in 0 until k) {
        val o = 3 * key * k
        tangents[2 * key * k + j] = out[o + j]                 // in
        values[key * k + j] = out[o + k + j]
        tangents[(2 * key + 1) * k + j] = out[o + 2 * k + j]   // out
    }
    return values to tangents
}

/**
 * The morph targets of one glTF mesh as its triangles are read: per target, how far each corner moves at weight 1
 * (9 floats per triangle), the same for the file's normals, and the mesh's own weights at rest.
 */
internal class MorphRead(val count: Int, val weights: FloatArray) {
    private val positions = Array(count) { ArrayList<Float>() }
    private val normals = Array(count) { ArrayList<Float>() }
    private var withNormals = true

    /** One primitive's targets: per target, its position and normal displacements per vertex (null: none). */
    class Targets(val positions: Array<List<FloatArray>?>, val normals: Array<List<FloatArray>?>)

    fun targetsOf(p: Json.Obj, vertices: Int, values: (Int, String) -> List<FloatArray>): Targets {
        val list = p.arr("targets")
        require((list?.size ?: 0) == count) { "GLB: every primitive of a mesh needs its $count morph targets (one has ${list?.size ?: 0})" }
        val pos = arrayOfNulls<List<FloatArray>>(count); val nrm = arrayOfNulls<List<FloatArray>>(count)
        for (t in 0 until count) {
            val target = list!!.obj(t)
            pos[t] = target.int("POSITION")?.let { values(it, "VEC3") }?.also { require(it.size >= vertices) { "GLB: a morph target has fewer positions than the mesh" } }
            nrm[t] = target.int("NORMAL")?.let { values(it, "VEC3") }?.also { require(it.size >= vertices) { "GLB: a morph target has fewer normals than the mesh" } }
            if (nrm[t] == null) withNormals = false
        }
        return Targets(pos, nrm)
    }

    fun addTriangle(targets: Targets?, a: Int, b: Int, c: Int) {
        for (t in 0 until count) for (v in intArrayOf(a, b, c)) for (j in 0..2) {
            positions[t].add(targets?.positions?.get(t)?.get(v)?.get(j) ?: 0f)
            normals[t].add(targets?.normals?.get(t)?.get(v)?.get(j) ?: 0f)
        }
    }

    /** What a node made of this mesh holds at rest. */
    class Rest(val triangles: FloatArray, val normals: FloatArray, val weights: FloatArray, val positions: FloatArray, val morphNormals: FloatArray)

    /**
     * The mesh's [triangles] (and the file's normals, [fileNormals]) moved to the weights at rest, the node's own
     * ([nodeWeights]) or the mesh's; the targets laid out per target, then per triangle.
     */
    fun atRest(nodeWeights: FloatArray?, triangles: FloatArray, fileNormals: FloatArray): Rest {
        val w = FloatArray(count) { nodeWeights?.getOrNull(it) ?: weights.getOrNull(it) ?: 0f }
        val tris = triangles.size / TRI
        require(positions.all { it.size == tris * 9 }) { "GLB: morph targets out of step with the triangles" }
        val pos = FloatArray(count * tris * 9) { i -> positions[i / (tris * 9)][i % (tris * 9)] }
        val nrm = if (withNormals) FloatArray(count * tris * 9) { i -> normals[i / (tris * 9)][i % (tris * 9)] } else FloatArray(0)
        val t = triangles.copyOf(); val n = fileNormals.copyOf()
        for (k in 0 until count) {
            if (w[k] == 0f) continue
            for (q in 0 until tris) for (j in 0 until 9) {
                t[q * TRI + j] += w[k] * pos[(k * tris + q) * 9 + j]
                if (n.isNotEmpty() && nrm.isNotEmpty()) n[q * 9 + j] += w[k] * nrm[(k * tris + q) * 9 + j]
            }
        }
        return Rest(t, n, w, pos, nrm)
    }

    companion object {
        /** The morph targets of [mesh], or null when it has none. */
        fun of(mesh: Json.Obj): MorphRead? {
            val first = mesh.arr("primitives")?.let { if (it.size > 0) it.obj(0) else null } ?: return null
            val count = first.arr("targets")?.size ?: 0
            if (count == 0) return null
            val w = mesh.arr("weights")?.floats() ?: FloatArray(count)
            return MorphRead(count, FloatArray(count) { w.getOrElse(it) { 0f } })
        }
    }
}
