package dev.konraditurbe.storiesclient.proto

import dev.konraditurbe.storiesclient.util.hexToBytes
import dev.konraditurbe.storiesclient.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatTest {

    /** Golden buffer produced by the original Java builder for this exact construction sequence. */
    private val golden = ("1f000000000000000000000000000010004d00180015000d0009000500040010000000011400000033000000" +
        "fbffffffffffffff3412000702000000100000000c000000080020000800040008000000140000002a000000" +
        "0000000500000001020304050300000061626300").hexToBytes()

    private fun build(): ByteArray {
        val b = Flat.Builder(64)
        val s1 = b.createString("abc")
        val v1 = b.createByteVector(byteArrayOf(1, 2, 3, 4, 5))
        b.startTable(2); b.addI32(0, 42, 0); b.addOffset(1, s1); val inner = b.endTable()
        val ov = b.createOffsetVector(intArrayOf(inner, inner))
        b.startTable(6)
        b.addU8(0, 7, 0); b.addU16(1, 0x1234, 0); b.addI64(2, -5L, 0)
        b.addOffset(3, v1); b.addOffset(4, ov); b.addBool(5, true, false)
        return b.finish(b.endTable())
    }

    @Test
    fun builderMatchesGoldenBytes() {
        assertEquals(golden.toHex(), build().toHex())
    }

    @Test
    fun readerRoundTrip() {
        val r = Flat.Reader.root(build())
        assertEquals(7, r.getU8(0, 0))
        assertEquals(0x1234, r.getU16(1, 0))
        assertEquals(-5L, r.getI64(2, 0))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), r.getByteVector(3))
        assertEquals(2, r.vectorLength(4))
        assertTrue(r.getBool(5, false))
        val inner = r.getTableElement(4, 1)
        assertNotNull(inner)
        assertEquals(42, inner!!.getI32(0, 0))
        assertEquals("abc", inner.getString(1))
        assertEquals(false, r.has(9))
    }

    @Test
    fun defensiveFieldsAgreeWithReader() {
        val buf = build()
        val root = Flat.Fields.root(buf, 0)
        assertEquals(7, Flat.Fields.byte(buf, root, 0))
        assertEquals(-5L, Flat.Fields.i64(buf, root, 2))
        assertEquals(-1, Flat.Fields.byte(buf, root, 9))        // absent -> -1
        assertEquals(0, Flat.Fields.field(buf, root, 40))       // beyond vtable -> 0
        val vec = Flat.Fields.indirect(buf, root, 4)
        val elem = Flat.Fields.follow(buf, vec + 4)
        assertEquals("abc", Flat.Fields.string(buf, elem, 1))
    }

    @Test
    fun defensiveFieldsSurviveGarbage() {
        val junk = ByteArray(8) { 0x7f }
        assertEquals(0, Flat.Fields.field(junk, Flat.Fields.root(junk, 0), 0))
        assertEquals(null, Flat.Fields.firstHexId(junk, 0))
        assertEquals(null, Flat.Fields.firstByteVector(junk, 0))
    }
}
