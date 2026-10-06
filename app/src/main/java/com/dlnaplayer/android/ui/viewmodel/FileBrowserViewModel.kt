package com.dlnaplayer.android.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dlnaplayer.android.model.FileItem
import com.dlnaplayer.android.repository.FileRepository
import com.dlnaplayer.android.repository.SortBy
import com.dlnaplayer.android.repository.SortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class FileBrowserUiState(
    val currentDirectory: File,
    val breadcrumbs: List<File> = emptyList(),
    val items: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val onlyPlayableMedia: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val isPermissionGranted: Boolean = false,
    val showPermissionRationale: Boolean = false,
    val errorMessage: String? = null
)

class FileBrowserViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = FileRepository()
    private val root = repository.getStorageRoot()

    private val _uiState = MutableStateFlow(
        FileBrowserUiState(
            currentDirectory = root,
            breadcrumbs = listOf(root)
        )
    )
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    init {
        checkPermission()
    }

    fun checkPermission() {
        val granted = hasStoragePermission(getApplication())
        _uiState.update { it.copy(isPermissionGranted = granted) }
        if (granted) {
            loadCurrentDirectory()
        }
    }

    fun loadCurrentDirectory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val state = _uiState.value
            val result = repository.listFolder(
                directory = state.currentDirectory,
                searchQuery = state.searchQuery,
                onlyPlayableMedia = state.onlyPlayableMedia,
                sortBy = state.sortBy,
                sortOrder = state.sortOrder
            )

            result.onSuccess { items ->
                val crumbs = repository.buildBreadcrumbs(state.currentDirectory)
                _uiState.update {
                    it.copy(
                        items = items,
                        breadcrumbs = crumbs,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Failed to read folder contents"
                    )
                }
            }
        }
    }

    fun navigateToDirectory(directory: File) {
        if (!directory.isDirectory) return
        _uiState.update {
            it.copy(
                currentDirectory = directory,
                searchQuery = "",
                isSearchActive = false
            )
        }
        loadCurrentDirectory()
    }

    fun navigateUp(): Boolean {
        val current = _uiState.value.currentDirectory
        val parent = repository.getParentDirectory(current) ?: return false
        navigateToDirectory(parent)
        return true
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        loadCurrentDirectory()
    }

    fun toggleSearch(active: Boolean) {
        _uiState.update {
            it.copy(
                isSearchActive = active,
                searchQuery = if (!active) "" else it.searchQuery
            )
        }
        if (!active) {
            loadCurrentDirectory()
        }
    }

    fun togglePlayableMediaFilter() {
        _uiState.update { it.copy(onlyPlayableMedia = !it.onlyPlayableMedia) }
        loadCurrentDirectory()
    }

    fun setSort(sortBy: SortBy, sortOrder: SortOrder) {
        _uiState.update { it.copy(sortBy = sortBy, sortOrder = sortOrder) }
        loadCurrentDirectory()
    }

    fun showPermissionRationale(show: Boolean) {
        _uiState.update { it.copy(showPermissionRationale = show) }
    }

    fun requestManageStoragePermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }
        }
    }

    companion object {
        fun hasStoragePermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
    }
}
