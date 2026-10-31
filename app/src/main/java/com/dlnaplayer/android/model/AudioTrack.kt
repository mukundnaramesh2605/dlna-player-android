package com.dlnaplayer.android.model

import java.util.Locale

/**
 * An audio stream inside a media container (MKV/WebM, MP4/MOV, ...).
 *
 * @param index position among the file's audio tracks (0 = first audio track in the header)
 * @param trackId container-level id (Matroska TrackNumber or MP4 track_ID)
 */
data class AudioTrack(
    val index: Int,
    val trackId: Long,
    val codec: String? = null,
    val language: String? = null,
    val title: String? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val isDefault: Boolean = false
) {
    val languageName: String?
        get() {
            val code = language?.takeIf { it.isNotBlank() && it != "und" } ?: return null
            val name = Locale.forLanguageTag(toTwoLetterCode(code)).getDisplayLanguage(Locale.getDefault())
            return name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code.uppercase(Locale.ROOT)
        }

    val displayLabel: String
        get() {
            val name = title?.takeIf { it.isNotBlank() }
            val lang = languageName
            return when {
                name != null && lang != null && !name.contains(lang, ignoreCase = true) -> "$name ($lang)"
                name != null -> name
                lang != null -> lang
                else -> "Audio track ${index + 1}"
            }
        }

    val detailLabel: String
        get() {
            val parts = mutableListOf<String>()
            codecName?.let { parts.add(it) }
            channelLabel?.let { parts.add(it) }
            sampleRate?.takeIf { it > 0 }?.let { parts.add("${it / 1000.0} kHz".replace(".0 ", " ")) }
            parts.add("Track #$trackId")
            return parts.joinToString(" • ")
        }

    /** Short tag for compact UI chips, e.g. "ENG" */
    val shortTag: String
        get() = language?.takeIf { it.isNotBlank() && it != "und" }?.take(3)?.uppercase(Locale.ROOT)
            ?: "A${index + 1}"

    companion object {
        /** ISO 639-2/B codes that differ from the /T codes used by Locale.getISO3Language(). */
        private val BIBLIOGRAPHIC_CODES = mapOf(
            "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs",
            "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is",
            "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk",
            "tib" to "bo", "wel" to "cy"
        )

        private val ISO3_TO_ISO2: Map<String, String> by lazy {
            Locale.getISOLanguages().mapNotNull { two ->
                runCatching { Locale(two).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it to two }
            }.toMap()
        }

        /** Maps "fra"/"fre"/"jpn" style codes to "fr"/"ja" so every JVM/Android can name them. */
        fun toTwoLetterCode(code: String): String {
            val c = code.lowercase(Locale.ROOT)
            if (c.length != 3) return code
            return BIBLIOGRAPHIC_CODES[c] ?: ISO3_TO_ISO2[c] ?: code
        }
    }

    private val channelLabel: String?
        get() = when (channels) {
            null, 0 -> null
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channels ch"
        }

    private val codecName: String?
        get() {
            val c = codec?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                c.startsWith("A_AAC", true) || c.equals("mp4a", true) || c.equals("audio/mp4a-latm", true) -> "AAC"
                c.equals("A_AC3", true) || c.equals("ac-3", true) || c.equals("audio/ac3", true) -> "Dolby Digital"
                c.equals("A_EAC3", true) || c.equals("ec-3", true) || c.equals("audio/eac3", true) -> "Dolby Digital+"
                c.startsWith("A_TRUEHD", true) -> "TrueHD"
                c.startsWith("A_DTS", true) || c.startsWith("dts", true) || c.contains("audio/vnd.dts", true) -> "DTS"
                c.equals("A_OPUS", true) || c.equals("Opus", true) || c.equals("audio/opus", true) -> "Opus"
                c.equals("A_VORBIS", true) || c.equals("audio/vorbis", true) -> "Vorbis"
                c.equals("A_FLAC", true) || c.equals("fLaC", true) || c.equals("audio/flac", true) -> "FLAC"
                c.startsWith("A_MPEG/L3", true) || c.equals(".mp3", true) || c.equals("audio/mpeg", true) -> "MP3"
                c.startsWith("A_MPEG/L2", true) -> "MP2"
                c.startsWith("A_PCM", true) || c.equals("lpcm", true) || c.equals("sowt", true) || c.equals("twos", true) -> "PCM"
                else -> c.removePrefix("A_").removePrefix("audio/")
            }
        }
}
