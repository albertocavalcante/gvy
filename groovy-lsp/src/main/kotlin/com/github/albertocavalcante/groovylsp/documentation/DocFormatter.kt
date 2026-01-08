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
            // Phase 3: Deprecation warning at top with visual prominence
            if (doc.deprecated.isNotBlank()) {
                markdown("> ⚠️ **Deprecated**: ${InlineTagRenderer.render(doc.deprecated)}")
            }

            // Add summary and description
            if (doc.summary.isNotBlank()) {
                text(InlineTagRenderer.render(doc.summary))
            }

            if (doc.description.isNotBlank() && doc.description != doc.summary) {
                text(InlineTagRenderer.render(doc.description))
            }

            // Phase 3: Add visual separator before documentation sections if we have content above
            val hasContentAbove =
                doc.deprecated.isNotBlank() || doc.summary.isNotBlank() || doc.description.isNotBlank()
            val hasDocSections = (includeParams && doc.params.isNotEmpty()) ||
                (includeReturn && doc.returnDoc.isNotBlank()) ||
                doc.throws.isNotEmpty() ||
                doc.see.isNotEmpty()

            if (hasContentAbove && hasDocSections) {
                markdown("---")
            }

            // Phase 3: Parameters with section header and smart formatting
            if (includeParams && doc.params.isNotEmpty()) {
                markdown("#### Parameters")

                // Use table format for multiple parameters, inline for single parameter
                if (doc.params.size > 1) {
                    // Table format for multiple parameters
                    val rows = doc.params.entries.map { (name, desc) ->
                        listOf("`$name`", "", InlineTagRenderer.render(desc))
                    }
                    table(listOf("Name", "Type", "Description"), rows)
                } else {
                    // Inline format for single parameter
                    val (name, desc) = doc.params.entries.first()
                    text("**$name** — ${InlineTagRenderer.render(desc)}")
                }
            }

            // Phase 3: Return documentation with section header
            if (includeReturn && doc.returnDoc.isNotBlank()) {
                markdown("#### Returns")
                text(InlineTagRenderer.render(doc.returnDoc))
            }

            // Phase 3: Throws/exceptions with section header
            if (doc.throws.isNotEmpty()) {
                markdown("#### Throws")
                list(doc.throws.entries.map { (exception, desc) -> "`$exception` — ${InlineTagRenderer.render(desc)}" })
            }

            // Phase 3: See references with section header
            if (doc.see.isNotEmpty()) {
                markdown("#### See Also")
                list(doc.see.map { InlineTagRenderer.render(it) })
            }

            // Phase 3: Metadata footer with visual separator
            val hasMetadata = doc.since.isNotBlank() || doc.author.isNotBlank()
            if (hasMetadata) {
                // Only add separator if there's content above the metadata footer
                val hasContentAboveFooter = hasContentAbove || hasDocSections
                if (hasContentAboveFooter) {
                    markdown("---")
                }

                val metadataParts = mutableListOf<String>()
                if (doc.since.isNotBlank()) {
                    metadataParts.add("*@since ${InlineTagRenderer.render(doc.since)}*")
                }
                if (doc.author.isNotBlank()) {
                    metadataParts.add("*@author ${InlineTagRenderer.render(doc.author)}*")
                }

                text(metadataParts.joinToString(" · "))
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
