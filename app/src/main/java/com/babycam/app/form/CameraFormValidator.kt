package com.babycam.app.form

import com.babycam.app.model.CameraConfig

/** Fields the camera form can show an inline validation error on. */
enum class FormField { HOST, PORT }

sealed interface ValidationResult {
    data class Valid(val config: CameraConfig) : ValidationResult
    data class Invalid(val errors: Map<FormField, String>) : ValidationResult
}

/**
 * Validates raw camera form input. Host is required; port is required, numeric and in
 * 1..65535; path/username/password are optional (blank is valid).
 */
class CameraFormValidator {

    fun validate(
        host: String,
        portText: String,
        path: String,
        username: String,
        password: String,
    ): ValidationResult {
        val errors = mutableMapOf<FormField, String>()

        if (host.isBlank()) {
            errors[FormField.HOST] = "Host é obrigatório"
        }

        val port = portText.toIntOrNull()
        if (port == null) {
            errors[FormField.PORT] = "Porta deve ser um número"
        } else if (port !in 1..65535) {
            errors[FormField.PORT] = "Porta deve estar entre 1 e 65535"
        }

        if (errors.isNotEmpty()) {
            return ValidationResult.Invalid(errors)
        }

        return ValidationResult.Valid(
            CameraConfig(
                host = host,
                port = port!!,
                path = path,
                username = username.ifBlank { null },
                password = password.ifBlank { null },
            ),
        )
    }
}
