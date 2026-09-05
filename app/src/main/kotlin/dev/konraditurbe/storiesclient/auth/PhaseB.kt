package dev.konraditurbe.storiesclient.auth

import android.content.Context
import android.util.Base64
import dev.konraditurbe.storiesclient.ble.DataXLink
import dev.konraditurbe.storiesclient.datax.DataXCodec
import dev.konraditurbe.storiesclient.datax.Frame
import dev.konraditurbe.storiesclient.datax.MessageTypes
import dev.konraditurbe.storiesclient.proto.SecurityMessages
import dev.konraditurbe.storiesclient.util.hexToBytes
import dev.konraditurbe.storiesclient.util.toHex
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Phase-B handshake driver: the plaintext RSA challenge-response over a [DataXLink] that derives the session
 * (sharedKey + auth_token + a ready [SessionCrypto]). Flow (STORIES_PAIRING_SPEC §1B, btsnoop-validated):
 * ```
 *   -> StartSession(0x00)     <- StartSessionResp(0x01)
 *   -> Identity(0x09)         <- IdentityResp(0x0a)        {deviceCert, serial}
 *   -> StartChallenge(0x0d)   <- StartChallengeResp(0x0e)  {appNonceSig, deviceNonce(16B)}
 *      sign deviceNonce with the app RSA private key (SHA256withRSA)
 *   -> FinishChallenge(0x05)  <- FinishChallengeResp(0x06) {ChallengeAccepted: encryptedSessionData, deviceCipherNonce(8B)}
 *      RSA-OAEP(SHA-256/MGF1-SHA-256) decrypt -> SessionData {sharedKey, ticket, authToken, expiry}
 * ```
 * The RSA key + bootstrap ticket come from [IdentityStore]; nothing sensitive is bundled in the APK.
 */
class PhaseB private constructor(
    ctx: Context,
    private val link: DataXLink,
    private val appPriv: PrivateKey,
    private val log: (String) -> Unit,
) {
    interface Callback {
        /** Handshake complete: session derived. */
        fun onSession(r: Result)
        fun onError(stage: String, t: Throwable)
    }

    class Result(
        val sharedKey: ByteArray,
        /** Base64 token sent as `Authorization: app_token <authToken>`. */
        val authToken: String?,
        val appCipherNonce: ByteArray,
        val deviceCipherNonce: ByteArray,
        /** Device X.509 cert (for TLS pinning; the official app does not verify it either). */
        val deviceCertPem: String?,
        /** Glasses serial from IdentityResp (e.g. 2537Q9ZCCS0050). */
        val serial: String?,
        val expirationTimeSec: Long,
        /** Ready for EncryptedPayload(0x02). */
        val crypto: SessionCrypto,
    )

    private enum class Stage { START_SESSION, IDENTITY, CHALLENGE, FINISH, DONE }

    private val appCtx: Context = ctx.applicationContext
    private val rng = SecureRandom()
    private val ticket: ByteArray = loadTicket()
    private var cb: Callback? = null
    private var stage = Stage.START_SESSION
    private lateinit var appCipherNonce: ByteArray
    private var deviceCertPem: String? = null
    private var serial: String? = null

    /** Persisted resume ticket from the previous session, or the bootstrap ticket on first run. */
    private fun loadTicket(): ByteArray {
        IdentityStore.resumeTicketHex(appCtx)?.let {
            log("[phaseB] using persisted resume ticket"); return it.hexToBytes()
        }
        IdentityStore.bootstrapHex(appCtx)?.takeIf { it.length == 32 }?.let {
            log("[phaseB] using bootstrap ticket (first run)"); return it.hexToBytes()
        }
        log("[phaseB] WARN no bootstrap ticket configured — StartSession will be rejected")
        return ByteArray(16)   // guarded upstream by IdentityStore.isConfigured(); an already-paired device rejects this
    }

    /** Persist the fresh ticket issued in SessionData for the next session. */
    private fun saveTicket(t: ByteArray?) {
        if (t == null || t.size != 16) return
        runCatching { IdentityStore.saveResumeTicketHex(appCtx, t.toHex()) }
            .onSuccess { log("[phaseB] persisted fresh resume ticket for next session") }
            .onFailure { log("[phaseB] saveTicket error: $it") }
    }

    /** Start the handshake; [cb] fires once on completion or error. */
    fun run(cb: Callback) {
        this.cb = cb
        link.onFrame = ::onFrame
        try {
            log("[phaseB] -> StartSession (ticketed)")
            sendFrame(MessageTypes.START_SESSION, START_PREFIX + ticket)
        } catch (t: Throwable) {
            fail("StartSession", t)
        }
    }

    private fun onFrame(f: Frame) {
        try {
            when (stage) {
                Stage.START_SESSION -> {
                    if (f.type != MessageTypes.START_SESSION_RESP) return
                    log("[phaseB] <- StartSessionResp; -> Identity")
                    stage = Stage.IDENTITY
                    sendFrame(MessageTypes.IDENTITY, SecurityMessages.buildIdentityRequest())
                }
                Stage.IDENTITY -> {
                    if (f.type != MessageTypes.IDENTITY_RESP) return
                    val id = SecurityMessages.parseIdentityResponse(f.payload)
                    deviceCertPem = id.certificate
                    serial = id.serial
                    log("[phaseB] <- IdentityResp serial=${id.serial}")
                    val appNonce = ByteArray(16).also(rng::nextBytes)
                    stage = Stage.CHALLENGE
                    sendFrame(MessageTypes.START_CHALLENGE, SecurityMessages.buildStartChallengeRequest(appNonce))
                }
                Stage.CHALLENGE -> {
                    if (f.type != MessageTypes.START_CHALLENGE_RESP) return
                    val sc = SecurityMessages.parseStartChallengeResponse(f.payload)
                    val deviceNonce = sc.deviceNonce ?: throw IllegalStateException("no deviceNonce")
                    val deviceNonceSig = signRsa(deviceNonce)
                    appCipherNonce = ByteArray(8).also(rng::nextBytes)
                    stage = Stage.FINISH
                    log("[phaseB] <- StartChallengeResp; signed deviceNonce; -> FinishChallenge")
                    sendFrame(
                        MessageTypes.FINISH_CHALLENGE,
                        SecurityMessages.buildFinishChallengeRequest(deviceNonceSig, appCipherNonce, System.currentTimeMillis() / 1000L),
                    )
                }
                Stage.FINISH -> {
                    if (f.type != MessageTypes.FINISH_CHALLENGE_RESP) return
                    val fr = SecurityMessages.parseFinishChallengeResponse(f.payload)
                    val accepted = fr.accepted
                    if (fr.resultType != 1 || accepted == null) {
                        fail("FinishChallenge", IllegalStateException("challenge not accepted (resultType=${fr.resultType})"))
                        return
                    }
                    val encrypted = accepted.encryptedSessionData ?: throw IllegalStateException("no encryptedSessionData")
                    val deviceCipherNonce = accepted.deviceCipherNonce ?: throw IllegalStateException("no deviceCipherNonce")
                    val sd = SecurityMessages.parseSessionData(oaepDecrypt(encrypted))
                    val sharedKey = sd.sharedKey ?: throw IllegalStateException("no sharedKey")
                    saveTicket(sd.ticket)
                    stage = Stage.DONE
                    log("[phaseB] SESSION ESTABLISHED  authToken=${sd.authToken}  sharedKey=${sharedKey.toHex()}")
                    cb?.onSession(
                        Result(
                            sharedKey = sharedKey,
                            authToken = sd.authToken,
                            appCipherNonce = appCipherNonce,
                            deviceCipherNonce = deviceCipherNonce,
                            deviceCertPem = deviceCertPem,
                            serial = serial,
                            expirationTimeSec = sd.expirationTimeSec,
                            crypto = SessionCrypto(sharedKey, appCipherNonce, deviceCipherNonce),
                        ),
                    )
                }
                Stage.DONE -> Unit
            }
        } catch (t: Throwable) {
            fail("stage $stage", t)
        }
    }

    private fun signRsa(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withRSA").apply { initSign(appPriv); update(data) }.sign()

    private fun oaepDecrypt(ct: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.DECRYPT_MODE, appPriv, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        }.doFinal(ct)

    private fun sendFrame(type: Int, payload: ByteArray) = link.send(DataXCodec.frame(type, payload))

    private fun fail(stage: String, t: Throwable) {
        log("[phaseB] ERROR @$stage: $t")
        cb?.onError(stage, t)
    }

    companion object {
        /**
         * Exact StartSessionRequest FlatBuffer prefix (version 0x100) from the official app, ending in the
         * ticket-vector length (16); the 16-byte ticket is appended. A no-ticket StartSession is REJECTED by an
         * already-paired device (disconnect reason 0x13): the ticket is a required resume credential.
         */
        private val START_PREFIX = "0c00000008000c000600080008000000000000010400000010000000".hexToBytes()

        /**
         * Build from the imported owner identity in [IdentityStore].
         * @throws IllegalStateException if no identity has been configured yet.
         */
        fun fromStore(ctx: Context, link: DataXLink, log: (String) -> Unit): PhaseB {
            val b64 = IdentityStore.rsaB64(ctx)
            if (b64.isNullOrEmpty()) throw IllegalStateException("no identity loaded — import your glasses identity JSON first")
            val der = Base64.decode(b64.trim(), Base64.DEFAULT)
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
            return PhaseB(ctx, link, key, log)
        }
    }
}
