package sillage.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

private val NULL: MemorySegment = MemorySegment.NULL

/** libxkbcommon (and libxkbcommon-x11) through FFM: keymaps, keyboard state, compose sequences. */
internal object Xkb {
    private fun lib(vararg names: String): SymbolLookup? =
        names.firstNotNullOfOrNull { n -> runCatching { SymbolLookup.libraryLookup(n, Arena.global()) }.getOrNull() }
    private val lib = lib("libxkbcommon.so.0", "libxkbcommon.so")
    private val x11 = lib("libxkbcommon-x11.so.0", "libxkbcommon-x11.so")
    /** False when libxkbcommon is not installed: the windows then type through a US table. */
    val available = lib != null

    private fun h(ret: MemoryLayout?, vararg args: MemoryLayout): MethodHandle = PSig.linker.downcallHandle(
        if (ret == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(ret, *args))
    private val P = ADDRESS; private val I = JAVA_INT; private val J = JAVA_LONG; private val S = JAVA_SHORT
    private fun fn(l: SymbolLookup?, name: String): MemorySegment = l?.find(name)?.orElse(NULL) ?: NULL

    @JvmField val P_I = h(P, I)
    @JvmField val P_PPII = h(P, P, P, I, I)
    @JvmField val P_PPI = h(P, P, P, I)
    @JvmField val P_PI = h(P, P, I)
    @JvmField val P_P = h(P, P)
    @JvmField val I_PIIIIII = h(I, P, I, I, I, I, I, I)
    @JvmField val I_PII = h(I, P, I, I)
    @JvmField val I_PI = h(I, P, I)
    @JvmField val I_PIPJ = h(I, P, I, P, J)
    @JvmField val I_PPJ = h(I, P, P, J)
    @JvmField val I_P = h(I, P)
    @JvmField val V_P = h(null, P)
    @JvmField val I_PSSIPPPP = h(I, P, S, S, I, P, P, P, P)
    @JvmField val P_PJIIIJ = h(P, P, J, I, I, I, J)
    @JvmField val I_PJ = h(I, P, J)

    val contextNew = fn(lib, "xkb_context_new"); val contextUnref = fn(lib, "xkb_context_unref")
    val keymapFromString = fn(lib, "xkb_keymap_new_from_string"); val keymapFromNames = fn(lib, "xkb_keymap_new_from_names")
    val keymapUnref = fn(lib, "xkb_keymap_unref"); val keyRepeats = fn(lib, "xkb_keymap_key_repeats")
    val stateNew = fn(lib, "xkb_state_new"); val stateUnref = fn(lib, "xkb_state_unref")
    val updateMask = fn(lib, "xkb_state_update_mask"); val updateKey = fn(lib, "xkb_state_update_key")
    val keyGetOneSym = fn(lib, "xkb_state_key_get_one_sym"); val keyGetUtf8 = fn(lib, "xkb_state_key_get_utf8")
    val composeTableFromLocale = fn(lib, "xkb_compose_table_new_from_locale"); val composeTableUnref = fn(lib, "xkb_compose_table_unref")
    val composeStateNew = fn(lib, "xkb_compose_state_new"); val composeStateUnref = fn(lib, "xkb_compose_state_unref")
    val composeFeed = fn(lib, "xkb_compose_state_feed"); val composeStatus = fn(lib, "xkb_compose_state_get_status")
    val composeUtf8 = fn(lib, "xkb_compose_state_get_utf8"); val composeReset = fn(lib, "xkb_compose_state_reset")
    val x11Setup = fn(x11, "xkb_x11_setup_xkb_extension"); val x11Device = fn(x11, "xkb_x11_get_core_keyboard_device_id")
    val x11Keymap = fn(x11, "xkb_x11_keymap_new_from_device"); val x11State = fn(x11, "xkb_x11_state_new_from_device")

    private val libc = PSig.linker.defaultLookup()
    val mmap: MemorySegment = libc.find("mmap").orElseThrow(); val munmap: MemorySegment = libc.find("munmap").orElseThrow()
}

/**
 * What the keys type, through the keyboard layout of the desktop: libxkbcommon's keymap (the compositor's
 * under Wayland, the X server's or the default one under X11), its state (Shift, AltGr, Caps Lock…) and
 * the compose table of the locale (dead keys: ^ then e gives ê on a French keyboard). Without
 * libxkbcommon, a US table. Keys are Linux evdev codes; held keys repeat their text when the window asks
 * for it (Wayland leaves repeating to clients, X11 repeats by itself).
 */
internal class KeyboardText : AutoCloseable {
    private val arena = Arena.ofConfined()
    private val buf = arena.allocate(64)
    private var context = NULL; private var keymap = NULL; private var state = NULL
    private var composeTable = NULL; private var compose = NULL
    private val out = StringBuilder()
    private val down = BooleanArray(KEY_COUNT)
    // Repeat of the last key that typed something: its code, text, when it went down, how many repeats so far.
    private var repeatKey = -1; private var repeatText = ""; private var repeatSince = 0L; private var repeatCount = 0
    /** Repeats per second and delay before the first, in milliseconds (Wayland's repeat_info). Rate 0: none. */
    var repeatRate = 25; var repeatDelay = 600
    /** True if this keyboard repeats held keys itself (Wayland); X11 sends repeated presses. */
    var repeats = true

    init {
        if (Xkb.available) context = Xkb.P_I.invokeExact(Xkb.contextNew, 0) as MemorySegment
    }

    val usesXkb: Boolean get() = state != NULL
    /** Where the layout comes from, for the log: the compositor's, the X server's, compiled from names, or the US table. */
    var origin = if (Xkb.available) "none yet" else "US table (no libxkbcommon)"; private set

    /** The keymap as xkb text (Wayland's keymap event). */
    fun keymapText(text: String) {
        if (context == NULL) return
        val km = Xkb.P_PPII.invokeExact(Xkb.keymapFromString, context, arena.allocateFrom(text), 1, 0) as MemorySegment
        if (km != NULL) { use(km, NULL); origin = "compositor (xkb)" }
    }

    /** Reads the keymap Wayland hands over as a file descriptor, then closes it. */
    fun keymapFd(fd: Int, size: Int) {
        try {
            if (context == NULL || size <= 1) return
            val p = Xkb.P_PJIIIJ.invokeExact(Xkb.mmap, NULL, size.toLong(), 1 /* PROT_READ */, 2 /* MAP_PRIVATE */, fd, 0L) as MemorySegment
            if (p.address() == -1L || p == NULL) return
            try { keymapText(p.reinterpret(size.toLong()).getString(0)) }
            finally { (Xkb.I_PJ.invokeExact(Xkb.munmap, p, size.toLong()) as Int) }
        } finally { Native.close(fd) }
    }

    /** A keymap from rule names (layout « fr », variant…), as xkbcommon compiles it: for X11 without the extension, and for tests. */
    fun keymapNames(layout: String? = null, variant: String? = null, model: String? = null, options: String? = null) {
        if (context == NULL) return
        val names = arena.allocate(40, 8)
        val v = listOf(null, model, layout, variant, options)
        for ((i, s) in v.withIndex()) names.set(ADDRESS, 8L * i, s?.let { arena.allocateFrom(it) } ?: NULL)
        val km = Xkb.P_PPI.invokeExact(Xkb.keymapFromNames, context, names, 0) as MemorySegment
        if (km != NULL) { use(km, NULL); origin = "xkb names " + (layout ?: System.getenv("XKB_DEFAULT_LAYOUT") ?: "default") }
    }

    /** The X server's keymap and state, through libxkbcommon-x11; false if it cannot (then [keymapNames]). */
    fun keymapX11(connection: MemorySegment): Boolean {
        if (context == NULL || Xkb.x11Setup == NULL) return false
        val ok = Xkb.I_PSSIPPPP.invokeExact(Xkb.x11Setup, connection, 1.toShort(), 0.toShort(), 0, NULL, NULL, NULL, NULL) as Int
        if (ok == 0) return false
        val device = Xkb.I_P.invokeExact(Xkb.x11Device, connection) as Int
        if (device < 0) return false
        val km = Xkb.P_PPII.invokeExact(Xkb.x11Keymap, context, connection, device, 0) as MemorySegment
        if (km == NULL) return false
        val st = Xkb.P_PPI.invokeExact(Xkb.x11State, km, connection, device) as MemorySegment
        use(km, st)
        origin = "X server (xkb-x11)"
        return true
    }

    private fun use(km: MemorySegment, st: MemorySegment) {
        if (state != NULL) Xkb.V_P.invokeExact(Xkb.stateUnref, state)
        if (keymap != NULL) Xkb.V_P.invokeExact(Xkb.keymapUnref, keymap)
        keymap = km
        state = if (st != NULL) st else Xkb.P_P.invokeExact(Xkb.stateNew, km) as MemorySegment
        if (compose == NULL && Xkb.composeTableFromLocale != NULL) {
            val env = System.getenv()
            val locales = listOfNotNull(env["LC_ALL"], env["LC_CTYPE"], env["LANG"]).filter { it.isNotEmpty() } + "en_US.UTF-8"
            for (l in locales) {
                composeTable = Xkb.P_PPI.invokeExact(Xkb.composeTableFromLocale, context, arena.allocateFrom(l), 0) as MemorySegment
                if (composeTable != NULL) break
            }
            if (composeTable != NULL) compose = Xkb.P_PI.invokeExact(Xkb.composeStateNew, composeTable, 0) as MemorySegment
        }
    }

    /** Wayland's modifiers event: Shift, AltGr, Caps Lock… as the compositor sees them. */
    fun modifiers(depressed: Int, latched: Int, locked: Int, group: Int) {
        if (state != NULL) (Xkb.I_PIIIIII.invokeExact(Xkb.updateMask, state, depressed, latched, locked, 0, 0, group) as Int)
    }

    /**
     * Key [code] (evdev) went down or up at [now] nanoseconds. [tracksModifiers]: update the state from the
     * key itself (X11 and tests; Wayland sends the modifiers apart).
     */
    fun key(code: Int, pressed: Boolean, now: Long, tracksModifiers: Boolean = false) {
        if (code !in 0 until KEY_COUNT) return
        val was = down[code]; down[code] = pressed
        if (!pressed) { if (code == repeatKey) repeatKey = -1; if (tracksModifiers && state != NULL) (Xkb.I_PII.invokeExact(Xkb.updateKey, state, code + 8, 0) as Int); return }
        val text = if (state != NULL) xkbText(code) else usText(code)
        if (tracksModifiers && state != NULL && !was) (Xkb.I_PII.invokeExact(Xkb.updateKey, state, code + 8, 1) as Int)
        if (text.isEmpty()) return
        out.append(text)
        val repeatable = state == NULL || (Xkb.I_PI.invokeExact(Xkb.keyRepeats, keymap, code + 8) as Int) != 0
        if (repeats && repeatable && text.length <= 2) { repeatKey = code; repeatText = text; repeatSince = now; repeatCount = 0 } else repeatKey = -1
    }

    private fun xkbText(code: Int): String {
        val kc = code + 8
        val sym = Xkb.I_PI.invokeExact(Xkb.keyGetOneSym, state, kc) as Int
        if (compose != NULL && sym != 0 && (Xkb.I_PI.invokeExact(Xkb.composeFeed, compose, sym) as Int) == 1) {
            when (Xkb.I_P.invokeExact(Xkb.composeStatus, compose) as Int) {
                1 -> return ""                                                   // composing: wait for the next key
                2 -> { val n = Xkb.I_PPJ.invokeExact(Xkb.composeUtf8, compose, buf, 64L) as Int
                    Xkb.V_P.invokeExact(Xkb.composeReset, compose); return keep(utf8(n)) }
                3 -> { Xkb.V_P.invokeExact(Xkb.composeReset, compose); return "" } // a sequence that leads nowhere
            }
        }
        val n = Xkb.I_PIPJ.invokeExact(Xkb.keyGetUtf8, state, kc, buf, 64L) as Int
        return keep(utf8(n))
    }

    private fun utf8(n: Int): String = if (n <= 0) "" else String(buf.asSlice(0, minOf(n, 63).toLong()).toArray(JAVA_BYTE), Charsets.UTF_8)

    /** Text, and Ctrl+A, C, V, X: no other control character (Enter, Tab, Backspace… are read as keys). */
    private fun keep(s: String): String = if (s.all { it >= ' ' && it != '\u007f' || it in SHORTCUTS }) s else s.filter { it >= ' ' && it != '\u007f' || it in SHORTCUTS }

    private fun usText(code: Int): String {
        val shift = down[42] || down[54]; val ctrl = down[29] || down[97]
        val i = US_CODES.indexOf(code)
        if (i < 0) return ""
        val c = (if (shift) US_SHIFTED else US_PLAIN)[i]
        if (ctrl) return if (c.lowercaseChar() in "acvx") (c.lowercaseChar() - 'a' + 1).toChar().toString() else ""
        return c.toString()
    }

    /** Text from held keys repeating ([repeatRate] per second after [repeatDelay] ms). */
    fun repeat(now: Long) {
        if (repeatKey < 0 || !repeats || repeatRate <= 0) return
        val elapsed = (now - repeatSince) / 1_000_000 - repeatDelay
        if (elapsed < 0) return
        val due = (elapsed * repeatRate / 1000 + 1).toInt()
        while (repeatCount < due) { out.append(repeatText); repeatCount++ }
    }

    /** What was typed since the last call. */
    fun take(): String { if (out.isEmpty()) return ""; val s = out.toString(); out.setLength(0); return s }

    /** Keys released (focus lost): no more repeats, no half-typed compose sequence. */
    fun reset() { down.fill(false); repeatKey = -1; if (compose != NULL) Xkb.V_P.invokeExact(Xkb.composeReset, compose) }

    override fun close() {
        if (compose != NULL) Xkb.V_P.invokeExact(Xkb.composeStateUnref, compose)
        if (composeTable != NULL) Xkb.V_P.invokeExact(Xkb.composeTableUnref, composeTable)
        if (state != NULL) Xkb.V_P.invokeExact(Xkb.stateUnref, state)
        if (keymap != NULL) Xkb.V_P.invokeExact(Xkb.keymapUnref, keymap)
        if (context != NULL) Xkb.V_P.invokeExact(Xkb.contextUnref, context)
        compose = NULL; composeTable = NULL; state = NULL; keymap = NULL; context = NULL
        arena.close()
    }

    companion object {
        private const val SHORTCUTS = "\u0001\u0003\u0016\u0018"
        // The US layout by evdev code, for computers without libxkbcommon.
        private val US_CODES = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
            30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 57)
        private const val US_PLAIN = "1234567890-=qwertyuiop[]asdfghjkl;'`\\zxcvbnm,./ "
        private const val US_SHIFTED = "!@#$%^&*()_+QWERTYUIOP{}ASDFGHJKL:\"~|ZXCVBNM<>? "
    }
}
