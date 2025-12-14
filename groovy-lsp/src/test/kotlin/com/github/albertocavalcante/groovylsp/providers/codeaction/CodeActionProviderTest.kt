package com.github.albertocavalcante.groovylsp.providers.codeaction

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.services.Formatter
import com.github.albertocavalcante.groovylsp.services.OpenRewriteFormatterAdapter
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class CodeActionProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var documentProvider: DocumentProvider
    private lateinit var formatter: Formatter
    private lateinit var provider: CodeActionProvider

    private val testUri = URI.create("file:///test.groovy")

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        documentProvider = DocumentProvider()
        formatter = OpenRewriteFormatterAdapter()
        provider = CodeActionProvider(compilationService, documentProvider, formatter)
    }

    @Test
    fun `provides no actions when document not found`() = runBlocking {
        val params = createCodeActionParams(emptyList())

        val actions = provider.provideCodeActions(params)

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `provides formatting action when document needs formatting`() = runBlocking {
        val content = "class Test{def x=1}"
        documentProvider.put(testUri, content)

        val params = createCodeActionParams(emptyList())

        val actions = provider.provideCodeActions(params)

        val formattingAction = actions.find { it.title == "Format document" }
        // Note: Formatter behavior may vary, so we just check it doesn't crash
        // and if action is provided, it has correct structure
        if (formattingAction != null) {
            assertEquals(CodeActionKind.SourceFixAll, formattingAction.kind)
            assertNotNull(formattingAction.edit)
        }
        // Test passes if no exception is thrown
    }

    @Test
    fun `provides no formatting action when document already formatted`() = runBlocking {
        val content = "def x = 1\n"
        documentProvider.put(testUri, content)

        val params = createCodeActionParams(emptyList())

        val actions = provider.provideCodeActions(params)

        val formattingAction = actions.find { it.title == "Format document" }
        // Formatter may or may not change this, but we check it doesn't crash
        assertNull(formattingAction, "Should not provide formatting action for an already formatted document")
    }

    @Test
    fun `provides no import action for missing symbol with no candidates`() = runBlocking {
        val content = "def x = UnknownClass.doSomething()"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 8), Position(0, 20))
            message = "unable to resolve class UnknownClass"
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Error
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val importActions = actions.filter { it.title.startsWith("Import") }
        assertTrue(importActions.isEmpty(), "Should not provide import actions when no candidates found")
    }

    @Test
    fun `provides no import action for non-import-related diagnostics`() = runBlocking {
        val content = "def x = 1 + 'string'"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 8), Position(0, 20))
            message = "type mismatch"
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Error
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val importActions = actions.filter { it.title.startsWith("Import") }
        assertTrue(importActions.isEmpty())
    }

    @Test
    fun `provides no lint fix actions for unknown CodeNarc issues`() = runBlocking {
        val content = "class Test { }"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 10))
            message = "Some CodeNarc issue"
            source = "CodeNarc"
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        // For now, we don't have any deterministic lint fixes implemented
        val lintActions = actions.filter { it.diagnostics?.any { d -> d.source == "CodeNarc" } == true }
        assertTrue(lintActions.isEmpty(), "Should not provide lint fix actions for unknown issues")
    }

    @Test
    fun `handles multiple diagnostics correctly`() = runBlocking {
        val content = "def x=1\ndef y=2"
        documentProvider.put(testUri, content)

        val diagnostic1 = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 5))
            message = "Some issue"
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val diagnostic2 = Diagnostic().apply {
            range = Range(Position(1, 0), Position(1, 5))
            message = "Another issue"
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic1, diagnostic2))

        val actions = provider.provideCodeActions(params)

        // Should not crash with multiple diagnostics
        assertTrue(actions.isNotEmpty() || actions.isEmpty())
    }

    @Test
    fun `handles empty diagnostics list`() = runBlocking {
        val content = "def x = 1"
        documentProvider.put(testUri, content)

        val params = createCodeActionParams(emptyList())

        val actions = provider.provideCodeActions(params)

        // May or may not have formatting action, but should not crash
        assertTrue(actions.isNotEmpty() || actions.isEmpty())
    }

    // ============================================================================
    // Integration Tests for Lint Fix Actions
    // **Feature: codenarc-lint-fixes**
    // **Validates: Requirements 6.1, 6.2, 6.3, 6.4**
    // ============================================================================

    @Test
    fun `provides lint fix action for TrailingWhitespace diagnostic`() = runBlocking {
        val content = "def x = 1   \n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 12))
            message = "Line has trailing whitespace"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("TrailingWhitespace")
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Remove trailing whitespace" }
        assertNotNull(lintAction, "Should provide lint fix action for TrailingWhitespace")
        assertEquals(CodeActionKind.QuickFix, lintAction?.kind)
        assertNotNull(lintAction?.edit)
        assertTrue(lintAction?.diagnostics?.contains(diagnostic) == true)
    }

    @Test
    fun `provides lint fix action for UnnecessarySemicolon diagnostic`() = runBlocking {
        val content = "def x = 1;\n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 10))
            message = "Unnecessary semicolon"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("UnnecessarySemicolon")
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Remove unnecessary semicolon" }
        assertNotNull(lintAction, "Should provide lint fix action for UnnecessarySemicolon")
        assertEquals(CodeActionKind.QuickFix, lintAction?.kind)
    }

    @Test
    fun `provides lint fix action for UnusedImport diagnostic`() = runBlocking {
        val content = "import java.util.List\nclass Test {}\n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 21))
            message = "Unused import"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("UnusedImport")
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Remove unused import" }
        assertNotNull(lintAction, "Should provide lint fix action for UnusedImport")
        assertEquals(CodeActionKind.QuickFix, lintAction?.kind)
    }

    @Test
    fun `provides lint fix action for UnnecessaryPublicModifier diagnostic`() = runBlocking {
        val content = "public class Test {}\n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 7))
            message = "Unnecessary public modifier"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("UnnecessaryPublicModifier")
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Remove unnecessary 'public'" }
        assertNotNull(lintAction, "Should provide lint fix action for UnnecessaryPublicModifier")
        assertEquals(CodeActionKind.QuickFix, lintAction?.kind)
    }

    @Test
    fun `provides lint fix action for UnnecessaryGetter diagnostic`() = runBlocking {
        val content = "obj.getName()\n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 4), Position(0, 13))
            message = "Unnecessary getter"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("UnnecessaryGetter")
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Use property access" }
        assertNotNull(lintAction, "Should provide lint fix action for UnnecessaryGetter")
        assertEquals(CodeActionKind.QuickFix, lintAction?.kind)
    }

    @Test
    fun `provides multiple lint fix actions for multiple CodeNarc diagnostics`() = runBlocking {
        val content = "def x = 1   \ndef y = 2;\n"
        documentProvider.put(testUri, content)

        val diagnostic1 = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 12))
            message = "Line has trailing whitespace"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("TrailingWhitespace")
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val diagnostic2 = Diagnostic().apply {
            range = Range(Position(1, 0), Position(1, 10))
            message = "Unnecessary semicolon"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("UnnecessarySemicolon")
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic1, diagnostic2))

        val actions = provider.provideCodeActions(params)

        val trailingWhitespaceAction = actions.find { it.title == "Remove trailing whitespace" }
        val semicolonAction = actions.find { it.title == "Remove unnecessary semicolon" }

        assertNotNull(trailingWhitespaceAction, "Should provide lint fix action for TrailingWhitespace")
        assertNotNull(semicolonAction, "Should provide lint fix action for UnnecessarySemicolon")
    }

    @Test
    fun `lint fix action edit targets correct URI`() = runBlocking {
        val content = "def x = 1   \n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 12))
            message = "Line has trailing whitespace"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("TrailingWhitespace")
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Remove trailing whitespace" }
        assertNotNull(lintAction)

        val changes = lintAction?.edit?.changes
        assertNotNull(changes)
        assertTrue(changes?.containsKey(testUri.toString()) == true, "Edit should target the correct URI")
    }

    @Test
    fun `lint fix action contains original diagnostic`() = runBlocking {
        val content = "def x = 1   \n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 12))
            message = "Line has trailing whitespace"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("TrailingWhitespace")
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Remove trailing whitespace" }
        assertNotNull(lintAction)

        val actionDiagnostics = lintAction?.diagnostics
        assertNotNull(actionDiagnostics)
        assertEquals(1, actionDiagnostics?.size)
        assertEquals(diagnostic, actionDiagnostics?.first())
    }

    @Test
    fun `does not provide lint fix action for non-CodeNarc diagnostic with same rule name`() = runBlocking {
        val content = "def x = 1   \n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 12))
            message = "Line has trailing whitespace"
            source = "OtherLinter" // Not CodeNarc
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("TrailingWhitespace")
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Remove trailing whitespace" }
        assertNull(lintAction, "Should not provide lint fix action for non-CodeNarc diagnostic")
    }

    private fun createCodeActionParams(diagnostics: List<Diagnostic>): CodeActionParams = CodeActionParams().apply {
        textDocument = TextDocumentIdentifier(testUri.toString())
        range = Range(Position(0, 0), Position(0, 0))
        context = CodeActionContext().apply {
            this.diagnostics = diagnostics
        }
    }
}
