package com.github.albertocavalcante.groovyjenkins

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Jenkins file detection based on patterns.
 */
class JenkinsFileDetectionTest {

    @Test
    fun `should detect Jenkinsfile by default pattern`() {
        val detector = JenkinsFileDetector()

        assertTrue(detector.isJenkinsFile(URI.create("file:///workspace/Jenkinsfile")))
        assertTrue(detector.isJenkinsFile(URI.create("file:///workspace/folder/Jenkinsfile")))
    }

    @Test
    fun `should not detect regular groovy files as Jenkins files`() {
        val detector = JenkinsFileDetector()

        assertFalse(detector.isJenkinsFile(URI.create("file:///workspace/Script.groovy")))
        assertFalse(detector.isJenkinsFile(URI.create("file:///workspace/build.gradle")))
    }

    @Test
    fun `should detect custom patterns when configured`() {
        val detector = JenkinsFileDetector(
            patterns = listOf("Jenkinsfile", "*.jenkins", "pipelines/*.groovy"),
        )

        assertTrue(detector.isJenkinsFile(URI.create("file:///workspace/Jenkinsfile")))
        assertTrue(detector.isJenkinsFile(URI.create("file:///workspace/build.jenkins")))
        assertTrue(detector.isJenkinsFile(URI.create("file:///workspace/pipelines/deploy.groovy")))
    }

    @Test
    fun `should not match files outside configured patterns`() {
        val detector = JenkinsFileDetector(
            patterns = listOf("Jenkinsfile", "*.jenkins"),
        )

        assertTrue(detector.isJenkinsFile(URI.create("file:///workspace/Jenkinsfile")))
        assertTrue(detector.isJenkinsFile(URI.create("file:///workspace/test.jenkins")))
        assertFalse(detector.isJenkinsFile(URI.create("file:///workspace/deploy.groovy")))
    }

    @Test
    fun `should handle empty patterns list`() {
        val detector = JenkinsFileDetector(patterns = emptyList())

        assertFalse(detector.isJenkinsFile(URI.create("file:///workspace/Jenkinsfile")))
        assertFalse(detector.isJenkinsFile(URI.create("file:///workspace/test.groovy")))
    }
}
