package com.github.albertocavalcante.groovylsp.documentation

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

        val parts = mutableListOf<String>()

        // Add summary and description
        if (doc.summary.isNotBlank()) {
            parts.add(doc.summary)
        }

        if (doc.description.isNotBlank() && doc.description != doc.summary) {
            parts.add(doc.description)
        }

        // Add deprecated notice
        if (doc.deprecated.isNotBlank()) {
            parts.add("**Deprecated**: ${doc.deprecated}")
        }

        // Add parameters
        if (includeParams && doc.params.isNotEmpty()) {
            val paramDocs = doc.params.entries.joinToString("\n") { (name, desc) ->
                "- `$name`: $desc"
            }
            parts.add("**Parameters:**\n$paramDocs")
        }

        // Add return documentation
        if (includeReturn && doc.returnDoc.isNotBlank()) {
            parts.add("**Returns:** ${doc.returnDoc}")
        }

        // Add throws/exceptions
        if (doc.throws.isNotEmpty()) {
            val throwsDocs = doc.throws.entries.joinToString("\n") { (exception, desc) ->
                "- `$exception`: $desc"
            }
            parts.add("**Throws:**\n$throwsDocs")
        }

        // Add since
        if (doc.since.isNotBlank()) {
            parts.add("**Since:** ${doc.since}")
        }

        // Add see references
        if (doc.see.isNotEmpty()) {
            val seeDocs = doc.see.joinToString("\n") { "- $it" }
            parts.add("**See:**\n$seeDocs")
        }

        return parts.joinToString("\n\n")
    }

    /**
     * Get a concise summary suitable for signature help.
     *
     * @param doc The documentation
     * @return Brief summary text
     */
    fun formatSummary(doc: Documentation): String = when {
        doc.summary.isNotBlank() -> doc.summary
        doc.description.isNotBlank() -> {
            // Take first sentence of description
            doc.description.split(Regex("""[.?!]\s+""")).firstOrNull()?.trim() ?: doc.description
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
    fun getParamDoc(doc: Documentation, paramName: String): String = doc.params[paramName] ?: ""
}
