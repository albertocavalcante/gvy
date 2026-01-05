package com.github.albertocavalcante.groovyparser.utils

/**
 * Utility functions for escaping and unescaping strings in Groovy source code.
 *
 * Similar to JavaParser's StringEscapeUtils.
 */
object StringEscapeUtils {

    private const val MIN_PRINTABLE_ASCII = 32
    private const val MAX_PRINTABLE_ASCII = 127
    private const val HEX_RADIX = 16
    private const val UNICODE_HEX_LENGTH = 4
    private const val UNICODE_ESCAPE_LENGTH = 6 // \uXXXX = 6 chars
    private const val UNICODE_START_INDEX = 2 // Start of hex digits in \uXXXX

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
                    if (char.code < MIN_PRINTABLE_ASCII || char.code > MAX_PRINTABLE_ASCII) {
                        append("\\u${char.code.toString(HEX_RADIX).padStart(UNICODE_HEX_LENGTH, '0')}")
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
                    if (char.code < MIN_PRINTABLE_ASCII || char.code > MAX_PRINTABLE_ASCII) {
                        append("\\u${char.code.toString(HEX_RADIX).padStart(UNICODE_HEX_LENGTH, '0')}")
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
                i = unescapeSequence(input, i, this)
            } else {
                append(input[i])
                i++
            }
        }
    }

    private fun unescapeSequence(input: String, i: Int, builder: StringBuilder): Int =
        when (val nextChar = input[i + 1]) {
            'n' -> appendAndAdvance(builder, '\n', i, 2)
            'r' -> appendAndAdvance(builder, '\r', i, 2)
            't' -> appendAndAdvance(builder, '\t', i, 2)
            'b' -> appendAndAdvance(builder, '\b', i, 2)
            'f' -> appendAndAdvance(builder, '\u000C', i, 2)
            '\\', '\'', '"', '$' -> appendAndAdvance(builder, nextChar, i, 2)
            'u' -> unescapeUnicode(input, i, builder)
            else -> appendAndAdvance(builder, input[i], i, 1) // Not an escape, just return current char
        }

    private fun appendAndAdvance(builder: StringBuilder, char: Char, currentIndex: Int, advance: Int): Int {
        builder.append(char)
        return currentIndex + advance
    }

    private fun unescapeUnicode(input: String, i: Int, builder: StringBuilder): Int {
        // Unicode escape: \uXXXX
        if (i + UNICODE_ESCAPE_LENGTH <= input.length) {
            val hex = input.substring(i + UNICODE_START_INDEX, i + UNICODE_ESCAPE_LENGTH)
            return try {
                builder.append(hex.toInt(HEX_RADIX).toChar())
                i + UNICODE_ESCAPE_LENGTH
            } catch (e: NumberFormatException) {
                builder.append(input[i])
                i + 1
            }
        } else {
            builder.append(input[i])
            return i + 1
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
