package dev.konraditurbe.storiesclient.proto

/**
 * Canonical FlatBuffer builders/parsers for the `stella.srvs.*` SoftAP / webserver / media messages
 * (STORIES_TRANSPORT_SPEC §5). These are the schema-derived layouts; the live control channel uses
 * byte-exact replicas of the official app's frames where the firmware is layout-sensitive (see
 * `control.Requests`). Kept as protocol reference for the SoftAP media path.
 */
object ControlMessages {

    /** `StartSoftApRequest{softapRequestToken:u16:0, timeoutSeconds:u16:1}`. */
    fun buildStartSoftApRequest(softapRequestToken: Int, timeoutSeconds: Int): ByteArray {
        val b = Flat.Builder()
        b.startTable(2)
        b.addU16(0, softapRequestToken, 0)
        b.addU16(1, timeoutSeconds, 0)
        return b.finish(b.endTable())
    }

    /** `StartSoftApResponse{status:u8:0, ssid:string:1, password:string:2, webserviceUrl:string:3, timeoutSeconds:u16:4}`. */
    class StartSoftApResponse(val status: Int, val ssid: String?, val password: String?, val webserviceUrl: String?, val timeoutSeconds: Int)

    fun parseStartSoftApResponse(payload: ByteArray): StartSoftApResponse {
        val r = Flat.Reader.root(payload)
        return StartSoftApResponse(r.getU8(0, 0), r.getString(1), r.getString(2), r.getString(3), r.getU16(4, 0))
    }

    /** `StartWebserverRequest{requestToken:i64:0, idleTimeoutSecs:u16:1}`. */
    fun buildStartWebserverRequest(requestToken: Long, idleTimeoutSecs: Int): ByteArray {
        val b = Flat.Builder()
        b.startTable(2)
        b.addI64(0, requestToken, 0)
        b.addU16(1, idleTimeoutSecs, 0)
        return b.finish(b.endTable())
    }

    /** `StartWebserverResponse{status:u8:0, statusMessage:string:1, scheme:u8:2, urlSuffix:string:3, requestToken:i64:4, idleTimeoutSecs:u16:5}`. */
    class StartWebserverResponse(
        val status: Int, val statusMessage: String?, val scheme: Int,
        val urlSuffix: String?, val requestToken: Long, val idleTimeoutSecs: Int,
    )

    fun parseStartWebserverResponse(payload: ByteArray): StartWebserverResponse {
        val r = Flat.Reader.root(payload)
        return StartWebserverResponse(r.getU8(0, 0), r.getString(1), r.getU8(2, 0), r.getString(3), r.getI64(4, 0L), r.getU16(5, 0))
    }

    /** `GetCaptureInfoRequest{includeDeletedCaptures:bool:0}`. */
    fun buildGetCaptureInfoRequest(includeDeleted: Boolean): ByteArray {
        val b = Flat.Builder()
        b.startTable(1)
        b.addBool(0, includeDeleted, false)
        return b.finish(b.endTable())
    }

    /** `GetAssetContentRequest{assetId:string:0}`. */
    fun buildGetAssetContentRequest(assetId: String): ByteArray {
        val b = Flat.Builder()
        val idOff = b.createString(assetId)
        b.startTable(1)
        b.addOffset(0, idOff)
        return b.finish(b.endTable())
    }

    /** `GetAssetContentResponse{content:[ubyte]:0}` (small assets inline over BLE). */
    fun parseGetAssetContent(payload: ByteArray): ByteArray? = Flat.Reader.root(payload).getByteVector(0)
}
