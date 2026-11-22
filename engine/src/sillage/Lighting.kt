package sillage

import sillage.math.Vec3
import kotlin.math.*

/**
 * Light and air of a scene: the sun, the colour of the shaded side, the ambient light from the sky
 * above and the ground below, the sky's gradient, the haze that fades far things into the sky, the
 * wind, and how the picture is finished (glow, ink lines, vignette). Everything can change at any time.
 *
 * By default the light is a clear day that stays still. Set [time] to give the scene an hour: the sun
 * and the moon then follow their course, the colours follow the hour (dawn, day, sunset, night, with
 * stars) and [night] tells the lamps when to light up. With [dayLength], the hours go by.
 */
class Lighting {
    /** Direction towards the sun (it need not be normalised). Follows the hour when [time] is set. */
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
    /** Colour of the glow around the sun in the sky (and of its glitter on water). */
    var sunGlow: Color = Color(1f, .86f, .62f)
    /** The haze starts at [fogStart] metres and hides everything beyond [fogEnd]. */
    var fogStart: Float = 45f
    var fogEnd: Float = 150f
    /**
     * Things cast shadows on each other: drawn from the sun (or the moon) into a map that covers a square
     * of 2 × [shadowRange] metres around what the camera looks at, fading out at its border. Each node can
     * opt out with [Node.castsShadow]. It can be turned on and off while the game runs.
     */
    var shadows: Boolean = true
    var shadowRange: Float = 24f
    /**
     * How many shadow squares, one inside the other (1 to 3): the finest of [shadowRange], then three
     * times wider, then out to the haze's end ([fogEnd], at least twice the second). Far things keep their
     * shadows, softer; 1 keeps only the finest, as before.
     */
    var shadowCascades: Int = 3
        set(v) { require(v in 1..3) { "shadowCascades is 1, 2 or 3 (got $v)" }; field = v }

    /** Half side of shadow square [c] (0: finest), or 0 when there are fewer squares. */
    internal fun cascadeHalf(c: Int): Float = when {
        c >= shadowCascades -> 0f
        c == 0 -> shadowRange
        c == 1 -> shadowRange * 3f
        else -> maxOf(shadowRange * 6f, minOf(fogEnd, 400f))
    }

    /**
     * The hour, from 0 to 24 (6 sunrise, 12 noon, 18 sunset, 0 midnight). Null (the default): no hour,
     * the light stays as set above. Once set, the sun, the moon and every colour above follow it.
     */
    var time: Float? = null
        set(value) { field = value?.let { ((it % 24f) + 24f) % 24f }; if (field != null) follow() }
    /** Seconds of play for a whole day of 24 hours; 0 (the default): the hour stands still. */
    var dayLength: Float = 0f
    /**
     * Where the sun rises (a direction on the ground, x and z); it sets on the opposite side and, at noon,
     * stands [sunTilt] degrees from the zenith, leaning to the right of its rising.
     */
    var sunrise: Vec3 = Vec3(.33f, 0f, .94f)
    var sunTilt: Float = 30f

    /**
     * 0 by day, 1 at night, in between at dusk and dawn. Lamps light up with it, stars come out. It follows the
     * hour ([time]); without a clock it is yours to set (a scene lit by hand that wants stars sets it to 1).
     */
    var night: Float = 0f
    /** Towards the moon (it shows at night when it is above the horizon). */
    var moonDirection: Vec3 = Vec3(.4f, -.6f, -.7f)
        private set

    /** The wind on the ground plane (x, z): its direction and strength (0 calm, 1 a strong breeze). Plants sway with it. */
    var wind: Vec3 = Vec3(.55f, 0f, .3f)

    /** Glow (bloom) around what is brighter than white: lamps, the sun, its glitter on the sea. 0: none. */
    var glow: Float = .8f
    /** Ink lines along silhouettes, from 0 (none) to 1. */
    var outlines: Float = .85f
    /** Darker corners, from 0 (none) to 1. */
    var vignette: Float = .22f
    /** Colour saturation of the finished picture: 1 as is, more to make colours richer. */
    var saturation: Float = 1.06f

    /** Moves the hour on by [dt] seconds of play (when the day has a length). */
    internal fun advance(dt: Float) {
        val t = time ?: return
        if (dayLength > 0f) time = t + dt * 24f / dayLength else follow()
    }

    /** The sun's direction at the hour [t] (it may be below the horizon). */
    internal fun sunAt(t: Float): Vec3 {
        val a = (t - 6f) / 12f * PI.toFloat()
        val r = Vec3(sunrise.x, 0f, sunrise.z).normalized()
        val side = Vec3(-r.z, 0f, r.x)
        val tilt = sillage.math.toRadians(sunTilt.toDouble()).toFloat()
        val noon = Vec3.Y * cos(tilt) + side * sin(tilt)
        return (r * cos(a) + noon * sin(a)).normalized()
    }

    /** The colours of the hour, from the sun's height (keys from deep night to full day, blended smoothly). */
    private fun follow() {
        val t = time ?: return
        val sun = sunAt(t)
        sunDirection = sun
        // The moon: across the sky from the sun, on a lower path.
        val m = sunAt(t + 12f)
        moonDirection = Vec3(m.x * .9f + .25f, m.y * .8f, m.z * .9f - .1f).normalized()
        val e = sun.y
        var i = 0
        while (i < KEYS.size - 2 && e > KEYS[i + 1].height) i++
        val a = KEYS[i]; val b = KEYS[i + 1]
        val k = ((e - a.height) / (b.height - a.height)).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        fun mix(x: Color, y: Color) = x.mix(y, k)
        sunColor = mix(a.light, b.light); shadeColor = mix(a.shade, b.shade)
        skyAmbient = mix(a.sky, b.sky); groundAmbient = mix(a.ground, b.ground)
        zenith = mix(a.zenith, b.zenith); skyMiddle = mix(a.middle, b.middle); horizon = mix(a.horizon, b.horizon)
        sunGlow = mix(a.glow, b.glow)
        night = smoothstep(.03f, -.16f, e)
    }

    /** The main light's direction for shading and shadows: the sun, or the moon once the sun is well down; never lower than 7°. */
    internal fun mainLight(): Vec3 {
        val d = if (time != null && sunDirection.normalized().y < -.05f) moonDirection else sunDirection.normalized()
        return if (d.y >= .12f) d else Vec3(d.x, 0f, d.z).normalized().let { h -> (h * sqrt(1f - .12f * .12f) + Vec3.Y * .12f) }
    }

    /** How much of the sun's disc and glow shows (0 well below the horizon). */
    internal fun sunShows(): Float = if (time == null) 1f else smoothstep(-.2f, -.02f, sunDirection.normalized().y)
    internal fun moonShows(): Float = if (time == null) 0f else smoothstep(-.04f, .1f, moonDirection.y) * (.3f + .7f * night)

    /** The sky dome: a half sphere a little below the horizon, coloured by the sky shader. */
    internal fun skyDome(radius: Float = 160f, sides: Int = 48, rings: Int = 20): Mesh {
        fun point(i: Int, j: Int): Vec3 {
            val el = (i.toFloat() / rings) * (PI.toFloat() * .55f) - PI.toFloat() * .05f   // a little below the horizon
            val az = j.toFloat() / sides * 2f * PI.toFloat()
            return Vec3(cos(el) * cos(az), sin(el), cos(el) * sin(az))
        }
        val v = FloatArray(rings * sides * 6 * 9); var k = 0
        fun vertex(d: Vec3) { v[k++] = d.x * radius; v[k++] = d.y * radius; v[k++] = d.z * radius; k += 6 }
        for (i in 0 until rings) for (j in 0 until sides) {
            val a = point(i, j); val b = point(i + 1, j); val c = point(i + 1, j + 1); val d = point(i, j + 1)
            vertex(a); vertex(b); vertex(c); vertex(a); vertex(c); vertex(d)
        }
        return Mesh(v)
    }

    private class Key(val height: Float, val light: Color, val shade: Color, val sky: Color, val ground: Color,
                      val zenith: Color, val middle: Color, val horizon: Color, val glow: Color)

    private companion object {
        fun smoothstep(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
        fun c(r: Float, g: Float, b: Float) = Color(r, g, b)
        /** The light of the day by the sun's height (sine of its elevation); by night the main light is the moon's. */
        val KEYS = listOf(
            Key(-.30f, c(.15f, .21f, .42f), c(.06f, .08f, .19f), c(.08f, .12f, .27f), c(.04f, .04f, .07f),
                c(.006f, .012f, .04f), c(.015f, .03f, .08f), c(.035f, .06f, .13f), c(0f, 0f, 0f)),
            Key(-.12f, c(.17f, .19f, .36f), c(.09f, .10f, .22f), c(.15f, .16f, .32f), c(.07f, .06f, .09f),
                c(.02f, .03f, .09f), c(.06f, .06f, .16f), c(.20f, .12f, .21f), c(.55f, .22f, .16f)),
            Key(-.04f, c(.14f, .11f, .20f), c(.16f, .14f, .28f), c(.26f, .24f, .40f), c(.14f, .10f, .10f),
                c(.05f, .08f, .20f), c(.20f, .16f, .30f), c(.72f, .36f, .28f), c(1f, .45f, .25f)),
            Key(.04f, c(1f, .50f, .24f), c(.30f, .26f, .48f), c(.36f, .32f, .46f), c(.30f, .20f, .14f),
                c(.10f, .16f, .38f), c(.42f, .36f, .50f), c(1f, .55f, .32f), c(1f, .50f, .24f)),
            Key(.16f, c(1f, .78f, .50f), c(.42f, .44f, .70f), c(.42f, .48f, .64f), c(.40f, .32f, .22f),
                c(.14f, .32f, .66f), c(.40f, .55f, .78f), c(.86f, .74f, .62f), c(1f, .72f, .45f)),
            Key(.40f, c(1f, .95f, .84f), c(.50f, .56f, .80f), c(.42f, .56f, .72f), c(.42f, .36f, .26f),
                c(.16f, .38f, .74f), c(.34f, .60f, .86f), c(.60f, .80f, .86f), c(1f, .86f, .62f)),
        )
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
