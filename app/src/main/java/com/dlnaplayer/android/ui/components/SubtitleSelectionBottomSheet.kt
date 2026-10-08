package com.dlnaplayer.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dlnaplayer.android.model.SubtitleSource
import com.dlnaplayer.android.model.SubtitleTrack
import com.dlnaplayer.android.ui.theme.CastAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSelectionBottomSheet(
    availableSubtitles: List<SubtitleTrack>,
    selectedSubtitle: SubtitleTrack,
    isExtracting: Boolean,
    onSelectSubtitle: (SubtitleTrack) -> Unit,
    onPickExternalFile: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val embeddedTracks = availableSubtitles.filter { it.source is SubtitleSource.EmbeddedMkv }
    val externalTracks = availableSubtitles.filter { it.source is SubtitleSource.ExternalFile }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ClosedCaption,
                        contentDescription = null,
                        tint = CastAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Subtitles & Captions",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isExtracting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = CastAccent
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Extracting...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action: Choose external file from storage
            OutlinedButton(
                onClick = onPickExternalFile,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = CastAccent
                )
            ) {
                Icon(
                    imageVector = Icons.Default.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Choose External Subtitle File (.srt, .vtt)",
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. None (Off)
                item {
                    SubtitleItemRow(
                        track = SubtitleTrack.NONE,
                        isSelected = selectedSubtitle.id == SubtitleTrack.NONE.id,
                        isExtracting = false,
                        onClick = { onSelectSubtitle(SubtitleTrack.NONE) }
                    )
                }

                // 2. Embedded MKV Subtitles Section
                if (embeddedTracks.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Embedded in MKV (${embeddedTracks.size})")
                    }
                    items(embeddedTracks, key = { it.id }) { track ->
                        SubtitleItemRow(
                            track = track,
                            isSelected = selectedSubtitle.id == track.id,
                            isExtracting = isExtracting && selectedSubtitle.id == track.id,
                            onClick = { onSelectSubtitle(track) }
                        )
                    }
                }

                // 3. Matched External Subtitles Section
                if (externalTracks.isNotEmpty()) {
                    item {
                        SectionHeader(title = "External Subtitles (${externalTracks.size})")
                    }
                    items(externalTracks, key = { it.id }) { track ->
                        SubtitleItemRow(
                            track = track,
                            isSelected = selectedSubtitle.id == track.id,
                            isExtracting = false,
                            onClick = { onSelectSubtitle(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SubtitleItemRow(
    track: SubtitleTrack,
    isSelected: Boolean,
    isExtracting: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = containerColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = onClick,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = track.displayLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val subSubtitle = when (val src = track.source) {
                        is SubtitleSource.None -> "No subtitles displayed"
                        is SubtitleSource.EmbeddedMkv -> "Track #${src.trackNumber} • ${src.codec}"
                        is SubtitleSource.ExternalFile -> src.file.name
                    }

                    Text(
                        text = subSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isExtracting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = CastAccent
                )
            } else if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = CastAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
