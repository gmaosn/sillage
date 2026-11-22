package sillage.io

/**
 * What the engine asks of the system it runs on, besides drawing and sound: files, the program's own
 * resources, settings given at launch, and locks. Each platform answers in its own way (a desktop reads
 * the disk and the classpath; a web page has only what was loaded beforehand, see [preloaded]).
 */
object Storage {
    /**
     * Resources handed over in advance, by their path ("models/island.glb"): looked in before the platform's
     * own resources. A web page fills it before the game starts, since it cannot read a file on the spot.
     */
    val preloaded: MutableMap<String, ByteArray> = HashMap()

    /** The whole file at [path], or null when there is no such file (or no files on this platform). */
    fun file(path: String): ByteArray? = platformFile(path)

    /** [n] bytes of the file at [path] from [at], or null when it has not that many (to look into big files without reading them whole). */
    fun fileRange(path: String, at: Long, n: Int): ByteArray? = platformFileRange(path, at, n)

    /** Writes [bytes] at [path], making the folders on the way. */
    fun write(path: String, bytes: ByteArray) = platformWrite(path, bytes)

    /** The files under [dir], folders within folders included, or nothing when it cannot be listed. */
    fun list(dir: String): List<String> = platformList(dir)

    /** The resource packed with the program at [path], or null. */
    fun resource(path: String): ByteArray? = preloaded[path.removePrefix("/")] ?: platformResource(path.removePrefix("/"))

    /** A setting given when the program was launched (`-Dname=value` on a JVM, the page's address on the web), or null. */
    fun setting(name: String): String? = platformSetting(name)

    /** An environment variable, or null (always null where there is no such thing). */
    fun environment(name: String): String? = platformEnvironment(name)

    /** How many threads the physics may use besides the caller's (0: this platform has one thread). */
    val spareProcessors: Int get() = platformProcessors() - 1
}

internal expect fun platformFile(path: String): ByteArray?
internal expect fun platformFileRange(path: String, at: Long, n: Int): ByteArray?
internal expect fun platformWrite(path: String, bytes: ByteArray)
internal expect fun platformList(dir: String): List<String>
internal expect fun platformResource(path: String): ByteArray?
internal expect fun platformSetting(name: String): String?
internal expect fun platformEnvironment(name: String): String?
internal expect fun platformProcessors(): Int

/** Runs [block] while holding [lock]: one thread at a time (where there is only one thread, it just runs). */
expect inline fun <T> locked(lock: Any, block: () -> T): T

/** Bytes written one after the other into an array that grows. */
class ByteSink(capacity: Int = 256) {
    private var data = ByteArray(maxOf(capacity, 16)); private var count = 0
    fun write(b: Int) { grow(1); data[count++] = b.toByte() }
    fun write(b: ByteArray) = write(b, 0, b.size)
    fun write(b: ByteArray, from: Int, length: Int) { grow(length); b.copyInto(data, count, from, from + length); count += length }
    fun size(): Int = count
    fun reset() { count = 0 }
    fun toByteArray(): ByteArray = data.copyOf(count)
    private fun grow(n: Int) { if (count + n > data.size) data = data.copyOf(maxOf(data.size * 2, count + n)) }
}

/** The [n] bytes of [b] from [at] as text, one character per byte (ISO 8859-1: tags and names in file formats). */
fun latin1(b: ByteArray, at: Int, n: Int): String = CharArray(n) { (b[at + it].toInt() and 255).toChar() }.concatToString()

/** Little-endian numbers read from and written into a byte array (WAV, GLB…). */
object Le {
    fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    fun i16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
    fun i32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    fun f32(b: ByteArray, o: Int): Float = Float.fromBits(i32(b, o))
    fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    fun put32(b: ByteArray, o: Int, v: Int) { put16(b, o, v); put16(b, o + 2, v ushr 16) }
    fun putF32(b: ByteArray, o: Int, v: Float) = put32(b, o, v.toRawBits())
}
