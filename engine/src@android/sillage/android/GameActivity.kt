package sillage.android

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import sillage.AndroidGame
import sillage.FrameLoop
import sillage.Key
import sillage.audio.AndroidOutput
import sillage.audio.Audio
import sillage.io.AndroidAssets
import sillage.platform.GameWindow
import sillage.platform.KEY_COUNT
import sillage.platformRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The activity of a Sillage game on Android. A game's own activity is one line:
 *
 * ```
 * class MainActivity : GameActivity({ main(emptyArray()) })     // the game's main, the one that calls game(…) { … }
 * ```
 * It fills the screen, plays the scene with OpenGL ES 3, reads the touch screen as a pad (a stick under
 * the left thumb, the camera under the right, a jump button), a keyboard if one is attached, and the
 * Back button as Escape (the pause menu). The game's files are its module's resources, or the
 * application's `assets` folder.
 */
open class GameActivity(private val start: () -> Unit) : Activity() {
    private lateinit var view: GLSurfaceView
    private val host = Host()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAssets.open = { path -> try { assets.open(path).use { it.readBytes() } } catch (e: java.io.IOException) { null } }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = GLSurfaceView(this)
        view.setEGLContextClientVersion(3)
        view.setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        view.preserveEGLContextOnPause = true
        view.setRenderer(host)
        view.isFocusableInTouchMode = true
        setContentView(view)
        fullScreen()
    }

    @Suppress("DEPRECATION")
    private fun fullScreen() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) fullScreen() }
    override fun onResume() { super.onResume(); view.onResume(); AndroidOutput.resumeAll() }
    override fun onPause() { view.queueEvent { host.loop?.touches?.cancel(); host.surface.releaseAll() }; view.onPause(); AndroidOutput.pauseAll(); super.onPause() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> { val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i); view.queueEvent { host.finger(id, x, y, 1) } }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) { val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i); view.queueEvent { host.finger(id, x, y, 0) } }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> { val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i); view.queueEvent { host.finger(id, x, y, -1) } }
            MotionEvent.ACTION_CANCEL -> view.queueEvent { host.loop?.touches?.cancel(); host.surface.pointer(Float.NaN, Float.NaN, false) }
        }
        return true
    }

    /** A key's place on the keyboard (its Linux scan code is the engine's key code); Back is Escape. */
    private fun code(keyCode: Int, e: KeyEvent): Int = when {
        keyCode == KeyEvent.KEYCODE_BACK -> Key.ESCAPE
        e.scanCode in 1 until KEY_COUNT -> e.scanCode
        else -> 0
    }
    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        val k = code(keyCode, e); if (k == 0) return super.onKeyDown(keyCode, e)
        val typed = e.unicodeChar
        view.queueEvent { host.surface.key(k, true); if (typed >= 32) host.surface.type(typed) }
        return true
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        val k = code(keyCode, e); if (k == 0) return super.onKeyUp(keyCode, e)
        view.queueEvent { host.surface.key(k, false) }
        return true
    }

    /** The screen as the loop's window: keys of an attached keyboard, the first finger as the pointer (for menus and buttons). */
    internal class Surface : GameWindow {
        private val down = BooleanArray(KEY_COUNT); private val tapped = BooleanArray(KEY_COUNT)
        private var width = 1; private var height = 1; private var resized = false
        private var held = false; private var clicked = false
        private val typed = StringBuilder()
        private val none = FloatArray(2)
        override var pointerX = Float.NaN; private set
        override var pointerY = Float.NaN; private set
        override val shouldClose get() = false
        override val focused get() = true
        override var mouseCaptured = true
        override var title = ""
        override var clickCaptures = false

        fun resize(w: Int, h: Int) { if (w != width || h != height) { width = w; height = h; resized = true } }
        fun key(k: Int, isDown: Boolean) { down[k] = isDown; if (isDown) tapped[k] = true }
        fun type(codePoint: Int) { if (typed.length < 256) typed.appendCodePoint(codePoint) }
        fun pointer(x: Float, y: Float, isDown: Boolean) { pointerX = x; pointerY = y; if (isDown && !held) clicked = true; held = isDown }
        fun releaseAll() { down.fill(false); held = false }

        override fun poll() {}
        override fun waitEvents(seconds: Double) {}
        override fun isDown(key: Int) = key in 0 until KEY_COUNT && (down[key] || tapped[key])
        override fun clearPresses() { tapped.fill(false) }
        override fun consumeMouseDelta(): FloatArray = none
        override fun consumeScroll() = 0f
        override fun consumeResize(): Boolean { val r = resized; resized = false; return r }
        override fun size() = intArrayOf(width, height)
        override fun buttonDown(): Boolean { val b = held || clicked; clicked = false; return b }
        override fun consumeText(): String { val t = typed.toString(); typed.setLength(0); return t }
    }

    /** The surface view's renderer: everything below runs on its thread, which is the game's. */
    private inner class Host : GLSurfaceView.Renderer {
        val surface = Surface()
        var loop: FrameLoop? = null
        private var gl: AndroidGl? = null
        private var fresh = false                         // a new graphics context: what the old one held is gone
        private var last = 0L
        private var primary = -1
        private var failed = false

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) { gl = AndroidGl(); fresh = true }

        @Volatile private var built: FrameLoop? = null      // the scene, built beside the screen's thread
        @Volatile private var broken: Throwable? = null
        private var building = false

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            surface.resize(width, height)
            val g = gl ?: return
            val running = loop
            if (running == null) {
                // Building the scene takes seconds (models, textures, physics): the system must not wait for it here,
                // or it declares the application frozen. A thread builds it; the screen stays dark meanwhile.
                if (!building) {
                    building = true
                    Thread({
                        try {
                            AndroidGame.pending = null
                            start()
                            val request = AndroidGame.pending ?: error("The game's main did not call game(…)")
                            surface.title = request.title
                            val audio = if ("--mute" in request.args) null else Audio.open()
                            built = FrameLoop(request.args, request.setup, surface, NoRenderer, audio)
                        } catch (e: Throwable) { broken = e }
                    }, "sillage-start").start()
                }
            } else if (fresh) {
                try { running.renderer = platformRenderer(g, width, height); surface.consumeResize() } catch (e: Throwable) { fail(e) }
            }
            if (running != null) fresh = false
        }

        override fun onDrawFrame(unused: GL10?) {
            if (failed) return
            broken?.let { fail(it); return }
            val g = gl ?: return
            var game = loop
            if (game == null) {
                android.opengl.GLES30.glClearColor(.02f, .05f, .07f, 1f); android.opengl.GLES30.glClear(android.opengl.GLES30.GL_COLOR_BUFFER_BIT)
                game = built ?: return
                try { val size = surface.size(); game.renderer = platformRenderer(g, size[0], size[1]); surface.consumeResize() } catch (e: Throwable) { fail(e); return }
                loop = game; fresh = false; last = 0L
            }
            val now = System.nanoTime()
            val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9).toFloat()
            last = now
            try { game.frame(dt) } catch (e: Throwable) { fail(e); return }
            if (game.frames == 1) Log.i("Sillage", "SILLAGE_ANDROID_OK first frame, " + surface.size().joinToString("x"))
            if (game.quitting) runOnUiThread { finish() }
        }

        /** [change]: 1 a finger lands, 0 it moves, −1 it lifts. */
        fun finger(id: Int, x: Float, y: Float, change: Int) {
            val t = loop?.touches ?: return
            when (change) { 1 -> t.down(id, x, y); 0 -> t.move(id, x, y); else -> t.up(id) }
            if (change == 1 && primary < 0) primary = id
            if (id == primary) { surface.pointer(x, y, change >= 0); if (change < 0) primary = -1 }
        }

        private fun fail(e: Throwable) {
            failed = true
            Log.e("Sillage", "SILLAGE_ANDROID_ERROR", e)
            runOnUiThread { android.widget.Toast.makeText(this@GameActivity, "Sillage: " + (e.message ?: e.toString()), android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}

/** Stands in while the scene is built, before the screen's thread makes the real renderer. */
private object NoRenderer : sillage.Renderer {
    override val width = 1; override val height = 1
    override fun resize(width: Int, height: Int) {}
    override fun draw(nodes: List<sillage.Node>, viewProjection: sillage.math.Mat4, hud: sillage.HudMesh, eye: sillage.math.Vec3, time: Float, hidden: sillage.Node?,
                      focus: sillage.math.Vec3?, lighting: sillage.Lighting, extras: sillage.FrameExtras?, capture: ((Int, Int, ByteArray) -> Unit)?) {}
    override fun forget() {}
    override fun close() {}
    override var cull = true
    override val drawn = 0; override val culled = 0
}
