package com.babycam.app.ui.screens

import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.babycam.app.model.StallCause
import com.babycam.app.model.ViewState

/**
 * Video/loading screen. Reconnecting/Connecting fully replace the video area with a loading
 * screen (no last-frame overlay, per camera-viewer context.md). Stalled is the exception: the
 * frozen last frame stays under a [StallBanner] so it's never mistaken for live
 * (stream-resilience RES-05..07, RES-14). See spec.md CAM-06, CAM-08, CAM-11.
 */
@Composable
fun ViewerScreen(
    state: ViewState.Viewer,
    player: ExoPlayer,
    isInPip: Boolean,
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

    Box(modifier = contentModifier.background(Color.Black)) {
        when (state) {
            // Playing and Stalled share one branch so the PlayerView (and its last frame) isn't
            // disposed and recreated when the stream freezes.
            is ViewState.Viewer.Playing, is ViewState.Viewer.Stalled -> {
                val muted = if (state is ViewState.Viewer.Stalled) state.muted else (state as ViewState.Viewer.Playing).muted
                PlayerSurface(player = player)
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ViewerIconButton(onClick = onToggleMute) {
                        Icon(
                            painter = painterResource(
                                id = if (muted) {
                                    android.R.drawable.ic_lock_silent_mode
                                } else {
                                    android.R.drawable.ic_lock_silent_mode_off
                                },
                            ),
                            contentDescription = if (muted) "Ativar áudio" else "Silenciar",
                            tint = Color.White,
                        )
                    }
                    ViewerIconButton(onClick = onEditClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Editar câmera", tint = Color.White)
                    }
                }
                if (state is ViewState.Viewer.Stalled) {
                    StallBanner(
                        state = state,
                        compact = isInPip,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(if (isInPip) 4.dp else 16.dp),
                    )
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
                            "Reconectando... (tentativa ${state.attempt})"
                        } else {
                            "Conectando..."
                        },
                    )
                }
                ViewerIconButton(
                    onClick = onEditClick,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Editar câmera", tint = Color.White)
                }
            }
        }
    }
}

/** Tells the viewer the picture on screen is frozen and a reconnection is under way (RES-05/06/14). */
@Composable
private fun StallBanner(state: ViewState.Viewer.Stalled, compact: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xCCB00020), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (compact) {
            Text("Travado · reconectando", color = Color.White, style = MaterialTheme.typography.labelSmall)
            return@Column
        }
        Text(
            when (state.cause) {
                StallCause.STALL -> "Transmissão travou por 5s — reconectando..."
                StallCause.DRIFT -> "Atraso alto — ressincronizando..."
            },
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Travada há ${state.stalledForSec}s · tentativa ${state.attempt}",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Floating control button with a dark circular backdrop so its icon stays legible over any video frame. */
@Composable
private fun ViewerIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape),
        content = content,
    )
}

/** Real PlayerView bound to [player], except in Compose preview where it's a stand-in box. */
@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(player: ExoPlayer) {
    if (LocalInspectionMode.current) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val context = LocalContext.current
    AndroidView(
        factory = {
            PlayerView(context).apply {
                this.player = player
                setBackgroundColor(android.graphics.Color.BLACK)
                useController = false
                // Keep showing the last frame while a stall reconnection re-prepares the player.
                setKeepContentOnPlayerReset(true)
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        },
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
                isInPip = false,
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
                isInPip = false,
                onToggleMute = {},
                onEditClick = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ViewerScreenStalledPreview() {
    MaterialTheme {
        Surface {
            ViewerScreen(
                state = ViewState.Viewer.Stalled(muted = false, attempt = 2, stalledForSec = 12, cause = StallCause.STALL),
                player = ExoPlayer.Builder(LocalContext.current).build(),
                isInPip = false,
                onToggleMute = {},
                onEditClick = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ViewerScreenDriftPreview() {
    MaterialTheme {
        Surface {
            ViewerScreen(
                state = ViewState.Viewer.Stalled(muted = true, attempt = 1, stalledForSec = 0, cause = StallCause.DRIFT),
                player = ExoPlayer.Builder(LocalContext.current).build(),
                isInPip = false,
                onToggleMute = {},
                onEditClick = {},
            )
        }
    }
}

@Preview(widthDp = 240, heightDp = 135)
@Composable
private fun ViewerScreenStalledPipPreview() {
    MaterialTheme {
        Surface {
            ViewerScreen(
                state = ViewState.Viewer.Stalled(muted = false, attempt = 1, stalledForSec = 6, cause = StallCause.STALL),
                player = ExoPlayer.Builder(LocalContext.current).build(),
                isInPip = true,
                onToggleMute = {},
                onEditClick = {},
            )
        }
    }
}
