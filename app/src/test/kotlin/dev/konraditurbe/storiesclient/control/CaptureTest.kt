package dev.konraditurbe.storiesclient.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTest {
    private fun photo(frames: List<Capture.FullFrame>) = Capture(
        captureId = "0123456789abcdef0123456789abcdef", type = Capture.TYPE_PHOTO, timestampNs = 1L,
        durationMs = -1, assetIds = frames.map { it.assetId }, thumbnailAssetId = "thumb",
        fullFrames = frames, imuAssetId = null, timingAssetId = null,
    )

    @Test
    fun bracketPicksMedianExposureAndNamesVariantsByEv() {
        val c = photo(listOf(
            Capture.FullFrame("r-2", Capture.CAM_RIGHT, -2f),
            Capture.FullFrame("r0", Capture.CAM_RIGHT, 0f),
            Capture.FullFrame("r+2", Capture.CAM_RIGHT, 2f),
            Capture.FullFrame("left", Capture.CAM_LEFT, 0f),
        ))
        assertTrue(c.isBracket)
        assertEquals("r0", c.mainAssetId)
        assertEquals(listOf("r-2" to "_ev-2", "r+2" to "_ev+2"), c.extraRightFrames())
        assertEquals("left", c.stereoAssetId)
    }

    @Test
    fun burstUsesNumberedSuffixes() {
        val c = photo(listOf(
            Capture.FullFrame("a", Capture.CAM_RIGHT, 0f),
            Capture.FullFrame("b", Capture.CAM_RIGHT, 0f),
            Capture.FullFrame("c", Capture.CAM_RIGHT, 0f),
        ))
        assertFalse(c.isBracket)
        assertEquals("b", c.mainAssetId)   // median of an all-equal set = the middle frame
        assertEquals(listOf("a" to "_b1", "c" to "_b2"), c.extraRightFrames())
        assertNull(c.stereoAssetId)
    }

    @Test
    fun videoExposesMp4AndDuration() {
        val c = Capture(
            captureId = "0123456789abcdef0123456789abcdef", type = Capture.TYPE_VIDEO, timestampNs = 1L,
            durationMs = 61_400, assetIds = listOf("mp4", "imu"), thumbnailAssetId = "thumb",
            fullFrames = listOf(Capture.FullFrame("mp4", -1, 0f)), imuAssetId = "imu", timingAssetId = null,
        )
        assertTrue(c.isVideo)
        assertEquals("mp4", c.mainAssetId)
        assertEquals("1:01", c.durationLabel)
        assertTrue(c.extraRightFrames().isEmpty())
        assertEquals("01234567", c.key8)
    }
}
