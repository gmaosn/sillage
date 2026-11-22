package sillage

import sillage.audio.Audio
import sillage.io.Storage
import sillage.io.WebFiles
import sillage.math.Vec3
import sillage.web.*

/**
 * In a page: takes the canvas (the one with id « sillage », else the first, else one made to fill the page),
 * loads the files named in `assets.list` (one path per line, beside the page), then builds the scene and
 * plays it on each animation frame. Returns at once: the page goes on calling the game.
 * The page's address gives the options: `?hour=21&seed=3&mute` is `--hour 21 --seed 3 --mute`.
 */
actual fun game(title: String, args: Array<String>, setup: Scene.() -> Unit): RunReport {
    WebGame.start(title, args, setup)
    return RunReport(0, 0.0, 0.0, 0.0, Vec3.ZERO)
}

internal object WebGame {
    var loop: FrameLoop? = null
        private set

    /** The address's `?a=1&b` as `--a 1 --b`, after the program's own arguments. */
    private fun arguments(args: Array<String>): Array<String> {
        val out = ArrayList(args.toList())
        for (part in jsSearch().removePrefix("?").split('&')) {
            if (part.isEmpty()) continue
            val name = part.substringBefore('='); out.add("--$name")
            if ('=' in part) { val v = jsQuery(name) ?: part.substringAfter('='); out.add(v); WebFiles.settings[name] = v }
        }
        return out.toTypedArray()
    }

    fun start(title: String, programArgs: Array<String>, setup: Scene.() -> Unit) {
        val args = arguments(programArgs)
        val level = webInit("sillage")
        if (level == 0) { jsMessage("$title needs WebGL 2, which this browser does not offer."); return }
        WebFiles.saved = { path, bytes -> bytesOut(bytes, 0, bytes.size) { jsDownload(path.substringAfterLast('/'), it, bytes.size) } }
        jsMessage("$title…")
        jsLoadAssets("assets.list", { got, of -> jsMessage("$title… $got / $of") }) { count, error ->
            if (count < 0) { jsMessage("$title could not load its files.\n$error"); return@jsLoadAssets }
            try {
                for (i in 0 until count) Storage.preloaded[jsAssetName(i)] = bytesIn(jsAssetSize(i)) { jsAssetCopy(i, it) }
                run(title, args, setup, WebGl(level == 2))
                jsMessage("")
            } catch (e: Throwable) {
                jsLog(e.stackTraceToString())
                jsMessage("$title stopped.\n" + (e.message ?: e.toString()))
            }
        }
    }

    private fun run(title: String, args: Array<String>, setup: Scene.() -> Unit, gl: WebGl) {
        val window = WebWindow(title)
        val size = window.size()
        val renderer = platformRenderer(gl, size[0], size[1])
        val audio = if ("--mute" in args) null else Audio.open()
        val game = FrameLoop(args, setup, window, renderer, audio)
        loop = game
        window.mouseCaptured = true                       // taken at the first click
        var last = Double.NaN
        var failed = false
        jsLoop { now ->
            val dt = if (last.isNaN()) 1f / 60f else ((now - last) / 1000.0).toFloat()
            last = now
            try { game.frame(dt) } catch (e: Throwable) {
                failed = true
                jsLog(e.stackTraceToString()); jsMessage("$title stopped.\n" + (e.message ?: e.toString()))
            }
            if (game.frames == 1) jsLog("SILLAGE_WEB_OK first frame, ${renderer.width}x${renderer.height}")
            !failed && !game.quitting
        }
    }
}
