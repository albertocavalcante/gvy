package com.github.albertocavalcante.groovyjenkins

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for JenkinsPluginManager's 4-tier fallback resolution strategy.
 */
class JenkinsPluginManagerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `registerPluginJar adds jar to cache and resolves steps`() = runBlocking {
        val mockExtractor = io.mockk.mockk<JenkinsPluginMetadataExtractor>()
        val manager = JenkinsPluginManager(metadataExtractor = mockExtractor)
        val jarPath = tempDir.resolve("test-plugin.jar")
        java.nio.file.Files.createFile(jarPath)

        val stepMetadata = com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata(
            name = "testStep",
            plugin = "test-plugin",
            parameters = emptyMap(),
            documentation = "doc",
        )
        // Mock extraction
        io.mockk.every { mockExtractor.extractFromJar(any(), any()) } returns listOf(stepMetadata)

        // Act: Register the JAR (this method does not exist yet)
        manager.registerPluginJar("test-plugin", jarPath)

        // Assert: Step is resolved
        val resolved = manager.resolveStepMetadata("testStep")
        assertNotNull(resolved, "Should resolve step from registered JAR")
        assertEquals("testStep", resolved.name)
    }

    @Test
    fun `resolveStepMetadata returns bundled step for known step`() = runBlocking {
        val manager = JenkinsPluginManager()

        val step = manager.resolveStepMetadata("sh")

        assertNotNull(step, "Should resolve 'sh' step from bundled metadata")
        assertEquals("sh", step.name)
    }

    @Test
    fun `resolveStepMetadata returns null for unknown step`() = runBlocking {
        val manager = JenkinsPluginManager()

        val step = manager.resolveStepMetadata("nonExistentStep12345")

        assertNull(step, "Should return null for unknown step")
    }

    @Test
    fun `resolveStepMetadata resolves echo step`() = runBlocking {
        val manager = JenkinsPluginManager()

        val step = manager.resolveStepMetadata("echo")

        assertNotNull(step, "Should resolve 'echo' step")
        assertEquals("echo", step.name)
    }

    @Test
    fun `resolveStepMetadata resolves stage step`() = runBlocking {
        val manager = JenkinsPluginManager()

        val step = manager.resolveStepMetadata("stage")

        assertNotNull(step, "Should resolve 'stage' step")
        assertEquals("stage", step.name)
    }

    @Test
    fun `getAllKnownSteps returns non-empty list`() = runBlocking {
        val manager = JenkinsPluginManager()

        val steps = manager.getAllKnownSteps()

        assertTrue(steps.isNotEmpty(), "Should have known steps")
        assertTrue(steps.size > 10, "Should have more than 10 known steps")
    }

    @Test
    fun `getAllKnownSteps includes common pipeline steps`() = runBlocking {
        val manager = JenkinsPluginManager()

        val steps = manager.getAllKnownSteps()

        assertTrue("sh" in steps, "Should include 'sh' step")
        assertTrue("echo" in steps, "Should include 'echo' step")
        assertTrue("stage" in steps, "Should include 'stage' step")
    }

    @Test
    fun `resolveStepMetadata resolves node step`() = runBlocking {
        val manager = JenkinsPluginManager()

        val step = manager.resolveStepMetadata("node")

        assertNotNull(step, "Should resolve 'node' step")
        assertEquals("node", step.name)
    }

    @Test
    fun `resolveStepMetadata is case-sensitive`(): Unit = runBlocking {
        val manager = JenkinsPluginManager()

        val upperCase = manager.resolveStepMetadata("SH")
        val lowerCase = manager.resolveStepMetadata("sh")

        assertNull(upperCase, "Step names should be case-sensitive")
        assertNotNull(lowerCase, "Lowercase 'sh' should be found")
    }

    @Test
    fun `resolveStepMetadata empty string returns null`() = runBlocking {
        val manager = JenkinsPluginManager()

        val step = manager.resolveStepMetadata("")

        assertNull(step, "Empty string should return null")
    }

    @Test
    fun `multiple calls return consistent results`() = runBlocking {
        val manager = JenkinsPluginManager()

        val first = manager.resolveStepMetadata("git")
        val second = manager.resolveStepMetadata("git")

        assertEquals(first, second, "Multiple calls should return same result")
    }
}
