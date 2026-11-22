package sillage.input

import sillage.platform.DeviceIo
import sillage.platform.DeviceIoFactory
import sillage.platform.Posix
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Des périphériques imités pour [sillage.platform.SystemPads] : chaque nœud a sa file de rapports à
 * lire, ses rapports de fonction, et garde ce qu'on lui écrit. Un arbre sysfs et /dev de fichiers
 * ordinaires (dans build/tmp) va avec : l'inotify du vrai noyau y voit les nœuds apparaître.
 */
internal class FakeIo(val root: Path) : DeviceIo, DeviceIoFactory {
    val sys: Path = root.resolve("sys")
    val dev: Path = root.resolve("dev")

    inner class Node(val path: String) {
        val reports = ConcurrentLinkedQueue<ByteArray>()
        val features = ConcurrentHashMap<Int, ByteArray>()
        val written = CopyOnWriteArrayList<ByteArray>()
        @Volatile var gone = false
        @Volatile var denied = false
        @Volatile var opened = 0
    }

    val nodes = ConcurrentHashMap<String, Node>()
    // Par numéro de descripteur, sans clés en boîte : le fil de lecture n'alloue rien, l'imitation non plus.
    private val fds = java.util.concurrent.atomic.AtomicReferenceArray<Node?>(4096)
    private val wakes = java.util.concurrent.atomic.AtomicIntegerArray(4096)
    private val wakeValid = java.util.concurrent.atomic.AtomicIntegerArray(4096)
    private val next = AtomicInteger(100)

    init { Files.createDirectories(sys.resolve("class/hidraw")); Files.createDirectories(sys.resolve("class/input")); Files.createDirectories(dev.resolve("input")) }

    override fun create(arena: Arena): DeviceIo = this

    /**
     * Branche une DualSense : son dossier HID dans sysfs ([bus] 3 USB, 5 Bluetooth), le lien du nœud
     * hidraw, puis le nœud dans /dev (en dernier : c'est lui que l'inotify voit).
     */
    fun plugDualSense(name: String, bus: Int, mac: String, product: Int = 0x0CE6, driver: String = "playstation", setup: Node.() -> Unit = {}): Node {
        val hid = sys.resolve("devices/fake/%04X:054C:%04X.%04X".format(bus, product, next.incrementAndGet()))
        Files.createDirectories(hid)
        Files.writeString(hid.resolve("uevent"), "DRIVER=$driver\nHID_ID=%04X:0000054C:%08X\nHID_NAME=DualSense Wireless Controller\nHID_PHYS=fake\nHID_UNIQ=$mac\n".format(bus, product))
        val link = sys.resolve("class/hidraw/$name")
        Files.createDirectories(link)
        Files.createSymbolicLink(link.resolve("device"), hid)
        val node = Node(dev.resolve(name).toString())
        node.setup()
        nodes[node.path] = node
        Files.createFile(dev.resolve(name))
        return node
    }

    /** Débranche : les lectures échouent (ENODEV), le nœud et son entrée sysfs disparaissent. */
    fun unplug(node: Node) {
        node.gone = true
        val name = Path.of(node.path).fileName.toString()
        Files.deleteIfExists(dev.resolve(name))
        val link = sys.resolve("class/hidraw/$name")
        Files.deleteIfExists(link.resolve("device")); Files.deleteIfExists(link)
        nodes.remove(node.path)
    }

    override fun open(path: String, flags: Int): Int {
        val n = nodes[path] ?: return -Posix.ENOENT
        if (n.denied) return -Posix.EACCES
        if (n.gone) return -Posix.ENODEV
        val fd = next.incrementAndGet(); fds.set(fd, n); n.opened++
        return fd
    }

    override fun close(fd: Int) { if (fd in 0 until 4096) { fds.set(fd, null); wakeValid.set(fd, 0) } }

    override fun read(fd: Int, buf: MemorySegment, max: Int): Int {
        val n = fds.get(fd) ?: return -9
        if (n.gone) return -Posix.ENODEV
        val r = n.reports.poll() ?: return -Posix.EAGAIN
        val len = minOf(r.size, max)
        MemorySegment.copy(r, 0, buf, JAVA_BYTE, 0, len)
        return len
    }

    override fun write(fd: Int, buf: MemorySegment, len: Int): Int {
        val n = fds.get(fd) ?: return -9
        if (n.gone) return -Posix.ENODEV
        val b = ByteArray(len); MemorySegment.copy(buf, JAVA_BYTE, 0, b, 0, len)
        n.written.add(b)
        return len
    }

    override fun ioctl(fd: Int, request: Long, arg: MemorySegment): Int {
        val n = fds.get(fd) ?: return -9
        val type = ((request shr 8) and 0xFF).toInt(); val nr = (request and 0xFF).toInt(); val size = ((request shr 16) and 0x3FFF).toInt()
        if (type == 'H'.code && nr == 0x07) {                   // HIDIOCGFEATURE
            val id = arg.get(JAVA_BYTE, 0).toInt() and 0xFF
            val f = n.features[id] ?: return -32                 // EPIPE, comme une manette qui ne l'a pas
            val len = minOf(f.size, size)
            MemorySegment.copy(f, 0, arg, JAVA_BYTE, 0, len)
            return len
        }
        return -Posix.ENOTTY
    }

    override fun ioctl(fd: Int, request: Long, arg: Int): Int = -Posix.ENOTTY

    override fun waitReadable(fd: Int, wake: Int, timeoutMs: Int): Int {
        val n = fds.get(fd) ?: return Posix.HANGUP
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            var r = 0
            if (n.gone) r = r or Posix.HANGUP or Posix.READABLE
            if (!n.reports.isEmpty()) r = r or Posix.READABLE
            if (wake >= 0 && wakes.get(wake) > 0) r = r or Posix.WOKEN
            if (r != 0 || System.nanoTime() >= end) return r
            Thread.sleep(1)
        }
    }

    override fun eventfd(): Int { val id = next.incrementAndGet(); wakeValid.set(id, 1); return id }
    override fun signal(efd: Int) { if (efd >= 0 && wakeValid.get(efd) == 1) wakes.incrementAndGet(efd) }
    override fun drain(efd: Int) { if (efd >= 0) wakes.set(efd, 0) }
}
