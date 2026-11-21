package sillage

import sillage.math.Vec3
import kotlin.math.PI
import kotlin.test.*

/** Scripts, conditions, tweens, and the scene's units (degrees, whole sizes, onGround). */
class ScriptTest {
    private val dt = .1f
    private fun Scene.frames(n: Int) = repeat(n) { step(dt) }

    @Test fun unScriptAttendLeTempsPuisUneCondition() {
        val s = Scene()
        val log = StringBuilder()
        var flag = false
        val sc = s.script {
            log.append('a'); wait(1); log.append('b'); waitUntil { flag }; log.append('c'); nextFrame(); log.append('d')
        }
        assertEquals("", log.toString(), "a script starts at the next update, not while the scene is built")
        s.frames(1); assertEquals("a", log.toString())
        s.frames(9); assertEquals("a", log.toString())
        s.frames(2); assertEquals("ab", log.toString())
        s.frames(5); assertEquals("ab", log.toString())
        flag = true
        s.frames(1); assertEquals("abc", log.toString())
        assertFalse(sc.done)
        s.frames(1); assertEquals("abcd", log.toString())
        assertTrue(sc.done)
    }

    @Test fun unScriptArrêtéNeReprendPas() {
        val s = Scene(); var n = 0
        val sc = s.script { while (true) { n++; wait(.25) } }
        s.frames(10); val seen = n
        assertTrue(seen in 3..5, "a pass every 0.25 s for 1 s: $seen")
        sc.stop(); s.frames(10)
        assertEquals(seen, n)
    }

    @Test fun uneErreurDansUnScriptRemonte() {
        val s = Scene()
        s.script { wait(.15); error("panne") }
        s.frames(1)
        assertEquals("panne", assertFailsWith<IllegalStateException> { s.frames(2) }.message)
    }

    @Test fun onceEtWhenever() {
        val s = Scene(); var x = 0; var onceCount = 0; var wheneverCount = 0
        s.once({ x >= 2 }) { onceCount++ }
        s.whenever({ x % 2 == 1 }) { wheneverCount++ }
        for (v in listOf(0, 1, 1, 2, 3, 3, 4, 5)) { x = v; s.frames(1) }
        assertEquals(1, onceCount)
        assertEquals(3, wheneverCount, "1, 3, 5: three times it became odd")
        s.restart(); x = 7; s.frames(1)
        assertEquals(2, onceCount, "restart arms once again")
    }

    @Test fun lesInterpolations() {
        assertEquals(0f, Ease.IN_OUT.at(0f)); assertEquals(1f, Ease.IN_OUT.at(1f)); assertEquals(.5f, Ease.IN_OUT.at(.5f), 1e-6f)
        assertEquals(.5f, Ease.LINEAR.at(.5f))
        assertTrue(Ease.IN.at(.5f) < .5f && Ease.OUT.at(.5f) > .5f)
        assertTrue((1..99).any { Ease.SPRING.at(it / 100f) > 1f }, "a spring goes past the end"); assertEquals(1f, Ease.SPRING.at(1f), 1e-5f)
        val s = Scene(); val seen = ArrayList<Float>()
        s.tween(10, 20, over = 1, ease = Ease.LINEAR, after = .5) { seen += it }
        s.frames(5); assertTrue(seen.isEmpty() || seen.all { it == 10f }, "nothing before its start: $seen")
        s.frames(11); assertEquals(20f, seen.last(), 1e-4f)          // 0.5 s waited, 1 s of change
        val k = seen.size; s.frames(3); assertEquals(k, seen.size, "a finished tween is gone")
    }

    @Test fun unTexteSEfface() {
        val s = Scene()
        val t = with(s) { hud.text("TITLE", 0, 0) }
        with(s) { t.fadeOut(over = 1, after = 1) }
        s.frames(10); assertEquals(1f, t.opacity); assertTrue(t.visible)
        s.frames(5); assertEquals(.5f, t.opacity, .02f)
        s.frames(6); assertFalse(t.visible); assertEquals(0f, t.opacity)
        with(s) { t.fadeIn(over = .5) }
        s.frames(1); assertTrue(t.visible)
    }

    @Test fun unNoeudSeDéplaceEtGrandit() {
        val s = Scene()
        val lift = s.box(2, .3, 2, at = vec(0, 0, 0)).kinematic()
        val tw = lift.moveTo(vec(0, 3, 0), over = 1)
        val crate = s.box(1, at = vec(5, 1, 5))
        crate.scaleTo(2, over = .5, ease = Ease.LINEAR)
        s.frames(12)
        assertTrue(tw.done)
        assertEquals(3f, lift.position.y, 1e-4f)
        assertEquals(3f, lift.body!!.position.y, .05f, "the kinematic body followed")
        assertEquals(2f, crate.scale, 1e-4f)
    }

    @Test fun posésSurLeSol() {
        val s = Scene().apply { ground() }
        val a = s.box(1, at = vec(0, 9, 0)).onGround(2, 3)
        assertEquals(.505f, a.position.y, 1e-3f); assertEquals(2f, a.position.x); assertEquals(3f, a.position.z)
        val b = s.box(1).rotate(z = 45).onGround(-2, 0)
        assertEquals(.7071f + .005f, b.position.y, 1e-3f)
        val c = s.box(1).dynamic().onGround(4, 0, lift = 1)
        assertEquals(1.505f, c.body!!.position.y, 1e-3f, "the body goes with it")
        assertEquals(vec(1, 0, 2), s.groundAt(1, 2))
    }

    @Test fun lesUnitésDeLaScène() {
        val b = boxShape(2, 1, 4); assertEquals(1f, b.halfX); assertEquals(.5f, b.halfY); assertEquals(2f, b.halfZ)
        val c = capsuleShape(.3, 1.6); assertEquals(.5f, c.halfHeight, 1e-6f); assertEquals(.3f, c.radius)
        assertEquals(.4f, sphereShape(.4).radius)
        assertFailsWith<IllegalArgumentException> { boxShape(0) }
        val s = Scene()
        val n = s.box(1).rotate(x = 20, y = 90, z = 10)
        val q = rotation(x = 20, y = 90, z = 10)
        val v = Vec3(.3f, .5f, .7f)
        assertTrue((n.rotation.rotate(v) - q.rotate(v)).length() < 1e-5f, "rotation() turns as Node.rotate does")
        assertTrue((rotation(y = 90).rotate(Vec3(1f, 0f, 0f)) - Vec3(0f, 0f, -1f)).length() < 1e-5f)
    }

    @Test fun lesButéesDUneCharnièreEnDegrés() {
        val s = Scene()
        val door = s.box(1, 2, .1, at = vec(0, 1, 0)).dynamic()
        val j = door.hinge(vec(-.5, 1, 0), Vec3.Y, min = -30, max = 90)
        assertTrue(j.limitsEnabled)
        assertEquals((-30 * PI / 180).toFloat(), j.lowerAngle, 1e-6f)
        assertEquals((90 * PI / 180).toFloat(), j.upperAngle, 1e-6f)
    }
}
