package com.babycam.app.model

data class CameraConfig(
    val host: String,
    val port: Int = 554,
    val path: String = "",
    val username: String? = null,
    val password: String? = null,
)
