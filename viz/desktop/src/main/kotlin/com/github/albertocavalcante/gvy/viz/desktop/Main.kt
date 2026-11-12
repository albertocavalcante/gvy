// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.github.albertocavalcante.gvy.viz.desktop.state.AstViewModel
import com.github.albertocavalcante.gvy.viz.desktop.ui.AstVisualizerApp

/**
 * Main entry point for the Groovy AST Visualizer desktop application.
 */
fun main() = application {
    val viewModel = remember { AstViewModel() }
    Window(
        onCloseRequest = { if (viewModel.canClose()) exitApplication() },
        title = "Groovy AST Visualizer",
        state = rememberWindowState(width = 1400.dp, height = 900.dp),
    ) {
        MaterialTheme {
            AstVisualizerApp(viewModel)
        }
    }
}
