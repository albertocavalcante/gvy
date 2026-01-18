package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovylsp.markdown.dsl.MarkdownBuilder
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

        val hasContentAbove = hasContentAbove(doc)
        val hasDocSections = hasDocSections(doc, includeParams, includeReturn)

        return markdown {
            addDeprecationSection(doc)
            addSummarySection(doc)
            addSeparatorIfNeeded(hasContentAbove, hasDocSections)
            addParametersSection(doc, includeParams)
            addReturnsSection(doc, includeReturn)
            addThrowsSection(doc)
            addSeeAlsoSection(doc)
            addMetadataFooter(doc, hasContentAbove, hasDocSections)
        }
    }

    private fun hasContentAbove(doc: Documentation): Boolean =
        doc.deprecated.isNotBlank() || doc.summary.isNotBlank() || doc.description.isNotBlank()

    private fun hasDocSections(doc: Documentation, includeParams: Boolean, includeReturn: Boolean): Boolean =
        (includeParams && doc.params.isNotEmpty()) ||
            (includeReturn && doc.returnDoc.isNotBlank()) ||
            doc.throws.isNotEmpty() ||
            doc.see.isNotEmpty()

    private fun MarkdownBuilder.addDeprecationSection(doc: Documentation) {
        if (doc.deprecated.isNotBlank()) {
            markdown("> ⚠️ **Deprecated**: ${InlineTagRenderer.render(doc.deprecated)}")
        }
    }

    private fun MarkdownBuilder.addSummarySection(doc: Documentation) {
        if (doc.summary.isNotBlank()) {
            text(InlineTagRenderer.render(doc.summary))
        }

        if (doc.description.isNotBlank() && doc.description != doc.summary) {
            text(InlineTagRenderer.render(doc.description))
        }
    }

    private fun MarkdownBuilder.addSeparatorIfNeeded(hasContentAbove: Boolean, hasDocSections: Boolean) {
        if (hasContentAbove && hasDocSections) {
            markdown("---")
        }
    }

    private fun MarkdownBuilder.addParametersSection(doc: Documentation, includeParams: Boolean) {
        if (!includeParams || doc.params.isEmpty()) return

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

    private fun MarkdownBuilder.addReturnsSection(doc: Documentation, includeReturn: Boolean) {
        if (!includeReturn || doc.returnDoc.isBlank()) return

        markdown("#### Returns")
        text(InlineTagRenderer.render(doc.returnDoc))
    }

    private fun MarkdownBuilder.addThrowsSection(doc: Documentation) {
        if (doc.throws.isEmpty()) return

        markdown("#### Throws")
        list(doc.throws.entries.map { (exception, desc) -> "`$exception` — ${InlineTagRenderer.render(desc)}" })
    }

    private fun MarkdownBuilder.addSeeAlsoSection(doc: Documentation) {
        if (doc.see.isEmpty()) return

        markdown("#### See Also")
        list(doc.see.map { InlineTagRenderer.render(it) })
    }

    private fun MarkdownBuilder.addMetadataFooter(
        doc: Documentation,
        hasContentAbove: Boolean,
        hasDocSections: Boolean,
    ) {
        val hasMetadata = doc.since.isNotBlank() || doc.author.isNotBlank()
        if (!hasMetadata) return

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
