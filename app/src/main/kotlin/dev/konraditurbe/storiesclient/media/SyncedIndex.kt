package dev.konraditurbe.storiesclient.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Which captures already exist on the phone: a `cid8 -> content URI` index of the MAIN frames saved in
 * MediaStore. A capture counts as "saved" (✓ badge, Open, safe-to-delete) ONLY when its suffix-less main frame
 * is present, not when only burst/left-lens variants exist; otherwise a variant-only download would read
 * "Saved" and the delete prompt could wipe the never-downloaded main frame off the glasses.
 */
class SyncedIndex(private val ctx: Context) {
    private val uris = ConcurrentHashMap<String, Uri>()

    fun contains(key8: String): Boolean = uris.containsKey(key8)
    operator fun get(key8: String): Uri? = uris[key8]

    /** Re-scan MediaStore (blocking; call off the UI thread). */
    fun refresh(log: (String) -> Unit = {}) {
        try {
            val fresh = HashMap<String, Uri>()
            scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fresh)
            scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, fresh)
            uris.keys.retainAll(fresh.keys)
            uris.putAll(fresh)
        } catch (e: Exception) {
            log("[sync] index error: $e")
        }
    }

    private fun scan(coll: Uri, into: MutableMap<String, Uri>) {
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val where = PREFIXES.joinToString(" OR ") { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" }
        val args = PREFIXES.map { "$it%" }.toTypedArray()
        ctx.contentResolver.query(coll, proj, where, args, null)?.use { cur ->
            val idCol = cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nmCol = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cur.moveToNext()) {
                val name = cur.getString(nmCol) ?: continue
                val prefix = PREFIXES.firstOrNull { name.startsWith(it) } ?: continue
                val preDot = name.removePrefix(prefix).substringBefore('.')   // "<cid8>" or "<cid8>_left" / "_b1"
                if (preDot.length != 8) continue                              // a suffix means a variant, not the main
                into[preDot] = ContentUris.withAppendedId(coll, cur.getLong(idCol))
            }
        }
    }

    private companion object {
        val PREFIXES = listOf(MediaSaver.FILE_PREFIX)
    }
}
