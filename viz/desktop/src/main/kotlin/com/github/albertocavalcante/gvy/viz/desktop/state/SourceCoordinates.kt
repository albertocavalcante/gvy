package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.RangeDto

/** One-based UTF-16 coordinates with offsets bounded to the requested line. */
internal class SourceCoordinates(text: String) {
    private val starts = mutableListOf(0)
    private val ends = mutableListOf<Int>()

    init {
        var index = 0
        while (index < text.length) {
            if (text[index] == '\r' || text[index] == '\n') {
                ends.add(index)
                if (text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                starts.add(index + 1)
            }
            index++
        }
        ends.add(text.length)
    }

    fun offset(line: Int, column: Int): Int? {
        if (line < 1 || line > starts.size || column < 1) return null
        val offset = starts[line - 1].toLong() + column - 1L
        return if (offset <= ends[line - 1]) offset.toInt() else null
    }

    fun position(offset: Int): Pair<Int, Int> {
        val target = offset.coerceIn(0, ends.last())
        val found = starts.binarySearch(target)
        val lineIndex = if (found >= 0) found else -found - 2
        // A caret between CR and LF already belongs to the following line, as in the editor.
        return if (target > ends[lineIndex]) lineIndex + 2 to 1 else lineIndex + 1 to target - starts[lineIndex] + 1
    }

    fun span(range: RangeDto): IntRange? {
        val start = offset(range.startLine, range.startColumn) ?: return null
        val end = offset(range.endLine, range.endColumn) ?: return null
        return if (end > start) start until end else null
    }

    fun lineEnd(line: Int): Int? = ends.getOrNull(line - 1)
}

/** Shares one coordinate index across all diagnostics in a rendering pass. */
internal fun errorSpans(text: String, errors: List<CodeError>): List<IntRange> {
    val coordinates = SourceCoordinates(text)
    return errors.mapNotNull { error ->
        val start = coordinates.offset(error.startLine, error.startColumn) ?: return@mapNotNull null
        val end = if (error.endLine != -1 && error.endColumn != -1) {
            coordinates.offset(error.endLine, error.endColumn)
        } else {
            coordinates.lineEnd(error.startLine)
        }
        end?.takeIf { it > start }?.let { start until it }
    }
}
