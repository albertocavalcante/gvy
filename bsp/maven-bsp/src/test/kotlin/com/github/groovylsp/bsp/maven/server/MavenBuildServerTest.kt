package com.github.groovylsp.bsp.maven.server

import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.DependencyModulesParams
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.InverseSourcesParams
import ch.epfl.scala.bsp4j.OutputPathsParams
import ch.epfl.scala.bsp4j.ResourcesParams
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.StatusCode
import ch.epfl.scala.bsp4j.TextDocumentIdentifier
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.resolution.DependencyResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Tests for MavenBuildServer.
 */
class MavenBuildServerTest {

    private lateinit var server: MavenBuildServer
    private lateinit var repositorySystem: RepositorySystem
    private lateinit var session: RepositorySystemSession

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        repositorySystem = mockk()
        session = mockk()

        // Mock empty dependency resolution by default
        val emptyResult = mockk<DependencyResult>()
        every { emptyResult.artifactResults } returns emptyList()
        every {
            repositorySystem.resolveDependencies(any(), any<org.eclipse.aether.resolution.DependencyRequest>())
        } returns
            emptyResult

        server = MavenBuildServer(tempDir, repositorySystem) { session }
    }

    @Nested
    inner class Initialization {

        @Test
        fun `should return server capabilities on initialize`() {
            // Given
            createSimpleMavenProject()
            val params = InitializeBuildParams(
                "Test Client",
                "1.0.0",
                "2.1.0",
                tempDir.toUri().toString(),
                BuildClientCapabilities(listOf("java")),
            )

            // When
            val result = server.buildInitialize(params).get()

            // Then
            assertThat(result.displayName).isEqualTo("Maven BSP")
            assertThat(result.bspVersion).isEqualTo("2.1.0")
            assertThat(result.capabilities.compileProvider).isNotNull
            assertThat(result.capabilities.testProvider).isNotNull
            assertThat(result.capabilities.dependencyModulesProvider).isTrue()
        }

        @Test
        fun `should scan workspace during initialization`() {
            // Given
            createSimpleMavenProject()
            val params = InitializeBuildParams(
                "Test Client",
                "1.0.0",
                "2.1.0",
                tempDir.toUri().toString(),
                BuildClientCapabilities(listOf("java")),
            )

            // When
            server.buildInitialize(params).get()

            // Then: Should find the module
            val targets = server.workspaceBuildTargets().get()
            assertThat(targets.targets).hasSize(2) // main + test
        }
    }

    @Nested
    inner class BuildTargets {

        @Test
        fun `should return all build targets on workspaceBuildTargets`() {
            // Given
            createSimpleMavenProject()
            initializeServer()

            // When
            val result = server.workspaceBuildTargets().get()

            // Then
            assertThat(result.targets).hasSize(2)
            assertThat(result.targets.map { it.id.uri }).containsExactlyInAnyOrder(
                "maven:com.example:my-app",
                "maven:com.example:my-app:test",
            )
        }

        @Test
        fun `should return build targets for multi-module project`() {
            // Given
            createMultiModuleMavenProject()
            initializeServer()

            // When
            val result = server.workspaceBuildTargets().get()

            // Then: 2 modules x 2 targets each = 4 (parent is aggregator, no targets)
            assertThat(result.targets).hasSize(4)
        }
    }

    @Nested
    inner class Sources {

        @Test
        fun `should return sources on buildTargetSources`() {
            // Given
            createSimpleMavenProject()
            tempDir.resolve("src/main/java").createDirectories()
            initializeServer()

            val params = SourcesParams(listOf(BuildTargetIdentifier("maven:com.example:my-app")))

            // When
            val result = server.buildTargetSources(params).get()

            // Then: Returns sources for requested targets (main + test for the module)
            val mainSources = result.items.find { it.target.uri == "maven:com.example:my-app" }
            assertThat(mainSources).isNotNull
            assertThat(mainSources!!.sources).isNotEmpty
        }
    }

    @Nested
    inner class Dependencies {

        @Test
        fun `should return dependencies on buildTargetDependencyModules`() {
            // Given
            createSimpleMavenProject()
            initializeServer()

            val params = DependencyModulesParams(listOf(BuildTargetIdentifier("maven:com.example:my-app")))

            // When
            val result = server.buildTargetDependencyModules(params).get()

            // Then
            assertThat(result.items).isNotEmpty
        }
    }

    @Nested
    inner class Compilation {

        @Test
        fun `should handle compile request`() {
            // Given
            createSimpleMavenProject()
            initializeServer()

            val params = CompileParams(listOf(BuildTargetIdentifier("maven:com.example:my-app")))

            // When
            val result = server.buildTargetCompile(params).get()

            // Then
            assertThat(result.statusCode).isEqualTo(StatusCode.OK)
        }
    }

    @Nested
    inner class Resources {

        @Test
        fun `should return resources for target`() {
            // Given
            createSimpleMavenProject()
            tempDir.resolve("src/main/resources").createDirectories()
            initializeServer()

            val params = ResourcesParams(listOf(BuildTargetIdentifier("maven:com.example:my-app")))

            // When
            val result = server.buildTargetResources(params).get()

            // Then
            assertThat(result.items).hasSize(1)
            assertThat(result.items.first().resources).hasSize(1)
        }
    }

    @Nested
    inner class OutputPaths {

        @Test
        fun `should return output paths for target`() {
            // Given
            createSimpleMavenProject()
            initializeServer()

            val params = OutputPathsParams(listOf(BuildTargetIdentifier("maven:com.example:my-app")))

            // When
            val result = server.buildTargetOutputPaths(params).get()

            // Then
            assertThat(result.items).hasSize(1)
            assertThat(result.items.first().outputPaths).hasSize(1)
            assertThat(result.items.first().outputPaths.first().uri).contains("target/classes")
        }

        @Test
        fun `should return test-classes for test target`() {
            // Given
            createSimpleMavenProject()
            initializeServer()

            val params = OutputPathsParams(listOf(BuildTargetIdentifier("maven:com.example:my-app:test")))

            // When
            val result = server.buildTargetOutputPaths(params).get()

            // Then
            assertThat(result.items.first().outputPaths.first().uri).contains("target/test-classes")
        }
    }

    @Nested
    inner class InverseSources {

        @Test
        fun `should find target containing source file`() {
            // Given
            createSimpleMavenProject()
            val javaFile = tempDir.resolve("src/main/java/App.java")
            javaFile.parent.createDirectories()
            javaFile.writeText("class App {}")
            initializeServer()

            val params = InverseSourcesParams(TextDocumentIdentifier(javaFile.toUri().toString()))

            // When
            val result = server.buildTargetInverseSources(params).get()

            // Then
            assertThat(result.targets).hasSize(1)
            assertThat(result.targets.first().uri).isEqualTo("maven:com.example:my-app")
        }
    }

    @Nested
    inner class WorkspaceReload {

        @Test
        fun `should reload workspace on workspaceReload`() {
            // Given
            createSimpleMavenProject()
            initializeServer()

            // When
            server.workspaceReload().get()

            // Then: Should still have targets
            val result = server.workspaceBuildTargets().get()
            assertThat(result.targets).isNotEmpty
        }
    }

    private fun createSimpleMavenProject() {
        val pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0</version>
            </project>
        """.trimIndent()
        tempDir.resolve("pom.xml").writeText(pomContent)
    }

    private fun createMultiModuleMavenProject() {
        val parentPom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>
                <modules>
                    <module>module-a</module>
                    <module>module-b</module>
                </modules>
            </project>
        """.trimIndent()
        tempDir.resolve("pom.xml").writeText(parentPom)

        val moduleADir = tempDir.resolve("module-a").createDirectories()
        val moduleAPom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>module-a</artifactId>
            </project>
        """.trimIndent()
        moduleADir.resolve("pom.xml").writeText(moduleAPom)

        val moduleBDir = tempDir.resolve("module-b").createDirectories()
        val moduleBPom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>module-b</artifactId>
            </project>
        """.trimIndent()
        moduleBDir.resolve("pom.xml").writeText(moduleBPom)
    }

    private fun initializeServer() {
        val params = InitializeBuildParams(
            "Test Client",
            "1.0.0",
            "2.1.0",
            tempDir.toUri().toString(),
            BuildClientCapabilities(listOf("java")),
        )
        server.buildInitialize(params).get()
        server.onBuildInitialized()
    }
}
