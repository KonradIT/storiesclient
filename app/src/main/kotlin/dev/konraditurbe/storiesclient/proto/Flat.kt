package dev.konraditurbe.storiesclient.proto

import dev.konraditurbe.storiesclient.util.inBounds
import dev.konraditurbe.storiesclient.util.le16
import dev.konraditurbe.storiesclient.util.le32
import dev.konraditurbe.storiesclient.util.le64

/**
 * Minimal hand-rolled FlatBuffers (1.12 wire format) reader + writer, little-endian. Only what the Stories
 * control/security messages need; no generated code, no flatbuffers dependency.
 *
 * Wire recap: a finished buffer starts with a u32 offset to the root table. A table starts with an i32
 * soffset to its vtable (`vtablePos = tablePos - soffset`). A vtable is `u16 vtableSize, u16 tableSize`,
 * then one u16 per field giving the field's byte offset within the table (0 = absent). Field `i` lives at
 * vtable slot `4 + 2*i`. Strings/vectors are u32 relative offsets to `[u32 len][data]`.
 */
object Flat {

    // ------------------------------------------------------------------------------------------ reader

    /**
     * Read-only view over one table of a finished FlatBuffer. Trusts the buffer (no bounds checks); use
     * [Fields] for defensive parsing of device-supplied responses.
     */
    class Reader private constructor(val buf: ByteArray, val tablePos: Int) {
        val vtablePos: Int = tablePos - buf.le32(tablePos)
        private val vtableSize: Int = buf.le16(vtablePos)

        /** Absolute position of field [i]'s data, or 0 if absent. */
        private fun fieldPos(i: Int): Int {
            val slot = 4 + 2 * i
            if (slot >= vtableSize) return 0
            val rel = buf.le16(vtablePos + slot)
            return if (rel == 0) 0 else tablePos + rel
        }

        fun has(field: Int): Boolean = fieldPos(field) != 0

        fun getBool(field: Int, def: Boolean): Boolean {
            val p = fieldPos(field); return if (p == 0) def else buf[p].toInt() != 0
        }

        fun getU8(field: Int, def: Int): Int {
            val p = fieldPos(field); return if (p == 0) def else buf[p].toInt() and 0xff
        }

        fun getU16(field: Int, def: Int): Int {
            val p = fieldPos(field); return if (p == 0) def else buf.le16(p)
        }

        fun getI32(field: Int, def: Int): Int {
            val p = fieldPos(field); return if (p == 0) def else buf.le32(p)
        }

        fun getI64(field: Int, def: Long): Long {
            val p = fieldPos(field); return if (p == 0) def else buf.le64(p)
        }

        /** String field, or null if absent. */
        fun getString(field: Int): String? {
            val p = fieldPos(field); if (p == 0) return null
            val strPos = p + buf.le32(p)
            val len = buf.le32(strPos)
            return String(buf, strPos + 4, len, Charsets.UTF_8)
        }

        /** `[ubyte]` vector field, or null if absent. */
        fun getByteVector(field: Int): ByteArray? {
            val p = fieldPos(field); if (p == 0) return null
            val vecPos = p + buf.le32(p)
            val len = buf.le32(vecPos)
            return buf.copyOfRange(vecPos + 4, vecPos + 4 + len)
        }

        /** Element count of a vector field (0 if absent). */
        fun vectorLength(field: Int): Int {
            val p = fieldPos(field); if (p == 0) return 0
            return buf.le32(p + buf.le32(p))
        }

        /** Reader over the [i]-th element of a vector-of-tables field, or null if the vector is absent. */
        fun getTableElement(field: Int, i: Int): Reader? {
            val p = fieldPos(field); if (p == 0) return null
            val slotPos = p + buf.le32(p) + 4 + i * 4
            return Reader(buf, slotPos + buf.le32(slotPos))
        }

        /** Reader over a nested-table field, or null if absent. */
        fun getTable(field: Int): Reader? {
            val p = fieldPos(field); if (p == 0) return null
            return Reader(buf, p + buf.le32(p))
        }

        companion object {
            /** View positioned at the root table (root offset is the leading u32). */
            fun root(buf: ByteArray): Reader = Reader(buf, buf.le32(0))

            /** View positioned at an arbitrary absolute table position. */
            fun at(buf: ByteArray, tablePos: Int): Reader = Reader(buf, tablePos)
        }
    }

    // ------------------------------------------------------------------------------- defensive reader

    /**
     * Bounds-checked field accessors for FlatBuffers received from the glasses. Every accessor takes an
     * absolute table position and returns a neutral value (0 / -1 / null) rather than throwing on a
     * malformed buffer. Response bodies carry the FlatBuffer after a 16-byte header, so callers typically
     * start with `root(msg, 16)`.
     */
    object Fields {
        /** Absolute root table position for a FlatBuffer starting at [base]. */
        fun root(b: ByteArray, base: Int): Int = base + b.le32(base)

        /** Absolute position of field [idx] in the table at [tablePos], or 0 if absent / out of bounds. */
        fun field(b: ByteArray, tablePos: Int, idx: Int): Int {
            if (!b.inBounds(tablePos, 4)) return 0
            val vt = tablePos - b.le32(tablePos)
            if (!b.inBounds(vt, 4)) return 0
            val slot = 4 + idx * 2
            if (slot >= b.le16(vt) || !b.inBounds(vt + slot, 2)) return 0
            val fo = b.le16(vt + slot)
            val p = tablePos + fo
            return if (fo == 0 || !b.inBounds(p, 1)) 0 else p
        }

        /** Resolve a u32 relative offset stored at [pos]. */
        fun follow(b: ByteArray, pos: Int): Int = pos + b.le32(pos)

        /** Nested table / vector position for an offset field, or 0 if absent. */
        fun indirect(b: ByteArray, tablePos: Int, idx: Int): Int {
            val p = field(b, tablePos, idx); return if (p == 0) 0 else follow(b, p)
        }

        fun u32(b: ByteArray, tablePos: Int, idx: Int): Int {
            val p = field(b, tablePos, idx); return if (p == 0) 0 else b.le32(p)
        }

        fun i64(b: ByteArray, tablePos: Int, idx: Int): Long {
            val p = field(b, tablePos, idx); return if (p == 0) 0 else b.le64(p)
        }

        fun float(b: ByteArray, tablePos: Int, idx: Int): Float {
            val p = field(b, tablePos, idx); return if (p == 0) 0f else Float.fromBits(b.le32(p))
        }

        /** Byte field, or -1 if absent (callers distinguish "absent" from a real 0). */
        fun byte(b: ByteArray, tablePos: Int, idx: Int): Int {
            val p = field(b, tablePos, idx); return if (p == 0) -1 else b[p].toInt() and 0xff
        }

        fun string(b: ByteArray, tablePos: Int, idx: Int): String? {
            val p = field(b, tablePos, idx); if (p == 0) return null
            val sp = follow(b, p)
            val ln = b.le32(sp)
            if (!b.inBounds(sp + 4, 0)) return null
            return String(b, sp + 4, minOf(ln, b.size - sp - 4).coerceAtLeast(0), Charsets.UTF_8)
        }

        /** First field of the table whose string value is a 32-char lowercase hex id (captureId/assetId). */
        fun firstHexId(b: ByteArray, tablePos: Int): String? {
            for (fi in 0 until 8) {
                val p = field(b, tablePos, fi); if (p == 0) continue
                runCatching {
                    val sp = follow(b, p)
                    if (b.le32(sp) == 32 && b.inBounds(sp + 4, 32)) {
                        val s = String(b, sp + 4, 32, Charsets.US_ASCII)
                        if (HEX32.matches(s)) return s
                    }
                }
            }
            return null
        }

        /** First length-prefixed byte vector in the root table (from [base]) longer than 64 bytes. */
        fun firstByteVector(b: ByteArray, base: Int): ByteArray? {
            val root = root(b, base)
            for (fi in 0 until 6) {
                val p = field(b, root, fi); if (p == 0) continue
                runCatching {
                    val vp = follow(b, p)
                    val ln = b.le32(vp)
                    if (ln > 64 && vp + 4 + ln <= b.size) return b.copyOfRange(vp + 4, vp + 4 + ln)
                }
            }
            return null
        }

        private val HEX32 = Regex("[0-9a-f]{32}")
    }

    // ------------------------------------------------------------------------------------------ writer

    /**
     * Back-to-front FlatBuffer builder following canonical builder semantics. The buffer fills from the high
     * end downward; the only exposed "position" is an offset measured from the END of the buffer. Every
     * stored reference is `referencePosition - targetOffset` in that space, which is exactly how the reader
     * resolves it.
     *
     * Usage: create strings/vectors first, then `startTable(n)`, `add*`, `endTable()`, `finish(root)`.
     */
    class Builder(initial: Int = 1024) {
        private var buf = ByteArray(maxOf(initial, 16))
        private var space = buf.size          // free bytes remaining at the low end
        private var minAlign = 1
        private var vtable = IntArray(0)      // per-field offset-from-end of the value (0 = unset)

        /** Current write head as an offset from the end of the buffer. */
        private fun off(): Int = buf.size - space

        private fun ensure(need: Int) {
            if (space >= need) return
            val used = buf.size - space
            var newLen = buf.size
            while (newLen - used < need) newLen *= 2
            val nb = ByteArray(newLen)
            buf.copyInto(nb, newLen - used, space, buf.size)
            buf = nb
            space = newLen - used
        }

        /** Insert alignment padding so a following [size]-byte block aligns to [align]. */
        private fun prep(size: Int, align: Int) {
            if (align > minAlign) minAlign = align
            val pad = ((off() + size).inv() + 1) and (align - 1)
            ensure(pad + size)
            repeat(pad) { buf[--space] = 0 }
        }

        private fun putByte(b: Int) { buf[--space] = b.toByte() }

        private fun putShort(v: Int) {
            buf[--space] = (v ushr 8).toByte(); buf[--space] = v.toByte()
        }

        private fun putInt(v: Int) {
            buf[--space] = (v ushr 24).toByte(); buf[--space] = (v ushr 16).toByte()
            buf[--space] = (v ushr 8).toByte(); buf[--space] = v.toByte()
        }

        private fun putLong(v: Long) {
            for (i in 7 downTo 0) buf[--space] = (v ushr (8 * i)).toByte()
        }

        private fun putReversed(data: ByteArray) {
            ensure(data.size)
            for (i in data.indices.reversed()) buf[--space] = data[i]
        }

        // ---- standalone objects (write BEFORE startTable) ----

        /** UTF-8 string `[u32 len][bytes][0]`; returns its offset-from-end. */
        fun createString(s: String): Int {
            val b = s.toByteArray(Charsets.UTF_8)
            prep(4, 4)
            putByte(0)
            putReversed(b)
            ensure(4); putInt(b.size)
            return off()
        }

        /** `[ubyte]` vector; returns its offset-from-end. */
        fun createByteVector(data: ByteArray): Int {
            prep(4, 4)
            putReversed(data)
            ensure(4); putInt(data.size)
            return off()
        }

        /** Vector of table/string offsets (each an offset-from-end); returns the vector's offset-from-end. */
        fun createOffsetVector(offsets: IntArray): Int {
            prep(4, 4 * offsets.size + 4)
            for (i in offsets.indices.reversed()) {
                ensure(4)
                val slotOff = off() + 4
                putInt(slotOff - offsets[i])
            }
            ensure(4); putInt(offsets.size)
            return off()
        }

        /** Vector of [count] inline structs whose LE-packed bytes are [elems] (4-aligned). */
        fun createStructVector(count: Int, elems: ByteArray): Int {
            prep(4, 4)
            putReversed(elems)
            ensure(4); putInt(count)
            return off()
        }

        // ---- table construction ----

        fun startTable(numFields: Int) { vtable = IntArray(numFields) }

        private fun slot(field: Int) {
            if (field >= vtable.size) vtable = vtable.copyOf(field + 1)
            vtable[field] = off()
        }

        fun addBool(field: Int, v: Boolean, def: Boolean) {
            if (v == def) return
            prep(1, 1); putByte(if (v) 1 else 0); slot(field)
        }

        fun addU8(field: Int, v: Int, def: Int) {
            if (v == def) return
            prep(1, 1); putByte(v and 0xff); slot(field)
        }

        fun addU16(field: Int, v: Int, def: Int) {
            if (v == def) return
            prep(2, 2); putShort(v and 0xffff); slot(field)
        }

        fun addI32(field: Int, v: Int, def: Int) {
            if (v == def) return
            prep(4, 4); putInt(v); slot(field)
        }

        fun addI64(field: Int, v: Long, def: Long) {
            if (v == def) return
            prep(8, 8); putLong(v); slot(field)
        }

        /** Offset field (string / vector / table) by its offset-from-end; 0 = omit. */
        fun addOffset(field: Int, valueOffset: Int) {
            if (valueOffset == 0) return
            prep(4, 4)
            ensure(4)
            val slotOff = off() + 4
            putInt(slotOff - valueOffset)
            slot(field)
        }

        /**
         * Finish the current table and return its offset-from-end. Writes a fresh vtable (trailing unused
         * fields trimmed) followed by the table's soffset slot.
         */
        fun endTable(): Int {
            ensure(4)
            putInt(0)                           // soffset placeholder, patched below
            val tableOff = off()
            val tableLen = tableOff
            val writtenFields = vtable.indexOfLast { it != 0 } + 1
            val vtableLen = (writtenFields + 2) * 2

            ensure(vtableLen)
            for (i in writtenFields - 1 downTo 0) {
                val v = vtable[i]
                putShort(if (v != 0) tableOff - v else 0)
            }
            putShort(tableLen)
            putShort(vtableLen)
            val vtableOff = off()
            // soffset = abs(table) - abs(vtable) = vtableOff - tableOff in offset-from-end space.
            writeI32At(tableOff, vtableOff - tableOff)
            return tableOff
        }

        private fun writeI32At(offsetFromEnd: Int, v: Int) {
            val pos = buf.size - offsetFromEnd
            buf[pos] = v.toByte(); buf[pos + 1] = (v ushr 8).toByte()
            buf[pos + 2] = (v ushr 16).toByte(); buf[pos + 3] = (v ushr 24).toByte()
        }

        /** Prepend the u32 root offset and return the exact bytes. */
        fun finish(rootTable: Int): ByteArray {
            prep(4, minAlign)
            ensure(4)
            putInt(0)
            val rootSlot = off()
            writeI32At(rootSlot, rootSlot - rootTable)
            return buf.copyOfRange(space, buf.size)
        }
    }
}
