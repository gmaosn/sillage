package sillage

/**
 * The game thread's side of the pads: which [Pad] holds which device, in which slot. Shared by the
 * inputs of the scenes a game goes through, so a pad keeps its slot from one scene to the next.
 */
internal class PadHub {
    var source: PadSource = PadSource.NONE
    private val virtual = ArrayList<VirtualPad>()
    private var virtualChanged = false
    private var seen: List<PadDevice>? = null
    /** Pads by slot: connected ones and those unplugged (kept so the same pad gets its slot back). */
    val slots = ArrayList<Pad?>()
    /** Connected pads, by slot. */
    val connected = ArrayList<Pad>()
    /** Pads just unplugged, still read for two frames: their buttons are seen let go, once. */
    private val leaving = ArrayList<Pad>()
    private val all = ArrayList<PadDevice>()
    private val gone = ArrayList<Pad>()
    private val arrived = ArrayList<Pad>()

    fun plug(p: VirtualPad) { if (p !in virtual) { virtual.add(p); virtualChanged = true } }
    fun unplug(p: VirtualPad) { if (virtual.remove(p)) virtualChanged = true }

    fun poll(input: Input) {
        val list = source.devices
        if (list !== seen || virtualChanged) { seen = list; virtualChanged = false; reconcile(list, input) }
        for (i in 0 until connected.size) connected[i].poll()
        for (i in leaving.size - 1 downTo 0) { val p = leaving[i]; p.poll(); if (--p.leavingFrames <= 0) leaving.removeAt(i) }
    }

    private fun holds(d: PadDevice): Boolean { for (i in 0 until connected.size) if (connected[i].device === d) return true; return false }

    private fun reconcile(list: List<PadDevice>, input: Input) {
        all.clear(); all.addAll(list); all.addAll(virtual)
        gone.clear(); arrived.clear()
        for (p in connected) if (all.none { it === p.device }) {
            // The same pad through another device (its hidraw node took over from evdev): it stays connected.
            val again = all.firstOrNull { it.deviceId == p.device.deviceId && !holds(it) }
            if (again != null) p.attach(again) else gone.add(p)
        }
        for (p in gone) { p.connected = false; connected.remove(p); p.leavingFrames = 2; leaving.add(p) }
        for (d in all) {
            if (holds(d)) continue
            val back = slots.indexOfFirst { it != null && !it.connected && it.device.deviceId == d.deviceId }
            val pad: Pad
            if (back >= 0) { pad = slots[back]!!; pad.attach(d); leaving.remove(pad) }
            else {
                // A free slot; else a new one (up to eight); else the slot of a pad gone longest ago.
                var slot = slots.indexOfFirst { it == null }
                if (slot < 0 && slots.size < KEPT_SLOTS) { slot = slots.size; slots.add(null) }
                if (slot < 0) slot = slots.indexOfFirst { it != null && !it.connected }
                if (slot < 0) { slot = slots.size; slots.add(null) }
                pad = Pad(slot, d); slots[slot] = pad
                if (input.padLights && d.deviceFeatures and Pad.F_LIGHTS != 0) {
                    pad.lightbar = Pad.slotColor(slot); pad.playerLeds = Pad.playerPattern(slot + 1)
                }
            }
            connected.add(pad); arrived.add(pad)
        }
        connected.sortBy { it.slot }
        for (p in gone) input.fire(p, false)
        for (p in arrived) input.fire(p, true)
    }

    fun anyDown(code: Int): Boolean { for (i in 0 until connected.size) if (connected[i].isDown(code)) return true; return false }
    fun anyPressed(code: Int): Boolean { for (i in 0 until connected.size) if (connected[i].pressed(code)) return true; return false }
    fun anyReleased(code: Int): Boolean {
        for (i in 0 until connected.size) if (connected[i].released(code)) return true
        for (i in 0 until leaving.size) if (leaving[i].released(code)) return true
        return false
    }
    fun anyWasDown(code: Int): Boolean {
        val b = code - Pad.BUTTON_BASE
        for (i in 0 until connected.size) if (connected[i].was and (1 shl b) != 0) return true
        for (i in 0 until leaving.size) if (leaving[i].was and (1 shl b) != 0) return true
        return false
    }

    /** The pad in [slot] if connected. */
    fun at(slot: Int): Pad? = slots.getOrNull(slot)?.takeIf { it.connected }

    private companion object {
        /** Slots kept for pads that went away, before a new pad may take one. */
        const val KEPT_SLOTS = 8
    }
}
