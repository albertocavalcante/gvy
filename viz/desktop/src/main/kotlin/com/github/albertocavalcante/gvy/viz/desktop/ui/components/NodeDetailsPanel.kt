@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.albertocavalcante.gvy.viz.desktop.state.ParserType
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.NativeAstNodeDto

/**
 * Panel displaying details of the selected AST node.
 */
@Composable
fun NodeDetailsPanel(node: AstNodeDto?, parserType: ParserType, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(8.dp).verticalScroll(rememberScrollState()),
    ) {
        if (node == null) {
            Text(
                text = "Select a node to view details",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            NodeDetailsContent(node, parserType)
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun NodeDetailsContent(node: AstNodeDto, parserType: ParserType) {
    // Node type
    Text(
        text = node.type,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )

    // Node ID
    Text(
        text = "ID: ${node.id}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Properties
    if (node.properties.isNotEmpty()) {
        SectionCard(title = "Properties") {
            node.properties.forEach { (key, value) ->
                PropertyRow(key, value)
            }
        }
    }

    // Source location
    node.range?.let { range ->
        SectionCard(title = "Source Location") {
            PropertyRow("Start", "Line ${range.startLine}, Column ${range.startColumn}")
            PropertyRow("End", "Line ${range.endLine}, Column ${range.endColumn}")
        }
    }

    // Symbol info (Native parser only)
    if (node is NativeAstNodeDto) {
        node.symbolInfo?.let { symbolInfo ->
            SectionCard(title = "Symbol Information") {
                PropertyRow("Kind", symbolInfo.kind)
                PropertyRow("Scope", symbolInfo.scope)
                PropertyRow("Visibility", symbolInfo.visibility)
            }
        } ?: run {
            if (parserType == ParserType.NATIVE) {
                SectionCard(title = "Symbol Information") {
                    Text(
                        text = "Not available for this node type",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    )
                }
            }
        }
    } else if (parserType == ParserType.CORE) {
        SectionCard(title = "Symbol Information") {
            Text(
                text = "Not available (Core parser)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            )
        }
    }

    // Type info (Native parser only)
    if (node is NativeAstNodeDto) {
        node.typeInfo?.let { typeInfo ->
            SectionCard(title = "Type Information") {
                PropertyRow("Resolved Type", typeInfo.resolvedType)
                PropertyRow("Inferred", if (typeInfo.isInferred) "Yes" else "No")
                if (typeInfo.typeParameters.isNotEmpty()) {
                    PropertyRow("Type Parameters", typeInfo.typeParameters.joinToString(", "))
                }
            }
        } ?: run {
            if (parserType == ParserType.NATIVE) {
                SectionCard(title = "Type Information") {
                    Text(
                        text = "Not available for this node type",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    )
                }
            }
        }
    } else if (parserType == ParserType.CORE) {
        SectionCard(title = "Type Information") {
            Text(
                text = "Not available (Core parser)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            )
        }
    }

    // Children count
    if (node.children.isNotEmpty()) {
        SectionCard(title = "Children") {
            PropertyRow("Count", node.children.size.toString())
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun PropertyRow(key: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
