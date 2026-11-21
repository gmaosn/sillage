package sillage.input

import sillage.*
import sillage.platform.Ev
import sillage.platform.EvdevPad
import sillage.platform.SystemPads
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * L'aller-retour par le vrai noyau : des manettes virtuelles faites par /dev/uinput (une générique, une
 * qui se présente comme une DualSense lue par evdev, une branchée et débranchée en cours de route),
 * trouvées par [SystemPads], chaque bouton, chaque axe, le retour de force. Les manettes sont prises
 * pour ce lecteur seul (EVIOCGRAB) : Steam, un jeu sous Wine ou le navigateur, qui les ouvrent aussi,
 * n'en reçoivent rien. Sautés quand /dev/uinput n'est pas accessible, ou quand Steam ou Wine tournent
 * (SILLAGE_UINPUT=1 pour les faire quand même).
 */
class UinputTest {
    private fun waitFor(ms: Long = 3000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) fail("attendu en vain : $what"); Thread.sleep(2) }
    }

    private val keys = intArrayOf(Ev.BTN_SOUTH, Ev.BTN_EAST, 0x133, 0x134, Ev.BTN_TL, Ev.BTN_TR, Ev.BTN_TL2, Ev.BTN_TR2, Ev.BTN_SELECT, Ev.BTN_START,
        Ev.BTN_MODE, Ev.BTN_THUMBL, Ev.BTN_THUMBR)

    @Test fun allerRetourParLeNoyau() {
        if (!Uinput.allowed()) return
        val tag = "Sillage essai " + System.nanoTime() % 100_000
        val names = setOf("$tag manette", "$tag DualSense", "$tag seconde")
        val logs = CopyOnWriteArrayList<String>()
        val made = ArrayList<Uinput>()
        var src: SystemPads? = null
        try {
            val big = 32767; val small = -32768
            val gen = Uinput("$tag manette", 0x1234, 0x5678, Uinput.BUS_USB, keys, listOf(
                Uinput.Axis(Ev.ABS_X, small, big), Uinput.Axis(Ev.ABS_Y, small, big), Uinput.Axis(Ev.ABS_RX, small, big), Uinput.Axis(Ev.ABS_RY, small, big),
                Uinput.Axis(Ev.ABS_Z, 0, 1023), Uinput.Axis(Ev.ABS_RZ, 0, 1023), Uinput.Axis(Ev.ABS_HAT0X, -1, 1), Uinput.Axis(Ev.ABS_HAT0Y, -1, 1)), ff = true).also(made::add)
            val s = SystemPads(log = { logs.add(it) }, accept = { name, _, _ -> name in names }, grab = true); src = s
            val input = Input().apply { padSource = s }
            val events = ArrayList<String>()
            input.onPadConnected { events.add("+${it.slot}") }; input.onPadDisconnected { events.add("-${it.slot}") }
            waitFor(what = "la manette virtuelle") { s.devices.size == 1 }
            input.poll()
            val pad = input.pad!!
            val reader = s.devices[0] as EvdevPad
            if (!reader.grabbed) { println("SKIP uinput : la manette n'a pas pu être prise pour ce seul lecteur"); return }
            assertEquals("$tag manette", pad.name); assertEquals(PadKind.GENERIC, pad.kind); assertEquals(PadConnection.USB, pad.connection)
            assertEquals(gen.node, pad.path); assertTrue(pad.canRumble); assertFalse(pad.hasLightbar)
            assertEquals(listOf("+0"), events)

            // Chaque bouton, appuyé puis relâché aussitôt vu.
            val expected = listOf(Pad.CROSS, Pad.CIRCLE, Pad.SQUARE, Pad.TRIANGLE, Pad.L1, Pad.R1, Pad.L2, Pad.R2, Pad.CREATE, Pad.OPTIONS, Pad.PS, Pad.L3, Pad.R3)
            for ((code, button) in keys.zip(expected)) {
                gen.key(code, true)
                waitFor(what = "${Pad.buttonName(button)} appuyé") { input.poll(); pad.isDown(button) }
                assertEquals(Pad.buttonName(button), pad.buttonsDown())
                gen.key(code, false)
                waitFor(what = "${Pad.buttonName(button)} relâché") { input.poll(); !pad.isDown(button) }
            }
            // Chaque axe aux deux bouts, puis au repos.
            fun axis(code: Int, value: Int, what: String, ok: () -> Boolean) { gen.abs(code, value); waitFor(what = what) { input.poll(); ok() } }
            axis(Ev.ABS_X, big, "gauche à droite") { pad.leftStick.rawX > .999f }; axis(Ev.ABS_X, small, "gauche à gauche") { pad.leftStick.rawX == -1f }
            axis(Ev.ABS_X, 0, "gauche au centre") { pad.leftStick.amount == 0f }
            axis(Ev.ABS_Y, small, "gauche en haut") { pad.leftStick.rawY == 1f }; axis(Ev.ABS_Y, 0, "gauche au repos") { pad.leftStick.amount == 0f }
            axis(Ev.ABS_RX, big, "droit à droite") { pad.rightStick.rawX > .999f }; axis(Ev.ABS_RX, 0, "droit au repos") { pad.rightStick.amount == 0f }
            axis(Ev.ABS_RY, big, "droit en bas") { pad.rightStick.rawY < -.999f }; axis(Ev.ABS_RY, 0, "droit au repos") { pad.rightStick.amount == 0f }
            axis(Ev.ABS_Z, 1023, "L2 à fond") { pad.trigger(Pad.L2) == 1f }; axis(Ev.ABS_Z, 512, "L2 à moitié") { pad.trigger(Pad.L2) in .45f..0.55f }
            axis(Ev.ABS_Z, 0, "L2 relâchée") { pad.trigger(Pad.L2) == 0f }
            axis(Ev.ABS_RZ, 1023, "R2 à fond") { pad.trigger(Pad.R2) == 1f }; axis(Ev.ABS_RZ, 0, "R2 relâchée") { pad.trigger(Pad.R2) == 0f }
            axis(Ev.ABS_HAT0X, -1, "croix à gauche") { pad.isDown(Pad.DPAD_LEFT) && !pad.isDown(Pad.DPAD_RIGHT) }
            axis(Ev.ABS_HAT0X, 1, "croix à droite") { pad.isDown(Pad.DPAD_RIGHT) && !pad.isDown(Pad.DPAD_LEFT) }
            axis(Ev.ABS_HAT0X, 0, "croix au repos") { !pad.isDown(Pad.DPAD_RIGHT) }
            axis(Ev.ABS_HAT0Y, -1, "croix en haut") { pad.isDown(Pad.DPAD_UP) }; axis(Ev.ABS_HAT0Y, 1, "croix en bas") { pad.isDown(Pad.DPAD_DOWN) && !pad.isDown(Pad.DPAD_UP) }
            axis(Ev.ABS_HAT0Y, 0, "croix au repos") { pad.buttonsDown().isEmpty() }

            // Vibration : l'effet téléversé (fort, faible, durée), joué, puis arrêté quand le temps est écoulé.
            pad.rumble(.5, .25, .3)
            waitFor(what = "l'effet joué") { gen.uploads.isNotEmpty() && gen.plays.any { it[1] == 1 } }
            val (strong, weak, length, type) = gen.uploads[0].toList()
            assertEquals(Ev.FF_RUMBLE, type); assertEquals(32767, strong); assertEquals(16383, weak); assertTrue(length in 250..300, "durée $length ms")
            waitFor(1500, "l'arrêt") { gen.plays.any { it[1] == 0 } }

            // Une seconde manette branchée pendant la lecture, puis débranchée.
            // Les mêmes numéros que la première : Steam, qui note chaque manette qu'il voit, n'en garde pas une de plus.
            val second = Uinput("$tag seconde", 0x1234, 0x5678, Uinput.BUS_USB, intArrayOf(Ev.BTN_SOUTH, Ev.BTN_EAST),
                listOf(Uinput.Axis(Ev.ABS_X, -1, 1), Uinput.Axis(Ev.ABS_Y, -1, 1)), ff = false).also(made::add)
            waitFor(what = "la seconde") { s.devices.size == 2 }
            input.poll()
            assertEquals(listOf("+0", "+1"), events); assertEquals(1, input.pads[1].slot); assertFalse(input.pads[1].canRumble)
            second.key(Ev.BTN_SOUTH, true)
            waitFor(what = "la croix de la seconde") { input.poll(); input.pads[1].isDown(Pad.CROSS) }
            assertFalse(pad.isDown(Pad.CROSS)); assertTrue(input.isDown(Pad.CROSS))
            second.key(Ev.BTN_SOUTH, false)
            waitFor(what = "relâchée") { input.poll(); !input.isDown(Pad.CROSS) }
            second.close(); made.remove(second)
            waitFor(what = "le départ de la seconde") { s.devices.size == 1 }
            input.poll()
            assertEquals(listOf("+0", "+1", "-1"), events); assertTrue(pad.connected); assertSame(pad, input.pad)

            // Une « DualSense » lue par evdev (pas de hidraw pour elle) : ses plages, son nommage par position.
            val ds = Uinput("$tag DualSense", 0x054C, 0x0CE6, Uinput.BUS_USB, keys, listOf(
                Uinput.Axis(Ev.ABS_X, 0, 255, 128), Uinput.Axis(Ev.ABS_Y, 0, 255, 128), Uinput.Axis(Ev.ABS_RX, 0, 255, 128), Uinput.Axis(Ev.ABS_RY, 0, 255, 128),
                Uinput.Axis(Ev.ABS_Z, 0, 255), Uinput.Axis(Ev.ABS_RZ, 0, 255), Uinput.Axis(Ev.ABS_HAT0X, -1, 1), Uinput.Axis(Ev.ABS_HAT0Y, -1, 1)), ff = true).also(made::add)
            waitFor(what = "la DualSense par evdev") { s.devices.size == 2 }
            input.poll()
            val dsPad = input.pads.first { it.path == ds.node }
            assertEquals(PadKind.DUALSENSE, dsPad.kind); assertTrue(dsPad.canRumble); assertFalse(dsPad.hasLightbar || dsPad.hasMotion)
            assertEquals(2, dsPad.slot, "l'emplacement 1 reste gardé pour la seconde, partie")
            assertTrue(dsPad.leftStick.amount == 0f, "au repos à 128")
            ds.key(0x133, true); waitFor(what = "triangle") { input.poll(); dsPad.isDown(Pad.TRIANGLE) }; ds.key(0x133, false)
            ds.key(0x134, true); waitFor(what = "carré") { input.poll(); dsPad.isDown(Pad.SQUARE) && !dsPad.isDown(Pad.TRIANGLE) }; ds.key(0x134, false)
            ds.abs(Ev.ABS_X, 255); waitFor(what = "à droite") { input.poll(); dsPad.leftStick.rawX == 1f }; ds.abs(Ev.ABS_X, 128)
            ds.abs(Ev.ABS_RZ, 255); waitFor(what = "R2") { input.poll(); dsPad.trigger(Pad.R2) == 1f }; ds.abs(Ev.ABS_RZ, 0)
            waitFor(what = "au repos") { input.poll(); dsPad.buttonsDown().isEmpty() && dsPad.trigger(Pad.R2) == 0f && dsPad.leftStick.amount == 0f }
            assertTrue(logs.isEmpty(), "aucun message : $logs")
        } finally {
            src?.close()
            for (u in made) runCatching { u.close() }
        }
    }
}
