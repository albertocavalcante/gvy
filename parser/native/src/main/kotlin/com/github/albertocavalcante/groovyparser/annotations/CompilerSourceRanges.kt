// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.annotations

import com.github.albertocavalcante.gvy.source.Utf16LineIndex
import com.github.albertocavalcante.nativeapi.ParserPosition
import com.github.albertocavalcante.nativeapi.ParserRange
import org.codehaus.groovy.ast.ASTNode

/** Groovy columns count code points; editor positions count UTF-16 code units. */
class CompilerSourceRanges(private val source: String) {
    private val lines = Utf16LineIndex(source)
    private val groovyLineStarts = buildList {
        add(0)
        source.forEachIndexed { offset, char -> if (char == '\n') add(offset + 1) }
    }.toIntArray()
    private val codePointOffsets = mutableMapOf<Int, IntArray>()

    fun range(node: ASTNode): ParserRange? {
        val start = position(node.lineNumber, node.columnNumber) ?: return null
        val end = position(node.lastLineNumber, node.lastColumnNumber) ?: return null
        if (end.line < start.line || (end.line == start.line && end.character < start.character)) return null
        return ParserRange(start, end)
    }

    fun text(range: ParserRange): String = source.substring(
        lines.offsetOf(range.start.line, range.start.character),
        lines.offsetOf(range.end.line, range.end.character),
    )

    fun offset(position: ParserPosition): Int {
        require(lines.isValidPosition(position.line, position.character))
        return lines.offsetOf(position.line, position.character)
    }

    fun positionAt(offset: Int): ParserPosition {
        require(offset in 0..source.length)
        val line = lines.lineAt(offset)
        return ParserPosition(line, offset - lines.lineStart(line))
    }

    private fun position(line: Int, column: Int): ParserPosition? {
        if (line <= 0 || column <= 0) return null
        val start = groovyLineStarts.getOrNull(line - 1) ?: return null
        val length = (groovyLineStarts.getOrNull(line)?.minus(1) ?: source.length) - start
        val offsets = codePointOffsets.getOrPut(line - 1) {
            val result = ArrayList<Int>()
            var offset = 0
            result.add(offset)
            while (offset < length) {
                offset += Character.charCount(source.codePointAt(start + offset))
                result.add(offset)
            }
            result.toIntArray()
        }
        return offsets.getOrNull(column - 1)?.let { positionAt(start + it) }
    }
}
