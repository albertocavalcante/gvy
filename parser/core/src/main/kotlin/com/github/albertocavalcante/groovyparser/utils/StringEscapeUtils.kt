package com.github.albertocavalcante.groovyparser.utils

/**
 * Utility functions for escaping and unescaping strings in Groovy source code.
 *
 * Similar to JavaParser's StringEscapeUtils.
 */
object StringEscapeUtils {

    /**
     * Escapes a string for use in Groovy source code (single-quoted string).
     */
    fun escapeGroovy(input: String): String = buildString {
        for (char in input) {
            when (char) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f") // form feed
                else -> {
                    if (char.code < 32 || char.code > 127) {
                        append("\\u${char.code.toString(16).padStart(4, '0')}")
                    } else {
                        append(char)
                    }
                }
            }
        }
    }

    /**
     * Escapes a string for use in a double-quoted Groovy string.
     * Also escapes $ to prevent GString interpolation.
     */
    fun escapeGString(input: String): String = buildString {
        for (char in input) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> {
                    if (char.code < 32 || char.code > 127) {
                        append("\\u${char.code.toString(16).padStart(4, '0')}")
                    } else {
                        append(char)
                    }
                }
            }
        }
    }

    /**
     * Unescapes a Groovy string (handles common escape sequences).
     */
    fun unescapeGroovy(input: String): String = buildString {
        var i = 0
        while (i < input.length) {
            if (input[i] == '\\' && i + 1 < input.length) {
                when (input[i + 1]) {
                    'n' -> {
                        append('\n')
                        i += 2
                    }
                    'r' -> {
                        append('\r')
                        i += 2
                    }
                    't' -> {
                        append('\t')
                        i += 2
                    }
                    'b' -> {
                        append('\b')
                        i += 2
                    }
                    'f' -> {
                        append('\u000C')
                        i += 2
                    }
                    '\\' -> {
                        append('\\')
                        i += 2
                    }
                    '\'' -> {
                        append('\'')
                        i += 2
                    }
                    '"' -> {
                        append('"')
                        i += 2
                    }
                    '$' -> {
                        append('$')
                        i += 2
                    }
                    'u' -> {
                        // Unicode escape: \uXXXX
                        if (i + 5 < input.length) {
                            val hex = input.substring(i + 2, i + 6)
                            try {
                                append(hex.toInt(16).toChar())
                                i += 6
                            } catch (e: NumberFormatException) {
                                append(input[i])
                                i++
                            }
                        } else {
                            append(input[i])
                            i++
                        }
                    }
                    else -> {
                        append(input[i])
                        i++
                    }
                }
            } else {
                append(input[i])
                i++
            }
        }
    }

    /**
     * Escapes a string for use in a regex pattern.
     */
    fun escapeRegex(input: String): String = input.replace(Regex("[\\\\\\[\\](){}.*+?^$|]")) { "\\${it.value}" }

    /**
     * Converts a string to a valid Groovy identifier by replacing invalid characters.
     */
    fun toIdentifier(input: String): String = buildString {
        for ((index, char) in input.withIndex()) {
            when {
                index == 0 && char.isJavaIdentifierStart() -> append(char)
                index == 0 && char.isDigit() -> append('_').append(char)
                index == 0 -> append('_')
                char.isJavaIdentifierPart() -> append(char)
                else -> append('_')
            }
        }
    }.ifEmpty { "_" }

    /**
     * Checks if a string is a valid Groovy identifier.
     */
    fun isValidIdentifier(input: String): Boolean {
        if (input.isEmpty()) return false
        if (!input[0].isJavaIdentifierStart()) return false
        return input.drop(1).all { it.isJavaIdentifierPart() }
    }

    /**
     * Returns the appropriate quote style for a string value.
     * Uses single quotes unless the string contains single quotes.
     */
    fun suggestQuoteStyle(value: String): QuoteStyle = when {
        value.contains('\n') || value.contains('\r') -> QuoteStyle.TRIPLE_DOUBLE
        value.contains('\'') && !value.contains('"') -> QuoteStyle.DOUBLE
        value.contains('$') -> QuoteStyle.SINGLE // Avoid GString interpolation
        else -> QuoteStyle.SINGLE
    }

    /**
     * Quote styles for Groovy strings.
     */
    enum class QuoteStyle(val open: String, val close: String) {
        SINGLE("'", "'"),
        DOUBLE("\"", "\""),
        TRIPLE_SINGLE("'''", "'''"),
        TRIPLE_DOUBLE("\"\"\"", "\"\"\""),
        SLASHY("/", "/"),
        DOLLAR_SLASHY("$/", "/$"),
    }
}
