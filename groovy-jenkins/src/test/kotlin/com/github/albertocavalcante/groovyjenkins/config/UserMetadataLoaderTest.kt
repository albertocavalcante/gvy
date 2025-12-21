package com.github.albertocavalcante.groovyjenkins.config

import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadataLoader
import com.github.albertocavalcante.groovyjenkins.metadata.MetadataMerger
import com.github.albertocavalcante.groovyjenkins.metadata.StableStepDefinitions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for UserMetadataLoader - loads user-provided GDSL and metadata overrides.
 *
 * TDD RED phase: These tests define expected user configuration behavior.
 */
class UserMetadataLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loads user GDSL file when configured`() {
        // Create config directory and files
        val configDir = tempDir.resolve(".groovy-lsp")
        Files.createDirectories(configDir)

        // Create config file
        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "gdslFile": ".jenkins/pipeline.gdsl"
            }
            """.trimIndent(),
        )

        // Create GDSL file
        val jenkinsDir = tempDir.resolve(".jenkins")
        Files.createDirectories(jenkinsDir)
        Files.writeString(
            jenkinsDir.resolve("pipeline.gdsl"),
            """
            method(name: 'myCustomStep', type: 'Object', params: [:], doc: 'Custom step')
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val metadata = loader.load()

        assertNotNull(metadata)
        assertTrue(metadata.steps.containsKey("myCustomStep"))
    }

    @Test
    fun `returns null when no config file exists`() {
        val loader = UserMetadataLoader(tempDir)
        val metadata = loader.load()

        assertNull(metadata)
    }

    @Test
    fun `loads metadata overrides from config`() {
        val configDir = tempDir.resolve(".groovy-lsp")
        Files.createDirectories(configDir)

        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "metadataOverrides": {
                    "myInternalStep": {
                        "plugin": "internal-plugin",
                        "documentation": "Our internal deployment step",
                        "parameters": {
                            "environment": { "type": "String", "required": true },
                            "version": { "type": "String", "required": false }
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val metadata = loader.load()

        assertNotNull(metadata)
        val step = metadata.steps["myInternalStep"]
        assertNotNull(step)
        assertEquals("internal-plugin", step.plugin)
        assertEquals("Our internal deployment step", step.documentation)
        assertTrue(step.parameters.containsKey("environment"))
        assertTrue(step.parameters["environment"]!!.required)
    }

    @Test
    fun `user metadata overrides bundled`() {
        val configDir = tempDir.resolve(".groovy-lsp")
        Files.createDirectories(configDir)

        // Override the sh step with custom config
        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "metadataOverrides": {
                    "sh": {
                        "plugin": "user-override",
                        "documentation": "User overridden sh step",
                        "parameters": {
                            "customParam": { "type": "String", "required": true }
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val userMetadata = loader.load()
        assertNotNull(userMetadata)

        val bundled = BundledJenkinsMetadataLoader().load()
        val merged = MetadataMerger.mergeWithPriority(
            bundled = bundled,
            stable = StableStepDefinitions.all(),
            userOverrides = userMetadata.steps,
        )

        val sh = merged.steps["sh"]
        assertNotNull(sh)
        assertEquals("user-override", sh.plugin)
        assertEquals("User overridden sh step", sh.documentation)
    }

    @Test
    fun `loads jenkinsVersion from config`() {
        val configDir = tempDir.resolve(".groovy-lsp")
        Files.createDirectories(configDir)

        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "jenkinsVersion": "2.479.3"
            }
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val config = loader.loadConfig()

        assertNotNull(config)
        assertEquals("2.479.3", config.jenkinsVersion)
    }

    @Test
    fun `handles malformed config gracefully`() {
        val configDir = tempDir.resolve(".groovy-lsp")
        Files.createDirectories(configDir)

        Files.writeString(
            configDir.resolve("jenkins.json"),
            "{ invalid json",
        )

        val loader = UserMetadataLoader(tempDir)
        val config = loader.loadConfig()

        // Should return null or default, not throw
        assertNull(config)
    }

    @Test
    fun `handles missing GDSL file gracefully`() {
        val configDir = tempDir.resolve(".groovy-lsp")
        Files.createDirectories(configDir)

        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "gdslFile": "nonexistent.gdsl"
            }
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val metadata = loader.load()

        // Should return metadata without the GDSL steps (or null)
        // Not throw an exception
    }

    @Test
    fun `loads plugin configuration`() {
        val configDir = tempDir.resolve(".groovy-lsp")
        Files.createDirectories(configDir)

        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "plugins": {
                    "workflow-durable-task-step": "1464.v2d3f5c68f84c",
                    "custom-plugin": "1.0.0"
                }
            }
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val config = loader.loadConfig()

        assertNotNull(config)
        assertEquals(2, config.plugins.size)
        assertEquals("1464.v2d3f5c68f84c", config.plugins["workflow-durable-task-step"])
    }
}
