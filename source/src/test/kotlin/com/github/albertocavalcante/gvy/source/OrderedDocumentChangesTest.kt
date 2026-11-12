package com.github.albertocavalcante.gvy.source

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals

class OrderedDocumentChangesTest {
    @Test
    fun `same-position inserts apply in notification order against evolving text`() {
        assertApplied("aYXb", "ab", replace(0, 1, 0, 1, "X"), replace(0, 1, 0, 1, "Y"))
    }

    @Test
    fun `oversized columns clamp to the current line without crossing its break`() {
        assertApplied("ab!\r\ncd", "ab\r\ncd", replace(0, Int.MAX_VALUE, 0, Int.MAX_VALUE, "!"))
        assertApplied("a!\nb", "a\nb", replace(0, 99, 0, 100, "!"))
    }

    @Test
    fun `later ranges use line lengths and line count produced by earlier changes`() {
        assertApplied(
            "x\nY!Z",
            "abcd",
            replace(0, 0, 0, 4, "x\nYZ"),
            replace(1, 1, 1, 1, "!"),
        )
        assertApplied("a!\nb", "abcd\nb", replace(0, 1, 0, 4, ""), replace(0, 99, 0, 99, "!"))
    }

    @Test
    fun `empty changes and full replacements retain the authored final state`() {
        assertApplied("unchanged", "unchanged")
        assertApplied("", "", DocumentChange.ReplaceAll(""))
        assertApplied(
            "final\n!",
            "initial",
            DocumentChange.ReplaceAll("intermediate"),
            DocumentChange.ReplaceAll("final\n"),
            replace(1, 0, 1, 0, "!"),
        )
        assertApplied(
            "reset?",
            "abc",
            replace(0, 0, 0, 1, "ignored"),
            DocumentChange.ReplaceAll("reset"),
            replace(0, 5, 0, 5, "?"),
        )
    }

    @Test
    fun `line breaks remain exact unless a range removes the whole break`() {
        for (breakText in listOf("\n", "\r\n", "\r")) {
            val source = "a" + breakText + "b"
            assertApplied("a-b", source, replace(0, 1, 1, 0, "-"))
            assertApplied("a!" + breakText + "b", source, replace(0, 1, 0, 1, "!"))
            assertApplied("", source, replace(0, 0, 1, 1, ""))
            assertApplied(breakText + "!", breakText, replace(1, 0, 1, 0, "!"))
        }
        assertApplied("a\r\nb\rc!\nd", "a\r\nb\rc\nd", replace(2, 1, 2, 1, "!"))
        assertApplied("!", "", replace(0, 0, 0, 0, "!"))
    }

    @Test
    fun `UTF16 boundaries include pair interiors and preserve unpaired code units`() {
        assertApplied("A!B", "A😀B", replace(0, 1, 0, 3, "!"))
        assertApplied("A\uD83Dx\uDE00B", "A😀B", replace(0, 2, 0, 2, "x"))
        assertApplied("AXYB", "A😀B", replace(0, 1, 0, 2, "X"), replace(0, 2, 0, 3, "Y"))
        assertApplied("\uD83D!-\uDE00", "\uD83D-\uDE00", replace(0, 1, 0, 1, "!"))
        assertApplied("\uDC00", "source", DocumentChange.ReplaceAll("\uDC00"))
    }

    @Test
    fun `negative positions reject the authored event with a typed reason`() {
        val negativeRanges = listOf(
            replace(-1, 0, 0, 0, "x"),
            replace(0, -1, 0, 0, "x"),
            replace(0, 0, -1, 0, "x"),
            replace(0, 0, 0, -1, "x"),
            replace(Int.MIN_VALUE, 0, 0, 0, "x"),
        )
        for (change in negativeRanges) {
            assertRejected(DocumentChangeRejectionReason.NEGATIVE_POSITION, "abc", change)
        }
    }

    @Test
    fun `nonexistent lines reject instead of clamping to the document end`() {
        for (change in listOf(replace(1, 0, 1, 0, "x"), replace(0, 0, Int.MAX_VALUE, 0, "x"))) {
            assertRejected(DocumentChangeRejectionReason.LINE_OUT_OF_BOUNDS, "abc", change)
        }
        assertRejected(DocumentChangeRejectionReason.LINE_OUT_OF_BOUNDS, "", replace(1, 0, 1, 0, "x"))
    }

    @Test
    fun `authored reversed ranges reject even when clamping would hide the reversal`() {
        assertRejected(DocumentChangeRejectionReason.REVERSED_RANGE, "abc", replace(0, 2, 0, 1, "x"))
        assertRejected(DocumentChangeRejectionReason.REVERSED_RANGE, "abc", replace(0, 100, 0, 99, "x"))
        assertRejected(DocumentChangeRejectionReason.REVERSED_RANGE, "a\nb", replace(1, 0, 0, 1, "x"))
    }

    @Test
    fun `a later invalid event exposes only its rejection and event index`() {
        assertEquals(
            DocumentChangeResult.Rejected(1, DocumentChangeRejectionReason.LINE_OUT_OF_BOUNDS),
            applyOrderedDocumentChanges(
                "a\nb",
                listOf(DocumentChange.ReplaceAll(""), replace(1, 0, 1, 0, "x")),
            ),
        )
        assertEquals(
            DocumentChangeResult.Rejected(2, DocumentChangeRejectionReason.REVERSED_RANGE),
            applyOrderedDocumentChanges(
                "abc",
                listOf(replace(0, 0, 0, 1, "XY"), replace(0, 4, 0, 4, "!"), replace(0, 3, 0, 1, "")),
            ),
        )
    }

    @Test
    fun `applying changes leaves the caller's event list in authored order`() {
        val changes = mutableListOf<DocumentChange>(replace(0, 2, 0, 2, "X"), replace(0, 0, 0, 0, "Y"))
        val authored = changes.toList()
        assertEquals(DocumentChangeResult.Applied("YabX"), applyOrderedDocumentChanges("ab", changes))
        assertEquals(authored, changes)
    }

    @Test
    fun `output lengths check the representable boundary without overflowing or allocating text`() {
        assertEquals(0, checkedDocumentChangeLength(0, 0, 0))
        assertEquals(5, checkedDocumentChangeLength(10, 8, 3))
        assertEquals(Int.MAX_VALUE, checkedDocumentChangeLength(Int.MAX_VALUE, 1, 1))
        assertEquals(Int.MAX_VALUE, checkedDocumentChangeLength(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(null, checkedDocumentChangeLength(Int.MAX_VALUE, 0, 1))
        assertEquals(null, checkedDocumentChangeLength(Int.MAX_VALUE, 0, Int.MAX_VALUE))
    }

    @Test
    fun `fixed-seed sequences agree with an independent offset splice oracle at every step`() {
        val fragments = listOf("", "x", "\r\n", "😀", "line\n", "\uD83D", "\uDE00")
        for (seed in listOf(7, 19, 53)) {
            val random = Random(seed)
            val initial = "a😀\r\nb\rc\n"
            var expected = initial
            val changes = mutableListOf<DocumentChange>()
            repeat(30) { step ->
                val replacement = fragments[random.nextInt(fragments.size)]
                if (random.nextInt(5) == 0) {
                    changes += DocumentChange.ReplaceAll(replacement)
                    expected = replacement
                } else {
                    val boundaries = (0..expected.length).filter { offset ->
                        offset == 0 || offset == expected.length ||
                            expected[offset - 1] != '\r' || expected[offset] != '\n'
                    }
                    val first = boundaries[random.nextInt(boundaries.size)]
                    val second = boundaries[random.nextInt(boundaries.size)]
                    val start = minOf(first, second)
                    val end = maxOf(first, second)
                    changes += DocumentChange.Replace(
                        RawUtf16Range(oraclePosition(expected, start), oraclePosition(expected, end)),
                        replacement,
                    )
                    expected = expected.take(start) + replacement + expected.drop(end)
                }
                assertEquals(
                    DocumentChangeResult.Applied(expected),
                    applyOrderedDocumentChanges(initial, changes),
                    "seed=$seed step=$step",
                )
            }
        }
    }

    // The oracle derives coordinates by scanning the prefix, without using the production line index.
    private fun oraclePosition(text: String, offset: Int): RawUtf16Position {
        var line = 0
        var character = 0
        var at = 0
        while (at < offset) {
            when (text[at]) {
                '\r' -> {
                    if (at + 1 < offset && text[at + 1] == '\n') at++
                    line++
                    character = 0
                }
                '\n' -> {
                    line++
                    character = 0
                }
                else -> character++
            }
            at++
        }
        return RawUtf16Position(line, character)
    }

    private fun assertRejected(reason: DocumentChangeRejectionReason, initial: String, change: DocumentChange) {
        assertEquals(DocumentChangeResult.Rejected(0, reason), applyOrderedDocumentChanges(initial, listOf(change)))
    }

    private fun assertApplied(expected: String, initial: String, vararg changes: DocumentChange) {
        assertEquals(DocumentChangeResult.Applied(expected), applyOrderedDocumentChanges(initial, changes.toList()))
    }

    private fun replace(sl: Int, sc: Int, el: Int, ec: Int, text: String): DocumentChange.Replace =
        DocumentChange.Replace(RawUtf16Range(RawUtf16Position(sl, sc), RawUtf16Position(el, ec)), text)
}
