package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsCollectionResult
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsOrchestratorTest {

    private val testUri = URI.create("file:///test/Example.groovy")
    private val testContent = "class Example { }"

    @AfterEach
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `problem limit bounds both parser and merged diagnostic publications`(): Unit = runTest {
        val compilation = mockk<GroovyCompilationService>()
        val diagnostics = mockk<DiagnosticsService>()
        val client = mockk<LanguageClient>(relaxed = true)
        val parser =
            listOf(Diagnostic().apply { message = "syntax one" }, Diagnostic().apply { message = "syntax two" })
        val extra = listOf(Diagnostic().apply { message = "lint one" })
        val result = mockk<CompilationResult>()
        every { result.diagnostics } returns parser
        every { result.parseCompleted } returns true
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnostics.collectDiagnostics(any(), any()) } returns
            DiagnosticsCollectionResult(extra, emptyList(), emptyList())
        val publications = mutableListOf<PublishDiagnosticsParams>()
        every { client.publishDiagnostics(capture(publications)) } just Runs
        val orchestrator = DiagnosticsOrchestrator(
            this,
            compilation,
            diagnostics,
            openDocuments(),
            ServerConfiguration(maxNumberOfProblems = 1),
            { client },
        )

        orchestrator.trigger(testUri, testContent)
        orchestrator.awaitDiagnostics(testUri)

        assertEquals(2, publications.size)
        assertTrue(publications.all { it.diagnostics.size == 1 })
        assertEquals("syntax one", publications.last().diagnostics.single().message)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `settings refresh prevents an old provider from publishing after replacement`(): Unit = runTest {
        val compilation = mockk<GroovyCompilationService>()
        val oldService = mockk<DiagnosticsService>()
        val newService = mockk<DiagnosticsService>()
        val client = mockk<LanguageClient>(relaxed = true)
        val result = mockk<CompilationResult>()
        every { result.diagnostics } returns emptyList()
        every { result.parseCompleted } returns true
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        val release = CompletableDeferred<Unit>()
        coEvery { oldService.collectDiagnostics(any(), any()) } coAnswers {
            withContext(NonCancellable) { release.await() }
            DiagnosticsCollectionResult(listOf(Diagnostic().apply { message = "obsolete" }), emptyList(), emptyList())
        }
        coEvery { newService.collectDiagnostics(any(), any()) } returns
            DiagnosticsCollectionResult(listOf(Diagnostic().apply { message = "current" }), emptyList(), emptyList())
        val publications = mutableListOf<PublishDiagnosticsParams>()
        every { client.publishDiagnostics(capture(publications)) } just Runs
        val documents = DocumentProvider().apply { put(testUri, testContent, 1) }
        val orchestrator =
            DiagnosticsOrchestrator(this, compilation, oldService, documents, ServerConfiguration(), { client })
        orchestrator.trigger(testUri, testContent)
        runCurrent()
        assertEquals(1, publications.size)

        orchestrator.updateConfiguration(ServerConfiguration(maxNumberOfProblems = 1), newService)
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals("current", publications.last().diagnostics.single().message)
        assertTrue(publications.none { publication -> publication.diagnostics.any { it.message == "obsolete" } })
    }

    // ==================== Test: trigger cancels existing job ====================

    @Test
    fun `trigger cancels existing job for same URI`(): Unit = runTest {
        // Given: compilations that stay pending until the test releases them
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = openDocuments()
        val client = mockk<LanguageClient>()

        val firstCompilation = CompletableDeferred<CompilationResult>()
        val secondCompilation = CompletableDeferred<CompilationResult>()
        coEvery { compilationService.compileAsync(any(), any(), "first content") } returns firstCompilation
        coEvery { compilationService.compileAsync(any(), any(), "second content") } returns secondCompilation
        coEvery { diagnosticsService.collectDiagnostics(any(), any()) } returns
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        every { client.publishDiagnostics(any()) } just Runs

        val orchestrator = DiagnosticsOrchestrator(
            coroutineScope = scope,
            compilationService = compilationService,
            diagnosticsService = diagnosticsService,
            documentProvider = documentProvider,
            serverConfiguration = ServerConfiguration(),
            client = { client },
        )

        // When: Trigger diagnostics twice for the same URI
        documentProvider.put(testUri, "first content", 1)
        orchestrator.trigger(testUri, "first content")
        val firstJob = assertNotNull(orchestrator.getDiagnosticJob(testUri))
        coVerify(timeout = 5_000, exactly = 1) {
            compilationService.compileAsync(any(), testUri, "first content")
        }
        documentProvider.update(testUri, "second content", 2)
        orchestrator.trigger(testUri, "second content")
        val secondJob = assertNotNull(orchestrator.getDiagnosticJob(testUri))

        // Then: the first job is cancelled and the second one is registered and still running
        assertNotSame(firstJob, secondJob)
        firstJob.join()
        assertTrue(firstJob.isCancelled, "First job should be cancelled")
        assertTrue(secondJob.isActive, "Second job should be active while its compilation is pending")

        // And: once the second job completes it removes itself from the map
        secondCompilation.complete(createMockCompilationResult())
        secondJob.join()
        assertNull(orchestrator.getDiagnosticJob(testUri))

        // Cleanup
        scope.cancel()
    }

    // ==================== Test: a job that finishes immediately does not leak ====================

    @Test
    fun `job that completes immediately is not left registered`(): Unit = runTest {
        // Given: an eager dispatcher and a compilation that is already complete, so the job
        // runs to completion inside trigger() unless it is registered before it starts
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = openDocuments()
        val client = mockk<LanguageClient>()

        coEvery {
            compilationService.compileAsync(any(), any(), any())
        } returns CompletableDeferred(createMockCompilationResult())
        coEvery { diagnosticsService.collectDiagnostics(any(), any()) } returns
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        every { client.publishDiagnostics(any()) } just Runs

        val orchestrator = DiagnosticsOrchestrator(
            coroutineScope = scope,
            compilationService = compilationService,
            diagnosticsService = diagnosticsService,
            documentProvider = documentProvider,
            serverConfiguration = ServerConfiguration(),
            client = { client },
        )

        // When
        orchestrator.trigger(testUri, testContent)

        // Then: the finished job removed itself instead of being registered after its cleanup
        verify { client.publishDiagnostics(any()) }
        assertNull(orchestrator.getDiagnosticJob(testUri))

        // Cleanup
        scope.cancel()
    }

    // ==================== Test: trigger publishes parser diagnostics first ====================

    @Test
    fun `trigger publishes parser diagnostics first`(): Unit = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = openDocuments()
        val client = mockk<LanguageClient>()

        val parserDiagnostic = createTestDiagnostic("Parser error", 1)
        val result = createMockCompilationResult(listOf(parserDiagnostic))

        coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnosticsService.collectDiagnostics(any(), any()) } coAnswers {
            // Simulate slow diagnostic provider
            delay(100)
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        }

        val publishedParams = mutableListOf<PublishDiagnosticsParams>()
        every { client.publishDiagnostics(capture(publishedParams)) } just Runs

        val orchestrator = DiagnosticsOrchestrator(
            coroutineScope = scope,
            compilationService = compilationService,
            diagnosticsService = diagnosticsService,
            documentProvider = documentProvider,
            serverConfiguration = ServerConfiguration(),
            client = { client },
        )

        // When
        orchestrator.trigger(testUri, testContent)
        orchestrator.awaitDiagnostics(testUri)

        // Then: Parser diagnostics should be published first
        assertTrue(publishedParams.size >= 1, "Should have published at least parser diagnostics")
        val firstPublication = publishedParams[0]
        assertEquals(testUri.toString(), firstPublication.uri)
        assertEquals(1, firstPublication.diagnostics.size)
        assertEquals("Parser error", firstPublication.diagnostics[0].message)

        // Cleanup
        scope.cancel()
    }

    // ==================== Test: trigger merges extra diagnostics ====================

    @Test
    fun `trigger merges extra diagnostics from providers`(): Unit = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = openDocuments()
        val client = mockk<LanguageClient>()

        val parserDiagnostic = createTestDiagnostic("Parser error", 1)
        val extraDiagnostic = createTestDiagnostic("CodeNarc warning", 2)

        val result = createMockCompilationResult(listOf(parserDiagnostic))
        coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnosticsService.collectDiagnostics(any(), any()) } returns
            DiagnosticsCollectionResult(listOf(extraDiagnostic), emptyList(), emptyList())

        val publishedParams = mutableListOf<PublishDiagnosticsParams>()
        every { client.publishDiagnostics(capture(publishedParams)) } just Runs

        val orchestrator = DiagnosticsOrchestrator(
            coroutineScope = scope,
            compilationService = compilationService,
            diagnosticsService = diagnosticsService,
            documentProvider = documentProvider,
            serverConfiguration = ServerConfiguration(),
            client = { client },
        )

        // When
        orchestrator.trigger(testUri, testContent)
        orchestrator.awaitDiagnostics(testUri)

        // Then: Should publish twice - first parser, then merged
        assertEquals(2, publishedParams.size, "Should publish parser diagnostics first, then merged")

        val firstPublication = publishedParams[0]
        assertEquals(1, firstPublication.diagnostics.size)
        assertEquals("Parser error", firstPublication.diagnostics[0].message)

        val secondPublication = publishedParams[1]
        assertEquals(2, secondPublication.diagnostics.size)
        assertTrue(secondPublication.diagnostics.any { it.message == "Parser error" })
        assertTrue(secondPublication.diagnostics.any { it.message == "CodeNarc warning" })

        // Cleanup
        scope.cancel()
    }

    // ==================== Test: trigger handles compilation failure ====================

    @Test
    fun `trigger handles compilation failure gracefully`(): Unit = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = openDocuments()
        val client = mockk<LanguageClient>()

        coEvery {
            compilationService.compileAsync(any(), any(), any())
        } returns CompletableDeferred<CompilationResult>().apply {
            completeExceptionally(RuntimeException("Compilation failed"))
        }

        val orchestrator = DiagnosticsOrchestrator(
            coroutineScope = scope,
            compilationService = compilationService,
            diagnosticsService = diagnosticsService,
            documentProvider = documentProvider,
            serverConfiguration = ServerConfiguration(),
            client = { client },
        )

        // When
        orchestrator.trigger(testUri, testContent)
        orchestrator.awaitDiagnostics(testUri)

        // Then: Should not crash, job should complete
        val job = orchestrator.getDiagnosticJob(testUri)
        assertNull(job, "Job should be removed after completion")

        // Verify client was never called (no diagnostics published on error)
        verify(exactly = 0) { client.publishDiagnostics(any()) }

        // Cleanup
        scope.cancel()
    }

    // ==================== Test: trigger respects cancellation ====================

    @Test
    fun `trigger respects cancellation`(): Unit = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = openDocuments()
        val client = mockk<LanguageClient>()

        val result = createMockCompilationResult()
        val deferred = CompletableDeferred<CompilationResult>()

        coEvery { compilationService.compileAsync(any(), any(), any()) } returns deferred

        val orchestrator = DiagnosticsOrchestrator(
            coroutineScope = scope,
            compilationService = compilationService,
            diagnosticsService = diagnosticsService,
            documentProvider = documentProvider,
            serverConfiguration = ServerConfiguration(),
            client = { client },
        )

        // When: Trigger and then cancel
        orchestrator.trigger(testUri, testContent)
        val job = orchestrator.getDiagnosticJob(testUri)
        assertNotNull(job, "Job should exist")

        job.cancelAndJoin()

        // Then: Job should be cancelled
        assertTrue(job.isCancelled, "Job should be cancelled")

        // Verify no diagnostics were published
        verify(exactly = 0) { client.publishDiagnostics(any()) }

        // Cleanup
        deferred.cancel()
        scope.cancel()
    }

    // ==================== Test: cancelAndRemove stops running job ====================

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelAndRemove stops running job`(): Unit = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = openDocuments()
        val client = mockk<LanguageClient>()
        val deferred = CompletableDeferred<CompilationResult>()
        coEvery { compilationService.compileAsync(any(), any(), any()) } returns deferred
        val orchestrator = DiagnosticsOrchestrator(
            coroutineScope = scope,
            compilationService = compilationService,
            diagnosticsService = diagnosticsService,
            documentProvider = documentProvider,
            serverConfiguration = ServerConfiguration(),
            client = { client },
        )

        try {
            orchestrator.trigger(testUri, testContent)
            runCurrent()
            val job = assertNotNull(orchestrator.getDiagnosticJob(testUri))
            coVerify(exactly = 1) { compilationService.compileAsync(any(), testUri, testContent) }
            assertFalse(job.isCompleted, "Compilation must be suspended before cancellation")

            orchestrator.cancelAndRemove(testUri)
            runCurrent()

            assertNull(orchestrator.getDiagnosticJob(testUri), "Job should be removed after cancel")
            assertTrue(job.isCancelled, "Running job should be cancelled")
        } finally {
            deferred.cancel()
            scope.cancel()
        }
    }

    // ==================== Test: refreshAll triggers for all open documents ====================

    @Test
    fun `refreshAll triggers diagnostics for all open documents`(): Unit = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = DocumentProvider()
        val client = mockk<LanguageClient>()

        val uri1 = URI.create("file:///test/File1.groovy")
        val uri2 = URI.create("file:///test/File2.groovy")
        val content1 = "class File1 {}"
        val content2 = "class File2 {}"

        val snapshot = mapOf(
            uri1 to content1,
            uri2 to content2,
        )

        snapshot.forEach { (uri, content) -> documentProvider.put(uri, content, 1) }

        val result = createMockCompilationResult()
        coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnosticsService.collectDiagnostics(any(), any()) } returns
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        every { client.publishDiagnostics(any()) } just Runs

        val orchestrator = DiagnosticsOrchestrator(
            coroutineScope = scope,
            compilationService = compilationService,
            diagnosticsService = diagnosticsService,
            documentProvider = documentProvider,
            serverConfiguration = ServerConfiguration(),
            client = { client },
        )

        // When
        orchestrator.refreshAll()

        // Then: one compile per open file. The jobs run on a real dispatcher while runTest's delay() is
        // virtual and returns at once, so wait in real time (bounded) instead of sleeping.
        coVerify(timeout = 5_000, exactly = 2) { compilationService.compileAsync(any(), any(), any()) }

        // Cleanup
        scope.cancel()
    }

    // ==================== Test: respects parser disabled configuration ====================

    @Test
    fun `trigger respects parser disabled in configuration`(): Unit = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = openDocuments()
        val client = mockk<LanguageClient>()

        val parserDiagnostic = createTestDiagnostic("Parser error", 1)
        val result = createMockCompilationResult(listOf(parserDiagnostic))

        coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnosticsService.collectDiagnostics(any(), any()) } returns
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())

        val publishedParams = mutableListOf<PublishDiagnosticsParams>()
        every { client.publishDiagnostics(capture(publishedParams)) } just Runs

        // Disable parser diagnostics
        val baseConfig = ServerConfiguration()
        val config = baseConfig.copy(
            diagnosticConfig = baseConfig.diagnosticConfig.copy(disabledProviders = setOf("parser")),
        )

        val orchestrator = DiagnosticsOrchestrator(
            coroutineScope = scope,
            compilationService = compilationService,
            diagnosticsService = diagnosticsService,
            documentProvider = documentProvider,
            serverConfiguration = config,
            client = { client },
        )

        // When
        orchestrator.trigger(testUri, testContent)
        orchestrator.awaitDiagnostics(testUri)

        // Then: Should publish empty diagnostics (parser disabled)
        assertTrue(publishedParams.isNotEmpty(), "Should publish diagnostics")
        val firstPublication = publishedParams[0]
        assertEquals(0, firstPublication.diagnostics.size, "Parser diagnostics should be filtered out")

        // Cleanup
        scope.cancel()
    }

    // ==================== Test: awaitDiagnostics waits for job completion ====================

    @Test
    fun `awaitDiagnostics waits for job completion`(): Unit = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = openDocuments()
        val client = mockk<LanguageClient>()

        val result = createMockCompilationResult()
        val deferred = CompletableDeferred<CompilationResult>()

        coEvery { compilationService.compileAsync(any(), any(), any()) } returns deferred
        coEvery { diagnosticsService.collectDiagnostics(any(), any()) } returns
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        every { client.publishDiagnostics(any()) } just Runs

        val orchestrator = DiagnosticsOrchestrator(
            coroutineScope = scope,
            compilationService = compilationService,
            diagnosticsService = diagnosticsService,
            documentProvider = documentProvider,
            serverConfiguration = ServerConfiguration(),
            client = { client },
        )

        // When: Trigger diagnostics
        orchestrator.trigger(testUri, testContent)

        var awaitCompleted = false
        val awaitJob = scope.launch {
            orchestrator.awaitDiagnostics(testUri)
            awaitCompleted = true
        }

        // Give it a moment
        delay(50)

        // Then: awaitDiagnostics should still be waiting
        assertFalse(awaitCompleted, "awaitDiagnostics should be waiting")

        // When: Complete the compilation
        deferred.complete(result)

        // Wait for the await job to complete
        awaitJob.join()

        // Then: awaitDiagnostics should complete
        assertTrue(awaitCompleted, "awaitDiagnostics should complete after job finishes")

        // Cleanup
        scope.cancel()
    }

    // ==================== Test: does not publish twice when no extra diagnostics ====================

    @Test
    fun `trigger does not publish twice when no extra diagnostics`(): Unit = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = openDocuments()
        val client = mockk<LanguageClient>()

        val parserDiagnostic = createTestDiagnostic("Parser error", 1)
        val result = createMockCompilationResult(listOf(parserDiagnostic))

        coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnosticsService.collectDiagnostics(any(), any()) } returns
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())

        val publishedParams = mutableListOf<PublishDiagnosticsParams>()
        every { client.publishDiagnostics(capture(publishedParams)) } just Runs

        val orchestrator = DiagnosticsOrchestrator(
            coroutineScope = scope,
            compilationService = compilationService,
            diagnosticsService = diagnosticsService,
            documentProvider = documentProvider,
            serverConfiguration = ServerConfiguration(),
            client = { client },
        )

        // When
        orchestrator.trigger(testUri, testContent)
        orchestrator.awaitDiagnostics(testUri)

        // Then: Should publish only once (no extra diagnostics to merge)
        assertEquals(1, publishedParams.size, "Should publish diagnostics only once when no extra diagnostics")

        // Cleanup
        scope.cancel()
    }

    // ==================== Test Helpers ====================

    private fun openDocuments() = DocumentProvider().apply { put(testUri, testContent, 1) }

    private fun createTestDiagnostic(message: String, line: Int): Diagnostic = Diagnostic().apply {
        range = Range(Position(line, 0), Position(line, 10))
        severity = DiagnosticSeverity.Error
        this.message = message
        source = "Test"
    }

    private fun createMockCompilationResult(diagnostics: List<Diagnostic> = emptyList()): CompilationResult =
        mockk<CompilationResult> {
            every { this@mockk.diagnostics } returns diagnostics
            every { parseCompleted } returns true
        }
}
