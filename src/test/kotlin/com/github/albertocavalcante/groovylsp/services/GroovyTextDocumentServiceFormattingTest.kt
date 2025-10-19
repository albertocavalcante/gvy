package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GroovyTextDocumentServiceFormattingTest {

    private val uri = URI.create("file:///formatter-test.groovy")
    private val compilationService = GroovyCompilationService()
    private val client = RecordingLanguageClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @AfterEach
    fun tearDown() {
        coroutineScope.cancel()
    }

    @Test
    fun `formatting returns edit when formatter changes content`() {
        client.telemetryEvents.clear()
        val documentProvider = DocumentProvider().apply { put(uri, "def x=1") }
        val formatter = TestFormatter { "def x = 1" }
        val service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { client },
            documentProvider = documentProvider,
            formatter = formatter,
        )

        val params = formattingParams()

        val edits = service.formatting(params).get(1, TimeUnit.SECONDS)

        assertEquals(1, edits.size)
        val telemetry = client.telemetryEvents.single() as FormatterTelemetryEvent
        assertEquals(FormatterStatus.SUCCESS, telemetry.status)
        assertFalse(telemetry.ignoredOptions)
    }

    @Test
    fun `formatting returns empty list when content unchanged`() {
        client.telemetryEvents.clear()
        val documentProvider = DocumentProvider().apply { put(uri, "def y = 2") }
        val formatter = TestFormatter { it }
        val service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { client },
            documentProvider = documentProvider,
            formatter = formatter,
        )

        val params = formattingParams()

        val edits = service.formatting(params).get(1, TimeUnit.SECONDS)

        assertTrue(edits.isEmpty())
        val telemetry = client.telemetryEvents.single() as FormatterTelemetryEvent
        assertEquals(FormatterStatus.NO_OP, telemetry.status)
    }

    @Test
    fun `formatting returns empty list when document missing`() {
        client.telemetryEvents.clear()
        val documentProvider = DocumentProvider()
        val formatter = TestFormatter { it }
        val service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { client },
            documentProvider = documentProvider,
            formatter = formatter,
        )

        val edits = service.formatting(formattingParams()).get(1, TimeUnit.SECONDS)

        assertTrue(edits.isEmpty())
        val telemetry = client.telemetryEvents.single() as FormatterTelemetryEvent
        assertEquals(FormatterStatus.NOT_FOUND, telemetry.status)
    }

    @Test
    fun `formatting reports error telemetry when formatter throws`() {
        client.telemetryEvents.clear()
        val documentProvider = DocumentProvider().apply { put(uri, "println 'hi'") }
        val formatter = TestFormatter { throw IllegalStateException("boom") }
        val service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { client },
            documentProvider = documentProvider,
            formatter = formatter,
        )

        val edits = service.formatting(formattingParams()).get(1, TimeUnit.SECONDS)

        assertTrue(edits.isEmpty())
        val telemetry = client.telemetryEvents.single() as FormatterTelemetryEvent
        assertEquals(FormatterStatus.ERROR, telemetry.status)
        assertEquals("boom", telemetry.errorMessage)
    }

    @Test
    fun `formatting telemetry indicates ignored options`() {
        client.telemetryEvents.clear()
        val documentProvider = DocumentProvider().apply { put(uri, "def z=3") }
        val formatter = TestFormatter { "def z = 3" }
        val service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { client },
            documentProvider = documentProvider,
            formatter = formatter,
        )

        val params = formattingParams().apply {
            options = FormattingOptions(8, false).apply {
                setTrimTrailingWhitespace(true)
                setInsertFinalNewline(true)
            }
        }

        service.formatting(params).get(1, TimeUnit.SECONDS)

        val telemetry = client.telemetryEvents.single() as FormatterTelemetryEvent
        assertTrue(telemetry.ignoredOptions)
    }

    private fun formattingParams(): DocumentFormattingParams = DocumentFormattingParams().apply {
        textDocument = TextDocumentIdentifier(uri.toString())
        options = FormattingOptions(4, true)
    }

    private class TestFormatter(private val delegate: (String) -> String) : Formatter {
        override fun format(text: String): String = delegate(text)
    }

    @Suppress("EmptyFunctionBlock")
    private class RecordingLanguageClient : LanguageClient {
        val telemetryEvents = mutableListOf<Any>()

        override fun telemetryEvent(`object`: Any) {
            telemetryEvents.add(`object`)
        }

        override fun publishDiagnostics(params: PublishDiagnosticsParams) = Unit

        override fun showMessage(params: MessageParams) = Unit

        override fun showMessageRequest(
            requestParams: ShowMessageRequestParams,
        ): CompletableFuture<MessageActionItem?> = CompletableFuture.completedFuture(null)

        override fun logMessage(params: MessageParams) = Unit
    }
}
