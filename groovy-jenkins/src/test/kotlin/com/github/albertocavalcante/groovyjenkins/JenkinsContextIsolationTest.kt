package com.github.albertocavalcante.groovyjenkins

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Jenkins context isolation from regular Groovy sources.
 */
class JenkinsContextIsolationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should identify Jenkinsfile as Jenkins context`() {
        val jenkinsUri = URI.create("file://$tempDir/Jenkinsfile")

        val config = JenkinsConfiguration(
            filePatterns = listOf("Jenkinsfile"),
        )

        val manager = JenkinsWorkspaceManager(config, tempDir)

        assertTrue(manager.isJenkinsFile(jenkinsUri))
    }

    @Test
    fun `should not identify regular Groovy file as Jenkins context`() {
        val groovyUri = URI.create("file://$tempDir/Script.groovy")

        val config = JenkinsConfiguration(
            filePatterns = listOf("Jenkinsfile"),
        )

        val manager = JenkinsWorkspaceManager(config, tempDir)

        assertFalse(manager.isJenkinsFile(groovyUri))
    }

    @Test
    fun `should build separate classpath for Jenkins files`() {
        val lib1 = tempDir.resolve("jenkins-lib.jar")
        Files.createFile(lib1)

        val config = JenkinsConfiguration(
            filePatterns = listOf("Jenkinsfile"),
            sharedLibraries = listOf(
                SharedLibrary("jenkins-lib", lib1.toString()),
            ),
        )

        val jenkinsfile = """
            @Library('jenkins-lib')
            import com.example.Pipeline
            
            node {
                echo 'hello'
            }
        """.trimIndent()

        val manager = JenkinsWorkspaceManager(config, tempDir)
        val jenkinsUri = URI.create("file://$tempDir/Jenkinsfile")

        val classpath = manager.getClasspathForFile(jenkinsUri, jenkinsfile)

        // Should include Jenkins library
        assertTrue(classpath.isNotEmpty())
        assertTrue(classpath.any { it.toString().contains("jenkins-lib.jar") })
    }

    @Test
    fun `should return empty classpath for non-Jenkins files`() {
        val config = JenkinsConfiguration(
            filePatterns = listOf("Jenkinsfile"),
        )

        val manager = JenkinsWorkspaceManager(config, tempDir)
        val groovyUri = URI.create("file://$tempDir/Script.groovy")

        val classpath = manager.getClasspathForFile(groovyUri, "println 'hello'")

        // Should return empty for non-Jenkins files
        assertTrue(classpath.isEmpty())
    }
}
