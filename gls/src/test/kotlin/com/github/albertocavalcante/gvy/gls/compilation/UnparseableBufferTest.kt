// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.diagnostics.DiagnosticsOrchestrator
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsCollectionResult
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An open buffer whose compile never yields an AST never gets a symbol index. Navigation must not recompile
 * that exact text on every request; only a change to its inputs compiles it again (#1344).
 */
class UnparseableBufferTest {
    private val broken = URI.create("file:///Broken.groovy")
    private val malformed = "class Broken {\n    def x( {\n"
    private val parses = ConcurrentHashMap<String, AtomicInteger>()
    private val attempts = AtomicInteger()

    /** Makes the worker throw on the next parse of [broken], like a crashed or restarting worker. */
    private val failNext = AtomicBoolean(false)
    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    val counted = request.uri == broken
                    if (counted) {
                        attempts.incrementAndGet()
                        check(!failNext.getAndSet(false)) { "worker session lost" }
                    }
                    return delegate.parse(request).also {
                        if (counted) parses.computeIfAbsent(request.content) { AtomicInteger() }.incrementAndGet()
                    }
                }
            }
        },
    )
    private val ensurer = CompilationEnsurer(documents, service)

    private fun parsesOf(content: String) = parses[content]?.get() ?: 0

    private fun open(content: String) {
        service.onDocumentOpened(broken)
        documents.put(broken, content, 1)
    }

    private fun navigate(times: Int) = runBlocking { repeat(times) { ensurer.ensureAllCompiled() } }

    @Test
    fun `navigation compiles a never-parsing buffer once, not once per request`() {
        open(malformed)

        navigate(5)

        assertEquals(null, service.getAst(broken), "the fixture must not parse")
        assertEquals(1, parsesOf(malformed), "N navigation requests must compile the unparseable text once")
    }

    @Test
    fun `a worker failure is not remembered, so the next navigation compiles again`() {
        failNext.set(true)
        open(malformed)

        navigate(1) // the worker throws: no parse completed, nothing is learned about the text
        navigate(3) // the next request parses the text for real, and only that is remembered

        assertEquals(2, attempts.get(), "a transient worker failure must not leave navigation skipping the buffer")
        assertEquals(1, parsesOf(malformed))
    }

    @Test
    fun `an edit compiles the buffer again`() {
        open(malformed)
        navigate(2)

        val edited = "$malformed\n// still broken\n"
        documents.put(broken, edited, 2)
        navigate(2)

        assertEquals(1, parsesOf(malformed))
        assertEquals(1, parsesOf(edited), "new text is compiled once")
    }

    @Test
    fun `a configuration or worker change compiles it again`() {
        open(malformed)
        navigate(2)

        service.clearCaches() // what a workspace-model or worker change does
        navigate(2)

        assertEquals(2, parsesOf(malformed), "retired facts must not suppress the recompile")
    }

    @Test
    fun `invalidating the file compiles it again`() {
        open(malformed)
        navigate(2)

        service.invalidateCache(broken)
        navigate(2)

        assertEquals(2, parsesOf(malformed))
    }

    @Test
    fun `closing the buffer drops its marker, so each URI holds at most one`() {
        open(malformed)
        navigate(2)
        assertEquals(1, service.symbolIndexingService.unparseable.size)
        val edited = "$malformed\n// edited\n"
        documents.put(broken, edited, 2)
        navigate(2)
        assertEquals(1, service.symbolIndexingService.unparseable.size, "an edit replaces the marker")

        documents.remove(broken)
        val closed = assertNotNull(service.onClosedSourceChanged(broken))
        runBlocking { service.symbolIndexingService.refreshAfterClose(broken, closed) }

        assertEquals(0, service.symbolIndexingService.unparseable.size, "didClose removes it")
    }

    @Test
    fun `a buffer that later parses is indexed and ready`() {
        open(malformed)
        navigate(2)

        val fixed = "class Broken {\n    def x() {}\n}\n"
        documents.put(broken, fixed, 2)
        navigate(3)

        assertNotNull(service.getAst(broken), "the fixed buffer parses")
        val snapshot = assertNotNull(documents.getSnapshot(broken))
        assertTrue(service.symbolIndexingService.isIndexed(broken, snapshot), "and is indexed, so it is ready")
        assertEquals(1, parsesOf(fixed), "a ready document is not compiled again")
    }

    @Test
    fun `diagnostics are still published once and navigation answers without them failing`() {
        val publications = CopyOnWriteArrayList<PublishDiagnosticsParams>()
        val published = CountDownLatch(1)
        val client = mockk<LanguageClient>()
        every { client.publishDiagnostics(any()) } answers {
            publications.add(firstArg())
            published.countDown()
        }
        val providers = mockk<DiagnosticsService>()
        coEvery { providers.collectDiagnostics(any(), any(), any()) } returns
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val diagnostics =
                DiagnosticsOrchestrator(scope, service, providers, documents, ServerConfiguration(), { client })
            open(malformed)
            diagnostics.trigger(broken, malformed)
            assertTrue(published.await(WAIT_SECONDS, TimeUnit.SECONDS), "diagnostics were never published")
            val result = runBlocking { diagnostics.awaitCurrentDiagnostics(broken, 1) }
            assertTrue(result.failures.isEmpty(), "Providers must finish successfully: ${result.failures}")
            assertTrue(result.diagnostics.isNotEmpty(), "The terminal result retains the parse errors")

            navigate(1)
            val afterFirstNavigation = parsesOf(malformed)
            navigate(4)

            assertEquals(1, publications.size, "navigation must not republish or suppress diagnostics")
            assertTrue(publications.single().diagnostics.isNotEmpty(), "the parse errors are reported")
            assertEquals(afterFirstNavigation, parsesOf(malformed), "later navigation does not compile again")
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
