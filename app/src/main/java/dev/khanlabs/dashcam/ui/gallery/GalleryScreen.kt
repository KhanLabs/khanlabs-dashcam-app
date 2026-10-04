package dev.khanlabs.dashcam.ui.gallery

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import dev.khanlabs.dashcam.data.model.VideoClip
import dev.khanlabs.dashcam.ui.theme.ElectricCyan
import dev.khanlabs.dashcam.ui.theme.SurfaceDark
import dev.khanlabs.dashcam.ui.theme.TelemetryFontFamily
import dev.khanlabs.dashcam.ui.theme.TextSecondary
import dev.khanlabs.dashcam.ui.theme.WarningAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun GalleryScreen(
    onPlayClip: (VideoClip) -> Unit,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val filter by viewModel.filter.collectAsState()
    val clips by viewModel.visibleClips.collectAsState()
    val totalClipCount by viewModel.totalClipCount.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedFilenames by viewModel.selectedFilenames.collectAsState()
    val recent = clips.filter { it.clip.isTimestampTrustworthy }
    val legacy = clips.filter { !it.clip.isTimestampTrustworthy }
    var showRemoveSelectedConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        SelectionHeader(
            selectionMode = selectionMode,
            selectedCount = selectedFilenames.size,
            allSelected = clips.isNotEmpty() && selectedFilenames.size == clips.size,
            onEnterSelection = viewModel::enterSelectionMode,
            onExitSelection = viewModel::exitSelectionMode,
            onToggleSelectAll = { checked ->
                if (checked) viewModel.selectAll(clips.map { it.clip.filename }) else viewModel.clearSelection()
            },
            onRemoveSelected = { showRemoveSelectedConfirm = true }
        )

        TabRow(selectedTabIndex = filter.ordinal, containerColor = SurfaceDark) {
            CameraFilter.entries.forEach { option ->
                Tab(
                    selected = filter == option,
                    onClick = { viewModel.setFilter(option) },
                    text = { Text(option.label) },
                    selectedContentColor = ElectricCyan,
                    unselectedContentColor = TextSecondary
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            if (recent.isNotEmpty()) {
                item { SectionHeader("Recent (GPS Synced)") }
                items(recent, key = { it.clip.filename }) { item ->
                    ClipRow(
                        item = item,
                        onPlayClip = onPlayClip,
                        onRemove = viewModel::removeClip,
                        onShare = viewModel::prepareForShare,
                        onThumbnail = viewModel::thumbnailFor,
                        selectionMode = selectionMode,
                        selected = item.clip.filename in selectedFilenames,
                        onToggleSelected = { viewModel.toggleSelected(item.clip.filename) }
                    )
                }
            }
            if (legacy.isNotEmpty()) {
                item { SectionHeader("Legacy / Unsynced") }
                items(legacy, key = { it.clip.filename }) { item ->
                    ClipRow(
                        item = item,
                        onPlayClip = onPlayClip,
                        onRemove = viewModel::removeClip,
                        onShare = viewModel::prepareForShare,
                        onThumbnail = viewModel::thumbnailFor,
                        selectionMode = selectionMode,
                        selected = item.clip.filename in selectedFilenames,
                        onToggleSelected = { viewModel.toggleSelected(item.clip.filename) }
                    )
                }
            }
            if (clips.isEmpty()) {
                item { EmptyState(totalClipCount = totalClipCount) }
            }
        }
    }

    if (showRemoveSelectedConfirm) {
        val count = selectedFilenames.size
        AlertDialog(
            onDismissRequest = { showRemoveSelectedConfirm = false },
            title = { Text("Remove $count ${if (count == 1) "clip" else "clips"} from this phone?") },
            text = {
                Text(
                    "Removes the selected ${if (count == 1) "clip" else "clips"} from this list and deletes the " +
                        "downloaded copy on this phone, if any. The dashcam's own SD card is not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeSelected()
                    showRemoveSelectedConfirm = false
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveSelectedConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SelectionHeader(
    selectionMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onEnterSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onToggleSelectAll: (Boolean) -> Unit,
    onRemoveSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(
                checked = allSelected,
                onCheckedChange = onToggleSelectAll,
                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = ElectricCyan)
            )
            Text(
                text = if (selectedCount == 0) "Select clips" else "$selectedCount selected",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selectedCount == 0) TextSecondary else Color.White,
                modifier = Modifier.weight(1f)
            )
            IconButton(enabled = selectedCount > 0, onClick = onRemoveSelected) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = "Remove selected",
                    tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else TextSecondary
                )
            }
            IconButton(onClick = onExitSelection) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel selection", tint = TextSecondary)
            }
        } else {
            Text(
                text = "Videos",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
            )
            IconButton(onClick = onEnterSelection) {
                Icon(Icons.Filled.Checklist, contentDescription = "Select clips", tint = TextSecondary)
            }
        }
    }
}

/** Distinguishes "nothing synced from the dashcam at all yet" (a real
 *  first-run/never-connected state, needs a call to action) from "this
 *  camera's filter just happens to have nothing" (footage exists, just not
 *  for Road/Cabin specifically) -- see BACKLOG's Gallery-tabs item. */
@Composable
private fun EmptyState(totalClipCount: Int) {
    Column(modifier = Modifier.padding(24.dp)) {
        if (totalClipCount == 0) {
            Text(
                text = "No footage synced yet",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Sync,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Connect to your dashcam's WiFi and tap SYNC on the Dashboard.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Text(
                text = "No clips for this camera.",
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

private enum class ShareState { IDLE, PREPARING }

@Composable
private fun ClipRow(
    item: GalleryClipItem,
    onPlayClip: (VideoClip) -> Unit,
    onRemove: (String) -> Unit,
    onShare: suspend (VideoClip) -> File?,
    onThumbnail: suspend (VideoClip) -> File?,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit
) {
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var shareState by remember { mutableStateOf(ShareState.IDLE) }
    val clip = item.clip
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selectionMode, onClick = onToggleSelected)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelected() },
                    colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = ElectricCyan)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            ClipThumbnail(clip = clip, onThumbnail = onThumbnail)
            Spacer(modifier = Modifier.width(12.dp))
            // Title/file capped to one line each: this column shares the row
            // with a 72dp thumbnail and up to three 48dp icon buttons, so it
            // only has ~100dp to work with. Letting these wrap used to blow
            // the row's height out to match a 4-5 line warning/badge block
            // squeezed into that same sliver -- see the badges block below,
            // which gets the row's full width instead.
            Column(modifier = Modifier.weight(1f)) {
                // Duration deliberately left out here -- it's already shown
                // as a badge on the thumbnail immediately to the left. Even
                // without it, this column (thumbnail + up to three icon
                // buttons all compete for the same row) is too narrow to fit
                // "Cabin Cam • 19:35:23" on one line, so this allows a
                // second line rather than truncating the time itself away --
                // still far more compact than the original unbounded wrap.
                Text(
                    text = "${clip.cameraIndex.label} • ${clip.recordedTime}",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "File: ${clip.filename}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = TelemetryFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // Hidden in selection mode: taps on the row are for checking/
            // unchecking clips there, and Play/Share/individual-Remove would be
            // ambiguous (and Remove specifically is redundant with the bulk
            // action) against that gesture.
            if (!selectionMode) {
                IconButton(onClick = { onPlayClip(clip) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = ElectricCyan)
                }
                IconButton(
                    enabled = shareState == ShareState.IDLE,
                    modifier = Modifier.size(40.dp),
                    onClick = {
                        shareState = ShareState.PREPARING
                        scope.launch {
                            val file = onShare(clip)
                            shareState = ShareState.IDLE
                            if (file != null) {
                                val uri = FileProvider.getUriForFile(context, "dev.khanlabs.dashcam.fileprovider", file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share clip"))
                            } else {
                                Toast.makeText(
                                    context,
                                    "Couldn't prepare this clip to share -- connect to the dashcam and try again",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    if (shareState == ShareState.PREPARING) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ElectricCyan)
                    } else {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = TextSecondary)
                    }
                }
                IconButton(onClick = { showRemoveConfirm = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary)
                }
            }
        }
        if (!clip.isTimestampTrustworthy || item.isDualCamEvent) {
            // Indented to roughly align under the title/file text above (72dp
            // thumbnail + 12dp spacer), spanning the row's full width rather
            // than being trapped in the same narrow column as the title.
            Column(modifier = Modifier.padding(start = 84.dp, top = 2.dp)) {
                if (!clip.isTimestampTrustworthy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Unsynced clock",
                            tint = WarningAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "System clock was unset. Actual time may vary.",
                            style = MaterialTheme.typography.labelSmall,
                            color = WarningAmber
                        )
                    }
                }
                if (item.isDualCamEvent) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(
                            imageVector = Icons.Filled.ViewColumn,
                            contentDescription = "Dual-cam event",
                            tint = ElectricCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Dual-Cam Event", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                    }
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove from this phone?") },
            text = {
                Text(
                    "Removes ${clip.filename} from this list and deletes the downloaded copy on " +
                        "this phone, if any. The dashcam's own SD card is not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(clip.filename)
                    showRemoveConfirm = false
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/** Placeholder icon shows while the frame is loading (or on failure) --
 *  never blocks the row. On-demand only: this composable's LaunchedEffect
 *  only runs once this row is actually in the LazyColumn's composition, i.e.
 *  scrolled into view, not for the whole list up front. */
@Composable
private fun ClipThumbnail(clip: VideoClip, onThumbnail: suspend (VideoClip) -> File?) {
    var bitmap by remember(clip.filename) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(clip.filename) {
        val file = onThumbnail(clip)
        if (file != null) {
            bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) }?.asImageBitmap()
        }
    }

    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceDark),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(imageVector = Icons.Filled.Videocam, contentDescription = null, tint = TextSecondary)
        }
        // durationSeconds <= 0 means the estimator had no neighboring clip to
        // measure a gap from (see formatDuration/withEstimatedDurations) --
        // omit the badge rather than overlay a "--" on the thumbnail itself.
        if (clip.durationSeconds > 0) {
            Text(
                text = formatDuration(clip.durationSeconds),
                color = Color.White,
                fontFamily = TelemetryFontFamily,
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    // 0 is the estimator's "no neighboring clip to measure a gap from"
    // fallback (see RealVideoRepository.withEstimatedDurations), not a real
    // zero-length recording -- showing "0s" would read as broken rather
    // than unknown.
    if (seconds <= 0) return "--"
    val minutes = seconds / 60
    val remSeconds = seconds % 60
    return if (minutes > 0) "$minutes min" else "${remSeconds}s"
}
