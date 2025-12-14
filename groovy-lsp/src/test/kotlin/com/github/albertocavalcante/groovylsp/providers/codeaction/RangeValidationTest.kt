package com.github.albertocavalcante.groovylsp.providers.codeaction

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for range validation edge cases in LintFixAction.
 *
 * **Feature: codenarc-lint-fixes**
 * **Validates: Requirements 5.2, 5.3**
 */
class RangeValidationTest {

    private lateinit var lintFixAction: LintFixAction
    private val testUri = "file:///test.groovy"

    @BeforeEach
    fun setUp() {
        lintFixAction = LintFixAction()
    }

    // === Out-of-Bounds Line Number Tests ===

    @Test
    fun `returns empty list when line number exceeds source line count`() {
        val content = "def x = 1\ndef y = 2" // 2 lines (0 and 1)
        val diagnostic = createDiagnosticWithLine(
            ruleName = "TrailingWhitespace",
            line = 5, // Out of bounds
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertTrue(actions.isEmpty(), "Expected empty list for out-of-bounds line number")
    }

    @Test
    fun `returns empty list when line number equals source line count`() {
        val content = "def x = 1\ndef y = 2" // 2 lines (indices 0 and 1)
        val diagnostic = createDiagnosticWithLine(
            ruleName = "TrailingWhitespace",
            line = 2, // Equals line count, so out of bounds
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertTrue(actions.isEmpty(), "Expected empty list when line equals line count")
    }

    @Test
    fun `returns empty list when line number is very large`() {
        val content = "def x = 1"
        val diagnostic = createDiagnosticWithLine(
            ruleName = "TrailingWhitespace",
            line = Int.MAX_VALUE,
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertTrue(actions.isEmpty(), "Expected empty list for very large line number")
    }

    // === Negative Line Number Tests ===

    @Test
    fun `returns empty list when line number is negative`() {
        val content = "def x = 1   "
        val diagnostic = createDiagnosticWithLine(
            ruleName = "TrailingWhitespace",
            line = -1,
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertTrue(actions.isEmpty(), "Expected empty list for negative line number")
    }

    @Test
    fun `returns empty list when line number is very negative`() {
        val content = "def x = 1   "
        val diagnostic = createDiagnosticWithLine(
            ruleName = "TrailingWhitespace",
            line = Int.MIN_VALUE,
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertTrue(actions.isEmpty(), "Expected empty list for very negative line number")
    }

    // === Invalid Range Tests ===

    @Test
    fun `returns empty list when start character exceeds line length`() {
        val content = "def x = 1" // Line length is 9
        val diagnostic = createDiagnosticWithRange(
            ruleName = "UnnecessaryPublicModifier",
            startLine = 0,
            startChar = 100, // Beyond line length
            endLine = 0,
            endChar = 110,
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertTrue(actions.isEmpty(), "Expected empty list when start char exceeds line length")
    }

    @Test
    fun `returns empty list when end character exceeds line length`() {
        val content = "def x = 1" // Line length is 9
        val diagnostic = createDiagnosticWithRange(
            ruleName = "UnnecessaryPublicModifier",
            startLine = 0,
            startChar = 0,
            endLine = 0,
            endChar = 100, // Beyond line length
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertTrue(actions.isEmpty(), "Expected empty list when end char exceeds line length")
    }

    @Test
    fun `returns empty list when start character is negative`() {
        val content = "def x = 1"
        val diagnostic = createDiagnosticWithRange(
            ruleName = "UnnecessaryPublicModifier",
            startLine = 0,
            startChar = -5,
            endLine = 0,
            endChar = 5,
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertTrue(actions.isEmpty(), "Expected empty list when start char is negative")
    }

    @Test
    fun `returns empty list when start character is greater than end character`() {
        val content = "def x = 1"
        val diagnostic = createDiagnosticWithRange(
            ruleName = "UnnecessaryPublicModifier",
            startLine = 0,
            startChar = 8,
            endLine = 0,
            endChar = 3, // End before start
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertTrue(actions.isEmpty(), "Expected empty list when start > end")
    }

    // === Empty Content Tests ===

    @Test
    fun `returns empty list for empty content with any line number`() {
        val content = ""
        val diagnostic = createDiagnosticWithLine(
            ruleName = "TrailingWhitespace",
            line = 0,
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        // Empty content has 1 line (empty string), but no trailing whitespace to fix
        assertTrue(actions.isEmpty(), "Expected empty list for empty content")
    }

    @Test
    fun `returns empty list for empty content with out of bounds line`() {
        val content = ""
        val diagnostic = createDiagnosticWithLine(
            ruleName = "TrailingWhitespace",
            line = 1, // Out of bounds for empty content
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertTrue(actions.isEmpty(), "Expected empty list for out-of-bounds line in empty content")
    }

    // === Valid Boundary Tests ===

    @Test
    fun `processes valid line at boundary - last line`() {
        val content = "def x = 1\ndef y = 2   " // Line 1 has trailing whitespace
        val diagnostic = createDiagnosticWithLine(
            ruleName = "TrailingWhitespace",
            line = 1, // Last line (valid)
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        // Should produce an action for valid line with trailing whitespace
        assertTrue(actions.size == 1, "Expected 1 action for valid last line with trailing whitespace")
    }

    @Test
    fun `processes valid line at boundary - first line`() {
        val content = "def x = 1   \ndef y = 2" // Line 0 has trailing whitespace
        val diagnostic = createDiagnosticWithLine(
            ruleName = "TrailingWhitespace",
            line = 0, // First line (valid)
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        // Should produce an action for valid line with trailing whitespace
        assertTrue(actions.size == 1, "Expected 1 action for valid first line with trailing whitespace")
    }

    // === Helper Methods ===

    private fun createDiagnosticWithLine(
        ruleName: String,
        line: Int,
        startChar: Int = 0,
        endChar: Int = 10,
    ): Diagnostic = Diagnostic().apply {
        range = Range(Position(line, startChar), Position(line, endChar))
        message = "Test diagnostic for $ruleName"
        source = "CodeNarc"
        code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(ruleName)
        severity = DiagnosticSeverity.Warning
    }

    private fun createDiagnosticWithRange(
        ruleName: String,
        startLine: Int,
        startChar: Int,
        endLine: Int,
        endChar: Int,
    ): Diagnostic = Diagnostic().apply {
        range = Range(Position(startLine, startChar), Position(endLine, endChar))
        message = "Test diagnostic for $ruleName"
        source = "CodeNarc"
        code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(ruleName)
        severity = DiagnosticSeverity.Warning
    }
}
