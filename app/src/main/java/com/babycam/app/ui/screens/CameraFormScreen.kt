package com.babycam.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.babycam.app.form.CameraFormValidator
import com.babycam.app.form.FormField
import com.babycam.app.form.ValidationResult
import com.babycam.app.model.CameraConfig

/**
 * Shared form for adding (`existing == null`) and editing (`existing != null`) the camera.
 * See design.md Components > CameraFormScreen and spec.md CAM-03/04/12.
 */
@Composable
fun CameraFormScreen(
    existing: CameraConfig?,
    onSave: (CameraConfig) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var portText by remember { mutableStateOf(existing?.port?.toString() ?: "554") }
    var path by remember { mutableStateOf(existing?.path ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var errors by remember { mutableStateOf<Map<FormField, String>>(emptyMap()) }

    val validator = remember { CameraFormValidator() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            isError = errors.containsKey(FormField.HOST),
            supportingText = errors[FormField.HOST]?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it },
            label = { Text("Porta") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = errors.containsKey(FormField.PORT),
            supportingText = errors[FormField.PORT]?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("Path") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Usuário") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Senha") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                when (val result = validator.validate(host, portText, path, username, password)) {
                    is ValidationResult.Valid -> {
                        errors = emptyMap()
                        onSave(result.config)
                    }
                    is ValidationResult.Invalid -> errors = result.errors
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Salvar")
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text("Remover câmera")
            }
        }
    }
}
