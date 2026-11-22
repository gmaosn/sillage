package sillage

import sillage.audio.Audio
import sillage.math.Mat4
import sillage.math.Vec3
import sillage.math.toRadians
import sillage.platform.GameWindow
import kotlin.math.roundToInt

/**
 * A game played frame by frame, where the system calls the game rather than the game looping by itself:
 * a page (each animation frame), a phone (each time the screen wants a picture). It does what the desktop's
 * loop does between two pictures: reads the window's keys and pointer, lets the interface and the scene
 * live for [frame]'s time, and hands the picture to the [Renderer]. The options it understands are the
 * portable ones of the desktop (`--hour`, `--seed`, `--debug`, `--mute` is the caller's business).
 */
internal class FrameLoop(private val args: Array<String>, private val setup: Scene.() -> Unit, private val window: GameWindow,
                         renderer: Renderer, private val audio: Audio?) {
    /** What draws; replaced when the system takes the graphics context away and gives another (a phone coming back from sleep). */
    var renderer: Renderer = renderer
        set(v) { v.cull = field.cull; field = v }
    /** The touch screen, if the platform has one: its window reports the fingers here. */
    val touches = Touches()
    var scene: Scene = build(setup)
        private set
    /** Frames drawn so far. */
    var frames = 0
        private set
    /** The game asked to stop (Quit in the pause menu). */
    var quitting = false
        private set
    /** Receives the next finished picture (width, height, RGB rows from the top), once. */
    var capture: ((Int, Int, ByteArray) -> Unit)? = null
    private var fps = 60; private var fpsFrames = 0; private var fpsTime = 0f

    private fun option(flag: String): String? {
        val i = args.indexOf(flag)
        return if (i < 0 || i + 1 >= args.size) null else args[i + 1]
    }

    private fun build(make: Scene.() -> Unit): Scene {
        val s = Scene().also { s -> option("--seed")?.toLongOrNull()?.let { s.seed(it) } }.apply(make)
        s.audio = audio
        s.input.source = KeySource { k -> window.isDown(k) }
        return s
    }

    init {
        option("--hour")?.toFloatOrNull()?.let { scene.lighting.time = it }
        option("--debug")?.split(',')?.map { it.trim() }?.let { wanted ->
            scene.debug.showPhysics = "physics" in wanted; scene.debug.paused = "paused" in wanted
            if ("free" in wanted) scene.debug.freeCamera = true
        }
        if (scene.input.padSource === PadSource.NONE) scene.input.padSource = PadSource.system()
        renderer.cull = "--no-cull" !in args
    }

    /** One frame, [seconds] after the last one. */
    fun frame(seconds: Float) {
        val dt = seconds.coerceIn(.0001f, .1f)
        window.poll()
        if (window.consumeResize()) { val size = window.size(); if (size[0] > 0 && size[1] > 0) renderer.resize(size[0], size[1]) }
        val delta = window.consumeMouseDelta(); val dx = delta[0]; val dy = delta[1]
        scene.input.mouseDelta = dx to dy
        if (dx != 0f || dy != 0f) { if (scene.debug.freeCamera) scene.debug.turnFree(dx, dy) else scene.camera.turn(dx, dy) }
        window.consumeScroll().let { scene.input.wheel = it; if (it != 0f) scene.camera.zoom(it) }
        touches.apply(scene, renderer.width, renderer.height, dt)
        scene.input.poll()
        scene.debug.handleKeys(scene.input)
        if (scene.debug.freeCamera) scene.debug.flyFree(scene.input, dt)
        window.clearPresses()
        scene.hud.interact(scene, dt, renderer.width, renderer.height, window)
        if (scene.hud.quitting) { quitting = true; return }
        scene.input.mouseCaptured = window.mouseCaptured
        if (scene.input.pressed(Key.TAB) && !scene.hud.usesKeys) window.mouseCaptured = !window.mouseCaptured
        if (!scene.hud.paused && scene.debug.runsThisFrame()) scene.update(dt)
        val free = scene.debug.freeCamera
        val view = scene.camera.view(dt).let { if (free) scene.debug.freeView() else it }
        val eye = if (free) scene.debug.freePosition else scene.camera.position
        val looking = if (free) scene.debug.freeForward() else scene.camera.forward
        scene.debug.panels(eye, looking)
        val c = scene.camera
        val aspect = renderer.width.toFloat() / renderer.height
        val projection = Mat4.perspective(toRadians(c.fov.toDouble()).toFloat(), aspect, c.near, c.far)
        fpsFrames++; fpsTime += dt
        if (fpsTime >= .5f) { fps = (fpsFrames / fpsTime).roundToInt(); fpsTime = 0f; fpsFrames = 0; scene.fps = fps }
        val shot = capture; capture = null
        renderer.draw(scene.drawList(eye), projection * view, scene.hud.mesh(aspect, fps, window.mouseCaptured || touches.used), eye, scene.time,
            scene.hiddenNode, if (free) eye + looking * 12f else scene.camera.focus, scene.lighting, scene.frameExtras(eye), shot)
        frames++
        // Another scene asked for: build it, forget the old meshes on the GPU, keep the window and the sound.
        scene.next?.let { make ->
            audio?.mixer?.stopAll()
            val fresh = build(make)
            fresh.input.adoptPads(scene.input)
            renderer.forget()
            scene = fresh
        }
    }
}
