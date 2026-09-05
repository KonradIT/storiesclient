package dev.konraditurbe.storiesclient.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import kotlin.math.roundToInt

/** Shared colors: the warm light surface (pill/gallery) and the dark squircle/cyan overlay theme. */
object Palette {
    // warm light surface
    const val BG_CREAM = 0xFFF4F1EB.toInt()
    const val CARD_BG = 0xFFFFFFFF.toInt()
    const val CARD_BORDER = 0xFFEDE8E1.toInt()
    const val TEXT_DARK = 0xFF2B2722.toInt()
    const val TEXT_MUTED = 0xFF8C867D.toInt()
    const val TEXT_OFF = 0xFFBFB9AE.toInt()      // grayed / unavailable
    const val GREEN = 0xFF55B66E.toInt()
    const val AMBER = 0xFFE0A23A.toInt()         // storage getting low
    const val RED = 0xFFD9534F.toInt()           // storage full
    const val DOT_GRAY = 0xFFD8D2C8.toInt()
    const val TRACK = 0xFFE2DCD2.toInt()
    const val SHADOW_PURPLE = 0xFFAE84F2.toInt()

    // dark overlay (detail view, log drawer, gallery cells)
    const val DARK_BG = 0xFF101012.toInt()
    const val SCRIM = 0xF2101012.toInt()
    const val CARD_DARK = 0xFF1C1C1E.toInt()
    const val TXT_LIGHT = 0xFFF2F0EC.toInt()
    const val TXT_LIGHT_MUTED = 0xFF9A948B.toInt()
    const val CYAN = 0xFF00E5FF.toInt()
    const val CYAN_INK = 0xFF05121A.toInt()      // text on a cyan fill
    const val CELL_GREEN = 0xFF2ECC71.toInt()
}

/** dp -> px for a view, rounded to whole pixels. */
fun View.dp(v: Float): Int = context.dp(v)

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

fun Context.dpF(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
