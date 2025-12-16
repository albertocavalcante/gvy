package com.github.albertocavalcante.groovylsp.documentation

/**
 * Result of documentation generation.
 *
 * Supports multiple content formats and source tracking.
 */
data class GroovyDocumentation(
    /**
     * The documentation content (markdown or plaintext).
     */
    val content: String,

    /**
     * Content format.
     */
    val format: Format = Format.MARKDOWN,

    /**
     * Source of the documentation (e.g., "Jenkins Plugin: kubernetes", "Groovy AST").
     */
    val source: String? = null,

    /**
     * URL for external documentation if available.
     */
    val externalUrl: String? = null,
) {
    enum class Format {
        MARKDOWN,
        PLAINTEXT,
        HTML,
    }

    /**
     * Combine multiple documentation results.
     */
    operator fun plus(other: GroovyDocumentation): GroovyDocumentation = GroovyDocumentation(
        content = "$content\n\n---\n\n${other.content}",
        format = this.format,
        source = listOfNotNull(source, other.source).joinToString(", "),
        externalUrl = externalUrl ?: other.externalUrl,
    )

    companion object {
        fun markdown(content: String, source: String? = null) = GroovyDocumentation(content, Format.MARKDOWN, source)

        fun plaintext(content: String, source: String? = null) = GroovyDocumentation(content, Format.PLAINTEXT, source)
    }
}
