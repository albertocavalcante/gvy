// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.ResolutionCancellation
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Every dependency progress the client is shown begins before resolution starts and ends however it stops. */
class ProjectStartupProgressTest {
    @TempDir
    lateinit var workspaceRoot: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val progress = CopyOnWriteArrayList<ProgressParams>()
    private val ended = CountDownLatch(1)
    private val resolutionStarted = CountDownLatch(1)
    private val ready = CountDownLatch(1)

    @Test
    fun `a resolution that finishes before the progress begins still ends it`() {
        val client = client {
            // Resolution already running may finish before the begin is sent, as a resolution with no build tool
            // does: let it finish first.
            if (resolutionStarted.count == 0L) ready.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }
        val startup = startup(buildTools = emptyList())

        try {
            start(startup, client)
            assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS), "startup never became ready")
        } finally {
            scope.cancel()
        }

        assertBeganAndEnded()
    }

    @Test
    fun `shutting down during resolution ends the progress`() {
        val resolving = CountDownLatch(1)
        val startup = startup(buildTools = listOf(blockingTool(resolving)))

        try {
            start(startup, client())
            assertTrue(resolving.await(WAIT_SECONDS, TimeUnit.SECONDS), "resolution never started")
            startup.shutdown() // cancels the attempt in flight
        } finally {
            scope.cancel()
        }

        assertBeganAndEnded()
    }

    @Test
    fun `cancelling the scope during resolution ends the progress`() {
        val resolving = CountDownLatch(1)
        val startup = startup(buildTools = listOf(blockingTool(resolving)))

        start(startup, client())
        assertTrue(resolving.await(WAIT_SECONDS, TimeUnit.SECONDS), "resolution never started")
        scope.cancel()

        assertTrue(ended.await(WAIT_SECONDS, TimeUnit.SECONDS), "the progress was left open: ${kinds()}")
        assertBeganAndEnded()
    }

    private fun start(startup: ProjectStartupManager, client: LanguageClient) {
        val params = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(workspaceRoot.toUri().toString(), "ws"))
        }
        startup.startAsyncDependencyResolution(client, params, null, {}) { _, quiescent, message, _, _, _, _ ->
            if (message == RESOLUTION_STARTED) resolutionStarted.countDown()
            if (quiescent) ready.countDown()
        }
    }

    /** Records every progress notification; [beforeBegin] may hold the begin back. */
    private fun client(beforeBegin: () -> Unit = {}): LanguageClient = mockk(relaxed = true) {
        every { createProgress(any()) } answers {
            beforeBegin()
            CompletableFuture.completedFuture(null)
        }
        every { notifyProgress(any()) } answers {
            val params = firstArg<ProgressParams>()
            progress += params
            if (params.value.left is WorkDoneProgressEnd) ended.countDown()
        }
    }

    private fun startup(buildTools: List<BuildTool>): ProjectStartupManager {
        val workspace = mockk<WorkspaceManager>(relaxed = true) {
            every { getSourceRoots() } returns emptyList()
            every { getWorkspaceSourceUris() } returns emptyList()
        }
        val compilation = mockk<GroovyCompilationService>(relaxed = true) {
            every { workspaceManager } returns workspace
        }
        return ProjectStartupManager(compilation, buildTools, scope, ProjectStrategyRegistry())
    }

    /** A build tool for any workspace whose resolution runs until it is cancelled. */
    private fun blockingTool(resolving: CountDownLatch): BuildTool = mockk(relaxed = true) {
        every { name } returns "Blocking"
        every { canHandle(any()) } returns true
        every { createWatcher(any(), any()) } returns null
        every { resolve(any(), any(), any()) } answers {
            val cancellation = thirdArg<ResolutionCancellation>()
            val stopped = CountDownLatch(1)
            cancellation.onCancel { stopped.countDown() }.use {
                resolving.countDown()
                check(stopped.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the resolution was never cancelled" }
            }
            cancellation.throwIfCancelled()
            WorkspaceResolution(emptyList(), emptyList())
        }
    }

    private fun kinds(): List<String?> = progress.map { it.value.left?.javaClass?.simpleName }

    private fun assertBeganAndEnded() {
        val values = progress.map { it.value.left }
        assertIs<WorkDoneProgressBegin>(values.firstOrNull(), "the progress must begin first: ${kinds()}")
        assertIs<WorkDoneProgressEnd>(values.lastOrNull(), "the progress was left open: ${kinds()}")
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val RESOLUTION_STARTED = "Starting dependency resolution..."
    }
}
