package com.github.albertocavalcante.groovyjenkins.metadata

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for StableStepDefinitions - hardcoded core Jenkins steps that rarely change.
 *
 * These tests define the expected behavior BEFORE implementation (TDD RED phase).
 */
class StableStepDefinitionsTest {

    @Test
    fun `sh step has correct parameters from stable definitions`() {
        val metadata = StableStepDefinitions.getStep("sh")

        assertNotNull(metadata)
        assertEquals("sh", metadata.name)
        assertEquals("workflow-durable-task-step", metadata.plugin)
        assertTrue(metadata.parameters.containsKey("script"))
        assertTrue(metadata.parameters["script"]!!.required)
        assertEquals("String", metadata.parameters["script"]!!.type)

        // Optional parameters
        assertTrue(metadata.parameters.containsKey("returnStdout"))
        assertFalse(metadata.parameters["returnStdout"]!!.required)
        assertEquals("boolean", metadata.parameters["returnStdout"]!!.type)
        assertEquals("false", metadata.parameters["returnStdout"]!!.default)

        assertTrue(metadata.parameters.containsKey("returnStatus"))
        assertFalse(metadata.parameters["returnStatus"]!!.required)

        assertTrue(metadata.parameters.containsKey("encoding"))
        assertFalse(metadata.parameters["encoding"]!!.required)

        assertTrue(metadata.parameters.containsKey("label"))
        assertFalse(metadata.parameters["label"]!!.required)
    }

    @Test
    fun `bat step has correct parameters`() {
        val metadata = StableStepDefinitions.getStep("bat")

        assertNotNull(metadata)
        assertEquals("bat", metadata.name)
        assertEquals("workflow-durable-task-step", metadata.plugin)
        assertTrue(metadata.parameters.containsKey("script"))
        assertTrue(metadata.parameters["script"]!!.required)
    }

    @Test
    fun `powershell step has correct parameters`() {
        val metadata = StableStepDefinitions.getStep("powershell")

        assertNotNull(metadata)
        assertEquals("powershell", metadata.name)
        assertEquals("workflow-durable-task-step", metadata.plugin)
        assertTrue(metadata.parameters.containsKey("script"))
    }

    @Test
    fun `echo step has message parameter`() {
        val metadata = StableStepDefinitions.getStep("echo")

        assertNotNull(metadata)
        assertEquals("echo", metadata.name)
        assertEquals("workflow-basic-steps", metadata.plugin)
        assertTrue(metadata.parameters.containsKey("message"))
        assertTrue(metadata.parameters["message"]!!.required)
        assertEquals("String", metadata.parameters["message"]!!.type)
    }

    @Test
    fun `error step has message parameter`() {
        val metadata = StableStepDefinitions.getStep("error")

        assertNotNull(metadata)
        assertEquals("error", metadata.name)
        assertTrue(metadata.parameters.containsKey("message"))
        assertTrue(metadata.parameters["message"]!!.required)
    }

    @Test
    fun `timeout step has time and unit parameters`() {
        val metadata = StableStepDefinitions.getStep("timeout")

        assertNotNull(metadata)
        assertEquals("timeout", metadata.name)
        assertEquals("workflow-basic-steps", metadata.plugin)
        assertTrue(metadata.parameters.containsKey("time"))
        assertTrue(metadata.parameters["time"]!!.required)
        assertEquals("int", metadata.parameters["time"]!!.type)

        assertTrue(metadata.parameters.containsKey("unit"))
        assertFalse(metadata.parameters["unit"]!!.required)
        assertEquals("MINUTES", metadata.parameters["unit"]!!.default)

        assertTrue(metadata.parameters.containsKey("activity"))
        assertFalse(metadata.parameters["activity"]!!.required)
    }

    @Test
    fun `retry step has count parameter`() {
        val metadata = StableStepDefinitions.getStep("retry")

        assertNotNull(metadata)
        assertEquals("retry", metadata.name)
        assertTrue(metadata.parameters.containsKey("count"))
        assertTrue(metadata.parameters["count"]!!.required)
        assertEquals("int", metadata.parameters["count"]!!.type)
    }

    @Test
    fun `sleep step has time and unit parameters`() {
        val metadata = StableStepDefinitions.getStep("sleep")

        assertNotNull(metadata)
        assertEquals("sleep", metadata.name)
        assertTrue(metadata.parameters.containsKey("time"))
        assertTrue(metadata.parameters["time"]!!.required)

        assertTrue(metadata.parameters.containsKey("unit"))
        assertFalse(metadata.parameters["unit"]!!.required)
        assertEquals("SECONDS", metadata.parameters["unit"]!!.default)
    }

    @Test
    fun `waitUntil step has correct parameters`() {
        val metadata = StableStepDefinitions.getStep("waitUntil")

        assertNotNull(metadata)
        assertEquals("waitUntil", metadata.name)
        assertEquals("workflow-basic-steps", metadata.plugin)

        assertTrue(metadata.parameters.containsKey("initialRecurrencePeriod"))
        assertFalse(metadata.parameters["initialRecurrencePeriod"]!!.required)
        assertEquals("250", metadata.parameters["initialRecurrencePeriod"]!!.default)
        assertEquals("long", metadata.parameters["initialRecurrencePeriod"]!!.type)

        assertTrue(metadata.parameters.containsKey("quiet"))
        assertFalse(metadata.parameters["quiet"]!!.required)
        assertEquals("false", metadata.parameters["quiet"]!!.default)
    }

    @Test
    fun `dir step has path parameter`() {
        val metadata = StableStepDefinitions.getStep("dir")

        assertNotNull(metadata)
        assertEquals("dir", metadata.name)
        assertTrue(metadata.parameters.containsKey("path"))
        assertTrue(metadata.parameters["path"]!!.required)
    }

    @Test
    fun `deleteDir step exists with no required parameters`() {
        val metadata = StableStepDefinitions.getStep("deleteDir")

        assertNotNull(metadata)
        assertEquals("deleteDir", metadata.name)
        // deleteDir has no required parameters
        assertTrue(metadata.parameters.isEmpty() || metadata.parameters.values.none { it.required })
    }

    @Test
    fun `pwd step exists`() {
        val metadata = StableStepDefinitions.getStep("pwd")

        assertNotNull(metadata)
        assertEquals("pwd", metadata.name)
        assertTrue(metadata.parameters.containsKey("tmp"))
        assertFalse(metadata.parameters["tmp"]!!.required)
    }

    @Test
    fun `writeFile step has file and text parameters`() {
        val metadata = StableStepDefinitions.getStep("writeFile")

        assertNotNull(metadata)
        assertEquals("writeFile", metadata.name)
        assertTrue(metadata.parameters.containsKey("file"))
        assertTrue(metadata.parameters["file"]!!.required)
        assertTrue(metadata.parameters.containsKey("text"))
        assertTrue(metadata.parameters["text"]!!.required)
        assertTrue(metadata.parameters.containsKey("encoding"))
        assertFalse(metadata.parameters["encoding"]!!.required)
    }

    @Test
    fun `readFile step has file parameter`() {
        val metadata = StableStepDefinitions.getStep("readFile")

        assertNotNull(metadata)
        assertEquals("readFile", metadata.name)
        assertTrue(metadata.parameters.containsKey("file"))
        assertTrue(metadata.parameters["file"]!!.required)
    }

    @Test
    fun `fileExists step has file parameter`() {
        val metadata = StableStepDefinitions.getStep("fileExists")

        assertNotNull(metadata)
        assertEquals("fileExists", metadata.name)
        assertTrue(metadata.parameters.containsKey("file"))
        assertTrue(metadata.parameters["file"]!!.required)
    }

    @Test
    fun `isUnix step exists with no parameters`() {
        val metadata = StableStepDefinitions.getStep("isUnix")

        assertNotNull(metadata)
        assertEquals("isUnix", metadata.name)
        assertTrue(metadata.parameters.isEmpty())
    }

    @Test
    fun `node step has label parameter`() {
        val metadata = StableStepDefinitions.getStep("node")

        assertNotNull(metadata)
        assertEquals("node", metadata.name)
        assertTrue(metadata.parameters.containsKey("label"))
        assertFalse(metadata.parameters["label"]!!.required) // label is optional
    }

    @Test
    fun `stage step has name parameter`() {
        val metadata = StableStepDefinitions.getStep("stage")

        assertNotNull(metadata)
        assertEquals("stage", metadata.name)
        assertEquals("pipeline-stage-step", metadata.plugin)
        assertTrue(metadata.parameters.containsKey("name"))
        assertTrue(metadata.parameters["name"]!!.required)
    }

    @Test
    fun `withEnv step has overrides parameter`() {
        val metadata = StableStepDefinitions.getStep("withEnv")

        assertNotNull(metadata)
        assertEquals("withEnv", metadata.name)
        assertTrue(metadata.parameters.containsKey("overrides"))
        assertTrue(metadata.parameters["overrides"]!!.required)
    }

    @Test
    fun `catchError step has correct parameters`() {
        val metadata = StableStepDefinitions.getStep("catchError")

        assertNotNull(metadata)
        assertEquals("catchError", metadata.name)
        assertTrue(metadata.parameters.containsKey("buildResult"))
        assertFalse(metadata.parameters["buildResult"]!!.required)
        assertTrue(metadata.parameters.containsKey("stageResult"))
        assertFalse(metadata.parameters["stageResult"]!!.required)
    }

    @Test
    fun `archiveArtifacts step has artifacts parameter`() {
        val metadata = StableStepDefinitions.getStep("archiveArtifacts")

        assertNotNull(metadata)
        assertEquals("archiveArtifacts", metadata.name)
        assertTrue(metadata.parameters.containsKey("artifacts"))
        assertTrue(metadata.parameters["artifacts"]!!.required)
        assertTrue(metadata.parameters.containsKey("allowEmptyArchive"))
        assertFalse(metadata.parameters["allowEmptyArchive"]!!.required)
    }

    @Test
    fun `stash step has name parameter`() {
        val metadata = StableStepDefinitions.getStep("stash")

        assertNotNull(metadata)
        assertEquals("stash", metadata.name)
        assertTrue(metadata.parameters.containsKey("name"))
        assertTrue(metadata.parameters["name"]!!.required)
        assertTrue(metadata.parameters.containsKey("includes"))
        assertFalse(metadata.parameters["includes"]!!.required)
    }

    @Test
    fun `unstash step has name parameter`() {
        val metadata = StableStepDefinitions.getStep("unstash")

        assertNotNull(metadata)
        assertEquals("unstash", metadata.name)
        assertTrue(metadata.parameters.containsKey("name"))
        assertTrue(metadata.parameters["name"]!!.required)
    }

    @Test
    fun `input step has message parameter`() {
        val metadata = StableStepDefinitions.getStep("input")

        assertNotNull(metadata)
        assertEquals("input", metadata.name)
        assertEquals("pipeline-input-step", metadata.plugin)
        assertTrue(metadata.parameters.containsKey("message"))
        assertTrue(metadata.parameters["message"]!!.required)
        assertTrue(metadata.parameters.containsKey("ok"))
        assertFalse(metadata.parameters["ok"]!!.required)
    }

    @Test
    fun `all returns all stable step definitions`() {
        val allSteps = StableStepDefinitions.all()

        assertTrue(allSteps.isNotEmpty())
        assertTrue(allSteps.size >= 20) // At least 20 stable steps

        // Verify some key steps are present
        assertTrue(allSteps.containsKey("sh"))
        assertTrue(allSteps.containsKey("echo"))
        assertTrue(allSteps.containsKey("bat"))
        assertTrue(allSteps.containsKey("node"))
        assertTrue(allSteps.containsKey("stage"))
        assertTrue(allSteps.containsKey("timeout"))
    }

    @Test
    fun `contains returns true for stable steps`() {
        assertTrue(StableStepDefinitions.contains("sh"))
        assertTrue(StableStepDefinitions.contains("echo"))
        assertTrue(StableStepDefinitions.contains("bat"))
    }

    @Test
    fun `contains returns false for non-stable steps`() {
        assertFalse(StableStepDefinitions.contains("nonExistentStep"))
        assertFalse(StableStepDefinitions.contains("someThirdPartyStep"))
    }

    @Test
    fun `all steps have documentation`() {
        val allSteps = StableStepDefinitions.all()

        allSteps.values.forEach { step ->
            assertNotNull(step.documentation, "Step ${step.name} should have documentation")
            assertTrue(step.documentation.isNotBlank(), "Step ${step.name} documentation should not be blank")
        }
    }

    @Test
    fun `all steps have plugin attribution`() {
        val allSteps = StableStepDefinitions.all()

        allSteps.values.forEach { step ->
            assertTrue(step.plugin.isNotBlank(), "Step ${step.name} should have plugin attribution")
        }
    }
}
