package com.babycam.app.player

import com.babycam.app.model.CameraConfig

/**
 * In-memory fake of [RtspPlayerController] for unit-testing [com.babycam.app.ui.CameraViewModel]
 * without touching real Media3/ExoPlayer. Records calls and exposes [triggerReady]/[triggerError]
 * so tests can manually simulate the player's listener callbacks.
 */
class FakeRtspPlayerController : RtspPlayerController {

    val playCalls = mutableListOf<CameraConfig>()
    val setMutedCalls = mutableListOf<Boolean>()
    var releaseCallCount = 0
        private set

    private var listener: PlayerEventListener? = null

    override fun play(config: CameraConfig) {
        playCalls.add(config)
    }

    override fun release() {
        releaseCallCount++
    }

    override fun setMuted(muted: Boolean) {
        setMutedCalls.add(muted)
    }

    override fun setListener(listener: PlayerEventListener) {
        this.listener = listener
    }

    fun triggerReady() {
        listener?.onReady()
    }

    fun triggerError() {
        listener?.onError()
    }
}
