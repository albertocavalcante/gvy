package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.CancellationException
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
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class GroovyTextDocumentServiceFormattingTest {

    private companion object {
        private const val FUTURE_TIMEOUT_SECONDS = 5L
    }

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

        val edits = service.formatting(params).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

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

        val edits = service.formatting(params).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

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

        val edits = service.formatting(formattingParams()).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

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

        val edits = service.formatting(formattingParams()).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(edits.isEmpty())
        val telemetry = client.telemetryEvents.single() as FormatterTelemetryEvent
        assertEquals(FormatterStatus.ERROR, telemetry.status)
        assertEquals("boom", telemetry.errorMessage)
    }

    @Test
    fun `formatting reports exception type when formatter throws without message`() {
        client.telemetryEvents.clear()
        val documentProvider = DocumentProvider().apply { put(uri, "println 'hi'") }
        val formatter = TestFormatter { throw IllegalStateException() }
        val service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { client },
            documentProvider = documentProvider,
            formatter = formatter,
        )

        val edits = service.formatting(formattingParams()).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(edits.isEmpty())
        val telemetry = client.telemetryEvents.single() as FormatterTelemetryEvent
        assertEquals(FormatterStatus.ERROR, telemetry.status)
        assertEquals("IllegalStateException", telemetry.errorMessage)
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
                isTrimTrailingWhitespace = true
                isInsertFinalNewline = true
            }
        }

        service.formatting(params).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val telemetry = client.telemetryEvents.single() as FormatterTelemetryEvent
        assertTrue(telemetry.ignoredOptions)
    }

    @Test
    fun `formatting handles concurrent requests without telemetry contamination`() {
        val uriOne = URI.create("file:///formatter-test-1.groovy")
        val uriTwo = URI.create("file:///formatter-test-2.groovy")
        val localClient = RecordingLanguageClient()
        val localScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val documentProvider = DocumentProvider().apply {
            put(uriOne, "def a = 1")
            put(uriTwo, "def b = 2")
        }
        val formatter = TestFormatter { it }
        val service = GroovyTextDocumentService(
            coroutineScope = localScope,
            compilationService = compilationService,
            client = { localClient },
            documentProvider = documentProvider,
            formatter = formatter,
        )

        try {
            val futures = listOf(
                service.formatting(formattingParams(uriOne)),
                service.formatting(formattingParams(uriTwo)),
            )
            futures.forEach { future ->
                val edits = future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertTrue(edits.isEmpty())
            }

            val telemetryEvents = localClient.telemetryEvents.map { it as FormatterTelemetryEvent }
            assertEquals(2, telemetryEvents.size)
            val uris = telemetryEvents.map { it.uri }.toSet()
            assertTrue(uris.contains(uriOne.toString()))
            assertTrue(uris.contains(uriTwo.toString()))
        } finally {
            localScope.cancel()
        }
    }

    @Test
    fun `formatting succeeds when client unavailable`() {
        val documentProvider = DocumentProvider().apply { put(uri, "def x=1") }
        val formatter = TestFormatter { "def x = 1" }
        val service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            client = { null },
            documentProvider = documentProvider,
            formatter = formatter,
        )

        val edits = service.formatting(formattingParams()).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertEquals(1, edits.size)
    }

    @Test
    fun `formatting propagates cancellation without telemetry`() {
        client.telemetryEvents.clear()
        val job = SupervisorJob()
        val localScope = CoroutineScope(Dispatchers.Default + job)
        val documentProvider = DocumentProvider().apply { put(uri, "def x=1") }
        val formatter = TestFormatter { "def x = 1" }
        val localClient = RecordingLanguageClient()
        val service = GroovyTextDocumentService(
            coroutineScope = localScope,
            compilationService = compilationService,
            client = { localClient },
            documentProvider = documentProvider,
            formatter = formatter,
        )

        job.cancel()

        val future = service.formatting(formattingParams())
        assertThrows<CancellationException> {
            future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertTrue(localClient.telemetryEvents.isEmpty())
        localScope.cancel()
    }

    private fun formattingParams(targetUri: URI = uri): DocumentFormattingParams = DocumentFormattingParams().apply {
        textDocument = TextDocumentIdentifier(targetUri.toString())
        options = FormattingOptions(4, true)
    }

    private class TestFormatter(private val delegate: (String) -> String) : Formatter {
        override fun format(text: String): String = delegate(text)
    }

    @Suppress("EmptyFunctionBlock")
    private class RecordingLanguageClient : LanguageClient {
        val telemetryEvents = CopyOnWriteArrayList<Any>()

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
