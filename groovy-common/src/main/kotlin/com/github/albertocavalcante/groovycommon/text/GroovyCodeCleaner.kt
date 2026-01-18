package com.github.albertocavalcante.groovycommon.text

/**
 * Utilities for cleaning Groovy code by removing comments and string literals.
 *
 * These functions are useful for heuristic code analysis where you need to avoid false positives
 * from patterns that might appear in comments or strings.
 */
object GroovyCodeCleaner {

    /**
     * Removes single-line comments from a line of code.
     *
     * This is a simple heuristic that handles the most common case: `//` comments.
     * It does not handle strings or multi-line comments.
     *
     * @param line The line to strip comments from
     * @return The line with single-line comments removed
     */
    fun stripSingleLineComments(line: String): String {
        val commentIndex = line.indexOf("//")
        return if (commentIndex != -1) line.substring(0, commentIndex) else line
    }

    /**
     * Removes comments and string literals from Groovy code to avoid false positive detections.
     *
     * This is a heuristic approach that handles common cases but may not be perfect for all edge cases.
     * It handles:
     * - Single-line comments (`//`)
     * - Multi-line comments (`/* */`)
     * - Triple-quoted strings (`'''` and `"""`)
     * - Single-quoted strings (`'...'`)
     * - Double-quoted strings (GStrings) (`"..."`)
     * - Escape sequences in strings
     *
     * NOTE: Heuristic / tradeoff:
     * This function is intentionally complex as it needs to handle multiple Groovy string and comment formats.
     * A proper solution would require a full lexer/parser, but that would defeat the purpose of a lightweight
     * heuristic check. Edge cases like nested strings or complex escape sequences may not be handled perfectly,
     * but this is acceptable for a best-effort detection mechanism that's supplemented by AST-based checks.
     *
     * @param code The code to clean
     * @return The code with comments and string literals removed
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "MagicNumber")
    fun removeCommentsAndStrings(code: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < code.length) {
            when {
                // Single-line comment
                code.startsWith("//", i) -> {
                    i = code.indexOf('\n', i).let { if (it == -1) code.length else it + 1 }
                }
                // Multi-line comment
                code.startsWith("/*", i) -> {
                    i = code.indexOf("*/", i + 2).let { if (it == -1) code.length else it + 2 }
                }
                // Triple-quoted string (GString or regular)
                code.startsWith("'''", i) || code.startsWith("\"\"\"", i) -> {
                    val delimiter = code.substring(i, i + 3)
                    i = code.indexOf(delimiter, i + 3).let { if (it == -1) code.length else it + 3 }
                }
                // Single-quoted string
                code[i] == '\'' -> {
                    i++
                    while (i < code.length) {
                        if (code[i] == '\\') {
                            i += 2 // Skip escaped character
                        } else if (code[i] == '\'') {
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                }
                // Double-quoted string (GString)
                code[i] == '"' -> {
                    i++
                    while (i < code.length) {
                        if (code[i] == '\\') {
                            i += 2 // Skip escaped character
                        } else if (code[i] == '"') {
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                }
                // Regular code
                else -> {
                    result.append(code[i])
                    i++
                }
            }
        }
        return result.toString()
    }
}
