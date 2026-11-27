// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

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

/** The dependency progress begins before resolution starts, so a resolution that ends at once still ends it. */
class ProjectStartupProgressTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `a resolution that finishes before the progress begins still ends it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val progress = CopyOnWriteArrayList<ProgressParams>()
        val resolutionStarted = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val client = mockk<LanguageClient>(relaxed = true) {
            every { createProgress(any()) } answers {
                // Resolution already running may finish before the begin is sent, as a resolution with no build
                // tool does: let it finish first.
                if (resolutionStarted.count == 0L) ready.await(WAIT_SECONDS, TimeUnit.SECONDS)
                CompletableFuture.completedFuture(null)
            }
            every { notifyProgress(any()) } answers { progress += firstArg<ProgressParams>() }
        }
        val workspace = mockk<WorkspaceManager>(relaxed = true) {
            every { getSourceRoots() } returns emptyList()
            every { getWorkspaceSourceUris() } returns emptyList()
        }
        val compilation = mockk<GroovyCompilationService>(relaxed = true) {
            every { workspaceManager } returns workspace
        }
        val startup = ProjectStartupManager(compilation, emptyList(), scope, ProjectStrategyRegistry())
        val params = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(workspaceRoot.toUri().toString(), "ws"))
        }

        try {
            startup.startAsyncDependencyResolution(client, params, null, {}) { _, quiescent, message, _, _, _, _ ->
                if (message == RESOLUTION_STARTED) resolutionStarted.countDown()
                if (quiescent) ready.countDown()
            }
            assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS), "startup never became ready")
        } finally {
            scope.cancel()
        }

        val values = progress.map { it.value.left }
        assertIs<WorkDoneProgressBegin>(values.first(), "the first notification must begin the progress: $values")
        assertIs<WorkDoneProgressEnd>(values.last(), "the progress was left open: $values")
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val RESOLUTION_STARTED = "Starting dependency resolution..."
    }
}
