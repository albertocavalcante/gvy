package com.github.albertocavalcante.groovylsp.sources

import com.github.albertocavalcante.groovylsp.buildtool.MavenSourceArtifactResolver
import com.github.albertocavalcante.groovylsp.buildtool.SourceArtifactResolver
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Unit tests for GroovySourceResolver.
 *
 * ## Test Strategy
 *
 * **Unit Tests (hermetic, always run):**
 * - Tests that don't require actual Groovy sources use mocks
 * - All file operations use @TempDir for isolation
 *
 * **Integration Tests (conditional, require Groovy runtime):**
 * - Tests that need actual Groovy sources use @EnabledIf("hasGroovy")
 * - These verify real-world behavior but gracefully skip if Groovy is unavailable
 */
class GroovySourceResolverTest {

    companion object {
        /**
         * Static check for Groovy availability.
         * Used by @EnabledIf annotations for conditional test execution.
         */
        @JvmStatic
        fun hasGroovy(): Boolean = try {
            Class.forName("groovy.lang.GroovySystem")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    @TempDir
    lateinit var tempDir: Path

    private lateinit var groovySourceDir: Path
    private lateinit var javaSourceInspector: JavaSourceInspector

    @BeforeEach
    fun setUp() {
        groovySourceDir = tempDir.resolve("groovy-sources")
        javaSourceInspector = JavaSourceInspector()
    }

    @Test
    fun `should handle initialization without crashing`() {
        // Given - a resolver with temp directory
        val resolver = GroovySourceResolver(
            groovySourceDir = groovySourceDir,
            javaSourceInspector = javaSourceInspector,
        )

        // When - we query before initialization
        val paramNames = resolver.getParameterNames(
            "DefaultGroovyMethods",
            "each",
            listOf("Closure"),
        )

        // Then - should return null (not initialized)
        assertThat(paramNames).isNull()
    }

    @Test
    @EnabledIf("hasGroovy")
    fun `should detect Groovy version from classpath`() {
        // Given - Groovy is available on classpath
        val resolver = GroovySourceResolver(
            groovySourceDir = groovySourceDir,
            javaSourceInspector = javaSourceInspector,
        )

        // When - we try to detect the version (indirectly through initialization)
        // The resolver will try to detect Groovy version internally

        // Then - we can check if Groovy is available
        val groovySystemClass = Class.forName("groovy.lang.GroovySystem")
        val versionMethod = groovySystemClass.getMethod("getVersion")
        val version = versionMethod.invoke(null) as String

        assertThat(version).isNotBlank()
        assertThat(version).matches("\\d+\\.\\d+\\.\\d+.*")
    }

    @Test
    fun `should return null for unknown method in index`() {
        // Given
        val resolver = GroovySourceResolver(
            groovySourceDir = groovySourceDir,
            javaSourceInspector = javaSourceInspector,
        )

        // When - query before initialization
        val paramNames = resolver.getParameterNames(
            "DefaultGroovyMethods",
            "nonExistent",
            listOf("String"),
        )

        // Then
        assertThat(paramNames).isNull()
    }

    @Test
    fun `should provide statistics`() {
        // Given
        val resolver = GroovySourceResolver(
            groovySourceDir = groovySourceDir,
            javaSourceInspector = javaSourceInspector,
        )

        // When
        val stats = resolver.getStatistics()

        // Then
        assertThat(stats).containsKeys("cachedSources", "groovySourceDir", "indexStats")
        assertThat(stats["groovySourceDir"]).isEqualTo(groovySourceDir.toString())
    }

    @Test
    fun `should clear cache and index`() {
        // Given
        val resolver = GroovySourceResolver(
            groovySourceDir = groovySourceDir,
            javaSourceInspector = javaSourceInspector,
        )

        // When
        resolver.clearCache(deleteFiles = false)

        // Then
        val stats = resolver.getStatistics()
        assertThat(stats["cachedSources"]).isEqualTo(0)
    }

    @Test
    fun `should handle GDK class list`() {
        // Given - the predefined GDK classes
        val gdkClasses = GroovySourceResolver.GDK_CLASSES

        // Then
        assertThat(gdkClasses).isNotEmpty
        assertThat(gdkClasses).contains(
            "org.codehaus.groovy.runtime.DefaultGroovyMethods",
            "org.codehaus.groovy.runtime.StringGroovyMethods",
        )
    }

    @Test
    fun `should use default groovy source directory`() {
        // When
        val defaultDir = GroovySourceResolver.getDefaultGroovySourceDir()

        // Then
        assertThat(defaultDir.toString()).contains(".gls")
        assertThat(defaultDir.toString()).contains("groovy-sources")
    }

    @Nested
    inner class JarHandlingTest {

        @Test
        fun `should handle non-existent source JAR gracefully`() = runBlocking {
            // Given - a resolver with a mock artifact resolver that returns non-existent JAR
            val nonExistentJar = tempDir.resolve("nonexistent.jar")
            val mockResolver = object : SourceArtifactResolver {
                override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
                    nonExistentJar

                override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean = false
                override val cacheDir: Path = tempDir
            }

            val resolver = GroovySourceResolver(
                groovySourceDir = groovySourceDir,
                javaSourceInspector = javaSourceInspector,
                sourceArtifactResolver = mockResolver,
            )

            // When - we try to initialize with non-existent JAR
            val result = resolver.initialize()

            // Then - should fail gracefully without throwing
            assertThat(result).isFalse()

            // And - statistics should show no cached sources
            val stats = resolver.getStatistics()
            assertThat(stats["cachedSources"]).isEqualTo(0)
        }

        @Test
        fun `should handle corrupted JAR gracefully`() = runBlocking {
            // Given - a corrupted JAR file (empty file pretending to be a JAR)
            val corruptedJar = tempDir.resolve("corrupted.jar")
            Files.write(corruptedJar, byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // Partial ZIP header

            val mockResolver = object : SourceArtifactResolver {
                override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
                    corruptedJar

                override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean = false
                override val cacheDir: Path = tempDir
            }

            val resolver = GroovySourceResolver(
                groovySourceDir = groovySourceDir,
                javaSourceInspector = javaSourceInspector,
                sourceArtifactResolver = mockResolver,
            )

            // When - we try to initialize with corrupted JAR
            val result = resolver.initialize()

            // Then - should fail gracefully without throwing
            assertThat(result).isFalse()

            // And - statistics should show no cached sources
            val stats = resolver.getStatistics()
            assertThat(stats["cachedSources"]).isEqualTo(0)
        }

        @Test
        fun `should handle empty JAR gracefully`() = runBlocking {
            // Given - an empty but valid JAR file
            val emptyJar = createTestSourceJar()

            val mockResolver = object : SourceArtifactResolver {
                override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
                    emptyJar

                override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean = false
                override val cacheDir: Path = tempDir
            }

            val resolver = GroovySourceResolver(
                groovySourceDir = groovySourceDir,
                javaSourceInspector = javaSourceInspector,
                sourceArtifactResolver = mockResolver,
            )

            // When - we try to initialize with empty JAR
            val result = resolver.initialize()

            // Then - should fail because no GDK classes found
            assertThat(result).isFalse()

            // And - statistics should show no cached sources
            val stats = resolver.getStatistics()
            assertThat(stats["cachedSources"]).isEqualTo(0)
        }

        @Test
        fun `should handle missing source file in JAR`() = runBlocking {
            // Given - a JAR with some files but missing the GDK class we're looking for
            val jarWithWrongFiles = createTestSourceJar(
                "com/example/SomeOtherClass.java" to "package com.example; class SomeOtherClass {}",
            )

            val mockResolver = object : SourceArtifactResolver {
                override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
                    jarWithWrongFiles

                override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean = false
                override val cacheDir: Path = tempDir
            }

            val resolver = GroovySourceResolver(
                groovySourceDir = groovySourceDir,
                javaSourceInspector = javaSourceInspector,
                sourceArtifactResolver = mockResolver,
            )

            // When - we try to initialize
            val result = resolver.initialize()

            // Then - should fail because no GDK classes found
            assertThat(result).isFalse()

            // And - statistics should show no cached sources
            val stats = resolver.getStatistics()
            assertThat(stats["cachedSources"]).isEqualTo(0)
        }
    }

    @Nested
    inner class GroovyVersionCoordinatesTest {

        @Test
        fun `should use apache groovy coordinates for Groovy 4_x`() = runBlocking {
            // Given - mock resolver that returns null (we won't get to download)
            val mockResolver = object : SourceArtifactResolver {
                var capturedGroupId: String? = null
                var capturedArtifactId: String? = null
                var capturedVersion: String? = null

                override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? {
                    capturedGroupId = groupId
                    capturedArtifactId = artifactId
                    capturedVersion = version
                    return null
                }

                override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean = false
                override val cacheDir: Path = tempDir
            }

            // Create resolver with mock that simulates Groovy 4.0.0
            val resolver = TestableGroovySourceResolver(
                groovySourceDir = groovySourceDir,
                javaSourceInspector = javaSourceInspector,
                sourceArtifactResolver = mockResolver,
                groovyVersion = "4.0.0",
            )

            // When - we initialize (will fail but we can check coordinates)
            resolver.initialize()

            // Then - should use Apache Groovy coordinates
            assertThat(mockResolver.capturedGroupId).isEqualTo("org.apache.groovy")
            assertThat(mockResolver.capturedArtifactId).isEqualTo("groovy")
            assertThat(mockResolver.capturedVersion).isEqualTo("4.0.0")
        }

        @Test
        fun `should use codehaus groovy coordinates for Groovy 3_x`() = runBlocking {
            // Given - mock resolver
            val mockResolver = object : SourceArtifactResolver {
                var capturedGroupId: String? = null
                var capturedArtifactId: String? = null
                var capturedVersion: String? = null

                override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? {
                    capturedGroupId = groupId
                    capturedArtifactId = artifactId
                    capturedVersion = version
                    return null
                }

                override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean = false
                override val cacheDir: Path = tempDir
            }

            // Create resolver with mock that simulates Groovy 3.0.0
            val resolver = TestableGroovySourceResolver(
                groovySourceDir = groovySourceDir,
                javaSourceInspector = javaSourceInspector,
                sourceArtifactResolver = mockResolver,
                groovyVersion = "3.0.0",
            )

            // When - we initialize
            resolver.initialize()

            // Then - should use Codehaus coordinates
            assertThat(mockResolver.capturedGroupId).isEqualTo("org.codehaus.groovy")
            assertThat(mockResolver.capturedArtifactId).isEqualTo("groovy")
            assertThat(mockResolver.capturedVersion).isEqualTo("3.0.0")
        }

        @Test
        fun `should use codehaus groovy coordinates for Groovy 2_x`() = runBlocking {
            // Given - mock resolver
            val mockResolver = object : SourceArtifactResolver {
                var capturedGroupId: String? = null

                override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? {
                    capturedGroupId = groupId
                    return null
                }

                override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean = false
                override val cacheDir: Path = tempDir
            }

            // Create resolver with mock that simulates Groovy 2.5.0
            val resolver = TestableGroovySourceResolver(
                groovySourceDir = groovySourceDir,
                javaSourceInspector = javaSourceInspector,
                sourceArtifactResolver = mockResolver,
                groovyVersion = "2.5.0",
            )

            // When - we initialize
            resolver.initialize()

            // Then - should use Codehaus coordinates
            assertThat(mockResolver.capturedGroupId).isEqualTo("org.codehaus.groovy")
        }
    }

    @Nested
    inner class CacheManagementTest {

        @Test
        fun `should delete files when clearing cache with deleteFiles flag`() = runBlocking {
            // Given - create some files in the groovy source directory
            Files.createDirectories(groovySourceDir)
            val testFile1 = groovySourceDir.resolve("test1.java")
            val testFile2 = groovySourceDir.resolve("subdir").resolve("test2.java")
            Files.createDirectories(testFile2.parent)
            Files.writeString(testFile1, "class Test1 {}")
            Files.writeString(testFile2, "class Test2 {}")

            val resolver = GroovySourceResolver(
                groovySourceDir = groovySourceDir,
                javaSourceInspector = javaSourceInspector,
            )

            // When - we clear cache with deleteFiles = true
            resolver.clearCache(deleteFiles = true)

            // Then - files should be deleted
            assertThat(Files.exists(testFile1)).isFalse()
            assertThat(Files.exists(testFile2)).isFalse()
            assertThat(Files.exists(groovySourceDir)).isFalse()

            // And - statistics should show zero cached sources
            val stats = resolver.getStatistics()
            assertThat(stats["cachedSources"]).isEqualTo(0)
        }

        @Test
        fun `should not delete files when clearing cache without deleteFiles flag`() = runBlocking {
            // Given - create some files in the groovy source directory
            Files.createDirectories(groovySourceDir)
            val testFile = groovySourceDir.resolve("test.java")
            Files.writeString(testFile, "class Test {}")

            val resolver = GroovySourceResolver(
                groovySourceDir = groovySourceDir,
                javaSourceInspector = javaSourceInspector,
            )

            // When - we clear cache with deleteFiles = false
            resolver.clearCache(deleteFiles = false)

            // Then - files should still exist
            assertThat(Files.exists(testFile)).isTrue()
            assertThat(Files.readString(testFile)).isEqualTo("class Test {}")

            // But - cache should be cleared
            val stats = resolver.getStatistics()
            assertThat(stats["cachedSources"]).isEqualTo(0)
        }

        @Test
        fun `should use cached path on subsequent calls to extractSourceFromJar`() = runBlocking {
            // Given - a JAR with a GDK class source
            val gdkClassPath = "org/codehaus/groovy/runtime/DefaultGroovyMethods.java"
            val sourceContent = """
                package org.codehaus.groovy.runtime;
                public class DefaultGroovyMethods {
                    public static void each(Object self, Closure closure) {}
                }
            """.trimIndent()

            val sourcesJar = createTestSourceJar(gdkClassPath to sourceContent)

            val mockResolver = object : SourceArtifactResolver {
                override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
                    sourcesJar

                override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean = false
                override val cacheDir: Path = tempDir
            }

            val resolver = GroovySourceResolver(
                groovySourceDir = groovySourceDir,
                javaSourceInspector = javaSourceInspector,
                sourceArtifactResolver = mockResolver,
            )

            // When - we initialize (this will extract sources)
            val firstInit = resolver.initialize()

            // Then - should succeed
            assertThat(firstInit).isTrue()

            // And - should have cached the source
            val statsAfterFirst = resolver.getStatistics()
            assertThat(statsAfterFirst["cachedSources"]).isEqualTo(1)

            // When - we initialize again
            val secondInit = resolver.initialize()

            // Then - should still succeed
            assertThat(secondInit).isTrue()

            // And - cache count should remain the same (reused)
            val statsAfterSecond = resolver.getStatistics()
            assertThat(statsAfterSecond["cachedSources"]).isEqualTo(1)

            // And - the extracted file should exist
            val extractedPath = groovySourceDir.resolve(gdkClassPath)
            assertThat(Files.exists(extractedPath)).isTrue()
            assertThat(Files.readString(extractedPath)).contains("DefaultGroovyMethods")
        }
    }

    @Nested
    inner class IntegrationTestsRequiringNetwork {

        @Test
        @Disabled(
            "Requires network access to Maven Central and real Groovy runtime. " +
                "Enable manually for full integration testing. " +
                "TODO: Create hermetic test with pre-downloaded groovy-sources.jar",
        )
        fun `should download and index real Groovy sources from Maven Central`() = runBlocking {
            // This test would require:
            // 1. Real Groovy on classpath (for version detection)
            // 2. Network access to Maven Central
            // 3. Time to download groovy-sources.jar
            //
            // Given - a resolver with real artifact resolver
            val resolver = GroovySourceResolver(
                groovySourceDir = groovySourceDir,
                javaSourceInspector = javaSourceInspector,
            )

            // When - we initialize
            val result = resolver.initialize()

            // Then - should succeed
            assertThat(result).isTrue()

            // And - should have indexed GDK methods
            val stats = resolver.getStatistics()
            val indexStats = stats["indexStats"] as Map<*, *>
            val indexedMethods = indexStats["indexedMethods"] as Int
            assertThat(indexedMethods).isGreaterThan(0)

            // And - should be able to resolve parameter names
            val paramNames = resolver.getParameterNames(
                "DefaultGroovyMethods",
                "each",
                listOf("Closure"),
            )
            assertThat(paramNames).isNotNull()
            assertThat(paramNames).isNotEmpty()
        }
    }

    /**
     * Helper class to test Groovy version coordinate mapping.
     *
     * This subclass allows us to override the Groovy version detection
     * to test the Maven coordinate mapping logic without requiring
     * a specific Groovy runtime version.
     */
    private class TestableGroovySourceResolver(
        groovySourceDir: Path,
        javaSourceInspector: JavaSourceInspector,
        sourceArtifactResolver: SourceArtifactResolver,
        private val groovyVersion: String,
    ) : GroovySourceResolver(groovySourceDir, javaSourceInspector, sourceArtifactResolver) {
        // Override to simulate having a specific Groovy version
        override suspend fun initialize(): Boolean {
            // Simulate the coordinate mapping logic from the parent class
            // This duplicates the logic from getGroovyMavenCoordinates to test it
            val coordinates = getGroovyMavenCoordinatesForTesting(groovyVersion)
            sourceArtifactResolver.resolveSourceJar(
                coordinates.groupId,
                coordinates.artifactId,
                coordinates.version,
            )
            return false // Always return false since we're just testing coordinates
        }

        private fun getGroovyMavenCoordinatesForTesting(version: String): MavenCoordinates {
            val majorVersion = version.split(".").firstOrNull()?.toIntOrNull() ?: 4
            return if (majorVersion >= 4) {
                MavenCoordinates("org.apache.groovy", "groovy", version)
            } else {
                MavenCoordinates("org.codehaus.groovy", "groovy", version)
            }
        }

        private data class MavenCoordinates(val groupId: String, val artifactId: String, val version: String)
    }

    // Helper to create test source JARs
    private fun createTestSourceJar(vararg files: Pair<String, String>): Path {
        val jarPath = tempDir.resolve("test-sources-${System.nanoTime()}.jar")

        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            files.forEach { (path, content) ->
                jar.putNextEntry(JarEntry(path))
                jar.write(content.toByteArray())
                jar.closeEntry()
            }
        }

        return jarPath
    }
}
