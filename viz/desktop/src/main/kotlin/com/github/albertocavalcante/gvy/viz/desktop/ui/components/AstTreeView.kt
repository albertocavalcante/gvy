@file:Suppress("ktlint:standard:function-naming", "FunctionNaming", "MagicNumber")

package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.Class
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import com.github.albertocavalcante.gvy.viz.model.NativeAstNodeDto
import com.github.albertocavalcante.gvy.viz.model.RewriteAstNodeDto

/**
 * Tree view component displaying the AST hierarchy.
 */
@Composable
@Suppress("LongMethod")
fun AstTreeView(
    astTree: AstNodeDto?,
    selectedNode: AstNodeDto?,
    onNodeSelected: (AstNodeDto?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filterText by remember { mutableStateOf("") }
    val expandedNodes = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()

    // Auto-expand parents of selected node
    LaunchedEffect(selectedNode) {
        selectedNode?.let { node ->
            if (node.children.isNotEmpty()) {
                expandedNodes[node.id] = true
            }
        }
    }

    Column(modifier = modifier.padding(8.dp)) {
        // Filter bar
        OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            placeholder = { Text("Filter nodes...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Box(modifier = Modifier.weight(1f)) {
            if (astTree == null) {
                Text(
                    text = "No AST to display. Load a file or paste code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val filteredTree = remember(astTree, filterText) {
                    if (filterText.isBlank()) astTree else filterAst(astTree, filterText)
                }

                if (filteredTree == null) {
                    Text(
                        text = "No nodes match \"$filterText\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                    ) {
                        item {
                            TreeNodeItem(
                                node = filteredTree,
                                depth = 0,
                                isExpanded = expandedNodes[filteredTree.id] ?: true,
                                selectedNodeId = selectedNode?.id,
                                onToggleExpand = {
                                    expandedNodes[filteredTree.id] = !(expandedNodes[filteredTree.id] ?: true)
                                },
                                onNodeClick = { onNodeSelected(it) },
                                expandedNodes = expandedNodes,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun filterAst(node: AstNodeDto, query: String): AstNodeDto? {
    val matches = node.type.contains(query, ignoreCase = true) ||
        (node.properties["name"]?.contains(query, ignoreCase = true) ?: false)

    val filteredChildren = node.children.mapNotNull { filterAst(it, query) }

    if (!matches && filteredChildren.isEmpty()) return null

    // Return a copy of the node with only filtered children
    return when (node) {
        is CoreAstNodeDto -> node.copy(children = filteredChildren)
        is NativeAstNodeDto -> node.copy(children = filteredChildren)
        is RewriteAstNodeDto -> node.copy(children = filteredChildren)
    }
}

@Composable
@Suppress("LongParameterList")
private fun TreeNodeItem(
    node: AstNodeDto,
    depth: Int,
    isExpanded: Boolean,
    selectedNodeId: String?,
    onToggleExpand: () -> Unit,
    onNodeClick: (AstNodeDto) -> Unit,
    expandedNodes: MutableMap<String, Boolean>,
) {
    val isSelected = selectedNodeId == node.id
    val hasChildren = node.children.isNotEmpty()
    val indentSize = (depth * 16).dp

    Column {
        NodeRow(
            node = node,
            isSelected = isSelected,
            isExpanded = isExpanded,
            hasChildren = hasChildren,
            indentSize = indentSize,
            onNodeClick = onNodeClick,
            onToggleExpand = onToggleExpand,
        )

        // Children (if expanded)
        if (isExpanded && hasChildren) {
            node.children.forEach { child ->
                val childExpanded = expandedNodes[child.id] ?: (depth < 1) // Auto-expand first 2 levels
                TreeNodeItem(
                    node = child,
                    depth = depth + 1,
                    isExpanded = childExpanded,
                    selectedNodeId = selectedNodeId,
                    onToggleExpand = {
                        expandedNodes[child.id] = !childExpanded
                    },
                    onNodeClick = onNodeClick,
                    expandedNodes = expandedNodes,
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun NodeRow(
    node: AstNodeDto,
    isSelected: Boolean,
    isExpanded: Boolean,
    hasChildren: Boolean,
    indentSize: Dp,
    onNodeClick: (AstNodeDto) -> Unit,
    onToggleExpand: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable { onNodeClick(node) }
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .padding(start = indentSize),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Expand/collapse icon
        if (hasChildren) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp).clickable { onToggleExpand() },
                tint = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Spacer(modifier = Modifier.width(20.dp))
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Node Icon
        Icon(
            imageVector = getNodeIcon(node.type),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.primary
            },
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Node type
        Text(
            text = node.type,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )

        // Node name (if available)
        node.properties["name"]?.let { name ->
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = ": \"$name\"",
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        // Children count
        if (hasChildren) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "(${node.children.size})",
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
            )
        }
    }
}

private fun getNodeIcon(type: String): ImageVector = when {
    type.contains("Class", ignoreCase = true) -> Icons.Default.Class
    type.contains(
        "Method",
        ignoreCase = true,
    ) || type.contains("Constructor", ignoreCase = true) -> Icons.Default.Functions
    type.contains(
        "Field",
        ignoreCase = true,
    ) || type.contains("Property", ignoreCase = true) -> Icons.Default.Settings
    type.contains(
        "Variable",
        ignoreCase = true,
    ) || type.contains("Parameter", ignoreCase = true) -> Icons.Default.DataObject
    type.contains("Package", ignoreCase = true) -> Icons.Default.Inventory
    type.contains("Import", ignoreCase = true) -> Icons.AutoMirrored.Filled.Input
    type.contains("Expression", ignoreCase = true) -> Icons.Default.Code
    type.contains("Statement", ignoreCase = true) -> Icons.Default.Terminal
    else -> Icons.Default.Description
}
