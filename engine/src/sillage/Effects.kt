package sillage

import sillage.math.Vec3
import kotlin.math.*

/**
 * A lamp: a point of light that brightens what is around it, fading to nothing at [radius] metres (it
 * casts no shadow). Made by [Scene.light] or [Node.light]. The sixteen nearest the camera are drawn.
 */
class PointLight internal constructor(
    var color: Color,
    /** Brightness: 1 lights a white wall to about its own colour at a metre. */
    var intensity: Float,
    var radius: Float,
    /** Where it is: in the world, or in the space of [node] when it follows one. */
    var position: Vec3,
    /** The node it follows (its position, rotation and scale), or null. */
    val node: Node?,
    /** Lights up only as night falls ([Lighting.night]), like street lamps. */
    var onlyAtNight: Boolean,
    /** How much it wavers, from 0 (steady) to 1 (a flame). */
    var flicker: Float,
) {
    var enabled = true
    internal var removed = false
    internal var seed = 0f

    /** Where it is in the world now. */
    val worldPosition: Vec3 get() = node?.let { it.position + it.rotation.rotate(position * it.scale) } ?: position
}

/**
 * How particles look and move: their colour and size from birth to death, how long they live, how fast
 * and in which cone they leave, how gravity, drag and the wind carry them, and whether they glow (their
 * light adds to what is behind them, like sparks) or are lit like the scene (smoke, dust, spray).
 * [Particles] holds ready-made styles.
 */
class ParticleStyle(
    val color: Color,
    val endColor: Color = color,
    /** Radius at birth and at death (m). */
    val size: Float = .1f,
    val endSize: Float = size,
    /** Seconds, give or take [lifeSpread] (a fraction). */
    val life: Float = 1f,
    val lifeSpread: Float = .3f,
    /** Speed at birth (m/s), give or take [speedSpread] (a fraction). */
    val speed: Float = 1f,
    val speedSpread: Float = .5f,
    /** The direction they leave in, and the half-angle of the cone around it (degrees; 180: every way). */
    val direction: Vec3 = Vec3.Y,
    val spread: Float = 30f,
    /** Born anywhere within this radius around the emitter (m), flattened to a disc. */
    val area: Float = 0f,
    /** m/s² downwards (negative: they rise). */
    val gravity: Float = 0f,
    /** Slowing down, per second (0: none, 3: they soon float). */
    val drag: Float = 0f,
    /** A random drift (m/s), like dust in the air or fireflies. */
    val wander: Float = 0f,
    /** How much the scene's wind carries them (0: not at all, 1: like smoke). */
    val wind: Float = 0f,
    /** Opacity at full strength; they fade in quickly and fade out over the last part of their life. */
    val opacity: Float = 1f,
    val glow: Boolean = false,
    /** Brightness of glowing particles (above 1 they bloom). */
    val brightness: Float = 1f,
    /** Twinkling: brightness that wavers (0 steady, 1 blinking), for fireflies and sparkles. */
    val twinkle: Float = 0f,
)

/** Ready-made particle styles. */
object Particles {
    /** Flames: bright yellow at the root, red and thin at the tips (about 60 per second for a campfire). */
    val FIRE = ParticleStyle(Color(1f, .78f, .30f), Color(.85f, .12f, .03f), size = .12f, endSize = .02f, life = .65f, speed = 1.2f,
        spread = 12f, area = .16f, gravity = -1.6f, drag = 1.6f, wander = .35f, wind = .2f, glow = true, brightness = 2.6f)
    val SMOKE = ParticleStyle(Color(.42f, .41f, .42f), Color(.78f, .78f, .80f), size = .14f, endSize = .65f, life = 3.4f, speed = .8f,
        spread = 12f, area = .08f, gravity = -.35f, drag = .8f, wander = .15f, wind = .8f, opacity = .38f)
    val SPARKS = ParticleStyle(Color(1f, .85f, .4f), Color(1f, .35f, .08f), size = .03f, endSize = .015f, life = 1.2f, speed = 2.4f,
        spread = 25f, gravity = -.4f, drag = .9f, wander = .9f, wind = .3f, glow = true, brightness = 4f, twinkle = .5f)
    /** Drops thrown up by something falling into water (made by the scene itself). */
    val SPLASH = ParticleStyle(Color(.85f, .95f, 1f), Color(.75f, .9f, 1f), size = .07f, endSize = .03f, life = .7f, speed = 3f,
        spread = 35f, area = .2f, gravity = 9.8f, drag = .3f, opacity = .9f)
    val SPRAY = ParticleStyle(Color(.95f, .98f, 1f), size = .05f, endSize = .2f, life = 1.1f, speed = 1.4f, spread = 40f, area = .3f,
        gravity = 2f, drag = 1f, wind = .5f, opacity = .45f)
    /** A puff of dust (players landing on sand or a path). */
    val DUST = ParticleStyle(Color(.80f, .68f, .48f), Color(.86f, .78f, .62f), size = .05f, endSize = .2f, life = .7f, speed = .9f,
        spread = 70f, area = .12f, gravity = .8f, drag = 3f, wind = .3f, opacity = .55f)
    val FIREFLIES = ParticleStyle(Color(.75f, 1f, .35f), size = .035f, life = 6f, lifeSpread = .4f, speed = .2f, spread = 180f,
        area = 8f, drag = .5f, wander = .45f, glow = true, brightness = 5f, twinkle = 1f)
    val SPARKLE = ParticleStyle(Color(1f, .95f, .7f), Color(1f, .75f, .4f), size = .06f, endSize = .02f, life = .9f, speed = 1.6f,
        spread = 180f, area = .1f, gravity = .6f, drag = 2f, glow = true, brightness = 4f, twinkle = .6f)
}

/** Particles flowing from a place or a node, [rate] per second. Made by [Scene.emit] or [Node.emit]. */
class Emitter internal constructor(val style: ParticleStyle, var rate: Float, var position: Vec3, val node: Node?) {
    var enabled = true
    internal var removed = false
    internal var owed = 0f
}

/** The particles of a scene, in flat arrays (nothing is made per particle). */
internal class ParticleSystem(private val capacity: Int = 4096) {
    private val px = FloatArray(capacity); private val py = FloatArray(capacity); private val pz = FloatArray(capacity)
    private val vx = FloatArray(capacity); private val vy = FloatArray(capacity); private val vz = FloatArray(capacity)
    private val age = FloatArray(capacity); private val life = FloatArray(capacity); private val seed = FloatArray(capacity)
    private val style = arrayOfNulls<ParticleStyle>(capacity)
    var count = 0
        private set
    private var random = 0x2545F491L

    private fun rnd(): Float {
        random = random xor (random shl 13); random = random xor (random ushr 7); random = random xor (random shl 17)
        return ((random ushr 40).toInt() and 0xFFFFFF) / 16777216f
    }

    fun spawn(s: ParticleStyle, x: Float, y: Float, z: Float, n: Int, dx: Float = s.direction.x, dy: Float = s.direction.y, dz: Float = s.direction.z) {
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
        val ux = dx / dl; val uy = dy / dl; val uz = dz / dl
        // Two axes across the direction, for the cone.
        var ax = -uz; var ay = 0f; var az = ux
        if (abs(uy) > .9f) { ax = 1f; ay = 0f; az = 0f }
        val al = sqrt(ax * ax + ay * ay + az * az); ax /= al; ay /= al; az /= al
        val bx = uy * az - uz * ay; val by = uz * ax - ux * az; val bz = ux * ay - uy * ax
        val cosMax = cos(Math.toRadians(s.spread.toDouble()).toFloat())
        for (k in 0 until n) {
            if (count >= capacity) return
            val i = count++
            val r = s.area * sqrt(rnd()); val t = rnd() * 2f * PI.toFloat()
            px[i] = x + cos(t) * r; py[i] = y; pz[i] = z + sin(t) * r
            // A direction in the cone: uniform over its cap.
            val c = 1f - rnd() * (1f - cosMax); val sn = sqrt((1f - c * c).coerceAtLeast(0f)); val phi = rnd() * 2f * PI.toFloat()
            val ex = cos(phi) * sn; val ey = sin(phi) * sn
            val sp = s.speed * (1f + (rnd() * 2f - 1f) * s.speedSpread)
            vx[i] = (ux * c + ax * ex + bx * ey) * sp; vy[i] = (uy * c + ay * ex + by * ey) * sp; vz[i] = (uz * c + az * ex + bz * ey) * sp
            age[i] = 0f; life[i] = s.life * (1f + (rnd() * 2f - 1f) * s.lifeSpread); seed[i] = rnd() * 100f
            style[i] = s
        }
    }

    fun update(dt: Float, windX: Float, windZ: Float, time: Float) {
        var i = 0
        while (i < count) {
            age[i] += dt
            if (age[i] >= life[i]) { count--; move(count, i); continue }
            val s = style[i]!!
            val keep = 1f / (1f + s.drag * dt)
            val w = s.wander
            var ax = 0f; var az = 0f; var ay = -s.gravity
            if (w > 0f) {
                val k = seed[i]
                ax = sin(time * 1.3f + k) * w * 2f; ay += sin(time * 1.1f + k * 1.7f) * w; az = cos(time * 1.7f + k * 2.3f) * w * 2f
            }
            vx[i] = (vx[i] + (ax + windX * s.wind * s.drag.coerceAtLeast(.5f)) * dt) * keep
            vy[i] = (vy[i] + ay * dt) * keep
            vz[i] = (vz[i] + (az + windZ * s.wind * s.drag.coerceAtLeast(.5f)) * dt) * keep
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt
            i++
        }
    }

    private fun move(from: Int, to: Int) {
        px[to] = px[from]; py[to] = py[from]; pz[to] = pz[from]; vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from]
        age[to] = age[from]; life[to] = life[from]; seed[to] = seed[from]; style[to] = style[from]; style[from] = null
    }

    /** Writes what the renderer draws ([PARTICLE_FLOATS] per particle) into [out]. */
    fun write(out: FrameExtras, time: Float, night: Float) {
        if (out.particles.size < count * PARTICLE_FLOATS) out.particles = FloatArray(Integer.highestOneBit(count) * 2 * PARTICLE_FLOATS)
        val o = out.particles
        for (i in 0 until count) {
            val s = style[i]!!
            val t = (age[i] / life[i]).coerceIn(0f, 1f)
            val fade = (t / .08f).coerceAtMost(1f) * (if (t > .6f) 1f - (t - .6f) / .4f else 1f)
            var bright = if (s.glow) s.brightness else 1f
            if (s.twinkle > 0f) bright *= 1f - s.twinkle * (.5f + .5f * sin(time * 9f + seed[i] * 7f)).let { it * it }
            val size = s.size + (s.endSize - s.size) * t
            val k = i * PARTICLE_FLOATS
            o[k] = px[i]; o[k + 1] = py[i]; o[k + 2] = pz[i]; o[k + 3] = if (s.glow) -size else size
            o[k + 4] = (s.color.r + (s.endColor.r - s.color.r) * t) * bright
            o[k + 5] = (s.color.g + (s.endColor.g - s.color.g) * t) * bright
            o[k + 6] = (s.color.b + (s.endColor.b - s.color.b) * t) * bright
            o[k + 7] = fade * s.opacity
        }
        out.particleCount = count
    }

    fun clear() { for (i in 0 until count) style[i] = null; count = 0 }
}
