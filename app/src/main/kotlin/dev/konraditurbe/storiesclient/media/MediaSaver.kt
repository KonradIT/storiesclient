package dev.konraditurbe.storiesclient.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.konraditurbe.storiesclient.util.isJpeg
import dev.konraditurbe.storiesclient.util.isMp4
import java.io.File
import java.io.FileOutputStream

/**
 * Saves downloaded assets to the phone as `stories_<cid8><suffix>.<ext>` in `DCIM/StoriesClient`.
 *
 * All of a capture's files share the `stories_<cid8>` prefix (main frame = no suffix; `_left` / `_b1` /
 * `_ev+2` for the stereo/burst/bracket variants; `_rect` for the undistorted copy) so the gallery's synced
 * index groups them by that first-8 key. Media goes through MediaStore so it shows in the system gallery;
 * the non-media IMU `.bin` sidecar lands next to the MP4 when All-Files-Access is granted, else in Download,
 * else in the app's private pictures dir.
 */
object MediaSaver {
    const val FILE_PREFIX = "stories_"
    const val RELATIVE_DIR = "StoriesClient"
    private val relativePath get() = "${Environment.DIRECTORY_DCIM}/$RELATIVE_DIR"

    /** Filename for a capture asset: `stories_<first 8 of captureId><suffix>.<ext>`. */
    fun fileName(captureId: String, suffix: String, ext: String) = "$FILE_PREFIX${captureId.take(8)}$suffix.$ext"

    /** Human-readable save location, for status text. */
    const val LOCATION_LABEL = "DCIM/$RELATIVE_DIR"

    sealed class Saved(val name: String) {
        class InMediaStore(name: String, val uri: Uri) : Saved(name)
        class OnDisk(name: String, val file: File) : Saved(name)
    }

    /** Save [data] (jpg / mp4 via MediaStore, anything else as a raw file). Returns null if every location failed. */
    fun save(ctx: Context, data: ByteArray, captureId: String, suffix: String, ext: String, log: (String) -> Unit): Saved? {
        val fname = fileName(captureId, suffix, ext)
        val dim = if (ext == "jpg") jpegDims(data) else ""
        val mime = when (ext) { "jpg" -> "image/jpeg"; "mp4" -> "video/mp4"; else -> null }
        if (mime != null) {
            insertMedia(ctx, fname, mime, data)?.let { uri ->
                log("[save] *** SAVED ${data.size}B $dim-> $LOCATION_LABEL/$fname (gallery) ***")
                return Saved.InMediaStore(fname, uri)
            }
            log("[save] MediaStore save failed — falling back to file")
        }
        val candidates = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), RELATIVE_DIR),
            File("/sdcard/Download"),
            ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
        )
        for (dir in candidates) {
            if (dir == null) continue
            try {
                dir.mkdirs()
                val out = File(dir, fname)
                FileOutputStream(out).use { it.write(data) }
                log("[save] *** SAVED ${data.size}B $dim-> ${out.absolutePath} ***")
                return Saved.OnDisk(fname, out)
            } catch (e: Exception) {
                log("[save] save to $dir failed: $e")
            }
        }
        return null
    }

    /** Insert a media file into MediaStore under DCIM/StoriesClient. Returns its content URI, or null. */
    fun insertMedia(ctx: Context, fname: String, mime: String, data: ByteArray): Uri? = try {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fname)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val coll = if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = ctx.contentResolver
        resolver.insert(coll, cv)?.also { uri -> resolver.openOutputStream(uri)?.use { it.write(data) } }
    } catch (e: Exception) {
        null
    }

    /** File extension from the leading magic bytes: jpg / mp4 / bin. */
    fun extensionFor(data: ByteArray): String = when {
        data.isJpeg() -> "jpg"
        data.isMp4() -> "mp4"
        else -> "bin"
    }

    /** Parse JPEG SOF0/1/2 for "WxH " (best-effort, empty if not found). */
    fun jpegDims(d: ByteArray): String {
        try {
            var i = 2
            while (i + 9 < d.size) {
                if (d[i].toInt() and 0xff != 0xff) { i++; continue }
                val m = d[i + 1].toInt() and 0xff
                if (m == 0xc0 || m == 0xc1 || m == 0xc2) {
                    val h = ((d[i + 5].toInt() and 0xff) shl 8) or (d[i + 6].toInt() and 0xff)
                    val w = ((d[i + 7].toInt() and 0xff) shl 8) or (d[i + 8].toInt() and 0xff)
                    return "${w}x$h "
                }
                if (m == 0xd8 || m in 0xd0..0xd7) { i += 2; continue }
                i += 2 + (((d[i + 2].toInt() and 0xff) shl 8) or (d[i + 3].toInt() and 0xff))
            }
        } catch (_: Exception) {
        }
        return ""
    }
}
