package com.github.groovylsp.bsp.maven.integration

import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.StatusCode
import com.github.groovylsp.bsp.maven.launcher.MavenBspLauncher
import com.github.groovylsp.bsp.maven.server.MavenBuildServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Integration tests for Maven BSP server.
 *
 * These tests use real Maven project fixtures to verify end-to-end behavior.
 * They test the full stack from server creation through BSP protocol responses.
 */
class MavenBspIntegrationTest {

    private val fixturesDir: Path = Paths.get("src/test/resources/fixtures")

    @Nested
    inner class SingleModuleProject {

        private lateinit var server: MavenBuildServer

        @BeforeEach
        fun setUp() {
            val projectDir = fixturesDir.resolve("single-module").toAbsolutePath()
            server = MavenBspLauncher.createServer(projectDir)
            initializeServer(server, projectDir)
        }

        @Test
        fun `should resolve single-module project end-to-end`() {
            // When
            val targets = server.workspaceBuildTargets().get()

            // Then: Should have main and test targets
            assertThat(targets.targets).hasSize(2)
            assertThat(targets.targets.map { it.id.uri }).containsExactlyInAnyOrder(
                "maven:com.example:simple-app",
                "maven:com.example:simple-app:test",
            )
        }

        @Test
        fun `should return correct capabilities for targets`() {
            // When
            val targets = server.workspaceBuildTargets().get()
            val mainTarget = targets.targets.find { it.id.uri == "maven:com.example:simple-app" }

            // Then
            assertThat(mainTarget).isNotNull
            assertThat(mainTarget!!.capabilities.canCompile).isTrue()
            assertThat(mainTarget.capabilities.canTest).isFalse()
            assertThat(mainTarget.languageIds).contains("java")
        }

        @Test
        fun `should return sources for single-module project`() {
            // Given
            val params = SourcesParams(listOf(BuildTargetIdentifier("maven:com.example:simple-app")))

            // When
            val result = server.buildTargetSources(params).get()

            // Then
            assertThat(result.items).isNotEmpty
            val mainSources = result.items.find { it.target.uri == "maven:com.example:simple-app" }
            assertThat(mainSources).isNotNull
        }
    }

    @Nested
    inner class MultiModuleProject {

        private lateinit var server: MavenBuildServer

        @BeforeEach
        fun setUp() {
            val projectDir = fixturesDir.resolve("multi-module").toAbsolutePath()
            server = MavenBspLauncher.createServer(projectDir)
            initializeServer(server, projectDir)
        }

        @Test
        fun `should resolve multi-module project end-to-end`() {
            // When
            val targets = server.workspaceBuildTargets().get()

            // Then: 2 modules x 2 targets each = 4 (parent is pom, no targets)
            assertThat(targets.targets).hasSize(4)
        }

        @Test
        fun `should have correct target IDs for modules`() {
            // When
            val targets = server.workspaceBuildTargets().get()
            val targetUris = targets.targets.map { it.id.uri }

            // Then
            assertThat(targetUris).containsExactlyInAnyOrder(
                "maven:com.example:module-a",
                "maven:com.example:module-a:test",
                "maven:com.example:module-b",
                "maven:com.example:module-b:test",
            )
        }

        @Test
        fun `should include inter-module dependencies`() {
            // When
            val targets = server.workspaceBuildTargets().get()
            val moduleBTarget = targets.targets.find { it.id.uri == "maven:com.example:module-b" }

            // Then: module-b depends on module-a
            assertThat(moduleBTarget).isNotNull
            assertThat(moduleBTarget!!.dependencies.map { it.uri })
                .contains("maven:com.example:module-a")
        }
    }

    @Nested
    inner class JenkinsStyleProject {

        private lateinit var server: MavenBuildServer

        @BeforeEach
        fun setUp() {
            val projectDir = fixturesDir.resolve("jenkins-style").toAbsolutePath()
            server = MavenBspLauncher.createServer(projectDir)
            initializeServer(server, projectDir)
        }

        @Test
        fun `should handle HPI packaging type`() {
            // When
            val targets = server.workspaceBuildTargets().get()

            // Then: HPI is like JAR, should have targets
            assertThat(targets.targets).hasSize(2)
            assertThat(targets.targets.map { it.id.uri }).containsExactlyInAnyOrder(
                "maven:org.jenkins-ci.plugins:example-plugin",
                "maven:org.jenkins-ci.plugins:example-plugin:test",
            )
        }
    }

    @Nested
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    inner class RealProjectTests {

        @Test
        fun `should work with pipeline-library project`() {
            // Given: Real project (skip if not available)
            val pipelineLibPath = Paths.get(System.getProperty("user.home"))
                .resolve("dev/refs/pipeline-library")

            if (!pipelineLibPath.toFile().exists()) {
                println("Skipping: pipeline-library not found at $pipelineLibPath")
                return
            }

            // When
            val server = MavenBspLauncher.createServer(pipelineLibPath)
            initializeServer(server, pipelineLibPath)
            val targets = server.workspaceBuildTargets().get()

            // Then
            assertThat(targets.targets).isNotEmpty
            println("Found ${targets.targets.size} targets in pipeline-library")
            targets.targets.forEach { target ->
                println("  - ${target.id.uri}")
            }
        }
    }

    @Nested
    inner class ServerLifecycle {

        @Test
        fun `should handle workspace reload`() {
            // Given
            val projectDir = fixturesDir.resolve("single-module").toAbsolutePath()
            val server = MavenBspLauncher.createServer(projectDir)
            initializeServer(server, projectDir)

            // When: Reload workspace
            server.workspaceReload().get()

            // Then: Should still have targets
            val targets = server.workspaceBuildTargets().get()
            assertThat(targets.targets).hasSize(2)
        }

        @Test
        fun `should handle compile request`() {
            // Given
            val projectDir = fixturesDir.resolve("single-module").toAbsolutePath()
            val server = MavenBspLauncher.createServer(projectDir)
            initializeServer(server, projectDir)
            val params = CompileParams(listOf(BuildTargetIdentifier("maven:com.example:simple-app")))

            // When
            val result = server.buildTargetCompile(params).get()

            // Then
            assertThat(result.statusCode).isEqualTo(StatusCode.OK)
        }
    }

    private fun initializeServer(server: MavenBuildServer, projectDir: Path) {
        val params = InitializeBuildParams(
            "Integration Test Client",
            "1.0.0",
            "2.1.0",
            projectDir.toUri().toString(),
            BuildClientCapabilities(listOf("java", "groovy", "kotlin")),
        )
        server.buildInitialize(params).get()
        server.onBuildInitialized()
    }
}
