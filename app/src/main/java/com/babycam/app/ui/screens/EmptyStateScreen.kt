package com.babycam.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Shown when no camera is registered yet. See spec.md CAM-02. */
@Composable
fun EmptyStateScreen(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Nenhuma câmera cadastrada")
        Button(onClick = onAddClick) {
            Text("Adicionar câmera")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStateScreenPreview() {
    MaterialTheme {
        Surface {
            EmptyStateScreen(onAddClick = {})
        }
    }
}
