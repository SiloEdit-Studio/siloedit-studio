package com.example.photoeditor.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Editor : Screen("editor")
    object AiTools : Screen("ai_tools")
    object Camera : Screen("camera")
}
