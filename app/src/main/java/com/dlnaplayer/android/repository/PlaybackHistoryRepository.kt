package com.dlnaplayer.android.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Data model for stored video playback position.
 */
data class PlaybackProgress(
    val filePath: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastUpdated: Long
) {
    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    /**
     * Eligible for resume if user has watched more than 5 seconds
     * and is not within the last 15 seconds of the video.
     */
    val isResumeEligible: Boolean
        get() = positionMs > 5000L && (durationMs == 0L || (durationMs - positionMs) > 15000L)
}

/**
 * Manages persisting and retrieving video playback positions so users can continue
 * watching from where they previously left off.
 */
class PlaybackHistoryRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Saves playback position for a video file.
     * Automatically clears progress if near the end of the video.
     */
    fun saveProgress(filePath: String, positionMs: Long, durationMs: Long) {
        if (filePath.isBlank() || positionMs < 3000L) return

        // If watched till the end (within 15 seconds), clear the progress
        if (durationMs > 0 && (durationMs - positionMs) <= 15000L) {
            clearProgress(filePath)
            return
        }

        try {
            val json = JSONObject().apply {
                put("filePath", filePath)
                put("positionMs", positionMs)
                put("durationMs", durationMs)
                put("lastUpdated", System.currentTimeMillis())
            }
            prefs.edit().putString(getStorageKey(filePath), json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Error saving playback progress: ${e.message}")
        }
    }

    /**
     * Retrieves saved playback progress for a video file, or null if none exists.
     */
    fun getProgress(filePath: String): PlaybackProgress? {
        if (filePath.isBlank()) return null
        val str = prefs.getString(getStorageKey(filePath), null) ?: return null
        return try {
            val json = JSONObject(str)
            PlaybackProgress(
                filePath = json.optString("filePath", filePath),
                positionMs = json.optLong("positionMs", 0L),
                durationMs = json.optLong("durationMs", 0L),
                lastUpdated = json.optLong("lastUpdated", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clears saved progress for a file (e.g. when video reaches the end or user starts over).
     */
    fun clearProgress(filePath: String) {
        if (filePath.isBlank()) return
        prefs.edit().remove(getStorageKey(filePath)).apply()
    }

    /**
     * Returns a map of all currently saved progress keyed by file path.
     */
    fun getAllProgress(): Map<String, PlaybackProgress> {
        val result = mutableMapOf<String, PlaybackProgress>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_PREFIX) && value is String) {
                try {
                    val json = JSONObject(value)
                    val path = json.optString("filePath")
                    if (path.isNotBlank()) {
                        result[path] = PlaybackProgress(
                            filePath = path,
                            positionMs = json.optLong("positionMs", 0L),
                            durationMs = json.optLong("durationMs", 0L),
                            lastUpdated = json.optLong("lastUpdated", 0L)
                        )
                    }
                } catch (ignored: Exception) {}
            }
        }
        return result
    }

    private fun getStorageKey(filePath: String): String {
        return "$KEY_PREFIX${filePath.hashCode().toString().replace("-", "x")}"
    }

    companion object {
        private const val TAG = "PlaybackHistoryRepo"
        private const val PREFS_NAME = "dlna_playback_history"
        private const val KEY_PREFIX = "prog_"
    }
}
