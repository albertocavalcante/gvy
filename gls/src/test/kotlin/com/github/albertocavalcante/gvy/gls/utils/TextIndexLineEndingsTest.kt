package com.github.albertocavalcante.gvy.gls.utils

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextIndexLineEndingsTest {
    @Test
    fun `protocol lines and Groovy lexer lines round trip across all line endings`() {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val source = "ab" + ending + "𐐀x" + ending
            val index = TextIndex(source)
            val groovy = if (ending == "\r") Position(0, 4) else Position(1, 1)
            assertEquals(Position(1, 2), index.utf16Position(groovy), ending)
            assertEquals(groovy, index.codePointPosition(Position(1, 2)), ending)
            assertEquals(Range(Position(1, 0), Position(1, 3)), index.lineRange(1), ending)
            assertEquals(Position(2, 0), index.positionOf(source.length), ending)
            assertNull(index.codePointPosition(Position(1, 4)), ending)
            for (position in listOf(Position(0, 0), Position(0, 2), Position(1, 0), Position(1, 3), Position(2, 0))) {
                assertEquals(position, index.positionOf(index.offsetOf(position)), ending)
            }
        }
    }

    @Test
    fun `cold and shared coordinate lookups agree at surrogate and mixed newline boundaries`() {
        val source = "a😀\rb\r\nc\n"
        val expected = listOf(
            Position(0, 0) to Position(0, 0),
            Position(0, 1) to Position(0, 1),
            Position(0, 2) to Position(0, 2), // A cursor inside the pair counts the leading surrogate.
            Position(0, 3) to Position(0, 2),
            Position(1, 0) to Position(0, 3),
            Position(1, 1) to Position(0, 4),
            Position(2, 0) to Position(1, 0),
            Position(2, 1) to Position(1, 1),
            Position(3, 0) to Position(2, 0),
        )
        val shared = TextIndex(source)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 32).map {
                Callable {
                    for ((protocol, groovy) in expected) {
                        assertEquals(groovy, TextIndex(source).codePointPosition(protocol), "cold $protocol")
                        // Populate all raw-line maps concurrently with inverse cursor reads.
                        shared.utf16Position(groovy)
                        assertEquals(groovy, shared.codePointPosition(protocol), "shared $protocol")
                    }
                    assertNull(shared.utf16Position(Position(0, 5))) // Between CR and LF.
                    assertNull(shared.utf16Position(Position(9, 0)))
                    assertNull(shared.codePointPosition(Position(0, 4)))
                }
            }
            for (result in workers.invokeAll(jobs, 5, TimeUnit.SECONDS)) result.get()
        } finally {
            workers.shutdownNow()
        }
    }
}
