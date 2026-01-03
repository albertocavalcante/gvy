@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package com.github.albertocavalcante.gvy.viz.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.albertocavalcante.gvy.viz.desktop.state.AstViewModel
import com.github.albertocavalcante.gvy.viz.desktop.state.CodeError
import com.github.albertocavalcante.gvy.viz.desktop.state.ParserType
import com.github.albertocavalcante.gvy.viz.desktop.ui.components.AstTreeView
import com.github.albertocavalcante.gvy.viz.desktop.ui.components.CodePanel
import com.github.albertocavalcante.gvy.viz.desktop.ui.components.NodeDetailsPanel
import com.github.albertocavalcante.gvy.viz.desktop.ui.layout.DesktopPanel
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Root composable for the AST Visualizer application.
 */
@Composable
@Suppress("LongMethod", "MagicNumber")
fun AstVisualizerApp() {
    val viewModel = remember { AstViewModel() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Slim Toolbar
            TopBar(
                viewModel = viewModel,
                onLoadFile = {
                    loadFile(viewModel.lastDirectory)?.let { file ->
                        viewModel.loadFile(file)
                    }
                },
            )

            // Main Workspace
            Row(modifier = Modifier.fillMaxSize().weight(1f)) {
                // Left: Code panel
                DesktopPanel(
                    title = "Source",
                    modifier = Modifier.weight(0.35f).fillMaxSize(),
                ) {
                    CodePanel(
                        sourceCode = viewModel.sourceCode,
                        selectedNode = viewModel.selectedNode,
                        errors = viewModel.parseErrors,
                        onCodeChange = { code ->
                            viewModel.updateSourceCode(code)
                            viewModel.parseCode()
                        },
                        onCursorChange = { line, col ->
                            viewModel.selectNodeAt(line, col)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Center: AST tree
                DesktopPanel(
                    title = "AST Structure",
                    modifier = Modifier.weight(0.35f).fillMaxSize(),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (viewModel.isParsing) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center).size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            AstTreeView(
                                astTree = viewModel.astTree,
                                selectedNode = viewModel.selectedNode,
                                onNodeSelected = { viewModel.selectNode(it) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Right: Node details
                DesktopPanel(
                    title = "Properties",
                    modifier = Modifier.weight(0.3f).fillMaxSize(),
                ) {
                    NodeDetailsPanel(
                        node = viewModel.selectedNode,
                        parserType = viewModel.selectedParser,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Bottom Problems Panel (if errors exist)
            if (viewModel.parseErrors.isNotEmpty()) {
                DesktopPanel(
                    title = "Problems (${viewModel.parseErrors.size})",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                ) {
                    ErrorPanel(errors = viewModel.parseErrors)
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(errors: List<CodeError>) {
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        modifier = Modifier.background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)),
    ) {
        items(errors) { error ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (error.startLine != -1) {
                        "${error.message} [${error.startLine}:${error.startColumn}]"
                    } else {
                        error.message
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "MagicNumber")
private fun TopBar(viewModel: AstViewModel, onLoadFile: () -> Unit) {
    var showExportMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Groovy AST",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (viewModel.inputPath.isEmpty()) "Untitled" else File(viewModel.inputPath).name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        actions = {
            // Path input - flexible width
            OutlinedTextField(
                value = viewModel.inputPath,
                onValueChange = { viewModel.updateInputPath(it) },
                modifier = Modifier.widthIn(max = 600.dp).weight(1f, fill = false).padding(vertical = 4.dp),
                placeholder = { Text("Enter file path...", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                trailingIcon = {
                    IconButton(onClick = { viewModel.loadFromInputPath() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            "Load",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )

            Spacer(Modifier.width(8.dp))

            IconButton(onClick = onLoadFile) {
                Icon(Icons.Default.FileOpen, contentDescription = "Browse")
            }

            VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 8.dp))

            // Parser selector
            ParserSelector(viewModel)

            Spacer(Modifier.width(8.dp))

            // Export
            OutlinedButton(
                onClick = { showExportMenu = true },
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text("Export", style = MaterialTheme.typography.labelMedium)
            }

            DropdownMenu(
                expanded = showExportMenu,
                onDismissRequest = { showExportMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("JSON") },
                    onClick = {
                        showExportMenu = false
                        viewModel.saveToFile(viewModel.exportAsJson(), "ast.json")
                    },
                )
                DropdownMenuItem(
                    text = { Text("DOT (Core)") },
                    onClick = {
                        showExportMenu = false
                        viewModel.saveToFile(viewModel.exportAsDot(), "ast.dot")
                    },
                )
            }

            Spacer(Modifier.width(16.dp))
        },
    )
}

@Composable
private fun ParserSelector(viewModel: AstViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ParserOption("Core", viewModel.selectedParser == ParserType.CORE) {
            viewModel.setParser(ParserType.CORE)
        }
        ParserOption("Native", viewModel.selectedParser == ParserType.NATIVE) {
            viewModel.setParser(ParserType.NATIVE)
        }
        ParserOption("Rewrite", viewModel.selectedParser == ParserType.REWRITE) {
            viewModel.setParser(ParserType.REWRITE)
        }
    }
}

@Composable
private fun ParserOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(28.dp),
        elevation = null,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

private fun loadFile(initialDirectory: String): File? {
    val chooser = JFileChooser(initialDirectory)
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
