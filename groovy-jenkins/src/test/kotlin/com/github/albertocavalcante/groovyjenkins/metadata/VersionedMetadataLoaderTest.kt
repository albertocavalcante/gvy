package com.github.albertocavalcante.groovyjenkins.metadata

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for VersionedMetadataLoader - loads metadata by Jenkins LTS version.
 *
 * TDD RED phase: These tests define expected behavior.
 */
class VersionedMetadataLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loads metadata for specific LTS version`() {
        val loader = VersionedMetadataLoader()
        val metadata = loader.load(jenkinsVersion = "2.479.3")

        // Should load from bundled resources for lts-2.479
        assertNotNull(metadata)
        // Should have at least stable steps
        assertNotNull(metadata.steps["sh"])
        assertNotNull(metadata.steps["echo"])
    }

    @Test
    fun `falls back to default when version not found`() {
        val loader = VersionedMetadataLoader()
        val metadata = loader.load(jenkinsVersion = "9.999.999")

        // Should still return metadata from default/fallback
        assertNotNull(metadata)
        assertNotNull(metadata.steps["echo"])
    }

    @Test
    fun `merges stable plus bundled in correct order`() {
        val loader = VersionedMetadataLoader()
        val metadata = loader.loadMerged(jenkinsVersion = "2.479.3")

        // Stable should have more complete definitions
        val sh = metadata.steps["sh"]!!
        assertTrue(sh.parameters.size >= 5, "Stable sh should have 5+ parameters")
        assertTrue(sh.parameters.containsKey("returnStdout"))
        assertTrue(sh.parameters.containsKey("returnStatus"))
        assertEquals("workflow-durable-task-step", sh.plugin)
    }

    @Test
    fun `extracts major LTS version from full version`() {
        val loader = VersionedMetadataLoader()

        // Should extract 2.479 from 2.479.3
        assertEquals("2.479", loader.extractLtsVersion("2.479.3"))
        assertEquals("2.479", loader.extractLtsVersion("2.479.1"))
        assertEquals("2.492", loader.extractLtsVersion("2.492.2"))

        // Weekly versions should fall back
        assertEquals("2.480", loader.extractLtsVersion("2.480"))
    }

    @Test
    fun `loadMerged includes stable steps even without bundled`() {
        val loader = VersionedMetadataLoader()
        val metadata = loader.loadMerged(jenkinsVersion = null)

        // Should still have stable steps
        assertNotNull(metadata.steps["sh"])
        assertNotNull(metadata.steps["echo"])
        assertNotNull(metadata.steps["bat"])
    }

    @Test
    fun `preserves bundled metadata when no stable override`() {
        val loader = VersionedMetadataLoader()
        val bundled = BundledJenkinsMetadataLoader().load()

        // Get a step that's in bundled but maybe not in stable
        val bundledStepNames = bundled.steps.keys

        val metadata = loader.loadMerged(jenkinsVersion = "2.479.3")

        // Bundled steps should be preserved
        for (stepName in bundledStepNames) {
            assertTrue(
                metadata.steps.containsKey(stepName),
                "Bundled step '$stepName' should be preserved",
            )
        }
    }

    @Test
    fun `preserves global variables from bundled`() {
        val loader = VersionedMetadataLoader()
        val metadata = loader.loadMerged(jenkinsVersion = "2.479.3")

        // Should have global variables from bundled
        assertTrue(metadata.globalVariables.isNotEmpty())
    }

    @Test
    fun `preserves post conditions from bundled`() {
        val loader = VersionedMetadataLoader()
        val metadata = loader.loadMerged(jenkinsVersion = "2.479.3")

        // Should have post conditions from bundled
        assertTrue(metadata.postConditions.isNotEmpty())
    }

    @Test
    fun `preserves declarative options from bundled`() {
        val loader = VersionedMetadataLoader()
        val metadata = loader.loadMerged(jenkinsVersion = "2.479.3")

        // Should have declarative options from bundled
        assertTrue(metadata.declarativeOptions.isNotEmpty())
    }

    @Test
    fun `getSupportedVersions returns available LTS versions`() {
        val loader = VersionedMetadataLoader()
        val versions = loader.getSupportedVersions()

        // Should at least have default
        assertTrue(versions.isNotEmpty())
        assertTrue(versions.contains("default"))
    }
}
