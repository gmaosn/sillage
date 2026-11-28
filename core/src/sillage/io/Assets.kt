package sillage.io

import kotlin.concurrent.Volatile

/**
 * Where models, images and sounds are found: a file at that path (absolute, or from the working
 * directory), then in each of [roots] (the game's own resources folder while `./sillage dev` runs, so
 * that a model saved from Blender is read again at once), then in the program's resources (on the web:
 * what the page loaded before the game started, see [Storage.preloaded]).
 */
object Assets {
    /** Folders looked in after the path itself and before the program's resources. */
    @Volatile var roots: List<String> = emptyList()

    /** The bytes of [path], or null when it is nowhere. */
    fun bytesOrNull(path: String): ByteArray? {
        Storage.file(path)?.let { return it }
        if (!path.startsWith("/")) for (root in roots) Storage.file(root.trimEnd('/') + "/" + path)?.let { return it }
        return Storage.resource(path)
    }

    /** The bytes of [path]; [what] names the kind of file in the error ("GLB", "Sound"…). */
    fun bytes(path: String, what: String): ByteArray = bytesOrNull(path) ?: throw AssetMissing(missing(path, what))

    /** Every place looked in, said plainly, and a file of nearly that name when there is one. */
    private fun missing(path: String, what: String): String {
        val kind = when (what) { "GLB" -> "model file"; "Sound" -> "sound file"; "Font" -> "font file"; "Texture" -> "texture image"; else -> what.lowercase() + " file" }
        val here = Storage.setting("user.dir")?.trimEnd('/')
        val places = ArrayList<String>()
        places.add(if (path.startsWith("/") || here == null) path else "$here/$path")
        if (!path.startsWith("/")) for (root in roots) places.add(root.trimEnd('/') + "/" + path)
        val text = StringBuilder("This $kind is missing: $path\n")
        text.append("  Looked for it here:\n")
        for (p in places) text.append("    ").append(p).append('\n')
        if (!path.startsWith("/")) text.append("    and as ").append(path.removePrefix("/")).append(" in the game's resources (the resources/ folder of the game: mygame/resources/")
            .append(path.removePrefix("/")).append(")\n")
        nearly(places)?.let { text.append("  A file with nearly that name: ").append(it).append(" (capital letters count)\n") }
        text.append("  Check the name and the folder.")
        return text.toString()
    }

    /** A file in the folder of one of [places] named like it but for capitals, or with another extension. */
    private fun nearly(places: List<String>): String? {
        for (p in places) {
            val dir = p.substringBeforeLast('/', "")
            if (dir.isEmpty()) continue
            val name = p.substringAfterLast('/')
            val stem = name.substringBeforeLast('.')
            // The folder's files, those nearest first (the list goes into the folders inside it).
            val files = (try { Storage.list(dir) } catch (e: Exception) { emptyList() }).sortedBy { it.count { c -> c == '/' } }
            files.firstOrNull { it.substringAfterLast('/').equals(name, ignoreCase = true) }?.let { return it }
            files.firstOrNull { it.substringAfterLast('/').substringBeforeLast('.').equals(stem, ignoreCase = true) }?.let { return it }
        }
        return null
    }
}

/** A model, sound, image or font that is nowhere to be found: the message says where it was looked for. */
internal class AssetMissing(message: String) : IllegalStateException(message)
