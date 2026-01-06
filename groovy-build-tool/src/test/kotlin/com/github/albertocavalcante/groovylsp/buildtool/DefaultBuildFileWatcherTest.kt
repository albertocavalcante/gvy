package com.github.albertocavalcante.groovylsp.buildtool

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

        // Give watcher time to start
        delay(200)

        // Create file
        tempDir.resolve("build.gradle").writeText("// new file")

        // Wait for event
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for file creation event")
        assertEquals(1, events.size)
        assertEquals(tempDir, events[0])
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

        delay(200)

        // Modify file
        buildFile.writeText("// modified")

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for file modification event")
        assertEquals(1, events.size)
    }

    @Test
    fun `should debounce rapid changes`() = runBlocking {
        val events = CopyOnWriteArrayList<Path>()
        // We expect exactly 1 event because we modify rapidly
        val latch = CountDownLatch(1)

        watcher = DefaultBuildFileWatcher(
            logLabel = "Test",
            coroutineScope = testScope,
            onBuildFileChanged = {
                events.add(it)
                latch.countDown()
            },
            buildFileNames = setOf("build.gradle"),
            debounceDelayMs = 500, // Longer delay for debounce test
        ).also { it.startWatching(tempDir) }

        val buildFile = tempDir.resolve("build.gradle")
        buildFile.writeText("// initial") // This might trigger create, wait a bit
        delay(1000)
        events.clear() // Clear any initial create events

        // Trigger rapid changes
        repeat(5) {
            buildFile.writeText("// change $it")
            delay(50) // less than debounce 500
        }

        // Wait for debounce period + buffer
        // WatchService poll takes up to 1s, and debounce is 500ms.
        // We need ample buffer.
        delay(3000)

        assertEquals(1, events.size, "Should debounce to single event (got ${events.size})")
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
}
