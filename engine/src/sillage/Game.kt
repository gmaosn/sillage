package sillage

import sillage.audio.Audio
import sillage.audio.Sound
import sillage.math.Mat4
import sillage.math.Vec3
import sillage.platform.Key
import sillage.platform.Window
import sillage.platform.openWindow
import java.nio.file.Path
import kotlin.math.*

/** Ce qu'a donné une partie : images, temps par image (calcul et rendu), position finale du héros. */
class Bilan(val images: Int, val moyenneMs: Double, val p99Ms: Double, val maxMs: Double, val position: Vec3)

internal class Game(private val titre: String,private val monde: Monde,private val args: Array<String>) {
    fun run(): Bilan {
        if("--help" in args) {
            println("Sillage : --headless --smoke --frames N --capture chemin.png --look-at ex,ey,ez,cx,cy,cz --anim clip --validation")
            println("  son : --mute --audio-device NOM --record-audio fichier.wav (sans fenêtre) --export-sounds dossier")
            println("  --taille LxH : taille de l'image sans fenêtre (1280x800 par défaut)")
            println("  --parcours \"x,z x,z j m p2 …\" : le héros suit ces points (j saute, m marche/court, pN attend N s)")
            return Bilan(0,0.0,0.0,0.0,Vec3.ZERO)
        }
        val headless="--headless" in args
        val smoke="--smoke" in args
        fun option(flag: String): String? {
            val i=args.indexOf(flag)
            if(i<0) return null
            require(i+1<args.size) { "Valeur manquante : " + flag }
            return args[i+1]
        }
        val frames=option("--frames")?.toInt()?.also { require(it>0) } ?: if(headless || smoke) 180 else Int.MAX_VALUE
        val screenshot=option("--capture")?.let { Path.of(it) }
        // Caméra fixe pour les captures : œil puis cible. Les déplacements restent relatifs à la caméra de jeu.
        option("--anim")?.let { monde.animationForcee=it }
        val lookAt=option("--look-at")?.split(',')?.map { it.trim().toFloat() }?.also { require(it.size==6) { "--look-at attend 6 nombres" } }
        option("--export-sounds")?.let { exporterSons(Path.of(it)); return Bilan(0,0.0,0.0,0.0,Vec3.ZERO) }
        val taille=option("--taille")?.split('x')?.map { it.trim().toInt() }?.also { require(it.size==2 && it.all { v -> v in 1..8192 }) { "--taille attend LxH" } }
        val tempsImages=ArrayList<Double>()
        val enregistrement=option("--record-audio")?.let { Path.of(it) }
        val parcours=option("--parcours")?.let { Parcours(it) }
        val trace="--trace" in args
        val pasTrace=option("--trace-pas")?.toInt() ?: 30
        // Sans fenêtre, le son est mixé image par image (et peut être enregistré) ; rien ne sort des haut-parleurs.
        val audio: Audio?=when {
            "--mute" in args -> null
            headless -> Audio.offline()
            else -> Audio.open(option("--audio-device") ?: "default")
        }
        monde.audio=audio
        val rate=audio?.mixer?.rate ?: 48_000
        val bloc=FloatArray(2*512)
        var enregistre=FloatArray(if(enregistrement!=null) 2*rate*10 else 0); var enregistres=0
        var reste=0f
        fun mixerHorsLigne(dt: Float) {
            val a=audio ?: return
            reste+=dt*rate
            var n=reste.toInt(); reste-=n
            while(n>0) {
                val k=min(n,512); a.mixer.render(bloc,k); n-=k
                if(enregistrement!=null) {
                    if(enregistres+2*k>enregistre.size) enregistre=enregistre.copyOf(enregistre.size*2)
                    System.arraycopy(bloc,0,enregistre,enregistres,2*k); enregistres+=2*k
                }
            }
        }
        var window: Window?=null
        var renderer: VulkanRenderer?=null
        try {
            audio?.device?.let { println("SILLAGE_AUDIO device="+it+" latence="+(audio.latency*1000).roundToInt()+" ms") }
            if(!headless) window=openWindow(titre+" - Les Alizés",1280,800)
            val r=VulkanRenderer(window,"--validation" in args,taille?.get(0) ?: 1280,taille?.get(1) ?: 800)
            renderer=r; r.ouvrir()
            window?.mouseCaptured=true
            var last=System.nanoTime()
            var frame=0; var fps=60; var fpsFrames=0; var fpsTime=0f
            var oldJump=false; var oldTab=false; var oldReset=false; var oldMute=false
            var highest=monde.joueur.position.y
            var lowest=highest
            var moved=false
            val initial=monde.joueur.position
            while(frame<frames && (window==null || !window.shouldClose) && parcours?.fini!=true) {
                if(window!=null) {
                    window.poll()
                    if(window.isDown(Key.ESCAPE)) break
                    if(window.consumeResize() && !r.recreateSwapchain()) { window.waitEvents(.05); continue }
                    // Mise en pause sans focus, mais jamais avant la première image : sous Wayland, la fenêtre n'existe qu'après.
                    if(!window.focused && !smoke && frame>0) { window.waitEvents(.05); last=System.nanoTime(); continue }
                    val (dx,dy)=window.consumeMouseDelta().let { it[0] to it[1] }
                    if(dx!=0f || dy!=0f) monde.camera.tourner(dx,dy)
                    window.consumeScroll().let { if(it!=0f) monde.camera.zoom(it) }
                }
                val now=System.nanoTime()
                val debutImage=now
                val dt=if(smoke || headless) 1f/60f else ((now-last)/1e9).toFloat().coerceIn(0.0001f,.1f)
                last=now
                // Touches par position physique : ZQSD en AZERTY et WASD en QWERTY sont les mêmes touches.
                fun down(vararg keys: Int)=window!=null && keys.any { window.isDown(it) }
                val tab=down(Key.TAB)
                if(tab && !oldTab) window!!.mouseCaptured=!window.mouseCaptured
                oldTab=tab
                val mute=down(Key.M,Key.M_AZERTY)
                if(mute && !oldMute) audio?.let { it.volume=if(it.volume>0f) 0f else 1f }
                oldMute=mute
                val reset=down(Key.R)
                if(reset && !oldReset) monde.reinitialiser()
                oldReset=reset
                var jump=down(Key.SPACE)
                var marche=down(Key.SHIFT,Key.RIGHT_SHIFT)
                val movement=if(parcours!=null) {
                    val (m,saut,lent)=parcours.suivre(monde.joueur.position,dt); jump=saut; marche=lent; m
                } else if(smoke) {
                    jump=frame==40
                    if(frame in 20..110) monde.camera.mouvement(1f,0f) else Vec3.ZERO
                } else monde.camera.mouvement(
                    (if(down(Key.D,Key.RIGHT)) 1f else 0f)-(if(down(Key.A,Key.LEFT)) 1f else 0f),
                    (if(down(Key.W,Key.UP)) 1f else 0f)-(if(down(Key.S,Key.DOWN)) 1f else 0f))
                monde.miseAJour(movement,jump && !oldJump,dt,marche=marche); oldJump=jump
                if(monde.joueur.position.distanceTo(initial)>.1f) moved=true
                highest=max(highest,monde.joueur.position.y); lowest=min(lowest,monde.joueur.position.y)
                if(headless) mixerHorsLigne(dt)
                if(trace && frame%pasTrace==0) monde.joueur.let { j -> println("SILLAGE_TRACE t="+"%.1f".format(monde.temps)+" pieds="+"(%.2f, %.2f, %.2f)".format(j.position.x,j.position.y,j.position.z)+" sol="+j.auSol+" sur="+(j.personnage.groundMaterial?.name ?: j.personnage.groundBody?.let { "corps "+it.id } ?: "-")+" anim="+j.objet.animateur?.animation) }
                val view=monde.camera.vue(monde,dt).let { v -> lookAt?.let { Mat4.lookAt(Vec3(it[0],it[1],it[2]),Vec3(it[3],it[4],it[5])) } ?: v }
                val oeil=lookAt?.let { Vec3(it[0],it[1],it[2]) } ?: monde.camera.position
                monde.ciel.position=oeil
                val projection=Mat4.perspective(Math.toRadians(58.0).toFloat(),r.largeur.toFloat()/r.hauteur,.08f,200f)
                fpsFrames++; fpsTime+=dt
                if(fpsTime>=.5f) { fps=(fpsFrames/fpsTime).roundToInt(); fpsTime=0f; fpsFrames=0 }
                val capture=if(frame==frames-1 || parcours?.fini==true) screenshot else null
                r.dessiner(monde.objets,projection*view,Hud.construire(monde,fps,window?.mouseCaptured ?: false),capture,oeil,monde.temps)
                tempsImages.add((System.nanoTime()-debutImage)/1e6)
                frame++
                if(window!=null && frame%30==0) window.title=titre+" | "+monde.recoltes+"/"+monde.coquillages.size+" coquillages | "+fps+" FPS"
            }
            if(smoke && parcours==null) {
                check(frame==frames) { "Démo interrompue avant la fin du smoke test" }
                check(moved) { "Le personnage n'a pas bougé" }
                check(highest-lowest>.5f) { "Le saut ou la gravité n'a pas été exercé" }
                check(monde.joueur.position.isFinite()) { "Position physique non finie" }
            }
            check(r.erreursValidation==0) { "Erreurs de validation Vulkan : " + r.erreursValidation }
            println("SILLAGE_RUN_OK frames="+frame+" smoke="+smoke+" position="+monde.joueur.position+" sol="+monde.joueur.auSol)
            if(System.getenv("SILLAGE_TEMPS")!=null) println("SILLAGE_TEMPS "+tempsImages.withIndex().sortedByDescending { it.value }.take(12).joinToString { "#"+it.index+"="+"%.1f".format(it.value) })
            val tri=tempsImages.drop(min(60,tempsImages.size/4)).sorted()        // sans les premières images (compilation)
            return Bilan(frame,if(tri.isEmpty()) 0.0 else tri.average(),tri.getOrElse((tri.size*.99).toInt().coerceAtMost(tri.size-1)) { 0.0 },
                tri.lastOrNull() ?: 0.0,monde.joueur.position)
            if(enregistrement!=null && enregistres>0) {
                Sound(enregistre.copyOf(enregistres),2,rate).save(enregistrement)
                println("SILLAGE_AUDIO_ENREGISTRE "+enregistrement+" "+"%.1f".format(enregistres/2f/rate)+" s")
            }
        } finally {
            renderer?.close()
            window?.close()
            audio?.close()
        }
    }
}

/**
 * Un trajet scripté pour les essais et les enregistrements : des points « x,z » que le héros rejoint
 * l'un après l'autre, « j » pour sauter, « m » pour passer de la course à la marche, « pN » pour
 * attendre N secondes. Un point qu'il n'atteint pas en 6 s (une caisse trop lourde…) est abandonné.
 */
internal class Parcours(texte: String) {
    private val etapes=texte.trim().split(Regex("\\s+"))
    private var k=0
    private var depuis=0f
    private var lent=false
    val fini get()=k>=etapes.size

    /** Mouvement voulu, saut, marche lente. */
    fun suivre(p: Vec3, dt: Float): Triple<Vec3,Boolean,Boolean> {
        while(!fini) {
            val e=etapes[k]
            when {
                e=="j" -> { k++; depuis=0f; return Triple(Vec3.ZERO,true,lent) }
                e=="m" -> { lent=!lent; k++ }
                e.startsWith("p") -> { depuis+=dt; if(depuis>=e.drop(1).toFloat()) { k++; depuis=0f } else return Triple(Vec3.ZERO,false,lent) }
                else -> {
                    val (x,z)=e.split(',').map { it.toFloat() }
                    val d=Vec3(x-p.x,0f,z-p.z)
                    depuis+=dt
                    if(depuis>6f) println("SILLAGE_PARCOURS point $e abandonné en "+"(%.2f, %.2f, %.2f)".format(p.x,p.y,p.z))
                    if(d.length()<.3f || depuis>6f) { k++; depuis=0f; continue }
                    return Triple(d.normalized(),false,lent)
                }
            }
        }
        return Triple(Vec3.ZERO,false,lent)
    }
}
