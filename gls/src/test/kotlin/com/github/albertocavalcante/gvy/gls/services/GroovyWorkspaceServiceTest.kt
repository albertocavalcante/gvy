// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.Version
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerCapabilitiesFactory
import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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

    private val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `blank workspace symbol query returns empty result for an empty workspace`() {
        val compilationService = GroovyCompilationService()
        val service = GroovyWorkspaceService(compilationService, compilationService.symbolIndexingService, testScope)

        val either = service.symbol(WorkspaceSymbolParams("   ")).get()

        assertTrue(either.isLeft)
        assertTrue(either.left.isEmpty())
    }

    @Test
    fun `empty workspace symbol query lists the indexed symbols without imports`() {
        val file = Files.writeString(
            tempDir.resolve("Greeter.groovy"),
            "import java.util.concurrent.TimeUnit\nclass Greeter {\n    def hello() {}\n}\n",
        )
        val compilationService = GroovyCompilationService()
        runBlocking { compilationService.symbolIndexingService.indexFile(file.toUri()) }
        val service = GroovyWorkspaceService(compilationService, compilationService.symbolIndexingService, testScope)

        val names = service.symbol(WorkspaceSymbolParams("")).get().left.map { it.name }

        assertTrue(names.any { it.contains("Greeter") }, "classes are listed: $names")
        assertTrue(names.any { it.contains("hello") }, "methods are listed: $names")
        assertTrue(names.none { it.contains("TimeUnit") }, "imports are not symbols: $names")
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
