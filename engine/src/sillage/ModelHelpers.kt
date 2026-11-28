package sillage

import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.Material
import sillage.physics.Shape
import sillage.physics.Sphere
import sillage.physics.TriangleMesh
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * Blender as the scene's editor: the helper objects of a model ([Role]) become what they stand for,
 * where the model is when the scene starts (its first update): collision volumes, the player's start,
 * lamps, separate objects that move or that a player picks up, trigger zones.
 */
internal class ModelHelpers(private val scene: Scene) {
    /** Model nodes whose helpers wait for the scene's first update. */
    val pending = ArrayList<Node>()
    private val zones = LinkedHashMap<String, MutableList<Zone>>()
    private val enterActions = ArrayList<Pair<String, (Node) -> Unit>>()
    private val exitActions = ArrayList<Pair<String, (Node) -> Unit>>()
    private val pickupActions = ArrayList<Pair<String, (Player) -> Unit>>()

    /** A place in the scene: position, rotation, scale along each axis. */
    private class Place(val position: Vec3, val rotation: Quat, val scale: Vec3)

    private fun place(m: Mat4): Place {
        val x = m.transformDirection(Vec3.X); val y0 = m.transformDirection(Vec3.Y); val z0 = m.transformDirection(Vec3.Z)
        val sx = x.length(); val sy = y0.length(); val sz = z0.length()
        val ax = if (sx > 1e-8f) x * (1f / sx) else Vec3.X
        var ay = y0 - ax * (y0 dot ax); ay = if (ay.length() > 1e-8f) ay.normalized() else Vec3.Y
        val az = ax cross ay
        // A mirrored object (negative scale) keeps a right-handed rotation; the sign goes to its Z scale.
        val flip = (az dot z0) < 0f
        return Place(m.transformPoint(Vec3.ZERO), Quat.fromAxes(ax, ay, az), Vec3(sx, sy, if (flip) -sz else sz))
    }

    /** Where the model of [node] puts its helper [i] now. */
    private fun placeOf(node: Node, i: Int): Place = place(node.matrix() * node.model!!.restPlace(i))

    /** The player's start from the first model that has one: position and yaw in degrees, or null. */
    fun spawn(): Pair<Vec3, Float>? {
        for (node in pending) {
            val m = node.model ?: continue
            for (i in m.helpers) if (m.nodes[i].role == Role.SPAWN) {
                val p = placeOf(node, i)
                val f = p.rotation.rotate(Vec3(0f, 0f, -1f))
                return p.position to sillage.math.toDegrees(atan2(-f.x, -f.z).toDouble()).toFloat()
            }
        }
        return null
    }

    /** Places every waiting model's helpers. */
    fun settle() {
        val nodes = ArrayList(pending); pending.clear()
        for (node in nodes) {
            if (node.removed) continue
            val m = node.model ?: continue
            for (i in m.helpers) settle(node, m, i)
        }
    }

    private fun number(props: Map<String, Any?>, key: String): Float? = when (val v = props[key]) {
        is Number -> v.toFloat(); is String -> v.toFloatOrNull(); else -> null
    }

    private fun flag(props: Map<String, Any?>, key: String): Boolean? = when (val v = props[key]) {
        is Boolean -> v; is Number -> v.toDouble() != 0.0; is String -> v.lowercase() in setOf("true", "yes", "1", "oui"); else -> null
    }

    /** The name without Blender's ".001" and without the role's prefix ("zone_door.001" → "door"). */
    private fun baseName(name: String?, prefix: String): String =
        (name ?: "").substringBefore('.').let { if (it.lowercase().startsWith(prefix)) it.substring(prefix.length) else it }

    /** The shape asked for by the `shape` property, or by the name (col_sphere_…, col_capsule_…, col_mesh_…). */
    private fun shapeKind(name: String?, props: Map<String, Any?>): String {
        (props["shape"] as? String)?.let { return it.lowercase() }
        val n = (name ?: "").lowercase()
        for (k in listOf("sphere", "ball", "capsule", "mesh", "hull", "box")) if (n.startsWith("col_${k}") || n.startsWith("zone_${k}")) return k
        return "box"
    }

    /** A shape fitted to helper [i]'s own vertices (an empty: Blender's cube of side 2), its centre in the helper's space, scaled. */
    private fun fitted(m: Model, i: Int, kind: String, scale: Vec3): Pair<Shape, Vec3> {
        val pts = m.localPoints(i)
        var lo = Vec3(-1f, -1f, -1f); var hi = Vec3(1f, 1f, 1f)
        if (pts.isNotEmpty()) {
            var ax = Float.MAX_VALUE; var ay = Float.MAX_VALUE; var az = Float.MAX_VALUE
            var bx = -Float.MAX_VALUE; var by = -Float.MAX_VALUE; var bz = -Float.MAX_VALUE
            for (k in pts.indices step 3) {
                ax = minOf(ax, pts[k]); ay = minOf(ay, pts[k + 1]); az = minOf(az, pts[k + 2])
                bx = maxOf(bx, pts[k]); by = maxOf(by, pts[k + 1]); bz = maxOf(bz, pts[k + 2])
            }
            lo = Vec3(ax, ay, az); hi = Vec3(bx, by, bz)
        }
        val s = Vec3(abs(scale.x), abs(scale.y), abs(scale.z))
        val half = Vec3((hi.x - lo.x) / 2 * s.x, (hi.y - lo.y) / 2 * s.y, (hi.z - lo.z) / 2 * s.z)
        val centre = Vec3((hi.x + lo.x) / 2 * scale.x, (hi.y + lo.y) / 2 * scale.y, (hi.z + lo.z) / 2 * scale.z)
        val h = Vec3(max(half.x, .005f), max(half.y, .005f), max(half.z, .005f))
        if (kind == "hull" && pts.size >= 12) {
            // Its own points, scaled as it stands; the hull sits on its centre of mass.
            val scaled = FloatArray(pts.size) { k -> pts[k] * when (k % 3) { 0 -> scale.x; 1 -> scale.y; else -> scale.z } }
            val hull = sillage.physics.ConvexHull(scaled)
            return hull to hull.centre
        }
        val shape: Shape = when (kind) {
            "sphere", "ball" -> Sphere(max(h.x, max(h.y, h.z)))
            "capsule" -> { val r = max(h.x, h.z); Capsule(r, max(h.y - r, 0f)) }
            else -> Box(h.x, h.y, h.z)
        }
        return shape to centre
    }

    /** The nearest ancestor of [i] drawn as a moving part of an animated model, or null. */
    private fun movingAncestor(node: Node, m: Model, i: Int): String? {
        val a = node.animator ?: return null
        var j = m.nodes[i].parent
        while (j >= 0) {
            val n = m.nodes[j].name
            if (n != null && a.nodeIndex(n) >= 0) return n
            j = m.nodes[j].parent
        }
        return null
    }

    private fun settle(node: Node, m: Model, i: Int) {
        val n = m.nodes[i]
        val props = n.properties
        val p = placeOf(node, i)
        when (n.role) {
            Role.COLLIDER -> {
                val kind = shapeKind(n.name, props)
                val surface = (props["surface"] as? String)?.lowercase() ?: "stone"
                val blocksCamera = flag(props, "camera") ?: true
                if (kind == "mesh") {
                    // Its triangles, as they stand in the scene, become a static mesh.
                    val pts = m.localPoints(i)
                    val w = node.matrix() * m.restPlace(i)
                    val out = FloatArray(pts.size)
                    for (k in pts.indices step 3) { val q = w.transformPoint(Vec3(pts[k], pts[k + 1], pts[k + 2])); out[k] = q.x; out[k + 1] = q.y; out[k + 2] = q.z }
                    if (w.determinant3x3() < 0) for (t in out.indices step 9) for (c in 0..2) { val a = out[t + 3 + c]; out[t + 3 + c] = out[t + 6 + c]; out[t + 6 + c] = a }
                    scene.physics.addMesh(TriangleMesh(out, Material.named(surface)))
                    return
                }
                val (shape, centre) = fitted(m, i, kind, p.scale)
                val part = movingAncestor(node, m, i)
                if (part != null) {
                    // It follows the part: its place relative to that part at rest.
                    val partIndex = m.nodes.indexOfFirst { it.name == part }
                    val rel = place(m.restPlace(partIndex).inverseAffine() * m.restPlace(i))
                    // In the part's own space: partPose applies the part's and the node's place (scale included).
                    val offset = rel.position + rel.rotation.rotate(Vec3(centre.x / max(abs(p.scale.x), 1e-6f) * rel.scale.x,
                        centre.y / max(abs(p.scale.y), 1e-6f) * rel.scale.y, centre.z / max(abs(p.scale.z), 1e-6f) * rel.scale.z))
                    scene.addCollider(node, shape, offset, part, surface, blocksCamera, rel.rotation)
                } else {
                    scene.collider(shape, p.position + p.rotation.rotate(centre), p.rotation, blocksCamera, surface).tag = node
                }
            }
            Role.SPAWN -> {}                              // read by player()
            Role.LAMP -> {
                val l = n.light ?: return
                if (l.type == "directional") return       // the scene has its sun
                val intensity = number(props, "intensity") ?: (l.intensity / 36.2f).coerceIn(.05f, 20f)
                val radius = number(props, "radius") ?: l.range ?: 6f
                scene.light(l.color, intensity, radius, p.position, flag(props, "night") ?: false, number(props, "flicker") ?: 0f)
            }
            Role.DYNAMIC, Role.PICKUP -> {
                val mesh = m.subtreeMesh(i)
                if (mesh.triangleCount == 0) return
                val obj = Node(scene, mesh)
                obj.name = baseName(n.name, if (n.role == Role.PICKUP) "pickup_" else "")
                obj.rotation = p.rotation
                scene.add(obj, p.position)
                if (n.role == Role.DYNAMIC) {
                    val kind = shapeKind(n.name, props)
                    val shape = if (props.containsKey("shape") || kind != "box") fitted(m, i, kind, p.scale).first else null
                    obj.dynamic(mass = number(props, "mass"), density = number(props, "density") ?: 400f, bounce = number(props, "bounce") ?: 0f,
                        friction = number(props, "friction") ?: .6f, shape = shape, surface = (props["surface"] as? String)?.lowercase() ?: "wood")
                } else {
                    val pick = scene.pickup(obj, radius = number(props, "radius") ?: .8f)
                    val key = obj.name ?: ""
                    pick.onPickup = { who -> for ((name, a) in pickupActions) if (name == key) a(who) }
                }
            }
            Role.ZONE -> {
                val (shape, centre) = fitted(m, i, shapeKind(n.name, props), p.scale)
                val key = baseName(n.name, "zone_")
                val z = scene.zone(shape, p.position + p.rotation.rotate(centre))
                z.trigger.rotation = p.rotation
                z.name = key
                z.onEnter = { who -> for ((name, a) in enterActions) if (name == key) a(who) }
                z.onExit = { who -> for ((name, a) in exitActions) if (name == key) a(who) }
                zones.getOrPut(key) { ArrayList() }.add(z)
            }
            Role.DRAWN -> {}
        }
    }

    fun onEnter(zone: String, action: (Node) -> Unit) { enterActions.add(zone to action) }
    fun onExit(zone: String, action: (Node) -> Unit) { exitActions.add(zone to action) }
    fun onPickup(name: String, action: (Player) -> Unit) { pickupActions.add(name to action) }
    fun zone(name: String): Zone? = zones[name]?.firstOrNull()
}
