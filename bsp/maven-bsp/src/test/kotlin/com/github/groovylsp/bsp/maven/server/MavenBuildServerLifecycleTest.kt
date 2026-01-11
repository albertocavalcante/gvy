package com.github.groovylsp.bsp.maven.server

import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.SourcesParams
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
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Tests for BSP lifecycle compliance of MavenBuildServer.
 *
 * Verifies that the server correctly implements the BSP lifecycle protocol:
 * 1. build/initialize must be called first
 * 2. build/initialized notification follows
 * 3. Other requests should be rejected before initialization
 * 4. build/shutdown and build/exit for graceful termination
 */
class MavenBuildServerLifecycleTest {

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
        } returns emptyResult

        createSimpleMavenProject()
        server = MavenBuildServer(tempDir, repositorySystem) { session }
    }

    @Nested
    inner class PreInitializationBehavior {

        @Test
        fun `workspaceBuildTargets before initialization returns empty results`() {
            // ISSUE: This test documents the CURRENT BEHAVIOR, which violates BSP spec
            // According to BSP 2.1, server should reject requests before initialization
            // with error code -32002 (ServerNotInitialized)
            //
            // Current behavior: Returns empty list instead of error

            // Given: Server not initialized (no buildInitialize called)

            // When: Call workspaceBuildTargets before initialization
            val result = server.workspaceBuildTargets().get()

            // Then: CURRENT BEHAVIOR - Returns empty list (but should error!)
            assertThat(result.targets).isEmpty()

            // TODO: After implementing pre-init protection, this test should change to:
            // val exception = assertThrows<ExecutionException> {
            //     server.workspaceBuildTargets().get()
            // }
            // assertThat(exception.cause).isInstanceOf<ResponseErrorException>()
            // val error = (exception.cause as ResponseErrorException).responseError
            // assertThat(error.code).isEqualTo(-32002) // ServerNotInitialized
        }

        @Test
        fun `buildTargetSources before initialization returns empty results`() {
            // ISSUE: Same as above - should reject with -32002 error

            // Given: Server not initialized
            val params = SourcesParams(listOf(BuildTargetIdentifier("maven:com.example:my-app")))

            // When: Call buildTargetSources before initialization
            val result = server.buildTargetSources(params).get()

            // Then: CURRENT BEHAVIOR - Returns empty items (but should error!)
            assertThat(result.items).isEmpty()
        }

        @Test
        fun `buildTargetCompile before initialization succeeds with stub`() {
            // ISSUE: Stub implementation doesn't check initialization state

            // Given: Server not initialized
            val params = CompileParams(listOf(BuildTargetIdentifier("maven:com.example:my-app")))

            // When: Call buildTargetCompile before initialization
            val result = server.buildTargetCompile(params).get()

            // Then: CURRENT BEHAVIOR - Returns OK (stub doesn't check state)
            assertThat(result.statusCode).isEqualTo(ch.epfl.scala.bsp4j.StatusCode.OK)

            // TODO: Should reject with -32002 error
        }
    }

    @Nested
    inner class InitializationSequence {

        @Test
        fun `buildInitialize returns correct server capabilities`() {
            // Given
            val params = createInitParams()

            // When
            val result = server.buildInitialize(params).get()

            // Then
            assertThat(result.displayName).isEqualTo("Maven BSP")
            assertThat(result.version).isEqualTo("0.1.0")
            assertThat(result.bspVersion).isEqualTo("2.1.0")
            assertThat(result.capabilities).isNotNull
            assertThat(result.capabilities.compileProvider).isNotNull
            assertThat(result.capabilities.testProvider).isNotNull
            assertThat(result.capabilities.runProvider).isNotNull
            assertThat(result.capabilities.dependencyModulesProvider).isTrue()
            assertThat(result.capabilities.dependencySourcesProvider).isTrue()
            assertThat(result.capabilities.resourcesProvider).isTrue()
            assertThat(result.capabilities.outputPathsProvider).isTrue()
            assertThat(result.capabilities.jvmRunEnvironmentProvider).isTrue()
            assertThat(result.capabilities.jvmTestEnvironmentProvider).isTrue()
            assertThat(result.capabilities.canReload).isTrue()
        }

        @Test
        fun `buildInitialize scans workspace and finds modules`() {
            // Given
            val params = createInitParams()

            // When
            server.buildInitialize(params).get()
            val targets = server.workspaceBuildTargets().get()

            // Then: Should find the module (main + test targets)
            assertThat(targets.targets).hasSize(2)
            assertThat(targets.targets.map { it.id.uri }).containsExactlyInAnyOrder(
                "maven:com.example:my-app",
                "maven:com.example:my-app:test",
            )
        }

        @Test
        fun `onBuildInitialized notification completes without error`() {
            // Given
            server.buildInitialize(createInitParams()).get()

            // When/Then: Should not throw
            server.onBuildInitialized()
        }

        @Test
        fun `second initialization call succeeds but rescans workspace`() {
            // ISSUE: Current implementation allows re-initialization
            // BSP spec is unclear if this should be rejected or allowed

            // Given: Server already initialized
            server.buildInitialize(createInitParams()).get()

            // When: Call initialize again
            val result = server.buildInitialize(createInitParams()).get()

            // Then: Succeeds (rescans workspace)
            assertThat(result).isNotNull
            assertThat(result.displayName).isEqualTo("Maven BSP")

            // Alternative behavior: Could reject with error
            // The spec says "the client must not send any additional requests
            // or notifications to the server" until initialize completes,
            // but doesn't explicitly forbid re-initialization
        }
    }

    @Nested
    inner class PostInitializationBehavior {

        @BeforeEach
        fun initializeServer() {
            server.buildInitialize(createInitParams()).get()
            server.onBuildInitialized()
        }

        @Test
        fun `workspaceBuildTargets after initialization returns targets`() {
            // When
            val result = server.workspaceBuildTargets().get()

            // Then
            assertThat(result.targets).isNotEmpty
            assertThat(result.targets).hasSize(2)
        }

        @Test
        fun `buildTargetSources after initialization returns sources`() {
            // Given
            val params = SourcesParams(listOf(BuildTargetIdentifier("maven:com.example:my-app")))

            // When
            val result = server.buildTargetSources(params).get()

            // Then
            assertThat(result.items).isNotEmpty
        }

        @Test
        fun `workspaceReload rescans workspace`() {
            // Given: Initial targets
            val initialTargets = server.workspaceBuildTargets().get()
            assertThat(initialTargets.targets).hasSize(2)

            // When: Reload workspace
            server.workspaceReload().get()

            // Then: Should still have targets
            val reloadedTargets = server.workspaceBuildTargets().get()
            assertThat(reloadedTargets.targets).hasSize(2)
        }
    }

    @Nested
    inner class ShutdownSequence {

        @BeforeEach
        fun initializeServer() {
            server.buildInitialize(createInitParams()).get()
            server.onBuildInitialized()
        }

        @Test
        fun `buildShutdown completes successfully`() {
            // When
            val result = server.buildShutdown().get()

            // Then: Returns null (Any type)
            assertThat(result).isNull()
        }

        @Test
        fun `onBuildExit completes without error`() {
            // Given: Shutdown called first
            server.buildShutdown().get()

            // When/Then: Should not throw
            server.onBuildExit()
        }

        @Test
        fun `requests after shutdown still work`() {
            // ISSUE: Current implementation doesn't track shutdown state
            // After shutdown, requests should potentially be rejected

            // Given: Server shutdown
            server.buildShutdown().get()

            // When: Call workspaceBuildTargets after shutdown
            val result = server.workspaceBuildTargets().get()

            // Then: CURRENT BEHAVIOR - Still works
            assertThat(result.targets).isNotEmpty

            // TODO: Consider if this should be rejected after shutdown
            // BSP spec: "After shutdown, client sends exit to terminate"
            // But doesn't explicitly say requests should fail
        }
    }

    @Nested
    inner class CorrectLifecycleSequence {

        @Test
        fun `full lifecycle sequence executes correctly`() {
            // 1. Initialize
            val initResult = server.buildInitialize(createInitParams()).get()
            assertThat(initResult).isNotNull

            // 2. Initialized notification
            server.onBuildInitialized()

            // 3. Normal operations
            val targets = server.workspaceBuildTargets().get()
            assertThat(targets.targets).isNotEmpty

            val params = SourcesParams(listOf(BuildTargetIdentifier("maven:com.example:my-app")))
            val sources = server.buildTargetSources(params).get()
            assertThat(sources.items).isNotEmpty

            // 4. Reload
            server.workspaceReload().get()

            // 5. Shutdown
            val shutdownResult = server.buildShutdown().get()
            assertThat(shutdownResult).isNull()

            // 6. Exit
            server.onBuildExit()
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

    private fun createInitParams(): InitializeBuildParams = InitializeBuildParams(
        "Test Client",
        "1.0.0",
        "2.1.0",
        tempDir.toUri().toString(),
        BuildClientCapabilities(listOf("java")),
    )
}
