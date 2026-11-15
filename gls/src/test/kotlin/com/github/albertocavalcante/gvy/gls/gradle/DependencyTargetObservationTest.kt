// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.gradle

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.build.ResolutionStatus
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import com.github.albertocavalcante.gvy.build.gradle.GradleBuildTool
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Target observation runs inside the resolution attempt, before publication, and never weakens legacy results. */
class DependencyTargetObservationTest {
    private val root = Path.of("/tmp/observation-workspace")
    private val legacy = WorkspaceResolution(listOf(Path.of("/tmp/union.jar")), listOf(Path.of("/tmp/src")))
    private val tool = mockk<BuildTool> {
        coEvery { name } returns "Gradle"
        coEvery { resolve(any(), any(), any()) } returns legacy
    }
    private val tools = mockk<BuildToolManager> { coEvery { detectBuildTool(any()) } returns tool }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val published = CopyOnWriteArrayList<WorkspaceResolution>()

    @AfterEach
    fun close() = scope.cancel()

    private fun start(manager: DependencyManager, workspace: Path = root, done: CountDownLatch? = null) =
        manager.startAsyncResolution(
            workspaceRoot = workspace,
            onComplete = {
                published.add(it)
                done?.countDown()
            },
            enableFileWatching = false,
        )

    @Test
    fun `observed evidence is attached before the single publication`() {
        val evidence = TargetDependencyEvidence.Scoped(emptyList())
        val done = CountDownLatch(1)
        val manager = DependencyManager(tools, scope) { _, _, _ -> evidence }

        start(manager, done = done)

        assertTrue(done.await(5, TimeUnit.SECONDS))
        val resolution = published.single()
        assertSame(evidence, resolution.targetDependencies)
        assertEquals(legacy.dependencies, resolution.dependencies, "legacy lists are unchanged")
        assertEquals(legacy.sourceDirectories, resolution.sourceDirectories)
    }

    @Test
    fun `membership refresh is quiet avoids flat resolution and coalesces in flight events`() {
        val initial = CountDownLatch(1)
        val enteredRefresh = CountDownLatch(1)
        val refreshed = CountDownLatch(2)
        val release = CompletableDeferred<Unit>()
        val observations = AtomicInteger()
        val manager = DependencyManager(tools, scope) { _, _, _ ->
            if (observations.incrementAndGet() == 2) {
                enteredRefresh.countDown()
                release.await()
            }
            TargetDependencyEvidence.Scoped(emptyList())
        }
        manager.startAsyncResolution(
            root,
            onComplete = { initial.countDown() },
            onProgress = null,
            onError = null,
            options = DependencyManager.ResolutionOptions(false, onMembershipComplete = { refreshed.countDown() }),
        )
        assertTrue(initial.await(5, TimeUnit.SECONDS))
        manager.refreshSourceMembership()
        assertTrue(enteredRefresh.await(5, TimeUnit.SECONDS))
        repeat(3) { manager.refreshSourceMembership() }
        release.complete(Unit)
        assertTrue(refreshed.await(5, TimeUnit.SECONDS))
        assertEquals(3, observations.get())
        coVerify(exactly = 1) { tool.resolve(any(), any(), any()) }
    }

    @Test
    fun `membership refresh starts after a completed attempt without lifecycle reentry`() {
        val initial = CountDownLatch(1)
        val refreshed = CountDownLatch(1)
        val manager = DependencyManager(tools, scope) { _, _, _ -> TargetDependencyEvidence.Scoped(emptyList()) }
        manager.startAsyncResolution(
            root,
            onComplete = { initial.countDown() },
            onProgress = null,
            onError = null,
            options = DependencyManager.ResolutionOptions(false, onMembershipComplete = { refreshed.countDown() }),
        )
        assertTrue(initial.await(5, TimeUnit.SECONDS))

        manager.refreshSourceMembership()

        assertTrue(refreshed.await(5, TimeUnit.SECONDS), "membership refresh did not complete")
    }

    @Test
    fun `membership refresh waits until initial publication finishes`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val refreshed = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val manager = DependencyManager(tools, scope) { _, _, _ -> TargetDependencyEvidence.Scoped(emptyList()) }
        manager.startAsyncResolution(
            root,
            onComplete = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                published.set(true)
            },
            onProgress = null,
            onError = null,
            options = DependencyManager.ResolutionOptions(false, onMembershipComplete = {
                assertTrue(published.get(), "initial publication must finish before membership refresh")
                refreshed.countDown()
            }),
        )
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val caller = thread {
            try {
                manager.refreshSourceMembership()
            } finally {
                returned.countDown()
            }
        }
        try {
            assertFalse(refreshed.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertTrue(refreshed.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            caller.join(5_000)
        }
    }

    @Test
    fun `reset discards a queued refresh before a new workspace starts`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val refreshed = CountDownLatch(1)
        val nextPublished = CountDownLatch(1)
        val observations = AtomicInteger()
        val manager = DependencyManager(tools, scope) { _, _, _ ->
            if (observations.incrementAndGet() == 1) {
                entered.complete(Unit)
                try {
                    release.await()
                } finally {
                    exited.complete(Unit)
                }
            }
            TargetDependencyEvidence.Scoped(emptyList())
        }
        manager.startAsyncResolution(
            root,
            onComplete = {},
            onProgress = null,
            onError = null,
            options = DependencyManager.ResolutionOptions(false, onMembershipComplete = { refreshed.countDown() }),
        )
        withTimeout(5_000) { entered.await() }
        manager.refreshSourceMembership()
        manager.reset()
        release.complete(Unit)
        withTimeout(5_000) { exited.await() }
        start(manager, root.resolve("other"), nextPublished)

        assertTrue(nextPublished.await(5, TimeUnit.SECONDS))
        assertFalse(refreshed.await(100, TimeUnit.MILLISECONDS), "a reset must discard the old refresh")
        assertEquals(2, observations.get())
    }

    @Test
    fun `membership refresh retries after a transient tool detection failure`() = runBlocking {
        val detected = AtomicInteger()
        val failed = CountDownLatch(1)
        coEvery { tools.detectBuildTool(any()) } coAnswers {
            if (detected.incrementAndGet() == 2) {
                failed.countDown()
                error("temporary detection failure")
            }
            tool
        }
        val initial = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val manager = DependencyManager(tools, scope) { _, _, _ -> TargetDependencyEvidence.Scoped(emptyList()) }
        manager.startAsyncResolution(
            root,
            onComplete = { initial.countDown() },
            onProgress = null,
            onError = null,
            options = DependencyManager.ResolutionOptions(false, onMembershipComplete = { recovered.countDown() }),
        )
        assertTrue(initial.await(5, TimeUnit.SECONDS))
        manager.refreshSourceMembership()
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) {
            while (manager.getState() != DependencyManager.State.COMPLETED) delay(10)
        }
        manager.refreshSourceMembership()
        assertTrue(recovered.await(5, TimeUnit.SECONDS))
        assertEquals(3, detected.get())
    }

    @Test
    fun `a failed Gradle observation remains an applicable incomplete model`() = runBlocking {
        val gradle = mockk<GradleBuildTool>()
        val observer = TargetEvidenceObserver { _, _, _ -> error("observation unavailable") }
        val result = legacy.withObservedTargets(observer, gradle, root, 1)
        val evidence = kotlin.test.assertIs<TargetDependencyEvidence.Scoped>(result.targetDependencies)
        assertTrue(evidence.snapshots.isEmpty())
        assertFalse(evidence.enumerationComplete)
        assertEquals(legacy.dependencies, result.dependencies)
    }

    @Test
    fun `an observer failure still publishes the legacy result`() {
        val done = CountDownLatch(1)
        val manager = DependencyManager(tools, scope) { _, _, _ -> error("observation exploded") }

        start(manager, done = done)

        assertTrue(done.await(5, TimeUnit.SECONDS))
        val resolution = published.single()
        assertSame(TargetDependencyEvidence.Legacy, resolution.targetDependencies)
        assertEquals(legacy.dependencies, resolution.dependencies)
        assertSame(ResolutionStatus.Success, resolution.status)
    }

    @Test
    fun `an unusable legacy resolution is not observed`() {
        val failed = WorkspaceResolution.failed("TEST", "broken build")
        coEvery { tool.resolve(any(), any(), any()) } returns failed
        val calls = AtomicInteger()
        val done = CountDownLatch(1)
        val manager = DependencyManager(tools, scope) { _, _, _ ->
            calls.incrementAndGet()
            TargetDependencyEvidence.Legacy
        }

        start(manager, done = done)

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(0, calls.get())
        assertSame(failed, published.single())
    }

    @Test
    fun `a superseded attempt cancels its observer and does not publish its observation`() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<TargetDependencyEvidence>()
        val exited = CompletableDeferred<Unit>()
        val sawCancellation = AtomicBoolean(false)
        val stale = TargetDependencyEvidence.Scoped(emptyList())
        val other = Path.of("/tmp/other-workspace")
        val done = CountDownLatch(1)
        val manager = DependencyManager(tools, scope) { _, workspace, _ ->
            if (workspace == root) {
                first.complete(Unit)
                try {
                    release.await()
                } catch (cancelled: CancellationException) {
                    sawCancellation.set(true)
                    throw cancelled
                } finally {
                    exited.complete(Unit)
                }
            } else {
                TargetDependencyEvidence.Legacy
            }
        }

        start(manager)
        withTimeout(5_000) { first.await() }
        start(manager, other, done)

        // The stale observer must be cancelled, not merely fenced at publication
        val cancelled = withTimeoutOrNull(5_000) { exited.await() } != null && sawCancellation.get()
        release.complete(stale)
        assertTrue(cancelled, "the superseded observer was never cancelled")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(published.none { it.targetDependencies === stale }, "stale evidence was published: $published")
    }
}
