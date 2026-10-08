package com.dlnaplayer.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MkvSubtitleExtractorTest {

    @Test
    fun testEmbeddedTracksDiscovery() {
        val testFile = File("/tmp/multi_sub.mkv")
        if (!testFile.exists()) return // Skip if file not on runner

        val tracks = MkvSubtitleExtractor.getEmbeddedSubtitleTracks(testFile)
        println("Discovered tracks: $tracks")
        assertEquals(2, tracks.size)

        val track1 = tracks[0]
        assertEquals("English (Full)", track1.title)
        assertEquals("eng", track1.language)
        assertEquals("S_TEXT/UTF8", track1.codec)

        val track2 = tracks[1]
        assertEquals("Espanol", track2.title)
        assertEquals("spa", track2.language)
    }

    @Test
    fun testTrackExtractionToSrt() {
        val testFile = File("/tmp/multi_sub.mkv")
        if (!testFile.exists()) return

        val outFile = File("/tmp/extracted_track_2.srt")
        if (outFile.exists()) outFile.delete()

        val result = MkvSubtitleExtractor.extractTrackToSrt(
            mkvFile = testFile,
            targetTrackNumber = 2L,
            codec = "S_TEXT/UTF8",
            outputFile = outFile
        )

        assertTrue(result.isSuccess)
        assertTrue(outFile.exists())
        val content = outFile.readText()
        println("Extracted content:\n$content")
        assertTrue(content.contains("First English subtitle"))
        assertTrue(content.contains("Second English subtitle"))
        assertTrue(content.contains("00:00:01,000 --> 00:00:03,000"))
    }
}
