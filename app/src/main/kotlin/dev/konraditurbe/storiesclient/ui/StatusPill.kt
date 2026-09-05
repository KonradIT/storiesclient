package dev.konraditurbe.storiesclient.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/**
 * Warm "Status Pill" device card: device name + battery %, a battery bar, a coarse storage bar, three status
 * rows (connection, firmware, case) and two settings rows (video length, system sounds). Grays out the
 * unavailable items and shows "Disconnected" when the link drops.
 */
class StatusPill(ctx: Context) : LinearLayout(ctx) {

    /** Fired when the user taps a settings radio. Raw values: video = ms (30000/60000); sounds = 0-100 level. */
    interface SettingsListener {
        fun onVideoDurationPick(ms: Int)
        fun onSystemSoundsPick(level: Int)
    }

    var settingsListener: SettingsListener? = null

    private val name: TextView
    private val pct: TextView
    private val connLabel: TextView
    private val fwLabel: TextView
    private val caseLabel: TextView
    private val storageLabel: TextView
    private val connDot: View
    private val fwDot: View
    private val caseDot: View
    private val bar: Bar
    private val storageBar: Bar
    private val videoGroup = RadioGroup(ctx)
    private val soundsGroup = RadioGroup(ctx)
    private var suppress = false   // guards programmatic check() from echoing back as a user pick

    init {
        orientation = VERTICAL
        setPadding(dp(22f), dp(20f), dp(22f), dp(20f))
        background = GradientDrawable().apply {
            setColor(Palette.CARD_BG); cornerRadius = context.dpF(28f)
            setStroke(maxOf(1, dp(1f)), Palette.CARD_BORDER)
        }
        elevation = context.dpF(10f)
        if (Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = Palette.SHADOW_PURPLE
            outlineAmbientShadowColor = Palette.SHADOW_PURPLE
        }

        // header: name (left) + battery % (right)
        name = text(17f, bold = true, Palette.TEXT_DARK).apply { text = "RB Stories" }
        pct = text(15f, bold = true, Palette.TEXT_DARK).apply { text = "—" }
        addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(pct, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        bar = Bar(ctx)
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(8f)).apply { topMargin = dp(16f) })

        // storage bar (glasses report a 3-level state only: OK/Low/Full)
        storageLabel = text(11f, bold = false, Palette.TEXT_MUTED).apply { text = "Storage —" }
        addView(storageLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })
        storageBar = Bar(ctx)
        addView(storageBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(8f)).apply { topMargin = dp(6f) })

        // status rows
        connDot = dot(); connLabel = text(12f, bold = false, Palette.TEXT_MUTED).apply { text = "Disconnected" }
        addView(statusRow(connDot, connLabel, dp(16f)))
        fwDot = dot(); fwLabel = text(12f, bold = false, Palette.TEXT_MUTED).apply { text = "FW —" }
        addView(statusRow(fwDot, fwLabel, dp(9f)))
        caseDot = dot(); caseLabel = text(12f, bold = false, Palette.TEXT_MUTED).apply { text = "Undocked" }
        addView(statusRow(caseDot, caseLabel, dp(9f)))

        // settings: two horizontal radio rows
        addView(settingRow("Video", videoGroup, VIDEO_LABELS, VIDEO_VALUES, dp(14f)))
        addView(settingRow("Sounds", soundsGroup, SOUND_LABELS, SOUND_VALUES, dp(4f)))
        videoGroup.setOnCheckedChangeListener { g, id -> onPick(g, id) }
        soundsGroup.setOnCheckedChangeListener { g, id -> onPick(g, id) }

        setDisconnected()
    }

    private fun onPick(g: RadioGroup, checkedId: Int) {
        if (suppress || checkedId == -1) return
        val listener = settingsListener ?: return
        val value = g.findViewById<View>(checkedId)?.tag as? Int ?: return
        if (g === videoGroup) listener.onVideoDurationPick(value) else listener.onSystemSoundsPick(value)
    }

    // ---- public state API ----

    fun setDeviceName(n: String?) { name.text = n?.takeIf { it.isNotEmpty() } ?: "RB Stories" }

    fun setConnecting() {
        connDot.tint(Palette.DOT_GRAY)
        connLabel.text = "Connecting…"; connLabel.setTextColor(Palette.TEXT_MUTED)
    }

    fun setConnected() {
        connDot.tint(Palette.GREEN)
        connLabel.text = "Connected · Bluetooth"; connLabel.setTextColor(Palette.TEXT_MUTED)
    }

    /** Gray out battery + firmware (unknown) and show "Disconnected". */
    fun setDisconnected() {
        connDot.tint(Palette.DOT_GRAY)
        connLabel.text = "Disconnected"; connLabel.setTextColor(Palette.TEXT_OFF)
        pct.text = "—"; pct.setTextColor(Palette.TEXT_OFF)
        bar.set(0f, false)
        storageBar.set(0f, false)
        storageLabel.text = "Storage —"; storageLabel.setTextColor(Palette.TEXT_OFF)
        fwLabel.text = "FW —"; fwLabel.setTextColor(Palette.TEXT_OFF)
        fwDot.tint(Palette.DOT_GRAY)
        caseLabel.text = "Undocked"; caseLabel.setTextColor(Palette.TEXT_OFF)
        caseDot.tint(Palette.DOT_GRAY)
        setSettingsEnabled(false)
    }

    fun setBattery(p: Int, charging: Boolean) {
        if (p < 0) {
            pct.text = "—"; pct.setTextColor(Palette.TEXT_OFF); bar.set(0f, false); return
        }
        pct.text = "$p%" + if (charging) " ⚡" else ""
        pct.setTextColor(Palette.TEXT_DARK)
        bar.set(p / 100f, true)
    }

    fun setFw(fw: String?) {
        fwLabel.text = if (fw != null) "FW $fw" else "FW —"
        fwLabel.setTextColor(Palette.TEXT_MUTED)
        fwDot.tint(Palette.DOT_GRAY)
    }

    /** Case battery: "Case XX%" when docked (0..100), else "Undocked". */
    fun setCase(pct: Int) {
        if (pct in 0..100) {
            caseLabel.text = "Case $pct%"; caseLabel.setTextColor(Palette.TEXT_MUTED); caseDot.tint(Palette.GREEN)
        } else {
            caseLabel.text = "Undocked"; caseLabel.setTextColor(Palette.TEXT_OFF); caseDot.tint(Palette.DOT_GRAY)
        }
    }

    /** Storage as a 3-level "space left" bar: green full = plenty, amber sliver = low, red sliver = full. */
    fun setStorage(low: Boolean, zero: Boolean) {
        when {
            zero -> { storageBar.set(0.03f, true, Palette.RED); storageLabel.text = "Storage full"; storageLabel.setTextColor(Palette.RED) }
            low -> { storageBar.set(0.15f, true, Palette.AMBER); storageLabel.text = "Storage low"; storageLabel.setTextColor(Palette.AMBER) }
            else -> { storageBar.set(1f, true, Palette.GREEN); storageLabel.text = "Storage OK"; storageLabel.setTextColor(Palette.TEXT_MUTED) }
        }
    }

    /** Reflect the current video-length setting (ms) in the radio without echoing a user pick. */
    fun setVideoDuration(ms: Int) = selectByTag(videoGroup, ms)

    /** Reflect the current system-sounds level in the radio; an unknown level clears the selection. */
    fun setSystemSounds(level: Int) = selectByTag(soundsGroup, level)

    private fun selectByTag(g: RadioGroup, value: Int) {
        suppress = true
        var match = -1
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            c.isEnabled = true
            if (c.tag == value) match = c.id
        }
        if (match != -1) g.check(match) else g.clearCheck()
        suppress = false
    }

    private fun setSettingsEnabled(en: Boolean) {
        suppress = true
        for (g in arrayOf(videoGroup, soundsGroup)) {
            for (i in 0 until g.childCount) g.getChildAt(i).isEnabled = en
            if (!en) g.clearCheck()
        }
        suppress = false
    }

    // ---- view construction helpers ----

    /** A "label  ( ) opt ( ) opt …" row: a fixed-width label + a horizontal RadioGroup of tagged buttons. */
    private fun settingRow(labelText: String, group: RadioGroup, labels: Array<String>, values: IntArray, topMargin: Int): LinearLayout {
        val tint = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Palette.DOT_GRAY, Palette.GREEN),
        )
        group.orientation = RadioGroup.HORIZONTAL
        for (i in labels.indices) {
            group.addView(RadioButton(context).apply {
                id = View.generateViewId()
                tag = values[i]
                text = labels[i]
                textSize = 12f
                setTextColor(Palette.TEXT_DARK)
                buttonTintList = tint
                setPadding(dp(4f), 0, dp(12f), 0)
            }, RadioGroup.LayoutParams(RadioGroup.LayoutParams.WRAP_CONTENT, RadioGroup.LayoutParams.WRAP_CONTENT))
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text(12f, bold = false, Palette.TEXT_MUTED).apply { text = labelText }, LayoutParams(dp(52f), LayoutParams.WRAP_CONTENT))
            addView(group, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.topMargin = topMargin }
        }
    }

    private fun statusRow(dot: View, label: TextView, topMargin: Int) = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(dot, LayoutParams(dp(6f), dp(6f)).also { it.rightMargin = dp(8f) })
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { it.topMargin = topMargin }
    }

    private fun dot() = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.DOT_GRAY) }
    }

    private fun View.tint(color: Int) { (background as? GradientDrawable)?.setColor(color) }

    private fun text(sp: Float, bold: Boolean, color: Int) = TextView(context).apply {
        textSize = sp
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }

    /** Rounded bar: gray track + colored fill at a fraction (fill hidden when inactive/disconnected). */
    private class Bar(c: Context) : View(c) {
        private var frac = 0f
        private var active = false
        private var fill = Palette.GREEN
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        fun set(f: Float, act: Boolean, color: Int = Palette.GREEN) {
            frac = f.coerceIn(0f, 1f); active = act; fill = color; invalidate()
        }

        override fun onDraw(cv: Canvas) {
            val h = height.toFloat(); val rad = h / 2f
            p.color = Palette.TRACK
            r.set(0f, 0f, width.toFloat(), h)
            cv.drawRoundRect(r, rad, rad, p)
            if (active && frac > 0f) {
                p.color = fill
                r.set(0f, 0f, maxOf(h, width * frac), h)   // min width = h so the rounded cap renders
                cv.drawRoundRect(r, rad, rad, p)
            }
        }
    }

    companion object {
        val VIDEO_VALUES = intArrayOf(30000, 60000)
        val VIDEO_LABELS = arrayOf("30s", "60s")
        // UserEarconVolume is a 0-100 scale (live device read = 90). These are the discrete stops we expose;
        // 90 = top step so a stock device highlights "High".
        val SOUND_VALUES = intArrayOf(30, 60, 90)
        val SOUND_LABELS = arrayOf("Low", "Med", "High")
    }
}
