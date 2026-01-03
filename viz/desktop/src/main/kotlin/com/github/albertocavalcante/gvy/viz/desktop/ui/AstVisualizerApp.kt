@file:Suppress("ktlint:standard:function-naming")

package com.github.albertocavalcante.gvy.viz.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.albertocavalcante.gvy.viz.desktop.state.AstViewModel
import com.github.albertocavalcante.gvy.viz.desktop.state.ParserType
import com.github.albertocavalcante.gvy.viz.desktop.ui.components.AstTreeView
import com.github.albertocavalcante.gvy.viz.desktop.ui.components.CodePanel
import com.github.albertocavalcante.gvy.viz.desktop.ui.components.NodeDetailsPanel
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Root composable for the AST Visualizer application.
 */
@Composable
fun AstVisualizerApp() {
    val viewModel = remember { AstViewModel() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            TopBar(
                viewModel = viewModel,
                onLoadFile = {
                    loadFile()?.let { file ->
                        viewModel.loadFile(file)
                    }
                },
            )

            // Main content: 3-panel layout
            Row(modifier = Modifier.fillMaxSize().weight(1f)) {
                // Left: Code panel (30%)
                CodePanel(
                    sourceCode = viewModel.sourceCode,
                    onCodeChange = { code ->
                        viewModel.updateSourceCode(code)
                        viewModel.parseCode()
                    },
                    modifier = Modifier.weight(0.3f).fillMaxSize(),
                )

                // Center: AST tree (40%)
                Box(modifier = Modifier.weight(0.4f).fillMaxSize()) {
                    if (viewModel.isParsing) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        AstTreeView(
                            astTree = viewModel.astTree,
                            selectedNode = viewModel.selectedNode,
                            onNodeSelected = { viewModel.selectNode(it) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Right: Node details (30%)
                NodeDetailsPanel(
                    node = viewModel.selectedNode,
                    parserType = viewModel.selectedParser,
                    modifier = Modifier.weight(0.3f).fillMaxSize(),
                )
            }

            // Status bar
            if (viewModel.parseErrors.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = viewModel.parseErrors.joinToString("\n"),
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(viewModel: AstViewModel, onLoadFile: () -> Unit) {
    var showExportMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("Groovy AST Visualizer") },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        actions = {
            // Load file button
            Button(onClick = onLoadFile) {
                Icon(Icons.Default.FileOpen, contentDescription = "Load file")
                Spacer(Modifier.width(4.dp))
                Text("Load File")
            }

            Spacer(Modifier.width(8.dp))

            // Parser selector
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Parser:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = viewModel.selectedParser == ParserType.CORE,
                        onClick = { viewModel.setParser(ParserType.CORE) },
                    )
                    Text("Core", style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.width(8.dp))

                    RadioButton(
                        selected = viewModel.selectedParser == ParserType.NATIVE,
                        onClick = { viewModel.setParser(ParserType.NATIVE) },
                    )
                    Text("Native", style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.width(8.dp))

                    RadioButton(
                        selected = viewModel.selectedParser == ParserType.REWRITE,
                        onClick = { viewModel.setParser(ParserType.REWRITE) },
                    )
                    Text("Rewrite", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.width(16.dp))

            // Export menu
            Box {
                OutlinedButton(onClick = { showExportMenu = true }) {
                    Text("Export")
                }

                DropdownMenu(
                    expanded = showExportMenu,
                    onDismissRequest = { showExportMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Export as JSON") },
                        onClick = {
                            showExportMenu = false
                            val json = viewModel.exportAsJson()
                            viewModel.saveToFile(json, "ast.json")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Export as DOT") },
                        onClick = {
                            showExportMenu = false
                            val dot = viewModel.exportAsDot()
                            viewModel.saveToFile(dot, "ast.dot")
                        },
                    )
                }
            }

            Spacer(Modifier.width(16.dp))
        },
    )
}

private fun loadFile(): File? {
    val chooser = JFileChooser()
    chooser.fileFilter = FileNameExtensionFilter(
        "Groovy Files (*.groovy, *.gvy, *.gradle)",
        "groovy",
        "gvy",
        "gradle",
    )

    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}
