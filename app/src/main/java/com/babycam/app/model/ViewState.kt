package com.babycam.app.model

sealed interface ViewState {
    data object Empty : ViewState
    data class Form(val existing: CameraConfig?) : ViewState
    sealed interface Viewer : ViewState {
        data object Connecting : Viewer
        data class Playing(val muted: Boolean) : Viewer
        data class Reconnecting(val muted: Boolean, val attempt: Int) : Viewer
    }
}
