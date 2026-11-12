// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

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

    @Test
    fun `should return false for untitled URI scheme without crashing`() {
        val detector = JenkinsFileDetector()

        // VSCode sends untitled: URIs for unsaved documents
        assertFalse(detector.isJenkinsFile(URI.create("untitled:Untitled-1")))
        assertFalse(detector.isJenkinsFile(URI.create("untitled:Untitled-42")))
    }

    @Test
    fun `should return false for vscode-notebook URI scheme without crashing`() {
        val detector = JenkinsFileDetector()

        assertFalse(detector.isJenkinsFile(URI.create("vscode-notebook-cell://path/notebook.ipynb")))
    }

    @Test
    fun `should return false for http URI scheme without crashing`() {
        val detector = JenkinsFileDetector()

        assertFalse(detector.isJenkinsFile(URI.create("http://example.com/Jenkinsfile")))
        assertFalse(detector.isJenkinsFile(URI.create("https://example.com/Jenkinsfile")))
    }

    @Test
    fun `default patterns match every file the editor treats as a Jenkinsfile`() {
        val detector = JenkinsFileDetector()

        listOf(
            "file:///workspace/Jenkinsfile.prod",
            "file:///workspace/ci/Jenkinsfile-release",
            "file:///workspace/Jenkinsfile_dev",
            "file:///workspace/Jenkinsfile.groovy",
            "file:///workspace/deploy.jenkinsfile",
            "file:///workspace/release.Jenkinsfile",
            "file:///workspace/vars/buildApp.groovy",
        ).forEach { uri ->
            assertTrue(detector.isJenkinsFile(URI.create(uri)), "expected Jenkins file: $uri")
        }
    }

    @Test
    fun `default patterns do not claim shared library classes or other groovy files`() {
        val detector = JenkinsFileDetector()

        listOf(
            "file:///workspace/src/org/example/BuildHelper.groovy",
            "file:///workspace/resources/org/example/script.groovy",
            "file:///workspace/vars/nested/helper.groovy",
            "file:///workspace/src/org/example/JenkinsfileParser.groovy",
            "file:///workspace/test/JenkinsfileTest.groovy",
            "file:///workspace/build.gradle",
        ).forEach { uri ->
            assertFalse(detector.isJenkinsFile(URI.create(uri)), "expected plain Groovy: $uri")
        }
    }
}
