package com.github.albertocavalcante.groovyjenkins

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for Jenkins configuration parsing.
 */
class JenkinsConfigurationTest {

    @Test
    fun `should parse default Jenkins configuration`() {
        val jenkinsConfig = JenkinsConfiguration.fromMap(emptyMap())

        assertNotNull(jenkinsConfig)
        assertEquals(listOf("Jenkinsfile"), jenkinsConfig.filePatterns)
        assertTrue(jenkinsConfig.sharedLibraries.isEmpty())
        assertTrue(jenkinsConfig.gdslPaths.isEmpty())
        assertFalse(jenkinsConfig.gdslExecutionEnabled)
    }

    @Test
    fun `should parse custom Jenkins file patterns`() {
        val configMap = mapOf(
            "jenkins.filePatterns" to listOf("Jenkinsfile", "*.jenkins", "pipelines/*.groovy"),
        )

        val jenkinsConfig = JenkinsConfiguration.fromMap(configMap)

        assertEquals(
            listOf("Jenkinsfile", "*.jenkins", "pipelines/*.groovy"),
            jenkinsConfig.filePatterns,
        )
    }

    @Test
    fun `should parse Jenkins shared libraries configuration`() {
        val configMap = mapOf(
            "jenkins.sharedLibraries" to listOf(
                mapOf(
                    "name" to "pipeline-library",
                    "jar" to "/path/to/pipeline-library.jar",
                    "sourcesJar" to "/path/to/pipeline-library-sources.jar",
                ),
                mapOf(
                    "name" to "utils",
                    "jar" to "/path/to/utils.jar",
                ),
            ),
        )

        val jenkinsConfig = JenkinsConfiguration.fromMap(configMap)

        assertEquals(2, jenkinsConfig.sharedLibraries.size)

        val lib1 = jenkinsConfig.sharedLibraries[0]
        assertEquals("pipeline-library", lib1.name)
        assertEquals("/path/to/pipeline-library.jar", lib1.jar)
        assertEquals("/path/to/pipeline-library-sources.jar", lib1.sourcesJar)

        val lib2 = jenkinsConfig.sharedLibraries[1]
        assertEquals("utils", lib2.name)
        assertEquals("/path/to/utils.jar", lib2.jar)
        assertEquals(null, lib2.sourcesJar)
    }

    @Test
    fun `should parse Jenkins GDSL paths configuration`() {
        val configMap = mapOf(
            "jenkins.gdslPaths" to listOf(
                "/path/to/pipeline.gdsl",
                "/path/to/custom-steps.gdsl",
            ),
        )

        val jenkinsConfig = JenkinsConfiguration.fromMap(configMap)

        assertEquals(
            listOf("/path/to/pipeline.gdsl", "/path/to/custom-steps.gdsl"),
            jenkinsConfig.gdslPaths,
        )
    }

    @Test
    fun `should parse Jenkins GDSL execution flag`() {
        val configMap = mapOf(
            "jenkins.gdslExecution.enabled" to true,
        )

        val jenkinsConfig = JenkinsConfiguration.fromMap(configMap)

        assertTrue(jenkinsConfig.gdslExecutionEnabled)
    }

    @Test
    fun `should handle complete Jenkins configuration`() {
        val configMap = mapOf(
            "jenkins.filePatterns" to listOf("Jenkinsfile", "*.jenkins"),
            "jenkins.sharedLibraries" to listOf(
                mapOf("name" to "lib1", "jar" to "/lib1.jar"),
            ),
            "jenkins.gdslPaths" to listOf("/pipeline.gdsl"),
        )

        val jenkinsConfig = JenkinsConfiguration.fromMap(configMap)

        assertEquals(listOf("Jenkinsfile", "*.jenkins"), jenkinsConfig.filePatterns)
        assertEquals(1, jenkinsConfig.sharedLibraries.size)
        assertEquals(listOf("/pipeline.gdsl"), jenkinsConfig.gdslPaths)
    }
}
