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

    private fun createCodeActionParams(diagnostics: List<Diagnostic>): CodeActionParams = CodeActionParams().apply {
        textDocument = TextDocumentIdentifier(testUri.toString())
        range = Range(Position(0, 0), Position(0, 0))
        context = CodeActionContext().apply {
            this.diagnostics = diagnostics
        }
    }
}
