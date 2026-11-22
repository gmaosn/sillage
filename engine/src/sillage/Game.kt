package sillage

import sillage.math.Vec3

/**
 * What a run measured: frames drawn, CPU time per frame (mean, 99th percentile, worst; without the first
 * frames, while the JIT compiles), the GPU's time per frame (median, 0 if the GPU cannot time) and where
 * the player ended.
 */
class RunReport(val frames: Int, val meanMs: Double, val p99Ms: Double, val maxMs: Double, val playerPosition: Vec3, val gpuMs: Double = 0.0,
                /** Milliseconds from the start of the JVM to the first frame drawn. */
                val firstFrameMs: Long = 0)

/**
 * Opens a window (on the web: takes the page's canvas; on a phone: the screen) and plays the scene built by [setup] until the window closes (or Quit in the pause menu
 * that Escape opens, see [Hud.pauseMenu]). Tab captures and frees the mouse. [args] are the program's arguments; `--help` lists the options
 * (running without a window, captures, recordings…).
 * On a desktop it returns when the game ends; in a page or on a phone the system calls the game frame by frame,
 * so it returns at once (with an empty report) and the game goes on.
 */
expect fun game(title: String = "Sillage", args: Array<String> = emptyArray(), setup: Scene.() -> Unit): RunReport
