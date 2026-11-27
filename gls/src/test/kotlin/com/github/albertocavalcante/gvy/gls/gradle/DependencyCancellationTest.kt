// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.gradle

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolFileWatcher
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.build.ResolutionCancellation
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyCancellationTest {
    private val root = Path.of("/tmp/cancellation-workspace")

    @Test
    fun `reset signals a blocked attempt and ignores its late completion`() {
        exerciseCancellation { manager, _ -> manager.reset() }
    }

    @Test
    fun `shutdown signals a blocked attempt and ignores its late completion`() {
        exerciseCancellation { manager, _ -> manager.cancel() }
    }

    @Test
    fun `scope cancellation signals a blocked attempt before it returns`() {
        exerciseCancellation { _, scope -> scope.cancel() }
    }

    @Test
    fun `a new workspace signals the superseded attempt`() {
        exerciseCancellation { manager, _ ->
            manager.startAsyncResolution(
                root.resolve("next"),
                callbacks = ResolutionCallbacks(onComplete = {}),
                enableFileWatching = false,
            )
        }
    }

    @Test
    fun `a build file refresh carries the request on instead of cancelling it`() {
        val resolving = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val calls = AtomicInteger()
        lateinit var onChange: (Path) -> Unit
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.createWatcher(any(), any()) } answers {
            onChange = secondArg()
            mockk<BuildToolFileWatcher>(relaxed = true)
        }
        every { tool.resolve(any(), any(), any()) } answers {
            val token = thirdArg<ResolutionCancellation>()
            if (calls.incrementAndGet() == 1) {
                // The first attempt runs until the refresh supersedes it
                val superseded = CountDownLatch(1)
                token.onCancel { superseded.countDown() }.use {
                    resolving.countDown()
                    check(superseded.await(30, TimeUnit.SECONDS))
                }
                token.throwIfCancelled()
            }
            WorkspaceResolution(emptyList(), listOf(root))
        }
        val tools = mockk<BuildToolManager>()
        every { tools.detectBuildTool(root) } returns tool
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        try {
            manager.startAsyncResolution(
                root,
                callbacks = ResolutionCallbacks(
                    onComplete = { completed.countDown() },
                    onCancelled = { cancellations.incrementAndGet() },
                ),
            )
            assertTrue(resolving.await(5, TimeUnit.SECONDS))
            val firstAttempt = scope.coroutineContext[Job]!!.children.single()

            onChange(root.resolve("build.gradle"))

            assertTrue(completed.await(5, TimeUnit.SECONDS), "the refresh completes the request")
            // The superseded attempt's completion handler has run once its job is complete
            runBlocking { withTimeout(5_000) { firstAttempt.join() } }
            assertEquals(0, cancellations.get(), "a refresh is not a cancellation of the request")
        } finally {
            manager.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `failed watcher shutdown does not strand the next workspace`() {
        val firstDone = CountDownLatch(1)
        val nextDone = CountDownLatch(1)
        val watcher = mockk<BuildToolFileWatcher>(relaxed = true)
        every { watcher.stopWatching() } throws IllegalStateException("watcher failure")
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.createWatcher(any(), any()) } returns watcher
        every { tool.resolve(any(), any(), any()) } returns WorkspaceResolution(emptyList(), listOf(root))
        val tools = mockk<BuildToolManager>()
        every { tools.detectBuildTool(root) } returns tool
        every { tools.detectBuildTool(root.resolve("next")) } returns null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        try {
            manager.startAsyncResolution(
                root,
                callbacks = ResolutionCallbacks(onComplete = { firstDone.countDown() }),
            )
            assertTrue(firstDone.await(5, TimeUnit.SECONDS))
            manager.startAsyncResolution(
                root.resolve("next"),
                callbacks = ResolutionCallbacks(onComplete = { nextDone.countDown() }),
            )
            assertTrue(nextDone.await(5, TimeUnit.SECONDS))
            assertEquals(DependencyManager.State.COMPLETED, manager.getState())
        } finally {
            manager.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `client event dispatch does not hold the manager lock and old watcher cannot refresh`() {
        val completed = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resetDone = CountDownLatch(1)
        val detections = AtomicInteger()
        lateinit var onChange: (Path) -> Unit
        val watcher = mockk<BuildToolFileWatcher>(relaxed = true)
        every { watcher.onFileChanged(any()) } answers {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS))
            onChange(firstArg())
        }
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.createWatcher(any(), any()) } answers {
            onChange = secondArg()
            watcher
        }
        every { tool.resolve(any(), any(), any()) } returns WorkspaceResolution(emptyList(), listOf(root))
        val tools = mockk<BuildToolManager>()
        every { tools.detectBuildTool(root) } answers {
            detections.incrementAndGet()
            tool
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        try {
            manager.startAsyncResolution(
                root,
                callbacks = ResolutionCallbacks(onComplete = { completed.countDown() }),
            )
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val event = Thread { manager.onWatchedFilesChanged(listOf(root.resolve("build.gradle"))) }
            event.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val reset = Thread {
                manager.reset()
                resetDone.countDown()
            }
            reset.start()
            assertTrue(resetDone.await(5, TimeUnit.SECONDS), "Client dispatch must not hold the manager lock")
            release.countDown()
            event.join(5_000)
            reset.join(5_000)
            assertTrue(!event.isAlive)
            assertEquals(1, detections.get(), "A detached watcher must not start another resolution")
        } finally {
            release.countDown()
            manager.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `client event returns while a completion callback holds the manager lock`() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val changed = root.resolve("build.gradle")
        val watcher = mockk<BuildToolFileWatcher>(relaxed = true)
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.createWatcher(any(), any()) } returns watcher
        every { tool.resolve(any(), any(), any()) } returns WorkspaceResolution(emptyList(), listOf(root))
        val tools = mockk<BuildToolManager>()
        every { tools.detectBuildTool(root) } returns tool
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        try {
            // The watcher is installed before resolving, so it is current while the callback holds the lock.
            manager.startAsyncResolution(
                root,
                callbacks = ResolutionCallbacks(
                    onComplete = {
                        callbackEntered.countDown()
                        check(releaseCallback.await(30, TimeUnit.SECONDS))
                    },
                ),
            )
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))

            // The LSP message thread delivers this; a blocked event would stall every later client message.
            val event = Thread { manager.onWatchedFilesChanged(listOf(changed)) }
            event.start()
            event.join(5_000)

            assertFalse(event.isAlive, "A client event must not wait for a completion callback")
            verify(exactly = 1) { watcher.onFileChanged(changed) }
        } finally {
            releaseCallback.countDown()
            manager.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `already cancelled scope does not launch resolution or publish`() {
        val tools = mockk<BuildToolManager>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        val callbacks = AtomicInteger()
        scope.cancel()
        manager.startAsyncResolution(
            root,
            callbacks = ResolutionCallbacks(
                onProgress = { _, _ -> callbacks.incrementAndGet() },
                onComplete = { callbacks.incrementAndGet() },
                onError = { callbacks.incrementAndGet() },
            ),
            enableFileWatching = false,
        )
        assertEquals(DependencyManager.State.NOT_STARTED, manager.getState())
        assertEquals(0, callbacks.get())
        io.mockk.verify(exactly = 0) { tools.detectBuildTool(any()) }
    }

    @Test
    fun `cancel signals the transport before waiting for a progress callback`() {
        signalBeforeProgressReturns { manager -> manager.cancel() }
    }

    @Test
    fun `reset signals the transport before waiting for a progress callback`() {
        signalBeforeProgressReturns { manager -> manager.reset() }
    }

    @Test
    fun `supersede signals the transport before waiting for a progress callback`() {
        signalBeforeProgressReturns { manager ->
            manager.startAsyncResolution(
                root.resolve("next"),
                callbacks = ResolutionCallbacks(onComplete = {}),
                enableFileWatching = false,
            )
        }
    }

    private fun signalBeforeProgressReturns(transition: (DependencyManager) -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val signalled = CountDownLatch(1)
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.resolve(any(), any(), any()) } answers {
            val progress = secondArg<(String) -> Unit>()
            val token = thirdArg<ResolutionCancellation>()
            token.onCancel { signalled.countDown() }.use {
                progress("in flight")
                token.throwIfCancelled()
            }
            WorkspaceResolution(emptyList(), emptyList())
        }
        val tools = mockk<BuildToolManager>()
        every { tools.detectBuildTool(root) } returns tool
        every { tools.detectBuildTool(root.resolve("next")) } returns null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        try {
            manager.startAsyncResolution(
                root,
                callbacks = ResolutionCallbacks(
                    onProgress = { _, message ->
                        if (message == "in flight") {
                            entered.countDown()
                            check(release.await(30, TimeUnit.SECONDS))
                        }
                    },
                    onComplete = {},
                ),
                enableFileWatching = false,
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val caller = Thread { transition(manager) }
            caller.start()
            assertTrue(signalled.await(5, TimeUnit.SECONDS), "Transport must be signalled before publication unlock")
            release.countDown()
            caller.join(5_000)
            assertTrue(!caller.isAlive)
        } finally {
            release.countDown()
            manager.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `cancellation listener can wait for a concurrent reset`() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        lateinit var manager: DependencyManager
        every { tool.resolve(any(), any(), any()) } answers {
            val token = thirdArg<ResolutionCancellation>()
            token.onCancel {
                val reset = Thread { manager.reset() }
                reset.start()
                reset.join(5_000)
                check(!reset.isAlive) { "Reset could not acquire the manager lock" }
                released.countDown()
            }.use {
                entered.countDown()
                check(released.await(5, TimeUnit.SECONDS))
            }
            WorkspaceResolution(emptyList(), emptyList())
        }
        val tools = mockk<BuildToolManager>()
        every { tools.detectBuildTool(root) } returns tool
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = DependencyManager(tools, scope)
        try {
            manager.startAsyncResolution(
                root,
                callbacks = ResolutionCallbacks(onComplete = {}),
                enableFileWatching = false,
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            manager.cancel()
            assertTrue(released.await(5, TimeUnit.SECONDS))
        } finally {
            manager.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `lifecycle reentry from progress is rejected before mutation`() {
        val tools = mockk<BuildToolManager>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        try {
            manager.startAsyncResolution(
                root,
                callbacks = ResolutionCallbacks(
                    onProgress = { _, _ ->
                        kotlin.test.assertFailsWith<IllegalStateException> { manager.cancel() }
                    },
                    onComplete = {},
                ),
                enableFileWatching = false,
            )
        } finally {
            manager.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `an older completion cannot overwrite a newer workspace publication`() {
        val oldCallbackEntered = CountDownLatch(1)
        val releaseOldCallback = CountDownLatch(1)
        val newCallbackDone = CountDownLatch(1)
        val oldCallbackDone = CountDownLatch(1)
        val oldAttemptSignalled = CountDownLatch(1)
        val published = AtomicReference<Path>()
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.resolve(any(), any(), any()) } answers {
            if (firstArg<Path>() == root) {
                thirdArg<ResolutionCancellation>().onCancel { oldAttemptSignalled.countDown() }
            }
            WorkspaceResolution(listOf(firstArg<Path>()), emptyList())
        }
        val tools = mockk<BuildToolManager>()
        every { tools.detectBuildTool(any()) } returns tool
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        val next = root.resolve("next")
        try {
            manager.startAsyncResolution(
                root,
                callbacks = ResolutionCallbacks(
                    onComplete = {
                        oldCallbackEntered.countDown()
                        check(releaseOldCallback.await(30, TimeUnit.SECONDS))
                        published.set(root)
                        oldCallbackDone.countDown()
                    },
                ),
                enableFileWatching = false,
            )
            assertTrue(oldCallbackEntered.await(5, TimeUnit.SECONDS))
            val startNext = Thread {
                manager.startAsyncResolution(
                    next,
                    callbacks = ResolutionCallbacks(
                        onComplete = {
                            published.set(next)
                            newCallbackDone.countDown()
                        },
                    ),
                    enableFileWatching = false,
                )
            }
            startNext.start()
            assertTrue(oldAttemptSignalled.await(5, TimeUnit.SECONDS), "Supersede must signal the old attempt")
            assertTrue(!newCallbackDone.await(200, TimeUnit.MILLISECONDS), "Successor published before old callback")
            releaseOldCallback.countDown()
            startNext.join(5_000)
            assertTrue(oldCallbackDone.await(5, TimeUnit.SECONDS))
            assertTrue(newCallbackDone.await(5, TimeUnit.SECONDS))
            assertEquals(next, published.get())
        } finally {
            releaseOldCallback.countDown()
            manager.cancel()
            scope.cancel()
        }
    }

    /**
     * Blocks an attempt in its build tool, applies [cancel], and checks that the transport is signalled, that the late
     * result is not published, and that the request is told once that it was cancelled: every caller stops the
     * request's attempt with nothing carrying the request on.
     */
    private fun exerciseCancellation(cancel: (DependencyManager, CoroutineScope) -> Unit) {
        val entered = CountDownLatch(1)
        val signalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val published = AtomicInteger()
        val cancelled = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val tool = mockk<BuildTool>(relaxed = true)
        every { tool.name } returns "Gradle"
        every { tool.resolve(any(), any(), any()) } answers {
            val token = thirdArg<ResolutionCancellation>()
            token.onCancel { signalled.countDown() }.use {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
            returned.countDown()
            WorkspaceResolution(listOf(root.resolve("stale.jar")), emptyList())
        }
        val tools = mockk<BuildToolManager>()
        every { tools.detectBuildTool(root) } returns tool
        every { tools.detectBuildTool(root.resolve("next")) } returns null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        try {
            manager.startAsyncResolution(
                root,
                callbacks = ResolutionCallbacks(
                    onComplete = { published.incrementAndGet() },
                    onError = { published.incrementAndGet() },
                    onCancelled = {
                        cancellations.incrementAndGet()
                        cancelled.countDown()
                    },
                ),
                enableFileWatching = false,
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val job = scope.coroutineContext[Job]!!.children.single()
            cancel(manager, scope)
            assertTrue(signalled.await(5, TimeUnit.SECONDS), "Cancellation must not wait for the blocked call")
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            runBlocking { withTimeout(5_000) { job.join() } }
            assertEquals(0, published.get())
            assertTrue(cancelled.await(5, TimeUnit.SECONDS), "the request was never told it was cancelled")
            assertEquals(1, cancellations.get())
        } finally {
            release.countDown()
            manager.cancel()
            scope.cancel()
        }
    }
}
