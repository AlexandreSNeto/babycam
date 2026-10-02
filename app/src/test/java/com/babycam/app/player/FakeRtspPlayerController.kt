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

    /** Tests advance this to simulate frames being rendered; leave it still to simulate a freeze. */
    var renderedFrames = 0L
    var position = 0L

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

    override fun renderedFrameCount(): Long = renderedFrames

    override fun positionMs(): Long = position

    fun triggerReady() {
        listener?.onReady()
    }

    fun triggerError() {
        listener?.onError()
    }
}
