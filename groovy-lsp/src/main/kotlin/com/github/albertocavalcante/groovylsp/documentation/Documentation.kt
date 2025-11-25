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

    companion object {
        val EMPTY = Documentation()
    }
}
