package com.github.albertocavalcante.groovylsp.sources

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for SourceNavigationService.
 */
class SourceNavigationServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `getStatistics returns expected keys`() {
        val service = SourceNavigationService()

        val stats = service.getStatistics()

        assertTrue(stats.containsKey("sourceJarsCached"), "Should contain sourceJarsCached key")
        assertTrue(stats.containsKey("extractorStats"), "Should contain extractorStats key")
    }

    @Test
    fun `getStatistics returns correct types`() {
        val service = SourceNavigationService()

        val stats = service.getStatistics()

        assertTrue(stats["sourceJarsCached"] is Int, "sourceJarsCached should be Int")
        assertTrue(stats["extractorStats"] is Map<*, *>, "extractorStats should be Map")
    }

    @Test
    fun `getStatistics starts with zero cached`() {
        val service = SourceNavigationService()

        val stats = service.getStatistics()

        assertTrue((stats["sourceJarsCached"] as Int) == 0, "Should start with 0 cached")
    }

    @Test
    fun `SourceResult SourceLocation holds all data`() {
        val uri = URI.create("file:///test/Foo.java")
        val result = SourceNavigationService.SourceResult.SourceLocation(
            uri = uri,
            className = "com.example.Foo",
            lineNumber = 42,
        )

        assertTrue(result.uri == uri)
        assertTrue(result.className == "com.example.Foo")
        assertTrue(result.lineNumber == 42)
    }

    @Test
    fun `SourceResult BinaryOnly includes reason`() {
        val uri = URI.create("jar:file:///test.jar!/Foo.class")
        val result = SourceNavigationService.SourceResult.BinaryOnly(
            uri = uri,
            className = "com.example.Foo",
            reason = "No source available",
        )

        assertTrue(result.reason.isNotBlank())
    }

    @Test
    fun `multiple instances are independent`() {
        val service1 = SourceNavigationService()
        val service2 = SourceNavigationService()

        val stats1 = service1.getStatistics()
        val stats2 = service2.getStatistics()

        // Both should work independently
        assertNotNull(stats1)
        assertNotNull(stats2)
    }
}
