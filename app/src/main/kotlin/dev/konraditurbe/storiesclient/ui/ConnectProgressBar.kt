package dev.konraditurbe.storiesclient.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A determinate Material-3-style linear progress indicator, custom-drawn to match the app's hand-rendered
 * look: fully rounded track + active indicator, a small gap before a "stop indicator" dot at the track end.
 * Used for the glasses connection flow (0% idle -> 100% media list loaded); progress changes animate.
 */
class ConnectProgressBar(c: Context) : View(c) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TRACK }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.GREEN }
    private val r = RectF()

    /** 0..1, the animated value actually drawn. */
    var progress = 0f
        private set
    private var anim: ValueAnimator? = null

    /** Animate to [frac] (0..1). */
    fun setProgress(frac: Float) {
        val target = frac.coerceIn(0f, 1f)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Jump immediately (no animation), e.g. reset to 0 on disconnect. */
    fun setProgressImmediate(frac: Float) {
        anim?.cancel()
        progress = frac.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val density = resources.displayMetrics.density
        setMeasuredDimension(resolveSize((density * 200).toInt(), widthSpec), resolveSize((density * 6).toInt(), heightSpec))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        if (w <= 0 || h <= 0) return
        val rad = h / 2f
        val dot = h                       // stop-indicator diameter == bar thickness
        val gap = h * 0.7f                // gap before the stop indicator
        val trackEnd = w - dot

        // mirror horizontally in RTL so the bar fills from the trailing edge
        val save = if (layoutDirection == LAYOUT_DIRECTION_RTL) c.save().also { c.scale(-1f, 1f, w / 2f, h / 2f) } else -1

        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, rad, rad, track)

        val activeEnd = progress * (trackEnd - gap)
        if (activeEnd > 0.5f) {
            r.set(0f, 0f, maxOf(h, activeEnd), h)   // min length so a tiny value still shows a rounded nub
            c.drawRoundRect(r, rad, rad, active)
        }

        r.set(w - dot, 0f, w, h)
        c.drawRoundRect(r, rad, rad, active)

        if (save != -1) c.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim?.cancel()   // don't leak the Activity via a running animator
    }
}
