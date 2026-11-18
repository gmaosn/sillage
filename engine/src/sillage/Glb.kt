package sillage

import sillage.io.Json
import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * glTF 2.0 binary (.glb) loader, in Kotlin. It reads the node hierarchy, the material or vertex
 * colours, the material names and the node animations (translation, rotation, scale; linear or step).
 * Textures, skins and cubic-spline curves are refused with a message that says so. A path is looked
 * up on disk first, then in the classpath resources.
 */
object Glb {
    /** The model in its rest pose as one mesh (static scenery). */
    fun load(path: String): Mesh = loadModel(path).flatten().also {
        require(it.triangleCount > 0) { "GLB without triangles: $path" } }

    /** The model with its hierarchy and animations. */
    fun loadModel(path: String): Model = readModel(bytes(path))

    /** [load] from the bytes of a file. */
    fun read(bytes: ByteArray): Mesh = readModel(bytes).flatten().also { require(it.triangleCount > 0) { "GLB without triangles" } }

    private fun bytes(path: String): ByteArray =
        if (Files.exists(Path.of(path))) Files.readAllBytes(Path.of(path))
        else Glb::class.java.getResourceAsStream("/" + path)?.use { it.readAllBytes() } ?: error("GLB not found (file or resource): " + path)

    /** [loadModel] from the bytes of a file. */
    fun readModel(bytes: ByteArray): Model {
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
        require(!doc.has("skins")) { "Skins (skeletons) are not supported yet: animate separate objects (nodes) instead" }
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
            val components = when(type) { "SCALAR" -> 1; "VEC3" -> 3; "VEC4" -> 4; else -> error("Invalid accessor type " + type) }
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
        /** Triangles of one glTF mesh in node space: 9 position floats, r, g, b, then the material index. */
        fun triangles(meshIndex: Int): FloatArray {
            val out = ArrayList<Float>()
            val mesh = doc.arr("meshes").at(meshIndex, "maillage")
            for (primitive in requireNotNull(mesh.arr("primitives"))) {
                val p = primitive as Json.Obj
                require((p.int("mode") ?: 4) == 4) { "Only triangles are supported" }
                val attributes = requireNotNull(p.obj("attributes"))
                val positions = values(requireNotNull(attributes.int("POSITION")), "VEC3")
                val colors = attributes.int("COLOR_0")?.let { values(it, requireNotNull(accessors.at(it, "accessor").string("type")) { "GLB: colours without a type" }) }
                ?.onEach { require(it.size >= 3) { "GLB: incomplete vertex colours" } }
                var color = Color(0.8f,0.8f,0.8f)
                p.int("material")?.let { materialIndex ->
                    val material = doc.arr("materials").at(materialIndex, "material")
                    val pbr = material.obj("pbrMetallicRoughness")
                    require(pbr?.has("baseColorTexture") != true) {
                        "Textures are not supported: use material colours (Base Color in Blender)" }
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
                }
            }
            return out.toFloatArray()
        }

        val nodesJson = doc.arr("nodes") ?: Json.Arr(emptyList())
        val parents = IntArray(nodesJson.size) { -1 }
        for (i in 0 until nodesJson.size) nodesJson.obj(i).arr("children")?.forEach {
            val c = (it as Json.Num).toInt()
            require(c in 0 until nodesJson.size) { "Missing GLB child $c" }
            require(parents[c] < 0 && c != i) { "Cycle or shared node in the GLB hierarchy" }
            parents[c] = i
        }
        val meshCache = HashMap<Int, FloatArray>()
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
                triangles = node.int("mesh")?.let { m -> meshCache.getOrPut(m) { triangles(m) } } ?: FloatArray(0),
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
        return Model(nodes, racines, clips, materialNames)
    }
}
