package com.github.albertocavalcante.groovylsp.providers.formatting

import org.eclipse.lsp4j.FormattingOptions

/**
 * A basic formatter that handles simple indentation based on brace counting.
 * This is a starting point that can be enhanced with AST-based formatting later.
 */
class BasicIndentationFormatter {

    /**
     * Formats the given content using basic brace-counting indentation.
     *
     * @param content The source code to format
     * @param options LSP formatting options (tab size, use spaces)
     * @return The formatted content
     */
    fun format(content: String, options: FormattingOptions): String {
        val lines = content.lines()
        val formatted = mutableListOf<String>()
        var indentLevel = 0
        val tabSize = options.tabSize
        val useSpaces = options.isInsertSpaces

        for (line in lines) {
            val trimmed = line.trim()

            // Skip empty lines but preserve them
            if (trimmed.isEmpty()) {
                formatted.add("")
                continue
            }

            // Decrease indent for closing braces at start of line
            if (isClosingBrace(trimmed)) {
                indentLevel = maxOf(0, indentLevel - 1)
            }

            // Format the line with proper indent
            val indentedLine = getIndent(indentLevel, tabSize, useSpaces) + trimmed
            formatted.add(indentedLine)

            // Increase indent for opening braces at end of line
            if (isOpeningBrace(trimmed)) {
                indentLevel++
            }

            // Handle case where line has both opening and closing braces
            // This handles cases like "} else {" or "}) {"
            val netBraceChange = countNetBraceChange(trimmed)
            if (netBraceChange != 0 && !isSimpleOpeningBrace(trimmed) && !isSimpleClosingBrace(trimmed)) {
                indentLevel = maxOf(0, indentLevel + netBraceChange)
            }
        }

        return formatted.joinToString("\n")
    }

    /**
     * Removes trailing whitespace from all lines.
     */
    fun removeTrailingWhitespace(content: String): String {
        return content.lines().joinToString("\n") { it.trimEnd() }
    }

    /**
     * Ensures the content ends with a single newline.
     */
    fun ensureTrailingNewline(content: String): String {
        val trimmed = content.trimEnd('\n', '\r')
        return "$trimmed\n"
    }

    private fun isOpeningBrace(line: String): Boolean {
        return line.endsWith('{') || line.endsWith('[') ||
               (line.endsWith('(') && !isMethodCall(line))
    }

    private fun isClosingBrace(line: String): Boolean {
        return line.startsWith('}') || line.startsWith(']') || line.startsWith(')')
    }

    private fun isSimpleOpeningBrace(line: String): Boolean {
        return line.endsWith('{') && !line.contains('}')
    }

    private fun isSimpleClosingBrace(line: String): Boolean {
        return line.startsWith('}') && !line.contains('{')
    }

    /**
     * Counts the net change in brace depth for a line.
     * Positive means more opening braces, negative means more closing braces.
     */
    private fun countNetBraceChange(line: String): Int {
        var depth = 0
        var inString = false
        var stringChar = '\u0000'
        var escapeNext = false

        for (char in line) {
            when {
                escapeNext -> {
                    escapeNext = false
                }
                char == '\\' && inString -> {
                    escapeNext = true
                }
                char == '"' || char == '\'' -> {
                    if (!inString) {
                        inString = true
                        stringChar = char
                    } else if (char == stringChar) {
                        inString = false
                        stringChar = '\u0000'
                    }
                }
                !inString -> {
                    when (char) {
                        '{', '[' -> depth++
                        '}', ']' -> depth--
                        '(' -> if (!isInMethodCall(line, char)) depth++
                        ')' -> if (!isInMethodCall(line, char)) depth--
                    }
                }
            }
        }
        return depth
    }

    /**
     * Simple heuristic to avoid treating method call parentheses as block braces.
     */
    private fun isMethodCall(line: String): Boolean {
        val trimmed = line.trim()
        // Simple heuristic: if line contains = or other assignment-like patterns,
        // and ends with (, it's likely a method call
        return trimmed.contains("=") || trimmed.contains("return ") ||
               trimmed.matches(Regex(".*\\w+\\s*\\(.*"))
    }

    /**
     * Checks if a parenthesis is part of a method call context.
     */
    private fun isInMethodCall(line: String, char: Char): Boolean {
        // For now, treat all parentheses as method calls to avoid over-indenting
        return char == '(' || char == ')'
    }

    private fun getIndent(level: Int, tabSize: Int, useSpaces: Boolean): String {
        val singleIndent = if (useSpaces) " ".repeat(tabSize) else "\t"
        return singleIndent.repeat(level)
    }
}