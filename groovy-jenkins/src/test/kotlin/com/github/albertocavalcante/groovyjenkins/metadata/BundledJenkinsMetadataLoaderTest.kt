package com.github.albertocavalcante.groovyjenkins.metadata

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD: Tests for bundled Jenkins metadata loading.
 *
 * This test drives the implementation of Phase 0 - Bundled Jenkins SDK stubs.
 */
class BundledJenkinsMetadataLoaderTest {

    @Test
    fun `should load bundled Jenkins metadata from resources`() {
        // RED: This test will fail because BundledJenkinsMetadataLoader doesn't exist yet
        val loader = BundledJenkinsMetadataLoader()
        val metadata = loader.load()

        assertNotNull(metadata, "Bundled metadata should be loaded")
    }

    @Test
    fun `should load metadata for common Jenkins steps`() {
        // RED: Testing that we can query step metadata
        val loader = BundledJenkinsMetadataLoader()
        val metadata = loader.load()

        // Verify we have metadata for 'sh' step (one of the most common)
        val shStep = metadata.getStep("sh")
        assertNotNull(shStep, "Should have metadata for 'sh' step")
        assertEquals("sh", shStep.name)
    }

    @Test
    fun `should provide step parameter metadata`() {
        // RED: Testing Map key inference - getting valid parameter names
        val loader = BundledJenkinsMetadataLoader()
        val metadata = loader.load()

        val shStep = metadata.getStep("sh")
        assertNotNull(shStep)

        // 'sh' step should have these parameters based on jenkins-stubs-metadata.json
        val parameters = shStep.parameters
        assertTrue(parameters.containsKey("script"), "sh step should have 'script' parameter")
        assertTrue(parameters.containsKey("returnStdout"), "sh step should have 'returnStdout' parameter")
        assertTrue(parameters.containsKey("returnStatus"), "sh step should have 'returnStatus' parameter")
    }

    @Test
    fun `should indicate required vs optional parameters`() {
        // RED: Testing parameter metadata details
        val loader = BundledJenkinsMetadataLoader()
        val metadata = loader.load()

        val shStep = metadata.getStep("sh")!!
        val scriptParam = shStep.parameters["script"]

        assertNotNull(scriptParam, "script parameter should exist")
        assertTrue(scriptParam.required, "script parameter should be required")
        assertEquals("String", scriptParam.type)
    }

    @Test
    fun `should load metadata for echo and git steps`() {
        val metadata = BundledJenkinsMetadataLoader().load()

        val echo = metadata.getStep("echo")
        assertNotNull(echo)
        assertEquals("workflow-basic-steps:1000.v2f5c09b_74cf6", echo.plugin)
        assertTrue(echo.parameters.containsKey("message"))

        val git = metadata.getStep("git")
        assertNotNull(git)
        assertEquals("git:5.2.1", git.plugin)
        assertTrue(git.parameters.keys.containsAll(listOf("url", "branch", "credentialsId")))
    }

    @Test
    fun `should load global variable metadata`() {
        // RED: Testing global variables like 'env', 'params', 'currentBuild'
        val loader = BundledJenkinsMetadataLoader()
        val metadata = loader.load()

        val envGlobal = metadata.getGlobalVariable("env")
        assertNotNull(envGlobal, "Should have metadata for 'env' global variable")
        assertEquals("env", envGlobal.name)
    }

    @Test
    fun `should handle missing step gracefully`() {
        // RED: Testing error handling
        val loader = BundledJenkinsMetadataLoader()
        val metadata = loader.load()

        val missingStep = metadata.getStep("nonExistentStep")
        assertEquals(null, missingStep, "Should return null for missing steps")
    }
}
