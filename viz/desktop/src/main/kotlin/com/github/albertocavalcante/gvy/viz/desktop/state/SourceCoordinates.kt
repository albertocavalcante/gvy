package com.github.albertocavalcante.gvy.viz.desktop.state

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
}

internal fun sourceOffset(text: String, line: Int, column: Int): Int? = SourceCoordinates(text).offset(line, column)
