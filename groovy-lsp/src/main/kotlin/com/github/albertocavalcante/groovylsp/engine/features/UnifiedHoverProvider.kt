package com.github.albertocavalcante.groovylsp.engine.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.api.HoverProvider
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind

/**
 * A generic HoverProvider that works with any parser adapter via the ParseUnit interface.
 *
 * This provider relies solely on the [ParseUnit.nodeAt] functionality to retrieve
 * node information and format it for display.
 */
class UnifiedHoverProvider(private val parseUnit: ParseUnit) : HoverProvider {

    override suspend fun getHover(params: HoverParams): Hover {
        val node = parseUnit.nodeAt(params.position)
            ?: return Hover(MarkupContent(MarkupKind.MARKDOWN, ""), null)

        val content = buildString {
            // Add signature/type in a Groovy code block
            append("```groovy\n")
            if (node.type != null) {
                append(node.type).append(" ")
            }
            append(node.name ?: "")
            append("\n```")

            // Add documentation if available
            if (!node.documentation.isNullOrBlank()) {
                append("\n\n")
                append(node.documentation)
            }

            // Add kind info as debug/extra info (optional, can be refined)
            append("\n\n*(${node.kind})*")
        }

        return Hover(MarkupContent(MarkupKind.MARKDOWN, content), node.range)
    }
}
