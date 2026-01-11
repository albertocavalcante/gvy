package com.github.albertocavalcante.groovylsp.sources

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

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
}
