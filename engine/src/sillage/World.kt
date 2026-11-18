package sillage

import org.joml.*
import kotlin.math.*

@DslMarker annotation class SillageDsl

@SillageDsl
class Monde {
    val objets = ArrayList<Objet>()
    val physique = Physique()
    lateinit var joueur: Joueur
        private set
    val coquillages = ArrayList<Objet>()
    var recoltes = 0
    val camera = Camera()
    var temps = 0f
    private val cache = HashMap<String,Maillage>()

    fun modele(glb: String, x: Float = 0f, y: Float = 0f, z: Float = 0f, taille: Float = 1f): Objet {
        val o = Objet(cache.getOrPut(glb) { Glb.charger(glb) })
        o.position.set(x,y,z); o.echelle=taille; objets.add(o)
        return o
    }
    fun terrain(glb: String): Objet = modele(glb).also { physique.surfaces.addAll(it.maillage.triangles) }
    fun obstacle(x: Float, y: Float, z: Float, largeur: Float, hauteur: Float, profondeur: Float) {
        physique.obstacles.add(Boite(Vector3f(x-largeur/2,y,z-profondeur/2),Vector3f(x+largeur/2,y+hauteur,z+profondeur/2)))
    }
    fun heros(glb: String, x: Float=0f, y: Float=3f, z: Float=4f, config: Joueur.() -> Unit = {}) {
        joueur=Joueur(modele(glb),Vector3f(x,y,z)).apply(config)
    }
    fun ocean() {
        val g=Geometrie()
        g.quad(Vector3f(-150f,-.32f,-150f),Vector3f(-150f,-.32f,150f),Vector3f(150f,-.32f,150f),Vector3f(150f,-.32f,-150f),Couleur.hex(0x148cab))
        // Taches d'écume graphiques : une mer en aplats.
        for(i in -18..18) for(j in -18..18) {
            val x=i*3.7f+sin(j*4f)*1.4f; val z=j*3.4f
            val r=sqrt(x*x+z*z)
            if(r < 13f) continue
            g.quad(Vector3f(x,-.30f,z),Vector3f(x,-.30f,z+.07f),Vector3f(x+.9f,-.30f,z+.07f),Vector3f(x+.9f,-.30f,z),Couleur.hex(0x63c4cd))
        }
        objets.add(Objet(g.construire()))
        // Liseré du rivage, sous la plage, sans cacher le terrain.
        val foam=Geometrie(); foam.disque(0f,-.22f,0f,12.5f,Couleur.hex(0x94dfce),96)
        objets.add(Objet(foam.construire()).also { it.echelle=1f })
    }
    fun coquillage(x: Float, z: Float) {
        val g=Geometrie(); val color=Couleur.hex(0xffcb66)
        val top=Vector3f(0f,.34f,0f); val bottom=Vector3f(0f,-.24f,0f)
        val ring=Array(6) { i -> val a=i*PI.toFloat()/3; Vector3f(cos(a)*.22f,0f,sin(a)*.22f) }
        for(i in 0..5) { g.triangle(top,ring[i],ring[(i+1)%6],color); g.triangle(bottom,ring[(i+1)%6],ring[i],color) }
        val o=Objet(g.construire()); o.position.set(x,(physique.hauteur(x,z) ?: 1f)+.65f,z)
        objets.add(o); coquillages.add(o)
    }
    fun reinitialiser() { joueur.reapparaitre(); recoltes=0; coquillages.forEach { it.visible=true } }
    fun miseAJour(mouvement: Vector3f, saut: Boolean, dt: Float) {
        temps+=dt
        physique.avancer(joueur,mouvement,saut,dt)
        if(mouvement.lengthSquared()>.01f) joueur.objet.rotation=atan2(-mouvement.x,-mouvement.z)
        coquillages.forEachIndexed { i,o ->
            o.rotation=temps*1.1f+i
            if(o.visible && joueur.position.distance(o.position)<1.15f) { o.visible=false; recoltes++; println("Coquillages : " + recoltes + "/" + coquillages.size) }
        }
    }
}

class Camera {
    var azimut=0f
    var elevation=.38f
    var distance=7f
    val position=Vector3f(0f,5f,10f)
    fun tourner(dx: Float, dy: Float) { azimut-=dx*.0035f; elevation=(elevation+dy*.003f).coerceIn(.08f,1.25f) }
    fun zoom(delta: Float) { distance=(distance-delta*.6f).coerceIn(2.5f,12f) }
    fun vue(monde: Monde, dt: Float): Matrix4f {
        val cible=Vector3f(monde.joueur.position).add(0f,1.1f,0f)
        val dir=Vector3f(sin(azimut)*cos(elevation),sin(elevation),cos(azimut)*cos(elevation))
        val libre=monde.physique.distanceCamera(cible,dir,distance)
        val desired=Vector3f(dir).mul(libre).add(cible)
        // Couper immédiatement un obstacle, lisser seulement le retour.
        if(position.distance(cible)>libre) position.set(desired)
        else position.lerp(desired,1-exp(-12f*dt))
        return Matrix4f().lookAt(position,cible,Vector3f(0f,1f,0f))
    }
    fun mouvement(x: Float, z: Float): Vector3f {
        val m=Vector3f(cos(azimut)*x-sin(azimut)*z,0f,-sin(azimut)*x-cos(azimut)*z)
        if(m.lengthSquared()>1f) m.normalize()
        return m
    }
}

fun jeu(titre: String = "Sillage", args: Array<String> = emptyArray(), construire: Monde.() -> Unit) {
    val monde=Monde().apply(construire)
    Game(titre,monde,args).run()
}
