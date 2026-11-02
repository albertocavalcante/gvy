package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceCompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceCompiler
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceIndexerLimitTest {

    private class Harness(fileCount: Int, limit: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val workspaceCompiler = mockk<WorkspaceCompiler>()
        val client = mockk<LanguageClient>(relaxed = true)
        val messages = mutableListOf<MessageParams>()
        val done = CountDownLatch(1)
        val config = ServerConfiguration(maxWorkspaceFiles = limit)
        private val compilationService = mockk<GroovyCompilationService>()
        private val indexer = WorkspaceIndexer(compilationService, scope, Dispatchers.Unconfined)

        init {
            val manager = mockk<WorkspaceManager>()
            every { manager.getWorkspaceSourceUris() } returns
                (1..fileCount).map { URI.create("file:///w/F$it.groovy") }
            every { compilationService.workspaceManager } returns manager
            coEvery { compilationService.indexAllWorkspaceSources(any(), any()) } returns Unit
            every { compilationService.getWorkspaceCompiler() } returns workspaceCompiler
            coEvery { workspaceCompiler.compileWorkspace() } returns
                WorkspaceCompilationResult(emptyMap(), emptyList(), true)
        }

        fun run() {
            every { client.showMessage(any()) } answers { messages += firstArg<MessageParams>() }
            indexer.startIndexing(
                client,
                { _, quiescent, _, _, _, _, _ -> if (quiescent) done.countDown() },
                emptyList<Job>(),
                config,
            )
            assertTrue(done.await(10, TimeUnit.SECONDS), "indexing should finish")
            scope.cancel()
        }
    }

    @Test
    fun `eager compile is skipped and client told once when workspace exceeds limit`() {
        val h = Harness(fileCount = 6, limit = 5)
        h.run()

        coVerify(exactly = 0) { h.workspaceCompiler.compileWorkspace() }
        val infos = h.messages.filter {
            it.type == MessageType.Info && "groovy.compilation.maxWorkspaceFiles" in it.message
        }
        assertEquals(1, infos.size)
        assertTrue("6" in infos.single().message && "5" in infos.single().message)
    }

    @Test
    fun `eager compile runs at the limit`() {
        val h = Harness(fileCount = 5, limit = 5)
        h.run()

        coVerify(exactly = 1) { h.workspaceCompiler.compileWorkspace() }
        assertTrue(h.messages.none { "groovy.compilation.maxWorkspaceFiles" in it.message })
    }
}
