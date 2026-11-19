// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.nativeapi.ParserPosition
import com.github.albertocavalcante.nativeapi.ParserRange

/** Coordinates in Groovy exceptions count code points; document coordinates count UTF-16 units. */
internal class SourceSnapshot(val text: String) {
    private val lineStarts = buildList {
        add(0)
        text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }

    fun compilerOffsetOrNull(line: Int, column: Int): Int? {
        if (line !in 1..lineStarts.size || column < 1) return null
        val start = lineStarts[line - 1]
        val count = text.codePointCount(start, lineEnd(start))
        if (column - 1 > count) return null
        return text.offsetByCodePoints(start, column - 1)
    }

    fun compilerOffset(line: Int, column: Int): Int {
        val start = lineStarts[(line - 1).coerceIn(0, lineStarts.lastIndex)]
        val end = lineEnd(start)
        val count = text.codePointCount(start, end)
        return text.offsetByCodePoints(start, (column - 1).coerceIn(0, count))
    }

    fun position(offset: Int): ParserPosition {
        val bounded = offset.coerceIn(0, text.length)
        val index = lineStarts.binarySearch(bounded).let { if (it >= 0) it else -it - 2 }
        val start = lineStarts[index]
        return ParserPosition(index, bounded.coerceAtMost(lineEnd(start)) - start)
    }

    fun range(start: Int, end: Int): ParserRange = ParserRange(position(start), position(end))

    private fun lineEnd(start: Int): Int {
        val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        return if (newline > start && text[newline - 1] == '\r') newline - 1 else newline
    }
}
