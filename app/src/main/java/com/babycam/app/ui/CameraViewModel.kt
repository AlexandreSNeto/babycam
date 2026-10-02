package com.babycam.app.ui

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.util.Log
import com.babycam.app.data.CameraConfigStore
import com.babycam.app.model.CameraConfig
import com.babycam.app.model.StallCause
import com.babycam.app.model.ViewState
import com.babycam.app.player.PlayerEventListener
import com.babycam.app.player.RtspPlayerController
import com.babycam.app.reconnect.BackoffPolicy
import com.babycam.app.reconnect.StreamHealthMonitor
import com.babycam.app.reconnect.StreamHealthMonitor.Health
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
    private val connectTimeoutMillis: Long = 30_000L,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val healthMonitor: StreamHealthMonitor = StreamHealthMonitor(),
) {

    /** Why a new connection is being made; logged only (RES-13). */
    private enum class ReconnectReason { STALL, DRIFT, ERROR, CONNECT_TIMEOUT, NETWORK_AVAILABLE }

    private val _viewState = MutableStateFlow<ViewState>(ViewState.Empty)
    val viewState: StateFlow<ViewState> = _viewState.asStateFlow()

    private var currentConfig: CameraConfig? = null
    private var watchdogJob: Job? = null
    private var retryJob: Job? = null
    private var healthJob: Job? = null
    private var tickerJob: Job? = null

    init {
        controller.setListener(object : PlayerEventListener {
            override fun onReady() = handleReady()
            override fun onError() = handleError(ReconnectReason.ERROR)
        })

        val config = store.getCamera()
        if (config == null) {
            _viewState.value = ViewState.Empty
        } else {
            startConnecting(config)
        }
    }

    private fun startConnecting(config: CameraConfig) {
        cancelJobs()
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
            Log.w(TAG, "No onReady/onError within ${connectTimeoutMillis}ms — treating as a timeout")
            handleError(ReconnectReason.CONNECT_TIMEOUT)
        }
    }

    private fun handleReady() {
        watchdogJob?.cancel()
        when (_viewState.value) {
            is ViewState.Viewer.Connecting,
            is ViewState.Viewer.Reconnecting,
            is ViewState.Viewer.Stalled -> {
                val muted = store.getMuted()
                controller.setMuted(muted)
                _viewState.value = ViewState.Viewer.Playing(muted = muted)
                startHealthMonitor()
            }
            else -> Unit
        }
    }

    /**
     * While [ViewState.Viewer.Playing], samples the player every [HEALTH_POLL_MILLIS] so a frozen
     * picture or piled-up latency triggers a fresh connection even when the player reports no
     * error (RES-01/RES-09). Re-baselined on every new session (RES-15).
     */
    private fun startHealthMonitor() {
        healthJob?.cancel()
        healthMonitor.reset(clock(), controller.renderedFrameCount(), controller.positionMs())
        healthJob = scope.launch {
            while (isActive) {
                delay(HEALTH_POLL_MILLIS)
                val health = healthMonitor.check(clock(), controller.renderedFrameCount(), controller.positionMs())
                if (health != Health.OK) {
                    handleUnhealthy(if (health == Health.STALL) StallCause.STALL else StallCause.DRIFT)
                    return@launch
                }
            }
        }
    }

    private fun handleUnhealthy(cause: StallCause) {
        val state = _viewState.value as? ViewState.Viewer.Playing ?: return
        _viewState.value = ViewState.Viewer.Stalled(
            muted = state.muted,
            attempt = 1,
            stalledForSec = ((clock() - healthMonitor.lastFrameAtMs()) / 1000).toInt(),
            cause = cause,
        )
        startStalledTicker()
        reconnect(
            if (cause == StallCause.STALL) ReconnectReason.STALL else ReconnectReason.DRIFT,
            attempt = 1,
            delayMillis = 0,
        )
    }

    /** Keeps the banner's "travada há Ns" current; ends by itself once the state leaves Stalled (RES-06). */
    private fun startStalledTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val state = _viewState.value as? ViewState.Viewer.Stalled ?: return@launch
                _viewState.value =
                    state.copy(stalledForSec = ((clock() - healthMonitor.lastFrameAtMs()) / 1000).toInt())
            }
        }
    }

    private fun cancelJobs() {
        watchdogJob?.cancel()
        retryJob?.cancel()
        healthJob?.cancel()
        tickerJob?.cancel()
    }

    private fun handleError(reason: ReconnectReason) {
        val state = _viewState.value
        val next: ViewState.Viewer = when (state) {
            is ViewState.Viewer.Playing -> ViewState.Viewer.Reconnecting(muted = state.muted, attempt = 1)
            is ViewState.Viewer.Reconnecting -> state.copy(attempt = state.attempt + 1)
            is ViewState.Viewer.Connecting -> ViewState.Viewer.Reconnecting(muted = store.getMuted(), attempt = 1)
            // Stays Stalled so the last frame + banner remain until video is back (RES-03).
            is ViewState.Viewer.Stalled -> state.copy(attempt = state.attempt + 1)
            else -> return
        }
        _viewState.value = next
        val attempt = when (next) {
            is ViewState.Viewer.Reconnecting -> next.attempt
            is ViewState.Viewer.Stalled -> next.attempt
            else -> 1
        }
        reconnect(reason, attempt, backoffPolicy.nextDelayMillis(attempt))
    }

    /**
     * Single entry point for every new connection: cancels whatever is pending (watchdog, earlier
     * retry, health loop) so overlapping triggers never schedule two `play()`s.
     */
    private fun reconnect(reason: ReconnectReason, attempt: Int, delayMillis: Long) {
        Log.w(TAG, "reconnect reason=$reason attempt=$attempt delayMs=$delayMillis")
        watchdogJob?.cancel()
        retryJob?.cancel()
        healthJob?.cancel()
        val config = currentConfig ?: return
        if (delayMillis == 0L) {
            playWithWatchdog(config)
        } else {
            retryJob = scope.launch {
                delay(delayMillis)
                playWithWatchdog(config)
            }
        }
    }

    fun toggleMute() {
        val newMuted = when (val state = _viewState.value) {
            is ViewState.Viewer.Playing -> !state.muted
            is ViewState.Viewer.Reconnecting -> !state.muted
            is ViewState.Viewer.Stalled -> !state.muted
            else -> return
        }
        store.setMuted(newMuted)
        controller.setMuted(newMuted)
        _viewState.value = when (val state = _viewState.value) {
            is ViewState.Viewer.Playing -> state.copy(muted = newMuted)
            is ViewState.Viewer.Reconnecting -> state.copy(muted = newMuted)
            is ViewState.Viewer.Stalled -> state.copy(muted = newMuted)
            else -> state
        }
    }

    fun saveCamera(config: CameraConfig) {
        store.saveCamera(config)
        startConnecting(config)
    }

    fun deleteCamera() {
        cancelJobs()
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
            is ViewState.Viewer.Stalled -> state.muted
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
        private const val TAG = "CameraViewModel"
        private const val HEALTH_POLL_MILLIS = 250L
    }
}
