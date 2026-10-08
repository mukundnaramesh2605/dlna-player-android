package com.dlnaplayer.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import com.dlnaplayer.android.service.DlnaCastingService
import com.dlnaplayer.android.ui.components.DevicePickerBottomSheet
import com.dlnaplayer.android.ui.components.FolderBrowserScreen
import com.dlnaplayer.android.ui.components.MiniPlayerBar
import com.dlnaplayer.android.ui.components.NowPlayingSheet
import com.dlnaplayer.android.ui.components.PermissionScreen
import com.dlnaplayer.android.ui.components.SubtitleSelectionBottomSheet
import com.dlnaplayer.android.ui.theme.CastAccent
import com.dlnaplayer.android.ui.theme.CastConnected
import com.dlnaplayer.android.ui.theme.DLNAPlayerTheme
import com.dlnaplayer.android.ui.viewmodel.CastViewModel
import com.dlnaplayer.android.ui.viewmodel.FileBrowserViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val fileBrowserViewModel: FileBrowserViewModel by viewModels()
    private val castViewModel: CastViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            DLNAPlayerTheme {
                MainContent(
                    fileBrowserViewModel = fileBrowserViewModel,
                    castViewModel = castViewModel,
                    onRequestManageStorage = {
                        fileBrowserViewModel.requestManageStoragePermission(this)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            DlnaCastingService.ACTION_OPEN_PLAYER -> castViewModel.setShowNowPlayingSheet(true)
            DlnaCastingService.ACTION_PLAY -> castViewModel.play()
            DlnaCastingService.ACTION_PAUSE -> castViewModel.pause()
            DlnaCastingService.ACTION_STOP -> castViewModel.stop()
            DlnaCastingService.ACTION_PREVIOUS -> castViewModel.playPrevious()
            DlnaCastingService.ACTION_NEXT -> castViewModel.playNext()
        }
    }

    override fun onResume() {
        super.onResume()
        fileBrowserViewModel.checkPermission()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(
    fileBrowserViewModel: FileBrowserViewModel,
    castViewModel: CastViewModel,
    onRequestManageStorage: () -> Unit
) {
    val fileState by fileBrowserViewModel.uiState.collectAsState()
    val castUiState by castViewModel.uiState.collectAsState()
    val playbackState by castViewModel.playbackState.collectAsState()
    val devices by castViewModel.discoveredDevices.collectAsState()
    val isScanning by castViewModel.isScanning.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Runtime permission launcher for storage/media fallback
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        fileBrowserViewModel.checkPermission()
    }

    // External subtitle (.srt, .vtt) document picker launcher
    val subtitleFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val contentResolver = context.contentResolver
                val displayName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else "external.srt"
                } ?: "external.srt"

                val cacheDir = File(context.cacheDir, "subtitles").apply { mkdirs() }
                val cacheFile = File(cacheDir, "custom_${System.currentTimeMillis()}_$displayName")
                contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    castViewModel.loadExternalSubtitle(cacheFile)
                    castViewModel.setShowSubtitlePicker(false)
                } else {
                    castViewModel.showSnackbar("Could not read selected subtitle file")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error reading subtitle file", e)
                castViewModel.showSnackbar("Failed to open subtitle: ${e.message}")
            }
        }
    }

    // Intercept back button to navigate up folder hierarchy
    BackHandler(enabled = fileState.breadcrumbs.size > 1) {
        fileBrowserViewModel.navigateUp()
    }

    // Show Snackbars when emitted
    LaunchedEffect(castUiState.snackbarMessage) {
        castUiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            castViewModel.clearSnackbar()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "DLNA Player",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // Search toggle button
                    if (fileState.isPermissionGranted && !fileState.isSearchActive) {
                        IconButton(onClick = { fileBrowserViewModel.toggleSearch(true) }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search Files"
                            )
                        }
                    }

                    // Persistent Cast Button
                    IconButton(onClick = { castViewModel.setShowDevicePicker(true) }) {
                        if (playbackState.isConnected) {
                            BadgedBox(
                                badge = {
                                    Badge(containerColor = CastConnected)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CastConnected,
                                    contentDescription = "Cast connected",
                                    tint = CastConnected
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.Cast,
                                contentDescription = "Cast to DLNA device",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!fileState.isPermissionGranted) {
                PermissionScreen(
                    onRequestAllFilesAccess = onRequestManageStorage,
                    onRequestStandardMediaAccess = {
                        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            arrayOf(
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.READ_MEDIA_AUDIO,
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        } else {
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                        permissionsLauncher.launch(perms)
                    },
                    onCheckPermissionAgain = {
                        fileBrowserViewModel.checkPermission()
                    }
                )
            } else {
                FolderBrowserScreen(
                    uiState = fileState,
                    onNavigateDirectory = { fileBrowserViewModel.navigateToDirectory(it) },
                    onNavigateUp = { fileBrowserViewModel.navigateUp() },
                    onCastMedia = { fileItem ->
                        castViewModel.castFile(fileItem, fileState.items)
                    },
                    onNonCastableClicked = { fileItem ->
                        if (fileItem.extension in setOf("srt", "vtt", "sub")) {
                            if (playbackState.hasActiveMedia) {
                                castViewModel.loadExternalSubtitle(fileItem.file)
                            } else {
                                castViewModel.showSnackbar("${fileItem.name} is a subtitle file. Start casting a video first to attach subtitles.")
                            }
                        } else {
                            castViewModel.showSnackbar("${fileItem.name} cannot be played on TV: DLNA only streams audio, video, and photos.")
                        }
                    },
                    onSearchChanged = { fileBrowserViewModel.onSearchQueryChanged(it) },
                    onToggleSearch = { fileBrowserViewModel.toggleSearch(it) },
                    onToggleMediaFilter = { fileBrowserViewModel.togglePlayableMediaFilter() },
                    onSortChanged = { sortBy, sortOrder ->
                        fileBrowserViewModel.setSort(sortBy, sortOrder)
                    }
                )
            }

            // Bottom Mini Player Bar
            if (playbackState.hasActiveMedia || (playbackState.currentMedia != null && playbackState.isConnected)) {
                MiniPlayerBar(
                    playbackState = playbackState,
                    onExpand = { castViewModel.setShowNowPlayingSheet(true) },
                    onPlayPause = {
                        if (playbackState.isPlaying) castViewModel.pause() else castViewModel.play()
                    },
                    onStop = { castViewModel.stop() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    // Device Picker Modal Bottom Sheet
    if (castUiState.showDevicePicker) {
        DevicePickerBottomSheet(
            devices = devices,
            currentDevice = playbackState.targetDevice,
            isScanning = isScanning,
            onSelectDevice = { castViewModel.selectDevice(it) },
            onDisconnect = { castViewModel.disconnect() },
            onRefreshScan = { castViewModel.startScan() },
            onProbeManualIp = { castViewModel.probeManualIp(it) },
            onDismiss = { castViewModel.setShowDevicePicker(false) }
        )
    }

    // Now Playing Modal Bottom Sheet
    if (castUiState.showNowPlayingSheet && playbackState.currentMedia != null) {
        NowPlayingSheet(
            playbackState = playbackState,
            onPlayPause = {
                if (playbackState.isPlaying) castViewModel.pause() else castViewModel.play()
            },
            onStop = { castViewModel.stop() },
            onSeekTo = { castViewModel.seekTo(it) },
            onSeekBy = { castViewModel.seekBy(it) },
            onNext = { castViewModel.playNext() },
            onPrevious = { castViewModel.playPrevious() },
            onSetVolume = { castViewModel.setVolume(it) },
            onToggleMute = { castViewModel.toggleMute() },
            onOpenSubtitles = { castViewModel.setShowSubtitlePicker(true) },
            onDismiss = { castViewModel.setShowNowPlayingSheet(false) }
        )
    }

    // Subtitle Selection Modal Bottom Sheet
    if (castUiState.showSubtitlePicker) {
        SubtitleSelectionBottomSheet(
            availableSubtitles = playbackState.availableSubtitles,
            selectedSubtitle = playbackState.selectedSubtitle,
            isExtracting = playbackState.isExtractingSubtitle,
            onSelectSubtitle = { track ->
                castViewModel.selectSubtitle(track)
                castViewModel.setShowSubtitlePicker(false)
            },
            onPickExternalFile = {
                subtitleFilePicker.launch(arrayOf("*/*", "text/*", "application/x-subrip"))
            },
            onDismiss = { castViewModel.setShowSubtitlePicker(false) }
        )
    }
}
