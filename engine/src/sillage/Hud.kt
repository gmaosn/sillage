package sillage

import sillage.platform.Window
import sillage.ui.GlyphAtlas
import sillage.ui.HUD_FLOATS
import sillage.ui.LaidText
import sillage.ui.Painter
import sillage.ui.TextLayout
import sillage.ui.styledText
import kotlin.math.max
import kotlin.math.min

/**
 * What the interface reads besides the keys (those come from [Input]): the pointer, in canvas units, and
 * the text typed. The window is the source in a game; set [Hud.source] to a [UiScript] (or your own) to
 * drive menus and fields from a test, a replay or a scripted capture.
 */
interface UiSource {
    /** Pointer position in canvas units (the canvas is 600 high), NaN when there is no pointer. */
    fun pointerX(): Float
    fun pointerY(): Float
    /** True while the main button is held. */
    fun pointerDown(): Boolean
    /**
     * The characters typed since the last call, in order ("" if none): the keyboard layout applied, dead
     * keys composed; Ctrl+A, Ctrl+C, Ctrl+V and Ctrl+X arrive as the control characters 1, 3, 22 and 24.
     */
    fun typed(): String
}

/** A [UiSource] driven by code: put the pointer somewhere, hold the button, type. */
class UiScript : UiSource {
    var x = Float.NaN
    var y = Float.NaN
    var down = false
    private val pending = StringBuilder()
    /** Types [text] (it reaches the interface at the next frame). */
    fun type(text: String) { pending.append(text) }
    /** Moves the pointer to ([x], [y]) canvas units. */
    fun pointAt(x: Number, y: Number) { this.x = x.toFloat(); this.y = y.toFloat() }
    override fun pointerX() = x
    override fun pointerY() = y
    override fun pointerDown() = down
    override fun typed(): String { if (pending.isEmpty()) return ""; val s = pending.toString(); pending.setLength(0); return s }
}

/**
 * What is drawn over the scene: texts, panels, laid-out boxes, buttons, text fields and menus, on a canvas
 * 600 units high (its width follows the window's shape), each placed from an [Anchor] — a corner, an
 * edge's middle or the centre. Texts can be fixed or computed every frame: `hud.text({ "SCORE $score" }, 24, 24)`.
 * The HUD is rebuilt only when something it shows changes.
 *
 * Texts use the engine's pixel font unless a [Font] is given (per text, or for the whole HUD with [font]).
 * Escape opens the [pauseMenu]: the game's time and physics stop, the mouse is free.
 */
class Hud internal constructor() {
    enum class Anchor { TOP_LEFT, TOP, TOP_RIGHT, LEFT, CENTER, RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT }
    /** How a text sits on its anchor point; [AUTO] follows the anchor (left for the left side…). */
    enum class Align { AUTO, LEFT, CENTER, RIGHT }

    /**
     * Something on the HUD; hide it with [visible] or take it away with [Hud.remove]. [opacity] lets the
     * scene show through (1: solid, 0: invisible), for panels that do not hide the game or a text that fades.
     */
    sealed class Element(x: Float, y: Float, anchor: Anchor) {
        internal var owner: Hud? = null
        internal fun dirty() { owner?.dirty = true }
        var x = x; set(v) { if (field != v) { field = v; dirty() } }
        var y = y; set(v) { if (field != v) { field = v; dirty() } }
        var anchor = anchor; set(v) { if (field != v) { field = v; dirty() } }
        var visible = true; set(v) { if (field != v) { field = v; dirty() } }
        var opacity = 1f; set(v) { if (field != v) { field = v; dirty() } }
    }

    /**
     * A line of text (or several: '\n', or [wrap]). [size] is its scale: the pixel font's pixel, so that
     * capitals are 7 × size units high — in a system font too, so a game can change fonts without moving
     * anything. [shadow] and [outline] keep it readable over a bright scene.
     */
    class Text internal constructor(var value: () -> String, x: Float, y: Float, size: Float, color: Color, anchor: Anchor, align: Align) : Element(x, y, anchor) {
        var size = size; set(v) { if (field != v) { field = v; dirty() } }
        var color = color; set(v) { if (field != v) { field = v; dirty() } }
        var align = align; set(v) { if (field != v) { field = v; dirty() } }
        /** Its font; null: the HUD's [Hud.font]. */
        var font: Font? = null; set(v) { if (field != v) { field = v; dirty() } }
        /** Width in canvas units at which lines break between words (0: only at '\n'). */
        var wrap = 0f; set(v) { if (field != v) { field = v; dirty() } }
        /** A soft copy below and to the right, in this colour (null: none). */
        var shadow: Color? = null; set(v) { if (field != v) { field = v; dirty() } }
        /** A ring around each letter, in this colour (null: none), [outlineWidth] units wide. */
        var outline: Color? = null; set(v) { if (field != v) { field = v; dirty() } }
        var outlineWidth = .6f; set(v) { if (field != v) { field = v; dirty() } }
        internal var shown: String? = null
        internal val laid = LaidText()
    }

    /** A rectangle; [radius] rounds its corners. */
    class Panel internal constructor(x: Float, y: Float, width: Float, height: Float, color: Color, anchor: Anchor) : Element(x, y, anchor) {
        var width = width; set(v) { if (field != v) { field = v; dirty() } }
        var height = height; set(v) { if (field != v) { field = v; dirty() } }
        var color = color; set(v) { if (field != v) { field = v; dirty() } }
        var radius = 0f; set(v) { if (field != v) { field = v; dirty() } }
    }

    /**
     * Boxes laid out by themselves ([Hud.column], [Hud.row]): the [root] box takes the size of its content,
     * and the whole is placed from its anchor like any element. Add children with the builder functions.
     */
    open class Layout internal constructor(x: Float, y: Float, anchor: Anchor, val root: Box) : Element(x, y, anchor), UiBuilder {
        override val box: Box get() = root
    }

    /**
     * A menu: a panel in the middle of the screen over a veil, a [title], buttons (and anything else) in a
     * column. While it is open it has the keyboard (arrows, Tab, Enter, Space), the mouse is free and, if
     * [pauses], the game stands still. Escape runs [onBack] (by default: [close]).
     */
    class Menu internal constructor(title: String?, val pauses: Boolean, root: Box, private val content: Box) : Layout(0f, 0f, Anchor.CENTER, root) {
        override val box: Box get() = content
        /** The title above the buttons (null: none). */
        val title: Label? = title?.let { Label({ it }, 4f, null, null, 0f) }
        /** What Escape does while the menu is on top; null: nothing. */
        var onBack: (() -> Unit)? = { close() }
        /** How dark the veil over the game is (0: none). */
        var veil = .38f; set(v) { if (field != v) { field = v; dirty() } }
        val isOpen: Boolean get() = owner?.openMenus?.contains(this) == true
        internal var appear = 1f

        /** Shows the menu on top of the others, the first button focused. */
        fun open() { val h = owner ?: return; if (!isOpen) { h.openMenus.add(this); appear = 0f; visible = true; h.menuOpened(this) } }
        fun close() { val h = owner ?: return; if (h.openMenus.remove(this)) h.menuClosed(this) }
        fun toggle() = if (isOpen) close() else open()
    }

    /** The interface's look: colours, corner radius, sizes. Changing it redraws the HUD. */
    class Theme internal constructor(private val hud: Hud) {
        private fun <T> changed(old: T, new: T): T { if (old != new) hud.dirty = true; return new }
        /** Panels and menus. */
        var panel: Color = Color.hex(0x153D48); set(v) { field = changed(field, v) }
        var panelOpacity = .9f; set(v) { field = changed(field, v) }
        /** Texts, titles and the focused button's ring and text. */
        var text: Color = Color.hex(0xFFEDBE); set(v) { field = changed(field, v) }
        var accent: Color = Color.hex(0xFFCE6E); set(v) { field = changed(field, v) }
        /** Buttons at rest, under the pointer, pressed. */
        var button: Color = Color.hex(0x245C6A); set(v) { field = changed(field, v) }
        var buttonHover: Color = Color.hex(0x317686); set(v) { field = changed(field, v) }
        var buttonPressed: Color = Color.hex(0x18434E); set(v) { field = changed(field, v) }
        /** Text fields. */
        var field: Color = Color.hex(0x0D2B33); set(v) { field = changed(field, v) }
        var radius = 9f; set(v) { field = changed(field, v) }
        /** Text size of buttons and fields, and of menu titles. */
        var size = 2f; set(v) { field = changed(field, v) }
        var titleSize = 4f; set(v) { field = changed(field, v) }
        /** The font of menus and laid-out texts; null: the HUD's [Hud.font]. */
        var font: Font? = null; set(v) { field = changed(field, v) }
    }

    private val elements = ArrayList<Element>()
    internal var dirty = true
    internal val openMenus = ArrayList<Menu>()

    /** The font of texts that do not name one: the pixel font by default. */
    var font: Font = Font.PIXEL; set(v) { if (field != v) { field = v; dirty = true } }
    val theme = Theme(this)

    /** Shows the frames per second in the top right corner. */
    var showFps = false
    /** Hint shown while the mouse is free (null: none). */
    var mouseHint: String? = "TAB TO CAPTURE THE MOUSE"
    /** Colours of the built-in hint panel and its text. */
    var hintPanelColor: Color = Color.hex(0x153D48)
    var hintTextColor: Color = Color.hex(0xFFEDBE)

    /** Where the pointer and typed text come from; null: the window (none without a window). */
    var source: UiSource? = null

    private fun <T : Element> add(e: T): T { e.owner = this; elements.add(e); dirty = true; return e }

    fun text(value: String, x: Number, y: Number, size: Number = 2, color: Color = Color.WHITE, anchor: Anchor = Anchor.TOP_LEFT, align: Align = Align.AUTO,
             font: Font? = null, wrap: Number = 0): Text =
        text({ value }, x, y, size, color, anchor, align, font, wrap)

    fun text(value: () -> String, x: Number, y: Number, size: Number = 2, color: Color = Color.WHITE, anchor: Anchor = Anchor.TOP_LEFT, align: Align = Align.AUTO,
             font: Font? = null, wrap: Number = 0): Text =
        add(Text(value, x.toFloat(), y.toFloat(), size.toFloat(), color, anchor, align).also { it.font = font; it.wrap = wrap.toFloat() })

    fun panel(x: Number, y: Number, width: Number, height: Number, color: Color = Color.hex(0x153D48), anchor: Anchor = Anchor.TOP_LEFT,
              opacity: Number = 1, radius: Number = 0): Panel =
        add(Panel(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), color, anchor).also { it.opacity = opacity.toFloat(); it.radius = radius.toFloat() })

    /**
     * Boxes one under the other, placed from [anchor] like any element and as large as their content:
     * `hud.column(24, 24, Hud.Anchor.TOP_RIGHT) { panel(ink, .7); text("SCORE"); text({ "$score" }, 3) }`.
     */
    fun column(x: Number = 0, y: Number = 0, anchor: Anchor = Anchor.TOP_LEFT, spacing: Number = 8, padding: Number = 0, place: Place = Place.START,
               block: Box.() -> Unit): Layout = layout(true, x, y, anchor, spacing, padding, place, block)

    /** Boxes side by side; see [column]. */
    fun row(x: Number = 0, y: Number = 0, anchor: Anchor = Anchor.TOP_LEFT, spacing: Number = 10, padding: Number = 0, place: Place = Place.CENTER,
            block: Box.() -> Unit): Layout = layout(false, x, y, anchor, spacing, padding, place, block)

    private fun layout(vertical: Boolean, x: Number, y: Number, anchor: Anchor, spacing: Number, padding: Number, place: Place, block: Box.() -> Unit): Layout {
        val root = Box(vertical).also { it.spacing = spacing.toFloat(); it.padding = padding.toFloat(); it.place = place }
        val l = add(Layout(x.toFloat(), y.toFloat(), anchor, root))
        root.owner = this
        root.block()
        return l
    }

    /**
     * A menu, closed until [Menu.open]: a [title], then what [block] adds (buttons first of all), in a panel
     * in the middle of the screen. [pauses]: the game stands still while it is open.
     *
     * ```
     * val start = hud.menu("MY GAME") { button("Play") { close() }; button("Quit") { hud.quit() } }
     * start.open()                                  // a title screen
     * ```
     */
    fun menu(title: String? = null, pauses: Boolean = true, block: Menu.() -> Unit = {}): Menu {
        val content = Box(true).also { it.spacing = 10f; it.place = Place.STRETCH }
        val root = Box(true).also { it.spacing = 18f; it.place = Place.STRETCH; it.paddingX = 34f; it.paddingY = 28f; it.radius = -1f; it.shadow = true }
        val m = Menu(title, pauses, root, content)
        m.title?.let { it.align = Align.CENTER; it.size = theme.titleSize; root.add(it) }
        root.add(content)
        m.visible = false
        add(m)
        root.owner = this
        m.block()
        return m
    }

    fun remove(element: Element) {
        if (element is Menu) element.close()
        if (elements.remove(element)) { element.owner = null; dirty = true }
        if (element === pauseDefault) pauseDefault = null
        if (element === chosenPause && pauseChosen) { chosenPause = null; pauseChosen = false }
    }
    /** Takes everything away (the pause menu comes back as the default one). */
    fun clear() {
        elements.forEach { it.owner = null }; elements.clear(); openMenus.clear(); focusedWidget = null; hoveredWidget = null; dirty = true
        pauseDefault = null; chosenPause = null; pauseChosen = false
    }

    // ------------------------------------------------------------------ the pause menu

    private var pauseChosen = false
    private var pauseDefault: Menu? = null
    internal var scene: Scene? = null

    /**
     * The menu Escape opens (Resume, Restart, Sound, Quit by default, in English; [pauseMenu] with other
     * words makes it again). Set your own menu, or null for Escape to quit the game at once, as before.
     */
    var pauseMenu: Menu?
        get() = if (pauseChosen) chosenPause else pauseDefault ?: pauseMenu().also { pauseDefault = it; pauseChosen = false }
        set(v) { chosenPause = v; pauseChosen = true }
    private var chosenPause: Menu? = null

    /**
     * Makes the standard pause menu with these words and sets it as [pauseMenu]: [resume] closes it,
     * [restart] restarts the scene (null: no such button), [sound] mutes or unmutes (shown as « on » or
     * « off »; null: none), [quit] ends the game.
     */
    fun pauseMenu(title: String = "PAUSE", resume: String = "Resume", restart: String? = "Restart",
                  sound: Pair<String, String>? = "Sound: on" to "Sound: off", quit: String = "Quit"): Menu {
        (if (pauseChosen) chosenPause else pauseDefault)?.let { remove(it) }
        val m = menu(title, pauses = true) {
            button(resume) { close() }
            if (restart != null) button(restart) { scene?.restart(); close() }
            if (sound != null) button({ if ((scene?.audio?.volume ?: 1f) > 0f) sound.first else sound.second }) { scene?.toggleMute() }
            button(quit) { this@Hud.quit() }
        }
        chosenPause = m; pauseChosen = true
        return m
    }

    /** Opens the pause menu (as Escape does). */
    fun pause() { pauseMenu?.open() }

    /** Ends the game at the end of this frame (a menu's Quit). */
    fun quit() { quitting = true }
    internal var quitting = false

    /** True while a menu that [Menu.pauses] is open: the game's time and physics stand still. */
    val paused: Boolean get() { for (i in 0 until openMenus.size) if (openMenus[i].pauses) return true; return false }   // read every frame: no iterator
    /** The menu on top, if any is open. */
    val topMenu: Menu? get() = openMenus.lastOrNull()
    /** True while the interface takes the keys: a menu is open or a widget has the focus. */
    val usesKeys: Boolean get() = openMenus.isNotEmpty() || focusedWidget != null

    // ------------------------------------------------------------------ focus

    /** The widget with the keyboard's focus (a button or a text field), or null. */
    var focusedWidget: Widget? = null
        private set
    /** The widget under the pointer, or null. */
    var hoveredWidget: Widget? = null
        private set
    /** Where the pointer is, in canvas units (NaN when there is none, or while the mouse is captured). */
    var pointerX = Float.NaN
        private set
    var pointerY = Float.NaN
        private set

    /** Gives the focus to [widget] (null: to nothing). */
    fun focus(widget: Widget?) {
        if (focusedWidget === widget) return
        focusedWidget = widget; dirty = true
        if (widget is TextField) { widget.blink = 0f; widget.editing.selectAll() }
    }

    /** The focus to the next widget that can take it (Tab), around the end. */
    fun focusNext() = cycle(1)
    /** The focus to the previous widget (Shift+Tab). */
    fun focusPrevious() = cycle(-1)

    private fun cycle(step: Int) {
        val list = focusables()
        if (list.isEmpty()) return
        val i = list.indexOf(focusedWidget)
        focus(list[if (i < 0) (if (step > 0) 0 else list.size - 1) else Math.floorMod(i + step, list.size)])
    }

    /**
     * The focus to the nearest widget towards ([dx], [dy]) (−1, 0 or 1 each: the arrows, a pad's stick or
     * cross); past the last one it comes back from the other side.
     */
    fun focusToward(dx: Int, dy: Int) {
        val list = focusables()
        if (list.isEmpty()) return
        val from = focusedWidget
        if (from == null || from !in list) { focus(list.first()); return }
        val fx = from.left + from.laidWidth / 2; val fy = from.top + from.laidHeight / 2
        var best: Widget? = null; var bestScore = Float.MAX_VALUE
        var far: Widget? = null; var farScore = -Float.MAX_VALUE
        for (w in list) {
            if (w === from) continue
            val ox = w.left + w.laidWidth / 2 - fx; val oy = w.top + w.laidHeight / 2 - fy
            val along = ox * dx + oy * dy; val across = kotlin.math.abs(ox * dy) + kotlin.math.abs(oy * dx)
            if (along > 1f) { val score = along + 2 * across; if (score < bestScore) { bestScore = score; best = w } }
            else if (along < -1f) { val score = -along - 2 * across; if (score > farScore) { farScore = score; far = w } }
        }
        focus(best ?: far ?: return)
    }

    /** Presses the focused widget: a button's action, a text field's [TextField.onSubmit] (Enter, Space, a pad's A). */
    fun activate() {
        when (val w = focusedWidget) {
            is Button -> press(w)
            is TextField -> w.onSubmit(w.text)
            else -> {}
        }
    }

    /** Backs out (Escape, a pad's B): a text field loses the focus, else the top menu's [Menu.onBack], else the pause menu opens. */
    fun cancel() {
        val f = focusedWidget
        val top = topMenu
        if (f is TextField && top == null) { focus(null); return }
        if (top != null) { top.onBack?.invoke(); return }
        val p = pauseMenu
        if (p != null) p.open() else quitting = true
    }

    private fun press(b: Button) { if (!b.enabled) return; b.flash = 0f; flashing = b; dirty = true; b.onClick() }
    private var flashing: Button? = null

    internal fun menuOpened(m: Menu) {
        dirty = true
        hoveredWidget = null
        focusedWidget = null
        layoutAll()
        focus(focusables().firstOrNull())
    }
    internal fun menuClosed(m: Menu) {
        dirty = true
        if (focusedWidget != null && inside(focusedWidget!!, m.root)) focusedWidget = null
        if (hoveredWidget != null && inside(hoveredWidget!!, m.root)) hoveredWidget = null
        if (openMenus.isNotEmpty()) { layoutAll(); focus(focusables().firstOrNull()) }
    }

    private fun inside(w: Widget, box: Box): Boolean {
        if (w === box) return true
        for (c in box.children) if (c === w || (c is Box && inside(w, c))) return true
        return false
    }

    /** The widgets the keys can reach now: those of the top menu, or else of every visible layout. */
    private val reachable = ArrayList<Widget>()
    private fun focusables(): List<Widget> {
        reachable.clear()
        val top = topMenu
        if (top != null) collect(top.root, reachable)
        else for (i in 0 until elements.size) { val e = elements[i]; if (e is Layout && e !is Menu && e.visible) collect(e.root, reachable) }
        return reachable
    }
    private fun collect(w: Widget, out: ArrayList<Widget>) {
        if (!w.visible) return
        if (w.focusable) out.add(w)
        if (w is Box) for (i in 0 until w.children.size) collect(w.children[i], out)
    }

    // ------------------------------------------------------------------ one frame of interaction

    private var pixelWidth = 0; private var pixelHeight = 0
    private var lastX = Float.NaN; private var lastY = Float.NaN; private var wasDown = false
    private var pressedWidget: Widget? = null
    private val held = FloatArray(256)
    private var hadPointer = false; private var recapture = false

    /** The keys as the interface reads them: its own state, from the scene's key source even while the scene's own view is masked. */
    private class Keys {
        private val was = BooleanArray(256); private val now = BooleanArray(256)
        fun poll(source: KeySource) { now.copyInto(was); for (k in 0 until 256) now[k] = source.isDown(k) }
        fun isDown(key: Int): Boolean = if (key == Key.SHIFT) now[Key.LEFT_SHIFT] || now[Key.RIGHT_SHIFT] else key in 0..255 && now[key]
        fun pressed(key: Int): Boolean = key in 0..255 && now[key] && !was[key]
    }
    private val keys = Keys()
    /** What the scene reads while the interface has the keyboard: no key at all (a space typed in a field must not make the player jump). */
    private class MaskedKeys(val inner: KeySource) : KeySource { override fun isDown(key: Int) = false }

    /** True on the frame [key] went down, then repeatedly while it is held (editing and moving keys). */
    private fun fires(input: Keys, key: Int, dt: Float): Boolean {
        if (!input.isDown(key)) { held[key] = 0f; return false }
        if (input.pressed(key)) { held[key] = 0f; return true }
        val before = held[key]; held[key] += dt
        if (held[key] < REPEAT_DELAY) return false
        return before < REPEAT_DELAY || ((held[key] - REPEAT_DELAY) * REPEAT_RATE).toInt() > ((before - REPEAT_DELAY) * REPEAT_RATE).toInt()
    }

    /**
     * Once a frame, before the scene's update: the pointer, the keys and the text typed reach menus, buttons
     * and fields; Escape opens the pause menu; the mouse is released while a menu is open, and the scene sees
     * no key while the interface has the keyboard. [width] and [height]: the picture's size in pixels.
     */
    // A gamepad's direction held: which, and when it fires again (as a key held down repeats).
    private var padDx = 0; private var padDy = 0; private var padNext = 0f

    /**
     * Gamepads drive the interface through the same calls as the keys: OPTIONS opens or closes the pause
     * menu; while a menu is open, the d-pad or the left stick moves the focus (repeating when held), cross
     * presses, circle goes back. What the interface took this frame does not reach the game.
     */
    private fun padNavigation(scene: Scene, dt: Float) {
        val input = scene.input
        val pads = input.pads
        if (pads.isEmpty()) return
        var start = false; var ok = false; var back = false; var dx = 0; var dy = 0
        for (i in pads.indices) {
            val p = pads[i]
            if (p.pressed(Pad.OPTIONS)) start = true
            if (p.pressed(Pad.CROSS)) ok = true
            if (p.pressed(Pad.CIRCLE)) back = true
            val sx = p.leftStick.x; val sy = p.leftStick.y
            if (p.isDown(Pad.DPAD_LEFT) || sx < -.6f) dx = -1 else if (p.isDown(Pad.DPAD_RIGHT) || sx > .6f) dx = 1
            if (p.isDown(Pad.DPAD_UP) || sy > .6f) dy = -1 else if (p.isDown(Pad.DPAD_DOWN) || sy < -.6f) dy = 1
        }
        val menuOpen = topMenu != null
        if (start) {
            input.swallowPadPresses()
            if (menuOpen) cancel() else pause()
            return
        }
        if (!menuOpen) { padDx = 0; padDy = 0; return }
        input.swallowPadPresses()
        if (dx != padDx || dy != padDy) {
            padDx = dx; padDy = dy
            if (dx != 0 || dy != 0) { focusToward(dx, dy); padNext = .4f }
        } else if (dx != 0 || dy != 0) {
            padNext -= dt
            if (padNext <= 0f) { focusToward(dx, dy); padNext = .12f }
        }
        if (ok) activate()
        if (back) cancel()
    }

    internal fun interact(scene: Scene, dt: Float, width: Int, height: Int, window: Window?) {
        this.scene = scene
        if (width != pixelWidth || height != pixelHeight) { pixelWidth = width; pixelHeight = height; dirty = true }
        val current = scene.input.source
        val real = (current as? MaskedKeys)?.inner ?: current
        val input = keys
        input.poll(real)
        val src = source
        val px: Float; val py: Float; val down: Boolean; val typed: String
        if (src != null) { px = src.pointerX(); py = src.pointerY(); down = src.pointerDown(); typed = src.typed() }
        else if (window != null && !window.mouseCaptured) {
            val k = 600f / max(1, height)
            px = window.pointerX * k; py = window.pointerY * k; down = window.buttonDown(); typed = window.consumeText()
        } else { px = Float.NaN; py = Float.NaN; down = false; window?.buttonDown(); typed = window?.consumeText() ?: "" }

        // Animations: menus appearing, buttons flashing, the caret blinking.
        for (i in 0 until openMenus.size) { val m = openMenus[i]; if (m.appear < 1f) { m.appear = min(1f, m.appear + dt / APPEAR); dirty = true } }
        flashing?.let { b -> if (b.flash < FLASH) { b.flash += dt; dirty = true } else flashing = null }
        val f = focusedWidget
        if (f is TextField) { val was = f.blink % BLINK < BLINK / 2; f.blink += dt; if (was != (f.blink % BLINK < BLINK / 2)) dirty = true }

        if (input.pressed(Key.ESCAPE)) cancel()

        // Keys, while the interface has them.
        if (usesKeys) {
            val shift = input.isDown(Key.SHIFT); val ctrl = input.isDown(Key.LEFT_CTRL) || input.isDown(Key.RIGHT_CTRL)
            val field = focusedWidget as? TextField
            if (input.pressed(Key.TAB)) { if (shift) focusPrevious() else focusNext() }
            if (field != null) {
                val e = field.editing; var changed = false; var moved = false
                if (fires(input, Key.LEFT, dt)) { e.left(shift, ctrl); moved = true }
                if (fires(input, Key.RIGHT, dt)) { e.right(shift, ctrl); moved = true }
                if (input.pressed(Key.HOME)) { e.home(shift); moved = true }
                if (input.pressed(Key.END)) { e.end(shift); moved = true }
                if (fires(input, Key.BACKSPACE, dt)) changed = e.backspace(ctrl) or changed
                if (fires(input, Key.DELETE, dt)) changed = e.delete(ctrl) or changed
                for (ch in typed) when (ch) {
                    '\u0001' -> { e.selectAll(); moved = true }
                    '\u0003' -> if (e.hasSelection) clipboard = e.selected
                    '\u0018' -> if (e.hasSelection) { clipboard = e.cut(); changed = true }
                    '\u0016' -> changed = e.insert(clipboard) or changed
                    else -> if (ch >= ' ') changed = e.insert(ch.toString()) or changed
                }
                if (fires(input, Key.UP, dt)) focusToward(0, -1)
                if (fires(input, Key.DOWN, dt)) focusToward(0, 1)
                if (input.pressed(Key.ENTER)) activate()
                if (changed || moved) { field.blink = 0f; dirty = true }
                if (changed) field.onChange(field.text)
            } else {
                if (fires(input, Key.UP, dt)) focusToward(0, -1)
                if (fires(input, Key.DOWN, dt)) focusToward(0, 1)
                if (fires(input, Key.LEFT, dt)) focusToward(-1, 0)
                if (fires(input, Key.RIGHT, dt)) focusToward(1, 0)
                if (input.pressed(Key.ENTER) || input.pressed(Key.SPACE)) activate()
            }
        }

        padNavigation(scene, dt)

        // The pointer: what is under it, presses and clicks, a text field's caret and selection.
        // Moved: a new place, or the pointer appearing or leaving (NaN is never equal to itself).
        val moved = (px != lastX && !(px.isNaN() && lastX.isNaN())) || (py != lastY && !(py.isNaN() && lastY.isNaN()))
        if (moved || dirty) {
            val list = focusables()
            var over: Widget? = null
            var i = list.size - 1
            if (!px.isNaN()) while (i >= 0) { if (list[i].contains(px, py)) { over = list[i]; break }; i-- }
            if (over !== hoveredWidget) { hoveredWidget = over; dirty = true }
            // In a menu the focus follows the pointer (one highlight, not two); elsewhere the keys stay with the game.
            if (moved && over is Button && focusedWidget !is TextField && topMenu != null) focus(over)
        }
        if (down && !wasDown) {
            val over = hoveredWidget
            pressedWidget = over
            if (over is TextField) { focus(over); over.editing.place(caretAt(over, px)); over.blink = 0f }
            else if (over == null && focusedWidget is TextField && topMenu == null) focus(null)
            dirty = true
        } else if (down && pressedWidget is TextField && moved) {
            val tf = pressedWidget as TextField
            tf.editing.place(caretAt(tf, px), select = true); dirty = true
        } else if (!down && wasDown) {
            val p = pressedWidget
            pressedWidget = null; dirty = true
            if (p is Button && p === hoveredWidget) { if (topMenu != null) focus(p); press(p) }
        }
        lastX = px; lastY = py; wasDown = down; pointerX = px; pointerY = py

        // The mouse: free while a menu is open (captured again after if it was), a click captures it only away from widgets.
        if (window != null) {
            val want = openMenus.isNotEmpty()
            if (want && !hadPointer) { recapture = window.mouseCaptured; window.mouseCaptured = false }
            if (!want && hadPointer && recapture) window.mouseCaptured = true
            hadPointer = want
            window.clickCaptures = !want && hoveredWidget == null
        }
        // The keyboard belongs to the interface or to the game, not both.
        val masked = current is MaskedKeys
        if (usesKeys && !masked) scene.input.source = MaskedKeys(real)
        else if (!usesKeys && masked) scene.input.source = real
    }

    /** The caret index nearest to [px] (canvas units) in a text field. */
    private fun caretAt(f: TextField, px: Float): Int {
        val t = f.laid
        if (t.caretCount == 0) return 0
        val x = px - (f.left + FIELD_PAD) + f.scroll
        var best = 0; var d = Float.MAX_VALUE
        for (i in 0 until t.caretCount) { val e = kotlin.math.abs(t.caretX[i] - x); if (e < d) { d = e; best = i } }
        return best
    }

    // ------------------------------------------------------------------ the mesh

    private var last: HudMesh? = null
    private val painter = Painter()
    private val atlas = GlyphAtlas()
    private var shownAspect = 0f; private var shownFps = 0; private var shownCaptured = false; private var shownHint: String? = null
    private var shownHintPanel = hintPanelColor; private var shownHintText = hintTextColor

    /** What the HUD has done so far, to check that a still interface costs nothing. */
    class Stats internal constructor(private val hud: Hud) {
        /** Times its triangles were made again (only when something it shows changed). */
        val rebuilds: Int get() = hud.rebuilds
        /** Glyphs of system fonts drawn into its atlas (each once per size). */
        val glyphs: Int get() = hud.atlas.rasterised
        /** Times the renderer sent changed rows of the atlas to the GPU. */
        val atlasUploads: Int get() = hud.atlas.uploads
        /** Size of the atlas in texels (it doubles when full). */
        val atlasSize: Int get() = hud.atlas.width
    }
    val stats = Stats(this)
    private var rebuilds = 0

    /** True if anything shown changed since the last mesh: a flag set by every property, and the texts computed every frame. */
    private fun changed(aspect: Float, fps: Int, mouseCaptured: Boolean): Boolean {
        var c = dirty || last == null || aspect != shownAspect || (if (showFps) fps else -1) != shownFps || mouseCaptured != shownCaptured ||
            mouseHint != shownHint || hintPanelColor != shownHintPanel || hintTextColor != shownHintText
        for (i in 0 until elements.size) {
            val e = elements[i]
            if (!e.visible) continue
            when (e) {
                is Text -> { val t = e.value(); if (t != e.shown) { e.shown = t; c = true } }
                is Layout -> if (texts(e.root)) c = true
                else -> {}
            }
        }
        return c
    }

    private fun texts(w: Widget): Boolean {
        if (!w.visible) return false
        var c = false
        when (w) {
            is Label -> { val t = w.value(); if (t != w.shown) { w.shown = t; c = true } }
            is Button -> { val fn = w.labelFn; if (fn != null) { val t = fn(); if (t != w.label) { w.label = t; c = true } } }
            is Box -> for (i in 0 until w.children.size) if (texts(w.children[i])) c = true
            else -> {}
        }
        return c
    }

    /** The HUD's triangles for a picture of shape [aspect], rebuilt only when what it shows changed. */
    internal fun mesh(aspect: Float, fps: Int, mouseCaptured: Boolean): HudMesh {
        if (!changed(aspect, fps, mouseCaptured)) return last!!
        shownAspect = aspect; shownFps = if (showFps) fps else -1; shownCaptured = mouseCaptured; shownHint = mouseHint
        shownHintPanel = hintPanelColor; shownHintText = hintTextColor
        dirty = false
        val ph = if (pixelHeight > 0) pixelHeight.toFloat() else 800f
        val pw = if (pixelHeight > 0 && pixelWidth > 0) pixelWidth.toFloat() else ph * aspect
        var tries = 0
        while (!build(600f * aspect, pw, ph, fps, mouseCaptured) && tries++ < 2) {}
        rebuilds++
        return HudMesh(painter.take(), if (atlas.version > 0) atlas else null).also { last = it }
    }

    private fun place(e: Element, ew: Float, eh: Float, w: Float, h: Float): Pair<Float, Float> {
        val ax = when (e.anchor) { Anchor.TOP_LEFT, Anchor.LEFT, Anchor.BOTTOM_LEFT -> e.x
            Anchor.TOP, Anchor.CENTER, Anchor.BOTTOM -> w / 2 - ew / 2 + e.x
            else -> w - ew - e.x }
        val ay = when (e.anchor) { Anchor.TOP_LEFT, Anchor.TOP, Anchor.TOP_RIGHT -> e.y
            Anchor.LEFT, Anchor.CENTER, Anchor.RIGHT -> h / 2 - eh / 2 + e.y
            else -> h - eh - e.y }
        return ax to ay
    }

    private fun alignOf(a: Align) = when (a) { Align.CENTER -> .5f; Align.RIGHT -> 1f; else -> 0f }

    /** Draws everything; false if the atlas was emptied meanwhile (then it is drawn again). */
    private fun build(w: Float, pw: Float, ph: Float, fps: Int, mouseCaptured: Boolean): Boolean {
        val p = painter
        p.begin(w, pw, ph, atlas)
        val h = 600f
        layoutAll(w)
        for (e in elements) {
            if (!e.visible || e is Menu) continue
            p.opacity = e.opacity
            when (e) {
                is Panel -> { val (x, y) = place(e, e.width, e.height, w, h)
                    if (e.radius > 0f) p.box(x, y, e.width, e.height, e.color, e.radius) else p.rect(x, y, e.width, e.height, e.color) }
                is Text -> {
                    val t = e.shown ?: continue
                    val font = e.font ?: font
                    // The alignment inside a block of several lines follows the text's own alignment.
                    val inner = when (e.align) { Align.AUTO -> when (e.anchor) { Anchor.TOP, Anchor.CENTER, Anchor.BOTTOM -> .5f
                        Anchor.TOP_RIGHT, Anchor.RIGHT, Anchor.BOTTOM_RIGHT -> 1f; else -> 0f }; else -> alignOf(e.align) }
                    TextLayout.layout(t, font, e.size, e.wrap, inner, e.laid)
                    val tw = e.laid.width
                    var (x, y) = place(e, tw, e.laid.height, w, h)
                    if (e.align != Align.AUTO) {
                        // The anchor point itself, then the text placed left, centred or right of it.
                        val px = when (e.anchor) { Anchor.TOP_LEFT, Anchor.LEFT, Anchor.BOTTOM_LEFT -> e.x; Anchor.TOP, Anchor.CENTER, Anchor.BOTTOM -> w / 2 + e.x; else -> w - e.x }
                        x = when (e.align) { Align.LEFT -> px; Align.CENTER -> px - tw / 2; else -> px - tw }
                    }
                    if (!p.styledText(e.laid, x, y, e.size, e.color, e.shadow, e.outline, e.outlineWidth, font)) return false
                }
                is Layout -> if (!draw(e.root, e.opacity)) return false
            }
        }
        // Open menus over everything, the last opened on top, each over its veil.
        for (m in openMenus) {
            val a = m.appear; val ease = 1f - (1f - a) * (1f - a)
            p.opacity = m.veil * ease
            if (m.veil > 0f) p.rect(0f, 0f, w, h, Color.hex(0x08161B))
            if (!draw(m.root, m.opacity * ease)) return false
        }
        p.opacity = 1f
        val pixel = Font.PIXEL
        if (showFps) { val t = "$fps FPS"; val lt = scratch; TextLayout.layout(t, pixel, 1.25f, 0f, 0f, lt); p.text(lt, w - lt.width - 24f, 24f, 1.25f, hintTextColor) }
        val hint = mouseHint
        if (!mouseCaptured && hint != null && openMenus.isEmpty()) {
            val lt = scratch; TextLayout.layout(hint, pixel, 1.5f, 0f, 0f, lt)
            val tw = lt.width
            p.opacity = .85f
            p.rect(w / 2 - tw / 2 - 14f, h - 122f, tw + 28f, 26f, hintPanelColor)
            p.opacity = 1f
            p.text(lt, w / 2 - tw / 2, h - 114f, 1.5f, hintTextColor)
        }
        return true
    }
    private val scratch = LaidText()

    // ------------------------------------------------------------------ layout of boxes

    private var canvasWidth = 960f
    private fun layoutAll(w: Float = canvasWidth) {
        canvasWidth = w
        for (e in elements) if (e is Layout && (e.visible || e is Menu)) {
            measure(e.root)
            val (x, y) = place(e, e.root.mw, e.root.mh, w, 600f)
            val slide = if (e is Menu) (1f - e.appear) * (1f - e.appear) * 14f else 0f
            arrange(e.root, x, y + slide, e.root.mw, e.root.mh)
        }
    }

    private fun fontOf(f: Font?) = f ?: theme.font ?: font
    private fun buttonSize(b: Button) = b.size ?: theme.size

    private fun measure(w: Widget) {
        when (w) {
            is Label -> {
                val t = w.shown ?: w.value().also { w.shown = it }
                val wrap = if (w.wrap > 0f) w.wrap else w.width
                TextLayout.layout(t, fontOf(w.font), w.size, wrap, alignOf(w.align), w.laid)
                w.mw = w.laid.width; w.mh = w.laid.height
            }
            is Button -> {
                val s = buttonSize(w)
                TextLayout.layout(w.label, fontOf(null), s, 0f, 0f, w.laid)
                w.mw = max(w.laid.width + 2 * 9f * s, 60f * s); w.mh = w.laid.height + 2 * 5.5f * s
            }
            is TextField -> {
                val s = w.size ?: theme.size
                w.mw = 260f; w.mh = 7f * s + 2 * 5.5f * s
            }
            is Spacer -> { w.mw = w.size; w.mh = w.size }
            is Box -> {
                var along = 0f; var across = 0f; var n = 0
                for (c in w.children) {
                    if (!c.visible) continue
                    measure(c)
                    if (w.vertical) { along += c.mh; across = max(across, c.mw) } else { along += c.mw; across = max(across, c.mh) }
                    n++
                }
                if (n > 1) along += w.spacing * (n - 1)
                if (w.vertical) { w.mw = across + 2 * w.paddingX; w.mh = along + 2 * w.paddingY } else { w.mw = along + 2 * w.paddingX; w.mh = across + 2 * w.paddingY }
            }
        }
        if (w.width > 0f) w.mw = w.width
        if (w.height > 0f) w.mh = w.height
    }

    private fun arrange(w: Widget, x: Float, y: Float, width: Float, height: Float) {
        w.left = x; w.top = y; w.laidWidth = width; w.laidHeight = height
        if (w !is Box) return
        val ix = x + w.paddingX; val iy = y + w.paddingY
        val iw = width - 2 * w.paddingX; val ih = height - 2 * w.paddingY
        var at = 0f
        for (c in w.children) {
            if (!c.visible) continue
            if (w.vertical) {
                val cw = if (w.place == Place.STRETCH) iw else min(c.mw, iw)
                val cx = when (w.place) { Place.CENTER -> ix + (iw - cw) / 2; Place.END -> ix + iw - cw; else -> ix }
                arrange(c, cx, iy + at, cw, c.mh); at += c.mh + w.spacing
            } else {
                val chh = if (w.place == Place.STRETCH) ih else min(c.mh, ih)
                val cy = when (w.place) { Place.CENTER -> iy + (ih - chh) / 2; Place.END -> iy + ih - chh; else -> iy }
                arrange(c, ix + at, cy, c.mw, chh); at += c.mw + w.spacing
            }
        }
    }

    // ------------------------------------------------------------------ drawing boxes and widgets

    private fun draw(w: Widget, inherited: Float): Boolean {
        if (!w.visible) return true
        val op = inherited * w.opacity
        if (op <= 0f) return true
        val p = painter; val th = theme
        when (w) {
            is Box -> {
                val bg = w.background ?: if (w.radius < 0f) th.panel else null       // a menu's panel takes the theme's colour
                val radius = if (w.radius < 0f) th.radius * 1.6f else w.radius
                if (w.shadow) { p.opacity = op * .45f; p.box(w.left, w.top + 6f, w.laidWidth, w.laidHeight, Color.hex(0x04090B), radius + 4f, soft = 22f * p.k) }
                if (bg != null) {
                    p.opacity = op * (if (w.radius < 0f) th.panelOpacity else w.backgroundOpacity)
                    p.box(w.left, w.top, w.laidWidth, w.laidHeight, bg.times(1.08f), radius, bottom = bg.times(.9f))
                }
                w.border?.let { b -> p.opacity = op; p.box(w.left, w.top, w.laidWidth, w.laidHeight, b, radius, border = w.borderWidth) }
                for (i in 0 until w.children.size) if (!draw(w.children[i], op)) return false
            }
            is Label -> {
                p.opacity = op
                val dx = (w.laidWidth - w.laid.width) * alignOf(w.align)
                val color = w.color ?: if (isTitle(w)) th.accent else th.text
                if (!p.styledText(w.laid, w.left + dx, w.top, w.size, color, w.shadow, w.outline, w.outlineWidth, fontOf(w.font))) return false
            }
            is Button -> {
                val s = buttonSize(w)
                val focused = focusedWidget === w; val hovered = hoveredWidget === w; val pressed = pressedWidget === w && hovered
                val base = when { !w.enabled -> th.button; pressed -> th.buttonPressed; hovered -> th.buttonHover; else -> th.button }
                val flash = if (w.flash < FLASH) 1f - w.flash / FLASH else 0f
                val fill = base.mix(th.accent, flash * .35f)
                p.opacity = op * (if (w.enabled) 1f else .45f)
                // Focus: a ring of the accent colour just outside the button, and a light top edge.
                if (focused) p.box(w.left - 3f, w.top - 3f, w.laidWidth + 6f, w.laidHeight + 6f, th.accent, th.radius + 3f, border = 2f)
                p.box(w.left, w.top, w.laidWidth, w.laidHeight, fill.times(1.12f), th.radius, bottom = fill.times(.86f))
                p.opacity = op * (if (w.enabled) .16f else .07f)
                p.box(w.left + 2f, w.top + 1.5f, w.laidWidth - 4f, 2f, Color.WHITE, 1f)
                p.opacity = op * (if (w.enabled) 1f else .45f)
                val tx = w.left + (w.laidWidth - w.laid.width) / 2; val ty = w.top + (w.laidHeight - w.laid.height) / 2 + (if (pressed) 1f else 0f)
                if (!p.styledText(w.laid, tx, ty, s, if (focused) th.accent else th.text, Color.hex(0x04090B), null, 0f, fontOf(null))) return false
            }
            is TextField -> if (!drawField(w, op)) return false
            is Spacer -> {}
        }
        return true
    }

    private fun isTitle(l: Label) = openMenus.any { it.title === l } || elements.any { it is Menu && it.title === l }

    private fun drawField(f: TextField, op: Float): Boolean {
        val p = painter; val th = theme
        val s = f.size ?: th.size
        val focused = focusedWidget === f; val hovered = hoveredWidget === f
        val font = fontOf(null)
        p.opacity = op
        p.box(f.left, f.top, f.laidWidth, f.laidHeight, th.field.times(.85f), th.radius * .7f, bottom = th.field.times(1.1f))
        p.box(f.left, f.top, f.laidWidth, f.laidHeight, if (focused) th.accent else th.button.mix(th.text, if (hovered) .35f else .15f),
            th.radius * .7f, border = if (focused) 2f else 1.25f)
        val text = f.text
        val empty = text.isEmpty()
        TextLayout.layout(if (empty) f.placeholder else text, font, s, 0f, 0f, f.laid)
        val inner = f.laidWidth - 2 * FIELD_PAD
        val ty = f.top + (f.laidHeight - 7f * s) / 2
        // Keep the caret in sight: the text scrolls left when it is longer than the field.
        if (!empty) {
            val cx = f.laid.caretX[min(f.editing.caret, f.laid.caretCount - 1)]
            if (cx - f.scroll > inner) f.scroll = cx - inner
            if (cx - f.scroll < 0f) f.scroll = cx
            f.scroll = f.scroll.coerceIn(0f, max(0f, f.laid.width - inner + 2f))
        } else f.scroll = 0f
        val x0 = f.left + FIELD_PAD - f.scroll
        p.clip(f.left + 2f, f.top, f.left + f.laidWidth - 2f, f.top + f.laidHeight)
        if (!empty && f.editing.hasSelection && focused) {
            val a = f.laid.caretX[f.editing.selectionStart]; val b = f.laid.caretX[f.editing.selectionEnd]
            p.opacity = op * .38f
            p.box(max(x0 + a, f.left + 2f), ty - 2.5f * s * .3f, min(b - a, f.laidWidth), 7f * s + 1.5f * s, th.accent, 2f)
        }
        p.opacity = op * (if (empty) .45f else 1f)
        val ok = p.text(f.laid, x0, ty, s, th.text)
        p.noClip()
        if (!ok) return false
        if (focused && f.blink % BLINK < BLINK / 2) {
            val cx = if (empty) 0f else f.laid.caretX[min(f.editing.caret, f.laid.caretCount - 1)]
            p.opacity = op
            p.box(x0 + cx - .6f, ty - 1.2f * s, max(1.4f, .35f * s), 9.4f * s, th.accent, .6f)
        }
        return true
    }

    companion object {
        /** Width of [text] in canvas units at [size], in the pixel font. */
        fun textWidth(text: String, size: Number): Float = (text.length * 6 - 1).coerceAtLeast(0) * size.toFloat()

        /** What Ctrl+C and Ctrl+X put aside for Ctrl+V: the game's own clipboard, shared by its fields. */
        var clipboard = ""

        private const val REPEAT_DELAY = .42f
        private const val REPEAT_RATE = 28f
        private const val APPEAR = .16f
        private const val FLASH = .25f
        private const val BLINK = 1.06f
        internal const val FIELD_PAD = 10f
    }
}

/** The HUD's triangles ([HUD_FLOATS] floats per vertex) and the glyph atlas they read. */
internal class HudMesh(val vertices: FloatArray, val atlas: GlyphAtlas?) {
    val triangleCount: Int get() = vertices.size / (3 * HUD_FLOATS)
}

/** A 5 × 7 pixel font: capitals, small letters, French accents, digits and common signs. */
internal object PixelFont {
    /** The rows of [c] (« 1 » lit), null for a space; characters it lacks are drawn as « ? ». */
    fun glyph(c: Char): List<String>? = glyphs[c] ?: glyphs[c.uppercaseChar().let { u -> plain[u] ?: u }] ?: if (c == ' ') null else glyphs['?']

    // Capitals with accents are drawn plain (no room above a capital in 7 rows).
    // g, j, p, q and y have two more rows: their tails hang below the line, like in print.
    private val plain = mapOf('À' to 'A', 'Â' to 'A', 'Ä' to 'A', 'Ç' to 'C', 'É' to 'E', 'È' to 'E', 'Ê' to 'E', 'Ë' to 'E',
        'Î' to 'I', 'Ï' to 'I', 'Ô' to 'O', 'Ö' to 'O', 'Ù' to 'U', 'Û' to 'U', 'Ü' to 'U', 'Ÿ' to 'Y', 'Œ' to 'O')

    private val glyphs: Map<Char, List<String>> = mapOf(
        'A' to "01110/10001/10001/11111/10001/10001/10001", 'B' to "11110/10001/10001/11110/10001/10001/11110",
        'C' to "01111/10000/10000/10000/10000/10000/01111", 'D' to "11110/10001/10001/10001/10001/10001/11110",
        'E' to "11111/10000/10000/11110/10000/10000/11111", 'F' to "11111/10000/10000/11110/10000/10000/10000",
        'G' to "01111/10000/10000/10111/10001/10001/01111", 'H' to "10001/10001/10001/11111/10001/10001/10001",
        'I' to "11111/00100/00100/00100/00100/00100/11111", 'J' to "00111/00010/00010/00010/10010/10010/01100",
        'K' to "10001/10010/10100/11000/10100/10010/10001", 'L' to "10000/10000/10000/10000/10000/10000/11111",
        'M' to "10001/11011/10101/10101/10001/10001/10001", 'N' to "10001/11001/10101/10011/10001/10001/10001",
        'O' to "01110/10001/10001/10001/10001/10001/01110", 'P' to "11110/10001/10001/11110/10000/10000/10000",
        'Q' to "01110/10001/10001/10001/10101/10010/01101", 'R' to "11110/10001/10001/11110/10100/10010/10001",
        'S' to "01111/10000/10000/01110/00001/00001/11110", 'T' to "11111/00100/00100/00100/00100/00100/00100",
        'U' to "10001/10001/10001/10001/10001/10001/01110", 'V' to "10001/10001/10001/10001/10001/01010/00100",
        'W' to "10001/10001/10001/10101/10101/10101/01010", 'X' to "10001/10001/01010/00100/01010/10001/10001",
        'Y' to "10001/10001/01010/00100/00100/00100/00100", 'Z' to "11111/00001/00010/00100/01000/10000/11111",
        'a' to "00000/00000/01110/00001/01111/10001/01111", 'b' to "10000/10000/11110/10001/10001/10001/11110",
        'c' to "00000/00000/01111/10000/10000/10000/01111", 'd' to "00001/00001/01111/10001/10001/10001/01111",
        'e' to "00000/00000/01110/10001/11111/10000/01111", 'f' to "00110/01001/01000/11100/01000/01000/01000",
        'g' to "00000/00000/01111/10001/10001/10001/01111/00001/01110", 'h' to "10000/10000/11110/10001/10001/10001/10001",
        'i' to "00100/00000/01100/00100/00100/00100/01110", 'j' to "00010/00000/00110/00010/00010/00010/00010/10010/01100",
        'k' to "10000/10000/10010/10100/11000/10100/10010", 'l' to "01100/00100/00100/00100/00100/00100/01110",
        'm' to "00000/00000/11010/10101/10101/10101/10101", 'n' to "00000/00000/11110/10001/10001/10001/10001",
        'o' to "00000/00000/01110/10001/10001/10001/01110", 'p' to "00000/00000/11110/10001/10001/10001/11110/10000/10000",
        'q' to "00000/00000/01111/10001/10001/10001/01111/00001/00001", 'r' to "00000/00000/10111/11000/10000/10000/10000",
        's' to "00000/00000/01111/10000/01110/00001/11110", 't' to "01000/01000/11100/01000/01000/01001/00110",
        'u' to "00000/00000/10001/10001/10001/10011/01101", 'v' to "00000/00000/10001/10001/10001/01010/00100",
        'w' to "00000/00000/10001/10001/10101/10101/01010", 'x' to "00000/00000/10001/01010/00100/01010/10001",
        'y' to "00000/00000/10001/10001/10001/10001/01111/00001/01110", 'z' to "00000/00000/11111/00010/00100/01000/11111",
        'é' to "00010/00100/01110/10001/11111/10000/01111", 'è' to "01000/00100/01110/10001/11111/10000/01111",
        'ê' to "00100/01010/01110/10001/11111/10000/01111", 'ë' to "01010/00000/01110/10001/11111/10000/01111",
        'à' to "01000/00100/01110/00001/01111/10001/01111", 'â' to "00100/01010/01110/00001/01111/10001/01111",
        'ä' to "01010/00000/01110/00001/01111/10001/01111", 'ç' to "00000/01111/10000/10000/01111/00100/01100",
        'î' to "00100/01010/00000/01100/00100/00100/01110", 'ï' to "01010/00000/01100/00100/00100/00100/01110",
        'ô' to "00100/01010/01110/10001/10001/10001/01110", 'ö' to "01010/00000/01110/10001/10001/10001/01110",
        'ù' to "01000/00100/10001/10001/10001/10011/01101", 'û' to "00100/01010/10001/10001/10001/10011/01101",
        'ü' to "01010/00000/10001/10001/10001/10011/01101", 'ÿ' to "01010/00000/10001/10001/01111/00001/01110",
        'œ' to "00000/00000/01010/10101/10111/10100/01011",
        '0' to "01110/10001/10011/10101/11001/10001/01110", '1' to "00100/01100/00100/00100/00100/00100/01110",
        '2' to "01110/10001/00001/00010/00100/01000/11111", '3' to "11110/00001/00001/01110/00001/00001/11110",
        '4' to "00010/00110/01010/10010/11111/00010/00010", '5' to "11111/10000/10000/11110/00001/00001/11110",
        '6' to "01110/10000/10000/11110/10001/10001/01110", '7' to "11111/00001/00010/00100/01000/01000/01000",
        '8' to "01110/10001/10001/01110/10001/10001/01110", '9' to "01110/10001/10001/01111/00001/00001/01110",
        '/' to "00001/00010/00010/00100/01000/01000/10000", '-' to "00000/00000/00000/11111/00000/00000/00000",
        '–' to "00000/00000/00000/11111/00000/00000/00000", '—' to "00000/00000/00000/11111/00000/00000/00000",
        ':' to "00000/00100/00100/00000/00100/00100/00000", '.' to "00000/00000/00000/00000/00000/00100/00100",
        ',' to "00000/00000/00000/00000/00110/00100/01000", ';' to "00000/00100/00100/00000/00100/00100/01000",
        '!' to "00100/00100/00100/00100/00100/00000/00100", '?' to "01110/10001/00001/00010/00100/00000/00100",
        '\'' to "00100/00100/01000/00000/00000/00000/00000", '’' to "00100/00100/01000/00000/00000/00000/00000",
        '"' to "01010/01010/00000/00000/00000/00000/00000", '(' to "00010/00100/01000/01000/01000/00100/00010",
        ')' to "01000/00100/00010/00010/00010/00100/01000", '[' to "01110/01000/01000/01000/01000/01000/01110",
        ']' to "01110/00010/00010/00010/00010/00010/01110", '+' to "00000/00100/00100/11111/00100/00100/00000",
        '=' to "00000/00000/11111/00000/11111/00000/00000", '%' to "11001/11010/00010/00100/01000/01011/10011",
        '_' to "00000/00000/00000/00000/00000/00000/11111", '<' to "00010/00100/01000/10000/01000/00100/00010",
        '>' to "01000/00100/00010/00001/00010/00100/01000", '#' to "01010/01010/11111/01010/11111/01010/01010",
        '&' to "01100/10010/10100/01000/10101/10010/01101", '*' to "00000/10101/01110/11111/01110/10101/00000",
        '«' to "00000/00101/01010/10100/01010/00101/00000", '»' to "00000/10100/01010/00101/01010/10100/00000",
        '°' to "01100/10010/10010/01100/00000/00000/00000", '×' to "00000/10001/01010/00100/01010/10001/00000",
    ).mapValues { it.value.split('/') }
}
