package com.babycam.app.ui

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.babycam.app.data.CameraConfigStore
import com.babycam.app.model.CameraConfig
import com.babycam.app.model.ViewState
import com.babycam.app.player.PlayerEventListener
import com.babycam.app.player.RtspPlayerController
import com.babycam.app.reconnect.BackoffPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    /**
     * Media3's RTSP extension can block indefinitely without ever calling `onError` when a
     * camera/network never responds (see androidx/media GitHub issue #10946 — known to hang up
     * to ~2 minutes with no error). Since reconnection is entirely error-driven, a silent hang
     * would strand the user on "Conectando..." forever, violating CAM-08/CAM-09's "never get
     * stuck" guarantee. This watchdog treats prolonged silence as an error, so we always fall
     * back into the normal backoff/retry cycle instead of waiting on the player indefinitely.
     */
    private val connectTimeoutMillis: Long = 15_000L,
) {

    private val _viewState = MutableStateFlow<ViewState>(ViewState.Empty)
    val viewState: StateFlow<ViewState> = _viewState.asStateFlow()

    private var currentConfig: CameraConfig? = null
    private var watchdogJob: Job? = null

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
        playWithWatchdog(config)
    }

    /** Calls [controller.play] and arms the connect-timeout watchdog (see its kdoc above). */
    private fun playWithWatchdog(config: CameraConfig) {
        controller.play(config)
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(connectTimeoutMillis)
            handleError()
        }
    }

    private fun handleReady() {
        watchdogJob?.cancel()
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
        watchdogJob?.cancel()
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
            playWithWatchdog(config)
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

    /**
     * Switches to the camera form, pre-filled with the persisted config if one exists (edit,
     * CAM-12 AC1) or blank otherwise (add, from [ViewState.Empty]).
     */
    fun showForm() {
        _viewState.value = ViewState.Form(existing = store.getCamera())
    }

    /**
     * Builds PiP params with a mute/unmute [RemoteAction] reflecting the current [muted] state.
     * Only [ViewState.Viewer.Playing]/[ViewState.Viewer.Reconnecting] carry a mute state, so no
     * action is attached for `Empty`/`Form` (matches T14's guard against entering PiP there).
     * See spec.md CAM-15/CAM-16.
     */
    fun buildPipParams(context: Context): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val muted = when (val state = _viewState.value) {
            is ViewState.Viewer.Playing -> state.muted
            is ViewState.Viewer.Reconnecting -> state.muted
            else -> null
        }
        if (muted != null) {
            val iconRes = if (muted) {
                android.R.drawable.ic_lock_silent_mode_off
            } else {
                android.R.drawable.ic_lock_silent_mode
            }
            val label = if (muted) "Ativar áudio" else "Silenciar"
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_TOGGLE_MUTE).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setActions(
                listOf(RemoteAction(Icon.createWithResource(context, iconRes), label, label, pendingIntent)),
            )
        }
        return builder.build()
    }

    companion object {
        /** Broadcast action for the PiP window's mute/unmute RemoteAction. App-internal only. */
        const val ACTION_TOGGLE_MUTE = "com.babycam.app.ACTION_TOGGLE_MUTE"
    }
}
