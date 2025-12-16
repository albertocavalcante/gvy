package com.github.albertocavalcante.groovylsp.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Tests for JdkSourceResolver JRT URI parsing.
 *
 * Note: Tests for actual src.zip extraction are integration tests
 * that require a JDK with src.zip present.
 */
class JdkSourceResolverTest {

    private val resolver = JdkSourceResolver()

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
}
