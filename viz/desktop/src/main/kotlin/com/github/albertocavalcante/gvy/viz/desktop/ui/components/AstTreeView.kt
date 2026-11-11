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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto

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
    val snapshot = AstTreeSnapshot(astTree)
    val projection = remember(snapshot) { astTree?.let(::AstTreeProjection) }
    // Expansion and scroll belong to this parse snapshot; IDs are reused by converters.
    val expandedNodes = remember(snapshot) { mutableStateMapOf<String, Boolean>() }
    val listState = remember(snapshot) { LazyListState() }
    val included = remember(projection, filterText) { projection?.matchingIds(filterText) }
    val rows = projection?.visibleRows(expandedNodes, included).orEmpty()
    val selectionPath = selectedNode?.let { projection?.pathTo(it) }.orEmpty()
    var pendingReveal by remember(snapshot) { mutableStateOf<AstNodeDto?>(null) }

    LaunchedEffect(projection, selectedNode) {
        pendingReveal = selectedNode.takeIf { selectionPath.isNotEmpty() }
        if (selectionPath.isNotEmpty()) {
            val hiddenByFilter = included != null && selectedNode?.id !in included
            if (included == null || hiddenByFilter) {
                selectionPath.dropLast(1).forEach { expandedNodes[it] = true }
            }
            // Visible filtered selections preserve the manual folds restored when filtering ends.
            if (hiddenByFilter) filterText = ""
        }
    }
    LaunchedEffect(projection, rows, pendingReveal) {
        val target = pendingReveal ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.node === target }
        if (index >= 0) {
            if (!listState.isFullyVisible(target.id)) listState.scrollToItem(index)
            pendingReveal = null
        }
    }

    Column(modifier = modifier) {
        TextField(
            value = filterText,
            onValueChange = { filterText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Filter nodes...", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.weight(1f).padding(top = 4.dp).fillMaxSize()) {
            if (rows.isEmpty()) {
                Text(
                    text = if (astTree == null) "No AST to display." else "No match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    items(rows, key = { it.node.id }) { row ->
                        NodeRow(
                            node = row.node,
                            isSelected = row.node === selectedNode,
                            isExpanded = row.isExpanded,
                            hasChildren = row.hasChildren,
                            canToggleExpand = included == null,
                            indentSize = (row.depth * 12).dp,
                            onNodeClick = onNodeSelected,
                            onToggleExpand = {
                                // Filtering temporarily opens all matching paths without changing manual expansion.
                                if (included == null) expandedNodes[row.node.id] = !row.isExpanded
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListState.isFullyVisible(nodeId: String): Boolean {
    val layout = layoutInfo
    return layout.visibleItemsInfo.any {
        it.key == nodeId && it.offset >= layout.viewportStartOffset &&
            it.offset + it.size <= layout.viewportEndOffset
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun NodeRow(
    node: AstNodeDto,
    isSelected: Boolean,
    isExpanded: Boolean,
    hasChildren: Boolean,
    canToggleExpand: Boolean,
    indentSize: Dp,
    onNodeClick: (AstNodeDto) -> Unit,
    onToggleExpand: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
            )
            .clickable { onNodeClick(node) }
            .padding(vertical = 2.dp, horizontal = 4.dp)
            .padding(start = indentSize),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Expand/collapse icon
        if (hasChildren) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = when {
                    !canToggleExpand -> "Expanded to show matches"
                    isExpanded -> "Collapse"
                    else -> "Expand"
                },
                modifier = Modifier.size(16.dp).clickable(enabled = canToggleExpand) { onToggleExpand() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Node Icon
        Icon(
            imageVector = getNodeIcon(node.type),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            },
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Node type
        Text(
            text = node.type,
            style = MaterialTheme.typography.bodySmall,
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
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
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
    type.contains("Field", ignoreCase = true) || type.contains("Property", ignoreCase = true) -> Icons.Default.Settings
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
