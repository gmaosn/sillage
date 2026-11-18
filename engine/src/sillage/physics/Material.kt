package sillage.physics

import sillage.math.ValueCandidate

/**
 * Physical surface of static geometry. [friction] is the sliding friction coefficient (combined with
 * the body's as √(μ₁·μ₂)); [rollingResistance] opposes rolling (the larger of surface and body wins:
 * soft grass or sand slows a ball down far more than a road); [bounce] scales the body's restitution
 * (1 keeps it, 0.5 halves it: grass and sand swallow bounces).
 */
@ValueCandidate
class Material(val friction: Float = .6f, val rollingResistance: Float = 0f, val bounce: Float = 1f) {
    init { require(friction >= 0f && rollingResistance >= 0f && bounce >= 0f) { "Material values must not be negative" } }

    override fun equals(other: Any?) = other is Material && other.friction == friction &&
        other.rollingResistance == rollingResistance && other.bounce == bounce
    override fun hashCode() = (friction.hashCode() * 31 + rollingResistance.hashCode()) * 31 + bounce.hashCode()
    override fun toString() = "Material(friction=$friction, rollingResistance=$rollingResistance, bounce=$bounce)"

    companion object {
        val DEFAULT = Material()
        /** Asphalt: grips, a ball rolls a long way. */
        val ROAD = Material(friction = .8f, rollingResistance = .015f, bounce = 1f)
        /** Lawn: a ball slows down within a few metres, bounces are damped. */
        val GRASS = Material(friction = .7f, rollingResistance = .12f, bounce = .6f)
        /** Loose sand: a ball soon stops, little bounce. */
        val SAND = Material(friction = .6f, rollingResistance = .3f, bounce = .3f)
        val WOOD = Material(friction = .45f, rollingResistance = .02f, bounce = .9f)
        val STONE = Material(friction = .7f, rollingResistance = .02f, bounce = 1f)
        /** Ice: almost no grip. */
        val ICE = Material(friction = .03f, rollingResistance = .005f, bounce = 1f)

        /** Presets by material name (English or French, as named in Blender); unknown names give [DEFAULT]. */
        fun named(name: String): Material = when (name.lowercase().substringBefore('.')) {
            "road", "asphalt", "route", "bitume", "goudron" -> ROAD
            "grass", "lawn", "herbe", "gazon", "pelouse" -> GRASS
            "sand", "sable" -> SAND
            "wood", "bois", "planche" -> WOOD
            "stone", "rock", "pierre", "roche", "rocher", "galet" -> STONE
            "ice", "glace" -> ICE
            else -> DEFAULT
        }
    }
}
