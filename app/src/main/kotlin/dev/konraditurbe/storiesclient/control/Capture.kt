package dev.konraditurbe.storiesclient.control

import java.util.Locale
import kotlin.math.roundToInt

/**
 * One capture from `get_capture_info`, with its assets already classified by AssetMetadata union / imageType.
 *
 * Per-asset element = `{f0:assetId, f1:metadataType (stella.common.AssetMetadata union), f2:nested metadata}`.
 * union 1 = VideoAssetMetadata (the MP4), 2 = ImageAssetMetadata (photo frame / thumbnail / video poster),
 * 3 = ImuAssetMetadata (gyro/accel sidecar), 4 = TimingAssetMetadata. For images the nested
 * `ImageAssetMetadata{sourceCamera, imageType, ev}` splits thumbnail (imageType 1) from full frames (2).
 */
class Capture(
    val captureId: String,
    val type: Int,
    /** Capture time, ns since epoch (0 if unknown). */
    val timestampNs: Long,
    /** Video only (VideoAssetMetadata.durationMs); -1 if unknown. */
    val durationMs: Int,
    /** Every asset id, order preserved. */
    val assetIds: List<String>,
    /** The small BLE-fetchable thumbnail (~7KB): union 2 && imageType 1. */
    val thumbnailAssetId: String?,
    /** Full-res frames: photo frames (imageType 2) or the video MP4 (union 1), in listing order. */
    val fullFrames: List<FullFrame>,
    /** Gyro/accel telemetry sidecar (union 3), fetched as .bin for Gyroflow. */
    val imuAssetId: String?,
    /** Per-frame timing (union 4). */
    val timingAssetId: String?,
) {
    /** A full-res asset with its ImageAssetMetadata (camera and EV are meaningful for photos only). */
    class FullFrame(val assetId: String, val camera: Int, val ev: Float)

    val isVideo: Boolean get() = type == TYPE_VIDEO

    val fullAssetIds: List<String> get() = fullFrames.map { it.assetId }

    /** Video duration as "M:SS", or null (photos / unknown). */
    val durationLabel: String?
        get() {
            if (durationMs <= 0) return null
            val s = (durationMs + 500) / 1000
            return "${s / 60}:${String.format(Locale.US, "%02d", s % 60)}"
        }

    private val rightFrames: List<FullFrame> get() = fullFrames.filter { it.camera == CAM_RIGHT }

    /**
     * The single "main" full-res asset. Video = the MP4. Photo = the RIGHT-lens frame at the MEDIAN exposure:
     * the normal (0 EV) frame of an HDR bracket, or the primary right frame of a same-exposure burst. Falls back
     * to the first frame.
     */
    val mainAssetId: String?
        get() {
            if (fullFrames.isEmpty()) return null
            if (isVideo) return fullFrames[0].assetId
            val right = rightFrames.sortedBy { it.ev }
            if (right.isEmpty()) return fullFrames[0].assetId
            return right[right.size / 2].assetId
        }

    /**
     * True when the right-lens frames span DIFFERENT exposures: a real HDR bracket (e.g. −2/0/+2 EV) rather than
     * a same-exposure burst. Scene-dependent: the glasses bracket only high-dynamic-range scenes.
     */
    val isBracket: Boolean
        get() {
            if (isVideo) return false
            val evs = rightFrames.map { it.ev }
            return evs.size >= 2 && (evs.max() - evs.min()) >= 0.5f
        }

    /**
     * The extra RIGHT-lens frames beside the main, each with its filename suffix. For a bracket these are the
     * alternate exposures (`_ev-2`, `_ev+2`); otherwise the burst tail (`_b1`, `_b2`). Photos only.
     */
    fun extraRightFrames(): List<Pair<String, String>> {
        if (isVideo) return emptyList()
        val main = mainAssetId
        val bracket = isBracket
        var n = 1
        return rightFrames.filter { it.assetId != main }.map { f ->
            f.assetId to if (bracket) "_ev${evTag(f.ev)}" else "_b${n++}"
        }
    }

    /** The LEFT-camera stereo partner frame (distinct from [mainAssetId]), or null. Photos only. */
    val stereoAssetId: String?
        get() {
            if (isVideo) return null
            val main = mainAssetId
            return fullFrames.firstOrNull { it.camera == CAM_LEFT && it.assetId != main }?.assetId
        }

    /** First 8 chars of the capture id: the key shared by all of a capture's saved files. */
    val key8: String get() = captureId.take(8)

    override fun equals(other: Any?): Boolean = other is Capture && other.captureId == captureId
    override fun hashCode(): Int = captureId.hashCode()

    companion object {
        const val TYPE_PHOTO = 3073
        const val TYPE_VIDEO = 3074

        const val UNION_MEDIA = 1
        const val UNION_IMAGE = 2
        const val UNION_IMU = 3
        const val UNION_TIMING = 4
        const val IMGTYPE_THUMB = 1
        const val IMGTYPE_FULL = 2
        const val CAM_NONE = 1
        const val CAM_LEFT = 2
        const val CAM_RIGHT = 4

        /** EV -> filename tag: +2 -> "+2", -2 -> "-2", 0 -> "0". */
        private fun evTag(ev: Float): String {
            val r = ev.roundToInt()
            return if (r > 0) "+$r" else r.toString()
        }
    }
}
