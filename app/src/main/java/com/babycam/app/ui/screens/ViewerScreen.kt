package com.babycam.app.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.babycam.app.model.ViewState

/**
 * Video/loading screen. Reconnecting/Connecting fully replace the video area with a loading
 * screen (no last-frame overlay, per context.md). See design.md Components > ViewerScreen and
 * spec.md CAM-06, CAM-08, CAM-11.
 */
@Composable
fun ViewerScreen(
    state: ViewState.Viewer,
    player: ExoPlayer,
    onToggleMute: () -> Unit,
    onEditClick: () -> Unit,
) {
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val contentModifier = if (isLandscape) {
        Modifier.fillMaxSize()
    } else {
        Modifier.fillMaxSize().padding(16.dp)
    }

    Box(modifier = contentModifier) {
        when (state) {
            is ViewState.Viewer.Playing -> {
                PlayerSurface(player = player)
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onToggleMute) {
                        Text(if (state.muted) "🔇" else "🔊")
                    }
                    IconButton(onClick = onEditClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Editar câmera")
                    }
                }
            }

            is ViewState.Viewer.Connecting, is ViewState.Viewer.Reconnecting -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(
                        if (state is ViewState.Viewer.Reconnecting) {
                            "Reconectando..."
                        } else {
                            "Conectando..."
                        },
                    )
                }
            }
        }
    }
}

/** Real PlayerView bound to [player], except in Compose preview where it's a stand-in box. */
@Composable
private fun PlayerSurface(player: ExoPlayer) {
    if (LocalInspectionMode.current) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val context = LocalContext.current
    AndroidView(
        factory = { PlayerView(context).apply { this.player = player } },
        modifier = Modifier.fillMaxSize(),
    )
}

@Preview(showBackground = true)
@Composable
private fun ViewerScreenPlayingPreview() {
    MaterialTheme {
        Surface {
            ViewerScreen(
                state = ViewState.Viewer.Playing(muted = false),
                player = ExoPlayer.Builder(LocalContext.current).build(),
                onToggleMute = {},
                onEditClick = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ViewerScreenReconnectingPreview() {
    MaterialTheme {
        Surface {
            ViewerScreen(
                state = ViewState.Viewer.Reconnecting(muted = false, attempt = 2),
                player = ExoPlayer.Builder(LocalContext.current).build(),
                onToggleMute = {},
                onEditClick = {},
            )
        }
    }
}
