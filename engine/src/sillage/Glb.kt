package sillage

import com.google.gson.*
import org.joml.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Chargeur glTF 2 binaire statique, entièrement JVM. Aucun moteur natif ni Assimp. */
object Glb {
    fun charger(chemin: String): Maillage {
        val bytes = if (Files.exists(Path.of(chemin))) Files.readAllBytes(Path.of(chemin))
            else Glb::class.java.getResourceAsStream("/" + chemin)?.use { it.readAllBytes() }
                ?: error("GLB introuvable : " + chemin)
        return lire(bytes)
    }

    fun lire(bytes: ByteArray): Maillage {
        require(bytes.size >= 20) { "GLB tronqué" }
        val file = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(file.int == 0x46546c67 && file.int == 2) { "Un GLB glTF 2.0 est requis" }
        require(file.int == bytes.size) { "Taille GLB invalide" }
        var json: JsonObject? = null
        var bin: ByteBuffer? = null
        while (file.remaining() >= 8) {
            val size = file.int; val type = file.int
            require(size >= 0 && size <= file.remaining()) { "Chunk GLB invalide" }
            val chunk = ByteArray(size); file.get(chunk)
            when (type) {
                0x4e4f534a -> json = JsonParser.parseString(chunk.toString(Charsets.UTF_8).trimEnd('\u0000', ' ')).asJsonObject
                0x004e4942 -> bin = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            }
        }
        val doc = requireNotNull(json) { "Chunk JSON absent" }
        val buffer = requireNotNull(bin) { "Chunk BIN absent" }
        require(!doc.has("skins")) { "Les squelettes ne sont pas encore pris en charge : exporter un modèle statique" }
        require(!doc.has("animations")) { "Les animations GLB ne sont pas encore prises en charge" }
        val required = doc.getAsJsonArray("extensionsRequired") ?: JsonArray()
        require(required.size() == 0) { "Extensions GLB requises non prises en charge : " + required }
        require(doc.getAsJsonArray("buffers").size() == 1 && !doc.getAsJsonArray("buffers")[0].asJsonObject.has("uri")) {
            "Le GLB doit embarquer son buffer" }
        val accessors = doc.getAsJsonArray("accessors")
        val views = doc.getAsJsonArray("bufferViews")
        fun accessor(index: Int, expected: String): Pair<JsonObject, Int> {
            val a = accessors[index].asJsonObject
            require(!a.has("sparse") && a.get("type").asString == expected) { "Accessor GLB non pris en charge" }
            val v = views[a.get("bufferView").asInt].asJsonObject
            require((v.get("buffer")?.asInt ?: 0) == 0)
            val start = (v.get("byteOffset")?.asInt ?: 0) + (a.get("byteOffset")?.asInt ?: 0)
            return a to start
        }
        fun values(index: Int, type: String): List<FloatArray> {
            val (a, offset) = accessor(index, type)
            val components = when(type) { "VEC3" -> 3; "VEC4" -> 4; else -> error("Type invalide") }
            val componentType = a.get("componentType").asInt
            val width = when(componentType) {5126 -> 4; 5121 -> 1; 5123 -> 2; else -> error("Type de composante non pris en charge")}
            val v = views[a.get("bufferView").asInt].asJsonObject
            val stride = v.get("byteStride")?.asInt ?: (width * components)
            val normalized = a.get("normalized")?.asBoolean ?: false
            return List(a.get("count").asInt) { i -> FloatArray(components) { c ->
                val o = offset + i * stride + c * width
                require(o >= 0 && o + width <= buffer.limit()) { "Accessor hors du buffer" }
                when(componentType) {
                    5126 -> buffer.getFloat(o)
                    5121 -> (buffer.get(o).toInt() and 255).toFloat() / if(normalized) 255f else 1f
                    else -> (buffer.getShort(o).toInt() and 65535).toFloat() / if(normalized) 65535f else 1f
                }
            } }
        }
        fun indices(index: Int): IntArray {
            val (a, offset) = accessor(index, "SCALAR")
            val type = a.get("componentType").asInt
            val width = when(type) {5121 -> 1; 5123 -> 2; 5125 -> 4; else -> error("Indices GLB invalides")}
            return IntArray(a.get("count").asInt) { i ->
                val o = offset + i * width
                require(o >= 0 && o + width <= buffer.limit()) { "Indices hors du buffer" }
                when(type) {5121 -> buffer.get(o).toInt() and 255; 5123 -> buffer.getShort(o).toInt() and 65535; else -> buffer.getInt(o)}
            }
        }
        val out = Geometrie()
        val nodes = doc.getAsJsonArray("nodes")
        val active = HashSet<Int>()
        fun visit(index: Int, parent: Matrix4f) {
            require(active.add(index)) { "Cycle dans la hiérarchie GLB" }
            val node = nodes[index].asJsonObject
            val local = Matrix4f()
            fun array(name: String) = node.getAsJsonArray(name)?.map { it.asFloat }?.toFloatArray()
            if (node.has("matrix")) local.set(array("matrix")!!) else {
                array("translation")?.let { local.translate(it[0],it[1],it[2]) }
                array("rotation")?.let { local.rotate(Quaternionf(it[0],it[1],it[2],it[3])) }
                array("scale")?.let { local.scale(it[0],it[1],it[2]) }
            }
            val transform = Matrix4f(parent).mul(local)
            if(node.has("mesh")) {
                val mesh = doc.getAsJsonArray("meshes")[node.get("mesh").asInt].asJsonObject
                for (primitive in mesh.getAsJsonArray("primitives")) {
                    val p = primitive.asJsonObject
                    require((p.get("mode")?.asInt ?: 4) == 4) { "Seuls les triangles sont pris en charge" }
                    val attributes = p.getAsJsonObject("attributes")
                    val positions = values(attributes.get("POSITION").asInt, "VEC3").map {
                        transform.transformPosition(Vector3f(it[0],it[1],it[2])) }
                    val colors = attributes.get("COLOR_0")?.asInt?.let {
                        values(it, accessors[it].asJsonObject.get("type").asString) }
                    var color = Couleur(0.8f,0.8f,0.8f)
                    if(p.has("material")) {
                        val material = doc.getAsJsonArray("materials")[p.get("material").asInt].asJsonObject
                        val pbr = material.getAsJsonObject("pbrMetallicRoughness")
                        require(pbr?.has("baseColorTexture") != true) {
                            "Textures non prises en charge : utiliser des couleurs de matériaux Blender" }
                        val rgba = pbr?.getAsJsonArray("baseColorFactor")
                        if(rgba != null) color = Couleur(rgba[0].asFloat,rgba[1].asFloat,rgba[2].asFloat)
                    }
                    val ids = p.get("indices")?.asInt?.let { indices(it) } ?: IntArray(positions.size) { it }
                    require(ids.size % 3 == 0)
                    for(i in ids.indices step 3) {
                        val a=ids[i]; val b=ids[i+1]; val c=ids[i+2]
                        require(listOf(a,b,c).all { it in positions.indices }) { "Indice GLB hors limites" }
                        val vc = colors?.let { listOf(a,b,c).map { j -> it[j] }.let { cs ->
                            Couleur(color.r * cs.sumOf { it[0].toDouble() }.toFloat()/3,
                                color.g * cs.sumOf { it[1].toDouble() }.toFloat()/3,
                                color.b * cs.sumOf { it[2].toDouble() }.toFloat()/3) } } ?: color
                        // Recalcul des normales par face pour le rendu en aplats, y compris après scale négatif.
                        if(transform.determinant3x3() < 0) out.triangle(positions[a],positions[c],positions[b],vc)
                        else out.triangle(positions[a],positions[b],positions[c],vc)
                    }
                }
            }
            node.getAsJsonArray("children")?.forEach { visit(it.asInt,transform) }
            active.remove(index)
        }
        val sceneIndex = doc.get("scene")?.asInt ?: 0
        doc.getAsJsonArray("scenes")[sceneIndex].asJsonObject.getAsJsonArray("nodes").forEach { visit(it.asInt,Matrix4f()) }
        return out.construire().also { require(it.sommets.isNotEmpty()) { "GLB sans triangles" } }
    }
}
