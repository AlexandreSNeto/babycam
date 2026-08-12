package com.babycam.app.ui

import android.app.PictureInPictureParams
import android.content.Context
import com.babycam.app.data.CameraConfigStore
import com.babycam.app.model.CameraConfig
import com.babycam.app.model.ViewState
import com.babycam.app.player.PlayerEventListener
import com.babycam.app.player.RtspPlayerController
import com.babycam.app.reconnect.BackoffPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the screen state machine: reads/writes [CameraConfigStore], drives [controller] and
 * the exponential-backoff reconnect loop, and exposes actions for the UI. See design.md
 * Components > CameraViewModel and spec.md CAM-01/02/06/07/08/09/10/13.
 */
class CameraViewModel(
    private val controller: RtspPlayerController,
    private val store: CameraConfigStore,
    private val backoffPolicy: BackoffPolicy = BackoffPolicy(),
    private val scope: CoroutineScope,
) {

    private val _viewState = MutableStateFlow<ViewState>(ViewState.Empty)
    val viewState: StateFlow<ViewState> = _viewState.asStateFlow()

    private var currentConfig: CameraConfig? = null

    init {
        controller.setListener(object : PlayerEventListener {
            override fun onReady() = handleReady()
            override fun onError() = handleError()
        })

        val config = store.getCamera()
        if (config == null) {
            _viewState.value = ViewState.Empty
        } else {
            startConnecting(config)
        }
    }

    private fun startConnecting(config: CameraConfig) {
        currentConfig = config
        _viewState.value = ViewState.Viewer.Connecting
        controller.play(config)
    }

    private fun handleReady() {
        when (_viewState.value) {
            is ViewState.Viewer.Connecting, is ViewState.Viewer.Reconnecting -> {
                val muted = store.getMuted()
                controller.setMuted(muted)
                _viewState.value = ViewState.Viewer.Playing(muted = muted)
            }
            else -> Unit
        }
    }

    private fun handleError() {
        val state = _viewState.value
        val muted = when (state) {
            is ViewState.Viewer.Playing -> state.muted
            is ViewState.Viewer.Reconnecting -> state.muted
            is ViewState.Viewer.Connecting -> store.getMuted()
            else -> return
        }
        val attempt = if (state is ViewState.Viewer.Reconnecting) state.attempt + 1 else 1
        _viewState.value = ViewState.Viewer.Reconnecting(muted = muted, attempt = attempt)

        val config = currentConfig ?: return
        scope.launch {
            delay(backoffPolicy.nextDelayMillis(attempt))
            controller.play(config)
        }
    }

    fun toggleMute() {
        val newMuted = when (val state = _viewState.value) {
            is ViewState.Viewer.Playing -> !state.muted
            is ViewState.Viewer.Reconnecting -> !state.muted
            else -> return
        }
        store.setMuted(newMuted)
        controller.setMuted(newMuted)
        _viewState.value = when (val state = _viewState.value) {
            is ViewState.Viewer.Playing -> state.copy(muted = newMuted)
            is ViewState.Viewer.Reconnecting -> state.copy(muted = newMuted)
            else -> state
        }
    }

    fun saveCamera(config: CameraConfig) {
        store.saveCamera(config)
        startConnecting(config)
    }

    fun deleteCamera() {
        store.deleteCamera()
        controller.release()
        currentConfig = null
        _viewState.value = ViewState.Empty
    }

    /** Minimal PiP params so the interface compiles; full action wiring lands in Phase 5 (T15). */
    fun buildPipParams(context: Context): PictureInPictureParams {
        return PictureInPictureParams.Builder().build()
    }
}
