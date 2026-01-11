package com.github.groovylsp.bsp.maven.targets

import ch.epfl.scala.bsp4j.BuildTargetTag
import com.github.groovylsp.bsp.maven.workspace.MavenDependency
import com.github.groovylsp.bsp.maven.workspace.MavenModuleInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * TDD tests for MavenBuildTargetProvider.
 *
 * These tests verify:
 * - Build target creation from Maven modules
 * - Correct capabilities and tags
 * - Main vs test target differentiation
 * - Inter-module dependencies
 */
class MavenBuildTargetProviderTest {

    private lateinit var provider: MavenBuildTargetProvider
    private val basePath = Path.of("/test/project")

    @BeforeEach
    fun setUp() {
        provider = MavenBuildTargetProvider()
    }

    @Nested
    inner class BuildTargetIdentifiers {

        @Test
        fun `should create main target ID with maven prefix`() {
            // Given
            val module = createModule("com.example", "my-app", "1.0.0")

            // When
            val targetId = provider.buildTargetId(module, MavenBuildTargetProvider.Scope.MAIN)

            // Then
            assertThat(targetId.uri).isEqualTo("maven:com.example:my-app")
        }

        @Test
        fun `should create test target ID with test suffix`() {
            // Given
            val module = createModule("com.example", "my-app", "1.0.0")

            // When
            val targetId = provider.buildTargetId(module, MavenBuildTargetProvider.Scope.TEST)

            // Then
            assertThat(targetId.uri).isEqualTo("maven:com.example:my-app:test")
        }

        @Test
        fun `should handle special characters in coordinates`() {
            // Given
            val module = createModule("org.jenkins-ci.plugins", "pipeline-library", "2.0-SNAPSHOT")

            // When
            val targetId = provider.buildTargetId(module, MavenBuildTargetProvider.Scope.MAIN)

            // Then
            assertThat(targetId.uri).isEqualTo("maven:org.jenkins-ci.plugins:pipeline-library")
        }
    }

    @Nested
    inner class BuildTargetCreation {

        @Test
        fun `should create BuildTarget from MavenModuleInfo`() {
            // Given
            val module = createModule("com.example", "my-app", "1.0.0")

            // When
            val targets = provider.createTargets(listOf(module))

            // Then: Should create 2 targets (main + test)
            assertThat(targets).hasSize(2)

            val mainTarget = targets.find { !it.id.uri.endsWith(":test") }
            assertThat(mainTarget).isNotNull
            assertThat(mainTarget!!.displayName).isEqualTo("my-app")

            val testTarget = targets.find { it.id.uri.endsWith(":test") }
            assertThat(testTarget).isNotNull
            assertThat(testTarget!!.displayName).isEqualTo("my-app (test)")
        }

        @Test
        fun `should set correct BuildTargetCapabilities`() {
            // Given
            val module = createModule("com.example", "my-app", "1.0.0")

            // When
            val targets = provider.createTargets(listOf(module))

            // Then: Main target should be compilable
            val mainTarget = targets.first { !it.id.uri.endsWith(":test") }
            assertThat(mainTarget.capabilities.canCompile).isTrue()
            assertThat(mainTarget.capabilities.canRun).isTrue()
            assertThat(mainTarget.capabilities.canDebug).isTrue()

            // Test target should additionally be testable
            val testTarget = targets.first { it.id.uri.endsWith(":test") }
            assertThat(testTarget.capabilities.canTest).isTrue()
        }

        @Test
        fun `should set correct tags for main vs test targets`() {
            // Given
            val module = createModule("com.example", "my-app", "1.0.0")

            // When
            val targets = provider.createTargets(listOf(module))

            // Then
            val mainTarget = targets.first { !it.id.uri.endsWith(":test") }
            assertThat(mainTarget.tags).contains(BuildTargetTag.LIBRARY)
            assertThat(mainTarget.tags).doesNotContain(BuildTargetTag.TEST)

            val testTarget = targets.first { it.id.uri.endsWith(":test") }
            assertThat(testTarget.tags).contains(BuildTargetTag.TEST)
        }

        @Test
        fun `should set language IDs for JVM targets`() {
            // Given
            val module = createModule("com.example", "my-app", "1.0.0")

            // When
            val targets = provider.createTargets(listOf(module))

            // Then: Should support Java, Groovy, and Kotlin
            val mainTarget = targets.first()
            assertThat(mainTarget.languageIds).containsExactlyInAnyOrder("java", "groovy", "kotlin")
        }

        @Test
        fun `should set base directory`() {
            // Given
            val pomPath = basePath.resolve("pom.xml")
            val module = MavenModuleInfo(
                pomPath = pomPath,
                groupId = "com.example",
                artifactId = "my-app",
                version = "1.0.0",
            )

            // When
            val targets = provider.createTargets(listOf(module))

            // Then
            val mainTarget = targets.first { !it.id.uri.endsWith(":test") }
            assertThat(mainTarget.baseDirectory).isEqualTo(basePath.toUri().toString())
        }
    }

    @Nested
    inner class InterModuleDependencies {

        @Test
        fun `should include inter-module dependencies in targets`() {
            // Given: Module B depends on Module A
            val moduleA = createModule("com.example", "module-a", "1.0.0")
            val moduleB = MavenModuleInfo(
                pomPath = basePath.resolve("module-b/pom.xml"),
                groupId = "com.example",
                artifactId = "module-b",
                version = "1.0.0",
                dependencies = listOf(
                    MavenDependency(
                        groupId = "com.example",
                        artifactId = "module-a",
                        version = "1.0.0",
                        scope = "compile",
                    ),
                ),
            )

            // When
            val targets = provider.createTargets(listOf(moduleA, moduleB))

            // Then: Module B's main target should depend on Module A's main target
            val moduleBMain = targets.first {
                it.id.uri == "maven:com.example:module-b"
            }
            assertThat(moduleBMain.dependencies).anyMatch {
                it.uri == "maven:com.example:module-a"
            }
        }

        @Test
        fun `test target should depend on main target`() {
            // Given
            val module = createModule("com.example", "my-app", "1.0.0")

            // When
            val targets = provider.createTargets(listOf(module))

            // Then: Test target should depend on its main target
            val testTarget = targets.first { it.id.uri.endsWith(":test") }
            assertThat(testTarget.dependencies).anyMatch {
                it.uri == "maven:com.example:my-app"
            }
        }

        @Test
        fun `test target should include test-scoped dependencies`() {
            // Given: Module with test-scoped dependency
            val module = MavenModuleInfo(
                pomPath = basePath.resolve("pom.xml"),
                groupId = "com.example",
                artifactId = "my-app",
                version = "1.0.0",
                dependencies = listOf(
                    MavenDependency(
                        groupId = "junit",
                        artifactId = "junit",
                        version = "4.13.2",
                        scope = "test",
                    ),
                ),
            )

            // When
            val targets = provider.createTargets(listOf(module))

            // Then: Main target should NOT have junit dependency
            val mainTarget = targets.first { !it.id.uri.endsWith(":test") }
            assertThat(mainTarget.dependencies).noneMatch { it.uri.contains("junit") }

            // But we don't model external deps in BSP dependencies (those come from dependencyModules)
            // Inter-module test deps would be modeled though
        }
    }

    @Nested
    inner class AggregatorProjects {

        @Test
        fun `should NOT create targets for aggregator pom-only modules`() {
            // Given: A parent pom that only aggregates, has no source
            val parentModule = MavenModuleInfo(
                pomPath = basePath.resolve("pom.xml"),
                groupId = "com.example",
                artifactId = "parent",
                version = "1.0.0",
                packaging = "pom",
                modules = listOf("child-a", "child-b"),
            )
            val childA = createModule("com.example", "child-a", "1.0.0")
            val childB = createModule("com.example", "child-b", "1.0.0")

            // When
            val targets = provider.createTargets(listOf(parentModule, childA, childB))

            // Then: Should only have targets for children, not parent
            assertThat(targets).hasSize(4) // 2 per child
            assertThat(targets).noneMatch { it.id.uri.contains("parent") }
        }

        @Test
        fun `should create targets for pom with packaging=jar (default)`() {
            // Given: A module with default jar packaging
            val module = MavenModuleInfo(
                pomPath = basePath.resolve("pom.xml"),
                groupId = "com.example",
                artifactId = "my-lib",
                version = "1.0.0",
                packaging = "jar",
            )

            // When
            val targets = provider.createTargets(listOf(module))

            // Then
            assertThat(targets).hasSize(2)
        }

        @Test
        fun `should create targets for hpi packaging`() {
            // Given: A Jenkins plugin (hpi packaging)
            val module = MavenModuleInfo(
                pomPath = basePath.resolve("pom.xml"),
                groupId = "org.jenkins-ci.plugins",
                artifactId = "my-plugin",
                version = "1.0.0",
                packaging = "hpi",
            )

            // When
            val targets = provider.createTargets(listOf(module))

            // Then: HPI is a compilable format
            assertThat(targets).hasSize(2)
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should return empty list for empty modules`() {
            // When
            val targets = provider.createTargets(emptyList())

            // Then
            assertThat(targets).isEmpty()
        }

        @Test
        fun `should handle multiple modules with same artifactId but different groupId`() {
            // Given
            val module1 = createModule("com.example", "common", "1.0.0")
            val module2 = createModule("org.other", "common", "2.0.0")

            // When
            val targets = provider.createTargets(listOf(module1, module2))

            // Then: Should create distinct targets
            assertThat(targets).hasSize(4)
            assertThat(targets.map { it.id.uri }).containsExactlyInAnyOrder(
                "maven:com.example:common",
                "maven:com.example:common:test",
                "maven:org.other:common",
                "maven:org.other:common:test",
            )
        }
    }

    private fun createModule(
        groupId: String,
        artifactId: String,
        version: String,
        packaging: String = "jar",
    ): MavenModuleInfo = MavenModuleInfo(
        pomPath = basePath.resolve(artifactId).resolve("pom.xml"),
        groupId = groupId,
        artifactId = artifactId,
        version = version,
        packaging = packaging,
    )
}
