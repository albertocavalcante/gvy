@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.albertocavalcante.gvy.viz.desktop.state.ParserType
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.NativeAstNodeDto

/**
 * Panel displaying details of the selected AST node in a property grid style.
 */
@Composable
fun NodeDetailsPanel(node: AstNodeDto?, parserType: ParserType, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        if (node == null) {
            Text(
                text = "Select a node.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            NodeDetailsContent(node, parserType)
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun NodeDetailsContent(node: AstNodeDto, parserType: ParserType) {
    // Header Info
    Column(modifier = Modifier.padding(12.dp)) {
        Text(
            text = node.type,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = node.id,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    // Property Grid
    SectionHeader("Identification")
    PropertyRow("Type", node.type)
    PropertyRow("ID", node.id)

    if (node.properties.isNotEmpty()) {
        SectionHeader("Properties")
        node.properties.forEach { (key, value) ->
            PropertyRow(key, value)
        }
    }

    node.range?.let { range ->
        SectionHeader("Range")
        PropertyRow("Start", "${range.startLine}:${range.startColumn}")
        PropertyRow("End", "${range.endLine}:${range.endColumn}")
    }

    if (node is NativeAstNodeDto) {
        node.symbolInfo?.let { symbol ->
            SectionHeader("Symbol")
            PropertyRow("Kind", symbol.kind)
            PropertyRow("Scope", symbol.scope)
            PropertyRow("Visibility", symbol.visibility)
        }

        node.typeInfo?.let { type ->
            SectionHeader("Type")
            PropertyRow("Resolved", type.resolvedType)
            PropertyRow("Inferred", if (type.isInferred) "Yes" else "No")
        }
    } else if (parserType == ParserType.CORE) {
        SectionHeader("Symbol/Type")
        PropertyRow("Status", "N/A (Core Parser)")
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PropertyRow(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}
