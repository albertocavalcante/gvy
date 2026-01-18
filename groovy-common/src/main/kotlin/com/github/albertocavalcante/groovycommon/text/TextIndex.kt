package com.github.albertocavalcante.groovycommon.text

import kotlin.concurrent.write

/**
 * Text analysis utilities for cursor position calculations.
 *
 * These functions are used across multiple modules (completions, diagnostics, etc.)
 * to work with line/character positions in source code.
 */
object TextIndex {

    private const val MAX_LINE_BREAK_CACHE_SIZE = 1000

    private val lineBreakCacheLock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val lineBreakCache = object : LinkedHashMap<String, IntArray>(
        16, // initial capacity
        0.75f, // load factor
        true, // accessOrder=true for LRU
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?): Boolean =
            size > MAX_LINE_BREAK_CACHE_SIZE
    }

    /**
     * Build an array of line break positions (indices of '\n' characters).
     * Uses caching for better performance on repeated queries.
     */
    private fun getLineBreaks(content: String): IntArray {
        // A 'get' on an access-ordered LinkedHashMap is a write operation, so we need a write lock.
        // We check for the key and return if present, all within a brief write lock.
        lineBreakCacheLock.write {
            lineBreakCache[content]?.let { return it }
        }

        // If not in cache, compute the result outside of any lock to avoid holding
        // the lock during potentially long computations.
        val breaks = mutableListOf<Int>()
        for (i in content.indices) {
            if (content[i] == '\n') {
                breaks.add(i)
            }
        }
        val result = breaks.toIntArray()

        // After computing, acquire the write lock again to put the result into the cache.
        // Use getOrPut to handle the race condition where another thread might have
        // computed and inserted the same key while we were working.
        return lineBreakCacheLock.write {
            lineBreakCache.getOrPut(content) { result }
        }
    }

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
        val lineBreaks = getLineBreaks(content)

        if (lineBreaks.isEmpty()) {
            return 0 to safeOffset
        }

        // Binary search to find which line the offset is on
        val idx = lineBreaks.binarySearch(safeOffset)

        val line: Int
        val lineStart: Int

        if (idx >= 0) {
            // Offset is exactly at a line break
            line = idx + 1
            lineStart = lineBreaks[idx] + 1
        } else {
            // Not at a line break - idx is -(insertion point) - 1
            val insertionPoint = -idx - 1
            line = insertionPoint
            lineStart = if (insertionPoint > 0) lineBreaks[insertionPoint - 1] + 1 else 0
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
