package sillage

import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.Sphere
import kotlin.math.PI
import kotlin.math.max

// The scene's API measures as people do: whole sizes, angles in degrees, any number. The physics package
// (sillage.physics) and the maths (sillage.math) keep the units of physics engines: half sizes, radians,
// Float. These helpers cross from the first to the second, so that a game never has to.

/** A rotation of [x], then [y], then [z] degrees, as [Node.rotate] turns a node. */
fun rotation(x: Number = 0, y: Number = 0, z: Number = 0): Quat {
    val d = PI.toFloat() / 180f
    return (Quat.axisAngle(Vec3.Y, y.toFloat() * d) * Quat.axisAngle(Vec3.X, x.toFloat() * d) * Quat.axisAngle(Vec3.Z, z.toFloat() * d)).normalized()
}

/** A box [width] × [height] × [depth] metres, centred, for [Scene.collider], [Scene.zone] or `dynamic(shape = …)`. */
fun boxShape(width: Number, height: Number = width, depth: Number = width): Box {
    val w = width.toFloat(); val h = height.toFloat(); val d = depth.toFloat()
    require(w > 0f && h > 0f && d > 0f) { "A box needs positive sizes (got $width × $height × $depth)" }
    return Box(w / 2, h / 2, d / 2)
}

/** A ball of [radius] metres. */
fun sphereShape(radius: Number): Sphere {
    val r = radius.toFloat(); require(r > 0f) { "A ball needs a positive radius (got $radius)" }
    return Sphere(r)
}

/** A capsule standing on Y, [height] metres from tip to tip (as [Scene.capsule] draws one). */
fun capsuleShape(radius: Number, height: Number): Capsule {
    val r = radius.toFloat(); val h = height.toFloat()
    require(r > 0f && h > 0f) { "A capsule needs a positive radius and height (got $radius, $height)" }
    return Capsule(r, max(h / 2 - r, 0f))
}
