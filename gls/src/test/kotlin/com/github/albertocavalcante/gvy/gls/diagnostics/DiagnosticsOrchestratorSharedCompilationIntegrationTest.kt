package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticsOrchestratorSharedCompilationIntegrationTest {
    @Test
    fun `current diagnostics recover after the navigation owner cancels real queued compilation`(): Unit = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val navigationScope = CoroutineScope(SupervisorJob() + io)
        val diagnosticsScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val uri = URI.create("untitled:SharedCompilation.groovy")
            val content = "class SharedCompilation { def value = }"
            val documents = DocumentProvider().apply { put(uri, content, 17) }
            val compilation = GroovyCompilationService(ioDispatcher = io, documentProvider = documents)
            val providers = mockk<DiagnosticsService>()
            coEvery { providers.getDiagnostics(any(), any()) } returns emptyList()
            val publications = mutableListOf<PublishDiagnosticsParams>()
            val client = mockk<LanguageClient>()
            every { client.publishDiagnostics(capture(publications)) } just Runs
            val diagnostics = DiagnosticsOrchestrator(
                diagnosticsScope,
                compilation,
                providers,
                documents,
                ServerConfiguration(),
                { client },
            )
            // Enter navigation synchronously, but leave the actual parser queued on the IO dispatcher.
            val navigation = navigationScope.async(start = CoroutineStart.UNDISPATCHED) {
                compilation.ensureCompiled(uri)
            }
            assertFalse(navigation.isCompleted)
            val navigationCompilation = assertNotNull(compilation.compilationCacheService.getActiveCompilation(uri))
            assertEquals(1, compilation.getCacheStatistics()["activeCompilations"])
            diagnostics.trigger(uri, content)
            val diagnosticJob = assertNotNull(diagnostics.getDiagnosticJob(uri))
            assertTrue(diagnosticJob.isActive)
            assertSame(navigationCompilation, compilation.compilationCacheService.getActiveCompilation(uri))
            assertEquals(1, compilation.getCacheStatistics()["activeCompilations"])

            navigationScope.cancel()
            advanceUntilIdle()

            assertTrue(navigation.isCancelled)
            assertTrue(diagnosticJob.isCompleted)
            assertFalse(diagnosticJob.isCancelled)
            val publication = publications.single()
            assertEquals(uri.toString(), publication.uri)
            assertEquals(17, publication.version)
            assertTrue(publication.diagnostics.isNotEmpty())
            assertEquals(0, compilation.getCacheStatistics()["activeCompilations"])
            assertNull(diagnostics.getDiagnosticJob(uri))
        } finally {
            navigationScope.cancel()
            diagnosticsScope.cancel()
        }
    }
}
