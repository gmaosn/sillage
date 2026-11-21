package sillage

import sillage.platform.DualSense
import sillage.platform.EvdevCaps
import sillage.platform.HidInfo
import sillage.platform.Posix
import sillage.platform.SystemPads
import java.lang.foreign.Arena
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

/**
 * `--pad-test`: checks the real pads from a terminal, without a window or a scene. It says which pads the
 * system shows and which it may not open, then for each pad connected: the live state (buttons, sticks,
 * triggers, motion, touchpad, battery, USB or Bluetooth), the light bar cycling, the player LEDs, a heavy
 * then a light rumble, and adaptive effects on R2 and L2. Keys on the pad: TRIANGLE changes the trigger
 * effects, CROSS rumbles, SQUARE switches the mute light, CIRCLE stops the colours; holding PS for two
 * seconds (or Ctrl-C) ends it. Everything is left as found: triggers free, motors stopped.
 */
internal fun padTest(args: Array<String>) {
    val seconds = args.indexOf("--seconds").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toFloatOrNull() } ?: 300f
    val tty = System.console() != null && System.getenv("TERM") != "dumb"
    println("""
        |Sillage pad test: plug in or pair a pad (a DualSense for everything), and watch it.
        |What to look for on a DualSense:
        |  - the light bar cycling red, green, blue, white, then the player's colour (CIRCLE stops it);
        |  - the player LEDs under the touchpad showing the player number (player 1: the middle one);
        |  - a heavy rumble, then a light one (CROSS rumbles again);
        |  - R2 resisting from a third of its travel, L2 clicking like a gun's trigger (TRIANGLE: next effects);
        |  - SQUARE switches the mute button's light;
        |  - below, the sticks, triggers, gyroscope (rad/s), accelerometer (m/s², about +9.8 on y when the pad lies flat),
        |    touchpad fingers and battery as you use them.
        |Hold PS for two seconds or press Ctrl-C to stop (it stops by itself after ${seconds.roundToInt()} s).
        |""".trimMargin())
    diagnose()

    val messages = ConcurrentLinkedQueue<String>()
    val source = SystemPads(log = { messages.add("! $it") })
    val input = Input()
    input.padSource = source
    val started = HashMap<Pad, Long>()
    val effect = HashMap<Pad, Int>()
    val cycling = HashMap<Pad, Boolean>()
    input.onPadConnected { p ->
        messages.add("+ pad ${p.slot + 1} connected: ${p.name}, ${p.connection.name.lowercase()}, ${p.path}, ${p.kind.name.lowercase()}, ${features(p)}")
        if (p.kind == PadKind.GENERIC && !p.canRumble) messages.add("  (no rumble: the pad has none, or its node is read-only)")
        started[p] = System.nanoTime(); effect[p] = 0; cycling[p] = true
    }
    input.onPadDisconnected { p -> messages.add("- pad ${p.slot + 1} disconnected (${p.name})") }
    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    fun closeAll() { if (closed.compareAndSet(false, true)) source.close() }
    val hook = Thread { closeAll(); println("\nPad test stopped: trigger effects freed, motors stopped.") }
    java.lang.Runtime.getRuntime().addShutdownHook(hook)

    val effects = listOf(
        "R2 resistance from 30 %, L2 gun click 30–60 %" to (TriggerEffect.resistance(.3, .8) to TriggerEffect.weapon(.3, .6, 1)),
        "R2 gun click 20–50 %, L2 resistance from 0 %" to (TriggerEffect.weapon(.2, .5, 1) to TriggerEffect.resistance(0, .4)),
        "R2 vibrating from 20 % at 40 Hz, L2 vibrating at 10 Hz" to (TriggerEffect.vibration(.2, .8, 40) to TriggerEffect.vibration(0, 1, 10)),
        "both triggers free" to (TriggerEffect.OFF to TriggerEffect.OFF))
    val colours = listOf(Color.hex(0xFF0000), Color.hex(0x00FF00), Color.hex(0x0000FF), Color.hex(0xFFFFFF))
    val start = System.nanoTime()
    var drawn = 0
    var lastDraw = 0L
    var psHeld = 0L
    try {
        while ((System.nanoTime() - start) / 1e9 < seconds) {
            input.poll()
            val now = System.nanoTime()
            for (p in input.pads) {
                val t = (now - (started[p] ?: now)) / 1e9
                if (p.hasLightbar && cycling[p] == true) {
                    val k = (t / .6).toInt()
                    val c = if (k < 4) colours[k] else if (k < 6) Pad.slotColor(p.slot) else colours[(k - 6) % 4]
                    if (p.lightbar !== c) p.lightbar = c
                }
                val step = ((t - .3) / .6).toInt()
                if (t >= .3 && p.out.rumbleCount == 0) p.rumble(1, 0, .35)
                if (step >= 1 && p.out.rumbleCount == 1) p.rumble(0, 1, .35)
                if (p.hasAdaptiveTriggers && t >= 1.5 && p.triggerEffect(Pad.R2) === TriggerEffect.OFF && effect[p] == 0) {
                    p.triggerEffect(Pad.R2, effects[0].second.first); p.triggerEffect(Pad.L2, effects[0].second.second)
                    messages.add("  pad ${p.slot + 1}: ${effects[0].first}")
                }
                if (p.pressed(Pad.TRIANGLE) && p.hasAdaptiveTriggers) {
                    val e = ((effect[p] ?: 0) + 1) % effects.size; effect[p] = e
                    p.triggerEffect(Pad.R2, effects[e].second.first); p.triggerEffect(Pad.L2, effects[e].second.second)
                    messages.add("  pad ${p.slot + 1}: ${effects[e].first}")
                }
                if (p.pressed(Pad.CROSS)) p.rumble(.5, .5, .3)
                if (p.pressed(Pad.SQUARE)) p.muteLed = !p.muteLed
                if (p.pressed(Pad.CIRCLE)) { cycling[p] = false; p.lightbar = Pad.slotColor(p.slot) }
                if (p.isDown(Pad.PS)) { if (psHeld == 0L) psHeld = now }
            }
            if (input.pads.none { it.isDown(Pad.PS) }) psHeld = 0L
            if (psHeld != 0L && now - psHeld > 2_000_000_000L) { messages.add("PS held: stopping"); break }
            if (now - lastDraw > (if (tty) 100_000_000L else 2_000_000_000L)) {
                lastDraw = now
                drawn = draw(input, messages, tty, drawn, effect, effects.map { it.first })
            }
            Thread.sleep(8)
        }
    } finally {
        closeAll()
        runCatching { java.lang.Runtime.getRuntime().removeShutdownHook(hook) }
        drawn = draw(input, messages, tty, drawn, effect, effects.map { it.first })
        println()
        println("Summary:")
        val seen = input.hub.slots.filterNotNull()
        if (seen.isEmpty()) println("  no pad was seen.")
        for (p in seen) println("  pad ${p.slot + 1}: ${p.name}, ${p.connection.name.lowercase()}, ${p.path}: ${features(p)}" +
            (if (p.hasMotion) ", gyro and accelerometer read" else ""))
        if (source.notes.isEmpty()) println("  nothing was out of reach.")
        else { println("  what could not be done:"); for (n in source.notes) println("    - $n") }
        if (steamRunning()) println("  Steam is running: it may drive the light bar and LEDs too (Steam Input); if the lights changed on their own, quit Steam or turn off its PlayStation controller support.")
        println("Trigger effects freed, motors stopped.")
    }
}

private fun features(p: Pad): String {
    val f = listOfNotNull("buttons, sticks, triggers", if (p.canRumble) "rumble" else null, if (p.hasLightbar) "light bar, player LEDs, mute light" else null,
        if (p.hasAdaptiveTriggers) "adaptive triggers" else null, if (p.hasMotion) "motion" else null, if (p.hasTouchpad) "touchpad" else null,
        if (p.hasBattery) "battery" else null)
    return f.joinToString(", ")
}

/** Redraws the pads' state (in place on a terminal); returns how many lines it drew. */
private fun draw(input: Input, messages: ConcurrentLinkedQueue<String>, tty: Boolean, drawn: Int, effect: Map<Pad, Int>, effectNames: List<String>): Int {
    val out = StringBuilder()
    if (tty && drawn > 0) out.append("\u001b[${drawn}A")
    fun line(s: String) { if (tty) out.append("\u001b[2K"); out.append(s).append('\n') }
    while (true) { val m = messages.poll() ?: break; line(m) }
    var n = 0
    if (input.pads.isEmpty()) { line("(no pad connected: waiting…)"); n++ }
    for (p in input.pads) {
        fun f(v: Float) = "%+.2f".format(v)
        line("pad ${p.slot + 1} — ${p.name} — ${p.connection.name.lowercase()} — ${p.path}"); n++
        line("  sticks L (${f(p.leftStick.x)}, ${f(p.leftStick.y)})  R (${f(p.rightStick.x)}, ${f(p.rightStick.y)})   L2 %.2f  R2 %.2f   held: %s"
            .format(p.trigger(Pad.L2), p.trigger(Pad.R2), p.buttonsDown().ifEmpty { "—" })); n++
        if (p.hasMotion) {
            val g = p.gyro; val a = p.accel
            line("  gyro (%+.2f, %+.2f, %+.2f) rad/s   accel (%+.1f, %+.1f, %+.1f) m/s²".format(g.x, g.y, g.z, a.x, a.y, a.z)); n++
        }
        if (p.hasTouchpad) {
            val t = p.touch.withIndex().joinToString("   ") { (i, t) -> "finger ${i + 1}: " + if (t.active) "(%.2f, %.2f) id %d".format(t.x, t.y, t.id) else "—" }
            line("  touchpad $t"); n++
        }
        if (p.hasBattery || p.hasAdaptiveTriggers) {
            val b = if (p.battery < 0f) "battery unknown" else "battery ${(p.battery * 100).roundToInt()} % ${p.batteryState.name.lowercase()}"
            val e = if (p.hasAdaptiveTriggers) "   triggers: R2 ${p.triggerEffect(Pad.R2)}, L2 ${p.triggerEffect(Pad.L2)}" else ""
            line("  $b   headphones ${if (p.headphones) "in" else "out"}   microphone ${if (p.microphone) "in" else "out"}$e"); n++
        }
    }
    // Clear lines left over from a longer drawing.
    if (tty) for (k in n until drawn) { out.append("\u001b[2K\n"); n++ }
    print(out); System.out.flush()
    return if (tty) n else 0
}

/** Says what the system shows before any pad is opened: DualSense hidraw nodes and gamepads, and whether this user may open them. */
private fun diagnose() {
    val sys = Path.of("/sys"); val dev = Path.of("/dev")
    Arena.ofConfined().use { arena ->
        val px = Posix(arena)
        val lines = ArrayList<String>()
        runCatching { Files.list(sys.resolve("class/hidraw")).use { it.toList() } }.getOrDefault(emptyList()).sorted().forEach { e ->
            val info = HidInfo.read(e.resolve("device")) ?: return@forEach
            if (!DualSense.isDualSense(info.vendor, info.product)) return@forEach
            val node = dev.resolve(e.fileName.toString()).toString()
            val fd = px.open(node, Posix.O_RDWR or Posix.O_NONBLOCK or Posix.O_CLOEXEC)
            px.close(fd)
            lines.add("  DualSense « ${info.name} » at $node (${if (info.bus == DualSense.BUS_BLUETOOTH) "Bluetooth" else "USB"}, driver ${info.driver.ifEmpty { "none" }}): " +
                if (fd >= 0) "can be opened" else "cannot be opened (${Posix.describe(-fd)})")
        }
        runCatching { Files.list(sys.resolve("class/input")).use { it.toList() } }.getOrDefault(emptyList())
            .filter { it.fileName.toString().startsWith("event") }.sortedBy { it.fileName.toString().removePrefix("event").toIntOrNull() ?: 0 }.forEach { e ->
                val caps = EvdevCaps.read(e.resolve("device")) ?: return@forEach
                if (!caps.layout.isGamepad) return@forEach
                val node = dev.resolve("input").resolve(e.fileName.toString()).toString()
                val fd = px.open(node, Posix.O_RDONLY or Posix.O_NONBLOCK or Posix.O_CLOEXEC)
                px.close(fd)
                lines.add("  gamepad « ${caps.name} » at $node (%04x:%04x): ".format(caps.vendor, caps.product) +
                    if (fd >= 0) "can be read" else "cannot be read (${Posix.describe(-fd)})")
            }
        if (lines.isEmpty()) println("No pad is plugged in or paired yet. Plug one in, or pair a DualSense: hold PS and Create until the light bar flashes, then pair it in the system's Bluetooth settings.")
        else { println("The system shows:"); lines.forEach(::println) }
        if (steamRunning()) println("Steam is running: it may also set the light bar and player LEDs (Steam Input).")
        println()
    }
}

private fun steamRunning(): Boolean = runCatching {
    ProcessHandle.allProcesses().anyMatch { h -> h.info().command().map { it.endsWith("/steam") }.orElse(false) }
}.getOrDefault(false)
