@file:Suppress("ktlint:standard:function-naming")

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto

/**
 * Tree view component displaying the AST hierarchy.
 */
@Composable
fun AstTreeView(
    astTree: AstNodeDto?,
    selectedNode: AstNodeDto?,
    onNodeSelected: (AstNodeDto?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandedNodes = remember { mutableStateMapOf<String, Boolean>() }

    Box(modifier = modifier.padding(8.dp)) {
        if (astTree == null) {
            Text(
                text = "No AST to display. Load a file or paste code.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    TreeNodeItem(
                        node = astTree,
                        depth = 0,
                        isExpanded = expandedNodes[astTree.id] ?: true, // Root expanded by default
                        isSelected = selectedNode?.id == astTree.id,
                        onToggleExpand = { expandedNodes[astTree.id] = !(expandedNodes[astTree.id] ?: true) },
                        onNodeClick = { onNodeSelected(it) },
                        expandedNodes = expandedNodes,
                    )
                }
            }
        }
    }
}

@Composable
private fun TreeNodeItem(
    node: AstNodeDto,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggleExpand: () -> Unit,
    onNodeClick: (AstNodeDto) -> Unit,
    expandedNodes: MutableMap<String, Boolean>,
) {
    val hasChildren = node.children.isNotEmpty()
    val indentSize = (depth * 16).dp

    Column {
        // Node row
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

        // Children (if expanded)
        if (isExpanded && hasChildren) {
            node.children.forEach { child ->
                val childExpanded = expandedNodes[child.id] ?: (depth < 1) // Auto-expand first 2 levels
                TreeNodeItem(
                    node = child,
                    depth = depth + 1,
                    isExpanded = childExpanded,
                    isSelected = false, // Only one node can be selected
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
