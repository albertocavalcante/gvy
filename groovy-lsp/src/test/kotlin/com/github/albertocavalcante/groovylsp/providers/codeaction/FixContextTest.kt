package com.github.albertocavalcante.groovylsp.providers.codeaction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for FixContext data class.
 * Validates: Requirements 1.4
 */
class FixContextTest {

    @Test
    fun `FixContext stores diagnostic correctly`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            "TrailingWhitespace",
            "Trailing whitespace found",
        )
        val content = "def x = 1   \n"
        val lines = content.lines()
        val uriString = "file:///test.groovy"

        val context = FixContext(diagnostic, content, lines, uriString)

        assertEquals(diagnostic, context.diagnostic)
    }

    @Test
    fun `FixContext stores content correctly`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            "TrailingWhitespace",
            "Trailing whitespace found",
        )
        val content = "def x = 1   \n"
        val lines = content.lines()
        val uriString = "file:///test.groovy"

        val context = FixContext(diagnostic, content, lines, uriString)

        assertEquals(content, context.content)
    }

    @Test
    fun `FixContext stores lines correctly`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            "TrailingWhitespace",
            "Trailing whitespace found",
        )
        val content = "def x = 1   \ndef y = 2"
        val lines = content.lines()
        val uriString = "file:///test.groovy"

        val context = FixContext(diagnostic, content, lines, uriString)

        assertEquals(lines, context.lines)
        assertEquals(2, context.lines.size)
        assertEquals("def x = 1   ", context.lines[0])
        assertEquals("def y = 2", context.lines[1])
    }

    @Test
    fun `FixContext stores uriString correctly`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            "TrailingWhitespace",
            "Trailing whitespace found",
        )
        val content = "def x = 1   \n"
        val lines = content.lines()
        val uriString = "file:///test.groovy"

        val context = FixContext(diagnostic, content, lines, uriString)

        assertEquals(uriString, context.uriString)
    }

    @Test
    fun `FixContext data class equality works correctly`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            "TrailingWhitespace",
            "Trailing whitespace found",
        )
        val content = "def x = 1   \n"
        val lines = content.lines()
        val uriString = "file:///test.groovy"

        val context1 = FixContext(diagnostic, content, lines, uriString)
        val context2 = FixContext(diagnostic, content, lines, uriString)

        assertEquals(context1, context2)
    }
}
