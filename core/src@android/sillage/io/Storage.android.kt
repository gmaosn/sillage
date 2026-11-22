package sillage.io

import java.io.File
import java.io.RandomAccessFile

internal actual fun platformFile(path: String): ByteArray? = File(path).let { if (it.isFile) it.readBytes() else null }

internal actual fun platformFileRange(path: String, at: Long, n: Int): ByteArray? =
    RandomAccessFile(path, "r").use { f ->
        if (at < 0 || n <= 0 || at + n > f.length()) return null
        val out = ByteArray(n); f.seek(at); f.readFully(out); out
    }

internal actual fun platformWrite(path: String, bytes: ByteArray) { val f = File(path).absoluteFile; f.parentFile?.mkdirs(); f.writeBytes(bytes) }

internal actual fun platformList(dir: String): List<String> =
    File(dir).let { d -> if (d.isDirectory) d.walkTopDown().maxDepth(6).filter { it.isFile }.map { it.path }.toList() else emptyList() }

/** The program's resources: packed in the application as Java resources, or handed over by the activity ([Storage.preloaded], [AndroidAssets]). */
internal actual fun platformResource(path: String): ByteArray? =
    AndroidAssets.open?.invoke(path) ?: Storage::class.java.getResourceAsStream("/$path")?.use { it.readBytes() }

/** Set by the activity: reads a file of the application's `assets` folder, or null when it has no such file. */
object AndroidAssets { @Volatile var open: ((String) -> ByteArray?)? = null }

internal actual fun platformSetting(name: String): String? = System.getProperty(name)
internal actual fun platformEnvironment(name: String): String? = System.getenv(name)
internal actual fun platformProcessors(): Int = Runtime.getRuntime().availableProcessors()

@Suppress("NOTHING_TO_INLINE")
actual inline fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)
