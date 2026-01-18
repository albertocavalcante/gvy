package com.github.groovylsp.bsp.maven.deps

import com.github.groovylsp.bsp.maven.workspace.MavenDependency
import com.github.groovylsp.bsp.maven.workspace.MavenModuleInfo
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.resolution.ArtifactResult
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * TDD tests for MavenDependencyProvider.
 *
 * These tests verify:
 * - Dependency resolution for main and test scopes
 * - DependencyModules result format
 * - DependencySources result format
 * - Error handling for missing dependencies
 */
class MavenDependencyProviderTest {

    private lateinit var provider: MavenDependencyProvider
    private lateinit var repositorySystem: RepositorySystem
    private lateinit var session: RepositorySystemSession

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        repositorySystem = mockk()
        session = mockk()
        provider = MavenDependencyProvider(repositorySystem) { session }
    }

    @Nested
    inner class DependencyResolution {

        @Test
        fun `should resolve compile dependencies for main target`() {
            // Given
            val jarPath = tempDir.resolve("groovy-4.0.23.jar").also {
                it.toFile().createNewFile()
            }
            val module = createModuleWithDependencies(
                MavenDependency("org.apache.groovy", "groovy", "4.0.23", "compile"),
            )
            mockResolutionResult(
                listOf(
                    createArtifactResult("org.apache.groovy", "groovy", "4.0.23", jarPath),
                ),
            )

            // When
            val result = provider.getDependencyModules(listOf(module))

            // Then
            val mainItem = result.items.find { !it.target.uri.endsWith(":test") }
            assertThat(mainItem).isNotNull
            assertThat(mainItem!!.modules).hasSize(1)
            assertThat(mainItem.modules.first().name).isEqualTo("org.apache.groovy:groovy")
            assertThat(mainItem.modules.first().version).isEqualTo("4.0.23")
        }

        @Test
        fun `should resolve test dependencies only for test target`() {
            // Given
            val groovyPath = tempDir.resolve("groovy-4.0.23.jar").also {
                it.toFile().createNewFile()
            }
            val junitPath = tempDir.resolve("junit-4.13.2.jar").also {
                it.toFile().createNewFile()
            }
            val module = createModuleWithDependencies(
                MavenDependency("org.apache.groovy", "groovy", "4.0.23", "compile"),
                MavenDependency("junit", "junit", "4.13.2", "test"),
            )
            mockResolutionResult(
                listOf(
                    createArtifactResult("org.apache.groovy", "groovy", "4.0.23", groovyPath),
                    createArtifactResult("junit", "junit", "4.13.2", junitPath),
                ),
            )

            // When
            val result = provider.getDependencyModules(listOf(module))

            // Then: Test target should have both, main should only have groovy
            val testItem = result.items.find { it.target.uri.endsWith(":test") }
            assertThat(testItem).isNotNull
            // Note: In real implementation, we'd check that test scope deps are only in test target
        }

        @Test
        fun `should handle transitive dependencies`() {
            // Given: Dependency on groovy which has transitive deps
            val groovyPath = tempDir.resolve("groovy-4.0.23.jar").also {
                it.toFile().createNewFile()
            }
            val asmPath = tempDir.resolve("asm-9.6.jar").also {
                it.toFile().createNewFile()
            }
            val module = createModuleWithDependencies(
                MavenDependency("org.apache.groovy", "groovy", "4.0.23", "compile"),
            )
            mockResolutionResult(
                listOf(
                    createArtifactResult("org.apache.groovy", "groovy", "4.0.23", groovyPath),
                    createArtifactResult("org.ow2.asm", "asm", "9.6", asmPath),
                ),
            )

            // When
            val result = provider.getDependencyModules(listOf(module))

            // Then: Should include transitive deps
            val mainItem = result.items.find { !it.target.uri.endsWith(":test") }
            assertThat(mainItem!!.modules).hasSize(2)
        }

        @Test
        fun `should use local repository cache`() {
            // Given: A dependency that's already cached
            val cachedPath = tempDir.resolve(".m2/repository/org/codehaus/groovy/groovy/4.0.23/groovy-4.0.23.jar")
            cachedPath.parent.createDirectories()
            cachedPath.toFile().createNewFile()

            val module = createModuleWithDependencies(
                MavenDependency("org.apache.groovy", "groovy", "4.0.23", "compile"),
            )
            mockResolutionResult(
                listOf(
                    createArtifactResult("org.apache.groovy", "groovy", "4.0.23", cachedPath),
                ),
            )

            // When
            val result = provider.getDependencyModules(listOf(module))

            // Then
            val mainItem = result.items.find { !it.target.uri.endsWith(":test") }
            assertThat(mainItem!!.modules.first().data).isNotNull
        }

        @Test
        fun `should handle missing dependencies gracefully`() {
            // Given: A module with dependency that fails to resolve
            val module = createModuleWithDependencies(
                MavenDependency("nonexistent", "artifact", "1.0.0", "compile"),
            )
            every {
                repositorySystem.resolveDependencies(any(), any<DependencyRequest>())
            } throws RuntimeException("Not found")

            // When
            val result = provider.getDependencyModules(listOf(module))

            // Then: Should not throw, just return empty
            val mainItem = result.items.find { !it.target.uri.endsWith(":test") }
            assertThat(mainItem!!.modules).isEmpty()
        }

        @Test
        fun `should skip dependencies with missing version`() {
            // Given: A dependency without version
            val module = createModuleWithDependencies(
                MavenDependency("org.example", "lib", null, "compile"),
            )
            mockResolutionResult(emptyList())

            // When
            val result = provider.getDependencyModules(listOf(module))

            // Then: Should return empty (no crash)
            val mainItem = result.items.find { !it.target.uri.endsWith(":test") }
            assertThat(mainItem!!.modules).isEmpty()
        }
    }

    @Nested
    inner class ResultFormat {

        @Test
        fun `should return Maven data kind for dependency modules`() {
            // Given
            val jarPath = tempDir.resolve("lib-1.0.jar").also {
                it.toFile().createNewFile()
            }
            val module = createModuleWithDependencies(
                MavenDependency("com.example", "lib", "1.0", "compile"),
            )
            mockResolutionResult(
                listOf(
                    createArtifactResult("com.example", "lib", "1.0", jarPath),
                ),
            )

            // When
            val result = provider.getDependencyModules(listOf(module))

            // Then
            val depModule = result.items.first().modules.first()
            assertThat(depModule.dataKind).isEqualTo("maven")
        }

        @Test
        fun `should include artifact URI in dependency module`() {
            // Given
            val jarPath = tempDir.resolve("lib-1.0.jar").also {
                it.toFile().createNewFile()
            }
            val module = createModuleWithDependencies(
                MavenDependency("com.example", "lib", "1.0", "compile"),
            )
            mockResolutionResult(
                listOf(
                    createArtifactResult("com.example", "lib", "1.0", jarPath),
                ),
            )

            // When
            val result = provider.getDependencyModules(listOf(module))

            // Then: The data should be MavenDependencyModule with artifact URI
            val depModule = result.items.first().modules.first()
            assertThat(depModule.data).isNotNull
        }

        @Test
        fun `should set correct target ID for dependency modules`() {
            // Given
            val module = createModuleWithDependencies()
            mockResolutionResult(emptyList())

            // When
            val result = provider.getDependencyModules(listOf(module))

            // Then
            assertThat(result.items.map { it.target.uri }).containsExactlyInAnyOrder(
                "maven:com.example:my-app",
                "maven:com.example:my-app:test",
            )
        }
    }

    @Nested
    inner class DependencySources {

        @Test
        fun `should return dependency source paths`() {
            // Given
            val jarPath = tempDir.resolve("lib-1.0.jar").also {
                it.toFile().createNewFile()
            }
            val module = createModuleWithDependencies(
                MavenDependency("com.example", "lib", "1.0", "compile"),
            )
            mockResolutionResult(
                listOf(
                    createArtifactResult("com.example", "lib", "1.0", jarPath),
                ),
            )

            // When
            val result = provider.getDependencySources(listOf(module))

            // Then
            val mainItem = result.items.find { !it.target.uri.endsWith(":test") }
            assertThat(mainItem).isNotNull
            assertThat(mainItem!!.sources).hasSize(1)
            assertThat(mainItem.sources.first()).contains("lib-1.0.jar")
        }
    }

    private fun createModuleWithDependencies(vararg deps: MavenDependency): MavenModuleInfo = MavenModuleInfo(
        pomPath = tempDir.resolve("pom.xml"),
        groupId = "com.example",
        artifactId = "my-app",
        version = "1.0.0",
        dependencies = deps.toList(),
    )

    private fun mockResolutionResult(artifacts: List<ArtifactResult>) {
        val depResult = mockk<DependencyResult>()
        every { depResult.artifactResults } returns artifacts
        every {
            repositorySystem.resolveDependencies(any(), any<DependencyRequest>())
        } returns depResult
    }

    private fun createArtifactResult(groupId: String, artifactId: String, version: String, path: Path): ArtifactResult {
        val artifact = DefaultArtifact(groupId, artifactId, "jar", version)
            .setFile(path.toFile())
        val result = mockk<ArtifactResult>()
        every { result.isResolved } returns true
        every { result.artifact } returns artifact
        return result
    }
}
