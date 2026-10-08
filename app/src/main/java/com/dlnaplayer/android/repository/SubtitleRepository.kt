package com.dlnaplayer.android.repository

import android.content.Context
import com.dlnaplayer.android.model.SubtitleSource
import com.dlnaplayer.android.model.SubtitleTrack
import com.dlnaplayer.android.util.MkvSubtitleExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class SubtitleRepository {

    /**
     * Discovers all available subtitles for a video file:
     *  1. "None (Off)"
     *  2. Embedded tracks inside MKV/WebM files
     *  3. Matching external (.srt, .vtt) sidecar files in the same directory
     *  4. Any other subtitle files present in the same directory
     */
    suspend fun getAvailableSubtitles(videoFile: File): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleTrack>()
        results.add(SubtitleTrack.NONE)

        // 1. Embedded MKV tracks
        val ext = videoFile.extension.lowercase(Locale.ROOT)
        if (ext == "mkv" || ext == "webm") {
            val embedded = MkvSubtitleExtractor.getEmbeddedSubtitleTracks(videoFile)
            results.addAll(embedded)
        }

        // 2. External sidecar subtitle files in the same directory
        val external = findExternalSubtitles(videoFile)
        results.addAll(external)

        results
    }

    /**
     * Looks for .srt and .vtt files in the same folder as the video.
     * Prioritizes files whose names start with the video's base name.
     */
    fun findExternalSubtitles(videoFile: File): List<SubtitleTrack> {
        val parentDir = videoFile.parentFile ?: return emptyList()
        val videoBaseName = videoFile.nameWithoutExtension.lowercase(Locale.ROOT)

        val subFiles = parentDir.listFiles { file ->
            if (file.isFile && file.canRead()) {
                val fExt = file.extension.lowercase(Locale.ROOT)
                fExt == "srt" || fExt == "vtt" || fExt == "sub"
            } else {
                false
            }
        } ?: emptyArray()

        // Sort matching files: matching prefix first, then alphabetical
        val sorted = subFiles.sortedWith(
            compareByDescending<File> {
                it.nameWithoutExtension.lowercase(Locale.ROOT).startsWith(videoBaseName)
            }.thenBy { it.name }
        )

        return sorted.map { file ->
            val name = file.name
            val isExactMatch = file.nameWithoutExtension.equals(videoFile.nameWithoutExtension, ignoreCase = true)
            val title = if (isExactMatch) "External: $name (Exact Match)" else "External: $name"

            SubtitleTrack(
                id = "ext_${file.absolutePath.hashCode()}",
                title = title,
                source = SubtitleSource.ExternalFile(file),
                isDefault = isExactMatch
            )
        }
    }

    /**
     * Resolves the actual File on disk that can be served over HTTP.
     * Extracts embedded tracks to app cache if needed.
     */
    suspend fun prepareSubtitleFile(context: Context, track: SubtitleTrack): File? = withContext(Dispatchers.IO) {
        when (val source = track.source) {
            is SubtitleSource.None -> null
            is SubtitleSource.ExternalFile -> {
                if (source.file.exists() && source.file.canRead()) source.file else null
            }
            is SubtitleSource.EmbeddedMkv -> {
                val cacheDir = File(context.cacheDir, "subtitles").apply { mkdirs() }
                val cacheFileName = "mkv_${source.mkvFile.name.hashCode()}_track_${source.trackNumber}.srt"
                val cachedFile = File(cacheDir, cacheFileName)

                if (cachedFile.exists() && cachedFile.length() > 0) {
                    cachedFile
                } else {
                    val extractResult = MkvSubtitleExtractor.extractTrackToSrt(
                        mkvFile = source.mkvFile,
                        targetTrackNumber = source.trackNumber,
                        codec = source.codec,
                        outputFile = cachedFile
                    )
                    extractResult.getOrNull()
                }
            }
        }
    }
}
