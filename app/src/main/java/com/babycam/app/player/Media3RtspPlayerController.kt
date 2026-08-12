package com.babycam.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.babycam.app.model.CameraConfig
import java.net.URLEncoder

/**
 * [RtspPlayerController] backed by Media3 ExoPlayer + the (experimental) RTSP extension.
 * See AD-001 in `.specs/STATE.md` for why this is `@UnstableApi`.
 */
@OptIn(UnstableApi::class)
class Media3RtspPlayerController(context: Context) : RtspPlayerController {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private var listener: PlayerEventListener? = null

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    listener?.onReady()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Logged because reconnection is silent-by-design to the user (CAM-08); this is
                // the only place the real RTSP/network failure reason is visible for debugging.
                Log.w(TAG, "RTSP playback error, will retry via backoff", error)
                listener?.onError()
            }
        })
    }

    override fun play(config: CameraConfig) {
        val mediaSource = RtspMediaSource.Factory()
            .createMediaSource(MediaItem.fromUri(buildUri(config)))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun release() {
        exoPlayer.release()
    }

    override fun setMuted(muted: Boolean) {
        exoPlayer.volume = if (muted) 0f else 1f
    }

    override fun setListener(listener: PlayerEventListener) {
        this.listener = listener
    }

    private fun buildUri(config: CameraConfig): Uri {
        val credentials = buildCredentials(config)
        val authority = if (credentials != null) {
            "$credentials@${config.host}:${config.port}"
        } else {
            "${config.host}:${config.port}"
        }
        val path = when {
            config.path.isEmpty() -> ""
            config.path.startsWith("/") -> config.path
            else -> "/${config.path}"
        }
        return Uri.parse("rtsp://$authority$path")
    }

    /** Omits the `user:pass@` segment entirely when username is null/blank (no bare `:@`). */
    private fun buildCredentials(config: CameraConfig): String? {
        val username = config.username?.takeIf { it.isNotBlank() } ?: return null
        val password = config.password?.takeIf { it.isNotBlank() }
        return if (password != null) {
            "${encode(username)}:${encode(password)}"
        } else {
            encode(username)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val TAG = "Media3RtspPlayerController"
    }
}
