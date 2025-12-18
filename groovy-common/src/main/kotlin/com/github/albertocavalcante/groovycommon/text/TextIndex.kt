package com.github.albertocavalcante.groovycommon.text

/**
 * Text analysis utilities for cursor position calculations.
 *
 * These functions are used across multiple modules (completions, diagnostics, etc.)
 * to work with line/character positions in source code.
 */
object TextIndex {

    /**
     * Convert (line, character) position to absolute byte offset.
     *
     * @param content The full source text
     * @param line 0-indexed line number
     * @param character 0-indexed character position within the line
     * @return Absolute offset into content, clamped to valid range
     */
    fun offsetAt(content: String, line: Int, character: Int): Int {
        val lines = content.split('\n')
        if (line < 0) return 0
        if (line >= lines.size) return content.length

        // Sum lengths of all lines before target line (including newlines)
        var offset = 0
        for (i in 0 until line) {
            offset += lines[i].length + 1 // +1 for '\n'
        }

        // Add character offset within target line, clamped to line length
        val safeChar = character.coerceIn(0, lines[line].length)
        return (offset + safeChar).coerceIn(0, content.length)
    }

    /**
     * Convert absolute offset to (line, character) position.
     *
     * @param content The full source text
     * @param offset Absolute offset into content
     * @return Pair of (line, character), both 0-indexed
     */
    fun positionAt(content: String, offset: Int): Pair<Int, Int> {
        val safeOffset = offset.coerceIn(0, content.length)
        var line = 0
        var lineStart = 0

        for (i in 0 until safeOffset) {
            if (content[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }

        return line to (safeOffset - lineStart)
    }

    /**
     * Count non-overlapping occurrences of needle in haystack.
     *
     * @param haystack The string to search in
     * @param needle The substring to find
     * @return Number of non-overlapping occurrences
     */
    fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty() || haystack.length < needle.length) return 0

        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) return count
            count++
            idx = found + needle.length
        }
    }

    /**
     * Get the text of a specific line (0-indexed).
     *
     * @param content The full source text
     * @param line 0-indexed line number
     * @return The line text, or empty string if line is out of bounds
     */
    fun lineAt(content: String, line: Int): String {
        val lines = content.split('\n')
        return if (line in lines.indices) lines[line] else ""
    }

    /**
     * Check if the cursor is at the start of a line (only whitespace before).
     *
     * @param content The full source text
     * @param line 0-indexed line number
     * @param character 0-indexed character position
     * @return true if only whitespace precedes the cursor on this line
     */
    fun isAtLineStart(content: String, line: Int, character: Int): Boolean {
        val lineText = lineAt(content, line)
        if (lineText.isEmpty()) return true

        val safeChar = character.coerceIn(0, lineText.length)
        val prefix = lineText.substring(0, safeChar)
        return prefix.all { it == ' ' || it == '\t' }
    }
}
