package sillage

import sillage.math.Mat4
import sillage.math.Vec3
import sillage.platform.Key
import sillage.platform.Window
import sillage.platform.openWindow
import java.nio.file.Path
import kotlin.math.*

internal class Game(private val titre: String,private val monde: Monde,private val args: Array<String>) {
    fun run() {
        if("--help" in args) {
            println("Sillage : --headless --smoke --frames N --capture chemin.png --look-at ex,ey,ez,cx,cy,cz --validation")
            return
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
        val lookAt=option("--look-at")?.split(',')?.map { it.trim().toFloat() }?.also { require(it.size==6) { "--look-at attend 6 nombres" } }
        var window: Window?=null
        var renderer: VulkanRenderer?=null
        try {
            if(!headless) window=openWindow(titre+" - Les Alizés",1280,800)
            val r=VulkanRenderer(window,"--validation" in args)
            renderer=r; r.ouvrir()
            window?.mouseCaptured=true
            var last=System.nanoTime()
            var frame=0; var fps=60; var fpsFrames=0; var fpsTime=0f
            var oldJump=false; var oldTab=false; var oldReset=false
            var highest=monde.joueur.position.y
            var lowest=highest
            var moved=false
            val initial=monde.joueur.position
            while(frame<frames && (window==null || !window.shouldClose)) {
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
                val dt=if(smoke || headless) 1f/60f else ((now-last)/1e9).toFloat().coerceIn(0.0001f,.1f)
                last=now
                // Touches par position physique : ZQSD en AZERTY et WASD en QWERTY sont les mêmes touches.
                fun down(vararg keys: Int)=window!=null && keys.any { window.isDown(it) }
                val tab=down(Key.TAB)
                if(tab && !oldTab) window!!.mouseCaptured=!window.mouseCaptured
                oldTab=tab
                val reset=down(Key.R)
                if(reset && !oldReset) monde.reinitialiser()
                oldReset=reset
                var jump=down(Key.SPACE)
                val movement=if(smoke) {
                    jump=frame==40
                    if(frame in 20..110) monde.camera.mouvement(1f,0f) else Vec3.ZERO
                } else monde.camera.mouvement(
                    (if(down(Key.D,Key.RIGHT)) 1f else 0f)-(if(down(Key.A,Key.LEFT)) 1f else 0f),
                    (if(down(Key.W,Key.UP)) 1f else 0f)-(if(down(Key.S,Key.DOWN)) 1f else 0f))
                monde.miseAJour(movement,jump && !oldJump,dt); oldJump=jump
                if(monde.joueur.position.distanceTo(initial)>.1f) moved=true
                highest=max(highest,monde.joueur.position.y); lowest=min(lowest,monde.joueur.position.y)
                val view=monde.camera.vue(monde,dt).let { v -> lookAt?.let { Mat4.lookAt(Vec3(it[0],it[1],it[2]),Vec3(it[3],it[4],it[5])) } ?: v }
                val projection=Mat4.perspective(Math.toRadians(58.0).toFloat(),r.largeur.toFloat()/r.hauteur,.08f,180f)
                fpsFrames++; fpsTime+=dt
                if(fpsTime>=.5f) { fps=(fpsFrames/fpsTime).roundToInt(); fpsTime=0f; fpsFrames=0 }
                val capture=if(frame==frames-1) screenshot else null
                r.dessiner(monde.objets,projection*view,Hud.construire(monde,fps,window?.mouseCaptured ?: false),capture)
                frame++
                if(window!=null && frame%30==0) window.title=titre+" | "+monde.recoltes+"/"+monde.coquillages.size+" coquillages | "+fps+" FPS"
            }
            if(smoke) {
                check(frame==frames) { "Démo interrompue avant la fin du smoke test" }
                check(moved) { "Le personnage n'a pas bougé" }
                check(highest-lowest>.5f) { "Le saut ou la gravité n'a pas été exercé" }
                check(monde.joueur.position.isFinite()) { "Position physique non finie" }
            }
            check(r.erreursValidation==0) { "Erreurs de validation Vulkan : " + r.erreursValidation }
            println("SILLAGE_RUN_OK frames="+frame+" smoke="+smoke+" position="+monde.joueur.position+" sol="+monde.joueur.auSol)
        } finally {
            renderer?.close()
            window?.close()
        }
    }
}
