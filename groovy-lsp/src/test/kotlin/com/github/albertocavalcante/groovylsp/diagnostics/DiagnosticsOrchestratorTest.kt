package com.github.albertocavalcante.groovylsp.diagnostics

import com.github.albertocavalcante.groovylsp.compilation.CompilationResult
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.services.DiagnosticsService
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsOrchestratorTest {

    private val testUri = URI.create("file:///test/Example.groovy")
    private val testContent = "class Example { }"

    @AfterEach
    fun cleanup() {
        unmockkAll()
    }

    // ==================== Test: trigger cancels existing job ====================

    @Test
    fun `trigger cancels existing job for same URI`() = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = mockk<DocumentProvider>()
        val client = mockk<LanguageClient>()

        // Mock compilation and diagnostics
        val result = createMockCompilationResult()
        coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnosticsService.getDiagnostics(any(), any()) } returns emptyList()
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
        orchestrator.trigger(testUri, "first content")
        val firstJobActive = orchestrator.getDiagnosticJob(testUri)?.isActive ?: false

        orchestrator.trigger(testUri, "second content")
        val secondJobActive = orchestrator.getDiagnosticJob(testUri)?.isActive ?: false

        // Give jobs time to start
        delay(50)

        // Then: Second trigger should have cancelled the first job
        assertTrue(secondJobActive, "Second job should be active")

        // Cleanup
        scope.cancel()
    }

    // ==================== Test: trigger publishes parser diagnostics first ====================

    @Test
    fun `trigger publishes parser diagnostics first`() = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = mockk<DocumentProvider>()
        val client = mockk<LanguageClient>()

        val parserDiagnostic = createTestDiagnostic("Parser error", 1)
        val result = createMockCompilationResult(listOf(parserDiagnostic))

        coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnosticsService.getDiagnostics(any(), any()) } coAnswers {
            // Simulate slow diagnostic provider
            delay(100)
            emptyList()
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
    fun `trigger merges extra diagnostics from providers`() = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = mockk<DocumentProvider>()
        val client = mockk<LanguageClient>()

        val parserDiagnostic = createTestDiagnostic("Parser error", 1)
        val extraDiagnostic = createTestDiagnostic("CodeNarc warning", 2)

        val result = createMockCompilationResult(listOf(parserDiagnostic))
        coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnosticsService.getDiagnostics(any(), any()) } returns listOf(extraDiagnostic)

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
    fun `trigger handles compilation failure gracefully`() = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = mockk<DocumentProvider>()
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
    fun `trigger respects cancellation`() = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = mockk<DocumentProvider>()
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

    @Test
    fun `cancelAndRemove stops running job`() = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = mockk<DocumentProvider>()
        val client = mockk<LanguageClient>()

        // Create a deferred that will never complete
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

        // When: Start a job and then cancel it
        orchestrator.trigger(testUri, testContent)
        val jobBeforeCancel = orchestrator.getDiagnosticJob(testUri)
        assertNotNull(jobBeforeCancel, "Job should exist before cancel")

        orchestrator.cancelAndRemove(testUri)

        // Give it time to process cancellation
        delay(50)

        // Then: Job should be cancelled and removed
        val jobAfterCancel = orchestrator.getDiagnosticJob(testUri)
        assertNull(jobAfterCancel, "Job should be removed after cancel")
        assertTrue(jobBeforeCancel.isCancelled, "Job should be cancelled")

        // Cleanup
        deferred.cancel()
        scope.cancel()
    }

    // ==================== Test: refreshAll triggers for all open documents ====================

    @Test
    fun `refreshAll triggers diagnostics for all open documents`() = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = mockk<DocumentProvider>()
        val client = mockk<LanguageClient>()

        val uri1 = URI.create("file:///test/File1.groovy")
        val uri2 = URI.create("file:///test/File2.groovy")
        val content1 = "class File1 {}"
        val content2 = "class File2 {}"

        val snapshot = mapOf(
            uri1 to content1,
            uri2 to content2,
        )

        every { documentProvider.snapshot() } returns snapshot

        val result = createMockCompilationResult()
        coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnosticsService.getDiagnostics(any(), any()) } returns emptyList()
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

        // Give time for jobs to start
        delay(100)

        // Then: Should have triggered diagnostics for both files
        coVerify { compilationService.compileAsync(any(), uri1, content1) }
        coVerify { compilationService.compileAsync(any(), uri2, content2) }

        // Cleanup
        scope.cancel()
    }

    // ==================== Test: respects parser disabled configuration ====================

    @Test
    fun `trigger respects parser disabled in configuration`() = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = mockk<DocumentProvider>()
        val client = mockk<LanguageClient>()

        val parserDiagnostic = createTestDiagnostic("Parser error", 1)
        val result = createMockCompilationResult(listOf(parserDiagnostic))

        coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnosticsService.getDiagnostics(any(), any()) } returns emptyList()

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
    fun `awaitDiagnostics waits for job completion`() = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = mockk<DocumentProvider>()
        val client = mockk<LanguageClient>()

        val result = createMockCompilationResult()
        val deferred = CompletableDeferred<CompilationResult>()

        coEvery { compilationService.compileAsync(any(), any(), any()) } returns deferred
        coEvery { diagnosticsService.getDiagnostics(any(), any()) } returns emptyList()
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

        // Give it time to complete
        delay(100)

        // Then: awaitDiagnostics should complete
        assertTrue(awaitCompleted, "awaitDiagnostics should complete after job finishes")

        // Cleanup
        awaitJob.cancel()
        scope.cancel()
    }

    // ==================== Test: does not publish twice when no extra diagnostics ====================

    @Test
    fun `trigger does not publish twice when no extra diagnostics`() = runTest {
        // Given
        val scope = CoroutineScope(SupervisorJob())
        val compilationService = mockk<GroovyCompilationService>()
        val diagnosticsService = mockk<DiagnosticsService>()
        val documentProvider = mockk<DocumentProvider>()
        val client = mockk<LanguageClient>()

        val parserDiagnostic = createTestDiagnostic("Parser error", 1)
        val result = createMockCompilationResult(listOf(parserDiagnostic))

        coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { diagnosticsService.getDiagnostics(any(), any()) } returns emptyList()

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

    private fun createTestDiagnostic(message: String, line: Int): Diagnostic = Diagnostic().apply {
        range = Range(Position(line, 0), Position(line, 10))
        severity = DiagnosticSeverity.Error
        this.message = message
        source = "Test"
    }

    private fun createMockCompilationResult(diagnostics: List<Diagnostic> = emptyList()): CompilationResult =
        mockk<CompilationResult> {
            every { this@mockk.diagnostics } returns diagnostics
        }
}
