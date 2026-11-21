package sillage

import sillage.audio.Audio
import sillage.audio.Noise
import sillage.audio.Reverb
import sillage.audio.Sound
import sillage.audio.Sounds
import sillage.audio.Voice
import sillage.math.Vec3
import sillage.physics.Body
import sillage.physics.BodyType
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.Shape
import sillage.physics.Sphere
import kotlin.math.*

/**
 * The sounds a scene makes by itself, all synthesised by the engine ([Sounds]) and placed in space:
 * players' footsteps (on their animation's footfalls, by the ground they walk on), jumps and landings,
 * bodies hitting things, a box scraping as it is pushed, pickups. Ambiences and one-shots come from
 * [Scene.ambience] and [Scene.play].
 */
internal class SceneSounds(private val scene: Scene) {
    private var audio: Audio? = null
    private val steps = java.util.concurrent.ConcurrentHashMap<String, List<Sound>>()
    private val jump by lazy { Sounds.jump() }
    private val land by lazy { Sounds.land() }
    val pickup by lazy { Sounds.pickup() }
    private val wood by lazy { List(3) { Sounds.knock(it) } }
    private val rubber by lazy { List(3) { Sounds.bounce(it) } }
    private val scrape by lazy { Sounds.scrape() }
    private val rnd = Noise(2026)

    private class Tracked(val body: Body, val impact: Impact, var velocity: Vec3, var last: Float = -1f)
    private val tracked = ArrayList<Tracked>()
    private class Walker(var clip: String? = null, var phase: Float = 0f, var air: Float = 0f, var grounded: Boolean = true,
                         var distance: Float = 0f, var last: Vec3 = Vec3.ZERO, var scraping: Voice? = null)
    private val walkers = HashMap<Player, Walker>()
    private class Pending(val sound: Sound, val at: Vec3?, val volume: Float, val range: Float, val offset: Float, val handle: Ambience)
    private val pendingAmbience = ArrayList<Pending>()
    /** Sounds that follow a node (or a part of an animated model) while they play. */
    private class Attached(val node: Node, val part: String?, val voice: Voice)
    private val attached = ArrayList<Attached>()
    private class Zone(val shape: Shape, val at: Vec3, val rotation: sillage.math.Quat, val reverb: Reverb)
    private val zones = ArrayList<Zone>()
    /** The place's reverberation outside every zone. */
    var reverb: Reverb? = null

    fun start(a: Audio?) {
        audio = a
        if (a != null) {
            for (p in pendingAmbience) startAmbience(a, p)
            // Synthesise the engine's sounds now, beside the game: made on first use, the pickup chime or
            // the first step on sand would hold a frame for tens of milliseconds.
            kotlin.concurrent.thread(isDaemon = true, name = "sillage-sounds") {
                pickup; jump; land; wood; rubber; scrape
                for (s in listOf("grass", "sand", "wood", "stone")) steps.getOrPut(s) { List(4) { Sounds.footstep(s, it) } }
            }
        }
    }

    /** The pickup chime, when there is sound (nothing is synthesised otherwise). */
    fun playPickup() { if (audio != null) play(pickup, null, .7f, 1f) }

    fun track(body: Body, impact: Impact) { if (impact != Impact.SILENT) tracked.add(Tracked(body, impact, body.velocity)) }
    fun untrack(body: Body) { tracked.removeAll { it.body == body } }

    fun ambience(sound: Sound, at: Vec3?, volume: Float, range: Float): Ambience {
        val handle = Ambience(volume)
        val p = Pending(sound, at, volume, range, rnd.unit() * sound.duration, handle)
        pendingAmbience.add(p)
        audio?.let { startAmbience(it, p) }
        return handle
    }

    private fun startAmbience(a: Audio, p: Pending) {
        p.handle.voice = if (p.at == null) a.play(p.sound, p.handle.volume, loop = true, offset = p.offset)
        else a.playAt(p.sound, p.at, p.handle.volume, vary(.04f), loop = true, referenceDistance = p.range / 12f, maxDistance = p.range, offset = p.offset)
    }

    /** Plays [sound] from [node] (its [part]) and keeps it there while it plays. */
    fun attach(node: Node, part: String?, sound: Sound, volume: Float, pitch: Float, range: Float, loop: Boolean): Voice? {
        val a = audio ?: return null
        val v = a.playAt(sound, scene.soundPosition(node, part), volume, pitch, loop = loop, referenceDistance = range / 12f, maxDistance = range)
        attached.add(Attached(node, part, v))
        return v
    }

    fun reverbZone(shape: Shape, at: Vec3, rotation: sillage.math.Quat, reverb: Reverb) { zones.add(Zone(shape, at, rotation, reverb)) }

    /** The reverberation where the ears are: the first zone holding them, else the scene's. */
    private fun reverbAt(p: Vec3): Reverb? {
        for (z in zones) {
            val l = z.rotation.conjugate().rotate(p - z.at)
            val inside = when (val s = z.shape) {
                is Sphere -> l.length() <= s.radius
                is Box -> kotlin.math.abs(l.x) <= s.halfX && kotlin.math.abs(l.y) <= s.halfY && kotlin.math.abs(l.z) <= s.halfZ
                is Capsule -> { val y = l.y.coerceIn(-s.halfHeight, s.halfHeight); Vec3(l.x, l.y - y, l.z).length() <= s.radius }
                is sillage.physics.ConvexHull -> s.contains(l)
                is sillage.physics.Compound -> s.contains(l)
            }
            if (inside) return z.reverb
        }
        return reverb
    }

    fun play(sound: Sound, at: Vec3?, volume: Float, pitch: Float, range: Float = 60f): Voice? {
        val a = audio ?: return null
        return if (at == null) a.play(sound, volume, pitch) else a.playAt(sound, at, volume, pitch, referenceDistance = range / 12f, maxDistance = range)
    }

    private fun vary(spread: Float) = 1f + (rnd.unit() - .5f) * 2f * spread

    fun update(dt: Float) {
        val a = audio ?: return
        val cam = scene.camera
        a.listen(cam.position, cam.forward)
        a.mixer.reverb = reverbAt(cam.position)
        for (p in pendingAmbience) p.handle.voice?.let { v -> if (p.handle.applied != p.handle.volume) { v.volume(p.handle.volume); p.handle.applied = p.handle.volume } }
        // Sounds that follow what makes them (a gull in flight): their place each frame, so they come and go (Doppler).
        var k = 0
        while (k < attached.size) {
            val s = attached[k]
            if (!s.voice.isPlaying || s.node.removed) { if (s.node.removed) s.voice.stop(); attached.removeAt(k); continue }
            s.voice.position(scene.soundPosition(s.node, s.part)); k++
        }
        for (p in scene.players) if (p.sounds) { val w = walkers.getOrPut(p) { Walker(last = p.position) }; footsteps(a, p, w); jumps(a, p, w, dt); scraping(a, p, w) }
        impacts(a, dt)
    }

    /** A player's sound: at its feet, heard in full from the usual camera distance. */
    private fun fromPlayer(a: Audio, p: Player, s: Sound, volume: Float, pitch: Float = 1f) =
        a.playAt(s, p.position.plus(0f, .3f, 0f), volume, pitch, referenceDistance = 9f, maxDistance = 60f)

    /** A step at each footfall: twice per cycle of the walk, run or push clip (phases 0 and ½), or every 0.7 m without clips. */
    private fun footsteps(a: Audio, p: Player, w: Walker) {
        val an = p.animator
        val names = p.animations
        val moved = Vec3(p.position.x - w.last.x, 0f, p.position.z - w.last.z).length(); w.last = p.position
        if (an == null || listOf(names.walk, names.run, names.push).none { it != null && it in an.model.clips }) {
            if (!p.onGround) return
            w.distance += moved
            if (w.distance > .7f) { w.distance = 0f; stepOn(a, p, ground(p), .45f) }
            return
        }
        val name = an.current
        val volume = when (name) { names.walk -> .38f; names.run -> .55f; names.push -> .45f; else -> { w.clip = name; return } }
        val duration = an.model.clips.getValue(name!!).duration
        val phase = (an.time % duration) / duration
        if (name != w.clip) { w.clip = name; w.phase = .9f }
        // The foot lands a little before the lowest point of the hips.
        val strike = if (name == names.run) .46f else .5f
        val landed = crossed(w.phase, phase, strike) || crossed(w.phase, phase, strike - .5f + if (strike < .5f) 1f else 0f)
        w.phase = phase
        if (landed && p.onGround) stepOn(a, p, ground(p), volume)
    }

    /** The cyclic phase went from [before] to [after] through [x] (less than a cycle per frame). */
    private fun crossed(before: Float, after: Float, x: Float) = if (after >= before) x > before && x <= after else x > before || x <= after

    private fun ground(p: Player): String {
        p.character.groundBody?.let { b -> return scene.surfaces[b.id] ?: "stone" }
        return p.character.groundMaterial?.name ?: "grass"
    }

    private fun stepOn(a: Audio, p: Player, surface: String, volume: Float) {
        val variants = steps.getOrPut(surface) { List(4) { Sounds.footstep(surface, it) } }
        fromPlayer(a, p, variants[(rnd.unit() * 4).toInt().coerceAtMost(3)], volume * vary(.12f), vary(.05f))
    }

    private fun jumps(a: Audio, p: Player, w: Walker, dt: Float) {
        if (p.onGround) {
            if (!w.grounded && w.air > .2f) {
                // The longer the fall, the harder the landing; the ground is heard too.
                val strength = ((w.air - .2f) / .6f).coerceIn(.15f, 1f)
                fromPlayer(a, p, land, .35f + .5f * strength, vary(.04f))
                stepOn(a, p, ground(p), .5f + .3f * strength)
            }
            w.air = 0f
        } else {
            if (w.grounded && p.velocity.y > 1f) fromPlayer(a, p, jump, .4f, vary(.06f))
            w.air += dt
        }
        w.grounded = p.onGround
    }

    /** A body suddenly changing speed (gravity aside) hit something. */
    private fun impacts(a: Audio, dt: Float) {
        val g = scene.physics.gravity
        for (t in tracked) {
            val v = t.body.velocity
            if (t.body.isSleeping) { t.velocity = v; continue }
            val dv = (v - t.velocity - g * dt).length()
            t.velocity = v
            if (dv < 1.1f || scene.time - t.last < .09f) continue
            t.last = scene.time
            val strength = ((dv - 1f) / 5f).coerceIn(.08f, 1f)
            val sounds = if (t.impact == Impact.RUBBER) rubber else wood
            a.playAt(sounds[(rnd.unit() * sounds.size).toInt().coerceAtMost(sounds.size - 1)], t.body.position, strength,
                vary(.06f), referenceDistance = 3f, maxDistance = 45f)
        }
    }

    /** A wooden body pushed by a player scrapes the ground while it moves. */
    private fun scraping(a: Audio, p: Player, w: Walker) {
        val b = p.character.pushing
        val box = b != null && b.type == BodyType.DYNAMIC && tracked.any { it.body == b && it.impact == Impact.WOOD }
        val v = if (box) b.velocity.let { sqrt(it.x * it.x + it.z * it.z) } else 0f
        if (box && v > .12f) {
            val volume = (v / 1.5f).coerceIn(.1f, .7f)
            val s = w.scraping
            if (s == null || !s.isPlaying) w.scraping = a.playAt(scrape, b.position, volume, .9f + .2f * v, loop = true, referenceDistance = 4f, maxDistance = 40f)
            else { s.volume(volume); s.position(b.position); s.pitch(.9f + .2f * v.coerceAtMost(1.5f)) }
        } else w.scraping?.let { it.stop(.12f); w.scraping = null }
    }
}

/** Writes every sound of the engine as WAV into [dir], to listen to them or touch them up. */
internal fun exportSounds(dir: java.nio.file.Path) {
    val all = LinkedHashMap<String, Sound>()
    for (s in listOf("grass", "sand", "wood", "stone", "road", "ice")) for (v in 0..3) all["step-$s-$v"] = Sounds.footstep(s, v)
    all["jump"] = Sounds.jump(); all["land"] = Sounds.land(); all["pickup"] = Sounds.pickup()
    for (v in 0..2) { all["knock-$v"] = Sounds.knock(v); all["bounce-$v"] = Sounds.bounce(v); all["waves-$v"] = Sounds.waves(v) }
    for (v in 0..3) all["gull-$v"] = Sounds.gull(v)
    all["scrape"] = Sounds.scrape()
    for ((name, s) in all) s.save(dir.resolve("$name.wav"))
    println("SILLAGE_SOUNDS ${all.size} files in $dir")
}

/** A looping sound of the scene ([Scene.ambience]): its [volume] can change at any time. */
class Ambience internal constructor(volume: Float) {
    var volume: Float = volume
        set(v) { field = v.coerceAtLeast(0f) }
    internal var voice: Voice? = null
    internal var applied = volume
}
