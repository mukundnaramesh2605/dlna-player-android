package com.dlnaplayer.android.repository

import com.dlnaplayer.android.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testListFolderWithSortingAndFiltering() = runBlocking {
        val root = tempFolder.newFolder("test_storage")

        // Create subdirectories and sample files
        val dirB = File(root, "Videos").apply { mkdir() }
        val dirA = File(root, "Audio").apply { mkdir() }
        val file1 = File(root, "sample.mp4").apply { writeBytes(ByteArray(1000)) }
        val file2 = File(root, "notes.pdf").apply { writeBytes(ByteArray(500)) }
        val file3 = File(root, "song.mp3").apply { writeBytes(ByteArray(2000)) }

        val repository = FileRepository()

        // 1. Test listing all files sorted by name ascending
        val resultAll = repository.listFolder(
            directory = root,
            onlyPlayableMedia = false,
            sortBy = SortBy.NAME,
            sortOrder = SortOrder.ASCENDING
        ).getOrThrow()

        // Directories first (Audio, Videos), then files (notes.pdf, sample.mp4, song.mp3)
        assertEquals(5, resultAll.size)
        assertTrue(resultAll[0].isDirectory)
        assertEquals("Audio", resultAll[0].name)
        assertTrue(resultAll[1].isDirectory)
        assertEquals("Videos", resultAll[1].name)
        assertEquals("notes.pdf", resultAll[2].name)
        assertEquals("sample.mp4", resultAll[3].name)
        assertEquals("song.mp3", resultAll[4].name)

        // 2. Test playable media filter (should exclude notes.pdf, but keep directories)
        val resultMediaOnly = repository.listFolder(
            directory = root,
            onlyPlayableMedia = true,
            sortBy = SortBy.NAME,
            sortOrder = SortOrder.ASCENDING
        ).getOrThrow()

        assertEquals(4, resultMediaOnly.size)
        assertEquals("Audio", resultMediaOnly[0].name)
        assertEquals("Videos", resultMediaOnly[1].name)
        assertEquals("sample.mp4", resultMediaOnly[2].name)
        assertEquals("song.mp3", resultMediaOnly[3].name)

        // 3. Test sort by size descending
        val resultBySize = repository.listFolder(
            directory = root,
            onlyPlayableMedia = false,
            sortBy = SortBy.SIZE,
            sortOrder = SortOrder.DESCENDING
        ).getOrThrow()

        // song.mp3 (2000B), sample.mp4 (1000B), notes.pdf (500B)
        val fileItems = resultBySize.filter { !it.isDirectory }
        assertEquals("song.mp3", fileItems[0].name)
        assertEquals("sample.mp4", fileItems[1].name)
        assertEquals("notes.pdf", fileItems[2].name)
    }
}
