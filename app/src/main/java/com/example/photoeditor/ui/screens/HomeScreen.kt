package com.example.photoeditor.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onNavigateToCamera: () -> Unit,
    onNavigateToEditor: () -> Unit,
    onNavigateToAiTools: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Photo AI Studio", style = MaterialTheme.typography.displayMedium)
        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onNavigateToCamera,
            modifier = Modifier.fillMaxWidth(0.7f).height(56.dp)
        ) {
            Text("Cámara Pro")
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onNavigateToEditor,
            modifier = Modifier.fillMaxWidth(0.7f).height(56.dp)
        ) {
            Text("Editor de Imágenes")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onNavigateToAiTools,
            modifier = Modifier.fillMaxWidth(0.7f).height(56.dp)
        ) {
            Text("Herramientas IA")
        }
    }
}
