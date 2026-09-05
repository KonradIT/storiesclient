package dev.konraditurbe.storiesclient.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.konraditurbe.storiesclient.control.Capture
import dev.konraditurbe.storiesclient.media.MediaSaver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Full-screen capture detail overlay: a large preview (full-res if the capture is already synced to the phone,
 * else the BLE thumbnail), a metadata panel, the HDR|BURST · 0 · LEFT frame picker, and Download / Open /
 * Delete / Undistort actions with an in-place download progress bar. GONE until [show].
 */
class DetailView(ctx: Context, private val actions: Actions) : FrameLayout(ctx) {

    /** Host callbacks; the activity owns the session, downloads and MediaStore. */
    interface Actions {
        fun onDownload(c: Capture)
        fun onOpen(c: Capture)
        fun onDelete(c: Capture)
        fun onUndistort(c: Capture)
        fun onClose()
    }

    /** Frame-picker selection: what the Download button fetches. */
    enum class Mode { MAIN, BURST, LEFT }

    /** Everything [show] needs to know about a capture beyond the [Capture] itself. */
    class Presentation(
        val thumb: Bitmap?,
        val savedUri: Uri?,
        val synced: Boolean,
        val downloadable: Boolean,
        val canDelete: Boolean,
        val hasBurst: Boolean,
        val hasLeft: Boolean,
        val bracket: Boolean,
    )

    private val image: ImageView
    private val playGlyph: TextView
    private val title: TextView
    private val badge: TextView
    private val metaBox: LinearLayout
    private val pickerRow: LinearLayout
    private val burstBtn: Button
    private val mainBtn: Button
    private val leftBtn: Button
    private val dlBtn: Button
    private val openBtn: Button
    private val delBtn: Button
    private val undistortBtn: Button
    private val progress: ProgressBar
    private val progressLabel: TextView

    private var current: Capture? = null
    private var currentSynced = false
    private var hasBurst = false
    private var hasLeft = false
    private var loadingId: String? = null      // captureId whose full-res decode is in flight (staleness guard)

    /** The current picker selection. */
    var selectedMode = Mode.MAIN
        private set

    init {
        visibility = GONE
        isClickable = true                      // swallow taps so they don't reach the gallery underneath
        setBackgroundColor(Palette.SCRIM)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- header: ‹ Back | Photo/Video | ✓ Saved ----
        title = TextView(ctx).apply {
            setTextColor(Palette.TXT_LIGHT); textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        badge = TextView(ctx).apply {
            text = "✓ Saved"; setTextColor(Palette.CELL_GREEN); textSize = 13f; visibility = GONE
        }
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                text = "‹  Back"; setTextColor(Palette.CYAN); textSize = 16f
                setPadding(0, dp(4f), dp(10f), dp(4f))
                setOnClickListener { actions.onClose() }
            })
            addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4f) })
            addView(badge)
        })

        // ---- media (fills the space between header and metadata) ----
        image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        playGlyph = TextView(ctx).apply {
            text = "▶"; setTextColor(0xE6FFFFFF.toInt()); textSize = 52f
            setShadowLayer(dp(4f).toFloat(), 0f, 0f, Color.BLACK)
            visibility = GONE
            // tapping ▶ plays when saved, else kicks off the download
            setOnClickListener {
                val c = current ?: return@setOnClickListener
                if (currentSynced) actions.onOpen(c) else actions.onDownload(c)
            }
        }
        val mediaWrap = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(Palette.CARD_DARK); cornerRadius = dp(18f).toFloat() }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(18f).toFloat())
            }
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(playGlyph, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        }
        col.addView(mediaWrap, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(14f); bottomMargin = dp(14f)
        })

        // ---- metadata panel ----
        metaBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(metaBox)

        // ---- HDR|BURST · 0 · LEFT frame picker (photos with variants only) ----
        burstBtn = segButton("BURST", Mode.BURST)
        mainBtn = segButton("0", Mode.MAIN)
        leftBtn = segButton("LEFT", Mode.LEFT)
        pickerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = GONE
            addView(burstBtn, segLp()); addView(mainBtn, segLp()); addView(leftBtn, segLp())
        }
        col.addView(pickerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })

        // ---- download progress (hidden unless downloading) ----
        progressLabel = TextView(ctx).apply { setTextColor(Palette.CYAN); textSize = 13f; visibility = GONE }
        col.addView(progressLabel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = GONE }
        col.addView(progress, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })

        // ---- action row ----
        dlBtn = pillButton("Download", Palette.CYAN, Palette.CYAN_INK) { actions.onDownload(it) }
        openBtn = pillButton("Open", 0xFF2B2B2E.toInt(), Palette.TXT_LIGHT) { actions.onOpen(it) }
        delBtn = pillButton("Delete", 0xFF2B2B2E.toInt(), 0xFFFF6B6B.toInt()) { actions.onDelete(it) }
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(dlBtn, btnLp()); addView(openBtn, btnLp()); addView(delBtn, btnLp())
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })

        // secondary action: fisheye -> rectilinear (photos only, once the main is saved)
        undistortBtn = pillButton("Undistort → rectilinear", 0xFF17323A.toInt(), Palette.CYAN) { actions.onUndistort(it) }
        col.addView(undistortBtn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
    }

    // ------------------------------------------------------------------------- public API (UI thread)

    /** Populate + reveal the overlay for [c]. */
    fun show(c: Capture, p: Presentation) {
        current = c
        currentSynced = p.synced
        loadingId = null
        title.text = if (c.isVideo) "Video" else "Photo"
        badge.visibility = if (p.synced) VISIBLE else GONE
        playGlyph.visibility = if (c.isVideo) VISIBLE else GONE
        image.setImageBitmap(p.thumb)                       // immediate: thumbnail (upscaled), possibly null
        buildMeta(c, p.synced)
        hasBurst = p.hasBurst; hasLeft = p.hasLeft
        burstBtn.text = if (p.bracket) "HDR" else "BURST"
        selectedMode = Mode.MAIN
        val showPicker = !c.isVideo && (p.hasBurst || p.hasLeft)
        pickerRow.visibility = if (showPicker) VISIBLE else GONE
        if (showPicker) styleSegments()
        // Download stays available even when synced, so the burst/left-lens variants remain reachable.
        dlBtn.visibility = if (p.downloadable) VISIBLE else GONE
        openBtn.visibility = if (p.synced) VISIBLE else GONE
        delBtn.visibility = if (p.canDelete) VISIBLE else GONE
        undistortBtn.visibility = if (!c.isVideo && p.synced) VISIBLE else GONE
        hideProgress()
        if (p.synced && p.savedUri != null && !c.isVideo) loadFullResAsync(p.savedUri, c.captureId)
        visibility = VISIBLE
    }

    fun isShowing(captureId: String?): Boolean =
        visibility == VISIBLE && captureId != null && captureId == current?.captureId

    fun hide() {
        visibility = GONE; current = null; loadingId = null
    }

    /** 0..1 download progress, shown in-place. */
    fun setDownloadProgress(p: Float) {
        val frac = p.coerceIn(0f, 1f)
        progressLabel.text = "Downloading… ${(frac * 100).roundToInt()}%"
        progressLabel.visibility = VISIBLE
        progress.visibility = VISIBLE
        progress.progress = (frac * 1000).roundToInt()
        dlBtn.isEnabled = false
    }

    /** Download finished: flip to the synced presentation (✓, Open) + load the full-res preview. */
    fun markSynced(savedUri: Uri?) {
        hideProgress()
        currentSynced = true
        badge.visibility = VISIBLE
        openBtn.visibility = VISIBLE
        val c = current ?: return
        buildMeta(c, true)
        if (!c.isVideo) undistortBtn.visibility = VISIBLE
        if (savedUri != null && !c.isVideo) loadFullResAsync(savedUri, c.captureId)
    }

    fun hideProgress() {
        progress.visibility = GONE
        progressLabel.visibility = GONE
        dlBtn.isEnabled = true
    }

    // ------------------------------------------------------------------------------------- internals

    private fun buildMeta(c: Capture, synced: Boolean) {
        metaBox.removeAllViews()
        addMetaRow("Type", if (c.isVideo) "Video" else "Photo")
        if (c.timestampNs > 0) {
            addMetaRow("Captured", SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.US).format(Date(c.timestampNs / 1_000_000L)))
        }
        if (c.isVideo) c.durationLabel?.let { addMetaRow("Duration", it) }
        if (!c.isVideo) {
            val frames = c.fullFrames.size
            if (frames > 0) addMetaRow("Frames", "$frames " + if (frames == 1) "frame" else "frames")
        }
        addMetaRow("ID", c.captureId.let { if (it.length > 12) it.take(12) + "…" else it })
        addMetaRow("Saved", if (synced) "On phone · ${MediaSaver.LOCATION_LABEL}" else "On glasses only")
    }

    private fun addMetaRow(label: String, value: String) {
        metaBox.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4f), 0, dp(4f))
            addView(TextView(context).apply { text = label; setTextColor(Palette.TXT_LIGHT_MUTED); textSize = 13f },
                LinearLayout.LayoutParams(dp(96f), LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply { text = value; setTextColor(Palette.TXT_LIGHT); textSize = 13f },
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    /** Decode the saved image downsampled to ~screen width off the UI thread; apply only if still shown. */
    private fun loadFullResAsync(uri: Uri, captureId: String) {
        loadingId = captureId
        thread(name = "detail-decode") {
            try {
                val target = maxOf(1, resources.displayMetrics.widthPixels)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= target) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bm = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bm != null) post {
                    if (captureId == current?.captureId && captureId == loadingId) image.setImageBitmap(bm)
                }
            } catch (_: Exception) {
            }
        }
    }

    // ---- segmented picker ----

    private fun segButton(text: String, mode: Mode) = Button(context).apply {
        this.text = text; isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 13f
        setOnClickListener { selectMode(mode) }
    }

    private fun segLp() = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4f), 0, dp(4f), 0) }

    private fun selectMode(mode: Mode) {
        if (mode == Mode.BURST && !hasBurst) return      // unavailable: ignore the tap
        if (mode == Mode.LEFT && !hasLeft) return
        selectedMode = mode
        styleSegments()
    }

    /** Filled cyan = selected; cyan outline = available; dimmed = unavailable. */
    private fun styleSegments() {
        styleSegment(burstBtn, Mode.BURST, hasBurst)
        styleSegment(mainBtn, Mode.MAIN, true)
        styleSegment(leftBtn, Mode.LEFT, hasLeft)
    }

    private fun styleSegment(b: Button, mode: Mode, available: Boolean) {
        val selected = selectedMode == mode
        b.background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            if (selected) setColor(Palette.CYAN) else { setColor(0); setStroke(maxOf(1, dp(1f)), Palette.CYAN) }
        }
        b.setTextColor(if (selected) Palette.CYAN_INK else Palette.CYAN)
        b.isEnabled = available
        b.alpha = if (available) 1f else 0.35f
    }

    private fun pillButton(text: String, bg: Int, fg: Int, onClick: (Capture) -> Unit) = Button(context).apply {
        this.text = text; isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(fg); textSize = 14f
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(14f).toFloat() }
        setOnClickListener { current?.let(onClick) }
    }

    private fun btnLp() = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4f), 0, dp(4f), 0) }
}
