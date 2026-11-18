package sillage

import sillage.io.Json
import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Chargeur glTF 2 binaire, entièrement JVM. Aucun moteur natif ni Assimp.
 * Lit la hiérarchie des nœuds, les couleurs de matériau ou de sommet et les animations TRS. */
object Glb {
    fun charger(chemin: String): Maillage = chargerModele(chemin).aplatir().also {
        require(it.sommets.isNotEmpty()) { "GLB sans triangles : $chemin" } }

    fun chargerModele(chemin: String): Modele = lireModele(octets(chemin))

    fun lire(bytes: ByteArray): Maillage = lireModele(bytes).aplatir().also { require(it.sommets.isNotEmpty()) { "GLB sans triangles" } }

    private fun octets(chemin: String): ByteArray =
        if (Files.exists(Path.of(chemin))) Files.readAllBytes(Path.of(chemin))
        else Glb::class.java.getResourceAsStream("/" + chemin)?.use { it.readAllBytes() } ?: error("GLB introuvable : " + chemin)

    fun lireModele(bytes: ByteArray): Modele {
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
        require(!doc.has("skins")) { "Les squelettes ne sont pas encore pris en charge : animer des objets séparés (nœuds)" }
        val required = doc.arr("extensionsRequired")
        require(required == null || required.size == 0) { "Extensions GLB requises non prises en charge : " + required }
        val buffers = doc.arr("buffers")
        val buffer: ByteBuffer = if (buffers == null || buffers.size == 0) ByteBuffer.allocate(0) else {
            require(buffers.size == 1 && !buffers.obj(0).has("uri")) { "Le GLB doit embarquer son buffer" }
            requireNotNull(bin) { "Chunk BIN absent" }
        }
        val accessors = doc.arr("accessors") ?: Json.Arr(emptyList())
        val views = doc.arr("bufferViews") ?: Json.Arr(emptyList())
        fun accessor(index: Int, expected: String): Pair<Json.Obj, Int> {
            require(index in 0 until accessors.size) { "Accessor GLB $index absent" }
            val a = accessors.obj(index)
            require(!a.has("sparse") && a.string("type") == expected) { "Accessor GLB non pris en charge (attendu $expected)" }
            val viewIndex = requireNotNull(a.int("bufferView")) { "Accessor GLB sans bufferView" }
            require(viewIndex in 0 until views.size) { "BufferView GLB $viewIndex absent" }
            val v = views.obj(viewIndex)
            require((v.int("buffer") ?: 0) == 0)
            val start = (v.int("byteOffset") ?: 0) + (a.int("byteOffset") ?: 0)
            return a to start
        }
        fun values(index: Int, type: String): List<FloatArray> {
            val (a, offset) = accessor(index, type)
            val components = when(type) { "SCALAR" -> 1; "VEC3" -> 3; "VEC4" -> 4; else -> error("Type invalide") }
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
        fun floats(index: Int, type: String): FloatArray {
            require(accessors.obj(index).int("componentType") == 5126) { "Les animations GLB doivent être en flottants" }
            val list = values(index, type)
            return FloatArray(list.sumOf { it.size }).also { out -> var k = 0; for (v in list) for (x in v) out[k++] = x }
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
        /** Triangles of one glTF mesh in node space: 9 position floats then r, g, b per triangle. */
        fun triangles(meshIndex: Int): FloatArray {
            val out = ArrayList<Float>()
            val mesh = requireNotNull(doc.arr("meshes")) { "Maillages GLB absents" }.obj(meshIndex)
            for (primitive in requireNotNull(mesh.arr("primitives"))) {
                val p = primitive as Json.Obj
                require((p.int("mode") ?: 4) == 4) { "Seuls les triangles sont pris en charge" }
                val attributes = requireNotNull(p.obj("attributes"))
                val positions = values(requireNotNull(attributes.int("POSITION")), "VEC3")
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
                for(i in ids.indices step 3) {
                    val a=ids[i]; val b=ids[i+1]; val c=ids[i+2]
                    require(a in positions.indices && b in positions.indices && c in positions.indices) { "Indice GLB hors limites" }
                    val vc = colors?.let { cs ->
                        Couleur(color.r * (cs[a][0].toDouble() + cs[b][0] + cs[c][0]).toFloat()/3,
                            color.g * (cs[a][1].toDouble() + cs[b][1] + cs[c][1]).toFloat()/3,
                            color.b * (cs[a][2].toDouble() + cs[b][2] + cs[c][2]).toFloat()/3) } ?: color
                    for (v in intArrayOf(a, b, c)) for (j in 0..2) out.add(positions[v][j])
                    out.add(vc.r); out.add(vc.g); out.add(vc.b)
                }
            }
            return out.toFloatArray()
        }

        val nodesJson = doc.arr("nodes") ?: Json.Arr(emptyList())
        val parents = IntArray(nodesJson.size) { -1 }
        for (i in 0 until nodesJson.size) nodesJson.obj(i).arr("children")?.forEach {
            val c = (it as Json.Num).toInt()
            require(c in 0 until nodesJson.size) { "Enfant GLB $c absent" }
            require(parents[c] < 0 && c != i) { "Cycle ou nœud partagé dans la hiérarchie GLB" }
            parents[c] = i
        }
        val meshCache = HashMap<Int, FloatArray>()
        val noeuds = List(nodesJson.size) { i ->
            val node = nodesJson.obj(i)
            val matrix = node.arr("matrix")?.let { Mat4.fromColumnMajor(it.floats()) }
            Noeud(
                nom = node.string("name"),
                parent = parents[i],
                enfants = node.arr("children")?.ints() ?: IntArray(0),
                translation = node.arr("translation")?.floats()?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3.ZERO,
                rotation = node.arr("rotation")?.floats()?.let { Quat(it[0], it[1], it[2], it[3]).normalized() } ?: Quat.IDENTITY,
                echelle = node.arr("scale")?.floats()?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3.ONE,
                matrice = matrix,
                triangles = node.int("mesh")?.let { m -> meshCache.getOrPut(m) { triangles(m) } } ?: FloatArray(0),
            )
        }
        val scenes = doc.arr("scenes")
        val racines = if (scenes == null || scenes.size == 0) IntArray(nodesJson.size) { it }.filter { parents[it] < 0 }.toIntArray()
            else scenes.obj(doc.int("scene") ?: 0).arr("nodes")?.ints() ?: IntArray(0)
        for (r in racines) require(r in noeuds.indices && parents[r] < 0) { "Racine de scène GLB invalide : $r" }

        val clips = LinkedHashMap<String, Clip>()
        doc.arr("animations")?.forEachIndexed { index, a ->
            val anim = a as Json.Obj
            val samplers = requireNotNull(anim.arr("samplers")) { "Animation GLB sans samplers" }
            val canaux = requireNotNull(anim.arr("channels")) { "Animation GLB sans canaux" }.map { ch ->
                val c = ch as Json.Obj
                val target = requireNotNull(c.obj("target")) { "Canal d'animation sans cible" }
                val node = requireNotNull(target.int("node")) { "Canal d'animation sans nœud" }
                require(node in noeuds.indices) { "Canal d'animation : nœud $node absent" }
                require(noeuds[node].matrice == null) { "Un nœud animé doit utiliser translation/rotation/scale, pas matrix" }
                val chemin = when (val path = target.string("path")) {
                    "translation" -> Chemin.TRANSLATION; "rotation" -> Chemin.ROTATION; "scale" -> Chemin.ECHELLE
                    else -> throw IllegalArgumentException("Animation de « $path » non prise en charge (seulement translation, rotation, scale)")
                }
                val sampler = samplers.obj(requireNotNull(c.int("sampler")))
                val palier = when (val interp = sampler.string("interpolation") ?: "LINEAR") {
                    "LINEAR" -> false; "STEP" -> true
                    else -> throw IllegalArgumentException("Interpolation $interp non prise en charge (LINEAR ou STEP)")
                }
                Canal(node, chemin, floats(requireNotNull(sampler.int("input")), "SCALAR"),
                    floats(requireNotNull(sampler.int("output")), if (chemin == Chemin.ROTATION) "VEC4" else "VEC3"), palier)
            }
            val nom = anim.string("name") ?: "animation$index"
            clips[nom] = Clip(nom, canaux)
        }
        return Modele(noeuds, racines, clips)
    }
}
