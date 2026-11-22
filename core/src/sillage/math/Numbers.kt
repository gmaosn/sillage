package sillage.math

import kotlin.time.TimeSource

/** Degrees to radians, as `java.lang.Math.toRadians` does (the same constant: the same bits on every platform). */
fun toRadians(degrees: Double): Double = degrees * 0.017453292519943295

/** Radians to degrees, as `java.lang.Math.toDegrees` does. */
fun toDegrees(radians: Double): Double = radians * 57.29577951308232

private val origin = TimeSource.Monotonic.markNow()

/** Nanoseconds on a clock that only goes forward (for measuring durations, not for telling the time). */
fun nanoTime(): Long = origin.elapsedNow().inWholeNanoseconds

/** The 32 bits of [v] in the opposite order. */
fun reverseBits(v: Int): Int {
    var x = v
    x = ((x ushr 1) and 0x55555555) or ((x and 0x55555555) shl 1)
    x = ((x ushr 2) and 0x33333333) or ((x and 0x33333333) shl 2)
    x = ((x ushr 4) and 0x0F0F0F0F) or ((x and 0x0F0F0F0F) shl 4)
    return (x ushr 24) or ((x ushr 8) and 0xFF00) or ((x shl 8) and 0xFF0000) or (x shl 24)
}
