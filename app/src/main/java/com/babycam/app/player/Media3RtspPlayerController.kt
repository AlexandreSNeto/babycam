package com.babycam.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.babycam.app.model.CameraConfig
import java.net.URLEncoder

/**
 * [RtspPlayerController] backed by Media3 ExoPlayer + the (experimental) RTSP extension.
 * See AD-001 in `.specs/STATE.md` for why this is `@UnstableApi`, and AD-005 for the
 * low-latency profile (tiny buffers + RTP over TCP).
 */
@OptIn(UnstableApi::class)
class Media3RtspPlayerController(context: Context) : RtspPlayerController {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build(),
        )
        .build()

    private var listener: PlayerEventListener? = null

    init {
        exoPlayer.addListener(object : Player.Listener {
            // Not STATE_READY: that fires again after every rebuffer and before any picture is
            // shown. "Ready" here means real video is on screen (RES-04/RES-07).
            override fun onRenderedFirstFrame() {
                listener?.onReady()
            }

            override fun onPlayerError(error: PlaybackException) {
                // The UI only says "reconnecting"; this is the only place the real RTSP/network
                // failure reason is visible for debugging.
                Log.w(TAG, "RTSP playback error, will retry via backoff", error)
                listener?.onError()
            }
        })
    }

    override fun play(config: CameraConfig) {
        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(FORCE_RTP_TCP)
            .setTimeoutMs(RTSP_TIMEOUT_MS)
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

    override fun renderedFrameCount(): Long =
        exoPlayer.videoDecoderCounters?.apply { ensureUpdated() }?.renderedOutputBufferCount?.toLong() ?: 0L

    override fun positionMs(): Long = exoPlayer.currentPosition

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

    companion object {
        private const val TAG = "Media3RtspPlayerController"

        // RES-08: start within 500ms of data and never hoard more than 2s (default is 2.5s/50s).
        const val MIN_BUFFER_MS = 500
        const val MAX_BUFFER_MS = 2_000
        const val BUFFER_FOR_PLAYBACK_MS = 500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 500

        // RES-10: RTP interleaved over TCP avoids Wi-Fi packet loss turning into grey/frozen frames.
        const val FORCE_RTP_TCP = true
        const val RTSP_TIMEOUT_MS = 8_000L
    }
}
