package com.dlnaplayer.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileItemTest {

    @Test
    fun testMediaTypeDetection() {
        val videoFile = FileItem.fromFile(File("/test/video.mp4"))
        assertEquals(MediaType.VIDEO, videoFile.mediaType)
        assertTrue(videoFile.isCastable)

        val mkvFile = FileItem.fromFile(File("/test/movie.mkv"))
        assertEquals(MediaType.VIDEO, mkvFile.mediaType)
        assertTrue(mkvFile.isCastable)

        val audioFile = FileItem.fromFile(File("/test/song.mp3"))
        assertEquals(MediaType.AUDIO, audioFile.mediaType)
        assertTrue(audioFile.isCastable)

        val flacFile = FileItem.fromFile(File("/test/track.flac"))
        assertEquals(MediaType.AUDIO, flacFile.mediaType)
        assertTrue(flacFile.isCastable)

        val imageFile = FileItem.fromFile(File("/test/photo.jpg"))
        assertEquals(MediaType.IMAGE, imageFile.mediaType)
        assertTrue(imageFile.isCastable)

        val docFile = FileItem.fromFile(File("/test/document.pdf"))
        assertEquals(MediaType.OTHER, docFile.mediaType)
        assertFalse(docFile.isCastable)

        val zipFile = FileItem.fromFile(File("/test/archive.zip"))
        assertEquals(MediaType.OTHER, zipFile.mediaType)
        assertFalse(zipFile.isCastable)
    }

    @Test
    fun testMimeTypeResolution() {
        assertEquals("video/mp4", FileItem.guessMimeType("mp4"))
        assertEquals("video/x-matroska", FileItem.guessMimeType("mkv"))
        assertEquals("audio/mpeg", FileItem.guessMimeType("mp3"))
        assertEquals("audio/flac", FileItem.guessMimeType("flac"))
        assertEquals("image/jpeg", FileItem.guessMimeType("jpeg"))
        assertEquals("image/png", FileItem.guessMimeType("png"))
        assertEquals("application/octet-stream", FileItem.guessMimeType("xyz"))
    }
}
