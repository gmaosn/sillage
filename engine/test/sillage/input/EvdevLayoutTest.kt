package sillage.input

import sillage.Pad
import sillage.platform.Ev
import sillage.platform.EvdevCaps
import sillage.platform.EvdevLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Ce que les capacités d'un périphérique evdev disent de sa disposition, et leur lecture dans sysfs. */
class EvdevLayoutTest {
    private fun keys(vararg codes: Int) = LongArray(12).also { k -> for (c in codes) k[c / 64] = k[c / 64] or (1L shl (c % 64)) }
    private fun abs(vararg codes: Int) = codes.fold(0L) { a, c -> a or (1L shl c) }
    private fun button(l: EvdevLayout, code: Int) = l.button[code].let { if (it < 0) null else Pad.BUTTON_NAMES[it] }

    private val pad = intArrayOf(Ev.BTN_SOUTH, Ev.BTN_EAST, Ev.BTN_NORTH, Ev.BTN_WEST, Ev.BTN_TL, Ev.BTN_TR, Ev.BTN_SELECT, Ev.BTN_START, Ev.BTN_MODE, Ev.BTN_THUMBL, Ev.BTN_THUMBR)

    @Test fun uneManetteXboxParXpad() {
        val l = EvdevLayout(keys(*pad, Ev.KEY_RECORD), abs(Ev.ABS_X, Ev.ABS_Y, Ev.ABS_Z, Ev.ABS_RX, Ev.ABS_RY, Ev.ABS_RZ, Ev.ABS_HAT0X, Ev.ABS_HAT0Y), 0x045E, 0x0B12)
        assertTrue(l.isGamepad)
        assertEquals("CROSS", button(l, Ev.BTN_SOUTH)); assertEquals("CIRCLE", button(l, Ev.BTN_EAST))
        assertEquals("SQUARE", button(l, 0x133), "BTN_X de xpad : le bouton de gauche"); assertEquals("TRIANGLE", button(l, 0x134))
        assertEquals("L1", button(l, Ev.BTN_TL)); assertEquals("CREATE", button(l, Ev.BTN_SELECT)); assertEquals("OPTIONS", button(l, Ev.BTN_START))
        assertEquals("PS", button(l, Ev.BTN_MODE)); assertEquals("L3", button(l, Ev.BTN_THUMBL)); assertEquals("MUTE", button(l, Ev.KEY_RECORD))
        assertEquals(EvdevLayout.RX, l.role[Ev.ABS_RX]); assertEquals(EvdevLayout.L2, l.role[Ev.ABS_Z]); assertEquals(EvdevLayout.R2, l.role[Ev.ABS_RZ])
        assertEquals(EvdevLayout.HAT_X, l.role[Ev.ABS_HAT0X]); assertFalse(l.digitalL2)
    }

    @Test fun lesPiloteDeSonyEtNintendoNommentParPosition() {
        for (vendor in listOf(0x054C, 0x057E)) {
            val l = EvdevLayout(keys(*pad, Ev.BTN_TL2, Ev.BTN_TR2), abs(Ev.ABS_X, Ev.ABS_Y, Ev.ABS_RX, Ev.ABS_RY, Ev.ABS_Z, Ev.ABS_RZ), vendor, 0x0CE6)
            assertEquals("TRIANGLE", button(l, Ev.BTN_NORTH)); assertEquals("SQUARE", button(l, Ev.BTN_WEST))
            assertTrue(l.digitalL2 && l.digitalR2)
        }
    }

    @Test fun uneManetteHidSansRxRy() {
        val l = EvdevLayout(keys(Ev.BTN_SOUTH, Ev.BTN_EAST, Ev.BTN_DPAD_UP, Ev.BTN_DPAD_DOWN, Ev.BTN_DPAD_LEFT, Ev.BTN_DPAD_RIGHT),
            abs(Ev.ABS_X, Ev.ABS_Y, Ev.ABS_Z, Ev.ABS_RZ, Ev.ABS_GAS, Ev.ABS_BRAKE, Ev.ABS_HAT0X, Ev.ABS_HAT0Y), 0x2DC8, 0x6001)
        assertEquals(EvdevLayout.RX, l.role[Ev.ABS_Z]); assertEquals(EvdevLayout.RY, l.role[Ev.ABS_RZ])
        assertEquals(EvdevLayout.L2, l.role[Ev.ABS_BRAKE]); assertEquals(EvdevLayout.R2, l.role[Ev.ABS_GAS])
        assertEquals(-1, l.role[Ev.ABS_HAT0X], "la croix a ses boutons : le chapeau ne sert pas")
        assertEquals("UP", button(l, Ev.BTN_DPAD_UP)); assertEquals("RIGHT", button(l, Ev.BTN_DPAD_RIGHT))
    }

    @Test fun clavierSourisPaveTactileNeSontPasDesManettes() {
        assertFalse(EvdevLayout(keys(30, 31, 32), 0L, 1, 1).isGamepad)
        assertFalse(EvdevLayout(keys(0x110, 0x111), 0L, 1, 1).isGamepad)
        assertFalse(EvdevLayout(keys(0x110, 0x14a), abs(Ev.ABS_X, Ev.ABS_Y), 1, 1).isGamepad, "un pavé tactile")
        assertFalse(EvdevLayout(keys(Ev.BTN_SOUTH), 0L, 1, 1).isGamepad, "des boutons sans sticks")
    }

    @Test fun bitsAuFormatDeSysfs() {
        val b = EvdevCaps.bits("30000 0 0 0 0")
        assertEquals(5, b.size); assertEquals(0x30000L, b[4]); assertEquals(0L, b[0])
        val k = EvdevCaps.bits("ffff000000000000 0")
        assertEquals(java.lang.Long.parseUnsignedLong("ffff000000000000", 16), k[1])
        assertEquals(1, EvdevCaps.bits("0").size)
    }

    @Test fun lectureDansUnSysfsImite() {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("project.yaml"))) dir = dir.parent
        val root = dir.resolve("build/tmp/evdev-" + System.nanoTime())
        try {
            fun device(at: Path, name: String, vendor: String, bus: String) {
                Files.createDirectories(at.resolve("capabilities")); Files.createDirectories(at.resolve("id"))
                Files.writeString(at.resolve("name"), "$name\n"); Files.writeString(at.resolve("uniq"), "AA:BB:CC:DD:EE:FF\n"); Files.writeString(at.resolve("phys"), "usb-1/input0\n")
                Files.writeString(at.resolve("id/bustype"), "$bus\n"); Files.writeString(at.resolve("id/vendor"), "$vendor\n"); Files.writeString(at.resolve("id/product"), "0ce6\n")
                // BTN_SOUTH… (mot 4 : codes 256 à 319), ABS_X, Y, Z, RX, RY, RZ, chapeau ; FF_RUMBLE (mot 1).
                Files.writeString(at.resolve("capabilities/key"), "7fdb000000000000 0 0 0 0\n")
                Files.writeString(at.resolve("capabilities/abs"), "3003f\n")
                Files.writeString(at.resolve("capabilities/ff"), "107030000 0\n")
            }
            val hid = root.resolve("sys/devices/pci/0005:054C:0CE6.0003")
            val input = hid.resolve("input/input40"); device(input, "DualSense Wireless Controller", "054c", "0005")
            val virt = root.resolve("sys/devices/virtual/input/input41"); device(virt, "Sillage", "1234", "0006")
            Files.createDirectories(root.resolve("sys/class/input/event20")); Files.createSymbolicLink(root.resolve("sys/class/input/event20/device"), input)
            Files.createDirectories(root.resolve("sys/class/input/event21")); Files.createSymbolicLink(root.resolve("sys/class/input/event21/device"), virt)
            val c = EvdevCaps.read(root.resolve("sys/class/input/event20/device"))!!
            assertEquals("DualSense Wireless Controller", c.name); assertEquals(5, c.bus); assertEquals(0x054C, c.vendor); assertEquals(0x0CE6, c.product)
            assertEquals("AA:BB:CC:DD:EE:FF", c.uniq); assertTrue(c.ffRumble); assertTrue(c.layout.isGamepad)
            assertEquals(hid.toRealPath(), c.hidDevice, "le périphérique HID au-dessus du nœud")
            assertEquals("TRIANGLE", button(c.layout, Ev.BTN_NORTH))
            val v = EvdevCaps.read(root.resolve("sys/class/input/event21/device"))!!
            assertNull(v.hidDevice, "un périphérique virtuel n'a pas de HID")
            assertNull(EvdevCaps.read(root.resolve("sys/class/input/event99/device")))
        } finally { root.toFile().deleteRecursively() }
    }
}
