package sillage

import sillage.ui.LaidText
import sillage.ui.TextEditing

/**
 * A piece of a laid-out interface: [Group]s hold others in a column or a row and take the size of what they
 * hold; [Label]s, [Button]s and [TextField]s go inside. Build them with [Hud.column], [Hud.row] or
 * [Hud.menu]. Sizes and places are in canvas units (the canvas is 600 high); changing any property redraws
 * the interface at the next frame, and only then.
 */
sealed class Widget {
    internal var owner: Hud? = null
        set(v) { field = v; if (this is Group) for (c in children) c.owner = v }
    internal fun dirty() { owner?.dirty = true }

    var visible = true; set(v) { if (field != v) { field = v; dirty() } }
    /** 1 solid, 0 invisible; multiplied down the boxes it sits in. */
    var opacity = 1f; set(v) { if (field != v) { field = v; dirty() } }
    /** A fixed width and height (canvas units); 0: as large as the content. */
    var width = 0f; set(v) { if (field != v) { field = v; dirty() } }
    var height = 0f; set(v) { if (field != v) { field = v; dirty() } }

    /** Where the layout put it (canvas units from the top left of the canvas) at the last frame drawn. */
    var left = 0f; internal set
    var top = 0f; internal set
    var laidWidth = 0f; internal set
    var laidHeight = 0f; internal set

    /** True for what the keyboard (or a gamepad) can reach: buttons and text fields. */
    open val focusable: Boolean get() = false
    /** True while the pointer is over it. */
    val hovered: Boolean get() = owner?.hoveredWidget === this
    /** True while it has the keyboard's focus. */
    val focused: Boolean get() = owner?.focusedWidget === this

    // Content size measured before placing.
    internal var mw = 0f; internal var mh = 0f
    internal fun contains(x: Float, y: Float) = x >= left && x < left + laidWidth && y >= top && y < top + laidHeight
}

/** How a box places its children across: at its start, centred, at its end, or stretched to its full size. */
enum class Place { START, CENTER, END, STRETCH }

/** What builds an interface: a box's children, added in order. */
interface UiBuilder {
    /** The box the children go into. */
    val box: Group

    /** A line (or lines) of text; [size] as for [Hud.text] (capitals 7 × size high). Null colour or font: the theme's. */
    fun text(value: String, size: Number = 2, color: Color? = null, font: Font? = null, wrap: Number = 0): Label =
        text({ value }, size, color, font, wrap)
    /** A text computed every frame (redrawn only when it changes). */
    fun text(value: () -> String, size: Number = 2, color: Color? = null, font: Font? = null, wrap: Number = 0): Label =
        Label(value, size.toFloat(), color, font, wrap.toFloat()).also { box.add(it) }
    /** A button: [onClick] runs on a click, or on Enter or Space while it has the focus. */
    fun button(label: String, size: Number? = null, onClick: () -> Unit): Button = Button(label, size?.toFloat(), onClick).also { box.add(it) }
    /** A button whose label is computed every frame (« Sound: on »). */
    fun button(label: () -> String, size: Number? = null, onClick: () -> Unit): Button = Button("", size?.toFloat(), onClick).also { it.labelFn = label; box.add(it) }
    /** A text field [width] units wide; [onSubmit] runs on Enter with its text. */
    fun field(placeholder: String = "", width: Number = 260, size: Number? = null, onSubmit: (String) -> Unit = {}): TextField =
        TextField(placeholder, size?.toFloat(), onSubmit).also { it.width = width.toFloat(); box.add(it) }
    /** Children side by side. */
    fun row(spacing: Number = 10, padding: Number = 0, place: Place = Place.CENTER, block: Group.() -> Unit = {}): Group =
        Group(false).also { it.spacing = spacing.toFloat(); it.padding = padding.toFloat(); it.place = place; box.add(it); it.block() }
    /** Children one under the other. */
    fun column(spacing: Number = 8, padding: Number = 0, place: Place = Place.START, block: Group.() -> Unit = {}): Group =
        Group(true).also { it.spacing = spacing.toFloat(); it.padding = padding.toFloat(); it.place = place; box.add(it); it.block() }
    /** Empty room of [size] units along the box. */
    fun space(size: Number): Spacer = Spacer(size.toFloat()).also { box.add(it) }
}

/**
 * Children in a column ([vertical]) or a row, [spacing] units apart, [padding] units inside its edge; it
 * takes the size of what it holds (or [width] × [height] if set). With a [background] it is a panel:
 * rounded by [radius], with an optional [border] and a soft [shadow] beneath.
 */
class Group internal constructor(val vertical: Boolean) : Widget(), UiBuilder {
    override val box: Group get() = this
    private val list = ArrayList<Widget>()
    val children: List<Widget> get() = list

    var spacing = 8f; set(v) { if (field != v) { field = v; dirty() } }
    /** Room inside the edge on every side; [paddingX] and [paddingY] set the sides apart. */
    var padding: Float
        get() = paddingX
        set(v) { paddingX = v; paddingY = v }
    var paddingX = 0f; set(v) { if (field != v) { field = v; dirty() } }
    var paddingY = 0f; set(v) { if (field != v) { field = v; dirty() } }
    /** How children sit across the box. */
    var place = Place.START; set(v) { if (field != v) { field = v; dirty() } }
    var background: Color? = null; set(v) { if (field != v) { field = v; dirty() } }
    /** Opacity of the background alone (the children keep theirs). */
    var backgroundOpacity = 1f; set(v) { if (field != v) { field = v; dirty() } }
    var radius = 0f; set(v) { if (field != v) { field = v; dirty() } }
    var border: Color? = null; set(v) { if (field != v) { field = v; dirty() } }
    var borderWidth = 1.5f; set(v) { if (field != v) { field = v; dirty() } }
    /** A soft shadow under the panel, as if it floated a little above the scene. */
    var shadow = false; set(v) { if (field != v) { field = v; dirty() } }

    fun add(w: Widget) { list.add(w); w.owner = owner; dirty() }
    fun remove(w: Widget) { if (list.remove(w)) { w.owner = null; dirty() } }
    fun clear() { for (w in list) w.owner = null; list.clear(); dirty() }

    /** Makes it a panel: a background of [color], rounded corners, padding. */
    fun panel(color: Color, opacity: Number = 1, radius: Number = 10, padding: Number = 14): Group {
        background = color; backgroundOpacity = opacity.toFloat(); this.radius = radius.toFloat(); this.padding = padding.toFloat(); return this
    }
}

/** A text inside a box. */
class Label internal constructor(internal var value: () -> String, size: Float, color: Color?, font: Font?, wrap: Float) : Widget() {
    var size = size; set(v) { if (field != v) { field = v; dirty() } }
    /** Its colour; null: the theme's text colour. */
    var color = color; set(v) { if (field != v) { field = v; dirty() } }
    var font = font; set(v) { if (field != v) { field = v; dirty() } }
    /** Width at which lines break (0: only at '\n'). */
    var wrap = wrap; set(v) { if (field != v) { field = v; dirty() } }
    /** How its lines sit within its width. */
    var align = Hud.Align.LEFT; set(v) { if (field != v) { field = v; dirty() } }
    var shadow: Color? = null; set(v) { if (field != v) { field = v; dirty() } }
    var outline: Color? = null; set(v) { if (field != v) { field = v; dirty() } }
    var outlineWidth = .6f; set(v) { if (field != v) { field = v; dirty() } }
    /** Changes the text it shows. */
    fun set(text: String) { value = { text }; dirty() }
    fun set(text: () -> String) { value = text; dirty() }

    internal var shown: String? = null
    internal val laid = LaidText()
}

/** A button: hovered by the mouse, focused by the keyboard or a gamepad, pressed by a click, Enter or Space. */
class Button internal constructor(label: String, size: Float?, var onClick: () -> Unit) : Widget() {
    override val focusable get() = visible && enabled
    var label = label; set(v) { if (field != v) { field = v; dirty() } }
    internal var labelFn: (() -> String)? = null
    /** Its text size; null: the theme's. */
    var size = size; set(v) { if (field != v) { field = v; dirty() } }
    /** A disabled button is drawn faded and cannot be pressed nor focused. */
    var enabled = true; set(v) { if (field != v) { field = v; dirty() } }
    internal var shown: String? = null
    internal val laid = LaidText()
    /** Seconds since it was last activated, for its flash. */
    internal var flash = 10f
}

/**
 * A line of text the player types into (keyboard layout applied, dead keys and compose sequences
 * included): caret, selection with Shift and the arrows, Backspace and Delete, Home and End, Ctrl+A, Ctrl+C,
 * Ctrl+X and Ctrl+V (a clipboard of the game's own). Enter runs [onSubmit].
 */
class TextField internal constructor(placeholder: String, size: Float?, var onSubmit: (String) -> Unit) : Widget() {
    override val focusable get() = visible
    internal val editing = TextEditing()
    /** The text typed so far. */
    var text: String
        get() = editing.text
        set(v) { if (editing.text != v) { editing.set(v); dirty() } }
    var placeholder = placeholder; set(v) { if (field != v) { field = v; dirty() } }
    var size = size; set(v) { if (field != v) { field = v; dirty() } }
    var maxLength: Int
        get() = editing.maxLength
        set(v) { editing.maxLength = v }
    /** Runs after every change of the text. */
    var onChange: (String) -> Unit = {}
    val caret: Int get() = editing.caret
    /** The selected part as start and end (equal when nothing is selected). */
    val selection: IntRange get() = editing.selectionStart until editing.selectionEnd
    /** Selects from [start] to [end] (UTF-16 indices), the caret at [end]. */
    fun select(start: Int, end: Int) { editing.place(start); editing.place(end, select = true); blink = 0f; dirty() }
    internal val laid = LaidText()
    internal var shown: String? = null
    /** How far the text is scrolled left so that the caret stays in sight. */
    internal var scroll = 0f
    internal var blink = 0f
}

/** Empty room in a box. */
class Spacer internal constructor(size: Float) : Widget() {
    var size = size; set(v) { if (field != v) { field = v; dirty() } }
}
