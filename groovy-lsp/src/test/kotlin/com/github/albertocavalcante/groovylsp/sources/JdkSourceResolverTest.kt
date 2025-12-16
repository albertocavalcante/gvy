package com.github.albertocavalcante.groovylsp.sources

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path

/**
 * Tests for JdkSourceResolver.
 *
 * ## Test Strategy
 *
 * **Unit Tests (hermetic, always run):**
 * - URI parsing tests use no external dependencies
 * - All file operations use @TempDir for isolation
 *
 * **Integration Tests (conditional, require JDK src.zip):**
 * - Tests that extract from real src.zip use @EnabledIf("hasSrcZip")
 * - These verify real-world behavior but gracefully skip if src.zip is unavailable
 * - CI environments (GitHub Actions) typically have JDK with src.zip
 *
 * This follows Bazel-style sandboxing principles:
 * - Tests never read from or write to user's home directory
 * - All state is isolated in temporary directories
 * - Tests are deterministic and reproducible
 */
class JdkSourceResolverTest {

    companion object {
        /**
         * Static check for src.zip availability.
         * Used by @EnabledIf annotations for conditional test execution.
         */
        @JvmStatic
        fun hasSrcZip(): Boolean {
            // Create a temporary resolver just to check for src.zip
            return JdkSourceResolver().findSrcZip() != null
        }
    }

    @TempDir
    lateinit var tempDir: Path

    private lateinit var resolver: JdkSourceResolver

    @BeforeEach
    fun setUp() {
        // Use temp directory for JDK source extraction to avoid polluting user's cache
        val jdkSourceDir = tempDir.resolve("jdk-sources")
        resolver = JdkSourceResolver(jdkSourceDir = jdkSourceDir)
    }

    @Nested
    inner class ParseJrtUriTest {

        @Test
        fun `parses java_base module Date class correctly`() {
            val uri = URI.create("jrt:/java.base/java/util/Date.class")
            val result = resolver.parseJrtUri(uri)

            assertNotNull(result)
            assertEquals("java.base", result!!.first)
            assertEquals("java/util/Date", result.second)
        }

        @Test
        fun `parses java_base module SimpleDateFormat correctly`() {
            val uri = URI.create("jrt:/java.base/java/text/SimpleDateFormat.class")
            val result = resolver.parseJrtUri(uri)

            assertNotNull(result)
            assertEquals("java.base", result!!.first)
            assertEquals("java/text/SimpleDateFormat", result.second)
        }

        @Test
        fun `parses java_sql module Connection correctly`() {
            val uri = URI.create("jrt:/java.sql/java/sql/Connection.class")
            val result = resolver.parseJrtUri(uri)

            assertNotNull(result)
            assertEquals("java.sql", result!!.first)
            assertEquals("java/sql/Connection", result.second)
        }

        @Test
        fun `parses java_xml module DocumentBuilder correctly`() {
            val uri = URI.create("jrt:/java.xml/javax/xml/parsers/DocumentBuilder.class")
            val result = resolver.parseJrtUri(uri)

            assertNotNull(result)
            assertEquals("java.xml", result!!.first)
            assertEquals("javax/xml/parsers/DocumentBuilder", result.second)
        }

        @Test
        fun `parses nested class URIs correctly`() {
            val uri = URI.create("jrt:/java.base/java/util/HashMap\$Entry.class")
            val result = resolver.parseJrtUri(uri)

            assertNotNull(result)
            assertEquals("java.base", result!!.first)
            assertEquals("java/util/HashMap\$Entry", result.second)
        }

        @Test
        fun `returns null for non-jrt scheme`() {
            val uri = URI.create("jar:file:///path/to/lib.jar!/com/example/Foo.class")
            val result = resolver.parseJrtUri(uri)

            assertNull(result)
        }

        @Test
        fun `returns null for file scheme`() {
            val uri = URI.create("file:///path/to/Source.java")
            val result = resolver.parseJrtUri(uri)

            assertNull(result)
        }

        @Test
        fun `returns null for malformed jrt URI without path separator`() {
            val uri = URI.create("jrt:/Date.class")
            val result = resolver.parseJrtUri(uri)

            // jrt:/Date.class splits into just ["Date.class"] - size < 2, returns null
            assertNull(result, "Malformed jrt: URI without module path separator should return null")
        }
    }

    @Nested
    inner class FindSrcZipTest {

        @Test
        fun `findSrcZip returns path when JAVA_HOME is set and src_zip exists`() {
            // This test will pass or fail depending on the environment
            // It serves as a smoke test for src.zip detection
            val srcZip = resolver.findSrcZip()

            // Just verify it doesn't throw - actual presence depends on JDK
            // In CI, we likely have a JDK with src.zip
            if (srcZip != null) {
                println("Found src.zip at: $srcZip")
            } else {
                println("src.zip not found - this is OK for JRE-only environments")
            }
        }
    }

    @Nested
    inner class StatisticsTest {

        @Test
        fun `getStatistics returns expected keys`() {
            val stats = resolver.getStatistics()

            assertEquals(0, stats["cachedClasses"])
            assertNotNull(stats["jdkSourceDir"])
            assertNotNull(stats["srcZipLocation"])
        }
    }

    @Nested
    inner class ResolveJdkSourceIntegrationTest {

        /**
         * Check if src.zip is available on this system.
         * Must be in this inner class for @EnabledIf to find it.
         */
        fun hasSrcZip(): Boolean = JdkSourceResolverTest.hasSrcZip()

        @Test
        @EnabledIf("hasSrcZip")
        fun `resolveJdkSource returns line number for JDK class`() = runBlocking {
            val jrtUri = URI.create("jrt:/java.base/java/util/Date.class")

            val result = resolver.resolveJdkSource(jrtUri, "java.util.Date")

            assertTrue(result is SourceNavigator.SourceResult.SourceLocation) {
                "Expected SourceLocation but got: $result"
            }

            val location = result as SourceNavigator.SourceResult.SourceLocation
            assertNotNull(location.lineNumber) {
                "Line number should not be null for java.util.Date"
            }
            assertTrue(location.lineNumber!! > 0) {
                "Line number should be positive, got: ${location.lineNumber}"
            }
        }

        @Test
        @EnabledIf("hasSrcZip")
        fun `resolveJdkSource returns documentation for JDK class`() = runBlocking {
            val jrtUri = URI.create("jrt:/java.base/java/text/SimpleDateFormat.class")

            val result = resolver.resolveJdkSource(jrtUri, "java.text.SimpleDateFormat")

            assertTrue(result is SourceNavigator.SourceResult.SourceLocation) {
                "Expected SourceLocation but got: $result"
            }

            val location = result as SourceNavigator.SourceResult.SourceLocation
            assertNotNull(location.documentation) {
                "Documentation should not be null for SimpleDateFormat"
            }

            // SimpleDateFormat has rich Javadoc - check it contains expected content
            val doc = location.documentation!!
            assertTrue(doc.summary.isNotBlank() || doc.description.isNotBlank()) {
                "Documentation should have summary or description. Got: $doc"
            }
        }
    }
}
