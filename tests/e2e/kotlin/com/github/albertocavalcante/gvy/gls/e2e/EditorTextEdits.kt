package com.github.albertocavalcante.gvy.gls.e2e

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit

/** Units negotiated by LSP 3.17 for a position's character offset. */
internal enum class EditorPositionEncoding { UTF8, UTF16, UTF32 }

/** Applies a single document's simultaneous LSP edits to a new string; no client or filesystem state is changed. */
internal object EditorTextEdits {
    fun apply(text: String, edits: List<TextEdit>, encoding: EditorPositionEncoding): String {
        val lines = lines(text)
        val resolved = edits.mapIndexed { index, edit ->
            val range = requireNotNull(edit.range) { "Text edit requires a range" }
            val start = offset(text, lines, requireNotNull(range.start), encoding)
            val end = offset(text, lines, requireNotNull(range.end), encoding)
            require(start <= end) { "Text edit range is reversed" }
            ResolvedEdit(start, end, requireNotNull(edit.newText) { "Text edit requires newText" }, index)
        }.sortedWith(compareBy<ResolvedEdit> { it.start }.thenBy { it.index })

        val result = StringBuilder(text.length)
        var cursor = 0
        for (edit in resolved) {
            require(edit.start >= cursor) { "Text edit ranges overlap or replace precedes an insert at the same position" }
            result.append(text, cursor, edit.start)
            result.append(edit.text)
            cursor = edit.end
        }
        result.append(text, cursor, text.length)
        return result.toString()
    }

    private fun lines(text: String): List<Line> {
        val result = mutableListOf<Line>()
        var start = 0
        var at = 0
        while (at < text.length) {
            if (text[at] == '\r' || text[at] == '\n') {
                result.add(Line(start, at))
                if (text[at] == '\r' && at + 1 < text.length && text[at + 1] == '\n') at++
                start = at + 1
            }
            at++
        }
        result.add(Line(start, text.length))
        return result
    }

    private fun offset(text: String, lines: List<Line>, position: Position, encoding: EditorPositionEncoding): Int {
        require(position.line >= 0 && position.line < lines.size) { "Text edit line is out of range" }
        require(position.character >= 0) { "Text edit character is negative" }
        val line = lines[position.line]
        var at = line.start
        var units = 0
        while (at < line.end) {
            if (units == position.character) return at
            val first = text[at]
            val width = if (first.isHighSurrogate()) {
                require(at + 1 < line.end && text[at + 1].isLowSurrogate()) { "Unpaired high surrogate" }
                2
            } else {
                require(!first.isLowSurrogate()) { "Unpaired low surrogate" }
                1
            }
            val codePoint = if (width == 2) Character.toCodePoint(first, text[at + 1]) else first.code
            val count = when (encoding) {
                EditorPositionEncoding.UTF8 -> when {
                    codePoint <= 0x7f -> 1
                    codePoint <= 0x7ff -> 2
                    codePoint <= 0xffff -> 3
                    else -> 4
                }
                EditorPositionEncoding.UTF16 -> width
                EditorPositionEncoding.UTF32 -> 1
            }
            require(units + count <= position.character) { "Text edit splits an encoded character" }
            units += count
            at += width
        }
        // LSP positions beyond a line's length refer to the end of that line.
        return line.end
    }

    private data class Line(val start: Int, val end: Int)
    private data class ResolvedEdit(val start: Int, val end: Int, val text: String, val index: Int)
}
