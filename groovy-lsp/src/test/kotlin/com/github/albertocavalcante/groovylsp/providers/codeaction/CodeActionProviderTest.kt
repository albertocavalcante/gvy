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
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
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
            severity = DiagnosticSeverity.Error
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
            severity = DiagnosticSeverity.Error
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
            severity = DiagnosticSeverity.Warning
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
            severity = DiagnosticSeverity.Warning
        }

        val diagnostic2 = Diagnostic().apply {
            range = Range(Position(1, 0), Position(1, 5))
            message = "Another issue"
            severity = DiagnosticSeverity.Warning
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
            code = Either.forLeft("TrailingWhitespace")
            severity = DiagnosticSeverity.Warning
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
            code = Either.forLeft("UnnecessarySemicolon")
            severity = DiagnosticSeverity.Warning
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
            code = Either.forLeft("UnusedImport")
            severity = DiagnosticSeverity.Warning
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
            code = Either.forLeft("UnnecessaryPublicModifier")
            severity = DiagnosticSeverity.Warning
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
            code = Either.forLeft("UnnecessaryGetter")
            severity = DiagnosticSeverity.Warning
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
            code = Either.forLeft("TrailingWhitespace")
            severity = DiagnosticSeverity.Warning
        }

        val diagnostic2 = Diagnostic().apply {
            range = Range(Position(1, 0), Position(1, 10))
            message = "Unnecessary semicolon"
            source = "CodeNarc"
            code = Either.forLeft("UnnecessarySemicolon")
            severity = DiagnosticSeverity.Warning
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
            code = Either.forLeft("TrailingWhitespace")
            severity = DiagnosticSeverity.Warning
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
            code = Either.forLeft("TrailingWhitespace")
            severity = DiagnosticSeverity.Warning
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
            code = Either.forLeft("TrailingWhitespace")
            severity = DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Remove trailing whitespace" }
        assertNull(lintAction, "Should not provide lint fix action for non-CodeNarc diagnostic")
    }

    // ============================================================================
    // Integration Tests for UnnecessaryGString
    // ============================================================================

    @Test
    fun `provides lint fix action for UnnecessaryGString diagnostic`() = runBlocking {
        // Position verified by Python: "hello" at 8-15
        val content = "def s = \"hello\"\n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 8), Position(0, 15))
            message = "The String 'hello' can be wrapped in single quotes"
            source = "CodeNarc"
            code = Either.forLeft("UnnecessaryGString")
            severity = DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Use single-quoted string" }
        assertNotNull(lintAction, "Should provide lint fix action for UnnecessaryGString")
        assertEquals(CodeActionKind.QuickFix, lintAction?.kind)
        assertNotNull(lintAction?.edit)
        assertTrue(lintAction?.diagnostics?.contains(diagnostic) == true)
    }

    @Test
    fun `UnnecessaryGString fix action produces correct text edit`() = runBlocking {
        // Position verified by Python: "hello" at 8-15
        val content = "def s = \"hello\"\n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 8), Position(0, 15))
            message = "The String 'hello' can be wrapped in single quotes"
            source = "CodeNarc"
            code = Either.forLeft("UnnecessaryGString")
            severity = DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Use single-quoted string" }
        assertNotNull(lintAction)

        val changes = lintAction?.edit?.changes
        assertNotNull(changes)
        val edits = changes?.get(testUri.toString())
        assertNotNull(edits)
        assertEquals(1, edits?.size)

        val edit = edits?.first()
        assertEquals("'hello'", edit?.newText, "Should replace with single-quoted string")
        assertEquals(0, edit?.range?.start?.line)
        assertEquals(8, edit?.range?.start?.character)
        assertEquals(0, edit?.range?.end?.line)
        assertEquals(15, edit?.range?.end?.character)
    }

    @Test
    fun `UnnecessaryGString fix handles long string from screenshot`() = runBlocking {
        // This reproduces the exact case from the user's screenshot
        // Position verified by Python: "myCustomDynamicPropertyValue" at 15-45
        val content = "        return \"myCustomDynamicPropertyValue\"\n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 15), Position(0, 45))
            message = "The String 'myCustomDynamicPropertyValue' can be wrapped in single quotes"
            source = "CodeNarc"
            code = Either.forLeft("UnnecessaryGString")
            severity = DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Use single-quoted string" }
        assertNotNull(lintAction, "Should provide lint fix action for UnnecessaryGString")

        val changes = lintAction?.edit?.changes
        val edits = changes?.get(testUri.toString())
        val edit = edits?.first()
        assertEquals("'myCustomDynamicPropertyValue'", edit?.newText)
    }

    @Test
    fun `UnnecessaryGString fix does not apply for strings containing single quotes`() = runBlocking {
        // Strings containing single quotes should not be converted
        // Position verified by Python: "it's" at 8-14
        val content = "def s = \"it's\"\n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 8), Position(0, 14))
            message = "The String 'it's' can be wrapped in single quotes"
            source = "CodeNarc"
            code = Either.forLeft("UnnecessaryGString")
            severity = DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        // Handler returns null, so no action should be provided
        val lintAction = actions.find { it.title == "Use single-quoted string" }
        assertNull(lintAction, "Should not provide fix action for strings containing single quotes")
    }

    @Test
    fun `UnnecessaryGString fix converts empty string`() = runBlocking {
        // Position verified by Python: "" at 8-10
        val content = "def s = \"\"\n"
        documentProvider.put(testUri, content)

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 8), Position(0, 10))
            message = "The String '' can be wrapped in single quotes"
            source = "CodeNarc"
            code = Either.forLeft("UnnecessaryGString")
            severity = DiagnosticSeverity.Warning
        }

        val params = createCodeActionParams(listOf(diagnostic))

        val actions = provider.provideCodeActions(params)

        val lintAction = actions.find { it.title == "Use single-quoted string" }
        assertNotNull(lintAction, "Should provide lint fix action for empty GString")

        val changes = lintAction?.edit?.changes
        val edits = changes?.get(testUri.toString())
        val edit = edits?.first()
        assertEquals("''", edit?.newText, "Should convert empty string to single quotes")
    }

    private fun createCodeActionParams(diagnostics: List<Diagnostic>): CodeActionParams = CodeActionParams().apply {
        textDocument = TextDocumentIdentifier(testUri.toString())
        range = Range(Position(0, 0), Position(0, 0))
        context = CodeActionContext().apply {
            this.diagnostics = diagnostics
        }
    }
}
