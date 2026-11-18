package sillage.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

/** Downcall shapes for the window code; same scheme as sillage.vk.Sig (address passed first). */
internal object PSig {
    val linker: Linker = Linker.nativeLinker()
    private fun h(ret: MemoryLayout?, vararg args: MemoryLayout): MethodHandle = linker.downcallHandle(
        if (ret == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(ret, *args))
    private val P = ADDRESS; private val I = JAVA_INT; private val J = JAVA_LONG
    private val S = JAVA_SHORT; private val B = JAVA_BYTE

    @JvmField val P_P = h(P, P)
    @JvmField val P_PP = h(P, P, P)
    @JvmField val V_P = h(null, P)
    @JvmField val I_P = h(I, P)
    @JvmField val I_I = h(I, I)
    @JvmField val I_PJI = h(I, P, J, I)
    @JvmField val I_PPPP = h(I, P, P, P, P)
    @JvmField val P_PIPIIP = h(P, P, I, P, I, I, P)
    // XCB
    @JvmField val I_PI = h(I, P, I)
    @JvmField val P_PIP = h(P, P, I, P)
    @JvmField val I_PBSP = h(I, P, B, S, P)
    @JvmField val I_PIIP = h(I, P, I, I, P)
    @JvmField val I_PBIIIBIP = h(I, P, B, I, I, I, B, I, P)
    @JvmField val I_PBIISSSSSSIIP = h(I, P, B, I, I, S, S, S, S, S, S, I, I, P)
    @JvmField val I_PIISSSSSS = h(I, P, I, I, S, S, S, S, S, S)
    @JvmField val I_PBIISS = h(I, P, B, I, I, S, S)
    @JvmField val I_PIIISSSSSSSS = h(I, P, I, I, I, S, S, S, S, S, S, S, S)
}

internal object Native {
    fun library(vararg names: String): SymbolLookup =
        names.firstNotNullOfOrNull { n -> runCatching { SymbolLookup.libraryLookup(n, Arena.global()) }.getOrNull() }
            ?: throw IllegalStateException("Bibliothèque système introuvable : ${names.joinToString()}")

    fun SymbolLookup.fn(name: String): MemorySegment = find(name).orElseThrow { IllegalStateException("Symbole $name absent") }

    private val libc = PSig.linker.defaultLookup()
    private val pPoll = libc.fn("poll")
    private val pClose = libc.fn("close")
    private val pFree = libc.fn("free")

    /** poll(2) on one descriptor; returns the revents bits (0 on timeout). */
    fun pollIn(fd: Int, timeoutMs: Int, scratch: MemorySegment): Int {
        scratch.set(JAVA_INT, 0, fd); scratch.set(JAVA_SHORT, 4, 1 /* POLLIN */); scratch.set(JAVA_SHORT, 6, 0)
        val n = PSig.I_PJI.invokeExact(pPoll, scratch, 1L, timeoutMs) as Int
        return if (n > 0) scratch.get(JAVA_SHORT, 6).toInt() else 0
    }
    fun close(fd: Int) { PSig.I_I.invokeExact(pClose, fd) as Int }
    fun free(p: MemorySegment) { PSig.V_P.invokeExact(pFree, p) }

    /** Reads a NUL-terminated C string at [p]. */
    fun string(p: MemorySegment): String = if (p == MemorySegment.NULL) "" else p.reinterpret(Long.MAX_VALUE).getString(0)
}
