package com.github.albertocavalcante.groovylsp.gradle

import kotlinx.coroutines.test.runTest
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

class GradleDependencyResolverTest {

    @Test
    fun `should resolve dependencies from gradle project using test resources`() = runTest {
        val resolver = GradleDependencyResolver()
        val testProjectPath = Paths.get("src/test/resources/test-gradle-project")

        // Use our test project which has known dependencies
        val resolution = resolver.resolve(testProjectPath)
        val dependencies = resolution.dependencies
        val sourceDirs = resolution.sourceDirectories

        println("Resolved ${dependencies.size} dependencies from test project:")
        dependencies.forEach { dep ->
            println("  - ${dep.fileName}")
        }
        println("Resolved ${sourceDirs.size} source directories:")
        sourceDirs.forEach { dir ->
            println("  - $dir")
        }

        // Should find at least some dependencies (groovy, commons-lang3)
        assertTrue(dependencies.isNotEmpty(), "Should resolve at least some dependencies from test project")

        val dependencyNames = dependencies.map { it.fileName.toString() }

        // Verify we find expected dependencies that our test project declares
        assertTrue(
            dependencyNames.any { it.contains("groovy") || it.contains("commons-lang3") },
            "Should find at least one of the declared dependencies (groovy or commons-lang3): $dependencyNames",
        )

        assertTrue(sourceDirs.any { it.toString().contains("src/main/groovy") }, "Should include main source directory")
    }

    @Test
    fun `should handle non-gradle project gracefully`() = runTest {
        val resolver = GradleDependencyResolver()
        val nonGradleProject = Paths.get("src/test/resources/non-gradle-project")

        val resolution = resolver.resolve(nonGradleProject)

        assertTrue(resolution.dependencies.isEmpty(), "Should return empty list for non-Gradle project")
        assertTrue(resolution.sourceDirectories.isEmpty(), "Should return no source directories for non-Gradle project")
    }

    @Test
    fun `should handle non-existent project gracefully`() = runTest {
        val resolver = GradleDependencyResolver()
        val nonExistentProject = Paths.get("non-existent-project")

        val resolution = resolver.resolve(nonExistentProject)

        assertTrue(resolution.dependencies.isEmpty(), "Should return empty list for non-existent project")
        assertTrue(
            resolution.sourceDirectories.isEmpty(),
            "Should return no source directories for non-existent project",
        )
    }
}
