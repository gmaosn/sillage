package sillage

import sillage.math.Mat4
import sillage.math.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.*

/** Ce que le moteur de rendu décide des lampes (RenderBatches.lights), sans carte graphique : la grille, les cubes d'ombre. */
class LampsTest {
    private val eye = vec(0, 2, 6)
    private val viewProj = Mat4.perspective(sillage.math.toRadians(60.0).toFloat(), 1.6f, .1f, 500f) * Mat4.lookAt(eye, vec(0, 1, -10))
    private val frame = FloatArray(FRAME_FLOATS)
    /** Où commencent, dans le bloc de l'image, la grille, ses tranches, puis les lampes. */
    private val gridAt = (14 + CASCADES) * 4
    private val lightsAt = gridAt + 8

    private fun batches() = RenderBatches {}.also { it.lampShadows(LAMP_SHADOWS, LAMP_TEXELS) }

    @Test fun peuDeLampesToutesPourChaquePoint() {
        val s = Scene()
        repeat(LOOP_LIGHTS) { s.light(at = vec(-8 + it, 1, -50 - it * 10), radius = 2) }     // certaines hors de vue : gardées quand même
        val b = batches()
        b.lights(s.frameExtras(eye), viewProj, eye, 0f, 1280, 800, false)
        assertFalse(b.gridOn); assertEquals(LOOP_LIGHTS, b.shownCount)
        b.writeFrame(frame, s.lighting, Vec3.Y, viewProj, s.frameExtras(eye), 2048)
        assertEquals(0f, frame[gridAt], "sans grille")
        assertEquals(LOOP_LIGHTS.toFloat(), frame[3 * 4 + 3], "le nombre de lampes dans skyAmb.w")
    }

    @Test fun beaucoupDeLampesUneGrilleQuiLesTrouveToutes() {
        val s = Scene()
        val r = Random(7)
        repeat(100) { s.light(at = vec(r.nextFloat() * 40 - 20, r.nextFloat() * 3, -r.nextFloat() * 40), radius = 1 + r.nextFloat() * 4) }
        repeat(10) { s.light(at = vec(0, 1, 40 + it), radius = 2) }                          // derrière l'œil : laissées
        val x = s.frameExtras(eye)
        val b = batches()
        b.lights(x, viewProj, eye, 0f, 1280, 800, false)
        assertTrue(b.gridOn); assertTrue(b.shownCount in LOOP_LIGHTS + 1..100, "${b.shownCount} lampes")
        b.writeFrame(frame, s.lighting, Vec3.Y, viewProj, x, 2048)
        val tilesX = frame[gridAt].toInt(); val tilesY = frame[gridAt + 1].toInt()
        val near = frame[gridAt + 4]; val perDoubling = frame[gridAt + 5]; val slices = frame[gridAt + 6].toInt()
        assertEquals(GRID_X, tilesX); assertEquals(GRID_Y, tilesY)
        fun slice(d: Float) = if (d < near) 0 else min(slices - 1, 1 + floor(ln(d / near) / ln(2f) * perDoubling).toInt())
        // Chaque lampe montrée est dans la liste de la case où se voit son centre (comme le shader cherche, shading.glsl).
        var checked = 0
        for (j in 0 until b.shownCount) {
            val o = lightsAt + 4 * j
            val p = Vec3(frame[o], frame[o + 1], frame[o + 2])
            val w = viewProj.m03 * p.x + viewProj.m13 * p.y + viewProj.m23 * p.z + viewProj.m33
            if (w <= .2f) continue
            val nx = (viewProj.m00 * p.x + viewProj.m10 * p.y + viewProj.m20 * p.z + viewProj.m30) / w
            val ny = (viewProj.m01 * p.x + viewProj.m11 * p.y + viewProj.m21 * p.z + viewProj.m31) / w
            if (abs(nx) > .99f || abs(ny) > .99f) continue
            val fx = (nx * .5f + .5f) * 1280; val fy = (ny * .5f + .5f) * 800
            val tx = min((fx * frame[gridAt + 2]).toInt(), tilesX - 1); val ty = min((fy * frame[gridAt + 3]).toInt(), tilesY - 1)
            val at = 4 * GRID_WIDTH * slice(p.distanceTo(eye)) + 16 * (ty * tilesX + tx)
            val n = b.grid[at].toInt()
            assertTrue(n in 1..GRID_LAMPS)
            val listed = (0 until n).map { b.grid[at + 1 + it].toInt() and 255 }
            if (n < GRID_LAMPS) assertTrue(j in listed, "lampe $j absente de sa case ($listed)")
            checked++
        }
        assertTrue(checked > 20, "$checked lampes vérifiées")
    }

    /** La face, le point de l'image et la profondeur où shading.glsl (lampLight) cherche le point [l] loin de la lampe. */
    private fun shaderLookup(l: Vec3, r: Float, size: Int): Triple<Int, Pair<Float, Float>, Float> {
        val ax = abs(l.x); val ay = abs(l.y); val az = abs(l.z)
        val face: Int; val qx: Float; val qy: Float; val qz: Float
        if (ax >= ay && ax >= az) { face = if (l.x > 0f) 0 else 1; qx = if (l.x > 0f) -l.z else l.z; qy = l.y; qz = ax }
        else if (ay >= az) { face = if (l.y > 0f) 2 else 3; qx = l.x; qy = if (l.y > 0f) -l.z else l.z; qz = ay }
        else { face = if (l.z > 0f) 4 else 5; qx = if (l.z > 0f) l.x else -l.x; qy = l.y; qz = az }
        val s = 1f - 6f / size
        val near = (r * .1f).coerceIn(.15f, .8f)
        return Triple(face, (qx / qz * .5f * s + .5f) to (qy / qz * .5f * s + .5f), r / (r - near) * (1f - near / qz))
    }

    @Test fun lesFacesDuCubeSontCellesQueLitLeShader() {
        val b = batches()
        val lamp = vec(3, 1.5, -2); val r = 7f
        val rnd = Random(3)
        repeat(2000) {
            val l = Vec3(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1).normalized() * (1f + rnd.nextFloat() * 5f)
            val (face, uv, depth) = shaderLookup(l, r, LAMP_TEXELS)
            val m = b.faceView(face, lamp.x, lamp.y, lamp.z, r)
            val p = lamp + l
            val w = m.m03 * p.x + m.m13 * p.y + m.m23 * p.z + m.m33
            val cx = (m.m00 * p.x + m.m10 * p.y + m.m20 * p.z + m.m30) / w
            val cy = (m.m01 * p.x + m.m11 * p.y + m.m21 * p.z + m.m31) / w
            val cz = (m.m02 * p.x + m.m12 * p.y + m.m22 * p.z + m.m32) / w
            assertEquals(uv.first, cx * .5f + .5f, 1e-4f); assertEquals(uv.second, cy * .5f + .5f, 1e-4f)
            assertEquals(depth, cz, 1e-4f)
            // Le point est dans la face, à trois texels du bord au moins (les neuf lectures restent dessus).
            assertTrue(abs(cx) <= 1f - 5.9f / LAMP_TEXELS && abs(cy) <= 1f - 5.9f / LAMP_TEXELS, "face $face, $cx $cy")
        }
    }

    @Test fun ceQuiNeBougePasNEstDessinéQuUneFois() {
        val s = Scene()
        s.lighting.time = 22f
        s.light(at = vec(0, 1.5, 0), radius = 6, intensity = 3)
        s.box(1, at = vec(1.5, .5, 0))                                   // immobile
        val ball = s.sphere(.3, at = vec(-1.5, .5, 0))                   // qui bouge
        val b = batches()
        val eye = vec(0, 3, 6); val vp = Mat4.perspective(1f, 1.6f, .1f, 500f) * Mat4.lookAt(eye, Vec3.ZERO)
        fun frame(): Pair<Int, Int> {
            b.lights(s.frameExtras(eye), vp, eye, 0f, 1280, 800, true)
            b.gather(s.drawList(eye), null, vp, eye, 0f, Vec3.Y)
            return b.lampStatic.count { it } to b.lampLive.count { it }
        }
        var first = frame()
        assertEquals(6, first.first, "un cube neuf : ses six caches"); assertEquals(6, first.second)
        repeat(20) { ball.position = vec(-1.5, .5, it * .01); frame() }
        val still = frame().let { ball.position = vec(-1.5, .5, .5); frame() }
        assertEquals(0, still.first, "rien d'immobile n'a changé : pas de cache refait")
        assertTrue(still.second in 1..3, "la balle seule : les faces où elle passe (${still.second})")
        repeat(20) { frame() }
        val quiet = frame()
        assertEquals(0 to 0, quiet, "tout est immobile : rien n'est dessiné")
        b.writeFrame(this.frame, s.lighting, Vec3.Y, vp, s.frameExtras(eye), 2048)
        assertEquals(1f, this.frame[lightsAt + 4 * MAX_LIGHTS + 3], "la lampe a son cube (1 + sa place)")
    }
}
