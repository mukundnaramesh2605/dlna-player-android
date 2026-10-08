package com.dlnaplayer.android.repository

import com.dlnaplayer.android.model.SubtitleSource
import com.dlnaplayer.android.model.SubtitleTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SubtitleRepositoryTest {

    private val repository = SubtitleRepository()

    @Test
    fun testFindExternalSubtitles() {
        val tempDir = File("/tmp/sub_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val videoFile = File(tempDir, "AwesomeMovie.mkv").apply { createNewFile() }
        val exactSrt = File(tempDir, "AwesomeMovie.srt").apply { createNewFile() }
        val langSrt = File(tempDir, "AwesomeMovie.en.srt").apply { createNewFile() }
        val otherSrt = File(tempDir, "Unrelated.srt").apply { createNewFile() }
        val randomTxt = File(tempDir, "Notes.txt").apply { createNewFile() }

        try {
            val subs = repository.findExternalSubtitles(videoFile)
            assertEquals(3, subs.size)

            // Exact match should be marked as default
            val exactTrack = subs.find { it.title.contains("Exact Match") }
            assertNotNull(exactTrack)
            assertTrue(exactTrack!!.isDefault)
            assertTrue(exactTrack.source is SubtitleSource.ExternalFile)

            // Other matched files present
            assertTrue(subs.any { it.title.contains("AwesomeMovie.en.srt") })
            assertTrue(subs.any { it.title.contains("Unrelated.srt") })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testGetAvailableSubtitlesForMkv() = runBlocking {
        val mkvFile = File("/tmp/multi_sub.mkv")
        if (!mkvFile.exists()) return@runBlocking

        val subs = repository.getAvailableSubtitles(mkvFile)
        // Should include None + 2 embedded tracks
        assertTrue(subs.size >= 3)
        assertEquals(SubtitleTrack.NONE.id, subs[0].id)

        val embedded = subs.filter { it.source is SubtitleSource.EmbeddedMkv }
        assertEquals(2, embedded.size)
        assertTrue(embedded.any { it.title.contains("English") })
        assertTrue(embedded.any { it.title.contains("Espanol") })
    }
}
