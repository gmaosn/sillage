package sillage.input

import sillage.BatteryState
import sillage.Color
import sillage.Pad
import sillage.TriggerEffect
import sillage.platform.Crc32
import sillage.platform.DualSense
import sillage.platform.PadOutput
import sillage.platform.PadState
import java.util.Random
import kotlin.math.PI
import kotlin.test.*

/** Rapports de la DualSense construits à la main : entrées USB et Bluetooth, calibrage, sorties, effets de gâchettes, CRC. */
class DualSenseTest {
    private val g = 9.80665f
    private val rad = (PI / 180).toFloat()

    private fun le16(b: ByteArray, at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
    private fun le32(b: ByteArray, at: Int, v: Long) { for (k in 0 until 4) b[at + k] = (v shr (8 * k)).toByte() }
    private fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    /** Le corps commun d'un rapport d'entrée, écrit à partir de [at]. */
    private fun body(b: ByteArray, at: Int) {
        b[at] = 255.toByte(); b[at + 1] = 0; b[at + 2] = 128.toByte(); b[at + 3] = 255.toByte()   // gauche à droite et en haut, droit en bas
        b[at + 4] = 255.toByte(); b[at + 5] = 128.toByte()                                     // L2 à fond, R2 à moitié
        b[at + 7] = (0x20 or 2).toByte()                                                        // croix, croix directionnelle à droite
        b[at + 8] = (0x01 or 0x20 or 0x80).toByte()                                             // L1, options, R3
        b[at + 9] = (0x01 or 0x04 or 0x10).toByte()                                             // PS, muet, Fn gauche (Edge)
        le16(b, at + 15, 1000); le16(b, at + 17, -2000); le16(b, at + 19, 0)
        le16(b, at + 21, 0); le16(b, at + 23, 8192); le16(b, at + 25, -4096)
        le32(b, at + 27, 123456L)
        // Doigt 1 : actif, numéro 5, en (1919, 1079) ; doigt 2 : levé, en (960, 540).
        b[at + 32] = 0x05; b[at + 33] = 0x7F; b[at + 34] = 0x77; b[at + 35] = 0x43
        b[at + 36] = 0x80.toByte(); b[at + 37] = 0xC0.toByte(); b[at + 38] = 0xC3.toByte(); b[at + 39] = 0x21
        b[at + 52] = 0x16                                                                        // en charge, 6 dixièmes
        b[at + 53] = 0x03                                                                        // casque et micro branchés
    }

    private fun usb() = ByteArray(64).also { it[0] = 1; body(it, 1) }
    private fun bluetooth() = ByteArray(78).also { it[0] = 0x31; body(it, 2); le32(it, 74, Crc32.seeded(0xA1, it, 0, 74).toLong() and 0xFFFFFFFFL) }

    private fun checkBody(s: PadState, edge: Boolean) {
        assertEquals(1f, s.axes[0]); assertEquals(1f, s.axes[1], "y vers le haut est positif"); assertEquals(0f, s.axes[2]); assertEquals(-1f, s.axes[3])
        assertEquals(1f, s.axes[4]); assertEquals(128 / 255f, s.axes[5])
        val held = (0 until Pad.BUTTON_COUNT).filter { s.buttons and (1 shl it) != 0 }.map { Pad.BUTTON_NAMES[it] }.toSet()
        val expected = setOf("CROSS", "RIGHT", "L1", "OPTIONS", "R3", "PS", "MUTE") + if (edge) setOf("FN_LEFT") else emptySet()
        assertEquals(expected, held)
        assertEquals(1000 * 2048f / 32767f * rad, s.gyro[0], 1e-6f); assertEquals(-2000 * 2048f / 32767f * rad, s.gyro[1], 1e-6f); assertEquals(0f, s.gyro[2])
        assertEquals(0f, s.accel[0]); assertEquals(g, s.accel[1], 1e-5f); assertEquals(-g / 2, s.accel[2], 1e-5f)
        assertTrue(s.touchActive[0]); assertEquals(5, s.touchId[0]); assertEquals(1f, s.touchX[0]); assertEquals(1f, s.touchY[0])
        assertFalse(s.touchActive[1]); assertEquals(960 / 1919f, s.touchX[1], 1e-6f); assertEquals(540 / 1079f, s.touchY[1], 1e-6f)
        assertEquals(.65f, s.battery, 1e-6f); assertEquals(BatteryState.CHARGING.ordinal, s.batteryState)
        assertTrue(s.headphones); assertTrue(s.microphone)
    }

    @Test fun rapportUsbComplet() {
        val s = PadState(); val stamp = LongArray(1)
        assertEquals(DualSense.PARSED, DualSense.parseInput(usb(), 64, DualSense.Calibration(), s, edge = true, timestamp = stamp))
        checkBody(s, edge = true)
        assertEquals(123456L, stamp[0])
        assertEquals(1L, s.reports)
    }

    @Test fun lesBoutonsDeLEdgeNeComptentQueSurUneEdge() {
        val s = PadState()
        DualSense.parseInput(usb(), 64, DualSense.Calibration(), s, edge = false)
        checkBody(s, edge = false)
    }

    @Test fun rapportBluetoothEtSonCrc() {
        val s = PadState()
        val b = bluetooth()
        assertEquals(DualSense.PARSED, DualSense.parseInput(b, 78, DualSense.Calibration(), s, edge = true))
        checkBody(s, edge = true)
        // Un octet changé : le rapport est rejeté, l'état ne bouge pas.
        b[10] = (b[10] + 1).toByte()
        val t = PadState()
        assertEquals(DualSense.BAD_CRC, DualSense.parseInput(b, 78, DualSense.Calibration(), t, edge = true))
        assertEquals(0L, t.reports); assertEquals(0, t.buttons)
    }

    @Test fun rapportBluetoothCourtAvantLeModeComplet() {
        val b = ByteArray(10)
        b[0] = 1; b[1] = 0; b[2] = 128.toByte(); b[3] = 255.toByte(); b[4] = 128.toByte()
        b[5] = (0x80 or 4).toByte(); b[6] = 0x02; b[7] = (0x01 or 0x08).toByte()     // triangle, bas ; R1 ; PS (le compteur au-dessus)
        b[8] = 0; b[9] = 255.toByte()
        val s = PadState()
        assertEquals(DualSense.PARSED, DualSense.parseInput(b, 10, DualSense.Calibration(), s, edge = false))
        assertEquals(-1f, s.axes[0]); assertEquals(0f, s.axes[1], 0.01f); assertEquals(1f, s.axes[2])
        assertEquals(1f, s.axes[5])
        val held = (0 until Pad.BUTTON_COUNT).filter { s.buttons and (1 shl it) != 0 }.map { Pad.BUTTON_NAMES[it] }.toSet()
        assertEquals(setOf("TRIANGLE", "DOWN", "R1", "PS"), held)
    }

    @Test fun unAutreRapportEstIgnore() {
        val s = PadState()
        assertEquals(DualSense.IGNORED, DualSense.parseInput(byteArrayOf(0x05, 1, 2), 3, DualSense.Calibration(), s, false))
        assertEquals(DualSense.IGNORED, DualSense.parseInput(ByteArray(0), 0, DualSense.Calibration(), s, false))
    }

    @Test fun lesHuitDirectionsDuChapeau() {
        val expected = listOf(setOf("UP"), setOf("UP", "RIGHT"), setOf("RIGHT"), setOf("RIGHT", "DOWN"), setOf("DOWN"),
            setOf("DOWN", "LEFT"), setOf("LEFT"), setOf("LEFT", "UP"), emptySet(), emptySet())
        for (hat in 0..9) {
            val bits = DualSense.buttons(hat, 0, 0, false)
            assertEquals(expected[hat], (0 until Pad.BUTTON_COUNT).filter { bits and (1 shl it) != 0 }.map { Pad.BUTTON_NAMES[it] }.toSet(), "chapeau $hat")
        }
    }

    @Test fun calibrageDuGyroscopeEtDeLAccelerometre() {
        val f = ByteArray(41); f[0] = 0x05
        le16(f, 1, 10); le16(f, 3, -5); le16(f, 5, 0)                       // biais
        le16(f, 7, 8010); le16(f, 9, -7990)                                  // tangage : 8000 + 8000 autour du biais
        le16(f, 11, 8000); le16(f, 13, -8000)                                // lacet : 8005 + 7995
        le16(f, 15, 8000); le16(f, 17, -8000)                                // roulis
        le16(f, 19, 540); le16(f, 21, 540)                                   // vitesse de référence (°/s) : 1080 pour 16 000
        le16(f, 23, 8300); le16(f, 25, -8100)                                // x : ±1 g autour de 100
        le16(f, 27, 8192); le16(f, 29, -8192)                                // y : centré
        le16(f, 31, 8000); le16(f, 33, -8400)                                // z : autour de −200
        val c = DualSense.Calibration()
        assertTrue(c.read(f, 41)); assertTrue(c.fromDevice)
        for (i in 0 until 3) assertEquals(1080f / 16000f * rad, c.gyroScale[i], 1e-9f, "gyro $i")
        val r = ByteArray(64); r[0] = 1; body(r, 1)
        le16(r, 1 + 15, 1000)
        le16(r, 1 + 21, 8300); le16(r, 1 + 23, -8192); le16(r, 1 + 25, -8400)
        val s = PadState()
        DualSense.parseInput(r, 64, c, s, false)
        assertEquals(67.5f * rad, s.gyro[0], 1e-5f, "1000 unités à 1080/16000 °/s : 67,5 °/s")
        assertEquals(g, s.accel[0], 1e-4f); assertEquals(-g, s.accel[1], 1e-4f); assertEquals(-g, s.accel[2], 1e-4f)
    }

    @Test fun unCalibrageAbsurdeGardeLesValeursParDefaut() {
        val f = ByteArray(41); f[0] = 0x05                                   // que des zéros : dénominateurs nuls
        val c = DualSense.Calibration()
        assertFalse(c.read(f, 41)); assertFalse(c.fromDevice)
        assertEquals(2048f / 32767f * rad, c.gyroScale[1]); assertEquals(g / 8192f, c.accelScale[2])
        assertFalse(DualSense.Calibration().read(f, 20), "trop court")
    }

    @Test fun batterieEnQuartets() {
        val s = PadState()
        fun at(status: Int): Pair<Float, BatteryState> { DualSense.battery(status, s); return s.battery to BatteryState.entries[s.batteryState] }
        assertEquals(.55f to BatteryState.DISCHARGING, at(0x05))
        assertEquals(.05f to BatteryState.DISCHARGING, at(0x00))
        assertEquals(1f to BatteryState.CHARGING, at(0x1A), "10 dixièmes : 100 %, pas 105")
        assertEquals(1f to BatteryState.FULL, at(0x23))
        assertEquals(0f to BatteryState.ERROR, at(0xA0)); assertEquals(0f to BatteryState.ERROR, at(0xB4))
        assertEquals(-1f to BatteryState.UNKNOWN, at(0xF0))
    }

    @Test fun crc32SurLesVecteursConnus() {
        assertEquals(0xCBF43926.toInt(), Crc32.of("123456789".toByteArray()))
        assertEquals(0, Crc32.of(ByteArray(0)))
        assertEquals(0x414FA339, Crc32.of("The quick brown fox jumps over the lazy dog".toByteArray()))
        val rnd = Random(7)
        repeat(200) {
            val b = ByteArray(rnd.nextInt(120)).also(rnd::nextBytes)
            val z = java.util.zip.CRC32(); z.update(b)
            assertEquals(z.value.toInt(), Crc32.of(b))
            val seed = rnd.nextInt(256)
            val zs = java.util.zip.CRC32(); zs.update(seed); zs.update(b)
            assertEquals(zs.value.toInt(), Crc32.seeded(seed, b, 0, b.size))
        }
    }

    private fun out(touched: Int, setup: PadOutput.() -> Unit = {}) = PadOutput().apply { this.touched = touched; setup() }

    @Test fun barreLumineuseParUsb() {
        val b = ByteArray(78)
        val n = DualSense.output(out(PadOutput.LIGHTBAR) { lightbar = Color.hex(0xFF8000) }, false, bluetooth = false, seq = 0, vibrationV2 = true, lightOut = false, into = b)
        assertEquals(63, n); assertEquals(0x02, b[0].toInt())
        assertEquals(0, b[1].toInt()); assertEquals(0x04, b[2].toInt()); assertEquals(0, b[1 + 38].toInt())
        assertEquals(listOf(255, 128, 0), (44..46).map { b[1 + it].toInt() and 0xFF })
        // Rien d'autre que la barre : ni moteurs, ni gâchettes, ni voyants.
        assertTrue((0 until 63).filter { it !in setOf(0, 2, 45, 46, 47) }.all { b[it].toInt() == 0 })
    }

    @Test fun parBluetoothNumeroDOrdreEtiquetteEtCrc() {
        val b = ByteArray(78)
        val n = DualSense.output(out(PadOutput.LEDS or PadOutput.MUTE) { playerLeds = Pad.playerPattern(3); muteLed = true }, false, bluetooth = true, seq = 5,
            vibrationV2 = true, lightOut = false, into = b)
        assertEquals(78, n); assertEquals(0x31, b[0].toInt()); assertEquals(0x50, b[1].toInt()); assertEquals(0x10, b[2].toInt())
        assertEquals(0x10 or 0x01, b[3 + 1].toInt(), "voyants des joueurs et lumière du bouton muet")
        assertEquals(0b10101, b[3 + 43].toInt()); assertEquals(1, b[3 + 8].toInt())
        assertEquals(Crc32.seeded(0xA2, b, 0, 74), le32(b, 74))
        val z = java.util.zip.CRC32(); z.update(0xA2); z.update(b, 0, 74)
        assertEquals(z.value.toInt(), le32(b, 74))
    }

    @Test fun vibrationsAncienneEtNouvelleManiere() {
        val b = ByteArray(78)
        val o = out(PadOutput.RUMBLE) { rumbleLow = .5f; rumbleHigh = 1f }
        DualSense.output(o, true, false, 0, vibrationV2 = false, lightOut = false, into = b)
        assertEquals(0x03, b[1].toInt()); assertEquals(0, b[1 + 38].toInt())
        assertEquals(255, b[1 + 2].toInt() and 0xFF, "moteur droit (léger)"); assertEquals(128, b[1 + 3].toInt() and 0xFF, "moteur gauche (lourd)")
        DualSense.output(o, true, false, 0, vibrationV2 = true, lightOut = false, into = b)
        assertEquals(0x02, b[1].toInt()); assertEquals(0x04, b[1 + 38].toInt())
        DualSense.output(o, false, false, 0, vibrationV2 = true, lightOut = false, into = b)
        assertEquals(0, b[1 + 2].toInt()); assertEquals(0, b[1 + 3].toInt(), "le temps est écoulé : moteurs arrêtés")
    }

    @Test fun gachettesAdaptativesDansLeRapport() {
        val b = ByteArray(78)
        DualSense.output(out(PadOutput.L2 or PadOutput.R2) { r2 = TriggerEffect.resistance(.3, .8); l2 = TriggerEffect.weapon(.3, .6, 1) }, false, false, 0, true, false, b)
        assertEquals(0x0C, b[1].toInt())
        assertEquals(listOf(0x21, 0xF8, 0x03, 0x00, 0x6C, 0xDB, 0x36, 0, 0, 0, 0), (0 until 11).map { b[1 + 10 + it].toInt() and 0xFF })
        assertEquals(listOf(0x25, 0x28, 0x00, 0x07, 0, 0, 0, 0, 0, 0, 0), (0 until 11).map { b[1 + 21 + it].toInt() and 0xFF })
    }

    @Test fun octetsDesEffets() {
        fun bytes(e: TriggerEffect) = ByteArray(11).also { e.bytes(it, 0) }.map { it.toInt() and 0xFF }
        assertEquals(listOf(5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), bytes(TriggerEffect.OFF))
        assertEquals(listOf(0x26, 0xFC, 0x03, 0x80, 0x6D, 0xDB, 0x36, 0, 0, 40, 0), bytes(TriggerEffect.vibration(.2, .8, 40)))
        // Toute la course, force maximale : 10 zones à 7.
        assertEquals(listOf(0x21, 0xFF, 0x03, 0xFF, 0xFF, 0xFF, 0x3F, 0, 0, 0, 0), bytes(TriggerEffect.resistance(0, 1)))
        // L'arme ne fonctionne qu'entre les zones 2 et 8 : les positions sont ramenées dedans.
        assertEquals(listOf(0x25, 0x04 or 0x08, 0x00, 0x00), bytes(TriggerEffect.weapon(0, 0, .01)).take(4))
        assertEquals(listOf(0x25, 0x80 or 0x00, 0x01, 0x07), bytes(TriggerEffect.weapon(1, 1, 1)).take(4))
        assertSame(TriggerEffect.OFF, TriggerEffect.resistance(.5, 0))
    }

    @Test fun ordreDEteindreLAnimationDeDemarrage() {
        val b = ByteArray(78)
        DualSense.output(PadOutput(), false, false, 0, true, lightOut = true, into = b)
        assertEquals(0x02, b[1 + 38].toInt()); assertEquals(0x02, b[1 + 41].toInt())
        assertEquals(0, b[1].toInt()); assertEquals(0, b[2].toInt())
    }

    @Test fun versionDuMicrologicielEtAdresse() {
        val f = ByteArray(64); f[0] = 0x20; le16(f, 44, 0x0215)
        assertEquals(0x0215, DualSense.updateVersion(f, 64))
        assertEquals(-1, DualSense.updateVersion(f, 20))
        val p = ByteArray(20); p[0] = 0x09
        for (i in 1..6) p[i] = (0x11 * i).toByte()
        assertEquals("66:55:44:33:22:11", DualSense.address(p, 20))
        assertNull(DualSense.address(ByteArray(20).also { it[0] = 9 }, 20), "adresse nulle")
        // Le CRC d'un rapport de fonction par Bluetooth (graine 0xA3).
        val c = ByteArray(41); c[0] = 5; c[5] = 42; le32(c, 37, Crc32.seeded(0xA3, c, 0, 37).toLong() and 0xFFFFFFFFL)
        assertTrue(DualSense.featureCrcOk(c, 41)); c[5] = 43; assertFalse(DualSense.featureCrcOk(c, 41))
    }
}
