package com.github.albertocavalcante.groovyjenkins

import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.StepParameter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for dynamic source resolution and precedence.
 *
 * Verifies that dynamic data (simulating classpath extraction) overrides
 * static bundled/stable definitions.
 */
class DynamicSourceTest {

    @Test
    fun `dynamic source overrides stable definitions`() = runTest {
        // 1. Create a mock extractor
        val mockExtractor = mockk<JenkinsPluginMetadataExtractor>()

        // 2. Define a fake JAR path and the metadata it "contains"
        val jarPath = Path.of("/tmp/fake-workflow-durable-task.jar")

        // "sh" is normally a stable step. We define a version with a custom parameter.
        val customShStep = JenkinsStepMetadata(
            name = "sh",
            plugin = "workflow-durable-task",
            parameters = mapOf(
                // "script" is omitted to verify it's inherited from stable
                "customDynamicParam" to
                    StepParameter("customDynamicParam", "String", documentation = "Added dynamically"),
            ),
            documentation = "Dynamic documentation override",
        )

        // 3. Train the mock to return our custom metadata when extracting from this JAR
        // We use any() for the name argument since logic might pass full name or simple name
        coEvery { mockExtractor.extractFromJar(jarPath, any()) } returns listOf(customShStep)

        // 4. Inject the mock into the manager
        val manager = JenkinsPluginManager(metadataExtractor = mockExtractor)

        // 5. Resolve the step, explicitly passing our fake JAR as if it were on the classpath
        val result = manager.resolveStepMetadata("sh", classpathJars = listOf(jarPath))

        // 6. Verify assertions
        assertNotNull(result, "Should resolve metadata for 'sh'")
        assertEquals(
            "Dynamic documentation override",
            result.documentation,
            "Dynamic documentation should override stable",
        )

        // Verify parameter merging (assuming Semigroup behavior merges params)
        assertTrue(result.parameters.containsKey("script"), "Should contain stable 'script' param")
        assertTrue(result.parameters.containsKey("customDynamicParam"), "Should contain dynamic 'customDynamicParam'")
    }
}
