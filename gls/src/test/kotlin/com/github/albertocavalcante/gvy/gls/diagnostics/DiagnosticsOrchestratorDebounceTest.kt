package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.net.URI

class DiagnosticsOrchestratorDebounceTest {

    private val uri = URI.create("file:///test/Debounce.groovy")

    private class Fixture(scope: CoroutineScope, debounceMillis: Long) {
        val compilationService = mockk<GroovyCompilationService>()
        val client = mockk<LanguageClient>()
        val orchestrator: DiagnosticsOrchestrator

        init {
            val result = mockk<CompilationResult> { every { diagnostics } returns emptyList() }
            coEvery { compilationService.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
            val diagnosticsService = mockk<DiagnosticsService>()
            coEvery { diagnosticsService.getDiagnostics(any(), any()) } returns emptyList()
            every { client.publishDiagnostics(any()) } just Runs
            orchestrator = DiagnosticsOrchestrator(
                coroutineScope = scope,
                compilationService = compilationService,
                diagnosticsService = diagnosticsService,
                documentProvider = mockk<DocumentProvider>(),
                serverConfiguration = ServerConfiguration(),
                client = { client },
                debounceMillis = debounceMillis,
            )
        }
    }

    private fun TestScope.scopeOnTestScheduler() =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun `burst of debounced triggers compiles only the latest content once`(): Unit = runTest {
        val scope = scopeOnTestScheduler()
        val f = Fixture(scope, debounceMillis = 250)

        f.orchestrator.trigger(uri, "v1", debounce = true)
        advanceTimeBy(100)
        f.orchestrator.trigger(uri, "v2", debounce = true)
        advanceTimeBy(100)
        f.orchestrator.trigger(uri, "v3", debounce = true)

        advanceTimeBy(249)
        coVerify(exactly = 0) { f.compilationService.compileAsync(any(), any(), any()) }

        advanceUntilIdle()
        coVerify(exactly = 1) { f.compilationService.compileAsync(any(), any(), any()) }
        coVerify(exactly = 1) { f.compilationService.compileAsync(any(), uri, "v3") }

        scope.cancel()
    }

    @Test
    fun `non debounced trigger compiles without waiting`(): Unit = runTest {
        val scope = scopeOnTestScheduler()
        val f = Fixture(scope, debounceMillis = 250)

        f.orchestrator.trigger(uri, "opened")
        advanceTimeBy(1)

        coVerify(exactly = 1) { f.compilationService.compileAsync(any(), uri, "opened") }

        scope.cancel()
    }

    @Test
    fun `cancelAndRemove during the debounce delay cancels the pending compile`(): Unit = runTest {
        val scope = scopeOnTestScheduler()
        val f = Fixture(scope, debounceMillis = 250)

        f.orchestrator.trigger(uri, "v1", debounce = true)
        advanceTimeBy(100)
        f.orchestrator.cancelAndRemove(uri)

        advanceUntilIdle()
        coVerify(exactly = 0) { f.compilationService.compileAsync(any(), any(), any()) }

        scope.cancel()
    }

    @Test
    fun `non debounced trigger after a debounced one compiles immediately and supersedes it`(): Unit = runTest {
        val scope = scopeOnTestScheduler()
        val f = Fixture(scope, debounceMillis = 250)

        f.orchestrator.trigger(uri, "typed", debounce = true)
        advanceTimeBy(100)
        f.orchestrator.trigger(uri, "saved")
        advanceTimeBy(1)
        coVerify(exactly = 1) { f.compilationService.compileAsync(any(), uri, "saved") }

        advanceUntilIdle()
        coVerify(exactly = 1) { f.compilationService.compileAsync(any(), any(), any()) }
        coVerify(exactly = 0) { f.compilationService.compileAsync(any(), uri, "typed") }

        scope.cancel()
    }
}
