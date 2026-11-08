package com.github.albertocavalcante.gvy.build

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.writeText

class DefaultBuildFileWatcherTest {

    @TempDir
    lateinit var tempDir: Path

    private val testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watcher: DefaultBuildFileWatcher? = null

    @AfterEach
    fun cleanup() {
        watcher?.stopWatching()
        testScope.cancel()
    }

    @Test
    fun `should detect build gradle file creation`() = runBlocking {
        val events = CopyOnWriteArrayList<Path>()
        val latch = CountDownLatch(1)

        watcher = DefaultBuildFileWatcher(
            logLabel = "Test",
            coroutineScope = testScope,
            onBuildFileChanged = {
                events.add(it)
                latch.countDown()
            },
            buildFileNames = setOf("build.gradle"),
            debounceDelayMs = 50, // Short delay for testing
        ).also { it.startWatching(tempDir) }

        // Create file
        tempDir.resolve("build.gradle").writeText("// new file")

        // Wait for event
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for file creation event")
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it == tempDir })
    }

    @Test
    fun `should detect build gradle modification`() = runBlocking {
        // Create file first
        val buildFile = tempDir.resolve("build.gradle")
        buildFile.writeText("// initial")

        val events = CopyOnWriteArrayList<Path>()
        val latch = CountDownLatch(1)

        watcher = DefaultBuildFileWatcher(
            logLabel = "Test",
            coroutineScope = testScope,
            onBuildFileChanged = {
                events.add(it)
                latch.countDown()
            },
            buildFileNames = setOf("build.gradle"),
            debounceDelayMs = 50,
        ).also { it.startWatching(tempDir) }

        // Modify file
        buildFile.writeText("// modified")

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for file modification event")
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it == tempDir })
    }

    @Test
    fun `should ignore non-build files`() = runBlocking {
        val events = CopyOnWriteArrayList<Path>()

        watcher = DefaultBuildFileWatcher(
            logLabel = "Test",
            coroutineScope = testScope,
            onBuildFileChanged = { events.add(it) },
            buildFileNames = setOf("build.gradle"),
            debounceDelayMs = 50,
        ).also { it.startWatching(tempDir) }

        delay(200)

        // Modify ignored file
        tempDir.resolve("README.md").writeText("ignored")

        delay(500)
        assertTrue(events.isEmpty(), "Should ignore non-build files")
    }

    @Test
    fun `should stop watching`() = runBlocking {
        val events = CopyOnWriteArrayList<Path>()

        watcher = DefaultBuildFileWatcher(
            logLabel = "Test",
            coroutineScope = testScope,
            onBuildFileChanged = { events.add(it) },
            buildFileNames = setOf("build.gradle"),
            debounceDelayMs = 50,
        ).also { it.startWatching(tempDir) }

        assertTrue(watcher!!.isWatching())

        delay(200)
        watcher!!.stopWatching()
        delay(100)

        assertTrue(!watcher!!.isWatching())

        // Modify file - should be ignored
        tempDir.resolve("build.gradle").writeText("// modified")

        delay(500)
        assertTrue(events.isEmpty(), "Should not receive events after stopping")
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `stopping during callback dispatch cancels the unpublished callback`(): Unit = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = PausedFirstDispatch(StandardTestDispatcher(scheduler))
        val events = CopyOnWriteArrayList<Path>()
        watcher = DefaultBuildFileWatcher(
            logLabel = "Test",
            coroutineScope = testScope,
            onBuildFileChanged = { events.add(it) },
            buildFileNames = setOf("build.gradle"),
            debounceDelayMs = 0, // Isolate dispatch publication from the timer implementation.
            dispatcher = dispatcher,
        ).also { it.startWatching(tempDir) }
        val watchJob = generateSequence(checkNotNull(testScope.coroutineContext[Job])) {
            it.children.singleOrNull()
        }.last()

        try {
            tempDir.resolve("build.gradle").writeText("// changed")
            assertTrue(dispatcher.entered.await(10, TimeUnit.SECONDS), "Expected actual file event to dispatch")

            // launch() has entered dispatch but has not yet returned its Job to the watcher.
            watcher?.stopWatching()
            dispatcher.release.countDown()
            withTimeout(10_000) { watchJob.join() }
            assertEquals(0L, dispatcher.returned.count, "The paused dispatch must finish replaying")
            scheduler.advanceUntilIdle()

            assertTrue(events.isEmpty(), "A callback queued during stop must be cancelled with its watcher")
        } finally {
            dispatcher.release.countDown()
            watcher?.stopWatching()
        }
    }

    private class PausedFirstDispatch(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Callback dispatch was not released" }
            }
            delegate.dispatch(context, block)
            returned.countDown()
        }
    }

    @Test
    fun `registration failure can be followed by a valid watch`() {
        val changed = CountDownLatch(1)
        watcher = DefaultBuildFileWatcher(
            logLabel = "Test",
            coroutineScope = testScope,
            onBuildFileChanged = { changed.countDown() },
            buildFileNames = setOf("build.gradle"),
            debounceDelayMs = 0,
        )
        watcher?.startWatching(tempDir.resolve("missing"))
        assertFalse(watcher?.isWatching() == true)

        watcher?.startWatching(tempDir)
        tempDir.resolve("build.gradle").writeText("// changed")

        assertTrue(changed.await(5, TimeUnit.SECONDS), "A later valid session must detect changes")
        assertTrue(watcher?.isWatching() == true)
    }

    @Test
    fun `caller cancellation retires the session and prevents another start`(): Unit = runBlocking {
        watcher = DefaultBuildFileWatcher(
            logLabel = "Test",
            coroutineScope = testScope,
            onBuildFileChanged = {},
            buildFileNames = setOf("build.gradle"),
        ).also { it.startWatching(tempDir) }
        val watchJob = generateSequence(checkNotNull(testScope.coroutineContext[Job])) {
            it.children.singleOrNull()
        }.last()

        testScope.cancel()
        withTimeout(10_000) { watchJob.join() }

        assertFalse(watcher?.isWatching() == true)
        watcher?.startWatching(tempDir)
        assertFalse(watcher?.isWatching() == true)
    }

    @Test
    fun `a callback can stop its own watcher without waiting on itself`() {
        val stopped = CountDownLatch(1)
        watcher = DefaultBuildFileWatcher(
            logLabel = "Test",
            coroutineScope = testScope,
            onBuildFileChanged = {
                watcher?.stopWatching()
                stopped.countDown()
            },
            buildFileNames = setOf("build.gradle"),
            debounceDelayMs = 0,
        ).also { it.startWatching(tempDir) }

        tempDir.resolve("build.gradle").writeText("// changed")

        assertTrue(stopped.await(5, TimeUnit.SECONDS), "Stopping from a callback must finish")
        assertFalse(watcher?.isWatching() == true)
    }
}
