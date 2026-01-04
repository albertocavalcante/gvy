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
    fun `should load jenkins version from metadata`() {
        val loader = BundledJenkinsMetadataLoader()
        val metadata = loader.load()

        // Metadata might not have version yet (null), or might have it if updated.
        // For now we just check that the property exists and doesn't crash.
        // Once we update the JSON, we can assert a specific version.
        val version = metadata.jenkinsVersion
        assertTrue(version == null || version.isNotBlank())
    }

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
        assertEquals("workflow-basic-steps", echo.plugin)
        assertTrue(echo.parameters.containsKey("message"))

        val git = metadata.getStep("git")
        assertNotNull(git)
        assertEquals("git", git.plugin)
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

    // ========== NEW TESTS FOR EXTENDED METADATA ==========

    @Test
    fun `should load post condition metadata`() {
        // TDD: Test for post { always { } } support
        val loader = BundledJenkinsMetadataLoader()
        val metadata = loader.load()

        // Verify we have metadata for 'always' post condition
        val always = metadata.getPostCondition("always")
        assertNotNull(always, "Should have metadata for 'always' post condition")
        assertEquals("always", always.name)
        assertNotNull(always.description)
    }

    @Test
    fun `should load all standard post conditions`() {
        val metadata = BundledJenkinsMetadataLoader().load()

        val expectedConditions = listOf(
            "always", "success", "failure", "unstable", "aborted",
            "changed", "fixed", "regression", "unsuccessful", "cleanup",
        )

        expectedConditions.forEach { condition ->
            val meta = metadata.getPostCondition(condition)
            assertNotNull(meta, "Should have metadata for '$condition' post condition")
        }
    }

    @Test
    fun `should load declarative options metadata`() {
        // TDD: Test for options { disableConcurrentBuilds() } support
        val loader = BundledJenkinsMetadataLoader()
        val metadata = loader.load()

        val disableConcurrent = metadata.getDeclarativeOption("disableConcurrentBuilds")
        assertNotNull(disableConcurrent, "Should have metadata for 'disableConcurrentBuilds' option")
        assertEquals("disableConcurrentBuilds", disableConcurrent.name)
    }

    @Test
    fun `should load declarative option parameters`() {
        // TDD: Test for disableConcurrentBuilds(abortPrevious: true)
        val metadata = BundledJenkinsMetadataLoader().load()

        val disableConcurrent = metadata.getDeclarativeOption("disableConcurrentBuilds")
        assertNotNull(disableConcurrent)

        // Should have 'abortPrevious' parameter
        val abortPreviousParam = disableConcurrent.parameters["abortPrevious"]
        assertNotNull(abortPreviousParam, "Should have 'abortPrevious' parameter")
        assertEquals("boolean", abortPreviousParam.type)
        assertEquals(false, abortPreviousParam.required)
    }

    @Test
    fun `should load timestamps option`() {
        val metadata = BundledJenkinsMetadataLoader().load()

        val timestamps = metadata.getDeclarativeOption("timestamps")
        assertNotNull(timestamps, "Should have metadata for 'timestamps' option")
        assertEquals("timestamper", timestamps.plugin)
    }

    @Test
    fun `should load agent type metadata`() {
        // TDD: Test for agent { label 'maven-21' } support
        val metadata = BundledJenkinsMetadataLoader().load()

        val labelAgent = metadata.getAgentType("label")
        assertNotNull(labelAgent, "Should have metadata for 'label' agent type")

        // 'label' agent should have a 'label' parameter
        val labelParam = labelAgent.parameters["label"]
        assertNotNull(labelParam, "Label agent should have 'label' parameter")
        assertEquals(true, labelParam.required)
    }

    @Test
    fun `should load all standard agent types`() {
        val metadata = BundledJenkinsMetadataLoader().load()

        val expectedAgents = listOf("any", "none", "label", "docker")

        expectedAgents.forEach { agentType ->
            val meta = metadata.getAgentType(agentType)
            assertNotNull(meta, "Should have metadata for '$agentType' agent type")
        }
    }
}
