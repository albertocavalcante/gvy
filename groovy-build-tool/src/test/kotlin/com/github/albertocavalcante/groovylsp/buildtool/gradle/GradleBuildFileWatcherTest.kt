package com.github.albertocavalcante.groovylsp.buildtool.gradle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createFile
import kotlin.io.path.writeText

@OptIn(ExperimentalCoroutinesApi::class)
class GradleBuildFileWatcherTest {

    @TempDir
    lateinit var tempDir: Path
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var buildFileWatcher: GradleBuildFileWatcher
    private var callbackCount = AtomicInteger(0)
    private var lastChangedPath: Path? = null

    @BeforeEach
    fun setup() {
        callbackCount.set(0)
        lastChangedPath = null
        buildFileWatcher = GradleBuildFileWatcher(
            coroutineScope = testScope,
            onBuildFileChanged = { path ->
                lastChangedPath = path
                callbackCount.incrementAndGet()
            },
            debounceDelayMs = 50, // Short delay for tests
        )
    }

    @Test
    @Timeout(5)
    fun `should detect build gradle file creation`() = testScope.runTest {
        val eventLatch = CountDownLatch(1)

        val buildFileWatcher = GradleBuildFileWatcher(
            coroutineScope = testScope,
            onBuildFileChanged = { projectDir ->
                lastChangedPath = projectDir
                callbackCount.incrementAndGet()
                eventLatch.countDown()
            },
            debounceDelayMs = 0L,
        )

        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        val buildFile = tempDir.resolve("build.gradle")
        buildFile.createFile()
        buildFile.writeText("// Test build file")

        testDispatcher.scheduler.advanceTimeBy(100)
        testDispatcher.scheduler.runCurrent()

        assertTrue(
            eventLatch.await(3, TimeUnit.SECONDS),
            "Expected build file change callback to complete within 3 seconds",
        )

        assertTrue(callbackCount.get() > 0, "Expected build file change to be detected")
        assertEquals(tempDir, lastChangedPath)

        buildFileWatcher.stopWatching()
    }

    @Test
    @Timeout(5)
    fun `should detect build gradle kts file modification`() = testScope.runTest {
        val eventLatch = CountDownLatch(1)

        val buildFileWatcher = GradleBuildFileWatcher(
            coroutineScope = testScope,
            onBuildFileChanged = { projectDir ->
                lastChangedPath = projectDir
                callbackCount.incrementAndGet()
                eventLatch.countDown()
            },
            debounceDelayMs = 0L,
        )

        val buildFile = tempDir.resolve("build.gradle.kts")
        buildFile.createFile()
        buildFile.writeText("// Initial content")

        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        buildFile.writeText("// Modified content\nplugins { kotlin(\"jvm\") }")

        testDispatcher.scheduler.advanceTimeBy(100)
        testDispatcher.scheduler.runCurrent()

        assertTrue(
            eventLatch.await(3, TimeUnit.SECONDS),
            "Expected build file modification callback to complete within 3 seconds",
        )

        assertTrue(callbackCount.get() > 0, "Expected build file modification to be detected")
        assertEquals(tempDir, lastChangedPath)

        buildFileWatcher.stopWatching()
    }

    @Test
    @Timeout(5)
    fun `should detect settings gradle file changes`() = testScope.runTest {
        val eventLatch = CountDownLatch(1)

        val buildFileWatcher = GradleBuildFileWatcher(
            coroutineScope = testScope,
            onBuildFileChanged = { projectDir ->
                lastChangedPath = projectDir
                callbackCount.incrementAndGet()
                eventLatch.countDown()
            },
            debounceDelayMs = 0L,
        )

        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        val settingsFile = tempDir.resolve("settings.gradle")
        settingsFile.createFile()
        settingsFile.writeText("rootProject.name = 'test-project'")

        testDispatcher.scheduler.advanceTimeBy(100)
        testDispatcher.scheduler.runCurrent()

        assertTrue(
            eventLatch.await(3, TimeUnit.SECONDS),
            "Expected settings file change callback to complete within 3 seconds",
        )

        assertTrue(callbackCount.get() > 0)
        assertEquals(tempDir, lastChangedPath)

        buildFileWatcher.stopWatching()
    }

    @Test
    @Timeout(5)
    fun `should ignore non-build files`() = testScope.runTest {
        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        val otherFile = tempDir.resolve("README.md")
        otherFile.createFile()
        otherFile.writeText("# Test project")

        testDispatcher.scheduler.advanceTimeBy(100)
        testDispatcher.scheduler.runCurrent()

        assertEquals(0, callbackCount.get(), "Should not detect changes for non-build files")

        buildFileWatcher.stopWatching()
    }

    @Test
    @Timeout(5)
    fun `debounces rapid build file changes`() = testScope.runTest {
        val eventLatch = CountDownLatch(1)

        val buildFileWatcher = GradleBuildFileWatcher(
            coroutineScope = testScope,
            onBuildFileChanged = {
                callbackCount.incrementAndGet()
                eventLatch.countDown()
            },
            debounceDelayMs = 200L,
        )

        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        val buildFile = tempDir.resolve("build.gradle")
        buildFile.createFile()
        buildFile.writeText("// Initial content")
        buildFile.writeText("// Update 1")
        buildFile.writeText("// Update 2")

        testDispatcher.scheduler.advanceTimeBy(250)
        testDispatcher.scheduler.runCurrent()

        assertTrue(
            eventLatch.await(3, TimeUnit.SECONDS),
            "Expected debounced build file change callback to complete within 3 seconds",
        )

        testDispatcher.scheduler.advanceTimeBy(250)
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, callbackCount.get(), "Expected a single debounced callback")

        buildFileWatcher.stopWatching()
    }
}
