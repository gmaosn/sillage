package sillage

import sillage.audio.Audio
import sillage.audio.Sounds
import sillage.math.Vec3
import sillage.physics.Compound
import sillage.physics.ConvexHull
import kotlin.math.abs
import kotlin.test.*

/** The physics' new shapes and water, as a scene uses them. */
class WaterSceneTest {
    private val dt = 1f / 60f

    @Test fun lEauDeLaScènePorteEtÉclabousse() {
        val s = Scene().apply { ground(at = vec(0, -4, 0)); water(level = 0) }
        s.audio = Audio.offline()
        val crate = s.box(1, at = vec(0, 2, 0)).dynamic(density = 500)
        val stone = s.box(.5, color = Color.STONE, at = vec(3, 2, 0)).dynamic(density = 2600)
        var splashes = 0; var maxVoices = 0
        repeat(60 * 8) {
            val before = s.particles.count
            s.step(dt)
            if (s.particles.count > before + 6) splashes++
            maxVoices = maxOf(maxVoices, s.audio!!.mixer.playing)
        }
        // A cube half as dense as water floats half in: its centre at the surface.
        assertEquals(0f, crate.position.y, .05f, "the crate floats half in")
        assertTrue(crate.body!!.submergedFraction in .45f..0.55f, "${crate.body!!.submergedFraction}")
        assertTrue(stone.position.y < -3f, "the stone sank to the bottom: ${stone.position}")
        assertTrue(splashes >= 2, "both splashed ($splashes)")
        assertTrue(maxVoices >= 1, "and were heard")
    }

    @Test fun lÉcumeAuPiedDesFalaisesEtDesPoteaux() {
        // Une falaise qui sort droit de l'eau (maillage solide), un poteau (corps statique), une plage en pente douce.
        val s = Scene()
        val beach = MeshBuilder(); beach.quad(vec(10, -1, -6), vec(10, -1, 6), vec(16, 1, 6), vec(16, 1, -6), Color.SAND)
        s.mesh(beach.build(), at = Vec3.ZERO).solid()
        s.mesh(Mesh.box(2f, 6f, 2f, Color.STONE), at = vec(0, 0, 0)).solid()
        s.collider(sillage.physics.Box(.15f, 2f, .15f), at = vec(-5, 0, 0))
        val water = s.water(level = 0, size = 60)
        s.drawList(Vec3.ZERO)
        val v = water.mesh.vertices
        fun at(x: Float, z: Float): Pair<Float, Float> {
            var best = Float.MAX_VALUE; var r = 0f; var g = 0f
            for (k in v.indices step 9) { val d = (v[k] - x) * (v[k] - x) + (v[k + 2] - z) * (v[k + 2] - z); if (d < best) { best = d; r = v[k + 6]; g = v[k + 7] } }
            return r to g
        }
        // Au pied de la falaise (son flanc en x = 1) : à moins d'un demi-mètre du mur, et pas de plage (pas de lagune).
        val cliff = at(1.25f, 0f)
        assertTrue(cliff.second in 1f..1.5f, "falaise : ${cliff.second}")
        assertTrue(cliff.first > 3f, "pas de rivage au pied de la falaise : ${cliff.first}")
        assertTrue(at(-5.5f, 0f).second in 1f..1.6f, "poteau : ${at(-5.5f, 0f).second}")
        assertEquals(0f, at(-20f, 20f).second, "loin de tout : rien")
        // La plage garde son rivage, sans mur.
        val shore = at(14.5f, 0f)
        assertEquals(0f, shore.second, "plage : pas de mur (${shore.second})")
        assertTrue(shore.first < .6f, "plage : son rivage (${shore.first})")
    }

    @Test fun uneEauSansPousséeLaisseCouler() {
        val s = Scene().apply { ground(at = vec(0, -4, 0)); water(level = 0, buoyant = false) }
        val crate = s.box(1, at = vec(0, 2, 0)).dynamic(density = 500)
        repeat(60 * 5) { s.step(dt) }
        assertTrue(crate.position.y < -3f)
    }

    @Test fun uneEnveloppeGardeSaPlaceSurLeNœud() {
        // A mesh off its node's origin: the hull's centre is not at the origin, the node must not jump.
        val b = MeshBuilder()
        b.box(2f, 0f, 0f, 1f, 1f, 1f, Color.STONE)                   // its base on the origin's level, 2 m along x
        val s = Scene().apply { ground() }
        val rock = s.mesh(b.build(), at = vec(0, 0, 0))
        val hull = rock.hullShape()
        assertEquals(2f, hull.centre.x, 1e-3f)
        rock.dynamic(shape = hull)
        assertEquals(0f, rock.position.x, 1e-5f)
        assertEquals(2f, rock.body!!.position.x, 1e-3f, "the body sits on the hull's centre of mass")
        repeat(120) { s.step(dt) }
        assertEquals(0f, rock.position.x, .02f); assertEquals(0f, rock.position.y, .02f, "resting on the ground as the mesh says")
    }

    @Test fun uneÉpauleResteDansSonCône() {
        val s = Scene()
        val arm = s.capsule(.08, .6, at = vec(0, 1.7, 0)).dynamic(mass = 3)
        val j = arm.cone(vec(0, 2, 0), Vec3(0f, -1f, 0f), swing = 30, twist = 20)
        arm.body!!.applyImpulse(Vec3(40f, 0f, 0f), vec(0, 1.5, 0))
        var worst = 0f
        repeat(240) { s.step(dt); worst = maxOf(worst, j.swingAngle) }
        assertTrue(worst < Math.toRadians(30.0).toFloat() + .03f, "within 30° (worst ${Math.toDegrees(worst.toDouble())}°)")
        assertTrue(worst > Math.toRadians(20.0).toFloat(), "it did swing up to the cone")
    }

    @Test fun leBruitDUneChuteDansLEau() {
        for (size in listOf(0f, .5f, 1f)) {
            val snd = Sounds.splash(size)
            val d = snd.data
            assertTrue(d.all { it.isFinite() })
            val peak = d.maxOf { abs(it) }
            assertEquals(.85f, peak, .02f)
            assertTrue(snd.duration in .45f..1f, "${snd.duration}")
        }
        assertTrue(Sounds.splash(1f).duration > Sounds.splash(0f).duration, "a big fall lasts longer")
    }

    @Test fun leDébogageDessineLesNouvellesFormes() {
        val s = Scene().apply { water(level = 0, size = 40) }
        val pts = listOf(vec(0, 0, 0), vec(1, 0, 0), vec(0, 1, 0), vec(0, 0, 1), vec(1, 1, 1))
        s.physics.add(ConvexHull(pts), vec(0, 5, 0))
        s.physics.add(Compound(Compound.Part(sillage.physics.Box(.5f, .1f, .5f)), Compound.Part(sillage.physics.Sphere(.2f), vec(0, .4, 0))), vec(3, 5, 0))
        s.debug.showPhysics = true
        val extras = FrameExtras()
        s.debug.fill(extras, vec(0, 5, 10))
        // The hull's 5 vertices make 6 faces of 3 edges (18 segments drawn), the compound a box (12) and a sphere (72), the water 4.
        assertEquals(2 * (18 + 12 + 72 + 4), extras.lineVertexCount)
    }
}
