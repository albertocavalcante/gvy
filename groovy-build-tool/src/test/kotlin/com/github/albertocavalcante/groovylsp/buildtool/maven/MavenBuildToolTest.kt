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

        assertTrue(command.args.contains("jacoco:report"), "Command should include jacoco:report")
        assertTrue(command.args.contains("-Dtest=com.example.Test#testMethod"), "Command should target specific test")
    }

    @Test
    fun `should generate correct coverage command for full suite`() {
        val tool = MavenBuildTool()
        val project = Paths.get(".")

        // when test is null, it means full suite
        val command = tool.getCoverageCommand(project, "com.example.Test", null)

        assertTrue(command.args.contains("jacoco:report"), "Command should include jacoco:report")
        // Should contain -Dtest=com.example.Test (without method name)
        assertTrue(command.args.contains("-Dtest=com.example.Test"), "Command should target suite")
        // Should NOT contain '#'
        assertFalse(command.args.any { it.contains('#') }, "Command should not contain method separator")
    }
}
