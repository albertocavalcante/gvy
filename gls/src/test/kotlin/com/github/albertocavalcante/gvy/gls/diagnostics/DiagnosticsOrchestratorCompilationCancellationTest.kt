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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticsOrchestratorCompilationCancellationTest {
    private val uri = URI.create("file:///workspace/Shared.groovy")
    private val content = "class Shared {}"

    private class Fixture(scope: CoroutineScope, uri: URI, content: String) {
        val compilation = mockk<GroovyCompilationService>()
        val diagnostics = mockk<DiagnosticsService>()
        val documents = DocumentProvider().apply { put(uri, content, 3) }
        val publications = mutableListOf<PublishDiagnosticsParams>()
        val orchestrator: DiagnosticsOrchestrator

        init {
            val client = mockk<LanguageClient>()
            every { client.publishDiagnostics(capture(publications)) } just Runs
            coEvery { diagnostics.collectDiagnostics(any(), any()) } returns
                DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
            orchestrator = DiagnosticsOrchestrator(
                scope,
                compilation,
                diagnostics,
                documents,
                ServerConfiguration(),
                { client },
            )
        }
    }

    @Test
    fun `current diagnostics retry when another compilation owner was cancelled`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        val navigation = Job()
        val shared = CompletableDeferred<CompilationResult>(navigation)
        navigation.cancel()
        val fresh = result("fresh parser result")
        coEvery { fixture.compilation.compileAsync(any(), uri, content) } returnsMany
            listOf(shared, CompletableDeferred(fresh))

        fixture.orchestrator.trigger(uri, content)
        advanceUntilIdle()

        assertEquals(
            listOf("fresh parser result"),
            fixture.publications.singleOrNull()?.diagnostics?.map {
                it.message
            },
        )
        assertEquals(3, fixture.publications.single().version)
        coVerify(exactly = 2) { fixture.compilation.compileAsync(any(), uri, content) }
    }

    @Test
    fun `external compilation cancellation is retried at most once`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        val cancelled = CompletableDeferred<CompilationResult>().apply { cancel() }
        coEvery { fixture.compilation.compileAsync(any(), uri, content) } returns cancelled

        fixture.orchestrator.trigger(uri, content)
        advanceUntilIdle()

        coVerify(exactly = 2) { fixture.compilation.compileAsync(any(), uri, content) }
        assertTrue(fixture.publications.isEmpty())
    }

    @Test
    fun `cancelling diagnostics itself never retries compilation`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        val shared = CompletableDeferred<CompilationResult>()
        coEvery { fixture.compilation.compileAsync(any(), uri, content) } returns shared
        fixture.orchestrator.trigger(uri, content)
        runCurrent()
        val diagnosticJob = assertNotNull(fixture.orchestrator.getDiagnosticJob(uri))

        fixture.orchestrator.cancelAndRemove(uri)
        advanceUntilIdle()

        assertTrue(diagnosticJob.isCancelled)
        coVerify(exactly = 1) { fixture.compilation.compileAsync(any(), uri, content) }
        assertTrue(fixture.publications.isEmpty())
        shared.cancel()
    }

    @Test
    fun `obsolete source does not retry an externally cancelled compilation`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        val shared = CompletableDeferred<CompilationResult>()
        coEvery { fixture.compilation.compileAsync(any(), uri, content) } returns shared
        fixture.orchestrator.trigger(uri, content)
        runCurrent()

        fixture.documents.update(uri, "class Changed {}", 4)
        shared.cancel()
        advanceUntilIdle()

        coVerify(exactly = 1) { fixture.compilation.compileAsync(any(), uri, content) }
        assertTrue(fixture.publications.isEmpty())
    }

    @Test
    fun `provider cancellation does not restart compilation`(): Unit = runTest {
        val fixture = Fixture(this, uri, content)
        coEvery { fixture.compilation.compileAsync(any(), uri, content) } returns CompletableDeferred(result("parser"))
        coEvery { fixture.diagnostics.collectDiagnostics(any(), any()) } throws
            CancellationException("Provider cancelled")

        fixture.orchestrator.trigger(uri, content)
        advanceUntilIdle()

        coVerify(exactly = 1) { fixture.compilation.compileAsync(any(), uri, content) }
        assertEquals(listOf("parser"), fixture.publications.single().diagnostics.map { it.message })
    }

    private fun result(message: String): CompilationResult = mockk {
        every { diagnostics } returns listOf(Diagnostic().apply { this.message = message })
        every { parseCompleted } returns true
    }
}
