package com.dlnaplayer.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dlnaplayer.android.model.FileItem
import com.dlnaplayer.android.model.MediaType
import com.dlnaplayer.android.repository.SortBy
import com.dlnaplayer.android.repository.SortOrder
import com.dlnaplayer.android.ui.theme.CastAccent
import com.dlnaplayer.android.ui.theme.FileOtherColor
import com.dlnaplayer.android.ui.theme.MediaAudioColor
import com.dlnaplayer.android.ui.theme.MediaImageColor
import com.dlnaplayer.android.ui.theme.MediaVideoColor
import com.dlnaplayer.android.ui.viewmodel.FileBrowserUiState
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderBrowserScreen(
    uiState: FileBrowserUiState,
    onNavigateDirectory: (File) -> Unit,
    onNavigateUp: () -> Unit,
    onCastMedia: (FileItem) -> Unit,
    onNonCastableClicked: (FileItem) -> Unit,
    onSearchChanged: (String) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onToggleMediaFilter: () -> Unit,
    onSortChanged: (SortBy, SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // Search & Filter header bar
        if (uiState.isSearchActive) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchChanged,
                    placeholder = { Text("Search files in current folder...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { onToggleSearch(false) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Breadcrumb Navigation Bar
        BreadcrumbsBar(
            breadcrumbs = uiState.breadcrumbs,
            onBreadcrumbClick = onNavigateDirectory,
            onNavigateUp = onNavigateUp,
            canNavigateUp = uiState.breadcrumbs.size > 1
        )

        // Filter and Sort Chips Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FilterChip(
                selected = uiState.onlyPlayableMedia,
                onClick = onToggleMediaFilter,
                label = { Text("Playable media only") }
            )

            Box {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort files")
                }

                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Name (A to Z)") },
                        onClick = {
                            onSortChanged(SortBy.NAME, SortOrder.ASCENDING)
                            showSortMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Name (Z to A)") },
                        onClick = {
                            onSortChanged(SortBy.NAME, SortOrder.DESCENDING)
                            showSortMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Date (Newest first)") },
                        onClick = {
                            onSortChanged(SortBy.DATE, SortOrder.DESCENDING)
                            showSortMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Date (Oldest first)") },
                        onClick = {
                            onSortChanged(SortBy.DATE, SortOrder.ASCENDING)
                            showSortMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Size (Largest first)") },
                        onClick = {
                            onSortChanged(SortBy.SIZE, SortOrder.DESCENDING)
                            showSortMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Size (Smallest first)") },
                        onClick = {
                            onSortChanged(SortBy.SIZE, SortOrder.ASCENDING)
                            showSortMenu = false
                        }
                    )
                }
            }
        }

        HorizontalDivider()

        // Content Area
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.items.isEmpty() -> {
                    EmptyFolderView(
                        isFiltered = uiState.onlyPlayableMedia || uiState.searchQuery.isNotEmpty(),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(
                            items = uiState.items,
                            key = { it.path }
                        ) { item ->
                            if (item.isDirectory) {
                                FolderListItem(
                                    item = item,
                                    onClick = { onNavigateDirectory(item.file) }
                                )
                            } else {
                                FileListItem(
                                    item = item,
                                    onCastClick = { onCastMedia(item) },
                                    onNonCastableClick = { onNonCastableClicked(item) }
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BreadcrumbsBar(
    breadcrumbs: List<File>,
    onBreadcrumbClick: (File) -> Unit,
    onNavigateUp: () -> Unit,
    canNavigateUp: Boolean
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (canNavigateUp) {
            IconButton(
                onClick = onNavigateUp,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Up one directory",
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp, end = 4.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically
        ) {
            breadcrumbs.forEachIndexed { index, file ->
                val isLast = index == breadcrumbs.size - 1
                val displayName = if (index == 0) "Storage" else file.name

                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                    color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = !isLast) { onBreadcrumbClick(file) }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )

                if (!isLast) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun FolderListItem(
    item: FileItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${item.formattedSize} • ${item.formattedDate}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun FileListItem(
    item: FileItem,
    onCastClick: () -> Unit,
    onNonCastableClick: () -> Unit
) {
    val isCastable = item.isCastable

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isCastable) onCastClick() else onNonCastableClick()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail or Type Icon
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isCastable) {
                        when (item.mediaType) {
                            MediaType.VIDEO -> MediaVideoColor.copy(alpha = 0.15f)
                            MediaType.AUDIO -> MediaAudioColor.copy(alpha = 0.15f)
                            MediaType.IMAGE -> MediaImageColor.copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (item.mediaType == MediaType.IMAGE || item.mediaType == MediaType.VIDEO) {
                AsyncImage(
                    model = item.file,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                val icon = when (item.mediaType) {
                    MediaType.AUDIO -> Icons.Default.Audiotrack
                    MediaType.VIDEO -> Icons.Default.Movie
                    MediaType.IMAGE -> Icons.Default.Image
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                }
                val tint = when (item.mediaType) {
                    MediaType.AUDIO -> MediaAudioColor
                    MediaType.VIDEO -> MediaVideoColor
                    MediaType.IMAGE -> MediaImageColor
                    else -> FileOtherColor
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isCastable) tint else FileOtherColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCastable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontWeight = if (isCastable) FontWeight.Normal else FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${item.extension.uppercase()} • ${item.formattedSize} • ${item.formattedDate}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isCastable) 1f else 0.5f)
                )

                if (!isCastable) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Non-media",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }

        if (isCastable) {
            IconButton(onClick = onCastClick) {
                Icon(
                    imageVector = Icons.Default.PlayCircleOutline,
                    contentDescription = "Cast media",
                    tint = CastAccent
                )
            }
        }
    }
}

@Composable
fun EmptyFolderView(
    isFiltered: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (isFiltered) "No matching media files found" else "This folder is empty",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
