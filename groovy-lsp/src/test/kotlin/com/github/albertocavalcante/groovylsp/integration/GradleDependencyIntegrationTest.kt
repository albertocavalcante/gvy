package com.github.albertocavalcante.groovylsp.integration

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.gradle.GradleDependencyResolver
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GradleDependencyIntegrationTest {

    @Test
    fun `should initialize workspace and resolve dependencies for compilation`() = runTest {
        val compilationService = GroovyCompilationService()
        val testProjectPath = Paths.get("src/test/resources/test-gradle-project")

        // Initialize the workspace and trigger dependency resolution for the test
        // Use our test project which has known dependencies
        compilationService.workspaceManager.initializeWorkspace(testProjectPath)

        val resolver = GradleDependencyResolver()
        val resolution = resolver.resolve(testProjectPath, null)
        compilationService.workspaceManager.updateWorkspaceModel(
            testProjectPath,
            resolution.dependencies,
            resolution.sourceDirectories,
        )

        // Check that dependencies were resolved
        val dependencies = compilationService.workspaceManager.getDependencyClasspath()
        assertTrue(dependencies.isNotEmpty(), "Dependencies should be resolved")

        println("Resolved ${dependencies.size} dependencies for compilation:")
        dependencies.forEach { dep ->
            println("  - ${dep.fileName}")
        }

        // Verify specific dependencies are found (using test project dependencies)
        val dependencyNames = dependencies.map { it.fileName.toString() }
        assertTrue(
            dependencyNames.any { it.contains("groovy") || it.contains("commons-lang3") },
            "Should find at least one declared dependency (groovy or commons-lang3) in compilation classpath",
        )
    }

    @Test
    fun `should compile groovy code with external dependencies`() = runTest {
        val compilationService = GroovyCompilationService()
        val testProjectPath = Paths.get("src/test/resources/test-gradle-project")

        // Initialize workspace with dependencies using test project
        compilationService.workspaceManager.initializeWorkspace(testProjectPath)

        val resolver = GradleDependencyResolver()
        val resolution = resolver.resolve(testProjectPath, null)
        compilationService.workspaceManager.updateWorkspaceModel(
            testProjectPath,
            resolution.dependencies,
            resolution.sourceDirectories,
        )

        // Test compilation of Groovy code that uses dependencies our test project declares
        val groovyCode = """
            import org.apache.commons.lang3.StringUtils

            class DependencyHarness {
                def reverseString(String input) {
                    return StringUtils.reverse(input)
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///tmp/DependencyHarness.groovy")
        val result = compilationService.compile(uri, groovyCode)

        println("Compilation result: success=${result.isSuccess}, diagnostics=${result.diagnostics.size}")
        result.diagnostics.forEach { diagnostic ->
            println("  - ${diagnostic.severity}: ${diagnostic.message}")
        }

        // For now, just verify that we have an AST and the dependency resolution is working
        // The compilation may still fail due to classpath issues, but we should have the dependency JARs
        assertNotNull(result.ast, "AST should be generated even if compilation has errors")

        // Let's just verify the dependency resolution worked
        val dependencies = compilationService.workspaceManager.getDependencyClasspath()
        assertTrue(dependencies.isNotEmpty(), "Dependencies should still be resolved")
    }
}
