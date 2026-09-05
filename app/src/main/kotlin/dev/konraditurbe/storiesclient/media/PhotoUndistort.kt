package dev.konraditurbe.storiesclient.media

import android.graphics.Bitmap
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Fisheye -> rectilinear undistortion for Ray-Ban Stories photos (2592x1944), OpenCV "fisheye"
 * (Kannala-Brandt) model. Pure per-pixel remap, no OpenCV dependency.
 *
 * Constants come from the lens calibration (Gyroflow profile `rayban_stories_1414.gyroflow.json`) derived to
 * the 4:3 photo resolution. `Knew` is the balance=0 output camera matrix (crops to no black).
 */
object PhotoUndistort {
    // source fisheye camera matrix K
    private const val FX = 1859.4623; private const val FY = 1859.4623
    private const val CX = 1281.2488; private const val CY = 962.1942
    // Kannala-Brandt distortion k1..k4
    private const val K1 = 0.309166; private const val K2 = -1.081670
    private const val K3 = 1.169614; private const val K4 = -0.481037
    // output (undistorted) camera matrix Knew, balance=0
    private const val NFX = 1739.8729; private const val NFY = 1739.8729
    private const val NCX = 1275.2132; private const val NCY = 960.4864

    const val SRC_W = 2592
    const val SRC_H = 1944

    /** True if this bitmap is the photo resolution this calibration is for. */
    fun supports(b: Bitmap?): Boolean = b != null && b.width == SRC_W && b.height == SRC_H

    /**
     * Undistort a 2592x1944 fisheye photo to rectilinear (same size, black where the source has no data).
     * Roughly 1-2 s for 5 MP on a background thread. Returns a new bitmap; the caller may recycle [src].
     */
    fun undistort(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val s = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val d = IntArray(w * h)
        for (v in 0 until h) {
            val b = (v - NCY) / NFY
            val row = v * w
            for (u in 0 until w) {
                val a = (u - NCX) / NFX
                val r = sqrt(a * a + b * b)
                val th = atan(r)                                    // undistorted normalized radius = tan(theta)
                val t2 = th * th
                val td = th * (1 + t2 * (K1 + t2 * (K2 + t2 * (K3 + t2 * K4))))   // theta_d (Horner)
                val scale = if (r > 1e-9) td / r else 1.0
                d[row + u] = sample(s, w, h, FX * (a * scale) + CX, FY * (b * scale) + CY)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(d, 0, w, 0, 0, w, h) }
    }

    /** Bilinear sample; opaque black outside the source. */
    private fun sample(s: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= w || y0 + 1 >= h) return 0xFF000000.toInt()
        val fx = x - x0; val fy = y - y0
        val w00 = (1 - fx) * (1 - fy); val w01 = fx * (1 - fy); val w10 = (1 - fx) * fy; val w11 = fx * fy
        val i = y0 * w + x0
        val p00 = s[i]; val p01 = s[i + 1]; val p10 = s[i + w]; val p11 = s[i + w + 1]
        fun ch(sh: Int): Int = (((p00 shr sh) and 0xFF) * w00 + ((p01 shr sh) and 0xFF) * w01 +
            ((p10 shr sh) and 0xFF) * w10 + ((p11 shr sh) and 0xFF) * w11 + 0.5).toInt()
        return 0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
