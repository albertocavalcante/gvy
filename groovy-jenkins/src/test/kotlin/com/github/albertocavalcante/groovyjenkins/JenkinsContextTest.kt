package com.github.albertocavalcante.groovyjenkins

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Jenkins context management.
 */
class JenkinsContextTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should build Jenkins classpath from configured libraries`() {
        val lib1 = tempDir.resolve("lib1.jar")
        val lib2 = tempDir.resolve("lib2.jar")
        Files.createFile(lib1)
        Files.createFile(lib2)

        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("lib1", lib1.toString()),
                SharedLibrary("lib2", lib2.toString()),
            ),
        )

        val context = JenkinsContext(config, tempDir)
        val classpath = context.buildClasspath(emptyList())

        // Should include configured libraries (may also include auto-injected jenkins-core)
        assertTrue(classpath.size >= 2, "Expected at least 2 items, got ${classpath.size}")
        assertTrue(classpath.any { it.toString().contains("lib1.jar") })
        assertTrue(classpath.any { it.toString().contains("lib2.jar") })
    }

    @Test
    fun `should build classpath from library references`() {
        val lib1 = tempDir.resolve("lib1.jar")
        Files.createFile(lib1)

        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("lib1", lib1.toString()),
            ),
        )

        val context = JenkinsContext(config, tempDir)
        val refs = listOf(LibraryReference("lib1", null))
        val classpath = context.buildClasspath(refs)

        // Should include lib1 (may also include auto-injected jenkins-core)
        assertTrue(classpath.size >= 1, "Expected at least 1 item, got ${classpath.size}")
        assertTrue(classpath.any { it.toString().contains("lib1.jar") })
    }

    @Test
    fun `should include sources jar when available`() {
        val lib = tempDir.resolve("lib.jar")
        val sources = tempDir.resolve("lib-sources.jar")
        Files.createFile(lib)
        Files.createFile(sources)

        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("mylib", lib.toString(), sources.toString()),
            ),
        )

        val context = JenkinsContext(config, tempDir)
        val refs = listOf(LibraryReference("mylib", null))
        val classpath = context.buildClasspath(refs)

        // Should include both jar and sources (may also include auto-injected jenkins-core)
        assertTrue(classpath.size >= 2, "Expected at least 2 items, got ${classpath.size}")
        assertTrue(classpath.any { it.toString().contains("lib.jar") })
        assertTrue(classpath.any { it.toString().contains("lib-sources.jar") })
    }

    @Test
    fun `should handle missing library references gracefully`() {
        val lib1 = tempDir.resolve("lib1.jar")
        Files.createFile(lib1)

        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("lib1", lib1.toString()),
            ),
        )

        val context = JenkinsContext(config, tempDir)
        val refs = listOf(
            LibraryReference("lib1", null),
            LibraryReference("missing", null),
        )

        // Should not throw, just log warning
        val classpath = context.buildClasspath(refs)

        // Should include lib1 but not the missing one
        assertTrue(classpath.size >= 1)
    }

    @Test
    fun `should load GDSL files from configuration`() {
        val gdsl1 = tempDir.resolve("pipeline.gdsl")
        Files.writeString(gdsl1, "// Pipeline GDSL")

        val config = JenkinsConfiguration(
            gdslPaths = listOf(gdsl1.toString()),
        )

        val context = JenkinsContext(config, tempDir)
        val gdslContent = context.loadGdslMetadata()

        assertEquals(1, gdslContent.successful.size)
        assertEquals(0, gdslContent.failed.size)
    }

    @Test
    fun `should skip GDSL execution when disabled`() {
        val marker = tempDir.resolve("gdsl-executed.txt")
        val markerPath = marker.toAbsolutePath().toString().replace("\\", "\\\\")
        val gdsl1 = tempDir.resolve("pipeline.gdsl")
        Files.writeString(gdsl1, "new File(\"$markerPath\").text = \"executed\"")

        val config = JenkinsConfiguration(
            gdslPaths = listOf(gdsl1.toString()),
            gdslExecutionEnabled = false,
        )

        val context = JenkinsContext(config, tempDir)
        context.loadGdslMetadata()

        assertTrue(Files.notExists(marker))
    }

    @Test
    fun `should report warnings for missing GDSL files`() {
        val config = JenkinsConfiguration(
            gdslPaths = listOf("/nonexistent/pipeline.gdsl"),
        )

        val context = JenkinsContext(config, tempDir)
        val gdslContent = context.loadGdslMetadata()

        assertEquals(0, gdslContent.successful.size)
        assertEquals(1, gdslContent.failed.size)
    }

    @Test
    fun `should include registered plugin jars in classpath`() = runBlocking {
        val pluginJar = tempDir.resolve("plugin.jar")
        Files.createFile(pluginJar)

        val mockPluginManager = mockk<JenkinsPluginManager>()
        coEvery { mockPluginManager.getRegisteredPluginJars() } returns listOf(pluginJar)

        val config = JenkinsConfiguration()
        // This constructor call will fail compilation initially
        val context = JenkinsContext(config, tempDir, mockPluginManager)
        val classpath = context.buildClasspath(emptyList())

        assertTrue(classpath.any { it == pluginJar }, "Classpath should include registered plugin JAR")
    }
}
