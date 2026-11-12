package com.github.albertocavalcante.gvy.source

/** Zero-based line and UTF-16 code-unit position in a document. */
public data class Utf16Position(val line: Int, val character: Int)

/** A half-open range whose positions refer to the same document state. */
public data class Utf16Range(val start: Utf16Position, val end: Utf16Position)

/** One incoming change; each range addresses the result of the preceding change. */
public sealed interface DocumentChange {
    public data class Replace(val range: Utf16Range, val text: String) : DocumentChange
    public data class ReplaceAll(val text: String) : DocumentChange
}

/** Why a batch cannot be applied; reasons never retain source text. */
public enum class DocumentChangeRejectionReason {
    NEGATIVE_POSITION,
    LINE_OUT_OF_BOUNDS,
    REVERSED_RANGE,
    OUTPUT_TOO_LARGE,
}

/** Complete batch output, or rejection of one event without intermediate text. */
public sealed interface DocumentChangeResult {
    public data class Applied(val text: String) : DocumentChangeResult
    public data class Rejected(val changeIndex: Int, val reason: DocumentChangeRejectionReason) : DocumentChangeResult
}

/**
 * Applies changes in authored order against the evolving text, without mutating either input.
 *
 * Columns beyond a line's content clamp to that line's end. Negative positions, nonexistent lines
 * and authored reversed ranges reject the entire batch. Ranges are half-open and count raw UTF-16
 * units, including boundaries inside surrogate pairs. LF, CRLF and bare CR delimit lines.
 *
 * This is sequential incoming-change replay, not simultaneous edits against one original string.
 * Rejection never exposes intermediate text. Unrepresentable output lengths are rejected before
 * allocation; allocation failures propagate.
 */
public fun applyOrderedDocumentChanges(initialText: String, changes: List<DocumentChange>): DocumentChangeResult {
    var text = initialText
    changes.forEachIndexed { index, change ->
        when (change) {
            is DocumentChange.ReplaceAll -> text = change.text
            is DocumentChange.Replace -> when (val result = applyReplacement(text, change, index)) {
                is DocumentChangeResult.Applied -> text = result.text
                is DocumentChangeResult.Rejected -> return result
            }
        }
    }
    return DocumentChangeResult.Applied(text)
}

private fun applyReplacement(text: String, change: DocumentChange.Replace, index: Int): DocumentChangeResult {
    val range = change.range
    invalidRangeReason(range)?.let { return DocumentChangeResult.Rejected(index, it) }

    val lines = Utf16LineIndex(text)
    val startLength = lines.lineLength(range.start.line)
    val endLength = lines.lineLength(range.end.line)
    if (startLength == null || endLength == null) {
        return DocumentChangeResult.Rejected(index, DocumentChangeRejectionReason.LINE_OUT_OF_BOUNDS)
    }
    val start = lines.lineStart(range.start.line) + minOf(range.start.character, startLength)
    val end = lines.lineStart(range.end.line) + minOf(range.end.character, endLength)
    val length = checkedDocumentChangeLength(text.length, end - start, change.text.length)
    return if (length == null) {
        DocumentChangeResult.Rejected(index, DocumentChangeRejectionReason.OUTPUT_TOO_LARGE)
    } else {
        DocumentChangeResult.Applied(
            buildString(length) {
                append(text, 0, start)
                append(change.text)
                append(text, end, text.length)
            },
        )
    }
}

private fun invalidRangeReason(range: Utf16Range): DocumentChangeRejectionReason? = when {
    range.start.line < 0 || range.start.character < 0 || range.end.line < 0 || range.end.character < 0 ->
        DocumentChangeRejectionReason.NEGATIVE_POSITION
    range.start.line > range.end.line ||
        (range.start.line == range.end.line && range.start.character > range.end.character) ->
        DocumentChangeRejectionReason.REVERSED_RANGE
    else -> null
}

/** Calculates a replacement length from validated nonnegative lengths without allocating the result. */
internal fun checkedDocumentChangeLength(currentLength: Int, removedLength: Int, replacementLength: Int): Int? =
    (currentLength.toLong() - removedLength + replacementLength).takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
