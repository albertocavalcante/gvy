package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.Version
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException

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

    @Test
    fun `groovy version command returns server version`() {
        val compilationService = GroovyCompilationService()
        val service = GroovyWorkspaceService(compilationService, compilationService.symbolIndexingService, testScope)

        val result = service.executeCommand(ExecuteCommandParams("groovy.version", emptyList())).get()

        assertEquals(Version.current, result)
    }

    @Test
    fun `unknown command fails with invalid params naming the command`() {
        val compilationService = GroovyCompilationService()
        val service = GroovyWorkspaceService(compilationService, compilationService.symbolIndexingService, testScope)

        val future = service.executeCommand(ExecuteCommandParams("groovy.resolveDependencies", emptyList()))

        assertTrue(future.isCompletedExceptionally)
        val thrown = org.junit.jupiter.api.assertThrows<ExecutionException> { future.get() }
        val cause = assertInstanceOf(ResponseErrorException::class.java, thrown.cause)
        assertEquals(ResponseErrorCode.InvalidParams.value, cause.responseError.code)
        assertTrue(cause.responseError.message.contains("groovy.resolveDependencies"))
    }

    @Test
    fun `advertised execute commands are exactly the implemented ones`() {
        val provider = com.github.albertocavalcante.gvy.gls.config.ServerCapabilitiesFactory
            .createInitializeResult().capabilities.executeCommandProvider

        assertEquals(listOf("groovy.version"), provider?.commands)
    }
}
