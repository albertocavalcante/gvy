package com.github.albertocavalcante.gvy.gls.gradle

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.build.ResolutionStatus
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Target observation runs inside the resolution attempt, before publication, and never weakens legacy results. */
class DependencyTargetObservationTest {
    private val root = Path.of("/tmp/observation-workspace")
    private val legacy = WorkspaceResolution(listOf(Path.of("/tmp/union.jar")), listOf(Path.of("/tmp/src")))
    private val tool = mockk<BuildTool> {
        coEvery { name } returns "Gradle"
        coEvery { resolve(any(), any()) } returns legacy
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
        coEvery { tool.resolve(any(), any()) } returns failed
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
    fun `a superseded attempt does not publish its observation`() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<TargetDependencyEvidence>()
        val stale = TargetDependencyEvidence.Scoped(emptyList())
        val other = Path.of("/tmp/other-workspace")
        val done = CountDownLatch(1)
        val manager = DependencyManager(tools, scope) { _, workspace, _ ->
            if (workspace == root) {
                first.complete(Unit)
                release.await()
            } else {
                TargetDependencyEvidence.Legacy
            }
        }

        start(manager)
        withTimeout(5_000) { first.await() }
        start(manager, other, done)
        release.complete(stale)

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(published.none { it.targetDependencies === stale }, "stale evidence was published: $published")
    }
}
