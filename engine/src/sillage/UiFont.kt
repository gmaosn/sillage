package sillage

import sillage.io.FaceInfo
import sillage.io.FontFace
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * A typeface for the interface: the engine's own pixel font ([PIXEL], the default), or a font already
 * installed on the computer — TrueType, OpenType (CFF outlines too) or a collection — found by its family
 * name. Fonts are never downloaded nor shipped with a game: [find] returns null when a family is not
 * there, and [sans], [serif] and [mono] pick a family that is, or the pixel font if the computer has none.
 *
 * ```
 * hud.font = Font.sans()                                    // every text of the HUD in a system font
 * val title = Font.find("Noto Serif", bold = true) ?: Font.serif(bold = true)
 * ```
 * A character the font lacks is taken from its [fallbacks] in turn, then drawn as an empty box.
 */
class Font private constructor(internal val face: FontFace?, val family: String, val style: String,
                               /** The file it comes from (null for the pixel font), and its face in a collection. */
                               val path: Path?, val index: Int) {
    /** Weight from 100 (thin) to 900 (black); 400 is regular, 700 bold. */
    val weight: Int get() = face?.weight ?: 400
    val bold: Boolean get() = weight >= 600
    val italic: Boolean get() = face?.italic ?: false
    /** True for the engine's built-in pixel font. */
    val isPixel: Boolean get() = face == null
    internal val id: Int = ids.getAndIncrement()

    /** Fonts tried in turn for the characters this one lacks (by default a few wide-coverage families found on the system). */
    var fallbacks: List<Font> = emptyList()
        get() = if (fallbacksSet || face == null) field else defaultFallbacks().filter { it !== this }
        set(v) { field = v; fallbacksSet = true }
    private var fallbacksSet = false

    /** True if the font itself draws [char] (a code point). */
    fun has(char: Int): Boolean = if (face == null) PixelFont.glyph(char.toChar()) != null || char == ' '.code else face.glyph(char) != 0

    /** Width of one line of [text] at [size] (see [Hud.text]: capitals are 7 × size units high), with kerning. */
    fun width(text: String, size: Number): Float = sillage.ui.TextLayout.measure(text, this, size.toFloat())

    /** Height of the capitals in font units (from the font, or measured on « H »): the size of a text is set by it. */
    internal val capUnits: Float by lazy {
        val f = face ?: return@lazy 7f
        if (f.capHeight > 0) f.capHeight.toFloat()
        else f.outline(f.glyph('H'.code)).controlBox().let { b -> if (b[3] > 0f) b[3] else f.unitsPerEm * .7f }
    }

    override fun toString() = if (face == null) "Font(pixel)" else "Font($family $style, $path${if (index > 0) "#$index" else ""})"

    companion object {
        private val ids = java.util.concurrent.atomic.AtomicInteger(0)

        /** The engine's 5 × 7 pixel font: capitals, small letters, French accents, digits and signs. */
        val PIXEL: Font = Font(null, "Pixel", "Regular", null, 0)

        private val loaded = ConcurrentHashMap<Pair<Path, Int>, Font>()

        /** Loads face [index] of a font file (.ttf, .otf, .ttc); throws if it cannot be read. */
        fun load(path: String, index: Int = 0): Font = load(Path.of(path), index)

        internal fun load(path: Path, index: Int): Font = loaded.getOrPut(path.toAbsolutePath() to index) {
            val face = FontFace.load(path.toString(), index)
            Font(face, face.family, face.subfamily, path.toAbsolutePath(), index)
        }

        /**
         * The installed face of [family] (its name as font menus show it, case and spaces ignored) closest to
         * the weight and slant asked; null if no font of that family is installed.
         */
        fun find(family: String, bold: Boolean = false, italic: Boolean = false): Font? =
            FontLibrary.best(family, if (bold) 700 else 400, italic)?.let { runCatching { load(it.path, it.index) }.getOrNull() }

        /** A sans-serif family found on the computer (Noto Sans, Source Sans, DejaVu Sans…), or the pixel font. */
        fun sans(bold: Boolean = false, italic: Boolean = false): Font = first(SANS, bold, italic)
        /** A serif family found on the computer (Noto Serif, Source Serif, DejaVu Serif…), or the pixel font. */
        fun serif(bold: Boolean = false, italic: Boolean = false): Font = first(SERIF, bold, italic)
        /** A monospaced family found on the computer (Noto Sans Mono, Source Code Pro, DejaVu Sans Mono…), or the pixel font. */
        fun mono(bold: Boolean = false, italic: Boolean = false): Font = first(MONO, bold, italic)

        /** The families installed on the computer that the engine can read, sorted. */
        fun families(): List<String> = FontLibrary.faces.filter { it.outlines }.map { it.family }.distinct().sorted()

        private val SANS = listOf("Noto Sans", "Source Sans 3", "Source Sans Pro", "Inter", "Open Sans", "Roboto", "Ubuntu", "Lato",
            "Fira Sans", "DejaVu Sans", "Liberation Sans", "Arimo", "Cantarell", "Bitstream Vera Sans", "FreeSans")
        private val SERIF = listOf("Noto Serif", "Source Serif 4", "Source Serif Pro", "Merriweather", "DejaVu Serif", "Liberation Serif",
            "Tinos", "Bitstream Vera Serif", "FreeSerif")
        private val MONO = listOf("Noto Sans Mono", "Source Code Pro", "JetBrains Mono", "Fira Mono", "Fira Code", "Hack", "DejaVu Sans Mono",
            "Liberation Mono", "Cousine", "Ubuntu Mono", "FreeMono")
        private val FALLBACK = listOf("DejaVu Sans", "Noto Sans", "Noto Sans Symbols", "Noto Sans Symbols 2", "Noto Sans Math",
            "Noto Sans CJK JP", "Noto Sans Arabic", "Noto Sans Hebrew", "Noto Sans Devanagari", "Noto Sans Thai", "Liberation Sans")

        private val firsts = ConcurrentHashMap<Triple<List<String>, Boolean, Boolean>, Font>()
        private fun first(names: List<String>, bold: Boolean, italic: Boolean): Font = firsts.getOrPut(Triple(names, bold, italic)) {
            names.firstNotNullOfOrNull { find(it, bold, italic) } ?: PIXEL
        }

        @Volatile private var fallbackList: List<Font>? = null
        internal fun defaultFallbacks(): List<Font> = fallbackList ?: FALLBACK.mapNotNull { find(it) }.distinct().also { fallbackList = it }
    }
}

/**
 * The fonts installed on the computer, without fontconfig: the standard directories are scanned once, each
 * file read only for its names (family, style, weight, slant); the result is kept for the process.
 */
internal object FontLibrary {
    val directories: List<Path> by lazy {
        val home = System.getProperty("user.home")
        val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotEmpty() } ?: "$home/.local/share"
        val dataDirs = (System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotEmpty() } ?: "/usr/local/share:/usr/share").split(':')
        (listOf("$dataHome/fonts", "$home/.fonts") + dataDirs.map { "$it/fonts" } + listOf("/usr/share/fonts", "/usr/local/share/fonts"))
            .map { Path.of(it).toAbsolutePath().normalize() }.distinct().filter { Files.isDirectory(it) }
    }

    /** Every face found, in a fixed order (sorted by path). */
    val faces: List<FaceInfo> by lazy {
        val files = java.util.TreeSet<Path>()
        for (d in directories) runCatching {
            Files.walk(d).use { s -> s.filter { p -> val n = p.fileName.toString().lowercase(); (n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")) && Files.isRegularFile(p) }
                .forEach { files.add(it.toRealPath()) } }
        }
        files.toList().parallelStream().map { p -> runCatching { FontFace.scan(p.toString()) }.getOrDefault(emptyList()) }.toList().flatten()
    }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private val byFamily: Map<String, List<FaceInfo>> by lazy { faces.filter { it.outlines }.groupBy { key(it.family) } }

    /** The face of [family] closest to [weight] and [italic]: static fonts before variable ones, plain widths before condensed. */
    fun best(family: String, weight: Int, italic: Boolean): FaceInfo? {
        val list = byFamily[key(family)] ?: return null
        return list.minByOrNull { f ->
            var score = kotlin.math.abs(f.weight - weight).toDouble()
            if (f.italic != italic) score += 1000
            if (f.variable) score += 60
            val s = f.subfamily.lowercase()
            if (listOf("condensed", "narrow", "expanded", "semicondensed", "extended", "display", "caption").any { it in s }) score += 300
            score
        }
    }
}
