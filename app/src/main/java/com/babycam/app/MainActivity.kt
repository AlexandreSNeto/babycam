package com.babycam.app

import android.content.pm.PackageManager
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        playerController = Media3RtspPlayerController(applicationContext)
        viewModel = CameraViewModel(
            controller = playerController,
            store = CameraConfigStore(applicationContext),
            scope = lifecycleScope,
        )

        setContent {
            MaterialTheme {
                Surface {
                    val state by viewModel.viewState.collectAsState()

                    // CAM-10: keep the screen on for as long as the stream is showing or
                    // reconnecting; release it on Empty/Form.
                    LaunchedEffect(state) {
                        if (state is ViewState.Viewer) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    // ponytail: local placeholder for the Empty->Form navigation; T16 replaces
                    // this with a real CameraViewModel-driven state transition.
                    var showAddForm by remember { mutableStateOf(false) }

                    when (val s = state) {
                        is ViewState.Empty -> {
                            if (showAddForm) {
                                CameraFormScreen(
                                    existing = null,
                                    onSave = {
                                        showAddForm = false
                                        viewModel.saveCamera(it)
                                    },
                                    onDelete = null,
                                )
                            } else {
                                EmptyStateScreen(onAddClick = { showAddForm = true })
                            }
                        }

                        is ViewState.Form -> CameraFormScreen(
                            existing = s.existing,
                            onSave = viewModel::saveCamera,
                            onDelete = if (s.existing != null) viewModel::deleteCamera else null,
                        )

                        is ViewState.Viewer -> ViewerScreen(
                            state = s,
                            player = playerController.exoPlayer,
                            onToggleMute = viewModel::toggleMute,
                            onEditClick = { /* wired in T16 */ },
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
}
