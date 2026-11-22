package sillage

import sillage.math.Vec3

/**
 * On Android the system starts an activity, not a `main`: a game's activity extends
 * [sillage.android.GameActivity] and hands it the game's own `main`. Called from there, [game] gives
 * the scene to the activity, which plays it on the screen, and returns at once.
 */
actual fun game(title: String, args: Array<String>, setup: Scene.() -> Unit): RunReport {
    AndroidGame.pending = AndroidGame.Request(title, args, setup)
    return RunReport(0, 0.0, 0.0, 0.0, Vec3.ZERO)
}

internal object AndroidGame {
    class Request(val title: String, val args: Array<String>, val setup: Scene.() -> Unit)
    @Volatile var pending: Request? = null
}
