// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceCompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceCompiler
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.telemetry.PhaseStatus
import com.github.albertocavalcante.gvy.gls.telemetry.StartupPhase
import com.github.albertocavalcante.gvy.gls.telemetry.StartupPhaseRecorder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The indexer reports how its index and compile phases ended; Unconfined dispatch makes each run synchronous. */
class WorkspaceIndexerPhasesTest {

    private class Harness(fileCount: Int, limit: Int = 5) {
        val phases = StartupPhaseRecorder()

        // Records what escapes the indexing coroutine, so a test's Error never reaches the global handlers
        val escaped = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> escaped += e },
        )
        val config = ServerConfiguration(maxWorkspaceFiles = limit)
        val compilationService = mockk<GroovyCompilationService>()
        private val workspaceCompiler = mockk<WorkspaceCompiler>()
        private val indexer = WorkspaceIndexer(compilationService, scope, Dispatchers.Unconfined, phases)

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
            val client = mockk<LanguageClient>(relaxed = true)
            indexer.startIndexing(client, { _, _, _, _, _, _, _ -> }, emptyList<Job>(), config)
            scope.cancel()
        }

        fun status(phase: StartupPhase): PhaseStatus? = phases.snapshot().phases[phase]?.status
    }

    @Test
    fun `index and compile both complete for a workspace within the limit`() {
        val h = Harness(fileCount = 3)
        h.run()

        assertEquals(PhaseStatus.COMPLETED, h.status(StartupPhase.WORKSPACE_INDEX))
        assertEquals(PhaseStatus.COMPLETED, h.status(StartupPhase.WORKSPACE_COMPILE))
    }

    @Test
    fun `compile is skipped for a workspace over the limit`() {
        val h = Harness(fileCount = 6, limit = 5)
        h.run()

        assertEquals(PhaseStatus.COMPLETED, h.status(StartupPhase.WORKSPACE_INDEX))
        assertEquals(PhaseStatus.SKIPPED, h.status(StartupPhase.WORKSPACE_COMPILE))
    }

    @Test
    fun `index and compile are skipped for an empty workspace`() {
        val h = Harness(fileCount = 0)
        h.run()

        assertEquals(PhaseStatus.SKIPPED, h.status(StartupPhase.WORKSPACE_INDEX))
        assertEquals(PhaseStatus.SKIPPED, h.status(StartupPhase.WORKSPACE_COMPILE))
    }

    @Test
    fun `a failed index fails its phase and skips compile`() {
        val h = Harness(fileCount = 3)
        coEvery { h.compilationService.indexAllWorkspaceSources(any(), any()) } throws IllegalStateException("boom")
        h.run()

        assertEquals(PhaseStatus.FAILED, h.status(StartupPhase.WORKSPACE_INDEX))
        assertEquals(PhaseStatus.SKIPPED, h.status(StartupPhase.WORKSPACE_COMPILE))
    }

    @Test
    fun `a cancelled index ends its phase as cancelled and leaves compile pending`() {
        val h = Harness(fileCount = 3)
        coEvery { h.compilationService.indexAllWorkspaceSources(any(), any()) } throws CancellationException("stopped")
        h.run()

        assertEquals(PhaseStatus.CANCELLED, h.status(StartupPhase.WORKSPACE_INDEX))
        assertNull(h.status(StartupPhase.WORKSPACE_COMPILE))
    }

    @Test
    fun `an Error from indexing fails its phase and skips compile before it escapes`() {
        val h = Harness(fileCount = 3)
        val error = SimulatedError()
        coEvery { h.compilationService.indexAllWorkspaceSources(any(), any()) } throws error
        h.run()

        assertEquals(PhaseStatus.FAILED, h.status(StartupPhase.WORKSPACE_INDEX))
        assertEquals(PhaseStatus.SKIPPED, h.status(StartupPhase.WORKSPACE_COMPILE))
        assertEquals(listOf<Throwable>(error), h.escaped.toList(), "the Error escapes indexing, as in production")
    }

    @Test
    fun `a phase skipped earlier is recorded when its work runs later`() {
        val h = Harness(fileCount = 3)
        h.phases.skip(listOf(StartupPhase.WORKSPACE_INDEX, StartupPhase.WORKSPACE_COMPILE))
        h.run()

        assertEquals(PhaseStatus.COMPLETED, h.status(StartupPhase.WORKSPACE_INDEX))
        assertEquals(PhaseStatus.COMPLETED, h.status(StartupPhase.WORKSPACE_COMPILE))
    }

    private class SimulatedError : Error("a stand-in for a JVM error")
}
