package com.github.albertocavalcante.gvy.gls.documentation

import com.github.albertocavalcante.groovylsp.markdown.dsl.markdown

/**
 * Represents documentation extracted from groovydoc/javadoc comments.
 */
data class Documentation(
    val summary: String = "",
    val description: String = "",
    val params: Map<String, String> = emptyMap(),
    val returnDoc: String = "",
    val throws: Map<String, String> = emptyMap(),
    val since: String = "",
    val author: String = "",
    val deprecated: String = "",
    val see: List<String> = emptyList(),
) {
    /**
     * Check if this documentation has any content.
     */
    fun isEmpty(): Boolean = summary.isBlank() &&
        description.isBlank() &&
        params.isEmpty() &&
        returnDoc.isBlank() &&
        throws.isEmpty() &&
        since.isBlank() &&
        author.isBlank() &&
        deprecated.isBlank() &&
        see.isEmpty()

    /**
     * Check if this documentation has meaningful content.
     */
    fun isNotEmpty(): Boolean = !isEmpty()

    /**
     * Convert to unified GroovyDocumentation format for the pluggable provider system.
     */
    fun toGroovyDocumentation(source: String = "GroovyDoc"): GroovyDocumentation? {
        if (isEmpty()) return null

        val content = markdown {
            if (summary.isNotBlank()) {
                text(InlineTagRenderer.render(summary))
            }

            if (description.isNotBlank() && description != summary) {
                text(InlineTagRenderer.render(description))
            }

            if (params.isNotEmpty()) {
                text("**Parameters:**")
                list(params.map { (name, desc) -> "`$name`: ${InlineTagRenderer.render(desc)}" })
            }

            if (returnDoc.isNotBlank()) {
                text("**Returns:** ${InlineTagRenderer.render(returnDoc)}")
            }

            if (throws.isNotEmpty()) {
                text("**Throws:**")
                list(throws.map { (type, desc) -> "`$type`: ${InlineTagRenderer.render(desc)}" })
            }

            if (deprecated.isNotBlank()) {
                text("**@deprecated** ${InlineTagRenderer.render(deprecated)}")
            }

            if (since.isNotBlank()) {
                text("**@since** ${InlineTagRenderer.render(since)}")
            }

            if (author.isNotBlank()) {
                text("**@author** ${InlineTagRenderer.render(author)}")
            }
        }

        return GroovyDocumentation.markdown(content, source)
    }

    companion object {
        val EMPTY = Documentation()
    }
}
