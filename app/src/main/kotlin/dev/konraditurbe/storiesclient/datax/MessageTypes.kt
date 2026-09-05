package dev.konraditurbe.storiesclient.datax

/**
 * DataX frame type bytes: the `stella.security.MessageTypes` enum (STORIES_TRANSPORT_SPEC §4, confirmed on
 * the wire). The handshake runs StartSession -> Identity -> StartChallenge -> FinishChallenge, after which
 * every control frame is [ENCRYPTED_PAYLOAD].
 */
object MessageTypes {
    const val START_SESSION = 0x00
    const val START_SESSION_RESP = 0x01
    const val ENCRYPTED_PAYLOAD = 0x02
    const val END_SESSION_REQUEST = 0x03
    const val END_SESSION_RESPONSE = 0x04
    const val FINISH_CHALLENGE = 0x05
    const val FINISH_CHALLENGE_RESP = 0x06
    const val FINISH_CHANGE_OWNER_REQ = 0x07
    const val FINISH_CHANGE_OWNER_RESP = 0x08
    const val IDENTITY = 0x09
    const val IDENTITY_RESP = 0x0a
    const val SKIP_CHALLENGE_REQ = 0x0b
    const val SKIP_CHALLENGE_RESP = 0x0c
    const val START_CHALLENGE = 0x0d
    const val START_CHALLENGE_RESP = 0x0e
    const val START_CHANGE_OWNER_REQ = 0x0f
    const val START_CHANGE_OWNER_RESP = 0x10

    private val names = mapOf(
        START_SESSION to "StartSession", START_SESSION_RESP to "StartSessionResp",
        ENCRYPTED_PAYLOAD to "EncryptedPayload",
        END_SESSION_REQUEST to "EndSessionRequest", END_SESSION_RESPONSE to "EndSessionResponse",
        FINISH_CHALLENGE to "FinishChallenge", FINISH_CHALLENGE_RESP to "FinishChallengeResp",
        FINISH_CHANGE_OWNER_REQ to "FinishChangeOwnerReq", FINISH_CHANGE_OWNER_RESP to "FinishChangeOwnerResp",
        IDENTITY to "Identity", IDENTITY_RESP to "IdentityResp",
        SKIP_CHALLENGE_REQ to "SkipChallengeReq", SKIP_CHALLENGE_RESP to "SkipChallengeResp",
        START_CHALLENGE to "StartChallenge", START_CHALLENGE_RESP to "StartChallengeResp",
        START_CHANGE_OWNER_REQ to "StartChangeOwnerReq", START_CHANGE_OWNER_RESP to "StartChangeOwnerResp",
    )

    /** Human-readable name for logging. */
    fun name(type: Int): String = names[type and 0xff] ?: "Unknown(0x${(type and 0xff).toString(16)})"
}
