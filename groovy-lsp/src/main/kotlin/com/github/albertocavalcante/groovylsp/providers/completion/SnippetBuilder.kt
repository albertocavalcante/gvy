package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.metadata.MergedParameter

/**
 * Builds LSP snippets for Jenkins parameter completions.
 *
 * Generates type-aware snippet templates that provide intelligent
 * completion behavior based on parameter type and valid values.
 *
 * Snippet format follows LSP specification:
 * - `$1`, `$2`, etc. are tab stops
 * - `$0` is the final cursor position
 * - `${1|a,b,c|}` is a choice placeholder
 *
 * @see <a href="https://microsoft.github.io/language-server-protocol/">LSP Snippet Syntax</a>
 */
object SnippetBuilder {

    /**
     * Build a complete parameter snippet including the key and value template.
     *
     * @param paramName The parameter name (key)
     * @param param The merged parameter metadata
     * @return Snippet string ready for insertion
     */
    fun buildParameterSnippet(paramName: String, param: MergedParameter, prefix: String = ""): String {
        val valueSnippet = buildValueSnippet(param)
        return "${prefix}$paramName: $valueSnippet"
    }

    /**
     * Build just the value portion of a snippet based on parameter type.
     *
     * @param param The merged parameter metadata
     * @return Value snippet template
     */
    private fun buildValueSnippet(param: MergedParameter): String {
        // If validValues are present, use choice placeholder
        val validValues = param.validValues
        if (!validValues.isNullOrEmpty()) {
            val escapedValues = validValues.map { escapeSnippetChars(it) }
            val choice = "${'$'}{1|${escapedValues.joinToString(",")}|}"
            // Conditionally quote based on type - numeric/boolean don't need quotes
            return when (normalizeType(param.type)) {
                "int", "long", "short", "byte", "float", "double", "boolean" -> choice
                else -> "'$choice'"
            }
        }

        // Otherwise, determine by type
        return when (normalizeType(param.type)) {
            "boolean" -> "${'$'}{1|true,false|}"
            "int", "long", "short", "byte", "float", "double" -> "$1"
            "closure" -> "{\n    $0\n}"
            "map", "list" -> "[$1]"
            else -> "'$1'" // Default to quoted string for String and unknown types
        }
    }

    /**
     * Normalize type string for consistent matching.
     * Handles wrapper types (Boolean -> boolean), fully qualified names,
     * and common variations.
     */
    private fun normalizeType(type: String): String {
        val simplified = type
            .substringAfterLast(".") // Remove package prefix
            .lowercase()

        // Handle array types as list
        if (simplified.endsWith("[]")) {
            return "list"
        }

        return when (simplified) {
            "boolean" -> "boolean"
            "integer", "int", "numeric" -> "int"
            "long" -> "long"
            "short" -> "short"
            "byte" -> "byte"
            "float" -> "float"
            "double" -> "double"
            "closure" -> "closure"
            "map", "hashmap", "linkedhashmap" -> "map"
            "list", "arraylist", "linkedlist" -> "list"
            else -> "string"
        }
    }

    /**
     * Escape special characters that have meaning in LSP snippet syntax.
     * - `$` is the tabstop/variable prefix
     * - `|` is the choice delimiter
     * - `,` separates choices
     * - `}` ends a placeholder
     * - `\` is the escape character
     */
    private fun escapeSnippetChars(value: String): String = value
        .replace("\\", "\\\\") // Escape backslash first
        .replace("$", "\\$")
        .replace("|", "\\|")
        .replace(",", "\\,")
        .replace("}", "\\}")
}
