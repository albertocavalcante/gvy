package com.github.albertocavalcante.gvy.gls.gradle

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolFileWatcher
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DependencyRecoveryTest {
    private val root: Path = Path.of("/tmp/dependency-recovery")
    private val recovered = WorkspaceResolution(listOf(root.resolve("fixed.jar")), listOf(root))

    @Test
    fun `same workspace can retry after a failed attempt`(): Unit = runBlocking {
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.resolve(any(), any()) } throws IllegalStateException("offline") andThen recovered
        withManager(tool) { manager, _ ->
            val failed = CompletableDeferred<WorkspaceResolution>()
            manager.startAsyncResolution(root, onComplete = { failed.complete(it) }, enableFileWatching = false)
            withTimeout(TIMEOUT) { failed.await() }
            assertEquals(DependencyManager.State.FAILED, manager.getState())
            val retried = CompletableDeferred<WorkspaceResolution>()

            manager.startAsyncResolution(root, onComplete = { retried.complete(it) }, enableFileWatching = false)

            assertEquals(recovered, withTimeout(TIMEOUT) { retried.await() })
            assertEquals(recovered.dependencies, manager.getCurrentDependencies())
            assertEquals(DependencyManager.State.COMPLETED, manager.getState())
        }
    }

    @Test
    fun `build file watcher permits recovery after the initial failure`(): Unit = runBlocking {
        val tool = mockk<BuildTool>(relaxed = true)
        val watcher = mockk<BuildToolFileWatcher>(relaxed = true)
        var change: ((Path) -> Unit)? = null
        every { tool.name } returns "Gradle"
        every { tool.createWatcher(any(), any()) } answers {
            change = secondArg()
            watcher
        }
        every { tool.resolve(any(), any()) } throws IllegalStateException("offline") andThen recovered
        withManager(tool) { manager, _ ->
            val first = CompletableDeferred<WorkspaceResolution>()
            val second = CompletableDeferred<WorkspaceResolution>()
            manager.startAsyncResolution(root, onComplete = { if (!first.complete(it)) second.complete(it) })
            withTimeout(TIMEOUT) { first.await() }

            assertNotNull(change, "Watching must be installed even if the first resolve fails")(root)

            assertEquals(recovered, withTimeout(TIMEOUT) { second.await() })
            assertEquals(DependencyManager.State.COMPLETED, manager.getState())
        }
    }

    @Test
    fun `cancellation is not reported as a failed dependency resolution`(): Unit = runBlocking {
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.resolve(any(), any()) } throws CancellationException("stopped")
        withManager(tool) { manager, scope ->
            val detected = CountDownLatch(1)
            every { tool.resolve(any(), any()) } answers
                {
                    detected.countDown()
                    throw CancellationException("stopped")
                }
            val callbacks = AtomicInteger()
            manager.startAsyncResolution(
                root,
                onComplete = { callbacks.incrementAndGet() },
                onError = { callbacks.incrementAndGet() },
                enableFileWatching = false,
            )
            assertTrue(detected.await(5, TimeUnit.SECONDS))
            withTimeout(TIMEOUT) { scope.coroutineContext[Job]!!.children.toList().joinAll() }

            assertEquals(0, callbacks.get())
            assertEquals(DependencyManager.State.NOT_STARTED, manager.getState())
        }
    }

    @Test
    fun `watcher cancellation does not report failure or continue resolving`(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.createWatcher(any(), any()) } answers {
            entered.countDown()
            throw CancellationException("watcher stopped")
        }
        withManager(tool) { manager, scope ->
            val callbacks = AtomicInteger()
            manager.startAsyncResolution(
                root,
                onComplete = { callbacks.incrementAndGet() },
                onError = { callbacks.incrementAndGet() },
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(TIMEOUT) { scope.coroutineContext[Job]!!.children.toList().joinAll() }

            assertEquals(0, callbacks.get())
            assertEquals(DependencyManager.State.NOT_STARTED, manager.getState())
            verify(exactly = 0) { tool.resolve(any(), any()) }
        }
    }

    @Test
    fun `an edit during watcher startup supersedes the old attempt before resolving`(): Unit = runBlocking {
        val tool = mockk<BuildTool>(relaxed = true)
        val watcher = mockk<BuildToolFileWatcher>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.resolve(any(), any()) } returns recovered
        every { tool.createWatcher(any(), any()) } answers {
            val onChange = secondArg<(Path) -> Unit>()
            every { watcher.startWatching(any()) } answers { onChange(root) }
            watcher
        }
        withManager(tool) { manager, scope ->
            val completed = CompletableDeferred<WorkspaceResolution>()
            manager.startAsyncResolution(root, onComplete = { completed.complete(it) })
            assertEquals(recovered, withTimeout(TIMEOUT) { completed.await() })
            withTimeout(TIMEOUT) { scope.coroutineContext[Job]!!.children.toList().joinAll() }

            verify(exactly = 1) { tool.resolve(any(), any()) }
            assertEquals(DependencyManager.State.COMPLETED, manager.getState())
        }
    }

    @Test
    fun `a returned failed resolution remains retryable`(): Unit = runBlocking {
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.resolve(any(), any()) } returns WorkspaceResolution.failed("OFFLINE", "offline") andThen recovered
        withManager(tool) { manager, _ ->
            val first = CompletableDeferred<WorkspaceResolution>()
            manager.startAsyncResolution(root, onComplete = { first.complete(it) }, enableFileWatching = false)
            withTimeout(TIMEOUT) { first.await() }
            assertEquals(DependencyManager.State.FAILED, manager.getState())
            val second = CompletableDeferred<WorkspaceResolution>()

            manager.startAsyncResolution(root, onComplete = { second.complete(it) }, enableFileWatching = false)

            assertEquals(recovered, withTimeout(TIMEOUT) { second.await() })
        }
    }

    @Test
    fun `a cancelled blocking attempt cannot overwrite the recovered result`(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.resolve(any(), any()) } answers {
            if (attempts.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                WorkspaceResolution(listOf(root.resolve("obsolete.jar")), emptyList())
            } else {
                recovered
            }
        }
        try {
            withManager(tool) { manager, scope ->
                val completions = mutableListOf<WorkspaceResolution>()
                manager.startAsyncResolution(
                    root,
                    onComplete = { synchronized(completions) { completions.add(it) } },
                    enableFileWatching = false,
                )
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                manager.reset()
                val retried = CompletableDeferred<WorkspaceResolution>()
                manager.startAsyncResolution(root, onComplete = { retried.complete(it) }, enableFileWatching = false)
                assertEquals(recovered, withTimeout(TIMEOUT) { retried.await() })

                release.countDown()
                withTimeout(TIMEOUT) { scope.coroutineContext[Job]!!.children.toList().joinAll() }

                assertTrue(completions.isEmpty(), "The cancelled attempt must never publish completion")
                assertEquals(recovered.dependencies, manager.getCurrentDependencies())
                assertEquals(DependencyManager.State.COMPLETED, manager.getState())
            }
        } finally {
            release.countDown()
        }
    }

    private suspend fun withManager(tool: BuildTool, block: suspend (DependencyManager, CoroutineScope) -> Unit) {
        val tools = mockk<BuildToolManager>()
        every { tools.detectBuildTool(any()) } returns tool
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        try {
            block(manager, scope)
        } finally {
            manager.cancel()
            scope.cancel()
        }
    }

    companion object {
        private const val TIMEOUT = 5_000L
    }
}
