package com.dlnaplayer.android.repository

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.dlnaplayer.android.model.AudioTrack
import com.dlnaplayer.android.util.AudioTrackSelector
import com.dlnaplayer.android.util.AudioTrackSelector.AudioTrackScan
import com.dlnaplayer.android.util.BytePatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AudioTrackRepository {

    /**
     * Lists the audio tracks of a media file.
     *  - MKV/WebM and MP4/MOV are parsed directly and support switching.
     *  - Other containers (TS, AVI, ...) are listed via MediaExtractor for information only,
     *    since we can't rewrite their headers to change the renderer's default track.
     */
    suspend fun getAudioTracks(file: File): AudioTrackScan = withContext(Dispatchers.IO) {
        if (AudioTrackSelector.containerOf(file) != AudioTrackSelector.Container.UNSUPPORTED) {
            AudioTrackSelector.scan(file)
        } else {
            AudioTrackScan(listWithMediaExtractor(file), canSwitch = false)
        }
    }

    suspend fun buildPatch(file: File, track: AudioTrack): BytePatch? = withContext(Dispatchers.IO) {
        AudioTrackSelector.buildPatch(file, track)
    }

    private fun listWithMediaExtractor(file: File): List<AudioTrack> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val audio = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) to it }
                .filter { (format, _) -> format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            audio.mapIndexed { i, (format, trackIndex) ->
                AudioTrack(
                    index = i,
                    trackId = trackIndex.toLong() + 1,
                    codec = format.getString(MediaFormat.KEY_MIME),
                    language = format.getStringOrNull(MediaFormat.KEY_LANGUAGE),
                    channels = format.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                    sampleRate = format.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE),
                    isDefault = i == 0
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor could not read ${file.name}: ${e.message}")
            emptyList()
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.getStringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null

    private fun MediaFormat.getIntOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    companion object {
        private const val TAG = "AudioTrackRepository"
    }
}
