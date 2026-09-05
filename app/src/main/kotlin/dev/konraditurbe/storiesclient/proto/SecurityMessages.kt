package dev.konraditurbe.storiesclient.proto

/**
 * Builders + parsers for the `stella.security.*` Phase-B handshake messages (STORIES_AUTH_CIPHER_SPEC §1).
 * Frame type bytes live in [dev.konraditurbe.storiesclient.datax.MessageTypes]; this object only produces
 * and consumes the FlatBuffer payload that rides inside a frame.
 */
object SecurityMessages {

    // ------------------------------------------------------------------------------------- requests

    /** `StartSessionRequest{version:u16:0, ticket:[ubyte]:1}` (frame 0x00). Empty ticket = full challenge. */
    fun buildStartSessionRequest(version: Int, ticket: ByteArray?): ByteArray {
        val b = Flat.Builder()
        val ticketOff = if (ticket != null && ticket.isNotEmpty()) b.createByteVector(ticket) else 0
        b.startTable(2)
        b.addU16(0, version, 0)
        b.addOffset(1, ticketOff)
        return b.finish(b.endTable())
    }

    /** `IdentityRequest{}` empty marker table (frame 0x09). */
    fun buildIdentityRequest(): ByteArray {
        val b = Flat.Builder()
        b.startTable(0)
        return b.finish(b.endTable())
    }

    /** `StartChallengeRequest{appNonce:[ubyte]:0}` (frame 0x0d). */
    fun buildStartChallengeRequest(appNonce: ByteArray): ByteArray {
        val b = Flat.Builder()
        val nonceOff = b.createByteVector(appNonce)
        b.startTable(1)
        b.addOffset(0, nonceOff)
        return b.finish(b.endTable())
    }

    /**
     * `FinishChallengeRequest{deviceNonceSignature:[ubyte]:0, appCipherNonce:[ubyte]:1, currentTimeSec:i64:2}`
     * (frame 0x05). The signature is SHA256withRSA over the 16-byte deviceNonce with the app private key.
     */
    fun buildFinishChallengeRequest(deviceNonceSignature: ByteArray, appCipherNonce: ByteArray, currentTimeSec: Long): ByteArray {
        val b = Flat.Builder()
        val sigOff = b.createByteVector(deviceNonceSignature)
        val nonceOff = b.createByteVector(appCipherNonce)
        b.startTable(3)
        b.addOffset(0, sigOff)
        b.addOffset(1, nonceOff)
        b.addI64(2, currentTimeSec, 0)
        return b.finish(b.endTable())
    }

    // ------------------------------------------------------------------------------------ responses

    /** `IdentityResponse{certificate:string:0, serial:string:1}` (frame 0x0a, plaintext). */
    class IdentityResponse(val certificate: String?, val serial: String?)

    fun parseIdentityResponse(payload: ByteArray): IdentityResponse {
        val r = Flat.Reader.root(payload)
        return IdentityResponse(r.getString(0), r.getString(1))
    }

    /** `StartChallengeResponse{appNonceSignature:[ubyte]:0, deviceNonce:[ubyte]:1}` (frame 0x0e). */
    class StartChallengeResponse(val appNonceSignature: ByteArray?, val deviceNonce: ByteArray?)

    fun parseStartChallengeResponse(payload: ByteArray): StartChallengeResponse {
        val r = Flat.Reader.root(payload)
        return StartChallengeResponse(r.getByteVector(0), r.getByteVector(1))
    }

    /** `ChallengeAccepted{sessionDataSignature:0, encryptedSessionData:1, deviceCipherNonce:2}` (union member 1). */
    class ChallengeAccepted(val sessionDataSignature: ByteArray?, val encryptedSessionData: ByteArray?, val deviceCipherNonce: ByteArray?)

    /** `FinishChallengeResponse{resultType:u8:0, result:union:1}`; resultType 1 = accepted, 0 = not successful. */
    class FinishChallengeResponse(val resultType: Int, val accepted: ChallengeAccepted?)

    fun parseFinishChallengeResponse(payload: ByteArray): FinishChallengeResponse {
        val r = Flat.Reader.root(payload)
        val resultType = r.getU8(0, 0)
        val accepted = if (resultType == 1) r.getTable(1)?.let {
            ChallengeAccepted(it.getByteVector(0), it.getByteVector(1), it.getByteVector(2))
        } else null
        return FinishChallengeResponse(resultType, accepted)
    }

    /** `SessionData{sharedKey:[ubyte]:0, ticket:[ubyte]:1, authToken:string:2, expirationTimeSec:i64:3}`. */
    class SessionData(val sharedKey: ByteArray?, val ticket: ByteArray?, val authToken: String?, val expirationTimeSec: Long)

    /** Parse the SessionData recovered by RSA-OAEP-decrypting `ChallengeAccepted.encryptedSessionData`. */
    fun parseSessionData(sessionDataBytes: ByteArray): SessionData {
        val r = Flat.Reader.root(sessionDataBytes)
        return SessionData(r.getByteVector(0), r.getByteVector(1), r.getString(2), r.getI64(3, 0L))
    }
}
