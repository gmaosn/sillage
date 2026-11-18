package sillage

import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import java.nio.file.Path
import kotlin.math.*

internal class Game(private val titre: String,private val monde: Monde,private val args: Array<String>) {
    fun run() {
        if("--help" in args) {
            println("Sillage : --headless --smoke --frames N --capture chemin.png --validation")
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
        var window=0L
        var glfw=false
        var callback: GLFWErrorCallback?=null
        var renderer: VulkanRenderer?=null
        try {
            if(!headless) {
                callback=GLFWErrorCallback.createPrint(System.err).set()
                check(glfwInit()) { "Pas de session graphique. Utiliser --headless pour le rendu Vulkan hors écran." }
                glfw=true
                glfwWindowHint(GLFW_CLIENT_API,GLFW_NO_API)
                window=glfwCreateWindow(1280,800,titre+" - Les Alizés",0L,0L)
                check(window!=0L) { "Création de fenêtre impossible" }
            }
            val r=VulkanRenderer(window,"--validation" in args)
            renderer=r; r.ouvrir()
            var captured=!headless
            var focused=true
            var mx=0.0; var my=0.0; var firstMouse=true
            var resized=false
            if(!headless) {
                glfwSetInputMode(window,GLFW_CURSOR,GLFW_CURSOR_DISABLED)
                if(glfwRawMouseMotionSupported()) glfwSetInputMode(window,GLFW_RAW_MOUSE_MOTION,GLFW_TRUE)
                glfwSetCursorPosCallback(window) { _,x,y ->
                    if(captured && focused && !firstMouse) monde.camera.tourner((x-mx).toFloat(),(y-my).toFloat())
                    mx=x; my=y; firstMouse=false
                }
                glfwSetScrollCallback(window) { _,_,dy -> if(focused) monde.camera.zoom(dy.toFloat()) }
                glfwSetWindowFocusCallback(window) { _,focus -> focused=focus; firstMouse=true }
                glfwSetFramebufferSizeCallback(window) { _,_,_ -> resized=true }
            }
            var last=System.nanoTime()
            var frame=0; var fps=60; var fpsFrames=0; var fpsTime=0f
            var oldJump=false; var oldTab=false; var oldReset=false
            var highest=monde.joueur.position.y
            var lowest=highest
            var moved=false
            val initial=Vector3f(monde.joueur.position)
            while(frame<frames && (headless || !glfwWindowShouldClose(window))) {
                if(!headless) {
                    glfwPollEvents()
                    if(glfwGetKey(window,GLFW_KEY_ESCAPE)==GLFW_PRESS) break
                    if(resized) { if(!r.recreateSwapchain()) { glfwWaitEventsTimeout(.05); continue }; resized=false }
                    if(!focused && !smoke) { glfwWaitEventsTimeout(.05); last=System.nanoTime(); continue }
                }
                val now=System.nanoTime()
                val dt=if(smoke || headless) 1f/60f else ((now-last)/1e9).toFloat().coerceIn(0.0001f,.1f)
                last=now
                fun down(vararg keys: Int)=!headless && keys.any { glfwGetKey(window,it)==GLFW_PRESS }
                val tab=down(GLFW_KEY_TAB)
                if(tab && !oldTab) { captured=!captured; firstMouse=true; glfwSetInputMode(window,GLFW_CURSOR,if(captured) GLFW_CURSOR_DISABLED else GLFW_CURSOR_NORMAL) }
                oldTab=tab
                val reset=down(GLFW_KEY_R)
                if(reset && !oldReset) monde.reinitialiser()
                oldReset=reset
                var jump=down(GLFW_KEY_SPACE)
                val movement=if(smoke) {
                    jump=frame==40
                    if(frame in 20..110) monde.camera.mouvement(1f,0f) else Vector3f()
                } else monde.camera.mouvement(
                    (if(down(GLFW_KEY_D,GLFW_KEY_RIGHT)) 1f else 0f)-(if(down(GLFW_KEY_A,GLFW_KEY_Q,GLFW_KEY_LEFT)) 1f else 0f),
                    (if(down(GLFW_KEY_W,GLFW_KEY_Z,GLFW_KEY_UP)) 1f else 0f)-(if(down(GLFW_KEY_S,GLFW_KEY_DOWN)) 1f else 0f))
                monde.miseAJour(movement,jump && !oldJump,dt); oldJump=jump
                if(monde.joueur.position.distance(initial)>.1f) moved=true
                highest=max(highest,monde.joueur.position.y); lowest=min(lowest,monde.joueur.position.y)
                val view=monde.camera.vue(monde,dt)
                val projection=Matrix4f().perspective(Math.toRadians(58.0).toFloat(),r.largeur.toFloat()/r.hauteur,.08f,180f,true)
                projection.m11(-projection.m11())
                fpsFrames++; fpsTime+=dt
                if(fpsTime>=.5f) { fps=(fpsFrames/fpsTime).roundToInt(); fpsTime=0f; fpsFrames=0 }
                val capture=if(frame==frames-1) screenshot else null
                r.dessiner(monde.objets,projection.mul(view),Hud.construire(monde,fps,captured),capture)
                frame++
                if(!headless && frame%30==0) glfwSetWindowTitle(window,titre+" | "+monde.recoltes+"/"+monde.coquillages.size+" coquillages | "+fps+" FPS")
            }
            if(smoke) {
                check(frame==frames) { "Démo interrompue avant la fin du smoke test" }
                check(moved) { "Le personnage n'a pas bougé" }
                check(highest-lowest>.5f) { "Le saut ou la gravité n'a pas été exercé" }
                check(monde.joueur.position.isFinite) { "Position physique non finie" }
            }
            check(r.erreursValidation==0) { "Erreurs de validation Vulkan : " + r.erreursValidation }
            println("SILLAGE_RUN_OK frames="+frame+" smoke="+smoke+" position="+monde.joueur.position+" sol="+monde.joueur.auSol)
        } finally {
            renderer?.close()
            if(window!=0L) { glfwFreeCallbacks(window); glfwDestroyWindow(window) }
            if(glfw) glfwTerminate()
            callback?.free()
        }
    }
}
