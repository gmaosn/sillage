package sillage.io

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where models, images and sounds are found: a file at that path (absolute, or from the working
 * directory), then in each of [roots] (the game's own resources folder while `./sillage dev` runs, so
 * that a model saved from Blender is read again at once), then in the classpath resources.
 */
object Assets {
    /** Folders looked in after the path itself and before the classpath resources. */
    @Volatile var roots: List<Path> = emptyList()

    /** The bytes of [path], or null when it is nowhere. */
    fun bytesOrNull(path: String): ByteArray? {
        val direct = Path.of(path)
        if (Files.isRegularFile(direct)) return Files.readAllBytes(direct)
        if (!direct.isAbsolute) for (root in roots) {
            val p = root.resolve(path)
            if (Files.isRegularFile(p)) return Files.readAllBytes(p)
        }
        return Assets::class.java.getResourceAsStream("/" + path.removePrefix("/"))?.use { it.readAllBytes() }
    }

    /** The bytes of [path]; [what] names the kind of file in the error ("GLB", "Sound"…). */
    fun bytes(path: String, what: String): ByteArray =
        bytesOrNull(path) ?: error("$what not found: $path (looked for the file, " +
            (if (roots.isEmpty()) "" else "in ${roots.joinToString()}, ") + "and in the resources)")
}
