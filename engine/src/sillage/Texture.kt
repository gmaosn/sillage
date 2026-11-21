package sillage

import java.util.concurrent.CompletableFuture
import kotlin.math.*

/**
 * A detail texture: a square pattern that repeats every [tile] metres and shades the colour of the
 * surface it lies on. Mid-grey leaves the colour as it is, lighter brightens it, darker darkens it: a
 * model keeps its own colours and gains grain, planks, tiles or blades of grass. [strength] scales the
 * pattern (0: none, 1: as painted).
 *
 * The pattern is laid on the object itself, so it moves with it, in one of two ways ([mapping]):
 * - [Mapping.SIDES]: seen from the three axes and blended by the slope, without seams on round shapes
 *   (grass, sand, rock, plaster);
 * - [Mapping.FACES]: along each flat face, rows running level and climbing the slope (roof tiles,
 *   planks, wood grain); best on flat faces, a round shape shows its facets.
 *
 * Ready-made textures are in [Textures]; make your own with [Texture.paint]. Textures are painted in
 * the background as soon as they are made and sent to the GPU once.
 */
class Texture private constructor(
    val name: String,
    /** Side of the square in pixels: a power of two. */
    val size: Int,
    /** Metres covered by one repeat of the pattern. */
    val tile: Float,
    val strength: Float,
    val mapping: Mapping,
    /** The pixels of the first level as RGBA bytes (painted in the background). */
    rgba: () -> ByteArray,
) {
    /**
     * How a texture lies on a surface: [SIDES] and [FACES] for detail patterns (see above); [UV] for an
     * image painted on a model (a GLB's base colour texture), by the model's own texture coordinates.
     */
    enum class Mapping { SIDES, FACES, UV }

    /** Its place among the textures the program has made (1, 2…); 0 means none in the vertex data. */
    internal val id: Int
    /** The pixels and every smaller level (RGBA, 8 bits), painted in the background. */
    private val levels: CompletableFuture<ByteArray>

    init {
        require(size >= 4 && size and (size - 1) == 0) { "A texture's size must be a power of two (4 or more), not $size" }
        require(tile > 0f) { "A texture's tile must be positive" }
        synchronized(registry) { registry.add(this); id = registry.size }
        levels = CompletableFuture.supplyAsync { withLevels(rgba(), size) }
    }

    /** The pixels and their smaller levels, once painted (waits for the painting). */
    internal val pixels: ByteArray get() = levels.join()
    /** Painted already. */
    internal val ready: Boolean get() = levels.isDone
    /** Number of levels: size, size/2 … 1. */
    internal val levelCount: Int get() = 31 - Integer.numberOfLeadingZeros(size) + 1

    override fun toString() = "Texture($name)"

    companion object {
        internal val registry = ArrayList<Texture>()
        internal fun all(): List<Texture> = synchronized(registry) { ArrayList(registry) }
        /** How many textures the program has made (read every frame: no copy). */
        internal fun count(): Int = synchronized(registry) { registry.size }

        /**
         * A texture painted by [pixel], called for every pixel with its place (u, v from 0 to 1, the
         * pattern must join up at the edges to repeat without seams). It returns the shade: mid-grey
         * ([Color] 0.5, 0.5, 0.5) leaves a surface as it is.
         */
        fun paint(name: String, tile: Number = 1, strength: Number = 1, size: Int = 256, mapping: Mapping = Mapping.SIDES,
                  pixel: (u: Float, v: Float) -> Color): Texture =
            Texture(name, size, tile.toFloat(), strength.toFloat(), mapping) {
                val out = FloatArray(3 * size * size)
                for (y in 0 until size) for (x in 0 until size) {
                    val c = pixel((x + .5f) / size, (y + .5f) / size); val i = 3 * (y * size + x)
                    out[i] = c.r * 2f; out[i + 1] = c.g * 2f; out[i + 2] = c.b * 2f
                }
                shadesToBytes(out)
            }

        /**
         * An image painted on a model ([Mapping.UV]): its pixels as they are (sRGB, with their alpha), brought
         * to a power-of-two square of [MAX_IMAGE] pixels at most (the texture coordinates do not change).
         */
        fun image(name: String, image: sillage.io.Image): Texture {
            var size = 4
            while (size < maxOf(image.width, image.height) && size < MAX_IMAGE) size *= 2
            return Texture(name, size, 1f, 1f, Mapping.UV) { resample(image, size) }
        }

        private class Encoded(val bytes: ByteArray) {
            private val hash = bytes.contentHashCode()
            override fun hashCode() = hash
            override fun equals(other: Any?) = other is Encoded && other.hash == hash && other.bytes.contentEquals(bytes)
        }
        private val images = HashMap<Encoded, Texture>()

        /**
         * The image texture of an encoded PNG or JPEG, decoded once per program: a scene that loads the same
         * model again (a restart, the next level) gets the same texture, not one more on the GPU.
         */
        fun image(name: String, encoded: ByteArray): Texture = synchronized(images) {
            images.getOrPut(Encoded(encoded.copyOf())) { image(name, sillage.io.Image.decode(encoded)) }
        }

        /** Largest side of an image texture: larger images are scaled down (the texture array holds every texture at one size). */
        const val MAX_IMAGE = 1024

        /** [image] scaled to [size] × [size]: by area averaging when it shrinks, bilinear when it grows. */
        private fun resample(image: sillage.io.Image, size: Int): ByteArray {
            val w = image.width; val h = image.height; val src = image.rgba
            if (w == size && h == size) return src.copyOf()
            val out = ByteArray(size * size * 4)
            val sx = w.toFloat() / size; val sy = h.toFloat() / size
            for (y in 0 until size) for (x in 0 until size) {
                val o = 4 * (y * size + x)
                if (sx >= 1f && sy >= 1f) {
                    // The source pixels this one covers, averaged.
                    val x0 = (x * sx).toInt(); val x1 = maxOf(x0 + 1, ((x + 1) * sx).toInt().coerceAtMost(w))
                    val y0 = (y * sy).toInt(); val y1 = maxOf(y0 + 1, ((y + 1) * sy).toInt().coerceAtMost(h))
                    for (c in 0..3) {
                        var sum = 0
                        for (yy in y0 until y1) for (xx in x0 until x1) sum += src[4 * (yy * w + xx) + c].toInt() and 255
                        out[o + c] = ((sum + (x1 - x0) * (y1 - y0) / 2) / ((x1 - x0) * (y1 - y0))).toByte()
                    }
                } else {
                    val fx = ((x + .5f) * sx - .5f).coerceIn(0f, w - 1f); val fy = ((y + .5f) * sy - .5f).coerceIn(0f, h - 1f)
                    val ax = fx.toInt(); val ay = fy.toInt(); val bx = minOf(ax + 1, w - 1); val by = minOf(ay + 1, h - 1)
                    val tx = fx - ax; val ty = fy - ay
                    for (c in 0..3) {
                        fun at(px: Int, py: Int) = (src[4 * (py * w + px) + c].toInt() and 255).toFloat()
                        val top = at(ax, ay) + (at(bx, ay) - at(ax, ay)) * tx; val bottom = at(ax, by) + (at(bx, by) - at(ax, by)) * tx
                        out[o + c] = Math.round(top + (bottom - top) * ty).coerceIn(0, 255).toByte()
                    }
                }
            }
            return out
        }

        /** Shades (3 floats per pixel, 0 to 2) to RGBA bytes, alpha opaque. */
        internal fun shadesToBytes(m: FloatArray): ByteArray {
            val out = ByteArray(m.size / 3 * 4)
            for (i in 0 until m.size / 3) {
                for (c in 0..2) out[4 * i + c] = (m[3 * i + c] * 127.5f + .5f).toInt().coerceIn(0, 255).toByte()
                out[4 * i + 3] = -1
            }
            return out
        }

        /** A ready-made texture: painted on a [Canvas], then its mean shade brought to 1 on each channel (it details, never recolours). */
        internal fun painted(name: String, size: Int, tile: Float, strength: Float, mapping: Mapping, paint: (Canvas) -> Unit) =
            Texture(name, size, tile, strength, mapping) {
                val m = Canvas(size).also(paint).m
                for (c in 0..2) {
                    var sum = 0.0; for (i in c until m.size step 3) sum += m[i]
                    val k = (m.size / 3 / sum).toFloat(); for (i in c until m.size step 3) m[i] *= k
                }
                shadesToBytes(m)
            }

        /** Every level after the first, each the mean of four pixels of the last (alpha included). */
        private fun withLevels(first: ByteArray, size: Int): ByteArray {
            var total = 0; var s = size
            while (s >= 1) { total += s * s * 4; s /= 2 }
            val out = first.copyOf(total)
            s = size; var at = 0
            while (s > 1) {
                val h = s / 2; val next = at + s * s * 4
                for (y in 0 until h) for (x in 0 until h) for (c in 0..3) {
                    val a = at + 4 * ((2 * y) * s + 2 * x) + c; val b = a + 4 * s
                    val sum = (out[a].toInt() and 255) + (out[a + 4].toInt() and 255) + (out[b].toInt() and 255) + (out[b + 4].toInt() and 255)
                    out[next + 4 * (y * h + x) + c] = ((sum + 2) / 4).toByte()
                }
                at = next; s = h
            }
            return out
        }
    }
}

/**
 * Ready-made detail textures, painted by the engine (no image files). [named] picks one from a material
 * name, in English or French: a GLB model gets them by itself (see [Scene.autoTextures]).
 */
object Textures {
    /** Blades of grass in tufts, and lighter and darker patches. */
    val GRASS: Texture by lazy { Texture.painted("grass", 512, 2.2f, .6f, Texture.Mapping.SIDES, ::grass) }
    /** Fine grains, soft wind ripples, a few specks of shell. */
    val SAND: Texture by lazy { Texture.painted("sand", 512, 3f, .7f, Texture.Mapping.SIDES, ::sand) }
    /** Wood grain along the faces, without seams (for objects already built of planks or logs). */
    val WOOD: Texture by lazy { Texture.painted("wood", 512, 1.4f, .75f, Texture.Mapping.FACES) { wood(it, false) } }
    /** Planks 20 cm wide with their seams, staggered joints and grain: floors, decks, walls. */
    val PLANKS: Texture by lazy { Texture.painted("planks", 512, 1.6f, .85f, Texture.Mapping.FACES) { wood(it, true) } }
    /** Rows of round clay tiles, each a little different, rows running level. */
    val ROOF_TILES: Texture by lazy { Texture.painted("roof tiles", 512, 1.2f, .85f, Texture.Mapping.FACES, ::tiles) }
    /** Cobbles: rounded stones of different shades with dark joints. */
    val STONE: Texture by lazy { Texture.painted("stone", 512, 1.5f, .7f, Texture.Mapping.SIDES, ::stone) }
    /** Rock: patches, fine cracks and level strata. */
    val ROCK: Texture by lazy { Texture.painted("rock", 512, 3f, .7f, Texture.Mapping.SIDES, ::rock) }
    /** Plaster: soft patches, a fine speckle and faint streaks. */
    val PLASTER: Texture by lazy { Texture.painted("plaster", 512, 2.5f, .6f, Texture.Mapping.SIDES, ::plaster) }
    /** Woven cloth: canvas, linen, sails. */
    val FABRIC: Texture by lazy { Texture.painted("fabric", 256, .3f, .6f, Texture.Mapping.FACES, ::fabric) }
    /** Leaves in small dabs of light and shade: bushes, hedges, foliage. */
    val LEAVES: Texture by lazy { Texture.painted("leaves", 512, 1.1f, .7f, Texture.Mapping.SIDES, ::leaves) }
    /** Bark: fibres along the trunk and rings across it. */
    val BARK: Texture by lazy { Texture.painted("bark", 512, 1.2f, .7f, Texture.Mapping.SIDES, ::bark) }

    /** Every ready-made texture (each is painted when first used). */
    val ALL: List<Texture> get() = listOf(GRASS, SAND, WOOD, PLANKS, ROOF_TILES, STONE, ROCK, PLASTER, FABRIC, LEAVES, BARK)

    private val names: List<Pair<Set<String>, () -> Texture>> = listOf(
        setOf("grass", "lawn", "turf", "herbe", "herbes", "gazon", "pelouse", "pre") to { GRASS },
        setOf("sand", "beach", "dune", "sable", "plage") to { SAND },
        setOf("plank", "planks", "deck", "floor", "floorboards", "planche", "planches", "plancher", "parquet") to { PLANKS },
        setOf("wood", "wooden", "timber", "log", "bois", "poutre", "rondin") to { WOOD },
        setOf("roof", "tile", "tiles", "shingle", "shingles", "toit", "toiture", "tuile", "tuiles") to { ROOF_TILES },
        setOf("stone", "stones", "cobble", "cobbles", "paving", "pavement", "pierre", "pierres", "pave", "paves", "dalle", "dalles") to { STONE },
        setOf("rock", "rocks", "cliff", "boulder", "pebble", "pebbles", "roche", "roches", "rocher", "rochers", "falaise", "galet", "galets") to { ROCK },
        setOf("plaster", "stucco", "render", "wall", "walls", "enduit", "platre", "crepi", "mur", "murs") to { PLASTER },
        setOf("cloth", "fabric", "canvas", "linen", "sail", "sails", "toile", "tissu", "lin", "voile", "voiles") to { FABRIC },
        setOf("leaf", "leaves", "foliage", "bush", "bushes", "hedge", "feuille", "feuilles", "feuillage", "buisson", "buissons", "haie") to { LEAVES },
        setOf("bark", "trunk", "ecorce", "tronc", "troncs") to { BARK },
    )

    /**
     * The ready-made texture whose name is one of the words of [material] (in English or French, case
     * and accents ignored: « Herbe », "grass_dark", "Roof.001"), or null.
     */
    fun named(material: String): Texture? {
        val plain = java.text.Normalizer.normalize(material.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        val words = plain.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        for ((keys, texture) in names) if (words.any { it in keys }) return texture()
        return null
    }

    // ------------------------------------------------------------------ the paintings

    private fun grass(c: Canvas) {
        val s = c.size; val r = Rand(11)
        c.each { u, v, i ->
            val big = fbm(u * 3f, v * 3f, 3, 4, 1) - .5f
            val mid = fbm(u * 10f, v * 10f, 10, 3, 2) - .5f
            val k = 1f + big * .30f + mid * .16f
            c.m[i] = k * (1f + big * .12f); c.m[i + 1] = k; c.m[i + 2] = k * (1f - big * .18f)
        }
        // Dabs of shade, then lighter tips, gathered in tufts.
        for (pass in 0..1) repeat(if (pass == 0) 1100 else 800) {
            val cx = r.next() * s; val cy = r.next() * s
            val dabs = 3 + (r.next() * 5).toInt()
            for (b in 0 until dabs) {
                val a = r.next() * 2f * PI.toFloat(); val d = s * r.next() * .012f
                val len = s * (.006f + r.next() * .008f)
                val x = cx + cos(a) * d; val y = cy + sin(a) * d
                if (pass == 0) c.disc(x, y, len, len * .42f, a, .74f, .80f, .86f, .65f)
                else c.disc(x, y, len * .8f, len * .32f, a, 1.18f, 1.22f, .96f, .5f)
            }
        }
    }

    private fun sand(c: Canvas) {
        val s = c.size; val r = Rand(23)
        c.each { u, v, i ->
            val warp = fbm(u * 2f, v * 2f, 2, 3, 3)
            val phase = v * 18f + warp * 2.4f + noise(u * 6f, v * 6f, 6, 4) * .6f
            val ripple = sin(2f * PI.toFloat() * phase)
            val crest = ripple * .07f + (if (ripple > .6f) (ripple - .6f) * .12f else 0f)
            val grain = (hash(i / 3 % s, i / 3 / s, 5) - .5f) * .09f + (noise(u * 128f, v * 128f, 128, 6) - .5f) * .08f
            val patch = (fbm(u * 4f, v * 4f, 4, 3, 7) - .5f) * .12f
            val k = 1f + crest + grain + patch
            c.m[i] = k; c.m[i + 1] = k; c.m[i + 2] = k * (1f - patch * .4f)
        }
        repeat(420) {
            val x = r.next() * s; val y = r.next() * s; val rad = s * (.0015f + r.next() * .003f)
            if (r.next() < .6f) c.disc(x, y, rad, rad, 0f, .72f, .70f, .70f, .7f) else c.disc(x, y, rad, rad * .7f, r.next() * 3f, 1.25f, 1.22f, 1.15f, .8f)
        }
    }

    /** Wood grain along u; [planks]: rows of planks 1/8 of the tile wide, with seams and joints. */
    private fun wood(c: Canvas, planks: Boolean) {
        val s = c.size; val rows = 8
        val r = Rand(31)
        val tone = FloatArray(rows * 4) { (r.next() - .5f) * .16f }
        val joints = FloatArray(rows) { r.next() }
        c.each { u, v, i ->
            val row = (v * rows).toInt().coerceAtMost(rows - 1)
            val inRow = v * rows - row
            // Two lengths of plank per row, the joint staggered from row to row.
            val seg = ((u + joints[row]) * 2f).toInt()
            val t = if (planks) tone[row * 4 + (seg and 1)] else 0f     // two lengths per repeat: the shade loops at the edge
            val plank = if (planks) row else 0                          // each plank its own grain; plain wood one grain
            val warp = fbm(u * 2f, v * 4f, 2, 3, 13 + plank) * 2.2f
            val phase = v * (if (planks) 40f else 26f) + warp + (if (planks) row * .37f else 0f)
            val line = (.5f + .5f * sin(2f * PI.toFloat() * phase)).pow(7f)
            val streak = noise(u * 3f, v * 90f, 3, 90, 17) - .5f
            var k = 1f + t - line * .13f + streak * .10f + (fbm(u * 3f, v * 3f, 3, 3, 19) - .5f) * .10f
            if (planks) {
                val px = 1f / s * rows                                  // one pixel in row units
                val edge = min(inRow, 1f - inRow)
                k *= 1f - .42f * (1f - smooth(px * .6f, px * 2.4f, edge))  // the seam between rows
                if (inRow > 1f - px * 3.5f && inRow < 1f - px * 1.5f) k *= 1.07f   // a lit bevel above it
                val ju = ((u + joints[row]) * 2f) % 1f
                val jd = min(ju, 1f - ju) * s / 2f
                k *= 1f - .38f * (1f - smooth(.6f, 2.2f, jd))           // the joint between two lengths
            }
            c.m[i] = k * 1.01f; c.m[i + 1] = k; c.m[i + 2] = k * .97f
        }
        // Knots: dark rings in a few places.
        repeat(if (planks) 7 else 5) {
            val x = r.next() * s; val y = r.next() * s; val rx = s * (.010f + r.next() * .010f)
            c.disc(x, y, rx * 2.2f, rx, 0f, .86f, .84f, .82f, .6f)
            c.disc(x, y, rx, rx * .55f, 0f, .70f, .66f, .64f, .8f)
        }
    }

    private fun tiles(c: Canvas) {
        val rows = 6; val across = 7
        val r = Rand(41)
        val tone = FloatArray(rows * across) { (r.next() - .5f) * .2f }
        val hue = FloatArray(rows * across) { (r.next() - .5f) * .12f }
        c.each { u, v, i ->
            val row = (v * rows).toInt().coerceAtMost(rows - 1)
            val t = v * rows - row                                       // 0 at the row's lower rim, 1 at its top
            val shifted = u * across + (if (row % 2 == 1) .5f else 0f)
            val col = floor(shifted).toInt()
            val a = shifted - col                                        // across one tile
            val id = row * across + ((col % across) + across) % across
            // A rounded tile: lit along its crown, darker towards its sides, a dark gap between tiles.
            val crown = sin(PI.toFloat() * a)
            var k = (.80f + .30f * crown) * (1f + tone[id])
            k *= 1f - .45f * (1f - smooth(.0f, .06f, min(a, 1f - a)))
            // The rim lit at the bottom, the shade of the row above at the top, a line between rows.
            k *= .90f + .16f * (1f - t) * (1f - t)
            k *= 1f - .40f * smooth(.80f, 1f, t)
            k *= 1f - .45f * (1f - smooth(0f, .035f, t))
            k *= 1f + (fbm(u * 5f, v * 5f, 5, 3, 43) - .5f) * .16f
            c.m[i] = k * (1f + hue[id]); c.m[i + 1] = k; c.m[i + 2] = k * (1f - hue[id] * .5f)
        }
    }

    private fun stone(c: Canvas) {
        val cell = FloatArray(3)
        c.each { u, v, i ->
            val wu = u + (fbm(u * 4f, v * 4f, 4, 2, 51) - .5f) * .05f
            val wv = v + (fbm(u * 4f, v * 4f, 4, 2, 52) - .5f) * .05f
            worley(wu * 7f, wv * 7f, 7, 53, cell)
            val joint = cell[1] - cell[0]
            val tone = (cell[2] - .5f) * .22f
            var k = (1.08f - cell[0] * .22f) * (1f + tone)
            k *= 1f - .48f * (1f - smooth(.03f, .12f, joint))
            k *= 1f + (noise(u * 96f, v * 96f, 96, 54) - .5f) * .10f
            c.m[i] = k * (1f + tone * .2f); c.m[i + 1] = k; c.m[i + 2] = k * (1f - tone * .2f)
        }
    }

    private fun rock(c: Canvas) {
        val cell = FloatArray(3); val small = FloatArray(3)
        c.each { u, v, i ->
            val f = fbm(u * 3f, v * 3f, 3, 5, 61) - .5f
            worley(u * 3f + f * .9f, v * 3f + f * .9f, 3, 62, cell)
            worley(u * 9f + f * 1.5f, v * 9f + f * 1.5f, 9, 64, small)
            // Big facets with a lit top, cracks between them, finer cracks only here and there.
            val facet = (cell[2] - .5f) * .22f + (.5f - cell[0]) * .14f
            val crack = 1f - smooth(.0f, .06f, cell[1] - cell[0])
            val fine = (1f - smooth(.0f, .04f, small[1] - small[0])) * smooth(.55f, .75f, noise(u * 5f, v * 5f, 5, 65))
            val strata = sin(2f * PI.toFloat() * (v * 7f + f * 2.2f))
            var k = 1f + f * .26f + facet + strata * .045f
            k *= (1f - crack * .45f) * (1f - fine * .25f)
            k *= 1f + (noise(u * 80f, v * 80f, 80, 63) - .5f) * .08f
            c.m[i] = k * (1f + f * .06f); c.m[i + 1] = k; c.m[i + 2] = k * (1f - f * .08f)
        }
    }

    private fun plaster(c: Canvas) {
        c.each { u, v, i ->
            val f = fbm(u * 3f, v * 3f, 3, 5, 71) - .5f
            val streak = (noise(u * 36f, v * 2f, 36, 2, 72) - .5f) * smooth(.3f, 1f, noise(u * 4f, v * 1f, 4, 1, 73))
            val speck = (hash((u * c.size).toInt(), (v * c.size).toInt(), 74) - .5f) * .05f
            val k = 1f + f * .14f + streak * .07f + speck + (noise(u * 64f, v * 64f, 64, 75) - .5f) * .05f
            c.m[i] = k; c.m[i + 1] = k * .995f; c.m[i + 2] = k * (1f - f * .05f)
        }
    }

    private fun fabric(c: Canvas) {
        val s = c.size; val threads = s / 4
        c.each { u, v, i ->
            val x = u * threads; val y = v * threads
            val over = ((floor(x).toInt() + floor(y).toInt()) and 1) == 0
            val wx = sin(PI.toFloat() * (x - floor(x))); val wy = sin(PI.toFloat() * (y - floor(y)))
            val slub = (noise(u * 6f, v * 40f, 6, 40, 81) - .5f) * .08f + (noise(u * 40f, v * 6f, 40, 6, 82) - .5f) * .08f
            val k = .90f + .14f * (if (over) wy else wx) + slub
            c.m[i] = k; c.m[i + 1] = k; c.m[i + 2] = k
        }
    }

    private fun leaves(c: Canvas) {
        val s = c.size; val r = Rand(91)
        c.each { u, v, i ->
            val f = fbm(u * 4f, v * 4f, 4, 4, 92) - .5f
            val k = .92f + f * .30f
            c.m[i] = k * (1f + f * .08f); c.m[i + 1] = k; c.m[i + 2] = k * (1f - f * .1f)
        }
        // Leaves: dark ones underneath, lit ones on top, each a pointed oval.
        for (pass in 0..1) repeat(if (pass == 0) 1300 else 900) {
            val x = r.next() * s; val y = r.next() * s; val len = s * (.014f + r.next() * .016f)
            if (pass == 0) c.disc(x, y, len, len * .42f, r.next() * 3.2f, .72f, .78f, .84f, .7f)
            else c.disc(x, y, len * .85f, len * .36f, r.next() * 3.2f, 1.24f, 1.28f, .94f, .6f)
        }
    }

    private fun bark(c: Canvas) {
        c.each { u, v, i ->
            val fibre = noise(u * 40f, v * 3f, 40, 3, 101) - .5f
            val ring = (.5f + .5f * sin(2f * PI.toFloat() * (v * 8f + (noise(u * 3f, v * 3f, 3, 102) - .5f) * .6f))).pow(10f)
            val f = fbm(u * 3f, v * 3f, 3, 3, 103) - .5f
            val k = (1f + fibre * .22f + f * .16f) * (1f - ring * .3f)
            c.m[i] = k * 1.01f; c.m[i + 1] = k; c.m[i + 2] = k * .97f
        }
    }
}

// ---------------------------------------------------------------------- painting tools

/** A square of shades (3 floats per pixel, 1 = unchanged) and the brushes that paint on it; everything wraps around. */
internal class Canvas(val size: Int) {
    val m = FloatArray(3 * size * size) { 1f }

    /** Calls [f] for every pixel with u, v at its centre and the index of its red in [m]. */
    inline fun each(f: (u: Float, v: Float, i: Int) -> Unit) {
        val inv = 1f / size
        for (y in 0 until size) for (x in 0 until size) f((x + .5f) * inv, (y + .5f) * inv, 3 * (y * size + x))
    }

    private fun blend(x: Int, y: Int, kr: Float, kg: Float, kb: Float, a: Float) {
        if (a <= 0f) return
        val i = 3 * (Math.floorMod(y, size) * size + Math.floorMod(x, size))
        m[i] *= 1f + (kr - 1f) * a; m[i + 1] *= 1f + (kg - 1f) * a; m[i + 2] *= 1f + (kb - 1f) * a
    }

    /** A stroke from (x0, y0) to (x1, y1), [width] pixels wide at its root, thinning to its tip. */
    fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, kr: Float, kg: Float, kb: Float, opacity: Float) {
        val dx = x1 - x0; val dy = y1 - y0; val l2 = (dx * dx + dy * dy).coerceAtLeast(1e-6f)
        val pad = width + 1.5f
        for (y in floor(min(y0, y1) - pad).toInt()..ceil(max(y0, y1) + pad).toInt())
            for (x in floor(min(x0, x1) - pad).toInt()..ceil(max(x0, x1) + pad).toInt()) {
                val px = x + .5f - x0; val py = y + .5f - y0
                val t = ((px * dx + py * dy) / l2).coerceIn(0f, 1f)
                val ex = px - dx * t; val ey = py - dy * t
                val w = width * (1f - .75f * t) * .5f
                val d = sqrt(ex * ex + ey * ey)
                blend(x, y, kr, kg, kb, opacity * (1f - smooth(w - .5f, w + .7f, d)))
            }
    }

    /** A soft-edged ellipse of radii [rx], [ry] turned by [angle] radians. */
    fun disc(cx: Float, cy: Float, rx: Float, ry: Float, angle: Float, kr: Float, kg: Float, kb: Float, opacity: Float) {
        val ca = cos(angle); val sa = sin(angle); val pad = max(rx, ry) + 1.5f
        for (y in floor(cy - pad).toInt()..ceil(cy + pad).toInt()) for (x in floor(cx - pad).toInt()..ceil(cx + pad).toInt()) {
            val px = x + .5f - cx; val py = y + .5f - cy
            val a = (px * ca + py * sa) / rx; val b = (-px * sa + py * ca) / ry
            val d = sqrt(a * a + b * b)
            blend(x, y, kr, kg, kb, opacity * (1f - smooth(1f - 1.2f / min(rx, ry).coerceAtLeast(1f), 1f, d)))
        }
    }
}

/** A small random generator with a seed (xorshift), so every painting is the same from run to run. */
private class Rand(seed: Long) {
    private var x = seed * 0x9E3779B97F4A7C15uL.toLong() or 1L
    fun next(): Float { x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17); return ((x ushr 40).toInt() and 0xFFFFFF) / 16777216f }
}

private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

/** A number from 0 to 1 for a lattice point. */
private fun hash(x: Int, y: Int, seed: Int): Float {
    var h = x * 374761393 + y * 668265263 + seed * 1442695041
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h ushr 8) / 16777216f
}

/** Smooth value noise from 0 to 1, repeating every [period] units (x and y from 0 to [period] cover one repeat). */
private fun noise(x: Float, y: Float, period: Int, seed: Int): Float = noise(x, y, period, period, seed)

/** The same, stretched: repeating every [periodX] units along x and [periodY] along y. */
private fun noise(x: Float, y: Float, periodX: Int, periodY: Int, seed: Int): Float {
    val fx = floor(x); val fy = floor(y)
    val ix = fx.toInt(); val iy = fy.toInt()
    val tx = x - fx; val ty = y - fy
    val ux = tx * tx * (3 - 2 * tx); val uy = ty * ty * (3 - 2 * ty)
    val x0 = Math.floorMod(ix, periodX); val x1 = Math.floorMod(ix + 1, periodX)
    val y0 = Math.floorMod(iy, periodY); val y1 = Math.floorMod(iy + 1, periodY)
    val a = hash(x0, y0, seed); val b = hash(x1, y0, seed); val c = hash(x0, y1, seed); val d = hash(x1, y1, seed)
    return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
}

/** Octaves of [noise], each twice as fine and half as strong, from 0 to 1, repeating like the first. */
private fun fbm(x: Float, y: Float, period: Int, octaves: Int, seed: Int): Float {
    var sum = 0f; var amp = .5f; var f = 1; var norm = 0f
    for (o in 0 until octaves) { sum += noise(x * f, y * f, period * f, seed + o * 31) * amp; norm += amp; amp *= .5f; f *= 2 }
    return sum / norm
}

/**
 * Cells around scattered points, repeating every [period] cells: [out] gets the distance to the nearest
 * point, to the second nearest (in cells) and a number from 0 to 1 for the nearest point's cell.
 */
private fun worley(x: Float, y: Float, period: Int, seed: Int, out: FloatArray) {
    val ix = floor(x).toInt(); val iy = floor(y).toInt()
    var d1 = 9f; var d2 = 9f; var id = 0f
    for (j in -1..1) for (i in -1..1) {
        val cx = ix + i; val cy = iy + j
        val wx = Math.floorMod(cx, period); val wy = Math.floorMod(cy, period)
        val px = cx + .15f + .7f * hash(wx, wy, seed); val py = cy + .15f + .7f * hash(wx, wy, seed + 1)
        val d = sqrt((px - x) * (px - x) + (py - y) * (py - y))
        if (d < d1) { d2 = d1; d1 = d; id = hash(wx, wy, seed + 2) } else if (d < d2) d2 = d
    }
    out[0] = d1; out[1] = d2; out[2] = id
}

/**
 * Writes every ready-made texture to [dir]: the pattern itself (name.png, mid-grey = unchanged) and the
 * pattern on a typical colour, twice repeated (name-preview.png). For `--export-textures`.
 */
internal fun exportTextures(dir: java.nio.file.Path) {
    java.nio.file.Files.createDirectories(dir)
    val tints = mapOf("grass" to 0x5FA040, "sand" to 0xE8C98A, "wood" to 0x8A5A30, "planks" to 0xB07F4A, "roof tiles" to 0xC2502E,
        "stone" to 0x9C9A94, "rock" to 0x9A7A58, "plaster" to 0xF0E2BC, "fabric" to 0xF2E8CC, "leaves" to 0x3E8A30, "bark" to 0x8A5A30)
    for (t in Textures.ALL) {
        val px = t.pixels; val s = t.size
        val raw = ByteArray(3 * s * s)
        val preview = ByteArray(3 * 4 * s * s)
        fun set(img: ByteArray, w: Int, x: Int, y: Int, rgb: Int) { val o = 3 * (y * w + x); img[o] = (rgb ushr 16).toByte(); img[o + 1] = (rgb ushr 8).toByte(); img[o + 2] = rgb.toByte() }
        val tint = Color.hex(tints[t.name] ?: 0x808080)
        fun srgb(v: Float) = (v.coerceIn(0f, 1f).pow(1f / 2.2f) * 255f + .5f).toInt()
        for (y in 0 until s) for (x in 0 until s) {
            val i = 4 * (y * s + x)
            val r = px[i].toInt() and 255; val g = px[i + 1].toInt() and 255; val b = px[i + 2].toInt() and 255
            set(raw, s, x, y, (r shl 16) or (g shl 8) or b)
            val k = 1f + (r / 127.5f - 1f) * t.strength
            val kg = 1f + (g / 127.5f - 1f) * t.strength; val kb = 1f + (b / 127.5f - 1f) * t.strength
            val rgb = (srgb(tint.r * k) shl 16) or (srgb(tint.g * kg) shl 8) or srgb(tint.b * kb)
            for (oy in 0..1) for (ox in 0..1) set(preview, 2 * s, x + ox * s, y + oy * s, rgb)
        }
        val file = t.name.replace(' ', '-')
        sillage.io.PngWriter.write(dir.resolve("$file.png"), s, s, raw)
        sillage.io.PngWriter.write(dir.resolve("$file-preview.png"), 2 * s, 2 * s, preview)
    }
    println("SILLAGE_TEXTURES ${Textures.ALL.size} textures in $dir")
}
