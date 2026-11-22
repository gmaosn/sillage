package sillage.io

// A web page: no files, one thread; the resources are loaded before the game starts (Storage.preloaded).
internal actual fun platformFile(path: String): ByteArray? = null
internal actual fun platformFileRange(path: String, at: Long, n: Int): ByteArray? = null
internal actual fun platformWrite(path: String, bytes: ByteArray) { WebFiles.saved?.invoke(path, bytes) }
internal actual fun platformList(dir: String): List<String> = emptyList()
internal actual fun platformResource(path: String): ByteArray? = null
internal actual fun platformSetting(name: String): String? = WebFiles.settings[name]
internal actual fun platformEnvironment(name: String): String? = null
internal actual fun platformProcessors(): Int = 1

/** Set by the page's backend: its launch settings (from the address), and what to do with a file the game writes (offer it as a download). */
object WebFiles {
    val settings: MutableMap<String, String> = HashMap()
    var saved: ((String, ByteArray) -> Unit)? = null
}

actual inline fun <T> locked(lock: Any, block: () -> T): T = block()
