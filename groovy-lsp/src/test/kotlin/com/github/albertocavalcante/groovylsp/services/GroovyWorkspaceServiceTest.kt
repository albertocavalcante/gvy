package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.test.parseGroovyVersion
import com.github.albertocavalcante.groovylsp.version.GroovyVersionRange
import com.github.albertocavalcante.groovylsp.worker.WorkerCapabilities
import com.github.albertocavalcante.groovylsp.worker.WorkerConnector
import com.github.albertocavalcante.groovylsp.worker.WorkerDescriptor
import com.github.albertocavalcante.groovylsp.worker.WorkerRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroovyWorkspaceServiceTest {

    private val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Test
    fun `blank workspace symbol query returns empty result`() {
        val compilationService = GroovyCompilationService()
        val service = GroovyWorkspaceService(compilationService, compilationService.symbolIndexingService, testScope)

        val either = service.symbol(WorkspaceSymbolParams("   ")).get()

        assertTrue(either.isLeft)
        assertTrue(either.left.isEmpty())
    }

    @Test
    fun `configuration change reselects worker`() {
        val compilationService = GroovyCompilationService()
        val worker = WorkerDescriptor(
            id = "override-worker",
            supportedRange = GroovyVersionRange(parseGroovyVersion("3.0.0"), parseGroovyVersion("4.0.0")),
            capabilities = WorkerCapabilities(),
            connector = WorkerConnector.InProcess,
        )
        val service = GroovyWorkspaceService(
            compilationService = compilationService,
            symbolIndexer = compilationService.symbolIndexingService,
            coroutineScope = testScope,
            workerRouter = WorkerRouter(listOf(worker)),
        )

        service.didChangeConfiguration(
            DidChangeConfigurationParams(mapOf("groovy.language.version" to "3.0.0")),
        )

        assertEquals(worker, compilationService.getSelectedWorker())
    }
}
