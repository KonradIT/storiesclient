package dev.konraditurbe.storiesclient.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.konraditurbe.storiesclient.control.Capture

/**
 * Squircle grid of captures. Thumbnails are requested lazily for bound (visible) cells through
 * [ThumbSource]; the adapter caches decoded bitmaps by captureId and guards against recycled cells.
 */
class GalleryAdapter(
    private val isSynced: (Capture) -> Boolean,
    private val onCellClick: (Capture) -> Unit,
) : RecyclerView.Adapter<GalleryAdapter.VH>() {

    /** Asynchronous thumbnail provider (BLE fetch); [onBitmap] may be called on any thread. */
    fun interface ThumbSource {
        fun fetch(capture: Capture, onBitmap: (Bitmap?) -> Unit)
    }

    /** Set while a session is up; null disables thumbnail fetching. */
    var thumbSource: ThumbSource? = null

    private val items = ArrayList<Capture>()
    private val thumbCache = HashMap<String, Bitmap>()
    private val thumbInFlight = HashSet<String>()

    class VH(val cell: CaptureCell) : RecyclerView.ViewHolder(cell) {
        var boundId: String? = null
    }

    fun setItems(caps: List<Capture>) {
        items.clear(); items.addAll(caps); notifyDataSetChanged()
    }

    fun remove(c: Capture) {
        val i = items.indexOf(c)
        if (i >= 0) { items.removeAt(i); notifyItemRemoved(i) }
    }

    fun cachedThumb(captureId: String): Bitmap? = thumbCache[captureId]

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val cell = CaptureCell(parent.context)
        cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return VH(cell)
    }

    override fun onBindViewHolder(vh: VH, pos: Int) {
        val c = items[pos]
        vh.boundId = c.captureId
        vh.cell.reset()
        vh.cell.setVideo(c.isVideo)
        vh.cell.setDuration(if (c.isVideo) c.durationLabel else null)
        val synced = isSynced(c)
        vh.cell.setSynced(synced)
        vh.cell.setDimmed(!synced && c.fullFrames.isEmpty())
        vh.cell.setOnClickListener { onCellClick(c) }
        bindThumb(vh, c)
    }

    private fun bindThumb(vh: VH, c: Capture) {
        val cid = c.captureId
        thumbCache[cid]?.let { vh.cell.setThumb(it); return }
        val source = thumbSource ?: return
        if (c.thumbnailAssetId == null) return
        synchronized(thumbInFlight) { if (!thumbInFlight.add(cid)) return }   // already fetching this one
        source.fetch(c) { bm ->
            synchronized(thumbInFlight) { thumbInFlight.remove(cid) }
            if (bm == null) return@fetch
            vh.cell.post {
                thumbCache[cid] = bm
                if (vh.boundId == cid) vh.cell.setThumb(bm)
            }
        }
    }

    /** Equal [gap] spacing between cells in a [span]-column grid (between rows too, excluding outer edges). */
    class GridSpacing(private val span: Int, private val gap: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, s: RecyclerView.State) {
            val pos = parent.getChildAdapterPosition(view)
            if (pos < 0) return
            val col = pos % span
            out.left = gap - col * gap / span
            out.right = (col + 1) * gap / span
            out.top = if (pos < span) 0 else gap
        }
    }
}
