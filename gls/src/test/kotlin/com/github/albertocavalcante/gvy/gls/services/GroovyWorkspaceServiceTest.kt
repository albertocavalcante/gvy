// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.Version
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerCapabilitiesFactory
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerRouter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException

class GroovyWorkspaceServiceTest {

    @TempDir
    lateinit var tempDir: Path

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
    fun `configuration updates strategies before diagnostics reanalysis`() {
        val compilation = GroovyCompilationService()
        var updated = false
        var revision = 0L
        val strategy = object : ProjectStrategy {
            override val id = "test"
            override val displayName = "Test"
            override val sourcePolicyRevision get() = revision
            override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true
            override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
            override fun updateConfiguration(config: ServerConfiguration) {
                updated = true
                revision += 2
            }
        }
        val registry = ProjectStrategyRegistry(listOf(strategy))
        registry.selectStrategies(Path.of("."), ServerConfiguration())
        compilation.workspaceManager.setStrategyRegistry(registry)
        val textService = mockk<GroovyTextDocumentService>(relaxed = true)
        every { textService.updateConfiguration(any()) } answers { assertTrue(updated) }
        val service = GroovyWorkspaceService(compilation, compilation.symbolIndexingService, testScope, textService)

        service.didChangeConfiguration(DidChangeConfigurationParams(emptyMap<String, Any>()))
        assertTrue(updated)
    }

    @Test
    fun `failed strategy update schedules open document and closed semantic recovery`(): Unit = runBlocking {
        val compilation = GroovyCompilationService()
        val source = Files.createDirectories(tempDir.resolve("src")).resolve("Closed.groovy")
        Files.writeString(source, "class Closed {}")
        compilation.workspaceManager.initializeWorkspace(tempDir)
        compilation.getWorkspaceCompiler().compileWorkspace()
        val index = requireNotNull(compilation.getWorkspaceSymbolIndex())
        assertTrue(index.getDocument(source.toUri()) != null)
        var revision = 0L
        val strategy = object : ProjectStrategy {
            override val id = "throwing"
            override val displayName = "Throwing"
            override val sourcePolicyRevision get() = revision
            override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true
            override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
            override fun updateConfiguration(config: ServerConfiguration) {
                revision += 2
                error("update failed")
            }
        }
        val registry = ProjectStrategyRegistry(listOf(strategy))
        registry.selectStrategies(Path.of("."), ServerConfiguration())
        compilation.workspaceManager.setStrategyRegistry(registry)
        val textService = mockk<GroovyTextDocumentService>(relaxed = true)
        val service = GroovyWorkspaceService(compilation, compilation.symbolIndexingService, testScope, textService)

        assertThrows<IllegalStateException> {
            service.didChangeConfiguration(DidChangeConfigurationParams(emptyMap<String, Any>()))
        }
        verify(exactly = 1) { textService.rerunDiagnosticsOnOpenFiles() }
        withTimeout(10_000) {
            while (index.getDocument(source.toUri()) == null) delay(10)
        }
    }

    @Test
    fun `groovy version command returns server version`() {
        val compilationService = GroovyCompilationService()
        val service = GroovyWorkspaceService(compilationService, compilationService.symbolIndexingService, testScope)

        val result = service.executeCommand(ExecuteCommandParams("groovy.version", emptyList())).get()

        assertEquals(Version.display, result)
    }

    @Test
    fun `unknown command fails with invalid params naming the command`() {
        val compilationService = GroovyCompilationService()
        val service = GroovyWorkspaceService(compilationService, compilationService.symbolIndexingService, testScope)

        val future = service.executeCommand(ExecuteCommandParams("groovy.resolveDependencies", emptyList()))

        assertTrue(future.isCompletedExceptionally)
        val thrown = assertThrows<ExecutionException> { future.get() }
        val cause = assertInstanceOf(ResponseErrorException::class.java, thrown.cause)
        assertEquals(ResponseErrorCode.InvalidParams.value, cause.responseError.code)
        assertTrue(cause.responseError.message.contains("groovy.resolveDependencies"))
    }

    @Test
    fun `advertised execute commands are exactly the implemented ones`() {
        val provider = ServerCapabilitiesFactory.createInitializeResult().capabilities.executeCommandProvider

        assertEquals(listOf("groovy.version"), provider?.commands)
    }
}
