package dev.khanlabs.dashcam.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.net.Uri
import dev.khanlabs.dashcam.R
import dev.khanlabs.dashcam.data.model.CameraIndex
import dev.khanlabs.dashcam.data.model.VideoClip
import dev.khanlabs.dashcam.ui.theme.ElectricCyan
import dev.khanlabs.dashcam.ui.theme.TelemetryFontFamily

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val eventClips by viewModel.eventClips.collectAsState()
    val isDualCamEvent = eventClips.any { it.cameraIndex == CameraIndex.ROAD } &&
        eventClips.any { it.cameraIndex == CameraIndex.CABIN }
    var dualView by remember { mutableStateOf(false) }
    val selectedClip = eventClips.firstOrNull { it.cameraIndex == viewModel.initialCameraIndex }
        ?: eventClips.firstOrNull()
    val roadClip = eventClips.firstOrNull { it.cameraIndex == CameraIndex.ROAD }
    val cabinClip = eventClips.firstOrNull { it.cameraIndex == CameraIndex.CABIN }

    fun uriFor(clip: VideoClip?, fallbackRawResId: Int): Uri =
        clip?.sourceUrl?.toUri()
            ?: "android.resource://${context.packageName}/$fallbackRawResId".toUri()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = selectedClip?.filename ?: "",
                color = Color.White,
                fontFamily = TelemetryFontFamily,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (dualView && isDualCamEvent) {
                Row(modifier = Modifier.fillMaxSize()) {
                    VideoSurface(
                        uri = uriFor(roadClip, R.raw.sample_road),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    VideoSurface(
                        uri = uriFor(cabinClip, R.raw.sample_cabin),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            } else {
                val resId = if (selectedClip?.cameraIndex == CameraIndex.CABIN) {
                    R.raw.sample_cabin
                } else {
                    R.raw.sample_road
                }
                VideoSurface(uri = uriFor(selectedClip, resId), modifier = Modifier.fillMaxSize())
            }
        }

        if (isDualCamEvent) {
            Button(
                onClick = { dualView = !dualView },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = if (dualView) "Switch to Single View" else "Switch to Dual-View",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun VideoSurface(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
            }
        },
        // `factory` only runs once to build the PlayerView -- without `update`,
        // a later `uri` change (e.g. the initial fallback-resource player being
        // replaced by the real clip's player once eventClips loads) creates a
        // new ExoPlayer that this PlayerView never gets bound to. The visible
        // view keeps pointing at the old player, which DisposableEffect then
        // releases, so every future tap on the (still-visible, now-dead)
        // controller silently no-ops -- reproduced live: real playback state
        // stuck at 00:00 with no error, ExoPlayer's internal thread already
        // torn down.
        update = { view -> view.player = exoPlayer }
    )
}
