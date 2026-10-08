package com.dlnaplayer.android.model

import java.io.File

/**
 * Origin of a subtitle track.
 */
sealed class SubtitleSource {
    /** Subtitles turned off */
    data object None : SubtitleSource()

    /** An external subtitle file on local storage (e.g. .srt, .vtt) */
    data class ExternalFile(val file: File) : SubtitleSource()

    /** An embedded subtitle track inside a Matroska (.mkv) video file */
    data class EmbeddedMkv(
        val mkvFile: File,
        val trackNumber: Long,
        val codec: String
    ) : SubtitleSource()
}

/**
 * Represents a subtitle track available for the currently playing media.
 */
data class SubtitleTrack(
    val id: String,
    val title: String,
    val language: String? = null,
    val codec: String? = null,
    val source: SubtitleSource,
    val isDefault: Boolean = false,
    val isForced: Boolean = false
) {
    val displayLabel: String
        get() {
            val parts = mutableListOf<String>()
            if (title.isNotBlank()) {
                parts.add(title)
            }
            if (!language.isNullOrBlank() && !title.contains(language, ignoreCase = true)) {
                parts.add("(${language.uppercase()})")
            }
            if (codec != null && codec.isNotBlank() && !codec.equals("S_TEXT/UTF8", ignoreCase = true)) {
                parts.add("[$codec]")
            }
            if (isDefault) parts.add("[Default]")
            if (isForced) parts.add("[Forced]")
            return if (parts.isNotEmpty()) parts.joinToString(" ") else "Subtitle"
        }

    companion object {
        val NONE = SubtitleTrack(
            id = "none",
            title = "None (Off)",
            source = SubtitleSource.None
        )
    }
}
