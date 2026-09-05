package dev.konraditurbe.storiesclient.auth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * EncryptedPayload(0x02) codec for the Stories BLE control channel (verified against 498 captured frames).
 *
 * - Cipher: AES-256-GCM with a 96-bit (12-byte) tag.
 * - Key: the raw 32-byte `SessionData.sharedKey` (no KDF).
 * - Nonce: 12 bytes = cipherNonce(8) || counter(4, little-endian). TX uses appCipherNonce, RX uses
 *   deviceCipherNonce. No AAD.
 * - Wire: 0x02 frame body = `[counter:4 LE][ciphertext || tag(12)]`; counter is per-direction, starts at 0.
 */
class SessionCrypto(sharedKey: ByteArray, appCipherNonce: ByteArray, deviceCipherNonce: ByteArray) {
    init {
        require(sharedKey.size == 32) { "sharedKey must be 32B" }
        require(appCipherNonce.size == 8 && deviceCipherNonce.size == 8) { "cipher nonces must be 8B" }
    }

    private val key = SecretKeySpec(sharedKey, "AES")
    private val txBase = appCipherNonce.copyOf()
    private val rxBase = deviceCipherNonce.copyOf()
    private val sendCtr = AtomicInteger(0)

    private fun iv(base: ByteArray, counter: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(base).putInt(counter).array()

    private fun cipher(mode: Int, base: ByteArray, counter: Int): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, iv(base, counter)))
        }

    /** Encrypt a control-message payload into a 0x02 frame body `[counter:4 LE][ct||tag]` (TX direction). */
    fun seal(plaintext: ByteArray): ByteArray {
        val ctr = sendCtr.getAndIncrement()
        val ctTag = cipher(Cipher.ENCRYPT_MODE, txBase, ctr).doFinal(plaintext)
        return ByteBuffer.allocate(4 + ctTag.size).order(ByteOrder.LITTLE_ENDIAN).putInt(ctr).put(ctTag).array()
    }

    /** Decrypt a received 0x02 frame body into the control-message payload (RX direction). */
    fun open(frameBody: ByteArray): ByteArray {
        val ctr = ByteBuffer.wrap(frameBody, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return cipher(Cipher.DECRYPT_MODE, rxBase, ctr).doFinal(frameBody, 4, frameBody.size - 4)
    }

    private companion object {
        const val TAG_BITS = 96   // 0x60, NOT the 128-bit default
    }
}
