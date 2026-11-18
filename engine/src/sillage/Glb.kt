package sillage

import sillage.io.Json
import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3
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
        var json: Json.Obj? = null
        var bin: ByteBuffer? = null
        while (file.remaining() >= 8) {
            val size = file.int; val type = file.int
            require(size >= 0 && size <= file.remaining()) { "Chunk GLB invalide" }
            val chunk = ByteArray(size); file.get(chunk)
            when (type) {
                0x4e4f534a -> json = Json.parse(chunk.toString(Charsets.UTF_8).trimEnd('\u0000', ' ')) as? Json.Obj
                    ?: throw IllegalArgumentException("Le JSON d'un GLB doit être un objet")
                0x004e4942 -> bin = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            }
        }
        val doc = requireNotNull(json) { "Chunk JSON absent" }
        val buffer = requireNotNull(bin) { "Chunk BIN absent" }
        require(!doc.has("skins")) { "Les squelettes ne sont pas encore pris en charge : exporter un modèle statique" }
        require(!doc.has("animations")) { "Les animations GLB ne sont pas encore prises en charge" }
        val required = doc.arr("extensionsRequired")
        require(required == null || required.size == 0) { "Extensions GLB requises non prises en charge : " + required }
        val buffers = requireNotNull(doc.arr("buffers")) { "Le GLB doit embarquer son buffer" }
        require(buffers.size == 1 && !buffers.obj(0).has("uri")) { "Le GLB doit embarquer son buffer" }
        val accessors = requireNotNull(doc.arr("accessors")) { "Accessors GLB absents" }
        val views = requireNotNull(doc.arr("bufferViews")) { "BufferViews GLB absents" }
        fun accessor(index: Int, expected: String): Pair<Json.Obj, Int> {
            val a = accessors.obj(index)
            require(!a.has("sparse") && a.string("type") == expected) { "Accessor GLB non pris en charge" }
            val v = views.obj(requireNotNull(a.int("bufferView")) { "Accessor GLB sans bufferView" })
            require((v.int("buffer") ?: 0) == 0)
            val start = (v.int("byteOffset") ?: 0) + (a.int("byteOffset") ?: 0)
            return a to start
        }
        fun values(index: Int, type: String): List<FloatArray> {
            val (a, offset) = accessor(index, type)
            val components = when(type) { "VEC3" -> 3; "VEC4" -> 4; else -> error("Type invalide") }
            val componentType = a.int("componentType")
            val width = when(componentType) {5126 -> 4; 5121 -> 1; 5123 -> 2; else -> error("Type de composante non pris en charge")}
            val v = views.obj(a.int("bufferView")!!)
            val stride = v.int("byteStride") ?: (width * components)
            val normalized = a.bool("normalized") ?: false
            return List(requireNotNull(a.int("count"))) { i -> FloatArray(components) { c ->
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
            val type = a.int("componentType")
            val width = when(type) {5121 -> 1; 5123 -> 2; 5125 -> 4; else -> error("Indices GLB invalides")}
            return IntArray(requireNotNull(a.int("count"))) { i ->
                val o = offset + i * width
                require(o >= 0 && o + width <= buffer.limit()) { "Indices hors du buffer" }
                when(type) {5121 -> buffer.get(o).toInt() and 255; 5123 -> buffer.getShort(o).toInt() and 65535; else -> buffer.getInt(o)}
            }
        }
        val out = Geometrie()
        val nodes = requireNotNull(doc.arr("nodes")) { "Nœuds GLB absents" }
        val active = HashSet<Int>()
        fun visit(index: Int, parent: Mat4) {
            require(active.add(index)) { "Cycle dans la hiérarchie GLB" }
            val node = nodes.obj(index)
            val local = node.arr("matrix")?.let { Mat4.fromColumnMajor(it.floats()) } ?: run {
                val t = node.arr("translation")?.floats()?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3.ZERO
                val r = node.arr("rotation")?.floats()?.let { Quat(it[0], it[1], it[2], it[3]).normalized() } ?: Quat.IDENTITY
                val s = node.arr("scale")?.floats()?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3.ONE
                Mat4.trs(t, r, s)
            }
            val transform = parent * local
            node.int("mesh")?.let { meshIndex ->
                val mesh = requireNotNull(doc.arr("meshes")) { "Maillages GLB absents" }.obj(meshIndex)
                for (primitive in requireNotNull(mesh.arr("primitives"))) {
                    val p = primitive as Json.Obj
                    require((p.int("mode") ?: 4) == 4) { "Seuls les triangles sont pris en charge" }
                    val attributes = requireNotNull(p.obj("attributes"))
                    val positions = values(requireNotNull(attributes.int("POSITION")), "VEC3").map {
                        transform.transformPoint(Vec3(it[0],it[1],it[2])) }
                    val colors = attributes.int("COLOR_0")?.let { values(it, accessors.obj(it).string("type")!!) }
                    var color = Couleur(0.8f,0.8f,0.8f)
                    p.int("material")?.let { materialIndex ->
                        val material = requireNotNull(doc.arr("materials")).obj(materialIndex)
                        val pbr = material.obj("pbrMetallicRoughness")
                        require(pbr?.has("baseColorTexture") != true) {
                            "Textures non prises en charge : utiliser des couleurs de matériaux Blender" }
                        pbr?.arr("baseColorFactor")?.floats()?.let { color = Couleur(it[0],it[1],it[2]) }
                    }
                    val ids = p.int("indices")?.let { indices(it) } ?: IntArray(positions.size) { it }
                    require(ids.size % 3 == 0)
                    val mirrored = transform.determinant3x3() < 0
                    for(i in ids.indices step 3) {
                        val a=ids[i]; val b=ids[i+1]; val c=ids[i+2]
                        require(a in positions.indices && b in positions.indices && c in positions.indices) { "Indice GLB hors limites" }
                        val vc = colors?.let { cs ->
                            Couleur(color.r * (cs[a][0].toDouble() + cs[b][0] + cs[c][0]).toFloat()/3,
                                color.g * (cs[a][1].toDouble() + cs[b][1] + cs[c][1]).toFloat()/3,
                                color.b * (cs[a][2].toDouble() + cs[b][2] + cs[c][2]).toFloat()/3) } ?: color
                        // Recalcul des normales par face pour le rendu en aplats, y compris après scale négatif.
                        if(mirrored) out.triangle(positions[a],positions[c],positions[b],vc)
                        else out.triangle(positions[a],positions[b],positions[c],vc)
                    }
                }
            }
            node.arr("children")?.forEach { visit((it as Json.Num).toInt(),transform) }
            active.remove(index)
        }
        val scenes = requireNotNull(doc.arr("scenes")) { "Scènes GLB absentes" }
        scenes.obj(doc.int("scene") ?: 0).arr("nodes")?.forEach { visit((it as Json.Num).toInt(),Mat4.IDENTITY) }
        return out.construire().also { require(it.sommets.isNotEmpty()) { "GLB sans triangles" } }
    }
}
