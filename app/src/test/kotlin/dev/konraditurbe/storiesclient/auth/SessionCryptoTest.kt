package dev.konraditurbe.storiesclient.auth

import dev.konraditurbe.storiesclient.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val appNonce = ByteArray(8) { 9 }
    private val deviceNonce = ByteArray(8) { 7 }

    /** Golden ciphertexts from the original Java SessionCrypto (AES-256-GCM, 96-bit tag, LE counter nonce). */
    @Test
    fun sealMatchesGoldenAndIncrementsCounter() {
        val sc = SessionCrypto(key, appNonce, deviceNonce)
        assertEquals("00000000d06412a9ad761ff9acf5a8a17a4ce452ca4e8d919e5f8de8", sc.seal("hello stella".toByteArray()).toHex())
        assertEquals("01000000c6d8df0c07d2cf4bbe8651840f24ca", sc.seal(byteArrayOf(1, 2, 3)).toHex())
    }

    @Test
    fun openReversesSealOfThePeerDirection() {
        // The glasses seal with deviceCipherNonce as base; emulate them by swapping the nonces.
        val glasses = SessionCrypto(key, deviceNonce, appNonce)
        val phone = SessionCrypto(key, appNonce, deviceNonce)
        val pt = "from the glasses".toByteArray()
        assertArrayEquals(pt, phone.open(glasses.seal(pt)))
        assertArrayEquals(byteArrayOf(), phone.open(glasses.seal(byteArrayOf())))
    }

    @Test
    fun rejectsBadKeyAndNonceSizes() {
        assertThrows(IllegalArgumentException::class.java) { SessionCrypto(ByteArray(16), appNonce, deviceNonce) }
        assertThrows(IllegalArgumentException::class.java) { SessionCrypto(key, ByteArray(4), deviceNonce) }
    }
}
