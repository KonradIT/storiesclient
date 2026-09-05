package dev.konraditurbe.storiesclient.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * A squircle gallery cell: rounded thumbnail with a centered loading dot, an optional play glyph + bottom-left
 * duration for videos, a top-right green "synced" check, and a cyan download-progress ring stroked around the
 * border. All state is driven by the adapter via the setters.
 */
class CaptureCell(ctx: Context) : FrameLayout(ctx) {

    private val radius = context.dpF(22f)
    private val ringStroke = context.dpF(4f)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ringStroke; strokeCap = Paint.Cap.ROUND; color = Palette.CYAN
    }
    private val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ringStroke; color = 0x33FFFFFF
    }
    private val ringRect = RectF()
    private val ringPath = Path()
    private val segPath = Path()
    private val pm = PathMeasure()
    private var progress = -1f            // -1 = ring hidden, else 0..1

    val thumb: ImageView
    private val placeholder: TextView
    private val playGlyph: TextView
    private val badge: TextView
    private val duration: TextView

    init {
        setWillNotDraw(false)
        background = GradientDrawable().apply { setColor(Palette.CARD_DARK); cornerRadius = radius }

        thumb = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        addView(thumb, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        placeholder = centered("·", 0xFF666666.toInt(), 18f).also(::addView)

        playGlyph = centered("▶", 0xE6FFFFFF.toInt(), 26f).apply {
            setShadowLayer(dp(3f).toFloat(), 0f, 0f, Color.BLACK)
            visibility = GONE
        }.also(::addView)

        duration = TextView(ctx).apply {
            setTextColor(Color.WHITE); textSize = 11f
            setShadowLayer(dp(3f).toFloat(), 0f, 0f, Color.BLACK)
            visibility = GONE
        }
        addView(duration, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(8f), 0, 0, dp(6f))
        })

        badge = TextView(ctx).apply {
            text = "✓"; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.CELL_GREEN) }
            visibility = GONE
        }
        val bs = dp(24f)
        addView(badge, LayoutParams(bs, bs).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(6f), dp(6f), 0)
        })
    }

    private fun centered(t: String, color: Int, size: Float) = TextView(context).apply {
        text = t; setTextColor(color); textSize = size; gravity = Gravity.CENTER
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
    }

    /** Force square cells (height = the column width the grid assigns). */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, widthSpec)
        val side = measuredWidth
        setMeasuredDimension(side, side)
    }

    // ---- state setters (driven by the adapter) ----

    fun reset() {
        thumb.setImageDrawable(null)
        placeholder.visibility = VISIBLE
        playGlyph.visibility = GONE
        duration.visibility = GONE
        badge.visibility = GONE
        progress = -1f
        alpha = 1f
        invalidate()
    }

    fun setThumb(bm: Bitmap) { thumb.setImageBitmap(bm); placeholder.visibility = GONE }
    fun setVideo(v: Boolean) { playGlyph.visibility = if (v) VISIBLE else GONE }
    fun setDuration(s: String?) {
        duration.text = s
        duration.visibility = if (s == null) GONE else VISIBLE
    }
    fun setSynced(s: Boolean) { badge.visibility = if (s) VISIBLE else GONE }
    fun setDimmed(d: Boolean) { alpha = if (d) 0.4f else 1f }

    /** 0..1 to show the cyan ring at that fraction, or -1 to hide it. */
    fun setProgress(p: Float) { progress = p; invalidate() }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (progress < 0) return
        val h = ringStroke / 2f + context.dpF(1f)
        ringRect.set(h, h, width - h, height - h)
        val r = maxOf(0f, radius - h)
        ringPath.reset()
        ringPath.addRoundRect(ringRect, r, r, Path.Direction.CW)
        canvas.drawPath(ringPath, ringTrack)
        pm.setPath(ringPath, false)
        val len = pm.length
        segPath.reset()
        if (len > 0 && pm.getSegment(0f, progress.coerceIn(0.001f, 1f) * len, segPath, true)) {
            canvas.drawPath(segPath, ringPaint)
        }
    }
}
