package com.github.albertocavalcante.groovylsp.codenarc.quickfix

import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.TrailingWhitespaceFixer
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.UnnecessarySemicolonFixer
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EnhancedCodeActionProviderTest {

    private lateinit var registry: CodeNarcQuickFixRegistry
    private lateinit var provider: EnhancedCodeActionProvider

    @BeforeEach
    fun setUp() {
        registry = CodeNarcQuickFixRegistry()
        provider = EnhancedCodeActionProvider(registry)

        // Register our test fixers
        registry.register(UnnecessarySemicolonFixer())
        registry.register(TrailingWhitespaceFixer())
    }

    @Test
    fun `should provide actions for CodeNarc diagnostics`() {
        val params = createCodeActionParams(
            listOf(
                createDiagnostic("UnnecessarySemicolon", line = 0),
                createDiagnostic("TrailingWhitespace", line = 1),
            ),
        )

        val actions = provider.provideCodeActions(params)

        assertEquals(3, actions.size) // 2 individual fixes + 1 fix-all

        // Check individual actions
        val semicolonAction = actions.find { it.title == "Remove unnecessary semicolon" }
        assertNotNull(semicolonAction)
        assertEquals(CodeActionKind.QuickFix, semicolonAction!!.kind)

        val whitespaceAction = actions.find { it.title == "Remove trailing whitespace" }
        assertNotNull(whitespaceAction)
        assertEquals(CodeActionKind.QuickFix, whitespaceAction!!.kind)

        // Check fix-all action
        val fixAllAction = actions.find { it.kind == CodeActionKind.SourceFixAll }
        assertNotNull(fixAllAction)
        assertTrue(fixAllAction!!.title.contains("Fix all"))
    }

    @Test
    fun `should ignore non-CodeNarc diagnostics`() {
        val params = createCodeActionParams(
            listOf(
                createDiagnostic("UnnecessarySemicolon", source = "eslint"),
                createDiagnostic("TrailingWhitespace", source = "other"),
            ),
        )

        val actions = provider.provideCodeActions(params)

        assertEquals(0, actions.size)
    }

    @Test
    fun `should handle empty diagnostic list`() {
        val params = createCodeActionParams(emptyList())

        val actions = provider.provideCodeActions(params)

        assertEquals(0, actions.size)
    }

    @Test
    fun `should handle unknown CodeNarc rules`() {
        val params = createCodeActionParams(
            listOf(
                createDiagnostic("UnknownRule"),
            ),
        )

        val actions = provider.provideCodeActions(params)

        assertEquals(0, actions.size)
    }

    @Test
    fun `should provide multiple actions for same rule when multiple fixers exist`() {
        // Register a second fixer for the same rule (for testing)
        val secondSemicolonFixer = UnnecessarySemicolonFixer()
        registry.register(secondSemicolonFixer)

        val params = createCodeActionParams(
            listOf(
                createDiagnostic("UnnecessarySemicolon", line = 0),
            ),
        )

        val actions = provider.provideCodeActions(params)

        // Should have 2 individual actions (from 2 fixers) + 1 fix-all
        assertEquals(3, actions.size)

        val individualActions = actions.filter { it.kind == CodeActionKind.QuickFix }
        assertEquals(2, individualActions.size)
    }

    @Test
    fun `should create fix-all action when multiple CodeNarc diagnostics exist`() {
        val params = createCodeActionParams(
            listOf(
                createDiagnostic("UnnecessarySemicolon", line = 0),
                createDiagnostic("UnnecessarySemicolon", line = 1),
                createDiagnostic("TrailingWhitespace", line = 2),
            ),
        )

        val actions = provider.provideCodeActions(params)

        val fixAllActions = actions.filter { it.kind == CodeActionKind.SourceFixAll }
        assertEquals(1, fixAllActions.size)

        val fixAllAction = fixAllActions.first()
        assertTrue(fixAllAction.title.contains("Fix all CodeNarc issues"))
        assertEquals(3, fixAllAction.diagnostics!!.size)
    }

    @Test
    fun `should not create fix-all action when no CodeNarc diagnostics exist`() {
        val params = createCodeActionParams(
            listOf(
                createDiagnostic("SomeRule", source = "eslint"),
            ),
        )

        val actions = provider.provideCodeActions(params)

        val fixAllActions = actions.filter { it.kind == CodeActionKind.SourceFixAll }
        assertEquals(0, fixAllActions.size)
    }

    @Test
    fun `should handle diagnostics with missing line in source`() {
        val params = createCodeActionParams(
            diagnostics = listOf(createDiagnostic("UnnecessarySemicolon", line = 10)), // Line out of bounds
            sourceLines = listOf("def x = 1;"), // Only one line
        )

        val actions = provider.provideCodeActions(params)

        // Should still create actions, but the fixer will handle the out-of-bounds gracefully
        assertTrue(actions.isNotEmpty())
    }

    @Test
    fun `should preserve diagnostic ordering in actions`() {
        val params = createCodeActionParams(
            listOf(
                createDiagnostic("TrailingWhitespace", line = 0),
                createDiagnostic("UnnecessarySemicolon", line = 1),
            ),
        )

        val actions = provider.provideCodeActions(params)

        val quickFixActions = actions.filter { it.kind == CodeActionKind.QuickFix }
        assertEquals(2, quickFixActions.size)

        // Actions should be ordered by fixer priority (TrailingWhitespace has priority 1,
        // UnnecessarySemicolon has priority 2)
        assertEquals("Remove trailing whitespace", quickFixActions[0].title)
        assertEquals("Remove unnecessary semicolon", quickFixActions[1].title)
    }

    private fun createCodeActionParams(
        diagnostics: List<Diagnostic>,
        sourceLines: List<String> = listOf("def x = 1; ", "def y = 2  "),
    ): CodeActionParams {
        // Create a test file for the provider to read
        val testFile = createTempFile(sourceLines)

        return CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(testFile.toURI().toString())
            range = Range(Position(0, 0), Position(sourceLines.size, 0))
            context = CodeActionContext().apply {
                this.diagnostics = diagnostics
            }
        }
    }

    private fun createTempFile(sourceLines: List<String>): java.io.File {
        val tempFile = kotlin.io.path.createTempFile(suffix = ".groovy").toFile()
        tempFile.writeText(sourceLines.joinToString("\n"))
        tempFile.deleteOnExit()
        return tempFile
    }

    private fun createDiagnostic(rule: String, line: Int = 0, source: String = "codenarc"): Diagnostic =
        Diagnostic().apply {
            range = Range(Position(line, 0), Position(line, 10))
            severity = DiagnosticSeverity.Warning
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(rule)
            this.source = source
            message = "Test message for $rule"
        }
}
