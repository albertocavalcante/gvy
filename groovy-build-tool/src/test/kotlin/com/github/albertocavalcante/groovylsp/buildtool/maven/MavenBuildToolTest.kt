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
}
