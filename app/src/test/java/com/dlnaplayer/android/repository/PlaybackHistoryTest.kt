package com.dlnaplayer.android.repository

import com.dlnaplayer.android.model.DlnaDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryTest {

    @Test
    fun testPlaybackProgressFraction() {
        val progressHalf = PlaybackProgress(
            filePath = "/sdcard/Movies/sample.mp4",
            positionMs = 50000L,
            durationMs = 100000L,
            lastUpdated = System.currentTimeMillis()
        )
        assertEquals(0.5f, progressHalf.progressFraction, 0.001f)

        val progressZeroDuration = PlaybackProgress(
            filePath = "/sdcard/Movies/sample.mp4",
            positionMs = 50000L,
            durationMs = 0L,
            lastUpdated = System.currentTimeMillis()
        )
        assertEquals(0.0f, progressZeroDuration.progressFraction, 0.001f)
    }

    @Test
    fun testResumeEligibility() {
        // Less than 5 seconds watched: not eligible
        val tooEarly = PlaybackProgress(
            filePath = "/sdcard/Movies/sample.mp4",
            positionMs = 4000L,
            durationMs = 120000L,
            lastUpdated = System.currentTimeMillis()
        )
        assertFalse(tooEarly.isResumeEligible)

        // Watched 30 seconds of 2 minutes: eligible
        val midWatch = PlaybackProgress(
            filePath = "/sdcard/Movies/sample.mp4",
            positionMs = 30000L,
            durationMs = 120000L,
            lastUpdated = System.currentTimeMillis()
        )
        assertTrue(midWatch.isResumeEligible)

        // Near end of video (less than 15s remaining): not eligible
        val nearEnd = PlaybackProgress(
            filePath = "/sdcard/Movies/sample.mp4",
            positionMs = 110000L,
            durationMs = 120000L,
            lastUpdated = System.currentTimeMillis()
        )
        assertFalse(nearEnd.isResumeEligible)

        // Unknown duration but watched over 5s: eligible
        val unknownDuration = PlaybackProgress(
            filePath = "/sdcard/Movies/sample.mp4",
            positionMs = 10000L,
            durationMs = 0L,
            lastUpdated = System.currentTimeMillis()
        )
        assertTrue(unknownDuration.isResumeEligible)
    }

    @Test
    fun testDlnaDeviceDisplayName() {
        val standardDevice = DlnaDevice(
            udn = "uuid:test-123",
            friendlyName = "Samsung Smart Monitor",
            manufacturer = "Samsung",
            modelName = "M7",
            locationUrl = "http://192.168.1.100:9197/dmr",
            ipAddress = "192.168.1.100",
            avTransportControlUrl = "http://192.168.1.100:9197/upnp/control/AVTransport1"
        )
        assertEquals("Samsung Smart Monitor", standardDevice.displayName)

        // User assigned a custom constant name
        val renamedDevice = standardDevice.copy(customName = "Living Room Screen")
        assertEquals("Living Room Screen", renamedDevice.displayName)
    }
}
