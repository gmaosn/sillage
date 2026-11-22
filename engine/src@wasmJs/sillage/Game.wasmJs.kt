package sillage

import sillage.math.Vec3

actual fun game(title: String, args: Array<String>, setup: Scene.() -> Unit): RunReport {
    WebGame.start(title, args, setup)
    return RunReport(0, 0.0, 0.0, 0.0, Vec3.ZERO)
}

internal object WebGame { fun start(title: String, args: Array<String>, setup: Scene.() -> Unit) { TODO("the page's backend") } }
