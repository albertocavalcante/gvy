// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.model

/**
 * Keeps Groovy-derived positions only when both endpoints are in an unambiguous BMP prefix.
 * Groovy can report mixed column widths, so positions on or after a surrogate are unavailable.
 */
class GroovyRangeGuard(source: String) {
    private data class LineInfo(val length: Int, val firstSurrogate: Int)

    private val lines = source.lines().map { line ->
        LineInfo(line.length, line.indexOfFirst { it in '\uD800'..'\uDFFF' })
    }

    fun validRange(range: RangeDto): RangeDto? {
        if (!validPosition(range.startLine, range.startColumn)) return null
        if (!validPosition(range.endLine, range.endColumn)) return null
        if (range.endLine < range.startLine) return null
        if (range.endLine == range.startLine && range.endColumn < range.startColumn) return null
        return range
    }

    private fun validPosition(line: Int, column: Int): Boolean {
        val info = lines.getOrNull(line - 1) ?: return false
        if (column < 1 || column > info.length + 1) return false
        return info.firstSurrogate < 0 || column <= info.firstSurrogate
    }
}
