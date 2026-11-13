// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.annotations

import com.github.albertocavalcante.gvy.source.Utf16LineIndex
import com.github.albertocavalcante.nativeapi.ParserPosition
import com.github.albertocavalcante.nativeapi.ParserRange
import org.codehaus.groovy.ast.ASTNode

/** Groovy columns count code points; editor positions count UTF-16 code units. */
class CompilerSourceRanges(private val source: String) {
    // Check both dimensions before either index can allocate per-line storage.
    internal val available: Boolean = source.length <= MAX_SOURCE_UTF16_UNITS && hasBoundedLineCount(source)
    private val lines = if (available) Utf16LineIndex(source) else null
    private val groovyLineStarts = if (available) {
        buildList {
            add(0)
            // The Groovy lexer (ANTLR) advances its line counter on LF only; a bare CR stays inside the line.
            source.forEachIndexed { offset, char -> if (char == '\n') add(offset + 1) }
        }.toIntArray()
    } else {
        IntArray(0)
    }
    private val codePointOffsets = mutableMapOf<Int, IntArray>()
    private var cachedOffsets = 0
    internal var exhausted: Boolean = false
        private set

    fun range(node: ASTNode): ParserRange? {
        if (!available || exhausted) return null
        val start = position(node.lineNumber, node.columnNumber) ?: return null
        val end = position(node.lastLineNumber, node.lastColumnNumber) ?: return null
        if (end.line < start.line || (end.line == start.line && end.character < start.character)) return null
        return ParserRange(start, end)
    }

    fun text(range: ParserRange): String = source.substring(
        checkNotNull(lines).offsetOf(range.start.line, range.start.character),
        checkNotNull(lines).offsetOf(range.end.line, range.end.character),
    )

    fun offset(position: ParserPosition): Int {
        val index = checkNotNull(lines)
        require(index.isValidPosition(position.line, position.character))
        return index.offsetOf(position.line, position.character)
    }

    fun positionAt(offset: Int): ParserPosition {
        require(offset in 0..source.length)
        val index = checkNotNull(lines)
        val line = index.lineAt(offset)
        return ParserPosition(line, offset - index.lineStart(line))
    }

    internal companion object {
        const val MAX_SOURCE_UTF16_UNITS = 1024 * 1024
        const val MAX_SOURCE_LINES = 16_384
        const val MAX_CACHED_OFFSETS = MAX_SOURCE_UTF16_UNITS + 1

        private fun hasBoundedLineCount(source: String): Boolean {
            var lineCount = 1
            var offset = 0
            while (offset < source.length) {
                val char = source[offset]
                if (char == '\r' || char == '\n') {
                    lineCount++
                    if (lineCount > MAX_SOURCE_LINES) return false
                    if (char == '\r' && offset + 1 < source.length && source[offset + 1] == '\n') offset++
                }
                offset++
            }
            return true
        }
    }

    private fun position(line: Int, column: Int): ParserPosition? {
        if (line <= 0 || column <= 0) return null
        val start = groovyLineStarts.getOrNull(line - 1) ?: return null
        var end = groovyLineStarts.getOrNull(line) ?: source.length
        while (end > start && (source[end - 1] == '\r' || source[end - 1] == '\n')) end--
        val length = end - start
        val cached = codePointOffsets[line - 1]
        if (cached == null && length + 1 > MAX_CACHED_OFFSETS - cachedOffsets) {
            exhausted = true
            return null
        }
        val offsets = cached ?: run {
            val result = IntArray(length + 1)
            var offset = 0
            var count = 1
            while (offset < length) {
                offset += Character.charCount(source.codePointAt(start + offset))
                result[count++] = offset
            }
            val trimmed = result.copyOf(count)
            cachedOffsets += trimmed.size
            codePointOffsets[line - 1] = trimmed
            trimmed
        }
        return offsets.getOrNull(column - 1)?.let { positionAt(start + it) }
    }
}
