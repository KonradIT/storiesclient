package dev.konraditurbe.storiesclient.control

import android.content.Context
import android.os.SystemClock
import dev.konraditurbe.storiesclient.auth.SessionCrypto
import dev.konraditurbe.storiesclient.ble.DataXLink
import dev.konraditurbe.storiesclient.datax.DataXCodec
import dev.konraditurbe.storiesclient.datax.Frame
import dev.konraditurbe.storiesclient.datax.MessageTypes
import dev.konraditurbe.storiesclient.media.MediaSaver
import dev.konraditurbe.storiesclient.media.WebserverClient
import dev.konraditurbe.storiesclient.proto.Flat.Fields
import dev.konraditurbe.storiesclient.util.abbr
import dev.konraditurbe.storiesclient.util.indexOfAscii
import dev.konraditurbe.storiesclient.util.isJpeg
import dev.konraditurbe.storiesclient.util.isMp4
import dev.konraditurbe.storiesclient.util.le16
import dev.konraditurbe.storiesclient.util.le32
import dev.konraditurbe.storiesclient.util.toAscii
import dev.konraditurbe.storiesclient.util.toHex
import dev.konraditurbe.storiesclient.wifi.WifiDirectHost
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Stella control channel: the service-name-routed RPC that rides inside EncryptedPayload(0x02) on the DataX
 * link once the Phase-B session is established.
 *
 * Wire format (RE'd from a decrypted capture):
 * - plaintext envelope kinds: 0x01 REGISTER (phone declares a service), 0x02 RegisterServiceClientResponse,
 *   0x03 INVOKE `[03][00][serviceId][00][totalLen:4][reqId:4][0:4][body]`, 0x04 single-frame response,
 *   0x84 chunked response `[84][seq:3][serviceId][00][chunkSz:2][totalLen:4][offset:4][content]`.
 * - each envelope is sealed by [SessionCrypto] and shipped as a DataX `[0x02][len:2 LE][counter:4 LE][ct||tag]`.
 *
 * The glasses ALLOCATE a serviceClientId per registered service; invokes and response routing go by service
 * NAME through that allocated-id map (hardcoded ids are only fallbacks until the registration responses land).
 */
class ControlChannel(
    private val link: DataXLink,
    private val crypto: SessionCrypto,
    ctx: Context,
    private val log: (String) -> Unit,
) {
    interface Listener {
        fun onCaptures(captures: List<Capture>)
        /** A glasses-originated notify mentioning a capture (new media). */
        fun onNotify(info: String)
        fun onDeviceInfo(d: DeviceInfo)
        /** Connect-flow progress: stage "register" (done/total services) or "capture_request" (get_capture_info sent). */
        fun onProgress(stage: String, done: Int, total: Int) {}
    }

    /** Result of a settings read/write. `result`: 0=Success, 1=FailedToUpdate, 2=InternalError, 4=BadIndexError. */
    interface SettingCallback {
        fun onValue(settingEnum: Int, value: Long, result: Int)
        fun onError(msg: String)
    }

    /** Streamed single-asset download. [total] may be -1 if the server omits Content-Length. */
    interface DownloadCallback {
        fun onProgress(got: Long, total: Long)
        fun onSaved(saved: MediaSaver.Saved, mime: String)
        fun onError(msg: String)
    }

    private val appCtx = ctx.applicationContext
    @Volatile var listener: Listener? = null
    @Volatile private var closed = false
    private val info = DeviceInfo()
    private var nextReqId = 0x60

    // registration: name -> allocated serviceClientId, and the reverse for response routing
    private val svcId = ConcurrentHashMap<String, Int>()
    private val idToName = ConcurrentHashMap<Int, String>()
    private val idxToName = ConcurrentHashMap<Int, String>()

    /** Allocated serviceClientId for [name], or [fallback] until the registration response is parsed. */
    private fun sid(name: String, fallback: Int) = svcId[name] ?: fallback

    // reassembly of chunked 0x84 responses keyed by serviceId
    private class Reasm(val buf: ByteArray, val total: Int, var filled: Int = 0)
    private val reasm = HashMap<Int, Reasm>()

    // webserver session (brought up once per connect; reused for every full-res download)
    private val webReady = CountDownLatch(1)
    private val web = WebserverClient(WifiDirectHost.CLIENT_IP, log)

    // -------------------------------------------------------------------------------------- lifecycle

    /**
     * Stop all worker threads and unblock any pending waits. Idempotent. After this, callbacks no longer
     * fire and in-flight fetches/downloads abort.
     */
    fun close() {
        closed = true
        listener = null
        webReady.countDown()
        curAssetLatch?.countDown()
        assetWorker?.interrupt()
        curSettingLatch?.countDown()
        runCatching { settingsExec.shutdownNow() }
    }

    /** Take over inbound 0x02 frames, register the service set, then invoke get_capture_info. */
    fun start() {
        link.onFrame = ::onFrame
        thread(name = "ctrl-register") {
            try {
                val regs = ServiceRegistry.registrations()
                log("[ctrl] registering ${regs.size} services…")
                regs.forEachIndexed { i, r ->
                    idxToName[r.idx] = r.name
                    if (REG_DEBUG) log("[reg] -> idx 0x${r.idx.toString(16)} ${r.name}")
                    sendEncrypted(r.frame)
                    listener?.onProgress("register", i + 1, regs.size)
                    Thread.sleep(50)
                }
                // wait for the allocated ids (RegisterServiceClientResponse) before invoking
                var w = 0
                while (w++ < 30 && svcId[ServiceRegistry.GET_CAPTURE_INFO] == null) Thread.sleep(100)
                listener?.onProgress("capture_request", 1, 1)
                log("[ctrl] -> get_capture_info (svc 0x${sid(ServiceRegistry.GET_CAPTURE_INFO, SVC_GET_CAPTURE_INFO).toString(16)})")
                requestCaptureList()
            } catch (e: Exception) {
                log("[ctrl] start error: $e")
            }
        }
        // Poll device_state on a timer for fresh glasses battery + case (Titan) battery when docked.
        thread(name = "ctrl-devpoll") {
            try {
                Thread.sleep(9000)
                while (!closed) {
                    requestDeviceState()
                    Thread.sleep(10000)
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun sendEncrypted(plaintext: ByteArray) {
        link.send(DataXCodec.frame(MessageTypes.ENCRYPTED_PAYLOAD, crypto.seal(plaintext)))
    }

    private fun invoke(serviceName: String, fallbackId: Int, body: ByteArray = Requests.EMPTY_TABLE) {
        sendEncrypted(Requests.invoke(sid(serviceName, fallbackId), nextReqId++, body))
    }

    // --------------------------------------------------------------------------------- inbound frames

    private fun onFrame(f: Frame) {
        if (f.type != MessageTypes.ENCRYPTED_PAYLOAD) return
        val pt = try { crypto.open(f.payload) } catch (e: Exception) { log("[ctrl] open() failed: $e"); return }
        if (pt.isEmpty()) return
        if (pt.size > 60 && pt.indexOfAscii("battery_level") >= 0) parseTelemetryBattery(pt)   // SOC telemetry JSON
        val kind = pt[0].toInt() and 0xff
        if (RX_DEBUG) log("[rx] k=0x${kind.toString(16)} ${pt.size}B ${pt.toHex(64)}")
        when {
            kind == 0x84 -> handleResponseChunk(pt)
            kind == 0x04 -> {   // single-frame response: [04][svc:2][00][totalLen:4][reqId:4][0:4][FlatBuffer]
                val serviceId = pt[2].toInt() and 0xff
                log("[ctrl] <- single response svc=0x${serviceId.toString(16)} ${pt.size}B  fb=${pt.copyOfRange(16, pt.size).toHex(48)}")
                onResponseComplete(serviceId, pt)
            }
            kind == 0x01 || kind == 0x03 -> {   // register/notify FROM the glasses (e.g. notify_capture)
                val asc = pt.toAscii()
                log("[ctrl] <- notify kind=0x${kind.toString(16)} $asc")
                if (asc.lowercase().contains("capture")) listener?.onNotify(asc)
            }
            kind == 0x02 && pt.size >= 28 -> parseRegResponse(pt)
            kind != 0x00 && kind != 0x02 -> log("[ctrl] <- kind=0x${kind.toString(16)} ${pt.toHex(32)}")   // 0x00 heartbeat / bare 0x02 ACK are silent
        }
    }

    /**
     * Accumulate a chunked response: content = `chunk[16:]` placed at `offset`. On completion the reassembled
     * message is `[16-byte response header][FlatBuffer]`.
     */
    private fun handleResponseChunk(pt: ByteArray) {
        if (pt.size < 16) return
        val serviceId = pt[4].toInt() and 0xff
        val total = pt.le32(8)
        val offset = pt.le32(12)
        val content = pt.copyOfRange(16, pt.size)
        val isAsset = serviceId == sid(ServiceRegistry.GET_ASSET, SVC_GET_ASSET)
        if (offset == 0 && isAsset) {
            assetOwner = curAssetReqId                                   // this stream belongs to the current request
            val seq = pt.le16(1) or ((pt[3].toInt() and 0xff) shl 16)
            log("[ctrl] <- asset stream seq=$seq total=$total")
            // A thumbnail is ~6-10KB. A huge stream is a mis-picked full-res frame that would clog BLE: abort.
            val cb = curAssetCallback
            if (total > 65536 && cb != null) {
                log("[ctrl] thumb stream too big (${total}B) — not a thumbnail, aborting wait")
                curAssetCallback = null
                cb(Result.failure(IllegalStateException("too big ${total}B")))
                curAssetLatch?.countDown()
            }
        }
        // For get_asset only, offset==0 forces a fresh buffer so a straggler of the same total can't share a
        // half-filled buffer with a new stream. Kept OFF for other services so a duplicate/reordered first chunk
        // can't wipe an in-progress get_capture_info/device_state reassembly.
        var r = reasm[serviceId]
        if (r == null || r.total != total || (offset == 0 && isAsset)) {
            r = Reasm(ByteArray(maxOf(total, 16)), total)
            reasm[serviceId] = r
        }
        if (offset >= 0 && offset + content.size <= r.buf.size) content.copyInto(r.buf, offset)
        r.filled = maxOf(r.filled, offset + content.size)
        if (r.filled < total) return
        reasm.remove(serviceId)
        onResponseComplete(serviceId, r.buf)
    }

    private fun onResponseComplete(serviceId: Int, msg: ByteArray) {
        // Route by the service NAME for this session's allocated id (legacy ids as fallbacks for leaked handles).
        val name = idToName[serviceId] ?: ""
        when {
            name == ServiceRegistry.GET_CAPTURE_INFO || serviceId == SVC_GET_CAPTURE_INFO -> parseCaptureInfo(msg)
            name == ServiceRegistry.GET_ASSET || serviceId == SVC_GET_ASSET -> onAssetResponse(msg)
            name == ServiceRegistry.GET_SYSTEM_INFO || serviceId == SVC_GET_SYSTEM_INFO -> parseSystemInfo(msg)
            name == ServiceRegistry.DEVICE_STATE || serviceId == SVC_DEVICE_STATE_UPDATE -> parseDeviceState(msg)
            name == ServiceRegistry.DELETE_CAPTURE || serviceId == SVC_DELETE_CAPTURE -> onDeleteResponse(msg)
            name == ServiceRegistry.MCU_SETTING -> parseMcuSetting(msg)
            else -> {
                if (msg.indexOfAscii("battery_level") >= 0) parseTelemetryBattery(msg)   // chunked SOC telemetry
                log("[ctrl] <- response svc=0x${serviceId.toString(16)} ${msg.size}B")
            }
        }
    }

    /** RegisterServiceClientResponse: `[02][sub][idx][00][len:4][result:4][token:4][fb{f0:id,f1:idx}]`. */
    private fun parseRegResponse(pt: ByteArray) {
        try {
            val idx = pt[2].toInt() and 0xff
            val root = Fields.root(pt, 16)
            val idPos = Fields.field(pt, root, 0)
            if (idPos == 0) return                                      // topic responses (sub 0x03) have no id field
            val id = pt.le16(idPos)
            val nm = idxToName[idx] ?: return
            svcId[nm] = id
            idToName[id] = nm
            if (REG_DEBUG) log("[reg] <- idx 0x${idx.toString(16)} => svc 0x${id.toString(16)}  $nm")
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------------------- capture listing

    /** Re-invoke get_capture_info (e.g. after a notify_capture or pull-to-refresh). */
    fun requestCaptureList() = guarded("requestCaptureList") { invoke(ServiceRegistry.GET_CAPTURE_INFO, SVC_GET_CAPTURE_INFO) }

    /** GetCaptureInfoResponse{captures:[CaptureInfo{id, type, assets:[Asset], {timestampNs}}]}. */
    private fun parseCaptureInfo(msg: ByteArray) {
        try {
            val root = Fields.root(msg, 16)
            val capVec = Fields.indirect(msg, root, 0)
            if (capVec == 0) {
                log("[ctrl] get_capture_info: 0 captures (empty gallery)")
                listener?.onCaptures(emptyList()); return
            }
            val n = msg.le32(capVec)
            val p = capVec + 4
            if (n < 0 || n > 500 || p + n * 4 > msg.size) { log("[ctrl] get_capture_info: bad count $n"); return }
            log("[ctrl] get_capture_info: $n captures")
            val captures = ArrayList<Capture>(n)
            for (i in 0 until n) {
                val cap = Fields.follow(msg, p + i * 4)
                if (DUMP_RAW) dumpTable(msg, cap, "  cap[$i]")
                val c = parseCapture(msg, cap) ?: continue
                captures += c
                log("  capture[$i] id=${c.captureId.abbr()} ${if (c.isVideo) "VIDEO" else "PHOTO"} assets=${c.assetIds.size}" +
                    " thumb=${c.thumbnailAssetId.abbr()} fullRes=${c.fullFrames.size} cams=${c.fullFrames.map { it.camera }}" +
                    " ev=${c.fullFrames.map { it.ev }}${if (c.isBracket) " [BRACKET]" else ""} imu=${c.imuAssetId.abbr()} tsNs=${c.timestampNs}")
            }
            // get_capture_info returns storage order, not chronological: newest first for the gallery.
            captures.sortByDescending { it.timestampNs }
            log("[ctrl] sorted newest-first: ${captures.firstOrNull()?.captureId.abbr()} at top")
            listener?.onCaptures(captures)
        } catch (e: Exception) {
            log("[ctrl] parseCaptureInfo error: $e")
        }
    }

    private fun parseCapture(msg: ByteArray, cap: Int): Capture? {
        val captureId = Fields.string(msg, cap, 0) ?: return null
        val type = Fields.u32(msg, cap, 2)
        var timestampNs = 0L
        val tsTbl = Fields.indirect(msg, cap, 4)                        // f4 = nested { timestampNs:i64, buildId:string }
        if (tsTbl != 0) {
            for (fi in 0..3) {                                          // the inline i64 in plausible ns-since-epoch range
                val v = Fields.i64(msg, tsTbl, fi)
                if (v in 1_400_000_000_000_000_001L..2_499_999_999_999_999_999L) { timestampNs = v; break }
            }
        }
        val assetIds = ArrayList<String>()
        val full = ArrayList<Capture.FullFrame>()
        var thumb: String? = null
        var imu: String? = null
        var timing: String? = null
        var durationMs = -1
        val avp = Fields.indirect(msg, cap, 3)                          // assets vector (field 3)
        if (avp != 0) {
            val an = msg.le32(avp)
            if (an in 0..63 && avp + 4 + an * 4 <= msg.size) for (j in 0 until an) {
                val a = Fields.follow(msg, avp + 4 + j * 4)
                val aid = Fields.firstHexId(msg, a) ?: continue
                val union = Fields.byte(msg, a, 1)                     // f1 = AssetMetadata union type
                val meta = Fields.indirect(msg, a, 2)                   // f2 = nested metadata table
                var imgType = -1; var camera = -1; var ev = 0f
                if (meta != 0) {
                    if (union == Capture.UNION_MEDIA) durationMs = Fields.u32(msg, meta, 0)   // VideoAssetMetadata.durationMs
                    else {                                                                    // ImageAssetMetadata
                        camera = Fields.byte(msg, meta, 0)              // sourceCamera (1=none/2=left/4=right)
                        imgType = Fields.byte(msg, meta, 1)             // imageType (1=thumb/2=full)
                        ev = Fields.float(msg, meta, 2)                 // ev: bracket offset per frame
                    }
                }
                assetIds += aid
                when {
                    union == Capture.UNION_IMAGE && imgType == Capture.IMGTYPE_THUMB -> thumb = aid
                    union == Capture.UNION_MEDIA || (union == Capture.UNION_IMAGE && imgType == Capture.IMGTYPE_FULL) ->
                        full += Capture.FullFrame(aid, camera, ev)
                    union == Capture.UNION_IMU -> imu = aid
                    union == Capture.UNION_TIMING -> timing = aid
                }
                if (DUMP_RAW) log("    asset[$j] id=${aid.abbr()} union=$union imgType=$imgType cam=$camera")
            }
        }
        return Capture(captureId, type, timestampNs, durationMs, assetIds, thumb, full, imu, timing)
    }

    // --------------------------------------------------------------------------- device info (pill)

    fun requestSystemInfo() = guarded("requestSystemInfo") { invoke(ServiceRegistry.GET_SYSTEM_INFO, SVC_GET_SYSTEM_INFO) }

    fun requestDeviceState() = guarded("requestDeviceState") {
        val id = sid(ServiceRegistry.DEVICE_STATE, SVC_DEVICE_STATE)    // real id is allocated (e.g. 0x44), not 0x3f
        log("[ctrl] -> device_state (svc 0x${id.toString(16)})")
        sendEncrypted(Requests.invoke(id, nextReqId++, Requests.EMPTY_TABLE))
    }

    private fun parseSystemInfo(msg: ByteArray) {
        try {
            val root = Fields.root(msg, 16)
            info.socBuildInfo = Fields.string(msg, root, 0)
            info.mcuBuildInfo = Fields.string(msg, root, 1)
            info.serial = Fields.string(msg, root, 2)
            info.deviceUuid = Fields.string(msg, root, 5)
            info.model = Fields.string(msg, root, 6)
            info.pendingOtaVersion = Fields.string(msg, root, 12)      // pendingCloudOtaVersion
            info.lastOtaVersion = Fields.string(msg, root, 14)         // lastCloudOtaUpdateVersion (human FW version)
            log("[ctrl] system_info: serial=${info.serial} model=${info.model} socFw=${info.socBuildInfo}" +
                " mcuFw=${info.mcuBuildInfo} lastOta=${info.lastOtaVersion} pendingOta=${info.pendingOtaVersion}")
            listener?.onDeviceInfo(info)
        } catch (e: Exception) {
            log("[ctrl] parseSystemInfo error: $e")
        }
    }

    /** The glasses stream SOC diagnostic JSON carrying the live battery level + charge state. */
    private fun parseTelemetryBattery(pt: ByteArray) {
        try {
            val s = String(pt, Charsets.ISO_8859_1)
            val lvl = BATTERY_LEVEL.find(s)?.groupValues?.get(1)?.toInt() ?: return
            var chgStatus = info.glassesChargingStatus
            TO_STATE.find(s)?.groupValues?.get(1)?.let { st ->
                chgStatus = if (st.contains("charging") && !st.contains("discharging")) 2 else 3
            }
            info.glassesBatteryPct = lvl
            info.glassesChargingStatus = chgStatus
            log("[ctrl] telemetry: glasses battery=$lvl% chgStatus=$chgStatus")
            listener?.onDeviceInfo(info)
        } catch (_: Exception) {
        }
    }

    private fun parseDeviceState(msg: ByteArray) {
        try {
            if (RX_DEBUG) log("[ctrl] device_state RAW ${msg.size}B fb=${msg.copyOfRange(16, msg.size).toHex(96)}")
            val root = Fields.root(msg, 16)
            val ds = Fields.indirect(msg, root, 0)                       // DeviceState table
            if (ds == 0) return
            val bs = Fields.indirect(msg, ds, 0)                         // BatteryStateTable (glasses)
            if (bs != 0) {
                info.glassesBatteryPct = Fields.u32(msg, bs, 0)
                info.glassesChargingStatus = Fields.byte(msg, bs, 2)
                info.glassesChargerType = Fields.byte(msg, bs, 3)
                info.glassesTempDeciC = Fields.u32(msg, bs, 4)
                info.glassesHealth = Fields.byte(msg, bs, 5)
                info.glassesVoltageMv = Fields.u32(msg, bs, 6)
            }
            val ts = Fields.indirect(msg, ds, 11)                        // TitanStateTable (case)
            if (ts != 0) {
                info.caseSerial = Fields.string(msg, ts, 0)
                info.caseBatteryPct = Fields.byte(msg, ts, 1)            // titanSoc
            }
            info.lowStorage = Fields.byte(msg, ds, 2) > 0
            info.zeroStorage = Fields.byte(msg, ds, 3) > 0
            log("[ctrl] device_state: glasses=${info.glassesBatteryPct}% chgStatus=${info.glassesChargingStatus}" +
                " case=${info.caseBatteryPct}% temp=${info.glassesTempDeciC / 10.0}C")
            listener?.onDeviceInfo(info)
        } catch (e: Exception) {
            log("[ctrl] parseDeviceState error: $e")
        }
    }

    // ------------------------------------------------------------------------- MCU settings (get/set)
    // Settings ride stella:mcu:setting_request as McuSettingRequest{op, entry} and come back as
    // McuSettingResponse{result, entry}. Ops are SERIALIZED on a single worker (one in flight, latch-released by
    // parseMcuSetting) so back-to-back reads/writes don't race a shared id.

    private val settingsExec = Executors.newSingleThreadExecutor { r -> Thread(r, "ctrl-settings") }
    @Volatile private var curSettingEnum = -1
    @Volatile private var curSettingCallback: SettingCallback? = null
    @Volatile private var curSettingLatch: CountDownLatch? = null

    /** Read a single MCU setting. Result via [cb] (and [DeviceInfo] for the pill settings). */
    fun readSetting(settingEnum: Int, cb: SettingCallback?) = enqueueSetting(Requests.OP_READ, settingEnum, 0L, cb)

    /** Write a single MCU setting. Callers should read back to confirm. */
    fun writeSetting(settingEnum: Int, value: Long, cb: SettingCallback?) = enqueueSetting(Requests.OP_WRITE, settingEnum, value, cb)

    /** Fetch the two pill settings (video duration + earcon volume), serialized. */
    fun requestSettings() {
        readSetting(SETTING_VIDEO_DURATION_MS, null)
        readSetting(SETTING_USER_EARCON_VOLUME, null)
        // UserCaptureEarconDisable (0x8035) is privacy-locked (Write rejected, result=1) and not shown.
    }

    private fun enqueueSetting(op: Int, settingEnum: Int, value: Long, cb: SettingCallback?) {
        if (closed) { cb?.onError("closed"); return }
        settingsExec.execute {
            if (closed) { cb?.onError("closed"); return@execute }
            curSettingEnum = settingEnum; curSettingCallback = cb
            val latch = CountDownLatch(1)
            curSettingLatch = latch
            try {
                val id = sid(ServiceRegistry.MCU_SETTING, SVC_MCU_SETTING)
                log("[ctrl] -> mcu_setting ${if (op == Requests.OP_WRITE) "WRITE" else "read"} 0x${settingEnum.toString(16)}" +
                    (if (op == Requests.OP_WRITE) " = $value" else "") + " (svc 0x${id.toString(16)})")
                sendEncrypted(Requests.invoke(id, nextReqId++, Requests.mcuSetting(op, settingEnum, value)))
            } catch (e: Exception) {
                log("[ctrl] mcu_setting send error: $e")
                cb?.onError("send: $e")
                latch.countDown()
            }
            try {
                if (!latch.await(6, TimeUnit.SECONDS)) cb?.onError("timeout")
            } catch (_: InterruptedException) {
                return@execute
            }
            curSettingCallback = null; curSettingEnum = -1; curSettingLatch = null
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
        }
    }

    /** McuSettingResponse{result:u8(f0), entry:McuSettingEntry{settingEnum:u32(f0), value:i64(f1)}(f1)}. */
    private fun parseMcuSetting(msg: ByteArray) {
        try {
            val root = Fields.root(msg, 16)
            val result = Fields.byte(msg, root, 0).coerceAtLeast(0)     // elided => Success(0)
            var en = curSettingEnum
            var value = 0L
            val ent = Fields.indirect(msg, root, 1)
            if (ent != 0) {
                val echoed = (Fields.i64(msg, ent, 0) and 0xffffffffL).toInt()   // settingEnum echo (may be 0)
                if (echoed != 0) en = echoed
                value = Fields.i64(msg, ent, 1)
            }
            log("[ctrl] <- mcu_setting 0x${en.toString(16)} value=$value result=$result")
            if (result == 0) {
                when (en) {
                    SETTING_VIDEO_DURATION_MS -> info.videoDurationMs = value.toInt()
                    SETTING_USER_EARCON_VOLUME -> info.earconVolume = value.toInt()
                    SETTING_USER_CAPTURE_EARCON_DISABLE -> info.captureEarconDisable = value.toInt()
                }
                listener?.onDeviceInfo(info)
            }
            curSettingCallback?.onValue(en, value, result)
        } catch (e: Exception) {
            log("[ctrl] parseMcuSetting error: $e")
        } finally {
            curSettingLatch?.countDown()
        }
    }

    // --------------------------------------------------------------- BLE asset fetch (thumbnails)
    // get_asset_content_v2 responses carry no reqId, so the reassembly is stamped with the requesting reqId at
    // its offset=0 chunk and delivered only if that still equals curAssetReqId. A LATE response for a timed-out
    // request is dropped instead of being misrouted onto the NEXT request's gallery cell.

    private val assetQueue = LinkedBlockingQueue<Pair<String, (Result<ByteArray>) -> Unit>>()
    @Volatile private var curAssetCallback: ((Result<ByteArray>) -> Unit)? = null
    @Volatile private var curAssetLatch: CountDownLatch? = null
    @Volatile private var curAssetReqId = -1
    @Volatile private var assetOwner = -2
    @Volatile private var assetsPaused = false
    private var assetWorker: Thread? = null

    /**
     * Pause/resume the thumbnail worker. Held during a full-res WiFi-Direct download: BLE GETs would otherwise
     * contend with the download for the radio/SOC and stall past their await window, and a stalled thumbnail
     * becomes a straggler the reqId-less response protocol could misroute.
     */
    fun pauseAssets(paused: Boolean) { assetsPaused = paused }

    /** Fetch an asset's bytes over BLE (the thumbnail). Serialized, 20 s timeout each. */
    fun fetchAssetBytes(assetId: String, cb: (Result<ByteArray>) -> Unit) {
        assetQueue.add(assetId to cb)
        startAssetWorker()
    }

    @Synchronized
    private fun startAssetWorker() {
        if (assetWorker?.isAlive == true) return
        assetWorker = thread(name = "ctrl-assets") {
            try {
                while (!closed) {
                    val (assetId, cb) = assetQueue.poll(45, TimeUnit.SECONDS) ?: break
                    // Yield the BLE link while a full-res download owns the radio.
                    while (assetsPaused && !closed) Thread.sleep(100)
                    if (closed) { cb(Result.failure(IllegalStateException("closed"))); break }
                    val rid = nextReqId++
                    val latch = CountDownLatch(1)
                    curAssetCallback = cb; curAssetReqId = rid; curAssetLatch = latch
                    val t0 = SystemClock.elapsedRealtime()
                    try {
                        log("[thumb] -> req#$rid ${assetId.abbr()}")
                        sendEncrypted(Requests.invoke(sid(ServiceRegistry.GET_ASSET, SVC_GET_ASSET), rid, Requests.getAssetContentV2(assetId)))
                    } catch (e: Exception) {
                        cb(Result.failure(e)); latch.countDown()
                    }
                    if (!latch.await(20, TimeUnit.SECONDS)) {
                        log("[thumb] req#$rid TIMEOUT after ${SystemClock.elapsedRealtime() - t0}ms")
                        cb(Result.failure(IllegalStateException("timeout")))
                    }
                    curAssetCallback = null; curAssetLatch = null; curAssetReqId = -1
                    Thread.sleep(150)   // let the glasses breathe
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun onAssetResponse(msg: ByteArray) {
        val content = Fields.firstByteVector(msg, 16)
        val root = Fields.root(msg, 16)
        val result = Fields.u32(msg, root, 1)
        log("[ctrl] <- asset ${msg.size}B content=${content?.size?.let { "${it}B" } ?: "none result=$result"} hdr=${msg.toHex(16)}")
        val cb = curAssetCallback
        // Deliver ONLY to the request that owns this reassembly; a drifted owner means a late/misrouted response.
        if (cb == null || assetOwner != curAssetReqId) {
            log("[ctrl] (stale/late asset response ignored: owner=$assetOwner cur=$curAssetReqId)")
            return
        }
        if (content != null) cb(Result.success(content))
        else cb(Result.failure(IllegalStateException("result=" + (ASSET_RESULTS.getOrNull(result) ?: result.toString()))))
        curAssetLatch?.countDown()
    }

    // ------------------------------------------------------------- WiFi-Direct webserver (full-res)

    /**
     * Bring up WiFi-Direct stationmode + the glasses webserver ONCE; [onReady] fires when GETs can be issued.
     * Event-driven: [joined] is tripped by `WifiDirectHost.awaitClientJoin` when the glasses actually join the
     * phone's P2P group, and the webserver's :443 warm-up is absorbed by the GET retry in [WebserverClient].
     * [onFail] always fires on error so the caller's in-flight state never wedges.
     */
    fun prepareWebserver(ssid: String, pass: String, freq: Int, joined: CountDownLatch?, onReady: () -> Unit, onFail: () -> Unit) {
        thread(name = "ctrl-webprep") {
            try {
                log("[ctrl] -> stationmode_connect (ssid=$ssid)")
                invoke(ServiceRegistry.STATIONMODE, SVC_STATIONMODE, Requests.staModeConnect(ssid, pass, freq, WEB_NETMASK, WEB_TOKEN, WEB_IDLE))
                // Cap our own wait too so a lost broadcast can't hang the thread.
                joined?.let { runCatching { it.await(20, TimeUnit.SECONDS) } }
                log("[ctrl] -> start_webserver")
                invoke(ServiceRegistry.START_WEBSERVER, SVC_START_WEBSERVER, Requests.startWebserver(WEB_TOKEN, WEB_IDLE))
                webReady.countDown()
                log("[ctrl] webserver ready")
                onReady()
            } catch (e: Exception) {
                log("[ctrl] prepareWebserver error: $e")
                onFail()
            }
        }
    }

    /** Blocks until the webserver is prepared (or the channel closes). */
    private fun awaitWebserver(): Boolean = try { webReady.await(30, TimeUnit.SECONDS) && !closed } catch (_: InterruptedException) { false }

    /**
     * Download ONE asset over the (already-up) webserver with live byte progress and save it as
     * `stories_<captureId first 8><suffix>.<jpg|mp4>` in DCIM/StoriesClient.
     */
    fun downloadOne(assetId: String, captureId: String, suffix: String, authToken: String?, cb: DownloadCallback) {
        thread(name = "ctrl-dlone") {
            try {
                if (closed) { cb.onError("closed"); return@thread }
                if (!awaitWebserver()) { cb.onError("webserver unreachable"); return@thread }
                val r = web.getAsset(assetId, authToken, progress = cb::onProgress, stop = { closed })
                    ?: run { cb.onError("webserver unreachable"); return@thread }
                val ext = when {
                    r.isJpeg() -> "jpg"
                    r.isMp4() -> "mp4"
                    else -> { cb.onError("not media: ${r.take(2).toByteArray().toHex()} ${r.size}B"); return@thread }
                }
                log("[dl] ${assetId.abbr()} -> ${r.size}B ${if (ext == "jpg") "JPEG " + MediaSaver.jpegDims(r) else "MP4"}")
                val saved = MediaSaver.save(appCtx, r, captureId, suffix, ext, log)
                if (saved != null) cb.onSaved(saved, if (ext == "jpg") "image/jpeg" else "video/mp4") else cb.onError("save failed")
            } catch (e: Exception) {
                log("[ctrl] downloadOne error: $e"); cb.onError(e.toString())
            }
        }
    }

    /**
     * Download a NON-media sidecar (the IMU/gyro telemetry blob) over the webserver and save it raw as
     * `stories_<captureId first 8>.bin` alongside the video. Read-only GET, same transport as the MP4.
     */
    fun downloadSidecar(assetId: String, captureId: String, authToken: String?, cb: (Result<MediaSaver.Saved>) -> Unit) {
        thread(name = "ctrl-dlbin") {
            try {
                if (closed) { cb(Result.failure(IllegalStateException("closed"))); return@thread }
                if (!awaitWebserver()) { cb(Result.failure(IllegalStateException("webserver unreachable"))); return@thread }
                val r = web.getAsset(assetId, authToken, stop = { closed })
                    ?: run { cb(Result.failure(IllegalStateException("webserver unreachable"))); return@thread }
                log("[ctrl] sidecar ${assetId.abbr()} -> ${r.size}B head=${r.toHex(16)}")
                val saved = MediaSaver.save(appCtx, r, captureId, "", "bin", log)
                cb(if (saved != null) Result.success(saved) else Result.failure(IllegalStateException("save failed")))
            } catch (e: Exception) {
                log("[ctrl] downloadSidecar error: $e"); cb(Result.failure(e))
            }
        }
    }

    // ------------------------------------------------------------------ delete (destructive)

    @Volatile private var curDeleteCallback: ((Result<String>) -> Unit)? = null
    @Volatile private var curDeleteId: String? = null

    /**
     * Delete a capture off the glasses: IMMEDIATE + DESTRUCTIVE (the firmware recursively unlinks the capture
     * dir + all assets). Only call after the media is safely saved to the phone. Refuses anything but a
     * 32-char captureId: historically an empty/short id made the glasses delete ALL captures.
     */
    fun deleteCapture(captureId: String?, cb: (Result<String>) -> Unit) {
        if (captureId == null || captureId.length != 32) {
            log("[ctrl] deleteCapture REFUSED — captureId not 32-hex: $captureId")
            cb(Result.failure(IllegalArgumentException("bad captureId (${captureId?.length ?: "null"} chars)")))
            return
        }
        try {
            curDeleteCallback = cb; curDeleteId = captureId
            log("[ctrl] -> delete_capture ${captureId.abbr()}")
            invoke(ServiceRegistry.DELETE_CAPTURE, SVC_DELETE_CAPTURE, Requests.deleteCapture(captureId))
        } catch (e: Exception) {
            log("[ctrl] deleteCapture error: $e"); cb(Result.failure(e))
        }
    }

    private fun onDeleteResponse(msg: ByteArray) {
        val root = Fields.root(msg, 16)
        val result = Fields.u32(msg, root, 0)                            // best-effort ack/result code if present
        log("[ctrl] <- delete_capture ack ${msg.size}B result=$result")
        val cb = curDeleteCallback; val id = curDeleteId
        curDeleteCallback = null; curDeleteId = null
        if (cb != null && id != null) cb(Result.success(id))
    }

    // --------------------------------------------------------------------------------------- helpers

    private inline fun guarded(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { log("[ctrl] $what error: $e") }
    }

    /** Dump every vtable field of a FlatBuffer table with a best-effort interpretation (DUMP_RAW diagnostics). */
    private fun dumpTable(b: ByteArray, tbl: Int, label: String) {
        try {
            val vt = tbl - b.le32(tbl)
            val nf = (b.le16(vt) - 4) / 2
            val sb = StringBuilder("$label nf=$nf |")
            for (f in 0 until nf) {
                val fo = b.le16(vt + 4 + f * 2)
                if (fo == 0) continue
                val pp = tbl + fo
                val raw = b.le32(pp).toLong() and 0xffffffffL
                sb.append(" f$f=$raw")
                runCatching {
                    val sp = pp + raw.toInt(); val ln = b.le32(sp)
                    if (ln in 1..64 && sp + 4 + ln <= b.size) {
                        val s = String(b, sp + 4, ln, Charsets.ISO_8859_1).replace(Regex("[^\\x20-\\x7e]"), ".")
                        sb.append("(str/vec ln=$ln:${s.take(34)})")
                    }
                }
            }
            log(sb.toString())
        } catch (e: Exception) {
            log("$label dumperr $e")
        }
    }

    companion object {
        // MCU setting enum ids (stella.common.Uint32SettingsEnum); value is i64 in McuSettingEntry.
        const val SETTING_VIDEO_DURATION_MS = 0x8004          // ms (30000 / 60000)
        const val SETTING_USER_EARCON_VOLUME = 0x8036         // system-sounds level 0-100
        const val SETTING_USER_CAPTURE_EARCON_DISABLE = 0x8035 // bool: 0 = shutter sound on, 1 = off

        // Legacy fallback serviceIds (registration index + 0x30) used only until the allocated ids are learned.
        private const val SVC_GET_CAPTURE_INFO = 0x49
        private const val SVC_GET_ASSET = 0x4a
        private const val SVC_GET_SYSTEM_INFO = 0x4b
        private const val SVC_START_WEBSERVER = 0x4c
        private const val SVC_STATIONMODE = 0x50
        private const val SVC_DELETE_CAPTURE = 0x52
        private const val SVC_MCU_SETTING = 0x3c
        private const val SVC_DEVICE_STATE = 0x3f
        private const val SVC_DEVICE_STATE_UPDATE = 0x46       // stella:mcu:device_state_update (push)

        // webserver session parameters (from the capture)
        private const val WEB_TOKEN = 0x0024c29f               // shared requestToken (reused across the two RPCs)
        private const val WEB_IDLE = 415                       // idleTimeout seconds
        private const val WEB_NETMASK = 0xffffffee.toInt()     // GO-advertised netmask

        private val ASSET_RESULTS = listOf("Success", "InvalidAssetType", "AssetNotFound", "ReadFailure", "NoAssetStats")
        private val BATTERY_LEVEL = Regex("\"battery_level\":(\\d+)")
        private val TO_STATE = Regex("\"to_state\":\"([a-z_]+)\"")

        /** Diagnostic: log each registration sent + its allocated id (one-time at connect; low volume). */
        const val REG_DEBUG = true
        /** Diagnostic firehose: every inbound frame raw + device_state raw. Off unless decoding. */
        const val RX_DEBUG = false
        /** Diagnostic: dump per-table fields of get_capture_info. Flip on to debug capture parsing. */
        const val DUMP_RAW = false
    }
}
