package sillage.physics

import sillage.math.Quat
import sillage.math.Vec3

/**
 * A zone of the world that notices bodies coming in and going out, without touching them: a coin to
 * pick up, a door that opens, water that slows things down. It is checked after each [PhysicsWorld.step].
 * By default only bodies that move (dynamic and kinematic, so characters too) are noticed.
 */
class Trigger internal constructor(val world: PhysicsWorld, val shape: Shape, var position: Vec3, var rotation: Quat) {
    /** Which bodies count; by default every body that is not static. */
    var filter: (Body) -> Boolean = { it.type != BodyType.STATIC }
    /** A disabled trigger notices nothing and forgets who was inside (they will enter again). */
    var enabled = true
        set(v) { field = v; if (!v) ids.clear() }
    var onEnter: ((Body) -> Unit)? = null
    var onExit: ((Body) -> Unit)? = null

    private val ids = LinkedHashSet<Int>()
    private val now = HashSet<Int>()
    private val pose = Pose()
    /** Bodies inside after the last step. */
    val inside: List<Body> get() = ids.map { Body(world, it) }

    internal fun check(entered: MutableList<() -> Unit>) {
        if (!enabled) return
        now.clear()
        pose.set(position.x, position.y, position.z, rotation.x, rotation.y, rotation.z, rotation.w)
        val touches = world.triggerScratch
        world.touching(shape, pose, 0f, -1, touches, meshes = false)
        for (k in 0 until touches.size) {
            val b = touches.body[k]
            if (b >= 0 && touches.sep[k] < 0f && b !in now && filter(world.handle(b))) now.add(b)
        }
        // (Nothing to walk through when nobody is inside: no iterator made.)
        if (now.isNotEmpty()) for (b in now) if (ids.add(b)) onEnter?.let { f -> entered.add { f(Body(world, b)) } }
        if (ids.isEmpty()) return
        val it = ids.iterator()
        while (it.hasNext()) { val b = it.next(); if (b !in now) { it.remove(); onExit?.let { f -> entered.add { f(Body(world, b)) } } } }
    }
}

/** Adds a [Trigger] zone of [shape] at [position]. */
fun PhysicsWorld.addTrigger(shape: Shape, position: Vec3, rotation: Quat = Quat.IDENTITY): Trigger =
    Trigger(this, shape, position, rotation.normalized()).also { triggers.add(it) }

fun PhysicsWorld.removeTrigger(trigger: Trigger) { triggers.remove(trigger) }
