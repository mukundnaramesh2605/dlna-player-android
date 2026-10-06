package com.dlnaplayer.android.model

import java.util.Locale

enum class TransportState {
    IDLE,
    CONNECTING,
    PLAYING,
    PAUSED,
    STOPPED,
    TRANSITIONING,
    ERROR
}

/**
 * Encapsulates the real-time casting and playback status on the connected DLNA renderer.
 */
data class PlaybackState(
    val transportState: TransportState = TransportState.IDLE,
    val currentMedia: FileItem? = null,
    val targetDevice: DlnaDevice? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = 50,
    val isMuted: Boolean = false,
    val streamUrl: String? = null,
    val errorMessage: String? = null
) {
    val isPlaying: Boolean
        get() = transportState == TransportState.PLAYING

    val isBufferingOrTransitioning: Boolean
        get() = transportState == TransportState.CONNECTING || transportState == TransportState.TRANSITIONING

    val isConnected: Boolean
        get() = targetDevice != null

    val hasActiveMedia: Boolean
        get() = currentMedia != null && (transportState == TransportState.PLAYING || transportState == TransportState.PAUSED || transportState == TransportState.TRANSITIONING)

    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val positionText: String
        get() = formatTime(positionMs)

    val durationText: String
        get() = formatTime(durationMs)

    companion object {
        fun formatTime(millis: Long): String {
            if (millis <= 0) return "00:00"
            val totalSeconds = millis / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

        fun parseTimeString(timeStr: String?): Long {
            if (timeStr.isNullOrBlank() || timeStr == "NOT_IMPLEMENTED") return 0L
            val parts = timeStr.trim().split(":")
            return try {
                when (parts.size) {
                    3 -> {
                        val hours = parts[0].toLong()
                        val minutes = parts[1].toLong()
                        val secondsPart = parts[2].split(".")[0]
                        val seconds = secondsPart.toLong()
                        ((hours * 3600) + (minutes * 60) + seconds) * 1000
                    }
                    2 -> {
                        val minutes = parts[0].toLong()
                        val seconds = parts[1].split(".")[0].toLong()
                        ((minutes * 60) + seconds) * 1000
                    }
                    else -> 0L
                }
            } catch (e: Exception) {
                0L
            }
        }
    }
}
