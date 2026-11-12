// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** Cancelling workspace indexing still ends the client's work-done progress, and stays cancelled (#1311). */
class WorkspaceIndexerCancellationTest {
    @Test
    fun `cancelling indexing ends the progress and propagates the cancellation`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val progress = CopyOnWriteArrayList<ProgressParams>()
        val ended = CountDownLatch(1)
        val client = mockk<LanguageClient>(relaxed = true) {
            every { createProgress(any()) } returns CompletableFuture.completedFuture(null)
            every { notifyProgress(any()) } answers {
                val params = firstArg<ProgressParams>()
                progress += params
                if (params.value.left is WorkDoneProgressEnd) ended.countDown()
            }
        }
        val indexing = CompletableDeferred<Job>()
        val compilation = mockk<GroovyCompilationService> {
            val manager = mockk<WorkspaceManager> {
                every { getWorkspaceSourceUris() } returns listOf(URI.create("file:///w/A.groovy"))
            }
            every { workspaceManager } returns manager
            coEvery { indexAllWorkspaceSources(any(), any()) } coAnswers {
                indexing.complete(checkNotNull(currentCoroutineContext()[Job]))
                awaitCancellation()
            }
        }
        val indexer = WorkspaceIndexer(compilation, scope, Dispatchers.Default)

        indexer.startIndexing(client, { _, _, _, _, _, _, _ -> }, emptyList(), ServerConfiguration())
        val job = runBlocking { withTimeout(WAIT_MILLIS) { indexing.await() } }
        scope.cancel()

        assertTrue(ended.await(WAIT_MILLIS, TimeUnit.MILLISECONDS), "the progress was left open: $progress")
        runBlocking { withTimeout(WAIT_MILLIS) { job.join() } }
        assertTrue(job.isCancelled, "indexing must end cancelled, not completed")
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
