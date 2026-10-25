package com.dlnaplayer.android.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dlnaplayer.android.dlna.DlnaController
import com.dlnaplayer.android.dlna.SsdpDiscovery
import com.dlnaplayer.android.model.DlnaDevice
import com.dlnaplayer.android.model.FileItem
import com.dlnaplayer.android.model.MediaType
import com.dlnaplayer.android.model.PlaybackState
import com.dlnaplayer.android.model.TransportState
import com.dlnaplayer.android.repository.DeviceRepository
import com.dlnaplayer.android.repository.PlaybackHistoryRepository
import com.dlnaplayer.android.repository.PlaybackProgress
import com.dlnaplayer.android.service.DlnaCastingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CastUiState(
    val showDevicePicker: Boolean = false,
    val showNowPlayingSheet: Boolean = false,
    val showSubtitlePicker: Boolean = false,
    val showResumeDialog: Boolean = false,
    val pendingResumeItem: FileItem? = null,
    val pendingResumePositionMs: Long = 0L,
    val pendingResumeDurationMs: Long = 0L,
    val pendingResumePlaylist: List<FileItem> = emptyList(),
    val snackbarMessage: String? = null
)

class CastViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val discovery = SsdpDiscovery(application, viewModelScope)
    private val controller = DlnaController(application, viewModelScope)
    val deviceRepo: DeviceRepository = controller.deviceRepo
    val playbackHistoryRepo: PlaybackHistoryRepository = controller.playbackHistoryRepo

    val discoveredDevices: StateFlow<List<DlnaDevice>> = discovery.devices
    val isScanning: StateFlow<Boolean> = discovery.isScanning
    val playbackState: StateFlow<PlaybackState> = controller.playbackState

    private val _uiState = MutableStateFlow(CastUiState())
    val uiState: StateFlow<CastUiState> = _uiState.asStateFlow()

    private var castingService: DlnaCastingService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? DlnaCastingService.LocalBinder
            castingService = binder?.getService()
            isBound = true
            setupPlaybackControlListener()
            castingService?.updateNotification(playbackState.value, hasNext(), hasPrevious())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            castingService = null
            isBound = false
        }
    }

    private val controlListener = object : DlnaCastingService.PlaybackControlListener {
        override fun onPlay() = play()
        override fun onPause() = pause()
        override fun onStop() = stop()
        override fun onNext() = playNext()
        override fun onPrevious() = playPrevious()
        override fun onSeekTo(positionMs: Long) = seekTo(positionMs)
    }

    private fun setupPlaybackControlListener() {
        DlnaCastingService.controlListener = controlListener
        castingService?.setPlaybackControlListener(controlListener)
    }

    init {
        setupPlaybackControlListener()
        bindCastingService()

        // Sync service notification and error messages whenever playback state changes
        viewModelScope.launch {
            var lastErrorMessage: String? = null
            playbackState.collect { state ->
                castingService?.updateNotification(
                    playbackState = state,
                    hasNext = hasNext(),
                    hasPrevious = hasPrevious()
                )
                if (state.errorMessage != null && state.errorMessage != lastErrorMessage) {
                    lastErrorMessage = state.errorMessage
                    showSnackbar(state.errorMessage)
                } else if (state.errorMessage == null) {
                    lastErrorMessage = null
                }
            }
        }

        // Auto-select last connected device if available
        val lastDevice = deviceRepo.getLastConnectedDevice()
        if (lastDevice != null) {
            controller.selectDevice(lastDevice)
        }

        // Start initial SSDP discovery scan
        startScan()
    }

    private fun bindCastingService() {
        val app = getApplication<Application>()
        val intent = Intent(app, DlnaCastingService::class.java)
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun startScan() {
        discovery.startDiscovery()
    }

    fun probeManualIp(ip: String) {
        viewModelScope.launch {
            val success = discovery.probeDeviceAtIp(ip)
            if (success) {
                showSnackbar("Found device at $ip")
            } else {
                showSnackbar("No DLNA device found at $ip")
            }
        }
    }

    fun selectDevice(device: DlnaDevice?) {
        controller.selectDevice(device)
        _uiState.update { it.copy(showDevicePicker = false) }
        if (device != null) {
            showSnackbar("Connected to ${device.displayName}")
        }
    }

    fun disconnect() {
        val devName = playbackState.value.targetDevice?.displayName
        controller.disconnect()
        _uiState.update { it.copy(showNowPlayingSheet = false) }
        showSnackbar("Disconnected from ${devName ?: "renderer"}")
    }

    /**
     * Initiates casting. If the media is a video that has prior playback progress,
     * prompts the user with a Resume/Start Over dialog.
     */
    fun initiateCast(fileItem: FileItem, currentFolderFiles: List<FileItem>) {
        if (!fileItem.isCastable) {
            showSnackbar("${fileItem.name} is not a supported media file for DLNA streaming")
            return
        }

        val currentDevice = playbackState.value.targetDevice
        if (currentDevice == null) {
            // Prompt user to select a device first
            _uiState.update { it.copy(showDevicePicker = true) }
            showSnackbar("Select a DLNA device to cast")
            return
        }

        // Check if eligible for resume
        if (fileItem.mediaType == MediaType.VIDEO) {
            val progress = playbackHistoryRepo.getProgress(fileItem.path)
            if (progress != null && progress.isResumeEligible) {
                _uiState.update {
                    it.copy(
                        showResumeDialog = true,
                        pendingResumeItem = fileItem,
                        pendingResumePositionMs = progress.positionMs,
                        pendingResumeDurationMs = progress.durationMs,
                        pendingResumePlaylist = currentFolderFiles
                    )
                }
                return
            }
        }

        // Otherwise start from beginning
        castFileDirectly(fileItem, currentFolderFiles, 0L)
    }

    /**
     * Confirms or declines resume playback from saved position.
     */
    fun confirmResume(resume: Boolean) {
        val item = _uiState.value.pendingResumeItem ?: return
        val playlist = _uiState.value.pendingResumePlaylist
        val pos = if (resume) _uiState.value.pendingResumePositionMs else 0L

        if (!resume) {
            playbackHistoryRepo.clearProgress(item.path)
        }

        _uiState.update {
            it.copy(
                showResumeDialog = false,
                pendingResumeItem = null,
                pendingResumePositionMs = 0L,
                pendingResumeDurationMs = 0L,
                pendingResumePlaylist = emptyList()
            )
        }

        castFileDirectly(item, playlist, pos)
    }

    fun dismissResumeDialog() {
        _uiState.update {
            it.copy(
                showResumeDialog = false,
                pendingResumeItem = null,
                pendingResumePositionMs = 0L,
                pendingResumeDurationMs = 0L,
                pendingResumePlaylist = emptyList()
            )
        }
    }

    fun castFileDirectly(fileItem: FileItem, currentFolderFiles: List<FileItem>, startPositionMs: Long = 0L) {
        // Start casting service in foreground
        startCastingForegroundService()

        controller.castMedia(fileItem, currentFolderFiles, startPositionMs)
        _uiState.update { it.copy(showNowPlayingSheet = true) }
    }

    fun castFile(fileItem: FileItem, currentFolderFiles: List<FileItem>) {
        initiateCast(fileItem, currentFolderFiles)
    }

    fun renameDevice(ipAddress: String, newName: String) {
        deviceRepo.updateDeviceName(ipAddress, newName)
        discovery.clearDevices()
        val currentTarget = playbackState.value.targetDevice
        if (currentTarget?.ipAddress == ipAddress) {
            controller.selectDevice(currentTarget.copy(customName = newName.trim()))
        }
        showSnackbar("Renamed device to \"$newName\"")
    }

    fun forgetDevice(ipAddress: String) {
        val currentTarget = playbackState.value.targetDevice
        if (currentTarget?.ipAddress == ipAddress) {
            disconnect()
        }
        deviceRepo.removeSavedDevice(ipAddress)
        discovery.clearDevices()
        showSnackbar("Device removed")
    }

    fun getPlaybackProgress(filePath: String): PlaybackProgress? {
        return playbackHistoryRepo.getProgress(filePath)
    }

    private fun startCastingForegroundService() {
        val app = getApplication<Application>()
        val intent = Intent(app, DlnaCastingService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("CastViewModel", "Could not start foreground service", e)
        }
    }

    fun play() = controller.play()
    fun pause() = controller.pause()
    fun stop() {
        controller.stop()
        _uiState.update { it.copy(showNowPlayingSheet = false) }
    }

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)
    fun setVolume(volume: Int) = controller.setVolume(volume)
    fun toggleMute() = controller.toggleMute()
    fun playNext() = controller.playNext()
    fun playPrevious() = controller.playPrevious()
    fun hasNext(): Boolean = controller.hasNext()
    fun hasPrevious(): Boolean = controller.hasPrevious()

    fun setShowDevicePicker(show: Boolean) {
        _uiState.update { it.copy(showDevicePicker = show) }
        if (show) {
            startScan()
        }
    }

    fun setShowNowPlayingSheet(show: Boolean) {
        _uiState.update { it.copy(showNowPlayingSheet = show) }
    }

    fun setShowSubtitlePicker(show: Boolean) {
        _uiState.update { it.copy(showSubtitlePicker = show) }
    }

    fun selectSubtitle(track: com.dlnaplayer.android.model.SubtitleTrack) {
        controller.selectSubtitle(track)
        showSnackbar("Subtitle set: ${track.displayLabel}")
    }

    fun loadExternalSubtitle(file: java.io.File) {
        controller.loadCustomExternalSubtitle(file)
        showSnackbar("Loaded external subtitle: ${file.name}")
    }

    fun showSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        discovery.stopDiscovery()
        controller.release()
        if (DlnaCastingService.controlListener === controlListener) {
            DlnaCastingService.controlListener = null
        }
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
    }
}
