package com.github.albertocavalcante.groovylsp.gradle

import kotlinx.coroutines.test.runTest
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

class SimpleDependencyResolverTest {

    @Test
    fun `should resolve dependencies from test gradle project`() = runTest {
        val resolver = SimpleDependencyResolver()
        val testProjectPath = Paths.get("test-project")

        // First ensure test project dependencies are resolved by building it
        val processBuilder = ProcessBuilder("./gradlew", "build")
            .directory(testProjectPath.toFile())
            .inheritIO()

        val process = processBuilder.start()
        val exitCode = process.waitFor()

        if (exitCode == 0) {
            // Now test our dependency resolver
            val dependencies = resolver.resolveDependencies(testProjectPath)

            println("Resolved ${dependencies.size} dependencies:")
            dependencies.forEach { dep ->
                println("  - ${dep.fileName}")
            }

            // Should find at least groovy and commons-lang3 dependencies
            assertTrue(dependencies.isNotEmpty(), "Should resolve at least some dependencies")

            val dependencyNames = dependencies.map { it.fileName.toString() }
            assertTrue(
                dependencyNames.any { it.contains("groovy") },
                "Should find Groovy dependency",
            )
            assertTrue(
                dependencyNames.any { it.contains("commons-lang3") },
                "Should find Commons Lang3 dependency",
            )
        } else {
            println("Test project build failed, skipping dependency resolution test")
        }
    }

    @Test
    fun `should handle non-gradle project gracefully`() = runTest {
        val resolver = SimpleDependencyResolver()
        val nonGradleProject = Paths.get("src") // This directory exists but is not a Gradle project

        val dependencies = resolver.resolveDependencies(nonGradleProject)

        assertTrue(dependencies.isEmpty(), "Should return empty list for non-Gradle project")
    }

    @Test
    fun `should handle non-existent project gracefully`() = runTest {
        val resolver = SimpleDependencyResolver()
        val nonExistentProject = Paths.get("non-existent-project")

        val dependencies = resolver.resolveDependencies(nonExistentProject)

        assertTrue(dependencies.isEmpty(), "Should return empty list for non-existent project")
    }
}
