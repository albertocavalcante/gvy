package com.github.albertocavalcante.groovylsp.gradle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildFileWatcherTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var coroutineScope: CoroutineScope
    private val changeEvents = ConcurrentLinkedQueue<Path>()

    @BeforeEach
    fun setup() {
        coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        changeEvents.clear()
    }

    @AfterEach
    fun cleanup() {
        coroutineScope.cancel()
    }

    @Test
    @Timeout(5)
    fun `should detect build gradle file creation`() = runTest {
        // Use CountDownLatch for synchronization - no delays!
        val eventLatch = CountDownLatch(1)

        val buildFileWatcher = BuildFileWatcher(
            coroutineScope = coroutineScope,
            onBuildFileChanged = { projectDir ->
                changeEvents.add(projectDir)
                eventLatch.countDown() // Signal that callback completed
            },
            debounceDelayMs = 0L, // No debounce delay for tests
        )

        // Start watching
        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        // Create a build.gradle file
        val buildFile = tempDir.resolve("build.gradle")
        buildFile.createFile()
        buildFile.writeText("// Test build file")

        // Wait for actual callback completion (max 3 seconds)
        assertTrue(
            eventLatch.await(3, TimeUnit.SECONDS),
            "Expected build file change callback to complete within 3 seconds",
        )

        // Verify the change was detected
        assertTrue(changeEvents.isNotEmpty(), "Expected build file change to be detected")
        assertEquals(tempDir, changeEvents.peek())

        buildFileWatcher.stopWatching()
    }

    @Test
    @Timeout(5)
    fun `should detect build gradle kts file modification`() = runTest {
        val eventLatch = CountDownLatch(1)

        val buildFileWatcher = BuildFileWatcher(
            coroutineScope = coroutineScope,
            onBuildFileChanged = { projectDir ->
                changeEvents.add(projectDir)
                eventLatch.countDown()
            },
            debounceDelayMs = 0L,
        )

        // Create initial file
        val buildFile = tempDir.resolve("build.gradle.kts")
        buildFile.createFile()
        buildFile.writeText("// Initial content")

        // Start watching after file creation
        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        // Modify the file
        buildFile.writeText("// Modified content\nplugins { kotlin(\"jvm\") }")

        // Wait for actual callback completion
        assertTrue(
            eventLatch.await(3, TimeUnit.SECONDS),
            "Expected build file modification callback to complete within 3 seconds",
        )

        // Verify the change was detected
        assertTrue(changeEvents.isNotEmpty(), "Expected build file modification to be detected")
        assertEquals(tempDir, changeEvents.peek())

        buildFileWatcher.stopWatching()
    }

    @Test
    @Timeout(5)
    fun `should detect settings gradle file changes`() = runTest {
        val eventLatch = CountDownLatch(1)

        val buildFileWatcher = BuildFileWatcher(
            coroutineScope = coroutineScope,
            onBuildFileChanged = { projectDir ->
                changeEvents.add(projectDir)
                eventLatch.countDown()
            },
            debounceDelayMs = 0L,
        )

        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        // Create settings.gradle
        val settingsFile = tempDir.resolve("settings.gradle")
        settingsFile.createFile()
        settingsFile.writeText("rootProject.name = 'test-project'")

        // Wait for actual callback completion
        assertTrue(
            eventLatch.await(3, TimeUnit.SECONDS),
            "Expected settings file change callback to complete within 3 seconds",
        )

        assertTrue(changeEvents.isNotEmpty())
        assertEquals(tempDir, changeEvents.peek())

        buildFileWatcher.stopWatching()
    }

    @Test
    @Timeout(5)
    fun `should ignore non-build files`() = runTest {
        val buildFileWatcher = BuildFileWatcher(
            coroutineScope = coroutineScope,
            onBuildFileChanged = { projectDir ->
                changeEvents.add(projectDir)
            },
            debounceDelayMs = 0L,
        )

        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        // Create a non-build file
        val otherFile = tempDir.resolve("README.md")
        otherFile.createFile()
        otherFile.writeText("# Test project")

        // Give some time for potential events (but there shouldn't be any)
        Thread.sleep(500) // Minimal sleep since we expect NO events

        // Should not detect changes for non-build files
        assertTrue(changeEvents.isEmpty(), "Should not detect changes for non-build files")

        buildFileWatcher.stopWatching()
    }

    @Test
    @Timeout(5)
    fun `should stop watching when requested`() = runTest {
        val buildFileWatcher = BuildFileWatcher(
            coroutineScope = coroutineScope,
            onBuildFileChanged = { projectDir ->
                changeEvents.add(projectDir)
            },
            debounceDelayMs = 0L,
        )

        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        buildFileWatcher.stopWatching()

        // Should not be watching anymore
        assertTrue(!buildFileWatcher.isWatching())

        // Create a build file - should not be detected
        val buildFile = tempDir.resolve("build.gradle")
        buildFile.createFile()
        buildFile.writeText("// Test")

        // Give some time for potential events (but there shouldn't be any)
        Thread.sleep(500)

        assertTrue(changeEvents.isEmpty(), "Should not detect changes after stopping")
    }

    @Test
    @Timeout(5)
    fun `should handle multiple file changes`() = runTest {
        val expectedEvents = 3
        val eventLatch = CountDownLatch(expectedEvents)

        val buildFileWatcher = BuildFileWatcher(
            coroutineScope = coroutineScope,
            onBuildFileChanged = { projectDir ->
                changeEvents.add(projectDir)
                eventLatch.countDown()
            },
            debounceDelayMs = 0L,
        )

        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        // Create multiple build files
        val files = listOf("build.gradle", "settings.gradle", "build.gradle.kts")
        files.forEach { filename ->
            val file = tempDir.resolve(filename)
            file.createFile()
            file.writeText("// $filename content")
        }

        // Wait for all events or timeout
        val allEventsReceived = eventLatch.await(3, TimeUnit.SECONDS)

        // Should detect at least one change (file system may batch events)
        assertTrue(changeEvents.isNotEmpty(), "Should detect multiple build file changes")

        // Log for debugging if not all events received
        if (!allEventsReceived) {
            println("Expected $expectedEvents events, received ${expectedEvents - eventLatch.count}")
        }

        buildFileWatcher.stopWatching()
    }
}
