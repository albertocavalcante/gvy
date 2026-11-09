package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SymbolIndexingService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticsOrchestratorSnapshotTest {
    private val uri = URI.create("file:///workspace/Example.groovy")
    private val content = "class Example {}"

    private class Fixture(scope: CoroutineScope, val uri: URI, content: String, version: Int = 7) {
        val documents = DocumentProvider().apply { put(uri, content, version) }
        val compilation = mockk<GroovyCompilationService>()
        val diagnostics = mockk<DiagnosticsService>()
        val publications = mutableListOf<PublishDiagnosticsParams>()
        val client = mockk<LanguageClient>()
        var beforeClientLookup: (Int) -> Unit = {}
        private val lookups = AtomicInteger()
        val orchestrator: DiagnosticsOrchestrator

        init {
            val result = mockk<CompilationResult>()
            every { result.diagnostics } returns listOf(diagnostic("parser"))
            coEvery { compilation.compileAsync(any(), uri, any()) } returns CompletableDeferred(result)
            coEvery { diagnostics.getDiagnostics(uri, any()) } returns listOf(diagnostic("provider"))
            every { client.publishDiagnostics(capture(publications)) } just Runs
            orchestrator = DiagnosticsOrchestrator(
                scope,
                compilation,
                diagnostics,
                documents,
                ServerConfiguration(),
                {
                    beforeClientLookup(lookups.incrementAndGet())
                    client
                },
            )
        }

        companion object {
            fun diagnostic(message: String) = Diagnostic().apply { this.message = message }
        }
    }

    private class ServiceFixture(scope: CoroutineScope, uri: URI) {
        val documents = DocumentProvider()
        val compilation = mockk<GroovyCompilationService>(relaxed = true)
        val indexing = mockk<SymbolIndexingService>(relaxed = true)
        val client = mockk<LanguageClient>()
        val publications = mutableListOf<PublishDiagnosticsParams>()
        var beforeClientLookup: () -> Unit = {}
        val service: GroovyTextDocumentService

        init {
            every { compilation.symbolIndexingService } returns indexing
            var generation = 0
            coEvery { compilation.compileAsync(any(), uri, any()) } answers {
                val result = mockk<CompilationResult>()
                every { result.diagnostics } returns listOf(Fixture.diagnostic("parse ${++generation}"))
                CompletableDeferred(result)
            }
            every { client.publishDiagnostics(capture(publications)) } just Runs
            val base = ServerConfiguration()
            service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(
                    documentProvider = documents,
                    serverConfiguration = base.copy(
                        diagnosticConfig = base.diagnosticConfig.copy(
                            disabledProviders = setOf("codenarc", "custom-rules", "unused-imports"),
                        ),
                    ),
                    client = {
                        beforeClientLookup()
                        client
                    },
                ),
            )
        }
    }

    @Test
    fun `parser and merged publications carry their captured document version`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)

        fixture.orchestrator.trigger(uri, content)
        advanceUntilIdle()

        assertEquals(listOf(7, 7), fixture.publications.map { it.version })
        assertEquals(listOf("parser"), fixture.publications.first().diagnostics.map { it.message })
        assertEquals(listOf("parser", "provider"), fixture.publications.last().diagnostics.map { it.message })
    }

    @Test
    fun `parser publication rejects an edit accepted during client lookup`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        fixture.beforeClientLookup = { lookup ->
            if (lookup == 1) fixture.documents.update(uri, "class Changed {}", 8)
        }

        fixture.orchestrator.trigger(uri, content)
        advanceUntilIdle()

        assertTrue(fixture.publications.isEmpty(), "An accepted edit retires the old parser and provider results")
    }

    @Test
    fun `merged publication rejects an edit accepted after parser publication`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        fixture.beforeClientLookup = { lookup ->
            if (lookup == 2) fixture.documents.update(uri, "class Changed {}", 8)
        }

        fixture.orchestrator.trigger(uri, content)
        advanceUntilIdle()

        assertEquals(1, fixture.publications.size)
        assertEquals(listOf("parser"), fixture.publications.single().diagnostics.map { it.message })
    }

    @Test
    fun `publication rejects a closed document even after its cancellation check`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        fixture.beforeClientLookup = { lookup -> if (lookup == 1) fixture.documents.remove(uri) }

        fixture.orchestrator.trigger(uri, content)
        advanceUntilIdle()

        assertTrue(fixture.publications.isEmpty())
    }

    @Test
    fun `same text and version on reopen cannot inherit the retired publication`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        fixture.beforeClientLookup = { lookup ->
            if (lookup == 1) {
                fixture.documents.remove(uri)
                fixture.documents.put(uri, content, 7)
            }
        }

        fixture.orchestrator.trigger(uri, content)
        advanceUntilIdle()

        assertTrue(fixture.publications.isEmpty(), "Equal snapshot values must not erase document incarnation")
    }

    @Test
    fun `cancellation during client lookup suppresses the pending publication`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        fixture.beforeClientLookup = { lookup -> if (lookup == 1) fixture.orchestrator.cancelAndRemove(uri) }

        fixture.orchestrator.trigger(uri, content)
        advanceUntilIdle()

        assertTrue(fixture.publications.isEmpty())
    }

    @Test
    fun `a queued job cannot adopt a document accepted after its trigger`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)

        fixture.orchestrator.trigger(uri, content)
        fixture.documents.update(uri, "class Changed {}", 8)
        advanceUntilIdle()

        assertTrue(fixture.publications.isEmpty())
    }

    @Test
    fun `an obsolete text trigger cannot attach its result to the current version`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        fixture.documents.update(uri, "class Changed {}", 8)

        fixture.orchestrator.trigger(uri, content)
        advanceUntilIdle()

        assertTrue(fixture.publications.isEmpty())
        coVerify(exactly = 0) { fixture.compilation.compileAsync(any(), any(), any()) }
    }

    @Test
    fun `configuration replacement during client lookup retires the old publication`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        fixture.beforeClientLookup = { lookup ->
            if (lookup == 1) {
                fixture.orchestrator.updateConfiguration(
                    ServerConfiguration(maxNumberOfProblems = 0),
                    fixture.diagnostics,
                )
            }
        }

        fixture.orchestrator.trigger(uri, content)
        advanceUntilIdle()

        assertTrue(fixture.publications.isNotEmpty(), "Current configuration must still publish")
        assertTrue(fixture.publications.all { it.diagnostics.isEmpty() })
    }

    @Test
    fun `text document close clears diagnostics without a trailing retired publication`(): Unit = runTest {
        val fixture = ServiceFixture(this, uri)
        var closeOnLookup = true
        fixture.beforeClientLookup = {
            if (closeOnLookup) {
                closeOnLookup = false
                fixture.service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri.toString())))
            }
        }

        fixture.service.didOpen(open())
        advanceUntilIdle()

        assertNull(fixture.documents.getSnapshot(uri))
        assertEquals(1, fixture.publications.size)
        assertTrue(fixture.publications.single().diagnostics.isEmpty())
    }

    @Test
    fun `an accepted version only change publishes diagnostics for that version`(): Unit = runTest {
        val fixture = ServiceFixture(this, uri)

        fixture.service.didOpen(open())
        fixture.service.didChange(
            DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri.toString(), 2), emptyList()),
        )
        advanceUntilIdle()

        assertEquals(listOf(2), fixture.publications.map { it.version })
        assertEquals(listOf("parse 1"), fixture.publications.single().diagnostics.map { it.message })
    }

    @Test
    fun `a reopen during close client lookup cannot be cleared by the retired close`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val fixture = ServiceFixture(scope, uri)
            fixture.service.didOpen(open())
            var reopenOnLookup = true
            fixture.beforeClientLookup = {
                if (reopenOnLookup) {
                    reopenOnLookup = false
                    fixture.service.didOpen(open())
                }
            }

            fixture.service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri.toString())))

            assertEquals(content, fixture.documents.get(uri))
            assertEquals("parse 2", fixture.publications.last().diagnostics.singleOrNull()?.message)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an edit during close client lookup still closes the same incarnation`(): Unit = runTest {
        val fixture = ServiceFixture(this, uri)
        fixture.service.didOpen(open())
        var editOnLookup = true
        fixture.beforeClientLookup = {
            if (editOnLookup) {
                editOnLookup = false
                fixture.documents.update(uri, "class Edited {}", 2)
            }
        }

        fixture.service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri.toString())))
        advanceUntilIdle()

        assertNull(fixture.documents.getSnapshot(uri))
        assertEquals(1, fixture.publications.size)
        assertTrue(fixture.publications.single().diagnostics.isEmpty())
    }

    @Test
    fun `a failing client lookup still closes and refreshes the document from disk`(): Unit = runTest {
        val fixture = ServiceFixture(this, uri)
        fixture.service.didOpen(open())
        fixture.beforeClientLookup = { error("Client lookup failed") }

        assertFailsWith<IllegalStateException> {
            fixture.service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri.toString())))
        }

        advanceUntilIdle()
        assertNull(fixture.documents.getSnapshot(uri))
        assertTrue(fixture.publications.isEmpty())
        coVerify(exactly = 1) { fixture.indexing.refreshAfterClose(uri, any()) }
        coVerify(exactly = 1) { fixture.compilation.refreshSemanticFromDisk(listOf(uri)) }
    }

    @Test
    fun `a failed close notification still refreshes both indices from disk`(): Unit = runTest {
        val fixture = ServiceFixture(this, uri)
        fixture.service.didOpen(open())
        every { fixture.client.publishDiagnostics(any()) } throws IllegalStateException("Send failed")

        assertFailsWith<IllegalStateException> {
            fixture.service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri.toString())))
        }

        advanceUntilIdle()
        assertNull(fixture.documents.getSnapshot(uri))
        coVerify(exactly = 1) { fixture.indexing.refreshAfterClose(uri, any()) }
        coVerify(exactly = 1) { fixture.compilation.refreshSemanticFromDisk(listOf(uri)) }
    }

    @Test
    fun `a failed close notification still cancels the retired diagnostics job`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        val compilation = CompletableDeferred<CompilationResult>()
        coEvery { fixture.compilation.compileAsync(any(), uri, any()) } returns compilation
        fixture.orchestrator.trigger(uri, content)
        runCurrent()
        val retired = assertNotNull(fixture.orchestrator.getDiagnosticJob(uri))
        every { fixture.client.publishDiagnostics(any()) } throws IllegalStateException("Send failed")

        assertFailsWith<IllegalStateException> { fixture.orchestrator.close(uri) }

        assertNull(fixture.documents.getSnapshot(uri))
        assertTrue(retired.isCancelled)
    }

    @Test
    fun `configuration and document updates do not wait for another thread resolving the client`(): Unit = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = Executors.newSingleThreadExecutor()
        val resolvingClient = CountDownLatch(1)
        val releaseClient = CountDownLatch(1)
        try {
            val fixture = Fixture(scope, uri, content)
            val blockFirstLookup = AtomicBoolean(true)
            fixture.beforeClientLookup = {
                if (blockFirstLookup.compareAndSet(true, false)) {
                    resolvingClient.countDown()
                    check(releaseClient.await(10, TimeUnit.SECONDS))
                }
            }
            fixture.orchestrator.trigger(uri, content)
            assertTrue(resolvingClient.await(10, TimeUnit.SECONDS))

            val update = executor.submit {
                fixture.documents.update(uri, "class Changed {}", 8)
                fixture.orchestrator.updateConfiguration(
                    ServerConfiguration(maxNumberOfProblems = 0),
                    fixture.diagnostics,
                )
            }
            // Client resolution is deliberately stopped. State updates must finish before it resumes.
            update.get(5, TimeUnit.SECONDS)
            releaseClient.countDown()
            fixture.orchestrator.awaitDiagnostics(uri)

            assertTrue(fixture.publications.isNotEmpty())
            assertTrue(fixture.publications.all { it.version == 8 && it.diagnostics.isEmpty() })
        } finally {
            releaseClient.countDown()
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private fun open() = DidOpenTextDocumentParams(TextDocumentItem(uri.toString(), "groovy", 1, content))
}
