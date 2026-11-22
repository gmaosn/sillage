package sillage.input

import sillage.platform.Posix
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Une manette virtuelle du vrai noyau, par /dev/uinput : ses boutons et axes, son retour de force (le
 * fil de service répond aux téléversements d'effets et note les ordres de jeu). Elle part neutre et est
 * détruite par [close] ; les essais ne la gardent que quelques secondes.
 */
internal class Uinput(val name: String, vendor: Int, product: Int, bus: Int, keys: IntArray, axes: List<Axis>, ff: Boolean) : AutoCloseable {
    class Axis(val code: Int, val min: Int, val max: Int, val value: Int = 0)
    /** Ce que le noyau lui a demandé : effets téléversés (fort, faible, durée en ms) et ordres (numéro d'effet, nombre de fois). */
    val uploads = CopyOnWriteArrayList<IntArray>()
    val plays = CopyOnWriteArrayList<IntArray>()
    val erases = CopyOnWriteArrayList<Int>()
    val node: String
    private val arena = Arena.ofShared()
    private val px = Posix(arena)
    private val fd: Int
    private val ev = arena.allocate(24, 8)
    @Volatile private var running = true
    private var service: Thread? = null

    init {
        fd = px.open("/dev/uinput", Posix.O_RDWR or Posix.O_NONBLOCK or Posix.O_CLOEXEC)
        check(fd >= 0) { "/dev/uinput : ${Posix.describe(-fd)}" }
        try {
            fun ok(r: Int, what: String) = check(r >= 0) { "$what : ${Posix.describe(-r)}" }
            ok(px.ioctl(fd, UI_SET_EVBIT, EV_KEY), "EV_KEY")
            for (k in keys) ok(px.ioctl(fd, UI_SET_KEYBIT, k), "touche $k")
            ok(px.ioctl(fd, UI_SET_EVBIT, EV_ABS), "EV_ABS")
            val a = arena.allocate(28, 8)
            for (x in axes) {
                ok(px.ioctl(fd, UI_SET_ABSBIT, x.code), "axe ${x.code}")
                a.fill(0); a.set(JAVA_SHORT, 0, x.code.toShort()); a.set(JAVA_INT, 4, x.value); a.set(JAVA_INT, 8, x.min); a.set(JAVA_INT, 12, x.max)
                ok(px.ioctl(fd, UI_ABS_SETUP, a), "réglage de l'axe ${x.code}")
            }
            if (ff) { ok(px.ioctl(fd, UI_SET_EVBIT, EV_FF), "EV_FF"); ok(px.ioctl(fd, UI_SET_FFBIT, FF_RUMBLE), "FF_RUMBLE") }
            val s = arena.allocate(92, 8)
            s.set(JAVA_SHORT, 0, bus.toShort()); s.set(JAVA_SHORT, 2, vendor.toShort()); s.set(JAVA_SHORT, 4, product.toShort()); s.set(JAVA_SHORT, 6, 1)
            val n = name.toByteArray(); MemorySegment.copy(n, 0, s, JAVA_BYTE, 8, minOf(n.size, 79))
            s.set(JAVA_INT, 88, if (ff) 4 else 0)
            ok(px.ioctl(fd, UI_DEV_SETUP, s), "UI_DEV_SETUP")
            ok(px.ioctl(fd, UI_DEV_CREATE, 0), "UI_DEV_CREATE")
            val sys = arena.allocate(64, 8)
            ok(px.ioctl(fd, Posix.ior('U', 44, 64), sys), "UI_GET_SYSNAME")
            val dir = Path.of("/sys/class/input", sys.getString(0))
            node = "/dev/input/" + Files.list(dir).use { st -> st.map { it.fileName.toString() }.filter { it.startsWith("event") }.findFirst().orElseThrow() }
            if (ff) service = Thread(::serve, "uinput-ff").apply { isDaemon = true; start() }
        } catch (e: Throwable) { px.ioctl(fd, UI_DEV_DESTROY, 0); px.close(fd); arena.close(); throw e }
    }

    private fun emit(type: Int, code: Int, value: Int) {
        synchronized(this) {
            ev.fill(0); ev.set(JAVA_SHORT, 16, type.toShort()); ev.set(JAVA_SHORT, 18, code.toShort()); ev.set(JAVA_INT, 20, value)
            check(px.write(fd, ev, 24) == 24)
        }
    }

    fun key(code: Int, down: Boolean) { emit(EV_KEY, code, if (down) 1 else 0); emit(0, 0, 0) }
    fun abs(code: Int, value: Int) { emit(EV_ABS, code, value); emit(0, 0, 0) }

    /** Répond au noyau : téléversements et effacements d'effets ; note les ordres de jeu. */
    private fun serve() {
        Arena.ofConfined().use { local ->
            val p = Posix(local)
            val buf = local.allocate(24 * 16, 8)
            val up = local.allocate(104, 8); val er = local.allocate(12, 8)
            while (running) {
                if (p.waitReadable(fd, -1, 20) and Posix.READABLE == 0) continue
                val n = p.read(fd, buf, 24 * 16)
                if (n <= 0) continue
                for (i in 0 until n / 24) {
                    val at = i * 24L
                    val type = buf.get(JAVA_SHORT, at + 16).toInt() and 0xFFFF; val code = buf.get(JAVA_SHORT, at + 18).toInt() and 0xFFFF
                    val value = buf.get(JAVA_INT, at + 20)
                    when {
                        type == EV_UINPUT && code == UI_FF_UPLOAD -> {
                            up.fill(0); up.set(JAVA_INT, 0, value)
                            if (p.ioctl(fd, UI_BEGIN_FF_UPLOAD, up) < 0) continue
                            // L'effet commence à l'octet 8 : type, numéro, …, durée (10), fort (16), faible (18).
                            uploads.add(intArrayOf(up.get(JAVA_SHORT, 8 + 16).toInt() and 0xFFFF, up.get(JAVA_SHORT, 8 + 18).toInt() and 0xFFFF,
                                up.get(JAVA_SHORT, 8 + 10).toInt() and 0xFFFF, up.get(JAVA_SHORT, 8 + 0).toInt() and 0xFFFF))
                            up.set(JAVA_INT, 4, 0)
                            p.ioctl(fd, UI_END_FF_UPLOAD, up)
                        }
                        type == EV_UINPUT && code == UI_FF_ERASE -> {
                            er.fill(0); er.set(JAVA_INT, 0, value)
                            if (p.ioctl(fd, UI_BEGIN_FF_ERASE, er) < 0) continue
                            erases.add(er.get(JAVA_INT, 8))
                            er.set(JAVA_INT, 4, 0)
                            p.ioctl(fd, UI_END_FF_ERASE, er)
                        }
                        type == EV_FF -> plays.add(intArrayOf(code, value))
                    }
                }
            }
        }
    }

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        running = false
        service?.join(1000)
        px.ioctl(fd, UI_DEV_DESTROY, 0)
        px.close(fd)
        arena.close()
    }

    companion object {
        const val EV_KEY = 1; const val EV_ABS = 3; const val EV_FF = 0x15; const val EV_UINPUT = 0x0101
        const val UI_FF_UPLOAD = 1; const val UI_FF_ERASE = 2; const val FF_RUMBLE = 0x50
        const val BUS_USB = 3; const val BUS_VIRTUAL = 6
        val UI_SET_EVBIT = Posix.iow('U', 100, 4); val UI_SET_KEYBIT = Posix.iow('U', 101, 4); val UI_SET_ABSBIT = Posix.iow('U', 103, 4)
        val UI_SET_FFBIT = Posix.iow('U', 107, 4)
        val UI_DEV_CREATE = Posix.io('U', 1); val UI_DEV_DESTROY = Posix.io('U', 2)
        val UI_DEV_SETUP = Posix.iow('U', 3, 92); val UI_ABS_SETUP = Posix.iow('U', 4, 28)
        val UI_BEGIN_FF_UPLOAD = Posix.iowr('U', 200, 104); val UI_END_FF_UPLOAD = Posix.iow('U', 201, 104)
        val UI_BEGIN_FF_ERASE = Posix.iowr('U', 202, 12); val UI_END_FF_ERASE = Posix.iow('U', 203, 12)

        /**
         * True if the round trips through the real kernel may run: /dev/uinput writable, and either nothing
         * on the desktop that would adopt a new pad (Steam, a game under Wine) or SILLAGE_UINPUT=1. With
         * SILLAGE_UINPUT=0 they never run.
         */
        fun allowed(): Boolean {
            if (System.getenv("SILLAGE_UINPUT") == "0") return false
            if (!Files.isWritable(Path.of("/dev/uinput"))) { println("SKIP uinput : /dev/uinput n'est pas accessible en écriture"); return false }
            if (System.getenv("SILLAGE_UINPUT") == "1") return true
            val busy = runCatching {
                ProcessHandle.allProcesses().anyMatch { h -> h.info().command().map { it.endsWith("/steam") || it.endsWith("wineserver") }.orElse(false) }
            }.getOrDefault(true)
            if (busy) println("SKIP uinput : Steam ou un jeu sous Wine tourne et adopterait la manette virtuelle (SILLAGE_UINPUT=1 pour forcer)")
            return !busy
        }
    }
}
