package com.dlnaplayer.android.dlna

import android.content.Context
import android.util.Log
import com.dlnaplayer.android.model.DlnaDevice
import com.dlnaplayer.android.model.FileItem
import com.dlnaplayer.android.model.PlaybackState
import com.dlnaplayer.android.model.SubtitleSource
import com.dlnaplayer.android.model.SubtitleTrack
import com.dlnaplayer.android.model.TransportState
import com.dlnaplayer.android.repository.SubtitleRepository
import com.dlnaplayer.android.server.DlnaMediaServer
import com.dlnaplayer.android.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * High-level DLNA Controller managing device connection, local media server,
 * transport polling, playlist ordering, volume control, and subtitle streaming.
 */
class DlnaController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val soapClient = UPnPSoapClient()
    private val subtitleRepo = SubtitleRepository()
    private var mediaServer: DlnaMediaServer? = null
    private var pollerJob: Job? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var currentPlaylist: List<FileItem> = emptyList()
    private var currentPlaylistIndex: Int = -1

    /**
     * Connects to a DLNA MediaRenderer device.
     */
    fun selectDevice(device: DlnaDevice?) {
        if (device == null) {
            disconnect()
            return
        }

        _playbackState.update {
            it.copy(targetDevice = device, errorMessage = null)
        }

        // Fetch initial volume if device supports RenderingControl
        if (device.renderingControlUrl != null) {
            scope.launch(Dispatchers.IO) {
                soapClient.getVolume(device.renderingControlUrl).onSuccess { vol ->
                    _playbackState.update { it.copy(volume = vol) }
                }
                soapClient.getMute(device.renderingControlUrl).onSuccess { muted ->
                    _playbackState.update { it.copy(isMuted = muted) }
                }
            }
        }
    }

    /**
     * Disconnects current device and stops active playback & server.
     */
    fun disconnect() {
        stop()
        _playbackState.update {
            PlaybackState()
        }
    }

    /**
     * Casts a file to the currently selected device, with optional playlist context.
     */
    fun castMedia(
        fileItem: FileItem,
        playlist: List<FileItem> = listOf(fileItem)
    ) {
        val device = _playbackState.value.targetDevice
        if (device == null) {
            _playbackState.update { it.copy(errorMessage = "Please select a DLNA device first") }
            return
        }

        currentPlaylist = playlist.filter { it.isCastable }
        currentPlaylistIndex = currentPlaylist.indexOfFirst { it.path == fileItem.path }

        scope.launch(Dispatchers.IO) {
            try {
                _playbackState.update {
                    it.copy(
                        transportState = TransportState.CONNECTING,
                        currentMedia = fileItem,
                        positionMs = 0L,
                        durationMs = 0L,
                        errorMessage = null,
                        availableSubtitles = emptyList(),
                        selectedSubtitle = SubtitleTrack.NONE,
                        isExtractingSubtitle = false
                    )
                }

                // Ensure local HTTP media server is running on the IP matching the target device's subnet
                val localIp = NetworkUtils.getLocalIpForTarget(device.ipAddress)
                ensureMediaServerRunning(localIp)

                val streamUrl = mediaServer?.registerMedia(fileItem.file)
                if (streamUrl == null) {
                    _playbackState.update {
                        it.copy(
                            transportState = TransportState.ERROR,
                            errorMessage = "Failed to create streaming URL"
                        )
                    }
                    return@launch
                }

                val mediaId = mediaServer?.getMediaId(fileItem.file) ?: ""

                // Discover available subtitles (embedded MKV tracks + external sidecar files)
                val availableSubs = subtitleRepo.getAvailableSubtitles(fileItem.file)
                val defaultSub = availableSubs.firstOrNull { it.isDefault } ?: SubtitleTrack.NONE
                var initialSubUrl: String? = null

                if (defaultSub.source !is SubtitleSource.None) {
                    val subFile = subtitleRepo.prepareSubtitleFile(context, defaultSub)
                    if (subFile != null && mediaId.isNotBlank()) {
                        initialSubUrl = mediaServer?.registerSubtitle(mediaId, subFile)
                    }
                }

                _playbackState.update {
                    it.copy(
                        availableSubtitles = availableSubs,
                        selectedSubtitle = defaultSub
                    )
                }

                Log.d(TAG, "Streaming URL: $streamUrl (Subtitle: $initialSubUrl) -> ${device.friendlyName} (${device.ipAddress})")
                _playbackState.update { it.copy(streamUrl = streamUrl) }

                var activeControlUrl = device.avTransportControlUrl

                // Best-effort stop any previous track on the renderer
                soapClient.stop(activeControlUrl)

                // Set URI with DIDL metadata and optional subtitle URL
                var setUriResult = soapClient.setAVTransportURI(activeControlUrl, streamUrl, fileItem, initialSubUrl)
                if (setUriResult.isFailure) {
                    // Try alternative Samsung/UPnP port if primary URL failed
                    val fallbackUrl = when {
                        activeControlUrl.contains(":7676/") -> activeControlUrl.replace(Regex(":[0-9]+/.*"), ":9197/upnp/control/AVTransport1")
                        activeControlUrl.contains(":9197/") -> activeControlUrl.replace(Regex(":[0-9]+/.*"), ":7676/smp_4_")
                        else -> null
                    }
                    if (fallbackUrl != null) {
                        Log.w(TAG, "Primary control URL failed. Retrying with fallback URL: $fallbackUrl")
                        val fallbackResult = soapClient.setAVTransportURI(fallbackUrl, streamUrl, fileItem, initialSubUrl)
                        if (fallbackResult.isSuccess) {
                            activeControlUrl = fallbackUrl
                            setUriResult = fallbackResult
                            // Update device with the working control URL
                            _playbackState.update { it.copy(targetDevice = device.copy(avTransportControlUrl = fallbackUrl)) }
                        }
                    }
                }

                if (setUriResult.isFailure) {
                    val err = setUriResult.exceptionOrNull()?.message ?: "Failed to set transport URI"
                    Log.e(TAG, "SetAVTransportURI failed: $err")
                    _playbackState.update {
                        it.copy(transportState = TransportState.ERROR, errorMessage = "Renderer rejected media: $err")
                    }
                    return@launch
                }

                // Send Play
                val playResult = soapClient.play(activeControlUrl)
                if (playResult.isFailure) {
                    val err = playResult.exceptionOrNull()?.message ?: "Failed to start playback"
                    Log.e(TAG, "Play command failed: $err")
                    _playbackState.update {
                        it.copy(transportState = TransportState.ERROR, errorMessage = "Playback start failed: $err")
                    }
                    return@launch
                }

                _playbackState.update { it.copy(transportState = TransportState.PLAYING, errorMessage = null) }
                startPoller(activeControlUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Error casting media", e)
                _playbackState.update {
                    it.copy(transportState = TransportState.ERROR, errorMessage = e.message ?: "Playback error")
                }
            }
        }
    }

    fun play() {
        val device = _playbackState.value.targetDevice ?: return
        scope.launch(Dispatchers.IO) {
            soapClient.play(device.avTransportControlUrl).onSuccess {
                _playbackState.update { it.copy(transportState = TransportState.PLAYING) }
            }.onFailure { err ->
                _playbackState.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    fun pause() {
        val device = _playbackState.value.targetDevice ?: return
        scope.launch(Dispatchers.IO) {
            soapClient.pause(device.avTransportControlUrl).onSuccess {
                _playbackState.update { it.copy(transportState = TransportState.PAUSED) }
            }.onFailure { err ->
                _playbackState.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    fun stop() {
        val device = _playbackState.value.targetDevice
        stopPoller()
        if (device != null) {
            scope.launch(Dispatchers.IO) {
                soapClient.stop(device.avTransportControlUrl)
            }
        }
        _playbackState.update {
            it.copy(
                transportState = TransportState.STOPPED,
                positionMs = 0L
            )
        }
    }

    fun seekTo(positionMs: Long) {
        val device = _playbackState.value.targetDevice ?: return
        val duration = _playbackState.value.durationMs
        val target = if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        val timeStr = PlaybackState.formatTime(target)

        // Optimistically update position
        _playbackState.update { it.copy(positionMs = target) }

        scope.launch(Dispatchers.IO) {
            soapClient.seek(device.avTransportControlUrl, timeStr).onFailure { err ->
                Log.w(TAG, "Seek error: ${err.message}")
            }
        }
    }

    fun seekBy(deltaMs: Long) {
        val current = _playbackState.value.positionMs
        seekTo(current + deltaMs)
    }

    fun setVolume(volume: Int) {
        val device = _playbackState.value.targetDevice ?: return
        val controlUrl = device.renderingControlUrl ?: return
        val clamped = volume.coerceIn(0, 100)
        _playbackState.update { it.copy(volume = clamped) }

        scope.launch(Dispatchers.IO) {
            soapClient.setVolume(controlUrl, clamped)
        }
    }

    fun toggleMute() {
        val device = _playbackState.value.targetDevice ?: return
        val controlUrl = device.renderingControlUrl ?: return
        val newMute = !_playbackState.value.isMuted
        _playbackState.update { it.copy(isMuted = newMute) }

        scope.launch(Dispatchers.IO) {
            soapClient.setMute(controlUrl, newMute)
        }
    }

    /**
     * Selects and activates a subtitle track (embedded MKV or external file),
     * updating the local streaming server and re-sending metadata to the renderer.
     */
    fun selectSubtitle(track: SubtitleTrack) {
        val currentMedia = _playbackState.value.currentMedia ?: return
        val server = mediaServer ?: return
        val mediaId = server.getMediaId(currentMedia.file)

        scope.launch(Dispatchers.IO) {
            if (track.source is SubtitleSource.EmbeddedMkv) {
                _playbackState.update { it.copy(isExtractingSubtitle = true) }
            }

            val subFile = subtitleRepo.prepareSubtitleFile(context, track)
            val subUrl = server.registerSubtitle(mediaId, subFile)

            _playbackState.update {
                it.copy(
                    selectedSubtitle = track,
                    isExtractingSubtitle = false
                )
            }

            // If a device is actively connected and playing, re-send transport URI with new subtitle
            val device = _playbackState.value.targetDevice ?: return@launch
            val streamUrl = _playbackState.value.streamUrl ?: return@launch
            val activeControlUrl = device.avTransportControlUrl
            val currentPos = _playbackState.value.positionMs
            val isPlaying = _playbackState.value.isPlaying

            Log.d(TAG, "Switched subtitle to: ${track.displayLabel} (URL: $subUrl)")
            val setRes = soapClient.setAVTransportURI(activeControlUrl, streamUrl, currentMedia, subUrl)
            if (setRes.isSuccess) {
                if (currentPos > 0) {
                    delay(350)
                    seekTo(currentPos)
                }
                if (isPlaying) {
                    soapClient.play(activeControlUrl)
                }
            }
        }
    }

    /**
     * Loads a user-selected external .srt or .vtt file from storage and activates it.
     */
    fun loadCustomExternalSubtitle(file: File) {
        if (!file.exists() || !file.canRead()) return
        val customTrack = SubtitleTrack(
            id = "custom_${file.absolutePath.hashCode()}",
            title = "External: ${file.name}",
            source = SubtitleSource.ExternalFile(file)
        )
        _playbackState.update { current ->
            val list = current.availableSubtitles.toMutableList()
            if (list.none { it.id == customTrack.id }) {
                list.add(customTrack)
            }
            current.copy(availableSubtitles = list)
        }
        selectSubtitle(customTrack)
    }

    fun playNext() {
        if (hasNext()) {
            val nextIndex = currentPlaylistIndex + 1
            val nextItem = currentPlaylist[nextIndex]
            castMedia(nextItem, currentPlaylist)
        }
    }

    fun playPrevious() {
        if (hasPrevious()) {
            val prevIndex = currentPlaylistIndex - 1
            val prevItem = currentPlaylist[prevIndex]
            castMedia(prevItem, currentPlaylist)
        }
    }

    fun hasNext(): Boolean = currentPlaylistIndex in 0 until (currentPlaylist.size - 1)
    fun hasPrevious(): Boolean = currentPlaylistIndex > 0

    private fun startPoller(avTransportUrl: String) {
        pollerJob?.cancel()
        pollerJob = scope.launch(Dispatchers.IO) {
            var consecutiveErrors = 0
            while (isActive) {
                delay(1000)

                // Poll position info
                soapClient.getPositionInfo(avTransportUrl).onSuccess { posInfo ->
                    consecutiveErrors = 0
                    val relMs = PlaybackState.parseTimeString(posInfo.relTime)
                    val durMs = PlaybackState.parseTimeString(posInfo.trackDuration)

                    _playbackState.update { current ->
                        current.copy(
                            positionMs = if (relMs > 0) relMs else current.positionMs,
                            durationMs = if (durMs > 0) durMs else current.durationMs
                        )
                    }
                }.onFailure {
                    consecutiveErrors++
                }

                // Poll transport state
                soapClient.getTransportInfo(avTransportUrl).onSuccess { transInfo ->
                    val newState = when (transInfo.currentTransportState.uppercase()) {
                        "PLAYING" -> TransportState.PLAYING
                        "PAUSED_PLAYBACK", "PAUSED" -> TransportState.PAUSED
                        "STOPPED" -> TransportState.STOPPED
                        "TRANSITIONING" -> TransportState.TRANSITIONING
                        else -> _playbackState.value.transportState
                    }
                    _playbackState.update { it.copy(transportState = newState) }

                    // Auto-advance playlist when stopped after reaching end of media
                    if (newState == TransportState.STOPPED && _playbackState.value.positionMs > 0 &&
                        _playbackState.value.durationMs > 0 &&
                        (_playbackState.value.durationMs - _playbackState.value.positionMs) <= 3000
                    ) {
                        if (hasNext()) {
                            launch(Dispatchers.Main) { playNext() }
                        }
                    }
                }

                if (consecutiveErrors >= 5) {
                    Log.w(TAG, "Device unreachable for 5 consecutive poll cycles")
                    _playbackState.update {
                        it.copy(
                            transportState = TransportState.ERROR,
                            errorMessage = "Connection to renderer lost"
                        )
                    }
                    break
                }
            }
        }
    }

    private fun stopPoller() {
        pollerJob?.cancel()
        pollerJob = null
    }

    private var currentServerIp: String? = null

    private fun ensureMediaServerRunning(ip: String) {
        if (mediaServer == null || currentServerIp != ip) {
            try {
                mediaServer?.stop()
                mediaServer = DlnaMediaServer(ip).apply {
                    start()
                }
                currentServerIp = ip
                Log.i(TAG, "Started DlnaMediaServer at $ip:${mediaServer?.serverPort}")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start DlnaMediaServer", e)
            }
        }
    }

    fun release() {
        stop()
        try {
            mediaServer?.stop()
            mediaServer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media server", e)
        }
    }

    companion object {
        private const val TAG = "DlnaController"
    }
}
