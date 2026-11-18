package sillage

import sillage.math.Mat4
import sillage.math.Quat
import sillage.math.Vec3
import sillage.physics.BodyType
import sillage.physics.Body
import sillage.physics.Box
import sillage.physics.Capsule
import sillage.physics.PhysicsWorld
import sillage.physics.Sphere
import sillage.physics.TriangleMesh
import kotlin.math.*

@DslMarker annotation class SillageDsl

@SillageDsl
class Monde {
    val objets = ArrayList<Objet>()
    val physique = Physique()
    /** Corps rigides : caisses, ballons… Le terrain et les obstacles y sont statiques, le héros y est cinématique. */
    val corps = PhysicsWorld()
    private val lies = ArrayList<Pair<Objet, Body>>()
    private var corpsHeros: Body? = null
    lateinit var joueur: Joueur
        private set
    val coquillages = ArrayList<Objet>()
    var recoltes = 0
    val camera = Camera()
    var temps = 0f
    private val modeles = HashMap<String,Modele>()
    private val aplatis = HashMap<String,Maillage>()
    private var tempsEnLAir = 0f
    /** Vrai quand le héros se déplace réellement (pas seulement quand on appuie contre un mur). */
    var enMouvement = false
        private set

    /** Place un modèle GLB. Un modèle qui contient des animations garde ses pièces et peut être animé. */
    fun modele(glb: String, x: Float = 0f, y: Float = 0f, z: Float = 0f, taille: Float = 1f): Objet {
        val m = modeles.getOrPut(glb) { Glb.chargerModele(glb) }
        val o = if (m.clips.isEmpty()) Objet(aplatis.getOrPut(glb) { m.aplatir().also {
                require(it.sommets.isNotEmpty()) { "GLB sans triangles : $glb" } } })
            else Objet(Maillage(FloatArray(0))).also { it.parties = m.parties(); it.animateur = Animateur(m, it.parties) }
        o.position=Vec3(x,y,z); o.echelle=taille; objets.add(o)
        return o
    }
    fun terrain(glb: String): Objet = modele(glb).also {
        physique.surfaces.addAll(it.maillage.triangles)
        val v = it.maillage.sommets
        corps.addMesh(TriangleMesh(FloatArray(v.size / 3) { k -> v[(k / 3) * 9 + k % 3] }))
    }
    /** Boîte de collision. [camera] = faux pour un objet fin (tronc, tonneau) que la caméra peut longer sans se rapprocher. */
    fun obstacle(x: Float, y: Float, z: Float, largeur: Float, hauteur: Float, profondeur: Float, camera: Boolean = true) {
        physique.obstacles.add(Boite(Vec3(x-largeur/2,y,z-profondeur/2),Vec3(x+largeur/2,y+hauteur,z+profondeur/2),camera))
        corps.add(Box(largeur/2,hauteur/2,profondeur/2),Vec3(x,y+hauteur/2,z),type=BodyType.STATIC)
    }

    /** Lie un objet affiché à un corps rigide : l'objet suit le corps à chaque image. */
    fun lier(objet: Objet, corps: Body): Objet { lies.add(objet to corps); objet.position=corps.position; objet.orientation=corps.rotation; return objet }

    /** Une caisse de [taille] mètres posée sur le sol (ou [hauteur] mètres au-dessus), qu'on peut pousser. */
    fun caisse(x: Float, z: Float, hauteur: Float = 0f, taille: Float = .7f, angle: Float = 0f): Objet {
        val o = modele("models/caisse.glb", taille = taille)
        val b = corps.add(Box(taille/2,taille/2,taille/2), Vec3(x,sol(x,z)+taille/2+hauteur+.01f,z), Quat.rotationY(angle),
            mass = 40f*taille*taille*taille, friction = .7f)
        return lier(o,b)
    }

    /** Un ballon de plage léger et rebondissant. */
    fun ballon(x: Float, z: Float, hauteur: Float = 0f, rayon: Float = .35f): Objet {
        val o = modele("models/ballon.glb", taille = rayon)
        val b = corps.add(Sphere(rayon), Vec3(x,sol(x,z)+rayon+hauteur+.01f,z), mass = .6f, friction = .5f, restitution = .65f,
            rollingResistance = .05f)
        b.angularDamping = .1f
        return lier(o,b)
    }
    /** Hauteur du terrain en (x, z), 0 hors de l'île. */
    fun sol(x: Float, z: Float): Float = physique.hauteur(x,z) ?: 0f
    /** Obstacle posé sur le terrain : sa base suit le sol. */
    fun obstacleAuSol(x: Float, z: Float, largeur: Float, hauteur: Float, profondeur: Float, camera: Boolean = true) =
        obstacle(x,sol(x,z),z,largeur,hauteur,profondeur,camera)
    fun heros(glb: String, x: Float=0f, y: Float=3f, z: Float=4f, config: Joueur.() -> Unit = {}) {
        joueur=Joueur(modele(glb),Vec3(x,y,z)).apply(config)
        // Le héros pousse les corps rigides : une capsule cinématique qui suit le contrôleur de personnage.
        val j=joueur
        corpsHeros=corps.add(Capsule(j.rayon,(j.hauteur-2*j.rayon)/2),j.position.plus(0f,j.hauteur/2,0f),type=BodyType.KINEMATIC)
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
        enMouvement=vitesse>.4f
        when {
            joueur.vitesseY>0f && !joueur.auSol && "jump" in clips -> a.jouer("jump",boucle=false,fondu=.08f,vitesse=1f)
            tempsEnLAir>.12f && "fall" in clips -> a.jouer("fall",boucle=false,fondu=.2f,vitesse=1f)
            tempsEnLAir>.12f -> {}
            vitesse>.4f && "run" in clips -> a.jouer("run",fondu=.12f,vitesse=(vitesse/joueur.vitesse).coerceIn(.5f,1.4f))
            "idle" in clips -> a.jouer("idle",fondu=.2f,vitesse=1f)
        }
    }

    fun reinitialiser() {
        joueur.reapparaitre(); recoltes=0; coquillages.forEach { it.visible=true }
        corpsHeros?.position=joueur.position.plus(0f,joueur.hauteur/2,0f)
    }
    fun miseAJour(mouvement: Vec3, saut: Boolean, dt: Float) {
        temps+=dt
        val avant=joueur.position
        physique.avancer(joueur,mouvement,saut,dt)
        if(mouvement.lengthSquared()>.01f) joueur.objet.rotation=tourner(joueur.objet.rotation,atan2(-mouvement.x,-mouvement.z),14f*dt)
        animerHeros(avant,dt)
        for(o in objets) o.animateur?.avancer(dt)
        // Corps rigides : la capsule du héros rejoint sa position, puis un pas de simulation.
        val h=dt.coerceIn(1f/240f,1f/30f)
        corpsHeros?.let { c ->
            val cible=joueur.position.plus(0f,joueur.hauteur/2,0f)
            // Une réapparition téléporte la capsule : traverser l'île d'un coup balaierait les caisses.
            if(c.position.distanceTo(cible)>1f) { c.position=cible; c.velocity=Vec3.ZERO } else c.moveTo(cible,h)
        }
        corps.step(h)
        for((o,b) in lies) { o.position=b.position; o.orientation=b.rotation }
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
    /** Élévation ajoutée pour passer au-dessus du relief, lissée. */
    private var levee=0f
    /** Secondes depuis le dernier mouvement de souris : la caméra ne pivote seule que si la souris est calme. */
    private var calmeSouris=10f

    fun tourner(dx: Float, dy: Float) { azimut-=dx*.0035f; elevation=(elevation+dy*.003f).coerceIn(.08f,1.25f); calmeSouris=0f }
    fun zoom(delta: Float) { distance=(distance-delta*.6f).coerceIn(2.5f,12f) }

    private fun direction(az: Float, el: Float) = Vec3(sin(az)*cos(el),sin(el),cos(az)*cos(el))

    fun vue(monde: Monde, dt: Float): Mat4 {
        calmeSouris+=dt
        val p=monde.physique
        val cible=monde.joueur.position.plus(0f,1.1f,0f)
        // Relief derrière le héros (plage, colline) : lever la caméra plutôt que la rapprocher.
        var leveeVoulue=0f
        if(p.libreRelief(cible,direction(azimut,elevation),distance)<distance*.95f)
            leveeVoulue=(1..9).map { it*.12f }.firstOrNull { l ->
                p.libreRelief(cible,direction(azimut,(elevation+l).coerceAtMost(1.35f)),distance)>=distance*.95f } ?: 1.08f
        levee+=(leveeVoulue-levee)*(1-exp(-6f*dt))
        val el=(elevation+levee).coerceAtMost(1.35f)
        // Bâtiment entre le héros et la caméra : pivoter doucement vers la vue dégagée la plus proche,
        // seulement quand le héros avance et que la souris ne tourne pas.
        if(monde.enMouvement && calmeSouris>.4f && p.libreObstacles(cible,direction(azimut,el),distance)<distance*.6f) {
            floatArrayOf(.15f,-.15f,.3f,-.3f,.45f,-.45f,.6f,-.6f,.8f,-.8f,1f,-1f)
                .firstOrNull { p.libreObstacles(cible,direction(azimut+it,el),distance)>=distance*.9f }
                ?.let { azimut+=sign(it)*min(abs(it),2.5f*dt) }
        }
        val dir=direction(azimut,el)
        val libre=p.distanceCamera(cible,dir,distance)
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
