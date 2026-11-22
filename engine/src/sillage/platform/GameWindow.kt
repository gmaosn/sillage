package sillage.platform

/** Number of key codes the windows track (Linux evdev codes, see [sillage.Key]). */
internal const val KEY_COUNT = 256

/**
 * What a game's loop asks of the surface it plays on, whatever it is: a desktop window, a page's canvas,
 * a phone's screen. Keys are [sillage.Key] codes (a touch screen has none and answers false).
 */
internal interface GameWindow {
    val shouldClose: Boolean
    /** True while the window has keyboard focus. */
    val focused: Boolean
    /** When true the cursor is hidden and held in place; mouse motion arrives as relative deltas. */
    var mouseCaptured: Boolean
    var title: String

    /** Handles pending events without blocking. */
    fun poll()
    /** Waits up to [seconds] for an event, then handles what arrived. */
    fun waitEvents(seconds: Double)
    /** True while [key] is held, or if it went down since the last [clearPresses] (a tap shorter than a frame still counts). */
    fun isDown(key: Int): Boolean
    /** Forgets the keys that went down and up again since the last call: the game has seen them. */
    fun clearPresses()
    /** Mouse motion accumulated while captured since the last call, in pixels: (dx, dy). */
    fun consumeMouseDelta(): FloatArray
    /** Wheel notches since the last call, positive away from the user. */
    fun consumeScroll(): Float
    /** True once after the drawable size changed. */
    fun consumeResize(): Boolean
    /** The drawable's size in pixels: width, height. */
    fun size(): IntArray

    /** Pointer position in pixels of the drawable, NaN when it is outside the window. */
    val pointerX: Float
    val pointerY: Float
    /** True while the left button is held, or if it went down since the last call (a click shorter than a frame still counts). */
    fun buttonDown(): Boolean
    /** Characters typed since the last call, through the keyboard layout (see [KeyboardText]). */
    fun consumeText(): String
    /** When true (the default), a left click in the window captures the mouse; the interface turns it off over its widgets and menus. */
    var clickCaptures: Boolean
}
