@file:Suppress("ktlint:standard:function-naming")

package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Panel displaying the source code.
 */
@Composable
fun CodePanel(sourceCode: String, onCodeChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(8.dp)) {
        OutlinedTextField(
            value = sourceCode,
            onValueChange = onCodeChange,
            modifier = Modifier.fillMaxSize(),
            label = { Text("Groovy Source Code") },
            placeholder = { Text("Paste Groovy code here or load a file...") },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
        )
    }
}
