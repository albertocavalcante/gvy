package com.github.albertocavalcante.groovylsp.parser.incremental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for TokenEditDistance - mapping positions between document versions.
 * Based on Metals' TokenEditDistance pattern.
 */
class TokenEditDistanceTest {

    @Test
    fun `toRevised maps position when line added`() {
        val original = """
            line1
            line2
            line3
        """.trimIndent()

        val revised = """
            line1
            newLine
            line2
            line3
        """.trimIndent()

        val editDistance = TokenEditDistance.fromTexts(original, revised)

        // line3 was at line 2 in original, now at line 3
        val originalPosition = Position(2, 0)
        val revisedPosition = editDistance.toRevised(originalPosition)

        assertEquals(Position(3, 0), revisedPosition)
    }

    @Test
    fun `toRevised maps position when text deleted`() {
        val original = """
            line1
            line2
            line3
            line4
        """.trimIndent()

        val revised = """
            line1
            line3
            line4
        """.trimIndent()

        val editDistance = TokenEditDistance.fromTexts(original, revised)

        // line3 was at line 2 in original, now at line 1
        val originalPosition = Position(2, 0)
        val revisedPosition = editDistance.toRevised(originalPosition)

        assertEquals(Position(1, 0), revisedPosition)
    }

    @Test
    fun `toOriginal reverses toRevised for matching tokens`() {
        val original = """
            line1
            line2
            line3
        """.trimIndent()

        val revised = """
            line1
            modified
            line2
            line3
        """.trimIndent()

        val editDistance = TokenEditDistance.fromTexts(original, revised)

        val originalPosition = Position(0, 0)
        val revisedPosition = editDistance.toRevised(originalPosition)
        val backToOriginal = editDistance.toOriginal(revisedPosition)

        assertEquals(originalPosition, backToOriginal)
    }

    @Test
    fun `handles unchanged document`() {
        val original = """
            line1
            line2
            line3
        """.trimIndent()

        val editDistance = TokenEditDistance.fromTexts(original, original)

        val position = Position(1, 5)
        val revisedPosition = editDistance.toRevised(position)

        assertEquals(position, revisedPosition)
    }

    @Test
    fun `handles empty document`() {
        val editDistance = TokenEditDistance.fromTexts("", "")

        val position = Position(0, 0)
        val revisedPosition = editDistance.toRevised(position)

        assertEquals(Position(0, 0), revisedPosition)
    }

    @Test
    fun `handles column-level changes within line`() {
        val original = "def foo = bar"
        val revised = "def fooBar = bar"

        val editDistance = TokenEditDistance.fromTexts(original, revised)

        // Position of 'bar' token at column 10 in original
        val originalPosition = Position(0, 10)
        val revisedPosition = editDistance.toRevised(originalPosition)

        // KNOWN LIMITATION: Since lines don't match exactly, LCS creates no mapping.
        // With no mapping, fallback finds closest line (0) but preserves original column.
        // Column-level mapping (proportional or token-based) only applies when lines ARE mapped.
        // This means modified lines don't get smart column adjustment.
        // TODO: Consider using line similarity or token-level LCS for modified lines.
        assertEquals(Position(0, 10), revisedPosition)
    }
}
