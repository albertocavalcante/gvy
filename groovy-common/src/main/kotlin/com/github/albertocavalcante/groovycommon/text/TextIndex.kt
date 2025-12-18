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
        if (line < 0) return 0

        // Find start of target line without allocating a list of all lines
        var lineStartOffset = 0
        repeat(line) {
            lineStartOffset = content.indexOf('\n', lineStartOffset) + 1
            if (lineStartOffset == 0) return content.length // indexOf returned -1, line beyond end
        }

        if (lineStartOffset > content.length) return content.length

        // Find end of target line to determine line length
        val lineEndOffset = content.indexOf('\n', lineStartOffset).let { if (it < 0) content.length else it }
        val lineLength = lineEndOffset - lineStartOffset
        val safeChar = character.coerceIn(0, lineLength)
        return (lineStartOffset + safeChar).coerceIn(0, content.length)
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
        if (line < 0) return ""

        // Find start of target line without allocating a list of all lines
        var lineStart = 0
        repeat(line) {
            lineStart = content.indexOf('\n', lineStart) + 1
            if (lineStart == 0) return "" // indexOf was -1, line beyond end
        }

        if (lineStart > content.length) return ""

        val lineEnd = content.indexOf('\n', lineStart)
        return if (lineEnd < 0) content.substring(lineStart) else content.substring(lineStart, lineEnd)
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
