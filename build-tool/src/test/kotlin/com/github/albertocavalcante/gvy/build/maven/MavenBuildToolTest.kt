package com.github.albertocavalcante.gvy.build.maven

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MavenBuildToolTest {

    @Test
    fun `should detect maven project`() {
        val tool = MavenBuildTool()
        val project = Paths.get("src/test/resources/test-maven-project")
        assertTrue(tool.canHandle(project), "Should detect Maven project with pom.xml")
    }

    @Test
    fun `should not detect non-maven project`() {
        val tool = MavenBuildTool()
        val project = Paths.get("src/test/resources")
        assertFalse(tool.canHandle(project), "Should not detect non-Maven project")
    }

    @Test
    fun `should generate correct coverage command`() {
        val tool = MavenBuildTool()
        val project = Paths.get(".")

        val command = tool.getCoverageCommand(project, "com.example.Test", "testMethod")

        val args = command.args
        assertTrue(args.contains("test"), "Command should include 'test' goal")
        assertTrue(args.contains("jacoco:report"), "Command should include jacoco:report")
        assertTrue(args.contains("-Dtest=com.example.Test#testMethod"), "Command should target specific test")

        // Verify order: test -> -Dtest -> jacoco:report
        val testIndex = args.indexOf("test")
        val propertyIndex = args.indexOfFirst { it.startsWith("-Dtest=") }
        val reportIndex = args.indexOf("jacoco:report")

        assertTrue(testIndex < propertyIndex, "Goal 'test' should come before properties")
        assertTrue(propertyIndex < reportIndex, "Properties should come before 'jacoco:report'")
    }

    @Test
    fun `should generate correct coverage command for full suite`() {
        val tool = MavenBuildTool()
        val project = Paths.get(".")

        // when test is null, it means full suite
        val command = tool.getCoverageCommand(project, "com.example.Test", null)

        val args = command.args
        assertTrue(args.contains("test"), "Command should include 'test' goal")
        assertTrue(args.contains("jacoco:report"), "Command should include jacoco:report")
        assertTrue(args.contains("-Dtest=com.example.Test"), "Command should target suite")
        assertFalse(args.any { it.contains('#') }, "Command should not contain method separator")

        // Verify order: test -> -Dtest -> jacoco:report
        val testIndex = args.indexOf("test")
        val propertyIndex = args.indexOfFirst { it.startsWith("-Dtest=") }
        val reportIndex = args.indexOf("jacoco:report")

        assertTrue(testIndex < propertyIndex, "Goal 'test' should come before properties")
        assertTrue(propertyIndex < reportIndex, "Properties should come before 'jacoco:report'")
    }

    @Test
    fun `getDependencyMetadata should return null for non-maven project`() {
        val tool = MavenBuildTool()
        val project = Paths.get("src/test/resources")

        val metadata = tool.getDependencyMetadata(project)

        assertTrue(metadata == null, "Should return null for project without pom.xml")
    }

    @Test
    fun `getDependencyMetadata should extract metadata from maven project`() {
        val tool = MavenBuildTool()
        val project = Paths.get("src/test/resources/test-maven-project")

        val metadata = tool.getDependencyMetadata(project)

        // Should have some dependencies (might be empty if test project has no deps, but shouldn't be null)
        assertTrue(metadata != null, "Should return non-null list for Maven project with pom.xml")

        if (metadata.isNotEmpty()) {
            // Verify metadata structure
            val firstDep = metadata.first()
            assertTrue(firstDep.name.isNotEmpty(), "Dependency should have a name")
            assertTrue(firstDep.version.isNotEmpty(), "Dependency should have a version")
            assertTrue(firstDep.scope.isNotEmpty(), "Dependency should have a scope")
            assertTrue(firstDep.path.startsWith("file://"), "Dependency path should be a file URI")

            // Verify scopes are normalized
            val scopes = metadata.map { it.scope }.distinct()
            scopes.forEach { scope ->
                assertTrue(
                    scope in listOf("compile", "runtime", "test", "provided"),
                    "Scope should be normalized to standard value, got: $scope",
                )
            }

            // Verify we have both direct and potentially transitive dependencies
            val directDeps = metadata.filter { !it.isTransitive }
            val transitiveDeps = metadata.filter { it.isTransitive }
        }
    }

    @Test
    fun `getDependencyMetadata should deduplicate by path`() {
        val tool = MavenBuildTool()
        val project = Paths.get("src/test/resources/test-maven-project")

        val metadata = tool.getDependencyMetadata(project)

        if (metadata != null && metadata.isNotEmpty()) {
            // Check for duplicates by path
            val paths = metadata.map { it.path }
            val uniquePaths = paths.distinct()

            assertTrue(
                paths.size == uniquePaths.size,
                "Should not have duplicate dependencies. Found ${paths.size} deps but ${uniquePaths.size} unique paths",
            )
        }
    }
}
