package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.groovylsp.buildtool.TestResources
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

class GradleBuildToolTest {

    @Test
    fun `should resolve dependencies from gradle project using test resources`() {
        val resolver = GradleBuildTool()
        val testProjectPath = TestResources.getTestGradleProject()

        // Use our test project which has known dependencies
        val resolution = resolver.resolve(testProjectPath, null)
        val dependencies = resolution.dependencies
        val sourceDirs = resolution.sourceDirectories

        // Should find at least some dependencies (groovy, commons-lang3)
        assertTrue(dependencies.isNotEmpty(), "Should resolve at least some dependencies from test project")

        val dependencyNames = dependencies.map { it.fileName.toString() }

        // Verify we find expected dependencies that our test project declares
        assertTrue(
            dependencyNames.any { it.contains("groovy") || it.contains("commons-lang3") },
            "Should find at least one of the declared dependencies (groovy or commons-lang3): $dependencyNames",
        )

        assertTrue(
            sourceDirs.any {
                it.endsWith(Paths.get("src", "main", "groovy")) || it.endsWith(
                    Paths.get(
                        "src",
                        "main",
                        "java",
                    ),
                )
            },
            "Should include main source directory (src/main/groovy or src/main/java). Found: $sourceDirs",
        )
    }

    @Test
    fun `should handle non-gradle project gracefully`() {
        val resolver = GradleBuildTool()
        val nonGradleProject = TestResources.getNonGradleProject()

        val resolution = resolver.resolve(nonGradleProject, null)

        assertTrue(resolution.dependencies.isEmpty(), "Should return empty list for non-Gradle project")
        assertTrue(resolution.sourceDirectories.isEmpty(), "Should return no source directories for non-Gradle project")
    }

    @Test
    fun `should handle non-existent project gracefully`() {
        val resolver = GradleBuildTool()
        val nonExistentProject = Paths.get("non-existent-project")

        val resolution = resolver.resolve(nonExistentProject, null)

        assertTrue(resolution.dependencies.isEmpty(), "Should return empty list for non-existent project")
        assertTrue(
            resolution.sourceDirectories.isEmpty(),
            "Should return no source directories for non-existent project",
        )
    }

    @Test
    fun `should generate correct coverage command`() {
        val tool = GradleBuildTool()
        val project = Paths.get(".")

        val command = tool.getCoverageCommand(project, "com.example.Test", "testMethod")

        assertTrue(command.args.contains("test"), "Command should include test task")
        assertTrue(command.args.contains("--tests"), "Command should include --tests flag")
        assertTrue(command.args.contains("com.example.Test.testMethod"), "Command should target specific test")
        assertTrue(command.args.contains("jacocoTestReport"), "Command should include jacocoTestReport task")
    }

    @Test
    fun `should generate correct coverage command for full suite`() {
        val tool = GradleBuildTool()
        val project = Paths.get(".")

        // when test is null, it means full suite
        val command = tool.getCoverageCommand(project, "com.example.Test", null)

        assertTrue(command.args.contains("test"), "Command should include test task")
        assertTrue(command.args.contains("--tests"), "Command should include --tests flag")
        assertTrue(command.args.contains("com.example.Test"), "Command should target suite")
        assertTrue(command.args.contains("jacocoTestReport"), "Command should include jacocoTestReport task")
    }

    @Test
    fun `getDependencyMetadata should return dependencies with metadata`() {
        val tool = GradleBuildTool()
        val testProjectPath = Paths.get("src/test/resources/test-gradle-project")

        val metadata = tool.getDependencyMetadata(testProjectPath)

        // Should have some dependencies
        assertTrue(metadata.isNotEmpty(), "Should extract at least some dependency metadata from test project")

        // Check that metadata has expected fields
        val firstDep = metadata.first()
        assertTrue(firstDep.name.isNotEmpty(), "Dependency should have a name")
        assertTrue(firstDep.version.isNotEmpty(), "Dependency should have a version")
        assertTrue(firstDep.scope.isNotEmpty(), "Dependency should have a scope")
        assertTrue(firstDep.path.startsWith("file://"), "Dependency path should be a file URI")

        // Verify scopes are normalized to standard values
        val scopes = metadata.map { it.scope }.distinct()
        scopes.forEach { scope ->
            assertTrue(
                scope in listOf("compile", "runtime", "test", "provided"),
                "Scope should be normalized to standard value, got: $scope",
            )
        }
    }

    @Test
    fun `getDependencyMetadata should handle non-gradle project`() {
        val tool = GradleBuildTool()
        val nonGradleProject = Paths.get("src/test/resources/non-gradle-project")

        val metadata = tool.getDependencyMetadata(nonGradleProject)

        assertTrue(metadata.isEmpty(), "Should return empty list for non-Gradle project")
    }

    @Test
    fun `getDependencyMetadata should deduplicate dependencies across modules`() {
        val tool = GradleBuildTool()
        val testProjectPath = Paths.get("src/test/resources/test-gradle-project")

        val metadata = tool.getDependencyMetadata(testProjectPath)

        // Check for duplicates by path
        val paths = metadata.map { it.path }
        val uniquePaths = paths.distinct()

        assertTrue(
            paths.size == uniquePaths.size,
            "Should not have duplicate dependencies. Found ${paths.size} deps but ${uniquePaths.size} unique paths",
        )
    }
}
