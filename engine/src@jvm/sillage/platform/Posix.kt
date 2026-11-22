package sillage.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle

/**
 * The few libc calls the controllers need (open, read, write, ioctl, poll, inotify, eventfd), through
 * FFM, with errno captured. One [Posix] per thread: it holds that thread's errno slot and scratch memory.
 * Calls return what libc returns, or −errno on failure, so a caller never reads errno separately.
 *
 * Linux numbers, the same on x86-64 and ARM64 (both use the generic ioctl encoding and 64-bit time).
 *
 * Each call is made first without capturing errno: a capturing downcall allocates a little on every call
 * (40 bytes, even compiled), which the readers, called hundreds of times a second, must not. Only when
 * that call fails is it made again with errno captured, to learn why. Every call here may be repeated so
 * (a failed read read nothing, a failed write wrote nothing, the ioctls read or replace a whole state).
 */
internal class Posix(arena: Arena) : DeviceIo {
    private val state: MemorySegment = arena.allocate(STATE)
    private val pollFds: MemorySegment = arena.allocate(16, 8)
    private val path: MemorySegment = arena.allocate(512, 8)
    private val eight: MemorySegment = arena.allocate(8, 8)

    private fun fail(r: Int): Int = if (r >= 0) r else -(ERRNO.get(state, 0L) as Int)
    private fun fail(r: Long): Long = if (r >= 0) r else -(ERRNO.get(state, 0L) as Int).toLong()

    /** open(2); [path] in UTF-8. */
    override fun open(path: String, flags: Int): Int {
        val bytes = path.toByteArray()
        require(bytes.size < 511) { "Path too long: $path" }
        MemorySegment.copy(bytes, 0, this.path, JAVA_BYTE, 0, bytes.size); this.path.set(JAVA_BYTE, bytes.size.toLong(), 0)
        while (true) {
            val r0 = OPEN0.invokeExact(fOpen, this.path, flags, 0) as Int
            if (r0 >= 0) return r0
            val r = fail(OPEN.invokeExact(fOpen, state, this.path, flags, 0) as Int)
            if (r != -EINTR) return r
        }
    }

    override fun close(fd: Int) { if (fd >= 0) CLOSE.invokeExact(fClose, fd) as Int }

    /** read(2) of at most [max] bytes into [buf]. */
    override fun read(fd: Int, buf: MemorySegment, max: Int): Int {
        while (true) {
            val r0 = (READ0.invokeExact(fRead, fd, buf, max.toLong()) as Long).toInt()
            if (r0 >= 0) return r0
            val r = fail(READ.invokeExact(fRead, state, fd, buf, max.toLong()) as Long).toInt()
            if (r != -EINTR) return r
        }
    }

    override fun write(fd: Int, buf: MemorySegment, len: Int): Int {
        while (true) {
            val r0 = (WRITE0.invokeExact(fWrite, fd, buf, len.toLong()) as Long).toInt()
            if (r0 >= 0) return r0
            val r = fail(WRITE.invokeExact(fWrite, state, fd, buf, len.toLong()) as Long).toInt()
            if (r != -EINTR) return r
        }
    }

    /** ioctl(2) with a pointer argument. */
    override fun ioctl(fd: Int, request: Long, arg: MemorySegment): Int {
        while (true) {
            val r0 = IOCTL_P0.invokeExact(fIoctl, fd, request, arg) as Int
            if (r0 >= 0) return r0
            val r = fail(IOCTL_P.invokeExact(fIoctl, state, fd, request, arg) as Int)
            if (r != -EINTR) return r
        }
    }

    /** ioctl(2) with an integer argument (or none: pass 0). */
    override fun ioctl(fd: Int, request: Long, arg: Int): Int {
        while (true) {
            val r0 = IOCTL_I0.invokeExact(fIoctl, fd, request, arg) as Int
            if (r0 >= 0) return r0
            val r = fail(IOCTL_I.invokeExact(fIoctl, state, fd, request, arg) as Int)
            if (r != -EINTR) return r
        }
    }

    /**
     * Waits until [fd] can be read or [wake] (an eventfd, or −1) was signalled, at most [timeoutMs]
     * (−1: no limit). Returns [READABLE] and/or [WOKEN] bits, [HANGUP] when the device is gone, 0 on timeout.
     */
    override fun waitReadable(fd: Int, wake: Int, timeoutMs: Int): Int {
        pollFds.set(JAVA_INT, 0, fd); pollFds.set(JAVA_SHORT, 4, POLLIN.toShort()); pollFds.set(JAVA_SHORT, 6, 0)
        pollFds.set(JAVA_INT, 8, wake); pollFds.set(JAVA_SHORT, 12, POLLIN.toShort()); pollFds.set(JAVA_SHORT, 14, 0)
        val count = if (wake >= 0) 2L else 1L
        val n0 = POLL0.invokeExact(fPoll, pollFds, count, timeoutMs) as Int
        val n = if (n0 >= 0) n0 else fail(POLL.invokeExact(fPoll, state, pollFds, count, 0) as Int)
        if (n == -EINTR) return 0
        if (n < 0) return n
        if (n == 0) return 0
        val a = pollFds.get(JAVA_SHORT, 6).toInt(); val b = if (wake >= 0) pollFds.get(JAVA_SHORT, 14).toInt() else 0
        var r = 0
        if (a and POLLIN != 0) r = r or READABLE
        if (a and (POLLERR or POLLHUP or POLLNVAL) != 0) r = r or HANGUP
        if (b and POLLIN != 0) r = r or WOKEN
        return r
    }

    /** A non-blocking eventfd, to wake a thread waiting in [waitReadable]. */
    override fun eventfd(): Int = fail(EVENTFD.invokeExact(fEventfd, state, 0, O_NONBLOCK or O_CLOEXEC) as Int)

    /** Signals an eventfd. */
    override fun signal(efd: Int) { eight.set(JAVA_LONG, 0, 1L); write(efd, eight, 8) }

    /** Clears an eventfd's count. */
    override fun drain(efd: Int) { read(efd, eight, 8) }

    fun inotifyInit(): Int = fail(INOTIFY_INIT.invokeExact(fInotifyInit, state, O_NONBLOCK or O_CLOEXEC) as Int)

    fun inotifyWatch(fd: Int, dir: String, mask: Int): Int {
        val bytes = dir.toByteArray()
        require(bytes.size < 511)
        MemorySegment.copy(bytes, 0, path, JAVA_BYTE, 0, bytes.size); path.set(JAVA_BYTE, bytes.size.toLong(), 0)
        return fail(INOTIFY_ADD.invokeExact(fInotifyAdd, state, fd, path, mask) as Int)
    }

    companion object {
        const val O_RDONLY = 0; const val O_WRONLY = 1; const val O_RDWR = 2
        const val O_NONBLOCK = 0x800; const val O_CLOEXEC = 0x80000
        const val EPERM = 1; const val ENOENT = 2; const val EINTR = 4; const val EIO = 5; const val ENXIO = 6
        const val EAGAIN = 11; const val EACCES = 13; const val EBUSY = 16; const val ENODEV = 19; const val EINVAL = 22
        const val ENOTTY = 25; const val ENOSYS = 38
        private const val POLLIN = 1; private const val POLLERR = 8; private const val POLLHUP = 16; private const val POLLNVAL = 32
        const val READABLE = 1; const val WOKEN = 2; const val HANGUP = 4
        const val IN_ATTRIB = 0x4; const val IN_CREATE = 0x100; const val IN_DELETE = 0x200
        const val IN_MOVED_FROM = 0x40; const val IN_MOVED_TO = 0x80

        // ioctl numbers: _IOC(dir, type, nr, size) = dir << 30 | size << 16 | type << 8 | nr.
        private fun ioc(dir: Int, type: Char, nr: Int, size: Int): Long =
            (dir.toLong() shl 30) or (size.toLong() shl 16) or (type.code.toLong() shl 8) or nr.toLong()
        fun io(type: Char, nr: Int) = ioc(0, type, nr, 0)
        fun ior(type: Char, nr: Int, size: Int) = ioc(2, type, nr, size)
        fun iow(type: Char, nr: Int, size: Int) = ioc(1, type, nr, size)
        fun iowr(type: Char, nr: Int, size: Int) = ioc(3, type, nr, size)

        /** Human words for an errno, for the messages printed to the player. */
        fun describe(errno: Int): String = when (errno) {
            EACCES, EPERM -> "permission denied"
            ENOENT -> "no such file"
            ENODEV, ENXIO -> "device gone"
            EBUSY -> "busy"
            EIO -> "input/output error"
            ENOTTY, EINVAL -> "not supported by the device"
            else -> "error $errno"
        }

        private val linker: Linker = Linker.nativeLinker()
        private val libc = linker.defaultLookup()
        private fun fn(name: String): MemorySegment = libc.find(name).orElseThrow { IllegalStateException("Missing libc symbol $name") }
        private val capture = Linker.Option.captureCallState("errno")
        private val STATE = Linker.Option.captureStateLayout()
        private val ERRNO: VarHandle = STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"))
        private fun h(ret: MemoryLayout, vararg args: MemoryLayout): MethodHandle =
            linker.downcallHandle(FunctionDescriptor.of(ret, *args), capture)
        private fun hv(firstVariadic: Int, ret: MemoryLayout, vararg args: MemoryLayout): MethodHandle =
            linker.downcallHandle(FunctionDescriptor.of(ret, *args), capture, Linker.Option.firstVariadicArg(firstVariadic))

        private val fOpen = fn("open"); private val fClose = fn("close"); private val fRead = fn("read"); private val fWrite = fn("write")
        private val fIoctl = fn("ioctl"); private val fPoll = fn("poll"); private val fEventfd = fn("eventfd")
        private val fInotifyInit = fn("inotify_init1"); private val fInotifyAdd = fn("inotify_add_watch")
        // Handles take the function's address first, then the errno slot, then the arguments.
        @JvmField val OPEN: MethodHandle = hv(2, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)          // open(path, flags, mode...)
        @JvmField val CLOSE: MethodHandle = linker.downcallHandle(FunctionDescriptor.of(JAVA_INT, JAVA_INT))
        @JvmField val READ: MethodHandle = h(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)
        @JvmField val WRITE: MethodHandle = h(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)
        @JvmField val IOCTL_P: MethodHandle = hv(2, JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS)     // ioctl(fd, request, ...)
        @JvmField val IOCTL_I: MethodHandle = hv(2, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT)
        @JvmField val POLL: MethodHandle = h(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT)
        @JvmField val EVENTFD: MethodHandle = h(JAVA_INT, JAVA_INT, JAVA_INT)
        @JvmField val INOTIFY_INIT: MethodHandle = h(JAVA_INT, JAVA_INT)
        @JvmField val INOTIFY_ADD: MethodHandle = h(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        // The same without errno, tried first.
        private fun p(ret: MemoryLayout, vararg args: MemoryLayout): MethodHandle = linker.downcallHandle(FunctionDescriptor.of(ret, *args))
        private fun pv(firstVariadic: Int, ret: MemoryLayout, vararg args: MemoryLayout): MethodHandle =
            linker.downcallHandle(FunctionDescriptor.of(ret, *args), Linker.Option.firstVariadicArg(firstVariadic))
        @JvmField val OPEN0: MethodHandle = pv(2, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)
        @JvmField val READ0: MethodHandle = p(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)
        @JvmField val WRITE0: MethodHandle = p(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)
        @JvmField val IOCTL_P0: MethodHandle = pv(2, JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS)
        @JvmField val IOCTL_I0: MethodHandle = pv(2, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT)
        @JvmField val POLL0: MethodHandle = p(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT)
    }
}
