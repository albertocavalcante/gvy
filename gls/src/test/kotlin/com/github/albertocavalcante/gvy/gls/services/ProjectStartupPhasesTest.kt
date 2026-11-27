// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolFileWatcher
import com.github.albertocavalcante.gvy.build.ResolutionCancellation
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import com.github.albertocavalcante.gvy.gls.BuildInfo
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.telemetry.PhaseStatus
import com.github.albertocavalcante.gvy.gls.telemetry.StartupPhase
import com.github.albertocavalcante.gvy.gls.telemetry.StartupPhaseRecorder
import com.github.albertocavalcante.gvy.gls.telemetry.serverStats
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How each startup phase ends for the ways a workspace can start, stop or fail. */
class ProjectStartupPhasesTest {
    @TempDir
    lateinit var tempDirectory: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val phases = StartupPhaseRecorder()
    private val ready = CountDownLatch(1)

    // One permit for every time startup reports it is quiescent, so a test can wait for a second report
    private val quiescentReports = Semaphore(0)

    private val workspaceRoot: Path by lazy { Files.createDirectory(tempDirectory.resolve("secret-project")) }

    @Test
    fun `a workspace without a build tool completes setup resolve and publish`() {
        val startup = startup(buildTools = emptyList())

        try {
            start(startup)
            assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS), "startup never became ready")
        } finally {
            scope.cancel()
        }

        assertEquals(PhaseStatus.COMPLETED, status(StartupPhase.WORKSPACE_SETUP))
        assertEquals(PhaseStatus.COMPLETED, status(StartupPhase.DEPENDENCIES_RESOLVE))
        assertEquals(PhaseStatus.COMPLETED, status(StartupPhase.WORKSPACE_MODEL_PUBLISH))
        assertEquals(PhaseStatus.SKIPPED, status(StartupPhase.WORKSPACE_INDEX), "the workspace has no sources")
        assertEquals(PhaseStatus.SKIPPED, status(StartupPhase.WORKSPACE_COMPILE), "the workspace has no sources")
    }

    @Test
    fun `shutting down during resolution cancels it and leaves the later phases pending`() {
        val resolving = CountDownLatch(1)
        val startup = startup(buildTools = listOf(blockingTool(resolving)))

        try {
            start(startup)
            assertTrue(resolving.await(WAIT_SECONDS, TimeUnit.SECONDS), "resolution never started")
            startup.shutdown()
            awaitStatus(StartupPhase.DEPENDENCIES_RESOLVE, PhaseStatus.CANCELLED)
        } finally {
            scope.cancel()
        }

        assertEquals(PhaseStatus.COMPLETED, status(StartupPhase.WORKSPACE_SETUP))
        assertNull(status(StartupPhase.WORKSPACE_MODEL_PUBLISH))
        assertNull(status(StartupPhase.WORKSPACE_INDEX))
        assertNull(status(StartupPhase.WORKSPACE_COMPILE))
    }

    @Test
    fun `a build tool that throws fails resolution and skips the later phases`() {
        val failing = mockk<BuildTool>(relaxed = true) {
            every { name } returns "Broken"
            every { canHandle(any()) } returns true
            every { createWatcher(any(), any()) } returns null
            every { resolve(any(), any(), any()) } throws IllegalStateException("the build is broken")
        }
        val startup = startup(buildTools = listOf(failing))

        try {
            start(startup)
            assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS), "startup never became ready")
        } finally {
            scope.cancel()
        }

        assertResolutionFailedAndLaterPhasesSkipped()
    }

    @Test
    fun `a build tool that cannot be detected fails resolution through the error callback`() {
        val undetectable = mockk<BuildTool>(relaxed = true) {
            every { name } returns "Undetectable"
            every { canHandle(any()) } throws IllegalStateException("cannot tell")
        }
        val startup = startup(buildTools = listOf(undetectable))

        try {
            start(startup)
            assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS), "startup never became ready")
        } finally {
            scope.cancel()
        }

        assertResolutionFailedAndLaterPhasesSkipped()
    }

    @Test
    fun `a watcher failure ends resolution as failed while the later phases record their real run`() {
        val watcher = mockk<BuildToolFileWatcher>(relaxed = true) {
            every { startWatching(any()) } throws IllegalStateException("cannot watch")
        }
        val watching = mockk<BuildTool>(relaxed = true) {
            every { name } returns "Watching"
            every { canHandle(any()) } returns true
            every { createWatcher(any(), any()) } returns watcher
            every { resolve(any(), any(), any()) } returns WorkspaceResolution(emptyList(), emptyList())
        }
        val startup = startup(buildTools = listOf(watching))

        try {
            start(startup)
            awaitStatus(StartupPhase.WORKSPACE_MODEL_PUBLISH, PhaseStatus.COMPLETED)
        } finally {
            scope.cancel()
        }

        assertEquals(PhaseStatus.FAILED, status(StartupPhase.DEPENDENCIES_RESOLVE), "the first outcome is final")
        assertEquals(PhaseStatus.COMPLETED, status(StartupPhase.WORKSPACE_SETUP))
    }

    @Test
    fun `a model publish that throws fails its phase and skips indexing and compiling`() {
        val startup = startup(buildTools = emptyList(), onPublish = { throw IllegalStateException("cannot publish") })

        try {
            start(startup)
            awaitStatus(StartupPhase.WORKSPACE_MODEL_PUBLISH, PhaseStatus.FAILED)
            awaitStatus(StartupPhase.WORKSPACE_INDEX, PhaseStatus.SKIPPED)
        } finally {
            scope.cancel()
        }

        assertEquals(PhaseStatus.SKIPPED, status(StartupPhase.WORKSPACE_COMPILE))
    }

    @Test
    fun `a refresh that completes again leaves the recorded phases unchanged`() {
        var changed: ((Path) -> Unit)? = null
        val watcher = mockk<BuildToolFileWatcher>(relaxed = true)
        val watching = mockk<BuildTool>(relaxed = true) {
            every { name } returns "Watching"
            every { canHandle(any()) } returns true
            every { createWatcher(any(), any()) } answers {
                changed = secondArg()
                watcher
            }
            every { resolve(any(), any(), any()) } returns WorkspaceResolution(emptyList(), emptyList())
        }
        val startup = startup(buildTools = listOf(watching))

        try {
            start(startup)
            assertTrue(quiescentReports.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS), "startup never became ready")
            val recorded = phases.snapshot().phases

            checkNotNull(changed).invoke(workspaceRoot)
            assertTrue(quiescentReports.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS), "the refresh never completed")

            assertEquals(recorded, phases.snapshot().phases)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `no workspace root skips every workspace phase`() {
        val startup = startup(buildTools = emptyList())

        startup.startAsyncDependencyResolution(client(), InitializeParams(), null, {})

        assertAllWorkspacePhasesSkipped()
    }

    @Test
    fun `no saved initialization parameters skips every workspace phase`() {
        val startup = startup(buildTools = emptyList())

        startup.startAsyncDependencyResolution(client(), null, null, {})

        assertAllWorkspacePhasesSkipped()
    }

    @Test
    fun `the serialised stats never contain the workspace directory name`() {
        val startup = startup(buildTools = emptyList())

        try {
            start(startup)
            assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS), "startup never became ready")
        } finally {
            scope.cancel()
        }

        val json = Gson().toJson(serverStats(BuildInfo.fromProperties(Properties()), phases.snapshot()))
        assertFalse("secret-project" in json, json)
    }

    private fun assertResolutionFailedAndLaterPhasesSkipped() {
        assertEquals(PhaseStatus.FAILED, status(StartupPhase.DEPENDENCIES_RESOLVE))
        assertEquals(PhaseStatus.SKIPPED, status(StartupPhase.WORKSPACE_MODEL_PUBLISH))
        assertEquals(PhaseStatus.SKIPPED, status(StartupPhase.WORKSPACE_INDEX))
        assertEquals(PhaseStatus.SKIPPED, status(StartupPhase.WORKSPACE_COMPILE))
    }

    private fun assertAllWorkspacePhasesSkipped() {
        StartupPhase.entries.filter { it != StartupPhase.LSP_INITIALIZE }.forEach { phase ->
            assertEquals(PhaseStatus.SKIPPED, status(phase), phase.wireName)
        }
    }

    private fun status(phase: StartupPhase): PhaseStatus? = phases.snapshot().phases[phase]?.status

    private fun awaitStatus(phase: StartupPhase, expected: PhaseStatus) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (status(phase) != expected && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
        assertEquals(expected, status(phase), "${phase.wireName} did not reach $expected")
    }

    private fun start(startup: ProjectStartupManager) {
        val params = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(workspaceRoot.toUri().toString(), "ws"))
        }
        startup.startAsyncDependencyResolution(client(), params, null, {}) { _, quiescent, _, _, _, _, _ ->
            if (quiescent) {
                ready.countDown()
                quiescentReports.release()
            }
        }
    }

    private fun client(): LanguageClient = mockk(relaxed = true)

    private fun startup(buildTools: List<BuildTool>, onPublish: () -> Unit = {}): ProjectStartupManager {
        val workspace = mockk<WorkspaceManager>(relaxed = true) {
            every { getSourceRoots() } returns emptyList()
            every { getWorkspaceSourceUris() } returns emptyList()
        }
        val compilation = mockk<GroovyCompilationService>(relaxed = true) {
            every { workspaceManager } returns workspace
            every { updateWorkspaceModel(any(), any(), any(), any()) } answers { onPublish() }
        }
        return ProjectStartupManager(
            compilation,
            buildTools,
            scope,
            ProjectStrategyRegistry(),
            startupPhases = phases,
        )
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

    private companion object {
        const val WAIT_SECONDS = 10L
        const val POLL_MILLIS = 10L
    }
}
