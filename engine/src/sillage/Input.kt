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

/** Where the keys come from: the window, or your own source for tests, replays or a bot. */
fun interface KeySource { fun isDown(key: Int): Boolean }

/**
 * Keyboard, mouse and pads, as the scene sees them this frame. Key codes ([Key]) and pad button codes
 * ([Pad.CROSS]…) work alike in [isDown], [pressed] and [released] (a pad button counts on any pad), and
 * named [action]s and [axes] gather them under names the game can rebind.
 */
class Input internal constructor() {
    /** Where the keys are read each frame: the window in a game; set your own to script the keys (`KeySource { it == Key.B }`). */
    var source: KeySource = KeySource { false }
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
    /** Frames polled so far. */
    internal var frame = 0L
        private set

    /** True while [key] (a [Key] or [Pad] button code) is held. */
    fun isDown(key: Int): Boolean = when (key) {
        Key.SHIFT -> isDown(Key.LEFT_SHIFT) || isDown(Key.RIGHT_SHIFT)
        in 0 until 256 -> now[key]
        else -> Pad.isButton(key) && hub.anyDown(key)
    }

    /** True on the frame [key] went down. */
    fun pressed(key: Int): Boolean = when (key) {
        Key.SHIFT -> pressed(Key.LEFT_SHIFT) || pressed(Key.RIGHT_SHIFT)
        in 0 until 256 -> now[key] && !was[key]
        else -> Pad.isButton(key) && swallowed != frame && hub.anyPressed(key)
    }

    /** True on the frame [key] was let go. */
    fun released(key: Int): Boolean = when (key) {
        Key.SHIFT -> released(Key.LEFT_SHIFT) || released(Key.RIGHT_SHIFT)
        in 0 until 256 -> !now[key] && was[key]
        else -> Pad.isButton(key) && hub.anyReleased(key)
    }

    /** −1, 0 or 1 from two keys: `axis(Key.A, Key.D)`. */
    fun axis(negative: Int, positive: Int): Float = (if (isDown(positive)) 1f else 0f) - (if (isDown(negative)) 1f else 0f)

    /** [isDown] for one pad ([slot] ≥ 0) or any (−1). */
    internal fun isDown(code: Int, slot: Int): Boolean =
        if (slot >= 0 && Pad.isButton(code)) hub.at(slot)?.isDown(code) ?: false else isDown(code)

    internal fun wasDown(code: Int, slot: Int): Boolean = when {
        code == Key.SHIFT -> was[Key.LEFT_SHIFT] || was[Key.RIGHT_SHIFT]
        code in 0 until 256 -> was[code]
        !Pad.isButton(code) -> false
        swallowed == frame -> true
        slot >= 0 -> hub.at(slot)?.let { it.was and (1 shl (code - Pad.BUTTON_BASE)) != 0 } ?: false
        else -> hub.anyWasDown(code)
    }

    internal fun pressed(code: Int, slot: Int): Boolean =
        if (slot >= 0 && Pad.isButton(code)) swallowed != frame && (hub.at(slot)?.pressed(code) ?: false) else pressed(code)

    /** The frame whose pad presses the interface took (a cross that closed the pause menu must not also jump). */
    private var swallowed = -1L
    internal fun swallowPadPresses() { swallowed = frame }

    /** 0..1 for one input: a trigger's pull, else 1 while held. */
    internal fun value(code: Int, slot: Int): Float {
        if (code != Pad.L2 && code != Pad.R2) return if (isDown(code, slot)) 1f else 0f
        if (slot >= 0) return hub.at(slot)?.trigger(code) ?: 0f
        var v = 0f
        for (i in 0 until hub.connected.size) v = maxOf(v, hub.connected[i].trigger(code))
        return v
    }

    /** The stick of pad [slot], or the most pushed one of all pads (−1); null without pads. */
    internal fun stick(which: Stick, slot: Int): Thumbstick? {
        if (slot >= 0) return hub.at(slot)?.stick(which)
        var best: Thumbstick? = null
        for (i in 0 until hub.connected.size) { val s = hub.connected[i].stick(which); if (best == null || s.amount > best.amount) best = s }
        return best
    }

    /** The pad whose motion sensors aim: pad [slot], or the first that has them. */
    internal fun motionPad(slot: Int): Pad? {
        if (slot >= 0) return hub.at(slot)?.takeIf { it.hasMotion }
        for (i in 0 until hub.connected.size) if (hub.connected[i].hasMotion) return hub.connected[i]
        return null
    }

    // ------------------------------------------------------------------ pads

    internal var hub = PadHub()
    private var greeted = false
    private val connectedBlocks = ArrayList<(Pad) -> Unit>()
    private val disconnectedBlocks = ArrayList<(Pad) -> Unit>()

    /**
     * Where the pads come from: the computer's ([PadSource.system]) in a game with a window, none
     * without one. Plug [VirtualPad]s to drive the scene from code.
     */
    var padSource: PadSource
        get() = hub.source
        set(v) { hub.source = v }

    /** The pads connected now, by slot. */
    val pads: List<Pad> get() = hub.connected
    /** The first pad connected (the lowest slot), or null. */
    val pad: Pad? get() = hub.connected.firstOrNull()
    /** The pad of [slot], connected or not (a pad unplugged keeps its slot), or null if the slot was never used. */
    fun pad(slot: Int): Pad? = hub.slots.getOrNull(slot)

    /** Adds a pad driven by code (alongside the real ones). */
    fun plug(pad: VirtualPad) = hub.plug(pad)
    fun unplug(pad: VirtualPad) = hub.unplug(pad)

    /** Runs [block] when a pad connects (and, for a new scene, for each pad already there). */
    fun onPadConnected(block: (Pad) -> Unit) { connectedBlocks.add(block) }
    /** Runs [block] when a pad is unplugged, switched off or out of range. */
    fun onPadDisconnected(block: (Pad) -> Unit) { disconnectedBlocks.add(block) }

    /** When true (the default) a DualSense gets its slot's light bar colour and player LEDs when it connects. */
    var padLights = true

    internal fun fire(p: Pad, connected: Boolean) {
        val blocks = if (connected) connectedBlocks else disconnectedBlocks
        for (i in 0 until blocks.size) blocks[i](p)
    }

    /** Takes the pads of [other] (the scene before), slots and all. */
    internal fun adoptPads(other: Input) { hub = other.hub }

    // ------------------------------------------------------------------ actions

    private val actionMap = LinkedHashMap<String, Action>()
    private val axesMap = LinkedHashMap<String, Axes>()

    /**
     * The action [name], made if new; with [inputs], its bindings become these:
     * `input.action("jump", Key.SPACE, Pad.CROSS)`, then `input.pressed("jump")` or `jump.pressed`.
     */
    fun action(name: String, vararg inputs: Int): Action {
        val a = actionMap.getOrPut(name) { Action(this, name) }
        if (inputs.isNotEmpty()) a.rebind(*inputs)
        return a
    }

    /** Every action made, in the order they were made (for a settings menu). */
    val actions: Collection<Action> get() = actionMap.values

    /** The axes [name], made if new (with no binding). */
    fun axes(name: String): Axes = axesMap.getOrPut(name) { Axes(this, name) }

    /**
     * The axes [name], bound to four keys or buttons and a stick:
     * `input.axes("move", Key.A, Key.D, Key.S, Key.W, Stick.LEFT)`, then `move.x`, `move.y`.
     */
    fun axes(name: String, left: Int, right: Int, down: Int, up: Int, stick: Stick? = null): Axes =
        axes(name).apply { rebind(left, right, down, up); this.stick = stick }

    private fun named(name: String) = actionMap[name] ?: throw IllegalArgumentException("No action « $name » (make it with input.action)")
    fun isDown(action: String): Boolean = named(action).isDown
    fun pressed(action: String): Boolean = named(action).pressed
    fun released(action: String): Boolean = named(action).released
    /** 0..1: see [Action.value]. */
    fun value(action: String): Float = named(action).value

    /**
     * The key or pad button that went down this frame (keys first), or −1: what a settings menu waits for
     * when the player picks a new binding.
     */
    fun firstPressed(): Int {
        for (k in 0 until 256) if (now[k] && !was[k]) return k
        for (b in 0 until Pad.BUTTON_COUNT) if (hub.anyPressed(Pad.BUTTON_BASE + b)) return Pad.BUTTON_BASE + b
        return -1
    }

    internal fun poll() {
        now.copyInto(was); val d = source; for (k in 0 until 256) now[k] = d.isDown(k)
        if (!greeted) { greeted = true; for (i in 0 until hub.connected.size) fire(hub.connected[i], true) }
        hub.poll(this)
        frame++
    }

    companion object {
        private val KEY_NAMES: Map<Int, String> by lazy {
            mapOf(1 to "ESCAPE", 2 to "DIGIT_1", 3 to "DIGIT_2", 4 to "DIGIT_3", 5 to "DIGIT_4", 6 to "DIGIT_5", 7 to "DIGIT_6", 8 to "DIGIT_7", 9 to "DIGIT_8", 10 to "DIGIT_9", 11 to "DIGIT_0", 12 to "MINUS", 13 to "EQUAL", 14 to "BACKSPACE", 15 to "TAB", 16 to "Q", 17 to "W", 18 to "E", 19 to "R", 20 to "T", 21 to "Y", 22 to "U", 23 to "I", 24 to "O", 25 to "P", 26 to "LEFT_BRACKET", 27 to "RIGHT_BRACKET", 28 to "ENTER", 29 to "LEFT_CTRL", 30 to "A", 31 to "S", 32 to "D", 33 to "F", 34 to "G", 35 to "H", 36 to "J", 37 to "K", 38 to "L", 39 to "SEMICOLON", 40 to "APOSTROPHE", 41 to "GRAVE", 42 to "LEFT_SHIFT", 43 to "BACKSLASH", 44 to "Z", 45 to "X", 46 to "C", 47 to "V", 48 to "B", 49 to "N", 50 to "M", 51 to "COMMA", 52 to "PERIOD", 53 to "SLASH", 54 to "RIGHT_SHIFT", 56 to "LEFT_ALT", 57 to "SPACE", 58 to "CAPS_LOCK", 59 to "F1", 60 to "F2", 61 to "F3", 62 to "F4", 63 to "F5", 64 to "F6", 65 to "F7", 66 to "F8", 67 to "F9", 68 to "F10", 87 to "F11", 88 to "F12", 97 to "RIGHT_CTRL", 100 to "RIGHT_ALT", 102 to "HOME", 103 to "UP", 104 to "PAGE_UP", 105 to "LEFT", 106 to "RIGHT", 107 to "END", 108 to "DOWN", 109 to "PAGE_DOWN", 110 to "INSERT", 111 to "DELETE", -1 to "SHIFT")
        }

        /** A readable name for a key or pad button code: "SPACE", "CROSS"… */
        fun codeName(code: Int): String = Pad.buttonName(code) ?: KEY_NAMES[code] ?: "#$code"
    }
}
