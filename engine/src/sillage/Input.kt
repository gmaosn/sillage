package sillage

/**
 * Keys by their place on the keyboard, named after a US (QWERTY) layout: [W] is the key above [S]
 * whatever is printed on it — Z on a French keyboard. Games thus keep the same hands-on layout
 * everywhere (WASD in QWERTY is ZQSD in AZERTY).
 */
object Key {
    const val ESCAPE = 1
    const val DIGIT_1 = 2; const val DIGIT_2 = 3; const val DIGIT_3 = 4; const val DIGIT_4 = 5; const val DIGIT_5 = 6
    const val DIGIT_6 = 7; const val DIGIT_7 = 8; const val DIGIT_8 = 9; const val DIGIT_9 = 10; const val DIGIT_0 = 11
    const val MINUS = 12; const val EQUAL = 13; const val BACKSPACE = 14; const val TAB = 15
    const val Q = 16; const val W = 17; const val E = 18; const val R = 19; const val T = 20; const val Y = 21
    const val U = 22; const val I = 23; const val O = 24; const val P = 25
    const val LEFT_BRACKET = 26; const val RIGHT_BRACKET = 27; const val ENTER = 28; const val LEFT_CTRL = 29
    const val A = 30; const val S = 31; const val D = 32; const val F = 33; const val G = 34; const val H = 35
    const val J = 36; const val K = 37; const val L = 38; const val SEMICOLON = 39; const val APOSTROPHE = 40
    const val GRAVE = 41; const val LEFT_SHIFT = 42; const val BACKSLASH = 43
    const val Z = 44; const val X = 45; const val C = 46; const val V = 47; const val B = 48; const val N = 49; const val M = 50
    const val COMMA = 51; const val PERIOD = 52; const val SLASH = 53; const val RIGHT_SHIFT = 54; const val LEFT_ALT = 56
    const val SPACE = 57; const val CAPS_LOCK = 58
    const val F1 = 59; const val F2 = 60; const val F3 = 61; const val F4 = 62; const val F5 = 63; const val F6 = 64
    const val F7 = 65; const val F8 = 66; const val F9 = 67; const val F10 = 68; const val F11 = 87; const val F12 = 88
    const val RIGHT_CTRL = 97; const val RIGHT_ALT = 100; const val HOME = 102; const val UP = 103; const val PAGE_UP = 104
    const val LEFT = 105; const val RIGHT = 106; const val END = 107; const val DOWN = 108; const val PAGE_DOWN = 109
    const val INSERT = 110; const val DELETE = 111
    /** Either shift key, for [Input.isDown]. */
    const val SHIFT = -1
}

/** Keyboard and mouse, as the scene sees them this frame. */
class Input internal constructor() {
    internal var down: (Int) -> Boolean = { false }
    private val was = BooleanArray(256)
    private val now = BooleanArray(256)
    /** Mouse motion since the last frame (pixels; only while the mouse is captured) and wheel turns. */
    var mouseDelta: Pair<Float, Float> = 0f to 0f
        internal set
    var wheel: Float = 0f
        internal set
    /** True while the window holds the mouse (Tab toggles it). */
    var mouseCaptured: Boolean = false
        internal set

    /** True while [key] is held. */
    fun isDown(key: Int): Boolean = when (key) {
        Key.SHIFT -> isDown(Key.LEFT_SHIFT) || isDown(Key.RIGHT_SHIFT)
        in 0 until 256 -> now[key]
        else -> false
    }

    /** True on the frame [key] went down. */
    fun pressed(key: Int): Boolean = when (key) {
        Key.SHIFT -> pressed(Key.LEFT_SHIFT) || pressed(Key.RIGHT_SHIFT)
        in 0 until 256 -> now[key] && !was[key]
        else -> false
    }

    /** −1, 0 or 1 from two keys: `axis(Key.A, Key.D)`. */
    fun axis(negative: Int, positive: Int): Float = (if (isDown(positive)) 1f else 0f) - (if (isDown(negative)) 1f else 0f)

    internal fun poll() { now.copyInto(was); for (k in 0 until 256) now[k] = down(k) }
}
