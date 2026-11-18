package sillage

import sillage.math.Vec3
import sillage.physics.BodyType
import sillage.physics.Character
import sillage.physics.PhysicsWorld
import sillage.physics.raycast
import kotlin.math.max

/** Étiquette des objets fins (troncs, tonneaux) que la caméra peut longer sans se rapprocher. */
internal object Mince

/**
 * Le héros : un [Character] du moteur physique, plus ce qui fait un jeu agréable — un saut demandé un
 * peu trop tôt est gardé un instant, et l'on peut encore sauter juste après avoir quitté un bord.
 */
class Joueur internal constructor(val objet: Objet, val personnage: Character, val depart: Vec3) {
    var position: Vec3
        get() = personnage.position
        set(v) { personnage.teleport(v); objet.position = v }
    val rayon get() = personnage.radius
    val hauteur get() = personnage.height
    /** Vitesse de course et de marche (touche Maj), en m/s. */
    var vitesse = 4.4f
    var vitesseMarche = 1.8f
    /** Vitesse verticale au départ d'un saut (m/s). */
    var impulsion = 8f
    val auSol get() = personnage.onGround
    val vitesseY get() = personnage.velocity.y
    private var coyote = 0f
    private var tampon = 0f

    fun reapparaitre() { position = depart; coyote = 0f; tampon = 0f }

    internal fun avancer(mouvement: Vec3, saut: Boolean, marche: Boolean, dt: Float) {
        if (saut) tampon = .12f
        coyote = if (personnage.onGround) .1f else max(0f, coyote - dt)
        var elan = 0f
        if (tampon > 0f && coyote > 0f) { elan = impulsion; tampon = 0f; coyote = 0f }
        tampon = max(0f, tampon - dt)
        personnage.update(mouvement * (if (marche) vitesseMarche else vitesse), elan, dt)
        objet.position = personnage.position
        if (personnage.position.y < -6f) reapparaitre()
    }
}

// ------------------------------------------------------------------ caméra et monde physique

/** Distance libre jusqu'au relief (maillages statiques). */
internal fun PhysicsWorld.libreRelief(cible: Vec3, dir: Vec3, distance: Float): Float {
    val hit = raycast(cible, dir, distance, filter = { false }) ?: return distance
    return max(.35f, hit.distance - .3f)
}

/** Distance libre jusqu'aux bâtiments (boîtes statiques qui ne sont pas fines), avec la marge du volume de la caméra. */
internal fun PhysicsWorld.libreObstacles(cible: Vec3, dir: Vec3, distance: Float): Float {
    val hit = raycast(cible, dir, distance, radius = .18f, meshes = false,
        filter = { it.type == BodyType.STATIC && it.tag !== Mince }) ?: return distance
    return max(.35f, hit.distance - .12f)
}

/**
 * Distance de la caméra : les bâtiments et le relief la rapprochent ; un objet fin ne la gêne que si
 * elle tomberait dedans, elle recule alors juste derrière lui (au plus un mètre de plus).
 */
internal fun PhysicsWorld.distanceCamera(cible: Vec3, dir: Vec3, distance: Float): Float {
    var voulu = distance
    val mince: (sillage.physics.Body) -> Boolean = { it.tag === Mince }
    val bout = cible + dir * distance
    if (raycast(bout, dir, .01f, radius = .18f, meshes = false, filter = mince)?.distance == 0f) {
        // Le point visé est dans un tronc : chercher sa sortie en revenant depuis un mètre plus loin.
        val sortie = raycast(bout + dir, -dir, 1f, radius = .18f, meshes = false, filter = mince)
        if (sortie != null && sortie.distance > 0f) voulu = distance + (1f - sortie.distance) + .12f
    }
    return minOf(libreObstacles(cible, dir, voulu), libreRelief(cible, dir, voulu))
}
