package sillage

import sillage.math.Vec3
import kotlin.math.*

/**
 * Light and air of a scene: the sun, the colour of the shaded side, the ambient light from the sky
 * above and the ground below, the sky's gradient and the haze that fades far things into the horizon.
 * Change it while building the scene (before the first frame); the defaults are a clear day.
 */
class Lighting {
    /** Direction towards the sun (it need not be normalised). */
    var sunDirection: Vec3 = Vec3(-.45f, .80f, .40f)
    var sunColor: Color = Color(1f, .95f, .84f)
    /** Colour of the sides turned away from the sun (a soft blue-violet by default). */
    var shadeColor: Color = Color(.50f, .56f, .80f)
    /** Ambient light: from the sky onto faces looking up, from the ground onto faces looking down. */
    var skyAmbient: Color = Color(.42f, .56f, .72f)
    var groundAmbient: Color = Color(.42f, .36f, .26f)
    /** Sky colours: straight up, a quarter of the way up, and at the horizon (also the haze colour). */
    var zenith: Color = Color(.16f, .38f, .74f)
    var skyMiddle: Color = Color(.34f, .60f, .86f)
    var horizon: Color = Color(.60f, .80f, .86f)
    /** The haze starts at [fogStart] metres and hides everything beyond [fogEnd]. */
    var fogStart: Float = 45f
    var fogEnd: Float = 150f
    /**
     * Things cast shadows on each other: drawn from the sun into a map that covers a square of
     * 2 × [shadowRange] metres around what the camera looks at, fading out at its border. Each node can
     * opt out with [Node.castsShadow].
     */
    var shadows: Boolean = true
    var shadowRange: Float = 24f

    /** The values as the shader takes them (specialisation constants 0–20). */
    internal fun constants(): FloatArray {
        val s = sunDirection.normalized()
        return floatArrayOf(s.x, s.y, s.z, sunColor.r, sunColor.g, sunColor.b, shadeColor.r, shadeColor.g, shadeColor.b,
            skyAmbient.r, skyAmbient.g, skyAmbient.b, groundAmbient.r, groundAmbient.g, groundAmbient.b,
            horizon.r, horizon.g, horizon.b, fogStart, fogEnd)
    }

    /** The sky dome: unlit, a gradient from the horizon to the zenith, a warm halo around the sun. */
    internal fun skyDome(radius: Float = 160f, sides: Int = 64, rings: Int = 32): Mesh {
        val sun = sunDirection.normalized()
        fun color(d: Vec3): Vec3 {
            val h = d.y.coerceIn(0f, 1f)
            val mid = Vec3(skyMiddle.r, skyMiddle.g, skyMiddle.b); val hor = Vec3(horizon.r, horizon.g, horizon.b); val zen = Vec3(zenith.r, zenith.g, zenith.b)
            var c = if (h < .25f) hor.lerp(mid, h / .25f) else mid.lerp(zen, ((h - .25f) / .75f).coerceAtMost(1f))
            val s = (d dot sun).coerceAtLeast(0f)
            c += Vec3(sunColor.r, sunColor.g * .9f, sunColor.b * .74f) * (s.pow(24f) * .45f + s.pow(300f) * .9f)
            return c
        }
        fun point(i: Int, j: Int): Vec3 {
            val el = (i.toFloat() / rings) * (PI.toFloat() * .55f) - PI.toFloat() * .05f   // a little below the horizon
            val az = j.toFloat() / sides * 2f * PI.toFloat()
            return Vec3(cos(el) * cos(az), sin(el), cos(el) * sin(az))
        }
        val v = FloatArray(rings * sides * 6 * 9); var k = 0
        fun vertex(d: Vec3) { val c = color(d); v[k++] = d.x * radius; v[k++] = d.y * radius; v[k++] = d.z * radius; k += 3; v[k++] = c.x; v[k++] = c.y; v[k++] = c.z }
        for (i in 0 until rings) for (j in 0 until sides) {
            val a = point(i, j); val b = point(i + 1, j); val c = point(i + 1, j + 1); val d = point(i, j + 1)
            vertex(a); vertex(b); vertex(c); vertex(a); vertex(c); vertex(d)
        }
        return Mesh(v)
    }
}

/** A soft round shadow of radius 1: opacity (in the red channel) 1 at the centre, 0 at the rim. */
internal fun shadowDisc(opacity: Float = 1f, sides: Int = 32): Mesh {
    val v = FloatArray(sides * 27); var k = 0
    fun vertex(x: Float, z: Float, a: Float) { v[k++] = x; v[k++] = 0f; v[k++] = z; k += 3; v[k++] = a; v[k++] = 0f; v[k++] = 0f }
    for (i in 0 until sides) {
        val a = i * 2f * PI.toFloat() / sides; val b = (i + 1) * 2f * PI.toFloat() / sides
        vertex(0f, 0f, opacity); vertex(cos(b), sin(b), 0f); vertex(cos(a), sin(a), 0f)
    }
    return Mesh(v)
}
