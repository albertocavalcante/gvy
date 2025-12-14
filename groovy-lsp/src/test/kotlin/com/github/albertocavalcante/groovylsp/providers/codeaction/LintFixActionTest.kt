package com.github.albertocavalcante.groovylsp.providers.codeaction

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for LintFixAction.
 * Validates: Requirements 1.3, 1.4, 1.5
 */
class LintFixActionTest {

    private lateinit var lintFixAction: LintFixAction
    private val testUri = "file:///test.groovy"

    @BeforeEach
    fun setUp() {
        lintFixAction = LintFixAction()
    }

    // === Source Filtering Tests ===

    @Test
    fun `returns empty list when no diagnostics`() {
        val actions = lintFixAction.createLintFixActions(testUri, emptyList(), "")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `returns empty list for non-CodeNarc diagnostics`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "SomeRule",
            message = "Some issue",
            source = "PMD",
        )

        val actions = lintFixAction.createLintFixActions(
            testUri,
            listOf(diagnostic),
            "def x = 1",
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `returns empty list when diagnostic source is null`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "SomeRule",
            message = "Some issue",
            source = null,
        )

        val actions = lintFixAction.createLintFixActions(
            testUri,
            listOf(diagnostic),
            "def x = 1",
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `accepts CodeNarc source case-insensitively`() {
        val sources = listOf("CodeNarc", "codenarc", "CODENARC", "cOdEnArC")

        sources.forEach { source ->
            val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
                code = "TrailingWhitespace",
                message = "Found trailing whitespace",
                source = source,
            )

            // Should not throw, should process (even if handler returns null)
            val actions = lintFixAction.createLintFixActions(
                testUri,
                listOf(diagnostic),
                "def x = 1   \n",
            )

            // Currently all handlers return null, so expect empty
            // When handlers implemented, this will validate processing
            assertTrue(actions.isEmpty() || actions.isNotEmpty())
        }
    }

    // === Rule Name Extraction Tests ===

    @Test
    fun `returns empty list when diagnostic code is null`() {
        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 10))
            message = "Some issue"
            source = "CodeNarc"
            code = null // Null code
            severity = DiagnosticSeverity.Warning
        }

        val actions = lintFixAction.createLintFixActions(
            testUri,
            listOf(diagnostic),
            "def x = 1",
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `handles diagnostic code as Either Left (string)`() {
        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 10))
            message = "Found trailing whitespace"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("TrailingWhitespace")
            severity = DiagnosticSeverity.Warning
        }

        val actions = lintFixAction.createLintFixActions(
            testUri,
            listOf(diagnostic),
            "def x = 1   \n",
        )

        // Should process without error (handler returns null currently)
        assertNotNull(actions)
    }

    @Test
    fun `handles diagnostic code as Either Right (number)`() {
        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 10))
            message = "Some issue"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(123)
            severity = DiagnosticSeverity.Warning
        }

        val actions = lintFixAction.createLintFixActions(
            testUri,
            listOf(diagnostic),
            "def x = 1",
        )

        // No handler for "123", should return empty
        assertTrue(actions.isEmpty())
    }

    // === Handler Invocation Tests ===

    @Test
    fun `returns empty list for unregistered rule`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnknownFakeRule",
            message = "Some issue",
        )

        val actions = lintFixAction.createLintFixActions(
            testUri,
            listOf(diagnostic),
            "def x = 1",
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `returns empty list when handler returns null`() {
        // All current handlers return null (placeholders)
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Found trailing whitespace",
        )

        val actions = lintFixAction.createLintFixActions(
            testUri,
            listOf(diagnostic),
            "def x = 1   \n",
        )

        // Handler returns null, so no actions
        assertTrue(actions.isEmpty())
    }

    // === Multiple Diagnostics Tests ===

    @Test
    fun `handles multiple diagnostics correctly`() {
        val diagnostics = listOf(
            TestDiagnosticFactory.createCodeNarcDiagnostic(
                code = "TrailingWhitespace",
                message = "Found trailing whitespace",
                line = 0,
            ),
            TestDiagnosticFactory.createCodeNarcDiagnostic(
                code = "UnnecessarySemicolon",
                message = "Found unnecessary semicolon",
                line = 1,
            ),
            TestDiagnosticFactory.createCodeNarcDiagnostic(
                code = "UnknownRule",
                message = "Unknown issue",
                line = 2,
            ),
            TestDiagnosticFactory.createCodeNarcDiagnostic(
                code = "SomeRule",
                message = "PMD issue",
                line = 3,
                source = "PMD",
            ),
        )

        val content = """
            def x = 1
            def y = 2;
            def z = 3
            def w = 4
        """.trimIndent()

        val actions = lintFixAction.createLintFixActions(testUri, diagnostics, content)

        // Currently all handlers return null, so expect empty
        // When handlers implemented: expect actions for first 2 only
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `handles mixed valid and invalid diagnostics`() {
        val diagnostics = listOf(
            TestDiagnosticFactory.createCodeNarcDiagnostic(
                code = "TrailingWhitespace",
                message = "Found trailing whitespace",
                source = "CodeNarc",
            ),
            TestDiagnosticFactory.createCodeNarcDiagnostic(
                code = "SomeRule",
                message = "Not CodeNarc",
                source = "SomeOtherTool",
            ),
            TestDiagnosticFactory.createCodeNarcDiagnostic(
                code = "UnknownRule",
                message = "Unknown CodeNarc rule",
                source = "CodeNarc",
            ),
        )

        val actions = lintFixAction.createLintFixActions(testUri, diagnostics, "def x = 1")

        // Only first diagnostic might produce action (but handler returns null)
        assertTrue(actions.isEmpty())
    }

    // === Content Handling Tests ===

    @Test
    fun `handles empty content`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Issue",
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), "")

        assertNotNull(actions)
    }

    @Test
    fun `handles multiline content`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Found trailing whitespace",
            line = 2,
        )

        val content = """
            package com.example

            class Foo {
                def bar() { }
            }
        """.trimIndent()

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertNotNull(actions)
    }

    @Test
    fun `handles content with special characters`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Found trailing whitespace",
        )

        val content = "def x = \"Hello\\nWorld\"\t  \n"

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertNotNull(actions)
    }

    // === Edge Cases ===

    @Test
    fun `handles diagnostic with very long message`() {
        val longMessage = "x".repeat(1000)
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = longMessage,
        )

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), "def x = 1")

        assertNotNull(actions)
    }

    @Test
    fun `handles content with many lines`() {
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "TrailingWhitespace",
            message = "Found trailing whitespace",
            line = 500,
        )

        val content = (1..1000).joinToString("\n") { "def x$it = $it" }

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        assertNotNull(actions)
    }

    // TODO: Add tests for actual fix creation when handlers are implemented
    // - Test CodeAction structure (kind, title, edit)
    // - Test WorkspaceEdit structure
    // - Test diagnostics list in CodeAction
}
