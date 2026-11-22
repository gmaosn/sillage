package sillage

import kotlin.math.abs
import kotlin.math.round

/**
 * This text with its `%` marks replaced by [args], as `String.format` does on a JVM, on every platform
 * and whatever the system's language (the decimal mark is always a point): `%d`, `%5d`, `%02d`, `%x`,
 * `%.2f`, `%8.3f`, `%e`, `%s`, `%-10s`, `%c`, `%b`, `%%`. `"%d h %02d".fmt(16, 5)` gives « 16 h 05 ».
 */
fun String.fmt(vararg args: Any?): String {
    val out = StringBuilder(length + 16)
    var i = 0; var next = 0
    while (i < length) {
        val c = this[i++]
        if (c != '%') { out.append(c); continue }
        require(i < length) { "A lone % at the end of \"$this\"" }
        if (this[i] == '%') { out.append('%'); i++; continue }
        if (this[i] == 'n') { out.append('\n'); i++; continue }
        var left = false; var zero = false; var plus = false; var space = false; var group = false
        while (i < length && this[i] in "-0+ ,") { when (this[i]) { '-' -> left = true; '0' -> zero = true; '+' -> plus = true; ' ' -> space = true; else -> group = true }; i++ }
        var width = 0
        while (i < length && this[i].isDigit()) width = width * 10 + (this[i++] - '0')
        var precision = -1
        if (i < length && this[i] == '.') { i++; precision = 0; while (i < length && this[i].isDigit()) precision = precision * 10 + (this[i++] - '0') }
        require(i < length) { "An unfinished % mark in \"$this\"" }
        val kind = this[i++]
        require(next < args.size) { "\"$this\" has more % marks than values (${args.size})" }
        val a = args[next++]
        fun signed(negative: Boolean, digits: String): String = (if (negative) "-" else if (plus) "+" else if (space) " " else "") + digits
        fun grouped(digits: String): String = if (!group) digits else {
            val whole = digits.substringBefore('.'); val rest = digits.substring(whole.length)
            whole.reversed().chunked(3).joinToString(",").reversed() + rest
        }
        val text = when (kind) {
            'd' -> { val v = (a as Number).toLong(); signed(v < 0, grouped(if (v == Long.MIN_VALUE) "9223372036854775808" else abs(v).toString())) }
            'x' -> (if (a is Long) a.toULong().toString(16) else (a as Number).toInt().toUInt().toString(16))
            'X' -> (if (a is Long) a.toULong().toString(16) else (a as Number).toInt().toUInt().toString(16)).uppercase()
            'f' -> { val v = (a as Number).toDouble(); if (v.isNaN() || v.isInfinite()) v.toString() else signed(v < 0 || (v == 0.0 && 1 / v < 0), grouped(fixed(abs(v), if (precision < 0) 6 else precision))) }
            'e', 'E' -> {
                val v = (a as Number).toDouble()
                if (v.isNaN() || v.isInfinite()) v.toString() else {
                    var m = abs(v); var e = 0
                    if (m != 0.0) { while (m >= 10.0) { m /= 10.0; e++ }; while (m < 1.0) { m *= 10.0; e-- } }
                    var digits = fixed(m, if (precision < 0) 6 else precision)
                    if (digits.startsWith("10")) { e++; digits = fixed(m / 10.0, if (precision < 0) 6 else precision) }
                    val s = signed(v < 0, digits) + "e" + (if (e < 0) "-" else "+") + abs(e).toString().padStart(2, '0')
                    if (kind == 'E') s.uppercase() else s
                }
            }
            's' -> a.toString().let { if (precision >= 0) it.take(precision) else it }
            'S' -> a.toString().uppercase()
            'c' -> a.toString()
            'b' -> (a != null && a != false).toString()
            else -> throw IllegalArgumentException("fmt does not know %$kind (in \"$this\")")
        }
        when {
            text.length >= width -> out.append(text)
            left -> out.append(text.padEnd(width))
            zero && kind in "dfxXeE" -> {
                val sign = if (text.isNotEmpty() && text[0] in "+- ") text.substring(0, 1) else ""
                out.append(sign).append(text.substring(sign.length).padStart(width - sign.length, '0'))
            }
            else -> out.append(text.padStart(width))
        }
    }
    return out.toString()
}

/** [v] (not negative) with [decimals] digits after the point, rounded to the nearest (halves away from zero). */
private fun fixed(v: Double, decimals: Int): String {
    if (v >= 1e15 || decimals > 15) return v.toString()
    var scale = 1.0; repeat(decimals) { scale *= 10.0 }
    val scaled = v * scale
    // Round half up as printf does: a tie is a value whose scaled form ends in exactly .5.
    val units = if (scaled - kotlin.math.floor(scaled) == 0.5) kotlin.math.floor(scaled) + 1.0 else round(scaled)
    if (units >= 9.0e15) return v.toString()
    val whole = units.toLong().toString()
    if (decimals == 0) return whole
    val padded = whole.padStart(decimals + 1, '0')
    return padded.substring(0, padded.length - decimals) + "." + padded.substring(padded.length - decimals)
}
