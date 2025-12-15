package com.github.albertocavalcante.groovylsp.buildtool.maven

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
class MavenBuildFileWatcherTest {

    @TempDir
    lateinit var tempDir: Path
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var buildFileWatcher: MavenBuildFileWatcher
    private var callbackCount = AtomicInteger(0)
    private var lastChangedPath: Path? = null

    @BeforeEach
    fun setup() {
        callbackCount.set(0)
        lastChangedPath = null
        buildFileWatcher = MavenBuildFileWatcher(
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
    fun `should detect pom xml file creation`() = testScope.runTest {
        val eventLatch = CountDownLatch(1)

        val buildFileWatcher = MavenBuildFileWatcher(
            coroutineScope = testScope,
            onBuildFileChanged = { projectDir ->
                lastChangedPath = projectDir
                callbackCount.incrementAndGet()
                eventLatch.countDown()
            },
            debounceDelayMs = 0L,
        )

        buildFileWatcher.startWatching(tempDir)

        // Create pom.xml
        val pomFile = tempDir.resolve("pom.xml")
        pomFile.createFile()
        pomFile.writeText("<project>...</project>")

        // Advance time to allow watcher to process events
        testDispatcher.scheduler.advanceTimeBy(100)
        testDispatcher.scheduler.runCurrent()

        // Wait for actual callback completion (max 3 seconds)
        assertTrue(
            eventLatch.await(3, TimeUnit.SECONDS),
            "Timed out waiting for file change event",
        )

        // Verify the change was detected
        assertTrue(callbackCount.get() > 0, "Expected build file change to be detected")
        assertEquals(tempDir, lastChangedPath)

        buildFileWatcher.stopWatching()
    }

    @Test
    @Timeout(5)
    fun `should ignore non-maven files`() = testScope.runTest {
        buildFileWatcher.startWatching(tempDir)

        // Create a random file
        val randomFile = tempDir.resolve("random.txt")
        randomFile.createFile()
        randomFile.writeText("some content")

        // Advance time
        testDispatcher.scheduler.advanceTimeBy(100)
        testDispatcher.scheduler.runCurrent()

        // Verify no change was detected
        assertEquals(0, callbackCount.get(), "Should not detect changes for non-maven files")

        buildFileWatcher.stopWatching()
    }
}
