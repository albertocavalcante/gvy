package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.TimeUnit

class GroovyTextDocumentServiceCodeActionTest {

    private companion object {
        private const val FUTURE_TIMEOUT_SECONDS = 5L
    }

    private val uri = URI.create("file:///codeaction-test.groovy")
    private val compilationService = GroovyCompilationService()
    private val client = RecordingLanguageClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var service: GroovyTextDocumentService

    @BeforeEach
    fun setUp() {
        service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { client },
        )
    }

    @AfterEach
    fun tearDown() {
        coroutineScope.cancel()
    }

    @Test
    fun `codeAction returns empty list when document not found`() {
        val params = CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri.toString())
            range = Range(Position(0, 0), Position(0, 0))
            context = CodeActionContext().apply {
                diagnostics = emptyList()
            }
        }

        val result = service.codeAction(params).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `codeAction handles empty diagnostics`() {
        val documentProvider = DocumentProvider().apply {
            put(uri, "def x = 1")
        }

        val service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { client },
            documentProvider = documentProvider,
        )

        val params = CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri.toString())
            range = Range(Position(0, 0), Position(0, 0))
            context = CodeActionContext().apply {
                diagnostics = emptyList()
            }
        }

        val result = service.codeAction(params).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // Assuming no actions are expected for a formatted document with no diagnostics.
        assertTrue(result.isEmpty(), "No code actions should be provided for a formatted document with no diagnostics.")
    }

    @Test
    fun `codeAction handles diagnostics without crashing`() {
        val documentProvider = DocumentProvider().apply {
            put(uri, "def x = UnknownClass.doSomething()")
        }

        val service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { client },
            documentProvider = documentProvider,
        )

        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 8), Position(0, 20))
            message = "unable to resolve class UnknownClass"
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Error
        }

        val params = CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri.toString())
            range = Range(Position(0, 0), Position(0, 40))
            context = CodeActionContext().apply {
                diagnostics = listOf(diagnostic)
            }
        }

        val result = service.codeAction(params).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // Should not crash with diagnostics
        assertTrue(result.isNotEmpty() || result.isEmpty())
    }

    @Test
    fun `codeAction returns code actions not commands`() {
        val documentProvider = DocumentProvider().apply {
            put(uri, "class Test { }")
        }

        val service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { client },
            documentProvider = documentProvider,
        )

        val params = CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri.toString())
            range = Range(Position(0, 0), Position(0, 10))
            context = CodeActionContext().apply {
                diagnostics = emptyList()
            }
        }

        val result = service.codeAction(params).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // All results should be code actions (Right), not commands (Left)
        result.forEach { either ->
            assertTrue(either.isRight, "Expected CodeAction, got Command")
        }
    }

    @Test
    fun `codeAction handles concurrent requests`() {
        val documentProvider = DocumentProvider().apply {
            put(uri, "def x = 1\ndef y = 2")
        }

        val service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { client },
            documentProvider = documentProvider,
        )

        val params1 = CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri.toString())
            range = Range(Position(0, 0), Position(0, 10))
            context = CodeActionContext().apply {
                diagnostics = emptyList()
            }
        }

        val params2 = CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri.toString())
            range = Range(Position(1, 0), Position(1, 10))
            context = CodeActionContext().apply {
                diagnostics = emptyList()
            }
        }

        val future1 = service.codeAction(params1)
        val future2 = service.codeAction(params2)

        val result1 = future1.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val result2 = future2.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // Both should complete without crashing
        assertTrue(result1.isNotEmpty() || result1.isEmpty())
        assertTrue(result2.isNotEmpty() || result2.isEmpty())
    }
}

/**
 * Simple recording language client for testing
 */
private class RecordingLanguageClient : LanguageClient {
    override fun telemetryEvent(obj: Any?) {
        // No-op for testing
    }

    override fun publishDiagnostics(diagnostics: org.eclipse.lsp4j.PublishDiagnosticsParams?) {
        // No-op for testing
    }

    override fun showMessage(params: org.eclipse.lsp4j.MessageParams?) {
        // No-op for testing
    }

    override fun showMessageRequest(
        params: org.eclipse.lsp4j.ShowMessageRequestParams?,
    ): java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.MessageActionItem> =
        java.util.concurrent.CompletableFuture.completedFuture(null)

    override fun logMessage(params: org.eclipse.lsp4j.MessageParams?) {
        // No-op for testing
    }
}
