package sillage.vk

import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.foreign.ValueLayout.*

/** C field kinds on LP64 Linux (x86-64 and AArch64). */
internal sealed class Field(val size: Long, val align: Long) {
    /** uint32_t, int32_t, enums, flags, VkBool32. */
    data object U32 : Field(4, 4)
    data object F32 : Field(4, 4)
    /** uint64_t, VkDeviceSize, size_t, non-dispatchable handles. */
    data object U64 : Field(8, 8)
    /** Pointers, strings and dispatchable handles. */
    data object Ptr : Field(8, 8)
    class Chars(n: Int) : Field(n.toLong(), 1)
    class Bytes(n: Int) : Field(n.toLong(), 1)
    class Nested(val struct: Struct) : Field(struct.size, struct.align)
    class Array(val element: Field, val count: Int) : Field(element.size * count, element.align)
}

/**
 * Layout of a C struct, computed with the C rules: each field at its natural alignment,
 * size rounded up to the largest alignment. Nested fields are reachable with dotted names
 * ("extent.width") and array items with indices ("srcOffsets[1].x").
 */
internal class Struct(val name: String, vararg fields: Pair<String, Field>, val sType: Int? = null) {
    val size: Long
    val align: Long
    val fields: List<Pair<String, Field>> = fields.toList()
    private val offsets = HashMap<String, Pair<Long, Field>>()

    init {
        var offset = 0L
        var maxAlign = 1L
        for ((n, f) in fields) {
            offset = (offset + f.align - 1) / f.align * f.align
            index(n, offset, f)
            offset += f.size
            maxAlign = maxOf(maxAlign, f.align)
        }
        align = maxAlign
        size = (offset + maxAlign - 1) / maxAlign * maxAlign
    }

    private fun index(n: String, offset: Long, f: Field) {
        offsets[n] = offset to f
        when (f) {
            is Field.Nested -> for ((sub, pair) in f.struct.offsets) offsets["$n.$sub"] = offset + pair.first to pair.second
            is Field.Array -> for (i in 0 until f.count) index("$n[$i]", offset + i * f.element.size, f.element)
            else -> {}
        }
    }

    fun offset(field: String): Long = entry(field).first
    fun field(field: String): Field = entry(field).second
    private fun entry(field: String) = offsets[field] ?: throw IllegalArgumentException("$name has no field '$field'")
    override fun toString() = "$name($size bytes)"
}

/** Writes fields into a struct already allocated at this segment. Ints go to U32, Floats to F32,
 * Longs to U64 or Ptr, segments to Ptr, Booleans to VkBool32. Returns the segment. */
internal fun MemorySegment.put(s: Struct, vararg values: Pair<String, Any?>): MemorySegment {
    for ((name, v) in values) {
        val o = s.offset(name)
        when (val f = s.field(name)) {
            Field.U32 -> set(JAVA_INT, o, when (v) { is Int -> v; is Boolean -> if (v) 1 else 0; else -> bad(s, name, v) })
            Field.F32 -> set(JAVA_FLOAT, o, v as? Float ?: bad(s, name, v))
            Field.U64 -> set(JAVA_LONG, o, v as? Long ?: bad(s, name, v))
            Field.Ptr -> set(ADDRESS, o, when (v) { null -> MemorySegment.NULL; is MemorySegment -> v; is Long -> MemorySegment.ofAddress(v); else -> bad(s, name, v) })
            else -> throw IllegalArgumentException("${s.name}.$name ($f) cannot be written as a single value")
        }
    }
    return this
}

private fun bad(s: Struct, name: String, v: Any?): Nothing =
    throw IllegalArgumentException("${s.name}.$name: unexpected value $v (${v?.javaClass?.simpleName})")

/** Allocates a zeroed struct, fills its sType when it has one, then the given fields. */
internal fun SegmentAllocator.struct(s: Struct, vararg values: Pair<String, Any?>): MemorySegment {
    val seg = allocate(s.size, s.align).fill(0)
    s.sType?.let { seg.set(JAVA_INT, 0, it) }
    return seg.put(s, *values)
}

/** Allocates a zeroed array of [count] structs, each with its sType set. */
internal fun SegmentAllocator.structs(s: Struct, count: Int): MemorySegment {
    val seg = allocate(s.size * count, s.align).fill(0)
    s.sType?.let { t -> for (i in 0 until count) seg.set(JAVA_INT, i * s.size, t) }
    return seg
}

internal fun MemorySegment.at(s: Struct, index: Int): MemorySegment = asSlice(index * s.size, s.size)
internal fun MemorySegment.u32(s: Struct, field: String): Int = get(JAVA_INT, s.offset(field))
internal fun MemorySegment.u64(s: Struct, field: String): Long = get(JAVA_LONG, s.offset(field))
internal fun MemorySegment.f32(s: Struct, field: String): Float = get(JAVA_FLOAT, s.offset(field))
internal fun MemorySegment.ptr(s: Struct, field: String): MemorySegment = get(ADDRESS, s.offset(field))
internal fun MemorySegment.chars(s: Struct, field: String): String {
    val f = s.field(field) as? Field.Chars ?: throw IllegalArgumentException("${s.name}.$field is not a char array")
    return asSlice(s.offset(field), f.size).getString(0)
}

/**
 * Bump allocator over one native block, like a C stack: allocations are zeroed, [frame] releases
 * everything allocated inside it. No native allocation after construction.
 */
internal class NativeStack(private val capacity: Long, arena: java.lang.foreign.Arena) : SegmentAllocator {
    private val base = arena.allocate(capacity, 16)
    @PublishedApi internal var top = 0L

    override fun allocate(byteSize: Long, byteAlignment: Long): MemorySegment {
        val start = (top + byteAlignment - 1) and (byteAlignment - 1).inv()
        check(start + byteSize <= capacity) { "NativeStack full ($capacity bytes)" }
        top = start + byteSize
        return base.asSlice(start, byteSize).fill(0)
    }

    inline fun <T> frame(block: (NativeStack) -> T): T {
        val mark = top
        try { return block(this) } finally { top = mark }
    }

    fun ints(vararg v: Int): MemorySegment = allocate(JAVA_INT, v.size.toLong()).also { s -> v.forEachIndexed { i, x -> s.setAtIndex(JAVA_INT, i.toLong(), x) } }
    fun longs(vararg v: Long): MemorySegment = allocate(JAVA_LONG, v.size.toLong()).also { s -> v.forEachIndexed { i, x -> s.setAtIndex(JAVA_LONG, i.toLong(), x) } }
    fun floats(vararg v: Float): MemorySegment = allocate(JAVA_FLOAT, v.size.toLong()).also { s -> v.forEachIndexed { i, x -> s.setAtIndex(JAVA_FLOAT, i.toLong(), x) } }
    fun pointers(vararg v: MemorySegment): MemorySegment = allocate(ADDRESS, v.size.toLong()).also { s -> v.forEachIndexed { i, x -> s.setAtIndex(ADDRESS, i.toLong(), x) } }
    fun strings(names: List<String>): MemorySegment = pointers(*names.map { allocateFrom(it) }.toTypedArray())
}
