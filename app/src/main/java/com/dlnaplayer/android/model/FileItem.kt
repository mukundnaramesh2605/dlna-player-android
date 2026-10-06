package com.dlnaplayer.android.model

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Categorization of files for DLNA compatibility.
 * DLNA MediaRenderers exclusively handle audio, video, and image media streams.
 */
enum class MediaType {
    VIDEO,
    AUDIO,
    IMAGE,
    OTHER
}

/**
 * Model representing a file or directory in the storage hierarchy.
 */
data class FileItem(
    val file: File,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
    val mediaType: MediaType,
    val childCount: Int = 0
) {
    /**
     * DLNA renderers can only stream Audio, Video, and Image files.
     * Documents, APKs, archives, etc. are browsable but not castable.
     */
    val isCastable: Boolean
        get() = !isDirectory && mediaType != MediaType.OTHER

    val extension: String
        get() = if (isDirectory) "" else file.extension.lowercase(Locale.ROOT)

    val formattedSize: String
        get() {
            if (isDirectory) {
                return if (childCount == 1) "1 item" else "$childCount items"
            }
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            val value = size / Math.pow(1024.0, digitGroups.toDouble())
            return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            return sdf.format(Date(lastModified))
        }

    companion object {
        fun fromFile(file: File, mimeType: String? = null, childCount: Int = 0): FileItem {
            val isDir = file.isDirectory
            val size = if (isDir) 0L else file.length()
            val ext = file.extension.lowercase(Locale.ROOT)
            val resolvedMime = mimeType ?: guessMimeType(ext)
            val mediaType = when {
                isDir -> MediaType.OTHER
                resolvedMime.startsWith("video/") || ext in VIDEO_EXTENSIONS -> MediaType.VIDEO
                resolvedMime.startsWith("audio/") || ext in AUDIO_EXTENSIONS -> MediaType.AUDIO
                resolvedMime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> MediaType.IMAGE
                else -> MediaType.OTHER
            }

            return FileItem(
                file = file,
                name = file.name,
                path = file.absolutePath,
                isDirectory = isDir,
                size = size,
                lastModified = file.lastModified(),
                mimeType = resolvedMime,
                mediaType = mediaType,
                childCount = childCount
            )
        }

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "ts", "flv", "wmv", "vob", "ogv"
        )

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "aac", "flac", "wav", "m4a", "ogg", "oga", "opus", "wma", "alac", "aiff"
        )

        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"
        )

        fun guessMimeType(extension: String): String {
            return when (extension.lowercase(Locale.ROOT)) {
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "m4v" -> "video/x-m4v"
                "3gp" -> "video/3gpp"
                "ts" -> "video/mp2t"
                "flv" -> "video/x-flv"
                "wmv" -> "video/x-ms-wmv"

                "mp3" -> "audio/mpeg"
                "aac" -> "audio/aac"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "m4a" -> "audio/mp4"
                "ogg", "oga" -> "audio/ogg"
                "opus" -> "audio/opus"

                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"

                else -> "application/octet-stream"
            }
        }
    }
}
