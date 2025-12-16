package com.github.albertocavalcante.groovylsp.documentation

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

        val content = buildString {
            if (summary.isNotBlank()) {
                appendLine(summary)
                appendLine()
            }

            if (description.isNotBlank() && description != summary) {
                appendLine(description)
                appendLine()
            }

            if (params.isNotEmpty()) {
                appendLine("**Parameters:**")
                params.forEach { (name, desc) ->
                    appendLine("- `$name`: $desc")
                }
                appendLine()
            }

            if (returnDoc.isNotBlank()) {
                appendLine("**Returns:** $returnDoc")
                appendLine()
            }

            if (throws.isNotEmpty()) {
                appendLine("**Throws:**")
                throws.forEach { (type, desc) ->
                    appendLine("- `$type`: $desc")
                }
                appendLine()
            }

            if (deprecated.isNotBlank()) {
                appendLine("**@deprecated** $deprecated")
            }

            if (since.isNotBlank()) {
                appendLine("**@since** $since")
            }

            if (author.isNotBlank()) {
                appendLine("**@author** $author")
            }
        }.trim()

        return GroovyDocumentation.markdown(content, source)
    }

    companion object {
        val EMPTY = Documentation()
    }
}
