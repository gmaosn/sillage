package sillage

import sillage.math.Mat4
import sillage.math.Vec3
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
    private val modeles = HashMap<String,Modele>()
    private val aplatis = HashMap<String,Maillage>()
    private var tempsEnLAir = 0f

    /** Place un modèle GLB. Un modèle qui contient des animations garde ses pièces et peut être animé. */
    fun modele(glb: String, x: Float = 0f, y: Float = 0f, z: Float = 0f, taille: Float = 1f): Objet {
        val m = modeles.getOrPut(glb) { Glb.chargerModele(glb) }
        val o = if (m.clips.isEmpty()) Objet(aplatis.getOrPut(glb) { m.aplatir().also {
                require(it.sommets.isNotEmpty()) { "GLB sans triangles : $glb" } } })
            else Objet(Maillage(FloatArray(0))).also { it.parties = m.parties(); it.animateur = Animateur(m, it.parties) }
        o.position=Vec3(x,y,z); o.echelle=taille; objets.add(o)
        return o
    }
    fun terrain(glb: String): Objet = modele(glb).also { physique.surfaces.addAll(it.maillage.triangles) }
    fun obstacle(x: Float, y: Float, z: Float, largeur: Float, hauteur: Float, profondeur: Float) {
        physique.obstacles.add(Boite(Vec3(x-largeur/2,y,z-profondeur/2),Vec3(x+largeur/2,y+hauteur,z+profondeur/2)))
    }
    /** Hauteur du terrain en (x, z), 0 hors de l'île. */
    fun sol(x: Float, z: Float): Float = physique.hauteur(x,z) ?: 0f
    /** Obstacle posé sur le terrain : sa base suit le sol. */
    fun obstacleAuSol(x: Float, z: Float, largeur: Float, hauteur: Float, profondeur: Float) =
        obstacle(x,sol(x,z),z,largeur,hauteur,profondeur)
    fun heros(glb: String, x: Float=0f, y: Float=3f, z: Float=4f, config: Joueur.() -> Unit = {}) {
        joueur=Joueur(modele(glb),Vec3(x,y,z)).apply(config)
    }
    fun ocean() {
        val g=Geometrie()
        g.quad(Vec3(-150f,-.32f,-150f),Vec3(-150f,-.32f,150f),Vec3(150f,-.32f,150f),Vec3(150f,-.32f,-150f),Couleur.hex(0x148cab))
        // Taches d'écume graphiques : une mer en aplats.
        for(i in -18..18) for(j in -18..18) {
            val x=i*3.7f+sin(j*4f)*1.4f; val z=j*3.4f
            val r=sqrt(x*x+z*z)
            if(r < 13f) continue
            g.quad(Vec3(x,-.30f,z),Vec3(x,-.30f,z+.07f),Vec3(x+.9f,-.30f,z+.07f),Vec3(x+.9f,-.30f,z),Couleur.hex(0x63c4cd))
        }
        objets.add(Objet(g.construire()))
        // Liseré du rivage, sous la plage, sans cacher le terrain.
        val foam=Geometrie(); foam.disque(0f,-.22f,0f,12.5f,Couleur.hex(0x94dfce),96)
        objets.add(Objet(foam.construire()).also { it.echelle=1f })
    }
    fun coquillage(x: Float, z: Float) {
        val g=Geometrie(); val color=Couleur.hex(0xffcb66)
        val top=Vec3(0f,.34f,0f); val bottom=Vec3(0f,-.24f,0f)
        val ring=Array(6) { i -> val a=i*PI.toFloat()/3; Vec3(cos(a)*.22f,0f,sin(a)*.22f) }
        for(i in 0..5) { g.triangle(top,ring[i],ring[(i+1)%6],color); g.triangle(bottom,ring[(i+1)%6],ring[i],color) }
        val o=Objet(g.construire()); o.position=Vec3(x,(physique.hauteur(x,z) ?: 1f)+.65f,z)
        objets.add(o); coquillages.add(o)
    }
    /** Rapproche l'angle [de] de [vers] d'au plus [pas] radians, par le plus court chemin. */
    private fun tourner(de: Float, vers: Float, pas: Float): Float {
        var d=(vers-de)%(2*PI.toFloat())
        if(d>PI) d-=2*PI.toFloat() else if(d< -PI) d+=2*PI.toFloat()
        return if(abs(d)<=pas) vers else de+sign(d)*pas
    }

    /** Choisit le clip du héros (idle, run, jump, fall) d'après son mouvement réel. */
    private fun animerHeros(avant: Vec3, dt: Float) {
        val a=joueur.objet.animateur ?: return
        val clips=a.modele.clips
        tempsEnLAir=if(joueur.auSol) 0f else tempsEnLAir+dt
        val dx=joueur.position.x-avant.x; val dz=joueur.position.z-avant.z
        val vitesse=if(dt>0f) sqrt(dx*dx+dz*dz)/dt else 0f
        when {
            joueur.vitesseY>0f && !joueur.auSol && "jump" in clips -> a.jouer("jump",boucle=false,fondu=.08f,vitesse=1f)
            tempsEnLAir>.12f && "fall" in clips -> a.jouer("fall",boucle=false,fondu=.2f,vitesse=1f)
            tempsEnLAir>.12f -> {}
            vitesse>.4f && "run" in clips -> a.jouer("run",fondu=.12f,vitesse=(vitesse/joueur.vitesse).coerceIn(.5f,1.4f))
            "idle" in clips -> a.jouer("idle",fondu=.2f,vitesse=1f)
        }
    }

    fun reinitialiser() { joueur.reapparaitre(); recoltes=0; coquillages.forEach { it.visible=true } }
    fun miseAJour(mouvement: Vec3, saut: Boolean, dt: Float) {
        temps+=dt
        val avant=joueur.position
        physique.avancer(joueur,mouvement,saut,dt)
        if(mouvement.lengthSquared()>.01f) joueur.objet.rotation=tourner(joueur.objet.rotation,atan2(-mouvement.x,-mouvement.z),14f*dt)
        animerHeros(avant,dt)
        for(o in objets) o.animateur?.avancer(dt)
        coquillages.forEachIndexed { i,o ->
            o.rotation=temps*1.1f+i
            if(o.visible && joueur.position.distanceTo(o.position)<1.15f) { o.visible=false; recoltes++; println("Coquillages : " + recoltes + "/" + coquillages.size) }
        }
    }
}

class Camera {
    var azimut=0f
    var elevation=.38f
    var distance=7f
    var position=Vec3(0f,5f,10f)
    fun tourner(dx: Float, dy: Float) { azimut-=dx*.0035f; elevation=(elevation+dy*.003f).coerceIn(.08f,1.25f) }
    fun zoom(delta: Float) { distance=(distance-delta*.6f).coerceIn(2.5f,12f) }
    fun vue(monde: Monde, dt: Float): Mat4 {
        val cible=monde.joueur.position.plus(0f,1.1f,0f)
        val dir=Vec3(sin(azimut)*cos(elevation),sin(elevation),cos(azimut)*cos(elevation))
        val libre=monde.physique.distanceCamera(cible,dir,distance)
        val desired=dir*libre+cible
        // Couper immédiatement un obstacle, lisser seulement le retour.
        position = if(position.distanceTo(cible)>libre) desired else position.lerp(desired,1-exp(-12f*dt))
        return Mat4.lookAt(position,cible,Vec3.UP)
    }
    fun mouvement(x: Float, z: Float): Vec3 {
        val m=Vec3(cos(azimut)*x-sin(azimut)*z,0f,-sin(azimut)*x-cos(azimut)*z)
        return if(m.lengthSquared()>1f) m.normalized() else m
    }
}

fun jeu(titre: String = "Sillage", args: Array<String> = emptyArray(), construire: Monde.() -> Unit) {
    val monde=Monde().apply(construire)
    Game(titre,monde,args).run()
}
