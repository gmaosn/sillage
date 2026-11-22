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
    fun bytes(path: String, what: String): ByteArray =
        bytesOrNull(path) ?: error("$what not found: $path (looked for the file, " +
            (if (roots.isEmpty()) "" else "in ${roots.joinToString()}, ") + "and in the resources)")
}
