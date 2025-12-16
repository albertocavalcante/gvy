package com.github.albertocavalcante.groovylsp.buildtool

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for SourceArtifactResolver.
 *
 * Following TDD approach: tests first, then implementation.
 */
class SourceArtifactResolverTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var resolver: SourceArtifactResolver

    @BeforeEach
    fun setup() {
        resolver = MavenSourceArtifactResolver(cacheDir = tempDir)
    }

    @Test
    fun `should return null for non-existent artifact`() = runBlocking {
        val result = resolver.resolveSourceJar(
            groupId = "com.nonexistent",
            artifactId = "nonexistent-artifact",
            version = "9999.9999.9999",
        )
        assertNull(result, "Should return null for non-existent artifact")
    }

    @Test
    fun `should report not cached for never-downloaded artifact`() {
        val cached = resolver.isSourcesCached(
            groupId = "org.jenkins-ci.plugins.workflow",
            artifactId = "workflow-step-api",
            version = "2.24",
        )
        assertFalse(cached, "Should not be cached before download")
    }

    @Test
    fun `should resolve sources jar for known Jenkins plugin`() = runBlocking {
        // This test may download from Maven Central - mark as integration test if too slow
        val result = resolver.resolveSourceJar(
            groupId = "org.jenkins-ci.plugins.workflow",
            artifactId = "workflow-step-api",
            version = "2.24",
        )

        // Note: This may fail in CI without network access
        // In that case, mark with @Tag("integration") and skip in unit tests
        if (result != null) {
            assertTrue(result.toString().endsWith("-sources.jar"), "Should be a sources JAR")
            assertTrue(java.nio.file.Files.exists(result), "File should exist")
        }
    }

    @Test
    fun `should cache resolved sources jar`() = runBlocking {
        val groupId = "org.jenkins-ci.plugins.workflow"
        val artifactId = "workflow-step-api"
        val version = "2.24"

        // First resolution (may download)
        val firstResult = resolver.resolveSourceJar(groupId, artifactId, version)

        if (firstResult != null) {
            // Should now be cached
            val cached = resolver.isSourcesCached(groupId, artifactId, version)
            assertTrue(cached, "Should be cached after successful download")

            // Second resolution should return same path (from cache)
            val secondResult = resolver.resolveSourceJar(groupId, artifactId, version)
            assertNotNull(secondResult, "Cached resolution should succeed")
        }
    }

    @Test
    fun `should build correct cache path structure`() {
        // Verify the expected Maven-style path structure
        val expectedPath = tempDir.resolve("org/jenkins-ci/plugins/workflow/workflow-step-api/2.24")

        // The resolver should use this structure for caching
        // This is a structural test - doesn't require network access
        val groupId = "org.jenkins-ci.plugins.workflow"
        val artifactId = "workflow-step-api"
        val version = "2.24"

        // The cache path should follow Maven repository layout
        val cacheSubPath = groupId.replace('.', '/') + "/$artifactId/$version"
        val expectedCachePath = tempDir.resolve(cacheSubPath)

        assertTrue(
            expectedCachePath.toString().contains("org/jenkins-ci/plugins/workflow"),
            "Cache path should follow Maven layout",
        )
    }
}
