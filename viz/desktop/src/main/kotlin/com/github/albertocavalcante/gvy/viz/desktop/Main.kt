package com.github.albertocavalcante.gvy.viz.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.github.albertocavalcante.gvy.viz.desktop.ui.AstVisualizerApp

/**
 * Main entry point for the Groovy AST Visualizer desktop application.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Groovy AST Visualizer",
        state = rememberWindowState(width = 1400.dp, height = 900.dp),
    ) {
        MaterialTheme {
            AstVisualizerApp()
        }
    }
}
