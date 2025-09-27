package com.github.albertocavalcante.groovylsp.gradle

import kotlinx.coroutines.test.runTest
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

class SimpleDependencyResolverTest {

    @Test
    fun `should resolve dependencies from gradle project using test resources`() = runTest {
        val resolver = SimpleDependencyResolver()
        val testProjectPath = Paths.get("src/test/resources/test-gradle-project")

        // Use our test project which has known dependencies
        val dependencies = resolver.resolveDependencies(testProjectPath)

        println("Resolved ${dependencies.size} dependencies from test project:")
        dependencies.forEach { dep ->
            println("  - ${dep.fileName}")
        }

        // Should find at least some dependencies (groovy, commons-lang3)
        assertTrue(dependencies.isNotEmpty(), "Should resolve at least some dependencies from test project")

        val dependencyNames = dependencies.map { it.fileName.toString() }

        // Verify we find expected dependencies that our test project declares
        assertTrue(
            dependencyNames.any { it.contains("groovy") || it.contains("commons-lang3") },
            "Should find at least one of the declared dependencies (groovy or commons-lang3): $dependencyNames",
        )
    }

    @Test
    fun `should handle non-gradle project gracefully`() = runTest {
        val resolver = SimpleDependencyResolver()
        val nonGradleProject = Paths.get("src/test/resources/non-gradle-project")

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
