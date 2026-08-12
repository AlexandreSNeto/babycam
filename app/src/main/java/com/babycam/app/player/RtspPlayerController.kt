package com.babycam.app.player

import com.babycam.app.model.CameraConfig

/**
 * Abstraction over the underlying RTSP player (Media3/ExoPlayer in production).
 *
 * Kept free of any Media3/ExoPlayer types in its signature so [com.babycam.app.ui.CameraViewModel]
 * can be unit-tested against a fake implementation, without touching real Media3.
 */
interface RtspPlayerController {
    fun play(config: CameraConfig)
    fun release()
    fun setMuted(muted: Boolean)
    fun setListener(listener: PlayerEventListener)
}

interface PlayerEventListener {
    fun onReady()
    fun onError()
}
