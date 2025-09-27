package com.github.albertocavalcante.groovylsp.gradle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildFileWatcherTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var coroutineScope: CoroutineScope
    private lateinit var buildFileWatcher: BuildFileWatcher
    private val changeEvents = ConcurrentLinkedQueue<Path>()

    @BeforeEach
    fun setup() {
        coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        buildFileWatcher = BuildFileWatcher(coroutineScope) { projectDir ->
            changeEvents.add(projectDir)
        }
    }

    @AfterEach
    fun cleanup() {
        buildFileWatcher.stopWatching()
        coroutineScope.cancel()
    }

    @Test
    @Timeout(10)
    fun `should detect build gradle file creation`() = runBlocking {
        // Start watching
        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        // Create a build.gradle file
        val buildFile = tempDir.resolve("build.gradle")
        buildFile.createFile()
        buildFile.writeText("// Test build file")

        // Wait for the watch event
        delay(1000) // Give file system time to notify

        // Verify the change was detected
        assertTrue(changeEvents.isNotEmpty(), "Expected build file change to be detected")
        assertEquals(tempDir, changeEvents.peek())
    }

    @Test
    @Timeout(10)
    fun `should detect build gradle kts file modification`() = runBlocking {
        // Create initial file
        val buildFile = tempDir.resolve("build.gradle.kts")
        buildFile.createFile()
        buildFile.writeText("// Initial content")

        // Start watching after file creation
        buildFileWatcher.startWatching(tempDir)
        delay(100) // Small delay to ensure watching is active

        // Modify the file
        buildFile.writeText("// Modified content\nplugins { kotlin(\"jvm\") }")

        // Wait for the watch event
        delay(1000)

        // Verify the change was detected
        assertTrue(changeEvents.isNotEmpty(), "Expected build file modification to be detected")
        assertEquals(tempDir, changeEvents.peek())
    }

    @Test
    @Timeout(10)
    fun `should detect settings gradle file changes`() = runBlocking {
        buildFileWatcher.startWatching(tempDir)
        delay(100)

        // Create settings.gradle
        val settingsFile = tempDir.resolve("settings.gradle")
        settingsFile.createFile()
        settingsFile.writeText("rootProject.name = 'test-project'")

        delay(1000)

        assertTrue(changeEvents.isNotEmpty())
        assertEquals(tempDir, changeEvents.peek())
    }

    @Test
    fun `should ignore non-build files`() = runBlocking {
        buildFileWatcher.startWatching(tempDir)
        delay(100)

        // Create a non-build file
        val otherFile = tempDir.resolve("README.md")
        otherFile.createFile()
        otherFile.writeText("# Test project")

        delay(1000)

        // Should not detect changes for non-build files
        assertTrue(changeEvents.isEmpty(), "Should not detect changes for non-build files")
    }

    @Test
    fun `should stop watching when requested`() = runBlocking {
        buildFileWatcher.startWatching(tempDir)
        assertTrue(buildFileWatcher.isWatching())

        buildFileWatcher.stopWatching()
        delay(100)

        // Should not be watching anymore
        assertTrue(!buildFileWatcher.isWatching())

        // Create a build file - should not be detected
        val buildFile = tempDir.resolve("build.gradle")
        buildFile.createFile()
        buildFile.writeText("// Test")

        delay(1000)

        assertTrue(changeEvents.isEmpty(), "Should not detect changes after stopping")
    }

    @Test
    @Timeout(10)
    fun `should handle multiple file changes`() = runBlocking {
        buildFileWatcher.startWatching(tempDir)
        delay(100)

        // Create multiple build files
        val files = listOf("build.gradle", "settings.gradle", "build.gradle.kts")
        files.forEach { filename ->
            val file = tempDir.resolve(filename)
            file.createFile()
            file.writeText("// $filename content")
            delay(200) // Small delay between creations
        }

        delay(1000)

        // Should detect at least one change (file system may batch events)
        assertTrue(changeEvents.isNotEmpty(), "Should detect multiple build file changes")
    }
}
