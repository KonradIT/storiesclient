package dev.konraditurbe.storiesclient.proto

import dev.konraditurbe.storiesclient.util.hexToBytes
import dev.konraditurbe.storiesclient.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Golden bytes captured from the original Java builders. */
class SecurityMessagesTest {
    private val ticket = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun startSessionRequest() {
        assertEquals(
            "0c000000080020000a000400080000000800000000000001100000000102030405060708090a0b0c0d0e0f10",
            SecurityMessages.buildStartSessionRequest(0x100, ticket).toHex(),
        )
    }

    @Test
    fun identityRequestIsEmptyTable() {
        assertEquals("080000000400040004000000", SecurityMessages.buildIdentityRequest().toHex())
    }

    @Test
    fun startChallengeRequest() {
        assertEquals(
            "0c000000000006001c0004000600000004000000100000000102030405060708090a0b0c0d0e0f10",
            SecurityMessages.buildStartChallengeRequest(ticket).toHex(),
        )
    }

    @Test
    fun finishChallengeRequest() {
        val sig = ByteArray(32) { (0xa0 + it).toByte() }
        val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(
            "140000000000000000000a00440010000c0004000a00000000f1536500000000080000001000000008000000" +
                "010203040506070820000000a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf",
            SecurityMessages.buildFinishChallengeRequest(sig, nonce, 1700000000L).toHex(),
        )
    }

    @Test
    fun parseSessionDataRoundTrip() {
        val b = Flat.Builder()
        val key = b.createByteVector(ByteArray(32) { it.toByte() })
        val tk = b.createByteVector(ticket)
        val tok = b.createString("dG9rZW4=")
        b.startTable(4)
        b.addOffset(0, key); b.addOffset(1, tk); b.addOffset(2, tok); b.addI64(3, 1_800_000_000L, 0)
        val sd = SecurityMessages.parseSessionData(b.finish(b.endTable()))
        assertEquals(32, sd.sharedKey!!.size)
        assertArrayEquals(ticket, sd.ticket)
        assertEquals("dG9rZW4=", sd.authToken)
        assertEquals(1_800_000_000L, sd.expirationTimeSec)
    }

    @Test
    fun parseFinishChallengeAccepted() {
        val b = Flat.Builder()
        val enc = b.createByteVector(ByteArray(256) { 1 })
        val dn = b.createByteVector(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9))
        b.startTable(3); b.addOffset(1, enc); b.addOffset(2, dn); val accepted = b.endTable()
        b.startTable(2); b.addU8(0, 1, 0); b.addOffset(1, accepted)
        val fr = SecurityMessages.parseFinishChallengeResponse(b.finish(b.endTable()))
        assertEquals(1, fr.resultType)
        assertNotNull(fr.accepted)
        assertEquals(256, fr.accepted!!.encryptedSessionData!!.size)
        assertEquals(8, fr.accepted!!.deviceCipherNonce!!.size)
    }

    @Test
    fun controlMessagesMatchGolden() {
        assertEquals("0c00000008000800060004000800000078000700", ControlMessages.buildStartSoftApRequest(7, 120).toHex())
        assertEquals("1200000000000000000008000e0006000400080000009f019fc2240000000000", ControlMessages.buildStartWebserverRequest(0x0024c29fL, 415).toHex())
        assertEquals("0a0000000600050004000600000001", ControlMessages.buildGetCaptureInfoRequest(true).toHex())
        assertEquals(
            "0c0000000000060030000400060000000700000000000020000000303132333435363738396162636465663031323334353637383961626364656600",
            ControlMessages.buildGetAssetContentRequest("0123456789abcdef0123456789abcdef").toHex(),
        )
        assertEquals("0a0000000600050004000600000001".hexToBytes().size, 15)
    }
}
