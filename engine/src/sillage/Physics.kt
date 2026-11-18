package sillage

import org.joml.Vector3f
import kotlin.math.*

/** Contrôleur cinématique capsule simplifiée (cercle horizontal + intervalle vertical).
 * Physique et résolution des contacts entièrement Kotlin. Pas de simulation de corps rigides. */
class Physique {
    val surfaces = ArrayList<Triangle>()
    val obstacles = ArrayList<Boite>()
    var gravite = 22f
    val pas = 1f / 120f
    private var reste = 0f

    fun hauteur(x: Float, z: Float, plafond: Float = Float.POSITIVE_INFINITY): Float? =
        surfaces.asSequence().mapNotNull { it.hauteur(x,z) }.filter { it <= plafond }.maxOrNull()

    fun avancer(joueur: Joueur, mouvement: Vector3f, saut: Boolean, dt: Float) {
        reste += dt.coerceIn(0f, 0.1f)
        // Une pression est mémorisée jusqu'au prochain pas, même à >120 FPS.
        if (saut) joueur.sautEnAttente = true
        while (reste >= pas) {
            integrer(joueur,mouvement,pas)
            reste -= pas
        }
    }

    private fun integrer(p: Joueur, mouvement: Vector3f, dt: Float) {
        if (p.auSol) p.coyote = .10f else p.coyote = max(0f,p.coyote-dt)
        if (p.sautEnAttente) p.tamponSaut = .12f
        p.sautEnAttente = false
        p.tamponSaut = max(0f,p.tamponSaut-dt)
        if(p.tamponSaut > 0 && p.coyote > 0) {
            p.vitesseY = p.impulsion; p.auSol = false; p.coyote = 0f; p.tamponSaut = 0f
        }
        val dx = mouvement.x * p.vitesse * dt
        val dz = mouvement.z * p.vitesse * dt
        fun bloque(x: Float, z: Float): Boolean {
            for (b in obstacles) {
                if(p.position.y >= b.max.y - .02f || p.position.y+p.hauteur <= b.min.y) continue
                val cx=x.coerceIn(b.min.x,b.max.x); val cz=z.coerceIn(b.min.z,b.max.z)
                if((x-cx).pow(2)+(z-cz).pow(2) < p.rayon*p.rayon) return true
            }
            // Refuse une marche trop haute et les pentes abruptes.
            val y = hauteur(x,z)
            val old = hauteur(p.position.x,p.position.z)
            return y != null && y > p.position.y + .32f && (old == null || y > old + .03f)
        }
        if(!bloque(p.position.x+dx,p.position.z)) p.position.x += dx
        if(!bloque(p.position.x,p.position.z+dz)) p.position.z += dz
        val avant = p.position.y
        p.vitesseY -= gravite*dt
        p.position.y += p.vitesseY*dt
        val sol = hauteur(p.position.x,p.position.z,avant+.32f)
        p.auSol = false
        if(sol != null && p.vitesseY <= 0 && p.position.y <= sol) {
            p.position.y = sol; p.vitesseY=0f; p.auSol=true
        }
        for(b in obstacles) {
            val dedans = p.position.x > b.min.x-p.rayon && p.position.x < b.max.x+p.rayon &&
                p.position.z > b.min.z-p.rayon && p.position.z < b.max.z+p.rayon
            if(!dedans) continue
            if(p.vitesseY > 0 && avant+p.hauteur <= b.min.y && p.position.y+p.hauteur > b.min.y) {
                p.position.y=b.min.y-p.hauteur; p.vitesseY=0f
            } else if(p.vitesseY <= 0 && avant >= b.max.y-.02f && p.position.y <= b.max.y) {
                p.position.y=b.max.y; p.vitesseY=0f; p.auSol=true
            }
        }
        if(p.position.y < -6f) p.reapparaitre()
    }

    /** Distance libre sur le segment caméra, avec marge pour son volume. */
    fun distanceCamera(cible: Vector3f, direction: Vector3f, distance: Float): Float {
        var libre = distance
        for (b in obstacles) {
            var proche = 0f; var loin = distance
            for(axis in 0..2) {
                val o=cible.get(axis); val d=direction.get(axis)
                val mn=b.min.get(axis)-.18f; val mx=b.max.get(axis)+.18f
                if(abs(d)<1e-6f) { if(o<mn || o>mx) { proche=distance+1; break } }
                else {
                    val t1=(mn-o)/d; val t2=(mx-o)/d
                    proche=max(proche,min(t1,t2)); loin=min(loin,max(t1,t2))
                }
            }
            if(proche <= loin && proche <= distance && loin >= 0) libre=min(libre,max(.35f,proche-.12f))
        }
        // Le relief, lui aussi, doit arrêter la caméra.
        var t=.3f
        while(t < libre) {
            val pos=Vector3f(direction).mul(t).add(cible)
            val terrain=hauteur(pos.x,pos.z)
            if(terrain != null && pos.y < terrain+.2f) { libre=max(.35f,t-.2f); break }
            t+=.12f
        }
        return libre
    }
}

class Joueur(val objet: Objet, depart: Vector3f) {
    val position = objet.position
    val depart = Vector3f(depart)
    var rayon=.28f
    var hauteur=1.8f
    var vitesse=4.4f
    var impulsion=8f
    var vitesseY=0f
    var auSol=false
    internal var sautEnAttente=false
    internal var coyote=0f
    internal var tamponSaut=0f
    init { reapparaitre() }
    fun reapparaitre() { position.set(depart); vitesseY=0f; auSol=false; coyote=0f; tamponSaut=0f; sautEnAttente=false }
}
