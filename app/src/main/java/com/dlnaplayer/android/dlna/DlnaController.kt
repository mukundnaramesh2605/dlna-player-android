package com.dlnaplayer.android.dlna

import android.content.Context
import android.util.Log
import com.dlnaplayer.android.model.DlnaDevice
import com.dlnaplayer.android.model.FileItem
import com.dlnaplayer.android.model.PlaybackState
import com.dlnaplayer.android.model.TransportState
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
import java.io.IOException

/**
 * High-level DLNA Controller managing device connection, local media server,
 * transport polling, playlist ordering, and volume control.
 */
class DlnaController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val soapClient = UPnPSoapClient()
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
                        errorMessage = null
                    )
                }

                // Ensure local HTTP media server is running on local IP
                val localIp = NetworkUtils.getLocalIpAddress()
                if (localIp == null) {
                    _playbackState.update {
                        it.copy(
                            transportState = TransportState.ERROR,
                            errorMessage = "No Wi-Fi/LAN connection found. Connect to Wi-Fi to cast."
                        )
                    }
                    return@launch
                }

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

                Log.d(TAG, "Streaming URL: $streamUrl -> ${device.friendlyName}")
                _playbackState.update { it.copy(streamUrl = streamUrl) }

                // Stop any previous track on the renderer
                soapClient.stop(device.avTransportControlUrl)

                // Set URI with DIDL metadata
                val setUriResult = soapClient.setAVTransportURI(device.avTransportControlUrl, streamUrl, fileItem)
                if (setUriResult.isFailure) {
                    val err = setUriResult.exceptionOrNull()?.message ?: "Failed to set transport URI"
                    _playbackState.update {
                        it.copy(transportState = TransportState.ERROR, errorMessage = err)
                    }
                    return@launch
                }

                // Send Play
                val playResult = soapClient.play(device.avTransportControlUrl)
                if (playResult.isFailure) {
                    val err = playResult.exceptionOrNull()?.message ?: "Failed to start playback"
                    _playbackState.update {
                        it.copy(transportState = TransportState.ERROR, errorMessage = err)
                    }
                    return@launch
                }

                _playbackState.update { it.copy(transportState = TransportState.PLAYING) }
                startPoller(device.avTransportControlUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Error casting media", e)
                _playbackState.update {
                    it.copy(transportState = TransportState.ERROR, errorMessage = e.message)
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

    private fun ensureMediaServerRunning(ip: String) {
        if (mediaServer == null) {
            try {
                mediaServer = DlnaMediaServer(ip).apply {
                    start()
                }
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
