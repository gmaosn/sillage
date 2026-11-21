package sillage.input

import sillage.*
import sillage.math.Vec3
import kotlin.math.*
import kotlin.test.*

/** L'API des manettes vue du jeu, avec des manettes virtuelles : zones mortes, actions, emplacements, joueur et caméra. */
class PadApiTest {
    private val dt = 1f / 60f

    private fun input(vararg pads: VirtualPad) = Input().also { i -> pads.forEach(i::plug); i.poll() }

    @Test fun zoneMorteRadiale() {
        val v = VirtualPad(); val i = input(v); val p = i.pad!!
        v.stick(Stick.LEFT, .05, .05); i.poll()
        assertEquals(0f, p.leftStick.x); assertEquals(0f, p.leftStick.amount, "dans la zone morte")
        v.stick(Stick.LEFT, .5, 0); i.poll()
        assertEquals((.5f - .1f) / (.95f - .1f), p.leftStick.x, 1e-6f, "le reste de la course part de zéro")
        assertEquals(.5f, p.leftStick.rawX)
        v.stick(Stick.LEFT, -.7, .7); i.poll()
        assertEquals(1f, p.leftStick.amount, 1e-6f, "au-delà de 0,95 : à fond")
        assertEquals(p.leftStick.x, -p.leftStick.y, 1e-6f, "la direction est gardée")
        p.deadZone = .6f; v.stick(Stick.LEFT, .5, .5); i.poll()
        assertEquals((sqrt(.5f) - .6f) / (.95f - .6f), p.leftStick.amount, 1e-5f)
        v.stick(Stick.RIGHT, 0, -1); i.poll()
        assertEquals(-1f, p.rightStick.y, 1e-6f); assertSame(p.rightStick, p.stick(Stick.RIGHT))
    }

    @Test fun gachettesAnalogiquesEtBoutons() {
        val v = VirtualPad(); val i = input(v); val p = i.pad!!
        v.trigger(Pad.R2, .5); i.poll()
        assertEquals((.5f - .03f) / .97f, p.trigger(Pad.R2), 1e-6f)
        assertTrue(p.isDown(Pad.R2), "le bouton passe le dixième de course")
        assertEquals(0f, p.trigger(Pad.L2))
        v.trigger(Pad.L2, .02); i.poll()
        assertEquals(0f, p.trigger(Pad.L2), "sous la zone morte"); assertFalse(p.isDown(Pad.L2))
        v.press(Pad.CROSS); i.poll()
        assertEquals(1f, p.trigger(Pad.CROSS), "un autre bouton : 0 ou 1")
    }

    @Test fun appuyeRelacheEtToutesLesManettes() {
        val a = VirtualPad("A"); val b = VirtualPad("B"); val i = input(a, b)
        b.press(Pad.CIRCLE); i.poll()
        assertTrue(i.pressed(Pad.CIRCLE)); assertTrue(i.isDown(Pad.CIRCLE)); assertTrue(i.pads[1].pressed(Pad.CIRCLE)); assertFalse(i.pads[0].isDown(Pad.CIRCLE))
        i.poll()
        assertFalse(i.pressed(Pad.CIRCLE), "une seule image"); assertTrue(i.isDown(Pad.CIRCLE))
        b.release(Pad.CIRCLE); i.poll()
        assertTrue(i.released(Pad.CIRCLE)); assertFalse(i.isDown(Pad.CIRCLE))
        assertEquals(Pad.SOUTH, Pad.CROSS); assertEquals("CROSS", Pad.buttonName(Pad.SOUTH)); assertNull(Pad.buttonName(Key.A))
        assertEquals("SPACE", Input.codeName(Key.SPACE)); assertEquals("OPTIONS", Input.codeName(Pad.START))
        assertFailsWith<IllegalArgumentException> { a.press(Key.A) }
    }

    @Test fun actionsReaffectablesPendantLaPartie() {
        var down = setOf<Int>()
        val v = VirtualPad(); val i = input(v)
        i.source = KeySource { it in down }
        val jump = i.action("jump", Key.SPACE, Pad.CROSS)
        assertSame(jump, i.action("jump")); assertContentEquals(intArrayOf(Key.SPACE, Pad.CROSS), jump.bindings)
        down = setOf(Key.SPACE); i.poll()
        assertTrue(i.pressed("jump")); assertTrue(jump.isDown); assertEquals(1f, jump.value)
        // La croix pendant que la barre d'espace est tenue : l'action ne recommence pas.
        v.press(Pad.CROSS); i.poll()
        assertFalse(jump.pressed); assertTrue(jump.isDown)
        down = emptySet(); v.release(Pad.CROSS); i.poll()
        assertTrue(jump.released)
        // Le joueur choisit une autre touche dans un menu : la première pressée.
        jump.rebind(); i.poll()
        v.press(Pad.TRIANGLE); i.poll()
        assertEquals(Pad.TRIANGLE, i.firstPressed())
        jump.rebind(i.firstPressed())
        i.poll(); assertTrue(jump.isDown); assertFalse(jump.pressed)
        v.release(Pad.TRIANGLE); i.poll(); v.press(Pad.TRIANGLE); i.poll()
        assertTrue(jump.pressed)
        v.release(Pad.TRIANGLE); jump.bind(Key.SPACE); down = setOf(Key.SPACE); i.poll()
        assertTrue(i.isDown("jump")); assertContentEquals(intArrayOf(Pad.TRIANGLE, Key.SPACE), jump.bindings)
        jump.unbind(Key.SPACE); i.poll(); assertFalse(jump.isDown)
        down = emptySet()
        // Une gâchette donne sa course.
        val accelerate = i.action("accelerate", Key.W, Pad.R2)
        v.trigger(Pad.R2, .4); i.poll()
        assertEquals(i.pad!!.trigger(Pad.R2), accelerate.value, 1e-6f)
        assertEquals(listOf("jump", "accelerate"), i.actions.map { it.name })
        assertFailsWith<IllegalArgumentException> { i.isDown("jmup") }
        assertEquals(-1, i.firstPressed().let { i.poll(); i.firstPressed() })
    }

    @Test fun deuxAxesAuClavierEtAuStick() {
        var down = setOf<Int>()
        val v = VirtualPad(); val i = input(v)
        i.source = KeySource { it in down }
        val move = i.axes("move", Key.A, Key.D, Key.S, Key.W, Stick.LEFT)
        down = setOf(Key.D, Key.W); i.poll()
        assertEquals(sqrt(.5f), move.x, 1e-6f); assertEquals(sqrt(.5f), move.y, 1e-6f, "en diagonale, dans le cercle")
        down = emptySet(); v.stick(Stick.LEFT, 0, 1); i.poll()
        assertEquals(0f, move.x); assertEquals(1f, move.y, 1e-6f)
        move.bind(Pad.DPAD_LEFT, Pad.DPAD_RIGHT, Pad.DPAD_DOWN, Pad.DPAD_UP)
        v.stick(Stick.LEFT, 0, 0); v.press(Pad.DPAD_LEFT); i.poll()
        assertEquals(-1f, move.x)
        val look = i.axes("look").apply { stick = Stick.RIGHT; mouse = .01f }
        i.mouseDelta = 10f to -20f; i.poll()
        assertEquals(.1f, look.x, 1e-6f); assertEquals(.2f, look.y, 1e-6f, "souris vers le haut : y positif")
        assertTrue(look.toString().contains("stick RIGHT"))
    }

    @Test fun emplacementsStablesEtBranchementAChaud() {
        val a = VirtualPad("A", id = "a"); val b = VirtualPad("B", id = "b"); val c = VirtualPad("C", id = "c")
        val events = ArrayList<String>()
        val i = Input()
        i.onPadConnected { events.add("+${it.name}${it.slot}") }; i.onPadDisconnected { events.add("-${it.name}${it.slot}") }
        i.plug(a); i.plug(b); i.poll()
        assertEquals(listOf("+A0", "+B1"), events); assertEquals(listOf(0, 1), i.pads.map { it.slot })
        val padA = i.pads[0]
        a.press(Pad.CROSS); i.poll(); assertTrue(padA.isDown(Pad.CROSS))
        i.unplug(a); i.poll()
        assertEquals("-A0", events.last()); assertFalse(padA.connected); assertFalse(padA.isDown(Pad.CROSS), "débranchée : rien n'est tenu")
        assertTrue(padA.released(Pad.CROSS), "le relâchement se voit une fois")
        assertEquals(listOf("B"), i.pads.map { it.name }); assertSame(padA, i.pad(0))
        // Une autre manette ne prend pas la place réservée tant qu'il reste une place libre ; la même retrouve la sienne.
        i.plug(c); i.poll()
        assertEquals("+C2", events.last())
        i.plug(a); i.poll()
        assertEquals("+A0", events.last()); assertSame(padA, i.pads[0]); assertTrue(padA.connected)
        assertEquals(listOf(0, 1, 2), i.pads.map { it.slot })
        // Une nouvelle scène garde les manettes et leurs places, et les annonce.
        val next = Input(); val seen = ArrayList<Int>()
        next.onPadConnected { seen.add(it.slot) }
        next.adoptPads(i); next.poll()
        assertEquals(listOf(0, 1, 2), seen); assertSame(padA, next.pad)
    }

    @Test fun lumieresDeLEmplacementALaConnexion() {
        val a = VirtualPad(kind = PadKind.DUALSENSE); val b = VirtualPad(kind = PadKind.DUALSENSE)
        val i = input(a, b)
        assertEquals(Pad.slotColor(0), a.lightbar); assertEquals(0b00100, a.playerLeds)
        assertEquals(Pad.slotColor(1), b.lightbar); assertEquals(0b01010, b.playerLeds)
        val p = i.pads[1]
        p.rumble(.3, .6, .5); p.lightbar = Color.RED; p.muteLed = true; p.triggerEffect(Pad.R2, TriggerEffect.weapon())
        assertEquals(.3f, b.rumbleLow); assertEquals(.6f, b.rumbleHigh); assertEquals(.5f, b.rumbleSeconds); assertEquals(1, b.rumbles)
        assertEquals(Color.RED, b.lightbar); assertTrue(b.muteLed); assertEquals(TriggerEffect.Kind.WEAPON, b.triggerEffect(Pad.R2).kind)
        assertFailsWith<IllegalArgumentException> { p.triggerEffect(Pad.CROSS, TriggerEffect.OFF) }
        val none = Input().apply { padLights = false; plug(VirtualPad().also { assertNull(it.lightbar) }) }
        none.poll(); assertNull((none.hub.connected[0].device as VirtualPad).lightbar)
    }

    @Test fun mouvementTactileBatterie() {
        val v = VirtualPad(); val i = input(v); val p = i.pad!!
        v.motion(Vec3(.1f, .2f, .3f), Vec3(0f, 0f, 9.8f)); v.touch(1, .25, .75, id = 9); v.battery(.4, BatteryState.CHARGING); i.poll()
        assertEquals(.2f, p.gyro.y); assertEquals(9.8f, p.accel.z)
        assertFalse(p.touch[0].active); assertTrue(p.touch[1].active); assertEquals(9, p.touch[1].id); assertEquals(.75f, p.touch[1].y)
        assertEquals(.4f, p.battery); assertEquals(BatteryState.CHARGING, p.batteryState)
        v.lift(1); i.poll(); assertFalse(p.touch[1].active)
    }

    private fun scene(): Pair<Scene, VirtualPad> {
        val v = VirtualPad()
        val s = Scene().apply { ground(); player(at = vec(0, 0, 0)); input.plug(v) }
        repeat(30) { s.step(dt) }                     // il se pose
        return s to v
    }

    private fun speed(s: Scene) = s.player!!.character.walkVelocity.let { sqrt(it.x * it.x + it.z * it.z) }

    @Test fun peuInclineIlMarcheAFondIlCourt() {
        val (s, v) = scene(); val p = s.player!!
        v.stick(Stick.LEFT, 0, .3); repeat(60) { s.step(dt) }
        val a = (.3f - .1f) / (.95f - .1f)
        assertEquals(p.walkSpeed * a / .5f, speed(s), .05f, "un peu incliné : moins vite que la marche")
        v.stick(Stick.LEFT, 0, .6); repeat(60) { s.step(dt) }
        assertTrue(speed(s) > p.walkSpeed && speed(s) < p.runSpeed, "entre la marche et la course : ${speed(s)}")
        v.stick(Stick.LEFT, 0, 1); repeat(60) { s.step(dt) }
        assertEquals(p.runSpeed, speed(s), .05f, "à fond : la course")
        // Vers l'avant de la caméra (qui regarde vers −z au départ).
        assertTrue(p.character.walkVelocity.z < -4f)
        v.stick(Stick.LEFT, 0, 0); repeat(30) { s.step(dt) }
        assertTrue(speed(s) < .05f)
        // Maj tenue : il marche même à fond.
        s.input.source = KeySource { it == Key.LEFT_SHIFT }
        v.stick(Stick.LEFT, 1, 0); repeat(60) { s.step(dt) }
        assertEquals(p.walkSpeed, speed(s), .05f)
    }

    @Test fun ilSauteALaCroix() {
        val (s, v) = scene(); val p = s.player!!
        assertTrue(p.onGround)
        v.press(Pad.CROSS); s.step(dt); v.release(Pad.CROSS)
        var top = p.position.y
        repeat(40) { s.step(dt); top = max(top, p.position.y) }
        assertTrue(top > .8f, "saut à ${top} m")
        // Une autre manette que la sienne ne le fait pas sauter.
        repeat(90) { s.step(dt) }
        p.keys.pad = 1
        v.press(Pad.CROSS); s.step(dt); v.release(Pad.CROSS)
        top = p.position.y; repeat(40) { s.step(dt); top = max(top, p.position.y) }
        assertTrue(top < .1f, "la manette 1 n'existe pas : pas de saut ($top)")
    }

    @Test fun laCameraTourneAuStickDroit() {
        val (s, v) = scene(); val c = s.camera
        val yaw0 = c.yaw; val el0 = c.elevation
        v.stick(Stick.RIGHT, 1, 0); repeat(60) { s.step(dt) }
        assertEquals(yaw0 - c.stickSpeed, c.yaw, 1f, "une seconde à fond : ${c.stickSpeed} degrés vers la droite")
        v.stick(Stick.RIGHT, 0, 1); repeat(30) { s.step(dt) }
        assertTrue(c.elevation < el0, "stick vers le haut : la caméra descend pour regarder plus haut")
        c.invertY = true; val e = c.elevation
        repeat(10) { s.step(dt) }
        assertTrue(c.elevation > e, "inversé")
        c.invertY = false; c.mode = CameraMode.FIRST_PERSON; c.pitch = 0f
        repeat(30) { s.step(dt) }
        assertEquals(c.stickSpeed * .66f * .5f, c.pitch, 1f, "première personne : lever la tête")
        c.lookStick = null; val y = c.yaw
        v.stick(Stick.RIGHT, 1, 0); repeat(10) { s.step(dt) }
        assertEquals(y, c.yaw, "sans stick de visée")
    }

    @Test fun viseeAuGyroscopeEnPremierePersonne() {
        val (s, v) = scene(); val c = s.camera
        c.mode = CameraMode.FIRST_PERSON
        v.motion(Vec3(0f, 1f, 0f), Vec3(0f, 9.80665f, 0f)); repeat(60) { s.step(dt) }
        assertEquals(0f, c.yaw, 1e-3f, "sans gyroAim, rien")
        c.gyroAim = true; repeat(60) { s.step(dt) }
        assertEquals(Math.toDegrees(1.0).toFloat(), c.yaw, .5f, "un radian par seconde pendant une seconde")
        // Tenue inclinée de 40° vers soi : tourner autour de la verticale tourne toujours la vue.
        val t = Math.toRadians(40.0).toFloat()
        val up = Vec3(0f, cos(t), sin(t))
        c.yaw = 0f
        v.motion(up, up * 9.80665f); repeat(60) { s.step(dt) }
        assertEquals(Math.toDegrees(1.0).toFloat(), c.yaw, .5f)
        // Lever l'avant de la manette : lever la tête.
        c.pitch = 0f; v.motion(Vec3(.5f, 0f, 0f), Vec3(0f, 9.80665f, 0f)); repeat(30) { s.step(dt) }
        assertEquals(Math.toDegrees(.25).toFloat(), c.pitch, .3f)
        c.mode = CameraMode.THIRD_PERSON; val y = c.yaw
        v.motion(Vec3(0f, 1f, 0f)); repeat(10) { s.step(dt) }
        assertEquals(y, c.yaw, 1e-3f, "troisième personne : le gyroscope ne vise pas")
    }

    @Test fun leClavierMarcheCommeAvant() {
        val (s, _) = scene()
        s.input.source = KeySource { it == Key.W }
        repeat(60) { s.step(dt) }
        assertEquals(s.player!!.runSpeed, speed(s), .05f)
        assertContentEquals(intArrayOf(Key.SPACE, Pad.CROSS), s.player!!.keys.jump)
    }

    @Test fun lireLesManettesNAllouePas() {
        val pads = List(4) { VirtualPad(kind = PadKind.DUALSENSE) }
        val i = input(*pads.toTypedArray())
        val jump = i.action("jump", Key.SPACE, Pad.CROSS)
        val fire = i.action("fire", Pad.R2)
        val move = i.axes("move", Key.A, Key.D, Key.S, Key.W, Stick.LEFT)
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        var sink = 0f
        // Des nombres déjà en boîte : les paramètres Number des manettes virtuelles alloueraient, pas la lecture.
        val sines = Array<Number>(64) { sin(it * .1f) }; val cosines = Array<Number>(64) { cos(it * .1f) }; val pulls = Array<Number>(10) { it / 10f }
        fun frame(k: Int) {
            pads[k % 4].stick(Stick.LEFT, sines[k % 64], cosines[k % 64]); pads[(k + 1) % 4].set(Pad.CROSS, k % 3 == 0); pads[2].trigger(Pad.R2, pulls[k % 10])
            i.poll()
            if (jump.pressed) sink += 1f
            sink += fire.value + move.x + move.y + i.pads[k % 4].leftStick.x + i.pad!!.trigger(Pad.R2)
            if (i.isDown(Pad.CROSS)) sink += 1f
        }
        repeat(20_000) { frame(it) }
        val before = bean.getThreadAllocatedBytes(id)
        repeat(10_000) { frame(it) }
        val bytes = bean.getThreadAllocatedBytes(id) - before
        assertTrue(bytes < 1024, "$bytes octets alloués en 10 000 images ($sink)")
    }
}
