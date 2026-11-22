package sillage.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal actual fun platformFile(path: String): ByteArray? =
    try { Path.of(path).let { if (Files.isRegularFile(it)) Files.readAllBytes(it) else null } } catch (e: java.nio.file.InvalidPathException) { null }

internal actual fun platformFileRange(path: String, at: Long, n: Int): ByteArray? =
    FileChannel.open(Path.of(path), StandardOpenOption.READ).use { ch ->
        if (at < 0 || n <= 0 || at + n > ch.size()) return null
        val buf = ByteBuffer.allocate(n); var p = at
        while (buf.hasRemaining()) { val k = ch.read(buf, p); if (k <= 0) return null; p += k }
        buf.array()
    }

internal actual fun platformWrite(path: String, bytes: ByteArray) {
    val p = Path.of(path); p.toAbsolutePath().parent?.let { Files.createDirectories(it) }; Files.write(p, bytes)
}

internal actual fun platformList(dir: String): List<String> {
    val d = Path.of(dir)
    if (!Files.isDirectory(d)) return emptyList()
    return try { Files.walk(d, 6).use { s -> s.filter { Files.isRegularFile(it) }.map { it.toString() }.toList() } } catch (e: Exception) { emptyList() }
}

internal actual fun platformResource(path: String): ByteArray? = Storage::class.java.getResourceAsStream("/$path")?.use { it.readAllBytes() }
internal actual fun platformSetting(name: String): String? = System.getProperty(name)
internal actual fun platformEnvironment(name: String): String? = System.getenv(name)
internal actual fun platformProcessors(): Int = Runtime.getRuntime().availableProcessors()

@Suppress("NOTHING_TO_INLINE")
actual inline fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)

/** A sound, a PNG, written at a [Path] (the portable functions take the path as text). */
fun sillage.audio.Sound.save(path: Path) = save(path.toString())
fun PngWriter.write(path: Path, width: Int, height: Int, rgb: ByteArray) = write(path.toString(), width, height, rgb)
