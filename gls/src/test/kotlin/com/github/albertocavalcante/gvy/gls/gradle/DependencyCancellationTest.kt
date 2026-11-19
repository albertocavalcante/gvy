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
            manager.startAsyncResolution(root.resolve("next"), onComplete = {}, enableFileWatching = false)
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
            manager.startAsyncResolution(root, onComplete = { firstDone.countDown() })
            assertTrue(firstDone.await(5, TimeUnit.SECONDS))
            manager.startAsyncResolution(root.resolve("next"), onComplete = { nextDone.countDown() })
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
            manager.startAsyncResolution(root, onComplete = { completed.countDown() })
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
    fun `already cancelled scope does not launch resolution or publish`() {
        val tools = mockk<BuildToolManager>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        val callbacks = AtomicInteger()
        scope.cancel()
        manager.startAsyncResolution(
            root,
            onProgress = { _, _ -> callbacks.incrementAndGet() },
            onComplete = { callbacks.incrementAndGet() },
            onError = { callbacks.incrementAndGet() },
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
            manager.startAsyncResolution(root.resolve("next"), onComplete = {}, enableFileWatching = false)
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
            manager.startAsyncResolution(root, onProgress = { _, message ->
                if (message == "in flight") {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
            }, onComplete = {}, enableFileWatching = false)
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
            manager.startAsyncResolution(root, onComplete = {}, enableFileWatching = false)
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
                onProgress = { _, _ ->
                    kotlin.test.assertFailsWith<IllegalStateException> { manager.cancel() }
                },
                onComplete = {},
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
            manager.startAsyncResolution(root, onComplete = {
                oldCallbackEntered.countDown()
                check(releaseOldCallback.await(30, TimeUnit.SECONDS))
                published.set(root)
                oldCallbackDone.countDown()
            }, enableFileWatching = false)
            assertTrue(oldCallbackEntered.await(5, TimeUnit.SECONDS))
            val startNext = Thread {
                manager.startAsyncResolution(next, onComplete = {
                    published.set(next)
                    newCallbackDone.countDown()
                }, enableFileWatching = false)
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

    private fun exerciseCancellation(cancel: (DependencyManager, CoroutineScope) -> Unit) {
        val entered = CountDownLatch(1)
        val signalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val published = AtomicInteger()
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
                onComplete = { published.incrementAndGet() },
                onError = { published.incrementAndGet() },
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
        } finally {
            release.countDown()
            manager.cancel()
            scope.cancel()
        }
    }
}
