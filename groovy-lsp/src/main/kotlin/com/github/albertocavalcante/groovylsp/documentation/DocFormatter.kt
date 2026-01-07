package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovylsp.markdown.dsl.markdown

/**
 * Formats documentation into markdown suitable for hover display.
 */
object DocFormatter {

    /**
     * Format documentation as markdown for display in hover.
     *
     * @param doc The documentation to format
     * @param includeParams Whether to include parameter documentation (default: true)
     * @param includeReturn Whether to include return documentation (default: true)
     * @return Markdown-formatted documentation
     */
    fun formatAsMarkdown(doc: Documentation, includeParams: Boolean = true, includeReturn: Boolean = true): String {
        if (doc.isEmpty()) {
            return ""
        }

        return markdown {
            // Add summary and description
            if (doc.summary.isNotBlank()) {
                text(InlineTagRenderer.render(doc.summary))
            }

            if (doc.description.isNotBlank() && doc.description != doc.summary) {
                text(InlineTagRenderer.render(doc.description))
            }

            // Add deprecated notice
            if (doc.deprecated.isNotBlank()) {
                text("**Deprecated**: ${InlineTagRenderer.render(doc.deprecated)}")
            }

            // Add parameters
            if (includeParams && doc.params.isNotEmpty()) {
                text("**Parameters:**")
                list(doc.params.entries.map { (name, desc) -> "`$name`: ${InlineTagRenderer.render(desc)}" })
            }

            // Add return documentation
            if (includeReturn && doc.returnDoc.isNotBlank()) {
                text("**Returns:** ${InlineTagRenderer.render(doc.returnDoc)}")
            }

            // Add throws/exceptions
            if (doc.throws.isNotEmpty()) {
                text("**Throws:**")
                list(doc.throws.entries.map { (exception, desc) -> "`$exception`: ${InlineTagRenderer.render(desc)}" })
            }

            // Add since
            if (doc.since.isNotBlank()) {
                text("**Since:** ${InlineTagRenderer.render(doc.since)}")
            }

            // Add see references
            if (doc.see.isNotEmpty()) {
                text("**See:**")
                list(doc.see.map { InlineTagRenderer.render(it) })
            }
        }
    }

    /**
     * Get a concise summary suitable for signature help.
     *
     * @param doc The documentation
     * @return Brief summary text
     */
    fun formatSummary(doc: Documentation): String = when {
        doc.summary.isNotBlank() -> InlineTagRenderer.render(doc.summary)
        doc.description.isNotBlank() -> {
            // Take first sentence of description
            val firstSentence = doc.description.split(Regex("""[.?!]\s+""")).firstOrNull()?.trim() ?: doc.description
            InlineTagRenderer.render(firstSentence)
        }

        else -> ""
    }

    /**
     * Get parameter documentation if available.
     *
     * @param doc The documentation
     * @param paramName The parameter name
     * @return Parameter documentation or empty string
     */
    fun getParamDoc(doc: Documentation, paramName: String): String {
        val paramDoc = doc.params[paramName] ?: ""
        return if (paramDoc.isNotBlank()) InlineTagRenderer.render(paramDoc) else ""
    }
}
