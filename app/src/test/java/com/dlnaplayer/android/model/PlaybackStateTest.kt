package com.dlnaplayer.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStateTest {

    @Test
    fun testFormatTime() {
        assertEquals("00:00", PlaybackState.formatTime(0L))
        assertEquals("00:45", PlaybackState.formatTime(45000L))
        assertEquals("05:30", PlaybackState.formatTime(330000L))
        assertEquals("01:15:20", PlaybackState.formatTime(4520000L))
    }

    @Test
    fun testParseTimeString() {
        assertEquals(0L, PlaybackState.parseTimeString(null))
        assertEquals(0L, PlaybackState.parseTimeString(""))
        assertEquals(0L, PlaybackState.parseTimeString("NOT_IMPLEMENTED"))

        assertEquals(320000L, PlaybackState.parseTimeString("00:05:20"))
        assertEquals(5400000L, PlaybackState.parseTimeString("01:30:00"))
        assertEquals(65000L, PlaybackState.parseTimeString("01:05.123"))
    }

    @Test
    fun testProgressCalculation() {
        val state = PlaybackState(
            positionMs = 30000L,
            durationMs = 60000L
        )
        assertEquals(0.5f, state.progress, 0.001f)

        val stateZeroDuration = PlaybackState(
            positionMs = 30000L,
            durationMs = 0L
        )
        assertEquals(0f, stateZeroDuration.progress, 0.001f)
    }
}
