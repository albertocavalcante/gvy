package com.github.groovylsp.bsp.maven.targets

import ch.epfl.scala.bsp4j.SourceItemKind
import com.github.groovylsp.bsp.maven.workspace.MavenModuleInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * TDD tests for MavenSourceProvider.
 *
 * These tests verify:
 * - Standard Maven source directory detection
 * - Custom source directory handling
 * - Generated sources detection
 * - Main vs test source separation
 */
class MavenSourceProviderTest {

    private lateinit var provider: MavenSourceProvider

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        provider = MavenSourceProvider()
    }

    @Nested
    inner class StandardSourceDirectories {

        @Test
        fun `should return standard Maven source directories`() {
            // Given: A Maven module with standard directory structure
            createStandardMavenStructure()
            val module = createModule()

            // When
            val sources = provider.getMainSources(module)

            // Then: Should include java, groovy, kotlin source dirs that exist
            assertThat(sources.sources).hasSize(3)
            assertThat(sources.sources.map { it.uri }).containsExactlyInAnyOrder(
                tempDir.resolve("src/main/java").toUri().toString(),
                tempDir.resolve("src/main/groovy").toUri().toString(),
                tempDir.resolve("src/main/kotlin").toUri().toString(),
            )
        }

        @Test
        fun `should only return existing source directories`() {
            // Given: A Maven module with only Java sources
            tempDir.resolve("src/main/java").createDirectories()
            val module = createModule()

            // When
            val sources = provider.getMainSources(module)

            // Then: Should only include Java
            assertThat(sources.sources).hasSize(1)
            assertThat(sources.sources.first().uri)
                .isEqualTo(tempDir.resolve("src/main/java").toUri().toString())
        }

        @Test
        fun `should return test source directories`() {
            // Given: A Maven module with test sources
            tempDir.resolve("src/test/java").createDirectories()
            tempDir.resolve("src/test/groovy").createDirectories()
            val module = createModule()

            // When
            val sources = provider.getTestSources(module)

            // Then
            assertThat(sources.sources).hasSize(2)
            assertThat(sources.sources.map { it.uri }).containsExactlyInAnyOrder(
                tempDir.resolve("src/test/java").toUri().toString(),
                tempDir.resolve("src/test/groovy").toUri().toString(),
            )
        }

        @Test
        fun `should separate main and test sources`() {
            // Given
            createStandardMavenStructure()
            tempDir.resolve("src/test/java").createDirectories()
            val module = createModule()

            // When
            val mainSources = provider.getMainSources(module)
            val testSources = provider.getTestSources(module)

            // Then: Main should not contain test, and vice versa
            assertThat(mainSources.sources.map { it.uri }).allMatch { !it.contains("/test/") }
            assertThat(testSources.sources.map { it.uri }).allMatch { it.contains("/test/") }
        }

        @Test
        fun `should set correct target ID for main sources`() {
            // Given
            tempDir.resolve("src/main/java").createDirectories()
            val module = createModule()

            // When
            val sources = provider.getMainSources(module)

            // Then
            assertThat(sources.target.uri).isEqualTo("maven:com.example:my-app")
        }

        @Test
        fun `should set correct target ID for test sources`() {
            // Given
            tempDir.resolve("src/test/java").createDirectories()
            val module = createModule()

            // When
            val sources = provider.getTestSources(module)

            // Then
            assertThat(sources.target.uri).isEqualTo("maven:com.example:my-app:test")
        }
    }

    @Nested
    inner class CustomSourceDirectories {

        @Test
        fun `should handle custom sourceDirectory in pom`() {
            // Given: A module with custom source directory
            tempDir.resolve("src/groovy").createDirectories()
            val module = MavenModuleInfo(
                pomPath = tempDir.resolve("pom.xml"),
                groupId = "com.example",
                artifactId = "my-app",
                version = "1.0.0",
                sourceDirectory = "src/groovy",
            )

            // When
            val sources = provider.getMainSources(module)

            // Then: Should use custom directory, not standard
            assertThat(sources.sources).hasSize(1)
            assertThat(sources.sources.first().uri)
                .isEqualTo(tempDir.resolve("src/groovy").toUri().toString())
        }

        @Test
        fun `should handle custom testSourceDirectory in pom`() {
            // Given: A module with custom test source directory
            tempDir.resolve("src/groovy-test").createDirectories()
            val module = MavenModuleInfo(
                pomPath = tempDir.resolve("pom.xml"),
                groupId = "com.example",
                artifactId = "my-app",
                version = "1.0.0",
                testSourceDirectory = "src/groovy-test",
            )

            // When
            val sources = provider.getTestSources(module)

            // Then
            assertThat(sources.sources).hasSize(1)
            assertThat(sources.sources.first().uri)
                .isEqualTo(tempDir.resolve("src/groovy-test").toUri().toString())
        }

        @Test
        fun `should ignore non-existent custom source directory`() {
            // Given: A module with custom source directory that doesn't exist
            val module = MavenModuleInfo(
                pomPath = tempDir.resolve("pom.xml"),
                groupId = "com.example",
                artifactId = "my-app",
                version = "1.0.0",
                sourceDirectory = "src/nonexistent",
            )

            // When
            val sources = provider.getMainSources(module)

            // Then
            assertThat(sources.sources).isEmpty()
        }
    }

    @Nested
    inner class GeneratedSources {

        @Test
        fun `should include generated sources`() {
            // Given: A module with generated sources
            tempDir.resolve("src/main/java").createDirectories()
            tempDir.resolve("target/generated-sources/annotations").createDirectories()
            val module = createModule()

            // When
            val sources = provider.getMainSources(module)

            // Then
            assertThat(sources.sources).hasSize(2)

            val generatedSource = sources.sources.find { it.uri.contains("generated-sources") }
            assertThat(generatedSource).isNotNull
            assertThat(generatedSource!!.generated).isTrue()
        }

        @Test
        fun `should include generated test sources`() {
            // Given
            tempDir.resolve("src/test/java").createDirectories()
            tempDir.resolve("target/generated-test-sources/test-annotations").createDirectories()
            val module = createModule()

            // When
            val sources = provider.getTestSources(module)

            // Then
            val generatedSource = sources.sources.find { it.uri.contains("generated-test-sources") }
            assertThat(generatedSource).isNotNull
            assertThat(generatedSource!!.generated).isTrue()
        }

        @Test
        fun `should mark regular sources as not generated`() {
            // Given
            tempDir.resolve("src/main/java").createDirectories()
            val module = createModule()

            // When
            val sources = provider.getMainSources(module)

            // Then
            assertThat(sources.sources.first().generated).isFalse()
        }
    }

    @Nested
    inner class SourceItemProperties {

        @Test
        fun `should set SourceItemKind to DIRECTORY`() {
            // Given
            tempDir.resolve("src/main/java").createDirectories()
            val module = createModule()

            // When
            val sources = provider.getMainSources(module)

            // Then
            assertThat(sources.sources.first().kind).isEqualTo(SourceItemKind.DIRECTORY)
        }

        @Test
        fun `should return URI format for source paths`() {
            // Given
            tempDir.resolve("src/main/java").createDirectories()
            val module = createModule()

            // When
            val sources = provider.getMainSources(module)

            // Then
            assertThat(sources.sources.first().uri).startsWith("file:")
        }
    }

    @Nested
    inner class BulkOperations {

        @Test
        fun `should get sources for multiple modules`() {
            // Given
            val moduleADir = tempDir.resolve("module-a").createDirectories()
            moduleADir.resolve("src/main/java").createDirectories()
            val moduleA = MavenModuleInfo(
                pomPath = moduleADir.resolve("pom.xml"),
                groupId = "com.example",
                artifactId = "module-a",
                version = "1.0.0",
            )

            val moduleBDir = tempDir.resolve("module-b").createDirectories()
            moduleBDir.resolve("src/main/groovy").createDirectories()
            val moduleB = MavenModuleInfo(
                pomPath = moduleBDir.resolve("pom.xml"),
                groupId = "com.example",
                artifactId = "module-b",
                version = "1.0.0",
            )

            // When
            val result = provider.getSources(listOf(moduleA, moduleB))

            // Then: Should have 4 SourcesItem (main + test for each module)
            assertThat(result.items).hasSize(4)
            assertThat(result.items.map { it.target.uri }).containsExactlyInAnyOrder(
                "maven:com.example:module-a",
                "maven:com.example:module-a:test",
                "maven:com.example:module-b",
                "maven:com.example:module-b:test",
            )
        }

        @Test
        fun `should return empty sources for module with no source directories`() {
            // Given: A module with no source directories created
            val module = createModule()

            // When
            val sources = provider.getMainSources(module)

            // Then
            assertThat(sources.sources).isEmpty()
        }
    }

    private fun createModule(): MavenModuleInfo = MavenModuleInfo(
        pomPath = tempDir.resolve("pom.xml"),
        groupId = "com.example",
        artifactId = "my-app",
        version = "1.0.0",
    )

    private fun createStandardMavenStructure() {
        tempDir.resolve("src/main/java").createDirectories()
        tempDir.resolve("src/main/groovy").createDirectories()
        tempDir.resolve("src/main/kotlin").createDirectories()
        tempDir.resolve("src/main/resources").createDirectories()
    }
}
