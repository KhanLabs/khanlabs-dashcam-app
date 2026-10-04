package dev.khanlabs.dashcam.ui.liveview

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import dev.khanlabs.dashcam.data.model.CameraIndex
import dev.khanlabs.dashcam.data.network.LiveStreamState
import dev.khanlabs.dashcam.ui.theme.ElectricCyan
import dev.khanlabs.dashcam.ui.theme.TelemetryFontFamily
import dev.khanlabs.dashcam.ui.theme.TextSecondary
import dev.khanlabs.dashcam.ui.theme.WarningAmber

@Composable
fun LiveViewScreen(
    onBack: () -> Unit,
    viewModel: LiveViewViewModel = hiltViewModel()
) {
    val wifiState by viewModel.wifiState.collectAsState()
    val selectedCamera by viewModel.selectedCamera.collectAsState()
    val streamState by viewModel.streamState.collectAsState()
    var surfaceReady by remember { mutableStateOf<android.view.Surface?>(null) }
    // Starts visible (a user landing on the screen for the first time needs
    // to see the back button and camera picker without already knowing the
    // tap-to-toggle gesture); tapping the video hides it for an
    // unobstructed full-bleed picture, tapping again brings it back.
    var controlsVisible by remember { mutableStateOf(true) }

    // Belt-and-suspenders alongside SurfaceHolder.Callback.surfaceDestroyed
    // below -- both paths call the same idempotent viewModel.stop(), so
    // whichever fires first (or both) leaves the dashcam-side stream
    // disabled and the decode thread joined rather than leaking either.
    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (wifiState.isDashcamNetwork) {
            // Full-bleed: the SurfaceView is sized to the *height* of the
            // screen at the video's real 16:9 ratio, which on a taller
            // phone makes it wider than the screen -- clipToBounds() on the
            // parent crops the overflow left/right instead of letterboxing
            // top/bottom the way a width-first fit did previously. This is
            // the same "cover" behavior a typical camera app uses; the
            // video's real aspect ratio is unchanged, just how much of it
            // fits in a portrait phone screen.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                LiveSurface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true),
                    onSurfaceCreated = { surface ->
                        surfaceReady = surface
                        viewModel.setCamera(selectedCamera, surface)
                    },
                    onSurfaceDestroyed = {
                        surfaceReady = null
                        viewModel.stop()
                    }
                )
            }

            // Tap-to-toggle-chrome catcher -- sits above the video but
            // below the controls bar below, so it never steals taps meant
            // for the back button or camera tabs.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { controlsVisible = !controlsVisible }
            )

            StreamCenterStatus(
                streamState,
                cameraLabel = CameraIndex.entries.getOrNull(selectedCamera)?.label ?: "Camera"
            )
        } else {
            val ssid = wifiState.ssid
            val message = if (ssid.isNullOrBlank()) {
                "Turn on the dashcam's hotspot and join its Dash-* WiFi network to see Live View."
            } else {
                "You're connected to '$ssid'. Turn on the dashcam's hotspot and join its Dash-* network to see Live View."
            }
            EmptyState(message = message)
        }

        AnimatedVisibility(
            visible = !wifiState.isDashcamNetwork || controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopControls(
                onBack = onBack,
                selectedCamera = selectedCamera,
                onSelectCamera = { camera -> surfaceReady?.let { viewModel.setCamera(camera, it) } },
                streamState = if (wifiState.isDashcamNetwork) streamState else null
            )
        }
    }
}

@Composable
private fun TopControls(
    onBack: () -> Unit,
    selectedCamera: Int,
    onSelectCamera: (Int) -> Unit,
    streamState: LiveStreamState?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                )
            )
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "Live View",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            CameraTab(label = "Road", selected = selectedCamera == 0, onClick = { onSelectCamera(0) })
            Spacer(modifier = Modifier.padding(start = 6.dp))
            CameraTab(label = "Cabin", selected = selectedCamera == 1, onClick = { onSelectCamera(1) })
        }
        if (streamState is LiveStreamState.Streaming) {
            Row(
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.padding(start = 6.dp))
                Text(
                    text = "LIVE · ${streamState.framesDecoded} frames",
                    color = ElectricCyan,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = TelemetryFontFamily
                )
            }
        }
    }
}

@Composable
private fun CameraTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) ElectricCyan else Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) ElectricCyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun LiveSurface(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceCreated(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    // Synchronous by design: LiveViewViewModel.stop() ->
                    // LiveStreamPlayer.stop() joins the decode thread before
                    // returning, so this callback doesn't return (and the
                    // surface doesn't get torn down further) until any
                    // in-flight releaseOutputBuffer(..., render = true) call
                    // against it has already finished -- calling this async
                    // would leave a window where the decode thread renders
                    // to a surface Android already considers destroyed.
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceDestroyed()
                    }
                })
            }
        }
    )
}

@Composable
private fun StreamCenterStatus(state: LiveStreamState, cameraLabel: String) {
    when (state) {
        is LiveStreamState.Connecting -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = ElectricCyan)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Connecting to $cameraLabel…",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = TelemetryFontFamily
                )
            }
        }

        is LiveStreamState.Failed -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.WifiTethering, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.message,
                    color = WarningAmber,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }

        // Streaming/Stopped: no center overlay -- the LIVE badge lives in
        // TopControls instead, and an unobstructed picture is the point of
        // this screen once it's actually playing.
        is LiveStreamState.Streaming -> Unit
        LiveStreamState.Stopped -> Unit
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.WifiTethering, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}
