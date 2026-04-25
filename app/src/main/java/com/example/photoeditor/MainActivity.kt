package com.example.photoeditor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.photoeditor.navigation.Screen
import com.example.photoeditor.ui.PhotoViewModel
import com.example.photoeditor.ui.screens.AiToolsScreen
import com.example.photoeditor.ui.screens.CameraScreen
import com.example.photoeditor.ui.screens.EditorScreen
import com.example.photoeditor.ui.screens.HomeScreen
import com.example.photoeditor.ui.theme.PhotoEditorTheme

class MainActivity : ComponentActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permiso concedido o denegado
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Solicitar permiso de notificaciones en Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != 
                PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            PhotoEditorTheme {
                val navController = rememberNavController()
                val viewModel: PhotoViewModel = viewModel()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.route,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable(Screen.Home.route) {
                            HomeScreen(
                                onNavigateToEditor = { navController.navigate(Screen.Editor.route) },
                                onNavigateToAiTools = { navController.navigate(Screen.AiTools.route) },
                                onNavigateToCamera = { navController.navigate(Screen.Camera.route) }
                            )
                        }
                        composable(Screen.Editor.route) {
                            EditorScreen(viewModel)
                        }
                        composable(Screen.AiTools.route) {
                            AiToolsScreen(viewModel)
                        }
                        composable(Screen.Camera.route) {
                            CameraScreen(viewModel, onPhotoCaptured = {
                                navController.navigate(Screen.Editor.route)
                            })
                        }
                    }
                }
            }
        }
    }
}
