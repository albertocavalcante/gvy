package com.github.albertocavalcante.groovylsp.buildtool.maven

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
}
