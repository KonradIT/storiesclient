package dev.konraditurbe.storiesclient.datax

import dev.konraditurbe.storiesclient.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DataXCodecTest {

    /** Golden chunking from the original Java implementation: encodeFrame(0x09, "ABCDEFGHIJ", mtu=6). */
    @Test
    fun encodeFrameMatchesGolden() {
        val chunks = DataXCodec.encodeFrame(0x09, "ABCDEFGHIJ".toByteArray(), 6)
        assertEquals(listOf("090a00414243", "444546474849", "4a"), chunks.map { it.toHex() })
    }

    @Test
    fun roundTripThroughReassembler() {
        val payload = ByteArray(700) { it.toByte() }
        val chunks = DataXCodec.encodeFrame(MessageTypes.IDENTITY, payload, 247)
        val r = Reassembler()
        var frame: Frame? = null
        for (c in chunks) {
            assertNull(frame)
            frame = r.push(c)
        }
        assertNotNull(frame)
        assertEquals(MessageTypes.IDENTITY, frame!!.type)
        assertArrayEquals(payload, frame.payload)
        assertEquals(false, r.isAssembling)
    }

    @Test
    fun emptyPayloadIsHeaderOnlyChunk() {
        val chunks = DataXCodec.encodeFrame(MessageTypes.START_SESSION, ByteArray(0), 247)
        assertEquals(1, chunks.size)
        assertEquals("000000", chunks[0].toHex())
        val f = Reassembler().push(chunks[0])
        assertNotNull(f)
        assertEquals(0, f!!.payload.size)
    }

    @Test
    fun wholeFrameHelperMatchesHeaderInvariant() {
        val f = DataXCodec.frame(0x02, byteArrayOf(1, 2, 3))
        assertEquals("020300010203", f.toHex())
    }
}
