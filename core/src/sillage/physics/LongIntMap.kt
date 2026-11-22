package sillage.physics

/** Open-addressing map from Long to Int (linear probing, mixed hash), reused without allocation. */
internal class LongIntMap(capacity: Int = 64) {
    private var keys = LongArray(0)
    private var values = IntArray(0)
    private var used = BooleanArray(0)
    private var mask = 0
    var size = 0; private set

    init { resize((maxOf(16, capacity) * 2 - 1).takeHighestOneBit()) }

    private fun resize(n: Int) {
        val oldK = keys; val oldV = values; val oldU = used
        keys = LongArray(n); values = IntArray(n); used = BooleanArray(n); mask = n - 1; size = 0
        for (i in oldU.indices) if (oldU[i]) put(oldK[i], oldV[i])
    }

    private fun slot(k: Long): Int {
        // Finaliseur de MurmurHash3 : mélange tous les bits, les clés (i << 32 | j) ne se collisionnent plus.
        var h = k
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return h.toInt() and mask
    }

    fun clear() { if (size > 0) { used.fill(false); size = 0 } }

    /** Stores [v] for [k] unless [k] is already present. */
    fun putIfAbsent(k: Long, v: Int) {
        if ((size + 1) * 2 > keys.size) resize(keys.size * 2)
        var i = slot(k)
        while (used[i]) { if (keys[i] == k) return; i = (i + 1) and mask }
        used[i] = true; keys[i] = k; values[i] = v; size++
    }

    private fun put(k: Long, v: Int) { putIfAbsent(k, v) }

    /** Value for [k], or -1. */
    fun get(k: Long): Int {
        var i = slot(k)
        while (used[i]) { if (keys[i] == k) return values[i]; i = (i + 1) and mask }
        return -1
    }
}
