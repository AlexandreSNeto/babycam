package com.babycam.app.model

sealed interface ViewState {
    data object Empty : ViewState
    data class Form(val existing: CameraConfig?) : ViewState
    sealed interface Viewer : ViewState {
        data object Connecting : Viewer
        data class Playing(val muted: Boolean) : Viewer
        data class Reconnecting(val muted: Boolean, val attempt: Int) : Viewer

        /**
         * The picture froze or fell too far behind live while playing: the last frame stays on
         * screen under a banner until a new frame arrives (RES-05..07). [stalledForSec] counts
         * from the last rendered frame.
         */
        data class Stalled(
            val muted: Boolean,
            val attempt: Int,
            val stalledForSec: Int,
            val cause: StallCause,
        ) : Viewer
    }
}

enum class StallCause { STALL, DRIFT }
