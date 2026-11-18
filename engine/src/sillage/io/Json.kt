package sillage.io

/**
 * Strict JSON (RFC 8259) reader, enough for glTF and settings files.
 * Numbers keep their source text, so `toFloat()` rounds the decimal text once, directly to Float.
 */
sealed interface Json {
    class Obj(val fields: Map<String, Json>) : Json {
        operator fun get(key: String): Json? = fields[key]
        fun has(key: String) = key in fields
        fun obj(key: String): Obj? = fields[key]?.let { it as? Obj ?: typeError(key, "an object") }
        fun arr(key: String): Arr? = fields[key]?.let { it as? Arr ?: typeError(key, "an array") }
        fun num(key: String): Num? = fields[key]?.let { it as? Num ?: typeError(key, "a number") }
        fun int(key: String): Int? = num(key)?.toInt()
        fun float(key: String): Float? = num(key)?.toFloat()
        fun string(key: String): String? = fields[key]?.let { (it as? Str ?: typeError(key, "a string")).value }
        fun bool(key: String): Boolean? = fields[key]?.let { (it as? Bool ?: typeError(key, "a boolean")).value }
        override fun toString() = fields.entries.joinToString(",", "{", "}") { (k, v) -> quote(k) + ":" + v }
        private fun typeError(key: String, expected: String): Nothing =
            throw IllegalArgumentException("JSON: \"$key\" should be $expected")
    }

    class Arr(val items: List<Json>) : Json, Iterable<Json> by items {
        val size get() = items.size
        operator fun get(i: Int): Json = items[i]
        fun obj(i: Int): Obj = items[i] as? Obj ?: throw IllegalArgumentException("JSON: item $i should be an object")
        fun floats(): FloatArray = FloatArray(items.size) { (items[it] as? Num ?: notNumber(it)).toFloat() }
        fun ints(): IntArray = IntArray(items.size) { (items[it] as? Num ?: notNumber(it)).toInt() }
        override fun toString() = items.joinToString(",", "[", "]")
        private fun notNumber(i: Int): Nothing = throw IllegalArgumentException("JSON: item $i should be a number")
    }

    class Num(val text: String) : Json {
        fun toDouble() = text.toDouble()
        fun toFloat() = text.toFloat()
        fun toLong(): Long = text.toLongOrNull() ?: toDouble().let { d ->
            require(d == Math.rint(d) && d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()) {
                "JSON: $text is not an integer" }
            d.toLong()
        }
        fun toInt(): Int = toLong().let {
            require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "JSON: $text does not fit in an Int" }
            it.toInt()
        }
        override fun toString() = text
    }

    class Str(val value: String) : Json { override fun toString() = quote(value) }
    class Bool private constructor(val value: Boolean) : Json {
        override fun toString() = value.toString()
        companion object { val TRUE = Bool(true); val FALSE = Bool(false) }
    }
    object Null : Json { override fun toString() = "null" }

    companion object {
        /** Nesting deeper than this is refused, so a hostile file cannot overflow the stack. */
        const val MAX_DEPTH = 256

        fun parse(text: String): Json {
            val p = Parser(text)
            p.skipSpace()
            val v = p.value(0)
            p.skipSpace()
            if (p.i != text.length) p.fail("unexpected text after the value")
            return v
        }

        internal fun quote(s: String) = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\""); c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n"); c == '\r' -> append("\\r"); c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}

private class Parser(val s: String) {
    var i = 0

    fun fail(message: String): Nothing = throw IllegalArgumentException("JSON, offset $i: $message")

    fun skipSpace() {
        while (i < s.length) when (s[i]) { ' ', '\t', '\n', '\r' -> i++; else -> return }
    }

    fun value(depth: Int): Json {
        if (i >= s.length) fail("value expected, end of text found")
        return when (val c = s[i]) {
            '{' -> obj(depth + 1)
            '[' -> arr(depth + 1)
            '"' -> Json.Str(string())
            't' -> literal("true", Json.Bool.TRUE)
            'f' -> literal("false", Json.Bool.FALSE)
            'n' -> literal("null", Json.Null)
            else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected character '$c'")
        }
    }

    private fun literal(word: String, v: Json): Json {
        if (!s.startsWith(word, i)) fail("'$word' expected")
        i += word.length
        return v
    }

    private fun obj(depth: Int): Json.Obj {
        if (depth > Json.MAX_DEPTH) fail("nesting deeper than ${Json.MAX_DEPTH}")
        i++
        val fields = LinkedHashMap<String, Json>()
        skipSpace()
        if (i < s.length && s[i] == '}') { i++; return Json.Obj(fields) }
        while (true) {
            skipSpace()
            if (i >= s.length || s[i] != '"') fail("member name expected")
            val key = string()
            skipSpace()
            if (i >= s.length || s[i] != ':') fail("':' expected")
            i++; skipSpace()
            fields[key] = value(depth)
            skipSpace()
            if (i >= s.length) fail("unterminated object")
            when (s[i++]) { ',' -> continue; '}' -> return Json.Obj(fields); else -> { i--; fail("',' or '}' expected") } }
        }
    }

    private fun arr(depth: Int): Json.Arr {
        if (depth > Json.MAX_DEPTH) fail("nesting deeper than ${Json.MAX_DEPTH}")
        i++
        val items = ArrayList<Json>()
        skipSpace()
        if (i < s.length && s[i] == ']') { i++; return Json.Arr(items) }
        while (true) {
            skipSpace()
            items.add(value(depth))
            skipSpace()
            if (i >= s.length) fail("unterminated array")
            when (s[i++]) { ',' -> continue; ']' -> return Json.Arr(items); else -> { i--; fail("',' or ']' expected") } }
        }
    }

    private fun string(): String {
        i++ // opening quote
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    if (i >= s.length) fail("unterminated escape")
                    when (val e = s[i++]) {
                        '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                        'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n')
                        'r' -> out.append('\r'); 't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("short \\u escape")
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                            out.append(code.toChar()); i += 4
                        }
                        else -> { i--; fail("unknown escape '\\$e'") }
                    }
                }
                c < ' ' -> { i--; fail("control character in a string") }
                else -> out.append(c)
            }
        }
    }

    private fun number(): Json.Num {
        val start = i
        if (s[i] == '-') i++
        if (i >= s.length) fail("digit expected")
        if (s[i] == '0') i++
        else if (s[i] in '1'..'9') while (i < s.length && s[i] in '0'..'9') i++
        else fail("digit expected")
        if (i < s.length && s[i] == '.') {
            i++
            if (i >= s.length || s[i] !in '0'..'9') fail("digit expected after '.'")
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || s[i] !in '0'..'9') fail("digit expected in exponent")
            while (i < s.length && s[i] in '0'..'9') i++
        }
        return Json.Num(s.substring(start, i))
    }
}
