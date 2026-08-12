package com.babycam.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.babycam.app.data.CameraConfigStore
import com.babycam.app.model.ViewState
import com.babycam.app.player.Media3RtspPlayerController
import com.babycam.app.ui.CameraViewModel
import com.babycam.app.ui.screens.CameraFormScreen
import com.babycam.app.ui.screens.EmptyStateScreen
import com.babycam.app.ui.screens.ViewerScreen

/**
 * Single-Activity host: observes [CameraViewModel.viewState] and renders one of the three
 * screens via a `when`. See design.md Components > MainActivity and spec.md CAM-01/02/10/11.
 */
class MainActivity : ComponentActivity() {

    private lateinit var playerController: Media3RtspPlayerController
    private lateinit var viewModel: CameraViewModel

    /** Handles the PiP window's mute/unmute RemoteAction. See CAM-15. */
    private val muteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewModel.toggleMute()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        playerController = Media3RtspPlayerController(applicationContext)
        viewModel = CameraViewModel(
            controller = playerController,
            store = CameraConfigStore(applicationContext),
            scope = lifecycleScope,
        )

        ContextCompat.registerReceiver(
            this,
            muteReceiver,
            IntentFilter(CameraViewModel.ACTION_TOGGLE_MUTE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            MaterialTheme {
                Surface {
                    val state by viewModel.viewState.collectAsState()
                    val orientation = LocalConfiguration.current.orientation

                    // CAM-10: keep the screen on for as long as the stream is showing or
                    // reconnecting; release it on Empty/Form.
                    LaunchedEffect(state) {
                        if (state is ViewState.Viewer) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        // CAM-16: keep the PiP action's icon/label in sync with `muted` while
                        // already in PiP mode.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                            setPictureInPictureParams(viewModel.buildPipParams(this@MainActivity))
                        }
                    }

                    // CAM-11: hide system bars for a true fullscreen video in landscape; restore
                    // them back in portrait. Keyed on orientation too since `state` alone doesn't
                    // change across a rotation (Playing stays Playing).
                    LaunchedEffect(state, orientation) {
                        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                        val fullscreen = state is ViewState.Viewer && orientation == Configuration.ORIENTATION_LANDSCAPE
                        WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
                        if (fullscreen) {
                            insetsController.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            insetsController.hide(WindowInsetsCompat.Type.systemBars())
                        } else {
                            insetsController.show(WindowInsetsCompat.Type.systemBars())
                        }
                    }

                    when (val s = state) {
                        is ViewState.Empty -> EmptyStateScreen(onAddClick = viewModel::showForm)

                        is ViewState.Form -> CameraFormScreen(
                            existing = s.existing,
                            onSave = viewModel::saveCamera,
                            onDelete = if (s.existing != null) viewModel::deleteCamera else null,
                        )

                        is ViewState.Viewer -> ViewerScreen(
                            state = s,
                            player = playerController.exoPlayer,
                            onToggleMute = viewModel::toggleMute,
                            onEditClick = viewModel::showForm,
                        )
                    }
                }
            }
        }
    }

    /** CAM-14: auto-enter PiP on leaving the app, only while a stream is showing/reconnecting. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            viewModel.viewState.value is ViewState.Viewer
        ) {
            enterPictureInPictureMode(viewModel.buildPipParams(this))
        }
    }

    override fun onDestroy() {
        unregisterReceiver(muteReceiver)
        playerController.release()
        super.onDestroy()
    }
}
