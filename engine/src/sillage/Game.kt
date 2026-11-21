package sillage

import sillage.audio.Audio
import sillage.audio.Sound
import sillage.dev.DevLink
import sillage.dev.DevOverlay
import sillage.math.Mat4
import sillage.math.Vec3
import sillage.platform.Window
import sillage.platform.openWindow
import java.nio.file.Path
import kotlin.math.*

/** What a run gave: frames drawn, time per frame (update and drawing), where the first player ended. */
/**
 * What a run measured: frames drawn, CPU time per frame (mean, 99th percentile, worst; without the first
 * frames, while the JIT compiles), the GPU's time per frame (median, 0 if the GPU cannot time) and where
 * the player ended.
 */
class RunReport(val frames: Int, val meanMs: Double, val p99Ms: Double, val maxMs: Double, val playerPosition: Vec3, val gpuMs: Double = 0.0,
                /** Milliseconds from the start of the JVM to the first frame drawn. */
                val firstFrameMs: Long = 0)

/** Frame times kept in a primitive array: measuring must not allocate. */
private class Times {
    var values = DoubleArray(1024); var size = 0
    fun add(v: Double) { if (size == values.size) values = values.copyOf(size * 2); values[size++] = v }
    fun list(): List<Double> = values.copyOf(size).toList()
}

/**
 * Opens a window and plays the scene built by [setup] until the window closes (or Quit in the pause menu
 * that Escape opens, see [Hud.pauseMenu]). Tab captures and frees the mouse. [args] are the program's arguments; `--help` lists the options
 * (running without a window, captures, recordings…).
 */
fun game(title: String = "Sillage", args: Array<String> = emptyArray(), setup: Scene.() -> Unit): RunReport {
    // Under ./sillage dev, a reloaded game hands its scene to the loop already running.
    if (DevLink.offer(setup)) return RunReport(0, 0.0, 0.0, 0.0, Vec3.ZERO)
    return Runtime(title, args, setup).run()
}

internal class Runtime(private val title: String, private val args: Array<String>, private val setup: Scene.() -> Unit) {
    private fun option(flag: String): String? {
        val i = args.indexOf(flag)
        if (i < 0) return null
        require(i + 1 < args.size) { "Missing value after $flag" }
        return args[i + 1]
    }

    fun run(): RunReport {
        if ("--help" in args) {
            println("""Sillage options:
  --headless              no window: draw off screen (with --frames, --capture)
  --frames N              stop after N frames
  --capture file.png      save the last frame
  --look-at ex,ey,ez,tx,ty,tz   fixed camera for captures
  --size WxH              off-screen image size (1280x800)
  --smoke                 scripted check: the player walks and jumps
  --route "x,z x,z j w p2 …"   the player follows points (j jumps, w walk/run, pN waits N s)
  --trace [--trace-every N]    print the player's state
  --animation clip        force the player's clip (captures)
  --hour H                start at hour H (0 to 24, see Lighting.time)
  --debug physics,free,paused   start with the physics drawn (F5), the free camera (F6), paused (F7)
  --no-cull               draw everything, even what the camera cannot see (comparisons)
  --mute | --audio-device NAME | --record-audio file.wav (no window) | --export-sounds dir
  --export-textures dir   write the ready-made textures as PNG
  --validation            Vulkan validation layers
  --pad-test [--seconds N]   check the pads from the terminal (buttons, sticks, motion, lights, rumble, triggers)
  --pad-script "0 L 0,1; 1 tap CROSS; 2 R 1,0"   a scripted pad (see PadScript)""")
            return RunReport(0, 0.0, 0.0, 0.0, Vec3.ZERO)
        }
        if ("--pad-test" in args) { padTest(args); return RunReport(0, 0.0, 0.0, 0.0, Vec3.ZERO) }
        option("--export-sounds")?.let { exportSounds(Path.of(it)); return RunReport(0, 0.0, 0.0, 0.0, Vec3.ZERO) }
        option("--export-textures")?.let { exportTextures(Path.of(it)); return RunReport(0, 0.0, 0.0, 0.0, Vec3.ZERO) }
        val headless = "--headless" in args
        val smoke = "--smoke" in args
        val frames = option("--frames")?.toInt()?.also { require(it > 0) } ?: if (headless || smoke) 180 else Int.MAX_VALUE
        val screenshot = option("--capture")?.let { Path.of(it) }
        val lookAt = option("--look-at")?.split(',')?.map { it.trim().toFloat() }?.also { require(it.size == 6) { "--look-at takes 6 numbers" } }
        val size = option("--size")?.split('x')?.map { it.trim().toInt() }?.also { require(it.size == 2 && it.all { v -> v in 1..8192 }) { "--size takes WxH" } }
        val recording = option("--record-audio")?.let { Path.of(it) }
        val route = option("--route")?.let { Route(it) }
        val trace = "--trace" in args
        val traceEvery = option("--trace-every")?.toInt() ?: 30
        // Without a window, sound is mixed frame by frame (and may be recorded): nothing reaches the speakers.
        val audio: Audio? = when {
            "--mute" in args -> null
            headless -> Audio.offline()
            else -> Audio.open(option("--audio-device") ?: "default")
        }
        var scene = Scene().apply(setup)
        val dev = DevLink.enabled
        var initial = if (dev) DevLink.Initial(scene) else null
        var frozen = false                                  // dev: an update failed, the picture waits for a fix
        val devOverlay = DevOverlay()
        option("--animation")?.let { scene.forcedPlayerClip = it }
        option("--hour")?.let { scene.lighting.time = it.toFloat() }
        option("--debug")?.split(',')?.map { it.trim() }?.let { wanted ->
            for (w in wanted) require(w in setOf("physics", "free", "paused")) { "--debug takes physics, free, paused (got $w)" }
            scene.debug.showPhysics = "physics" in wanted; scene.debug.paused = "paused" in wanted
            if ("free" in wanted) scene.debug.freeCamera = true
        }
        option("--pad-script")?.let { PadScript(it).attach(scene) }
        scene.audio = audio
        val rate = audio?.mixer?.rate ?: 48_000
        val block = FloatArray(2 * 512)
        var recorded = FloatArray(if (recording != null) 2 * rate * 10 else 0); var recordedCount = 0
        var carry = 0f
        fun mixOffline(dt: Float) {
            val a = audio ?: return
            carry += dt * rate
            var n = carry.toInt(); carry -= n
            while (n > 0) {
                val k = min(n, 512); a.mixer.render(block, k); n -= k
                if (recording != null) {
                    if (recordedCount + 2 * k > recorded.size) recorded = recorded.copyOf(recorded.size * 2)
                    System.arraycopy(block, 0, recorded, recordedCount, 2 * k); recordedCount += 2 * k
                }
            }
        }
        val frameTimes = Times(); val gpuTimes = Times(); var firstFrame = 0L
        var window: Window? = null
        var renderer: VulkanRenderer? = null
        try {
            audio?.device?.let { println("SILLAGE_AUDIO device=" + it + " latency=" + (audio.latency * 1000).roundToInt() + " ms") }
            if (!headless) window = openWindow(title, 1280, 800)
            var r = VulkanRenderer(window, "--validation" in args, size?.get(0) ?: 1280, size?.get(1) ?: 800, scene.lighting.shadows)
            renderer = r; r.ouvrir()
            r.cull = "--no-cull" !in args
            window?.mouseCaptured = true
            scene.input.source = KeySource { k -> window?.isDown(k) ?: false }
            if (window != null && scene.input.padSource === PadSource.NONE) scene.input.padSource = PadSource.system()
            var last = System.nanoTime()
            var frame = 0; var fps = 60; var fpsFrames = 0; var fpsTime = 0f
            val start = scene.player?.position ?: Vec3.ZERO
            var highest = start.y; var lowest = start.y; var moved = false
            if (dev) DevLink.looping = true
            while (frame < frames && (window == null || !window.shouldClose) && route?.done != true && DevLink.quitCode == null) {
                if (window != null) {
                    window.poll()
                    if (window.consumeResize() && !r.recreateSwapchain()) { window.waitEvents(.05); continue }
                    // Paused without focus, but never before the first frame: under Wayland the window only exists after it.
                    if (!window.focused && !smoke && frame > 0) { window.waitEvents(.05); last = System.nanoTime(); continue }
                }
                // Waits until the last frame is on screen, then reads what came meanwhile: the keys and the mouse act
                // on the very next picture, instead of waiting behind the frames queued for the screen.
                if (!r.attendre()) continue
                if (window != null) {
                    window.poll()
                    val (dx, dy) = window.consumeMouseDelta().let { it[0] to it[1] }
                    scene.input.mouseDelta = dx to dy
                    if (dx != 0f || dy != 0f) { if (scene.debug.freeCamera) scene.debug.turnFree(dx, dy) else scene.camera.turn(dx, dy) }
                    window.consumeScroll().let { scene.input.wheel = it; if (it != 0f) scene.camera.zoom(it) }
                }
                val now = System.nanoTime()
                val frameStart = now
                val dt = if (smoke || headless) 1f / 60f else ((now - last) / 1e9).toFloat().coerceIn(.0001f, .1f)
                last = now
                scene.input.poll()
                scene.debug.handleKeys(scene.input)    // F5 physics, F6 free camera, F7 pause, F8 one frame
                if (scene.debug.freeCamera) scene.debug.flyFree(scene.input, dt)
                window?.clearPresses()                 // a tap between two frames counted once
                // The interface: menus, buttons and fields get the pointer, the keys and the text typed; Escape
                // opens the pause menu, which stops the game's time and frees the mouse.
                scene.hud.interact(scene, dt, r.largeur, r.hauteur, window)
                if (scene.hud.quitting) break
                scene.input.mouseCaptured = window?.mouseCaptured ?: false
                if (window != null && scene.input.pressed(Key.TAB) && !scene.hud.usesKeys) window.mouseCaptured = !window.mouseCaptured
                // Scripted drivers for checks and recordings.
                val p = scene.player
                if (p != null && (route != null || smoke)) {
                    p.controls = false
                    if (route != null) { val (m, jump, walk) = route.follow(p.position, dt); p.move(m, walk); if (jump) p.jump() }
                    else { if (frame == 40) p.jump(); if (frame in 20..110) p.move(scene.camera.moveDirection(1f, 0f)) }
                }
                // The pause menu stops the game; the debugging pause (F7) too, but F8 lets one frame through.
                val runs = !scene.hud.paused && scene.debug.runsThisFrame()
                if (!dev) { if (runs) scene.update(dt) }
                else if (!frozen && runs) try { scene.update(dt) } catch (e: Exception) {
                    frozen = true
                    DevLink.error = "THE GAME STOPPED\n" + sillage.dev.DevHost.describe(e)
                    System.err.println("SILLAGE_DEV_ERROR " + sillage.dev.DevHost.describe(e)); e.printStackTrace()
                }
                if (dev) devOverlay.show(scene.hud)
                if (p != null) {
                    if (p.position.distanceTo(start) > .1f) moved = true
                    highest = max(highest, p.position.y); lowest = min(lowest, p.position.y)
                    if (trace && frame % traceEvery == 0) println("SILLAGE_TRACE t=" + "%.1f".format(scene.time) + " feet=" + "(%.2f, %.2f, %.2f)".format(p.position.x, p.position.y, p.position.z) +
                        " ground=" + p.onGround + " on=" + (p.character.groundMaterial?.name ?: p.character.groundBody?.let { "body " + it.id } ?: "-") + " clip=" + p.animator?.current)
                }
                if (headless) mixOffline(dt)
                val free = scene.debug.freeCamera
                val view = scene.camera.view(dt).let { v -> lookAt?.let { Mat4.lookAt(Vec3(it[0], it[1], it[2]), Vec3(it[3], it[4], it[5])) } ?: if (free) scene.debug.freeView() else v }
                val eye = lookAt?.let { Vec3(it[0], it[1], it[2]) } ?: if (free) scene.debug.freePosition else scene.camera.position
                val looking = lookAt?.let { (Vec3(it[3], it[4], it[5]) - eye).normalized() } ?: if (free) scene.debug.freeForward() else scene.camera.forward
                scene.debug.panels(eye, looking)
                val c = scene.camera
                val projection = Mat4.perspective(Math.toRadians(c.fov.toDouble()).toFloat(), r.largeur.toFloat() / r.hauteur, c.near, c.far)
                fpsFrames++; fpsTime += dt
                if (fpsTime >= .5f) { fps = (fpsFrames / fpsTime).roundToInt(); fpsTime = 0f; fpsFrames = 0; scene.fps = fps }
                val capture = if (frame == frames - 1 || route?.done == true) screenshot else null
                r.dessiner(scene.drawList(eye), projection * view, scene.hud.mesh(r.largeur.toFloat() / r.hauteur, fps, window?.mouseCaptured ?: true), capture, eye, scene.time,
                    scene.hiddenNode, lookAt?.let { Vec3(it[3], it[4], it[5]) } ?: if (free) eye + looking * 12f else scene.camera.focus, scene.lighting, scene.frameExtras(eye))
                frameTimes.add((System.nanoTime() - frameStart) / 1e6)
                if (r.gpuMs > 0.0) gpuTimes.add(r.gpuMs)
                if (frame == 0) firstFrame = java.lang.management.ManagementFactory.getRuntimeMXBean().uptime
                frame++
                if (window != null && frame % 30 == 0) window.title = title
                // Another scene asked for: build it, free the old meshes on the GPU, keep the window and the sound.
                fun install(fresh: Scene) {
                    audio?.mixer?.stopAll()
                    fresh.input.source = scene.input.source
                    fresh.input.adoptPads(scene.input)
                    fresh.audio = audio
                    r.oublier()
                    if (fresh.lighting.shadows != scene.lighting.shadows) {
                        r.close(); r = VulkanRenderer(window, "--validation" in args, size?.get(0) ?: 1280, size?.get(1) ?: 800, fresh.lighting.shadows); renderer = r; r.ouvrir(); r.cull = "--no-cull" !in args
                    }
                    scene = fresh
                }
                scene.next?.let { build -> install(Scene().apply(build)); if (dev) initial = DevLink.Initial(scene) }
                // ./sillage dev: a reload brought a new scene; a failure keeps the old one on screen.
                if (dev) DevLink.take()?.let { build ->
                    val fresh = try { Scene().apply(build) } catch (e: Exception) {
                        DevLink.error = "THE SCENE FAILED TO BUILD\n" + sillage.dev.DevHost.describe(e)
                        System.err.println("SILLAGE_DEV_ERROR " + sillage.dev.DevHost.describe(e)); e.printStackTrace(); null
                    }
                    if (fresh != null) {
                        val freshInitial = DevLink.Initial(fresh)
                        DevLink.carryOver(scene, initial!!, fresh, freshInitial)
                        install(fresh); initial = freshInitial
                        frozen = false; DevLink.error = null; devOverlay.reset()
                        val ms = if (DevLink.changedAt != 0L) (System.nanoTime() - DevLink.changedAt) / 1_000_000 else -1
                        println("SILLAGE_DEV_RELOADED " + DevLink.reloadWhat + " in " + ms + " ms, player at " + (scene.player?.position ?: "-"))
                    }
                }
            }
            val p = scene.player
            if (smoke && route == null && p != null) {
                check(frame == frames) { "Smoke run stopped early" }
                check(moved) { "The player did not move" }
                check(highest - lowest > .5f) { "The jump or gravity was not exercised" }
                check(p.position.isFinite()) { "Player position not finite" }
            }
            check(r.erreursValidation == 0) { "Vulkan validation errors: " + r.erreursValidation }
            val gpu = gpuTimes.list().drop(min(60, gpuTimes.size / 4)).sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
            val cpu = frameTimes.list().drop(min(60, frameTimes.size / 4)).sorted()
            println("SILLAGE_RUN_OK frames=" + frame + " smoke=" + smoke + " position=" + (p?.position ?: Vec3.ZERO) + " ground=" + (p?.onGround ?: false) +
                " cpu=" + (if (cpu.isEmpty()) "-" else "%.2f/%.2f/%.1f".format(cpu.average(), cpu[(cpu.size * .99).toInt().coerceAtMost(cpu.size - 1)], cpu.last())) +
                "ms gpu=" + "%.2f".format(gpu) + "ms first-frame=" + firstFrame + "ms drawn=" + r.drawn + " culled=" + r.culled)
            if (System.getenv("SILLAGE_FRAME_TIMES") != null) println("SILLAGE_FRAME_TIMES " + frameTimes.list().withIndex().sortedByDescending { it.value }.take(12).joinToString { "#" + it.index + "=" + "%.1f".format(it.value) })
            if (recording != null && recordedCount > 0) {
                Sound(recorded.copyOf(recordedCount), 2, rate).save(recording)
                println("SILLAGE_AUDIO_RECORDED " + recording + " " + "%.1f".format(recordedCount / 2f / rate) + " s")
            }
            val sorted = frameTimes.list().drop(min(60, frameTimes.size / 4)).sorted()        // without the first frames (compilation)
            return RunReport(frame, if (sorted.isEmpty()) 0.0 else sorted.average(), sorted.getOrElse((sorted.size * .99).toInt().coerceAtMost(sorted.size - 1)) { 0.0 },
                sorted.lastOrNull() ?: 0.0, p?.position ?: Vec3.ZERO, gpu, firstFrame)
        } finally {
            if (dev) DevLink.looping = false
            scene.input.padSource.close()
            renderer?.close()
            window?.close()
            audio?.close()
        }
    }
}

/**
 * A scripted route for checks and recordings: points « x,z » the player reaches one after another,
 * « j » to jump, « w » to switch between running and walking, « pN » to wait N seconds. A point not
 * reached within 6 s (a crate too heavy…) is given up and reported.
 */
internal class Route(text: String) {
    private val steps = text.trim().split(Regex("\\s+"))
    private var k = 0
    private var since = 0f
    private var walk = false
    val done get() = k >= steps.size

    /** Wanted move, jump, walking. */
    fun follow(p: Vec3, dt: Float): Triple<Vec3, Boolean, Boolean> {
        while (!done) {
            val e = steps[k]
            when {
                e == "j" -> { k++; since = 0f; return Triple(Vec3.ZERO, true, walk) }
                e == "w" || e == "m" -> { walk = !walk; k++ }
                e.startsWith("p") -> { since += dt; if (since >= e.drop(1).toFloat()) { k++; since = 0f } else return Triple(Vec3.ZERO, false, walk) }
                else -> {
                    val (x, z) = e.split(',').map { it.toFloat() }
                    val d = Vec3(x - p.x, 0f, z - p.z)
                    since += dt
                    if (since > 6f) println("SILLAGE_ROUTE point $e given up at " + "(%.2f, %.2f, %.2f)".format(p.x, p.y, p.z))
                    if (d.length() < .3f || since > 6f) { k++; since = 0f; continue }
                    return Triple(d.normalized(), false, walk)
                }
            }
        }
        return Triple(Vec3.ZERO, false, walk)
    }
}
