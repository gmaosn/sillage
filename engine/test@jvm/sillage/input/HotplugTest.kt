package sillage.input

import sillage.*
import sillage.platform.Crc32
import sillage.platform.HidrawDualSense
import sillage.platform.SystemPads
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.test.*

/**
 * Le suivi des manettes branchées et débranchées, sur un arbre sysfs et /dev imité (dans build/tmp) et
 * des DualSense imitées ([FakeIo]) : l'inotify, la poignée de main, la lecture, les sorties, les
 * emplacements, les messages, sans allocation dans le fil de lecture.
 */
class HotplugTest {
    private fun root(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("project.yaml"))) dir = dir.parent
        return dir
    }

    private fun <T> withTree(block: (FakeIo) -> T): T {
        val dir = root().resolve("build/tmp/manettes-" + System.nanoTime())
        try { return block(FakeIo(dir)) } finally { dir.toFile().deleteRecursively() }
    }

    private fun waitFor(ms: Long = 3000, what: String = "", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) fail("attendu en vain : $what"); Thread.sleep(3) }
    }

    private fun le16(b: ByteArray, at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
    private fun le32(b: ByteArray, at: Int, v: Int) { for (k in 0 until 4) b[at + k] = (v shr (8 * k)).toByte() }

    /** Les rapports de fonction d'une DualSense : calibrage, micrologiciel, adresse ; avec leur CRC par Bluetooth. */
    private fun FakeIo.Node.features(bluetooth: Boolean, mac: List<Int>, badCrc: Boolean = false) {
        val cal = ByteArray(41); cal[0] = 5
        le16(cal, 7, 8000); le16(cal, 9, -8000); le16(cal, 11, 8000); le16(cal, 13, -8000); le16(cal, 15, 8000); le16(cal, 17, -8000)
        le16(cal, 19, 1000); le16(cal, 21, 1000)                                  // 2000 °/s pour 16 000 unités
        for (k in 0 until 3) { le16(cal, 23 + 4 * k, 8192); le16(cal, 25 + 4 * k, -8192) }
        if (bluetooth) le32(cal, 37, Crc32.seeded(0xA3, cal, 0, 37) + if (badCrc) 1 else 0)
        features[5] = cal
        val fw = ByteArray(64); fw[0] = 0x20; le16(fw, 44, 0x0220); features[0x20] = fw
        val pairing = ByteArray(20); pairing[0] = 9; for (i in 0 until 6) pairing[1 + i] = mac[5 - i].toByte(); features[9] = pairing
    }

    private fun report(bluetooth: Boolean, buttons0: Int = 0x08, lx: Int = 128, gyroY: Int = 0, stamp: Int = 0): ByteArray {
        val b = ByteArray(if (bluetooth) 78 else 64)
        val at = if (bluetooth) 2 else 1
        b[0] = if (bluetooth) 0x31 else 1
        b[at] = lx.toByte(); b[at + 1] = 128.toByte(); b[at + 2] = 128.toByte(); b[at + 3] = 128.toByte()
        b[at + 7] = buttons0.toByte()
        le16(b, at + 17, gyroY); le16(b, at + 23, 8192)
        le32(b, at + 27, stamp)
        b[at + 32] = 0x80.toByte(); b[at + 36] = 0x80.toByte()
        b[at + 52] = 0x08
        if (bluetooth) le32(b, 74, Crc32.seeded(0xA1, b, 0, 74))
        return b
    }

    private fun crcOk(b: ByteArray) = b.size != 78 || Crc32.seeded(0xA2, b, 0, 74) == ((b[74].toInt() and 0xFF) or ((b[75].toInt() and 0xFF) shl 8) or
        ((b[76].toInt() and 0xFF) shl 16) or ((b[77].toInt() and 0xFF) shl 24))

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private val mac = listOf(0xAA, 0xBB, 0xCC, 0x11, 0x22, 0x33)

    @Test fun uneDualSenseBluetoothBrancheeDebrancheeRebranchee() = withTree { io ->
        val logs = CopyOnWriteArrayList<String>()
        val src = SystemPads(io.sys, io.dev, io, log = { logs.add(it) })
        try {
            waitFor(what = "premier regard") { src.scans >= 1 }
            val input = Input().apply { padSource = src }
            val events = ArrayList<String>()
            input.onPadConnected { events.add("+${it.slot}") }; input.onPadDisconnected { events.add("-${it.slot}") }
            input.poll(); assertNull(input.pad)
            val node = io.plugDualSense("hidraw7", 5, "aa:bb:cc:11:22:33") { features(true, mac) }
            waitFor(what = "la manette branchée") { src.devices.size == 1 }
            input.poll()
            val pad = input.pad!!
            assertEquals(listOf("+0"), events)
            assertEquals(PadKind.DUALSENSE, pad.kind); assertEquals(PadConnection.BLUETOOTH, pad.connection); assertEquals(node.path, pad.path)
            assertEquals("aa:bb:cc:11:22:33", pad.device.deviceId, "l'adresse lue dans le rapport 0x09")
            val hw = src.devices[0] as HidrawDualSense
            assertTrue(hw.calibration.fromDevice); assertTrue(hw.vibrationV2); assertEquals(0x0220, hw.firmware)
            assertTrue(pad.hasLightbar && pad.hasMotion && pad.hasAdaptiveTriggers && pad.hasTouchpad && pad.hasBattery && pad.canRumble)
            // Les lumières de l'emplacement 1, envoyées à la connexion.
            waitFor(what = "lumières de l'emplacement") { node.written.any { u(it, 3 + 1) and 0x14 == 0x14 } }
            val first = node.written.first { u(it, 3 + 1) and 0x14 == 0x14 }
            assertEquals(0b00100, u(first, 3 + 43)); assertEquals(0x31, u(first, 0)); assertEquals(0x10, u(first, 2))
            // Un rapport : la croix, le stick à droite.
            node.reports.add(report(true, buttons0 = 0x08 or 0x20, lx = 255))
            waitFor(what = "la croix") { input.poll(); pad.isDown(Pad.CROSS) }
            assertEquals(1f, pad.leftStick.rawX); assertEquals(.85f, pad.battery, 1e-6f)
            assertEquals(9.80665f, pad.accel.y, 1e-4f)
            // Un rapport abîmé est ignoré.
            node.reports.add(report(true, buttons0 = 0x08).also { it[20] = 1 })
            waitFor(what = "le rapport abîmé compté") { hw.badReports == 1L }
            input.poll(); assertTrue(pad.isDown(Pad.CROSS))
            // Couleur, vibration de 0,1 s : envoyées, puis les moteurs s'arrêtent seuls.
            pad.lightbar = Color.hex(0x00FF00)
            waitFor(what = "la couleur") { node.written.any { u(it, 3 + 1) and 0x04 != 0 && u(it, 3 + 44) == 0 && u(it, 3 + 45) == 255 && u(it, 3 + 46) == 0 } }
            val before = node.written.size
            pad.rumble(1, .5, .1)
            waitFor(what = "les moteurs") { node.written.drop(before).any { u(it, 3) and 0x02 != 0 && u(it, 3 + 2) == 128 && u(it, 3 + 3) == 255 } }
            waitFor(what = "l'arrêt des moteurs") { node.written.last().let { u(it, 3) and 0x02 != 0 && u(it, 3 + 2) == 0 && u(it, 3 + 3) == 0 } }
            assertTrue(node.written.all { crcOk(it) }, "CRC de chaque rapport de sortie")
            val seqs = node.written.map { u(it, 1) shr 4 }
            assertEquals(seqs.indices.map { it % 16 }, seqs, "numéros d'ordre qui se suivent")
            // Débranchée : elle s'en va, son emplacement reste.
            io.unplug(node)
            waitFor(what = "le départ") { src.devices.isEmpty() }
            input.poll()
            assertFalse(pad.connected); assertEquals(listOf("+0", "-0"), events); assertNull(input.pad)
            assertTrue(input.released(Pad.CROSS), "la croix est vue relâchée")
            // Rebranchée sur un autre nœud : la même manette (même adresse) retrouve sa place et sa couleur.
            val again = io.plugDualSense("hidraw9", 5, "") { features(true, mac) }
            waitFor(what = "le retour") { src.devices.size == 1 }
            input.poll()
            assertSame(pad, input.pad); assertTrue(pad.connected); assertEquals(0, pad.slot); assertEquals(listOf("+0", "-0", "+0"), events)
            waitFor(what = "la couleur renvoyée") { again.written.any { u(it, 3 + 1) and 0x04 != 0 && u(it, 3 + 45) == 255 } }
            assertTrue(logs.isEmpty(), "aucun message : $logs")
        } finally { src.close() }
    }

    @Test fun parUsbLeRapportZeroDeux() = withTree { io ->
        val src = SystemPads(io.sys, io.dev, io, log = {})
        try {
            val node = io.plugDualSense("hidraw3", 3, "aa:bb:cc:11:22:33", product = 0x0DF2) { features(false, mac) }
            waitFor { src.devices.size == 1 }
            val input = Input().apply { padSource = src }; input.poll()
            val pad = input.pad!!
            assertEquals(PadKind.DUALSENSE_EDGE, pad.kind); assertEquals(PadConnection.USB, pad.connection)
            pad.triggerEffect(Pad.R2, TriggerEffect.resistance(.3, .8))
            waitFor { node.written.any { u(it, 0) == 0x02 && it.size == 63 && u(it, 1) and 0x04 != 0 && u(it, 1 + 10) == 0x21 } }
            node.reports.add(report(false, buttons0 = 0x08 or 0x80))
            waitFor { input.poll(); pad.isDown(Pad.TRIANGLE) }
            // Fermer rend la gâchette libre.
            src.close()
            val last = node.written.last()
            assertEquals(0x04, u(last, 1) and 0x04); assertEquals(0x05, u(last, 1 + 10), "R2 libérée en partant")
            pad.lightbar = Color.RED; pad.rumble(1)                 // après la fermeture (la dernière image d'un jeu) : sans effet ni erreur
            assertSame(last, node.written.last())
        } finally { src.close() }
    }

    @Test fun deuxManettesDeuxEmplacementsEtLeursVoyants() = withTree { io ->
        val src = SystemPads(io.sys, io.dev, io, log = {})
        try {
            val a = io.plugDualSense("hidraw4", 3, "") { features(false, mac) }
            val b = io.plugDualSense("hidraw5", 5, "") { features(true, listOf(1, 2, 3, 4, 5, 6)) }
            waitFor { src.devices.size == 2 }
            val input = Input().apply { padSource = src }; input.poll()
            assertEquals(listOf(0, 1), input.pads.map { it.slot })
            waitFor { a.written.isNotEmpty() && b.written.isNotEmpty() }
            // Les emplacements suivent l'ordre où les manettes ont fini de se présenter ; les voyants les montrent.
            val slotA = input.pads.first { it.path == a.path }.slot; val slotB = input.pads.first { it.path == b.path }.slot
            assertEquals(setOf(0, 1), setOf(slotA, slotB))
            waitFor { a.written.any { u(it, 1 + 1) and 0x10 != 0 } && b.written.any { u(it, 3 + 1) and 0x10 != 0 } }
            assertEquals(Pad.playerPattern(slotA + 1), u(a.written.last { u(it, 1 + 1) and 0x10 != 0 }, 1 + 43))
            assertEquals(Pad.playerPattern(slotB + 1), u(b.written.last { u(it, 3 + 1) and 0x10 != 0 }, 3 + 43))
        } finally { src.close() }
    }

    @Test fun leGyroscopeEstIntegreSurToutesLesMesures() = withTree { io ->
        val src = SystemPads(io.sys, io.dev, io, log = {})
        try {
            val node = io.plugDualSense("hidraw6", 3, "") { features(false, mac) }
            waitFor { src.devices.size == 1 }
            val input = Input().apply { padSource = src }; input.poll()
            val pad = input.pad!!
            // 101 mesures à 1 ms d'écart (3000 tiers de µs), 1000 unités : 125 °/s avec ce calibrage.
            for (k in 0..100) node.reports.add(report(false, gyroY = 1000, stamp = 1_000_000 + 3000 * k))
            waitFor { input.poll(); pad.state.reports >= 101 }
            val rate = 1000 * 2000f / 16000f * (PI / 180).toFloat()
            assertEquals(rate, pad.gyro.y, 1e-4f)
            assertEquals(rate * .1, pad.state.turn[1], 1e-4, "intégré sur 100 intervalles de 1 ms")
            assertTrue(pad.state.turnValid)
        } finally { src.close() }
    }

    @Test fun uneDualSenseFermeeAUtilisateurLeDitUneFois() = withTree { io ->
        val logs = CopyOnWriteArrayList<String>()
        val src = SystemPads(io.sys, io.dev, io, log = { logs.add(it) })
        try {
            val node = io.plugDualSense("hidraw2", 5, "") { denied = true }
            Thread.sleep(800)
            assertTrue(logs.isEmpty(), "pas tout de suite : udev donne peut-être encore les droits")
            waitFor(4000, "le message") { logs.isNotEmpty() }
            assertEquals(1, logs.size)
            assertTrue("evdev" in logs[0] && "light bar" in logs[0] && "60-steam-input.rules" in logs[0], logs[0])
            assertTrue(src.devices.isEmpty()); assertTrue(node.opened == 0)
            // Les droits arrivent : la manette est prise au prochain changement dans /dev.
            node.features(true, mac)
            node.denied = false
            Files.setLastModifiedTime(io.dev.resolve("hidraw2"), java.nio.file.attribute.FileTime.fromMillis(0))
            waitFor(what = "prise après coup") { src.devices.size == 1 }
            assertEquals(1, logs.size)
        } finally { src.close() }
    }

    @Test fun unCalibrageAuCrcFauxEstRefuse() = withTree { io ->
        val logs = CopyOnWriteArrayList<String>()
        val src = SystemPads(io.sys, io.dev, io, log = { logs.add(it) })
        try {
            io.plugDualSense("hidraw1", 5, "") { features(true, mac, badCrc = true) }
            waitFor { src.devices.size == 1 }
            assertFalse((src.devices[0] as HidrawDualSense).calibration.fromDevice)
            assertTrue(logs.single().contains("default scales"), logs.toString())
        } finally { src.close() }
    }

    @Test fun leFilDeLectureNAllouePas() = withTree { io ->
        val src = SystemPads(io.sys, io.dev, io, log = {})
        try {
            val node = io.plugDualSense("hidraw8", 5, "") { features(true, mac) }
            waitFor { src.devices.size == 1 }
            val hw = src.devices[0] as HidrawDualSense
            val input = Input().apply { padSource = src }
            val reports = List(64) { report(true, buttons0 = if (it % 2 == 0) 0x28 else 0x08, lx = it * 4, gyroY = it * 10, stamp = it * 3000) }
            fun feed(n: Int) {
                repeat(n) { node.reports.add(reports[it % 64]) }
                waitFor(5000) { node.reports.isEmpty() }
                Thread.sleep(20)
            }
            feed(5000); input.poll()
            val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val id = hw.thread!!.threadId()
            val before = bean.getThreadAllocatedBytes(id)
            feed(5000)
            val bytes = bean.getThreadAllocatedBytes(id) - before
            assertTrue(bytes < 4096, "$bytes octets alloués par le fil de lecture pour 5000 rapports")
            input.poll(); assertTrue(input.pad!!.state.reports >= 10_000)
        } finally { src.close() }
    }
}
