package dev.konraditurbe.storiesclient.control

import dev.konraditurbe.storiesclient.proto.Flat
import dev.konraditurbe.storiesclient.util.hexToBytes
import dev.konraditurbe.storiesclient.util.putLe32

/**
 * Request envelopes + bodies for the control channel. Where the firmware is layout-sensitive the bodies are
 * byte-exact replicas of the official app's frames (prefix hex + substituted id); the rest use [Flat.Builder].
 */
object Requests {

    /** `[03][00][serviceId][00][totalLen:4][reqId:4][0:4][body]` INVOKE envelope. */
    fun invoke(serviceId: Int, reqId: Int, body: ByteArray): ByteArray {
        val f = ByteArray(16 + body.size)
        f[0] = 0x03; f[1] = 0; f[2] = serviceId.toByte(); f[3] = 0
        f.putLe32(4, f.size)
        f.putLe32(8, reqId)
        f.putLe32(12, 0)
        body.copyInto(f, 16)
        return f
    }

    /** An empty FlatBuffer table: the body of get_capture_info / get_system_info / device_state. */
    val EMPTY_TABLE: ByteArray = "080000000400040004000000".hexToBytes()

    /**
     * GetAssetContentRequestV2 with the asset id, exact layout from the official app (assetType=1, 32-char id
     * string with a 0x20 length prefix and 4-byte tail).
     */
    fun getAssetContentV2(assetId: String): ByteArray =
        GET_ASSET_PREFIX + assetId.toByteArray(Charsets.US_ASCII) + ByteArray(4)

    /**
     * DeleteCaptureRequest, byte-exact to the official app; only the 32-hex captureId varies (field 0, voffset 4).
     *
     * History: a `Flat.Builder` version once passed field VOFFSETS as field INDICES, leaving field 0 empty; the
     * glasses then recursively unlinked with no id and deleted ALL captures while acking result=0. This layout
     * is verified identical to the official request. Callers must enforce the 32-char id guard.
     */
    fun deleteCapture(captureId: String): ByteArray {
        require(captureId.length == 32) { "captureId must be 32 chars" }
        return DELETE_PREFIX + captureId.toByteArray(Charsets.US_ASCII) + ByteArray(4)
    }

    /** McuSettingOpMode. */
    const val OP_READ = 0
    const val OP_WRITE = 1

    /**
     * Bare `McuSettingRequest{op:u8(f0), entry:McuSettingEntry{settingEnum:u32(f0), value:i64(f1)}(f1)}`.
     * Impossible defaults force each field to be written even when its value is 0.
     */
    fun mcuSetting(op: Int, settingEnum: Int, value: Long): ByteArray {
        val b = Flat.Builder(96)
        b.startTable(3)                                  // McuSettingEntry
        b.addI32(0, settingEnum, -1)
        b.addI64(1, value, Long.MIN_VALUE)
        val entry = b.endTable()
        b.startTable(2)                                  // McuSettingRequest
        b.addU8(0, op, -1)
        b.addOffset(1, entry)
        return b.finish(b.endTable())
    }

    /** `stella.srvs.StaModeConnectRequest` (schema from WIFI_DIRECT_MEDIA_SPEC.md). */
    fun staModeConnect(ssid: String, pass: String, freq: Int, netmask: Int, token: Int, idleTimeout: Int): ByteArray {
        val b = Flat.Builder(256)
        val ssidOff = b.createString(ssid)
        val passOff = b.createString(pass)
        val goVec = b.createByteVector(byteArrayOf(192.toByte(), 168.toByte(), 49, 1))
        b.startTable(1); b.addOffset(0, goVec); val goTbl = b.endTable()
        val clVec = b.createByteVector(byteArrayOf(192.toByte(), 168.toByte(), 49, 2))
        b.startTable(1); b.addOffset(0, clVec); val clTbl = b.endTable()
        val band = ByteArray(8).apply { putLe32(0, freq); putLe32(4, netmask) }   // struct{u32 freq, i32 netmask}
        val bandVec = b.createStructVector(1, band)
        b.startTable(11)
        b.addOffset(0, ssidOff)                                                   // f0 ssid
        b.addOffset(2, passOff)                                                   // f2 passphrase
        b.addI64(3, (token.toLong() and 0xffffffffL) or (idleTimeout.toLong() shl 32), -1L)   // f3 struct{token, idleTimeout}
        b.addU16(4, 0xffff, -1)                                                   // f4 const 0xffff
        b.addI32(5, 1, -999)                                                      // f5 const 1
        b.addOffset(6, clTbl)                                                     // f6 client IP (glasses)
        b.addI32(7, 24, -999)                                                     // f7 const 24
        b.addOffset(8, goTbl)                                                     // f8 GO IP (phone)
        b.addOffset(10, bandVec)                                                  // f10 wifi_band[]
        return b.finish(b.endTable())
    }

    /** `stella.srvs.StartWebserverRequest`: the captured 32-byte body with token + idle timeout patched in. */
    fun startWebserver(token: Int, idleTimeout: Int): ByteArray =
        START_WEBSERVER_TEMPLATE.copyOf().apply { putLe32(24, token); putLe32(28, idleTimeout) }

    private val GET_ASSET_PREFIX =
        "0c00000008000e000700080008000000000000010c0000000000060008000400060000000400000020000000".hexToBytes()
    private val DELETE_PREFIX = "0c0000000000060008000400060000000400000020000000".hexToBytes()
    private val START_WEBSERVER_TEMPLATE = "10000000000000000800100008000600080000000000ffff9fc224009f010000".hexToBytes()
}
