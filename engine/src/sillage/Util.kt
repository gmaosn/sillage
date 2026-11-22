package sillage

import sillage.io.Le

// Small things the JVM's library gave and Kotlin's shared one does not.

/** Little-endian numbers at given places of a byte array (a GLB's buffer). */
internal class LeBuffer(val bytes: ByteArray) {
    fun limit(): Int = bytes.size
    fun get(o: Int): Byte = bytes[o]
    fun getShort(o: Int): Short = Le.i16(bytes, o).toShort()
    fun getInt(o: Int): Int = Le.i32(bytes, o)
    fun getFloat(o: Int): Float = Le.f32(bytes, o)
}

/** Base64 (RFC 4648, padding optional, line breaks ignored). */
internal fun base64(text: String): ByteArray {
    val out = sillage.io.ByteSink(text.length * 3 / 4 + 3)
    var acc = 0; var bits = 0
    for (c in text) {
        val v = when (c) {
            in 'A'..'Z' -> c - 'A'; in 'a'..'z' -> c - 'a' + 26; in '0'..'9' -> c - '0' + 52
            '+', '-' -> 62; '/', '_' -> 63; '=', '\n', '\r', ' ' -> continue
            else -> throw IllegalArgumentException("Not base64: '$c'")
        }
        acc = (acc shl 6) or v; bits += 6
        if (bits >= 8) { bits -= 8; out.write((acc shr bits) and 255) }
    }
    return out.toByteArray()
}

/** A URI's %XX escapes turned back into text (UTF-8); '+' is a space, as in a form. */
internal fun percentDecode(text: String): String {
    if ('%' !in text && '+' !in text) return text
    val out = sillage.io.ByteSink(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 < text.length + 0 && i + 2 <= text.length - 1 + 0) { out.write(text.substring(i + 1, i + 3).toInt(16)); i += 3 }
        else if (c == '+') { out.write(' '.code); i++ }
        else { out.write(c.toString().encodeToByteArray()); i++ }
    }
    return out.toByteArray().decodeToString()
}

/** Lower case without accents (é → e, ç → c, œ → oe): names of materials in English or French read alike. */
internal fun plainLower(text: String): String {
    val out = StringBuilder(text.length)
    for (c in text.lowercase()) when (c) {
        'à', 'á', 'â', 'ã', 'ä', 'å' -> out.append('a'); 'ç' -> out.append('c')
        'è', 'é', 'ê', 'ë' -> out.append('e'); 'ì', 'í', 'î', 'ï' -> out.append('i'); 'ñ' -> out.append('n')
        'ò', 'ó', 'ô', 'õ', 'ö' -> out.append('o'); 'ù', 'ú', 'û', 'ü' -> out.append('u'); 'ý', 'ÿ' -> out.append('y')
        in '̀'..'ͯ' -> {}                 // combining marks of text already taken apart
        else -> out.append(c)
    }
    return out.toString()
}

/**
 * The smallest first: a binary heap that sifts exactly as `java.util.PriorityQueue` does, so that ties
 * come out in the same order on every platform (a simplified mesh is the same everywhere).
 */
internal class MinHeap<T>(private val compare: Comparator<T>) {
    private var items = arrayOfNulls<Any?>(11); private var size = 0
    fun isNotEmpty() = size > 0
    @Suppress("UNCHECKED_CAST")
    fun add(x: T) {
        if (size == items.size) items = items.copyOf(if (size < 64) size * 2 + 2 else size + (size shr 1))
        var k = size++
        while (k > 0) {
            val parent = (k - 1) ushr 1; val e = items[parent] as T
            if (compare.compare(x, e) >= 0) break
            items[k] = e; k = parent
        }
        items[k] = x
    }
    @Suppress("UNCHECKED_CAST")
    fun poll(): T {
        val result = items[0] as T
        val n = --size; val x = items[n] as T; items[n] = null
        if (n > 0) {
            var k = 0; val half = n ushr 1
            while (k < half) {
                var child = 2 * k + 1; var c = items[child] as T; val right = child + 1
                if (right < n && compare.compare(c, items[right] as T) > 0) { child = right; c = items[child] as T }
                if (compare.compare(x, c) <= 0) break
                items[k] = c; k = child
            }
            items[k] = x
        }
        return result
    }
}
