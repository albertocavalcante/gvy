package com.github.albertocavalcante.gvy.gls.providers.selection

import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRange

/** Builds strictly nested selections from one held source; no compilation, cache or filesystem access. */
internal class SelectionRangeBuilder(private val index: TextIndex, structures: List<Range>) {
    private val text = index.text
    private val document = Span(0, text.length)
    private val structuralSpans = structures.mapNotNull { range ->
        if (index.codePointPosition(range.start) == null || index.codePointPosition(range.end) == null) {
            null
        } else {
            val start = index.offsetOf(range.start)
            val end = index.offsetOf(range.end)
            if (start < end) Span(start, end) else null
        }
    }.distinct()

    fun build(positions: List<Position>, checkCancelled: () -> Unit): List<SelectionRange> = positions.map { position ->
        checkCancelled()
        require(index.codePointPosition(position) != null) { "Position is outside the source" }
        val offset = index.offsetOf(position)
        val leaf = word(offset) ?: Span(offset, offset)
        val line = checkNotNull(index.lineRange(position.line))
        val candidates = structuralSpans.asSequence()
            .onEach { checkCancelled() }
            .filter { it.contains(leaf) }
            .plus(Span(index.offsetOf(line.start), index.offsetOf(line.end)))
            .plus(document)
            .sortedWith(compareBy<Span> { it.end - it.start }.thenBy { it.start })
        val chain = mutableListOf(leaf)
        for (candidate in candidates) {
            checkCancelled()
            if (candidate != chain.last() && candidate.contains(chain.last())) {
                if (chain.size == MAX_SELECTION_DEPTH - 1) break
                chain += candidate
            }
        }
        if (chain.last() != document) chain += document
        var parent: SelectionRange? = null
        for (span in chain.asReversed()) {
            parent = SelectionRange(Range(index.positionOf(span.start), index.positionOf(span.end)), parent)
        }
        checkNotNull(parent)
    }

    private fun word(offset: Int): Span? {
        val splitsCodePoint = offset in 1 until text.length &&
            text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()
        if (offset >= text.length || splitsCodePoint || !Character.isJavaIdentifierPart(text.codePointAt(offset))) {
            return null
        }
        var start = offset
        var end = offset
        while (start > 0 && Character.isJavaIdentifierPart(text.codePointBefore(start))) {
            start -= Character.charCount(text.codePointBefore(start))
        }
        while (end < text.length && Character.isJavaIdentifierPart(text.codePointAt(end))) {
            end += Character.charCount(text.codePointAt(end))
        }
        return Span(start, end)
    }

    private data class Span(val start: Int, val end: Int) {
        fun contains(other: Span): Boolean = start <= other.start && end >= other.end
    }

    companion object {
        // Bound serialized parent depth while retaining the document as the outermost selection.
        internal const val MAX_SELECTION_DEPTH = 64
    }
}
