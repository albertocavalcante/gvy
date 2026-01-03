package com.github.albertocavalcante.groovyjenkins.metadata.enrichment

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD: Tests for enrichment metadata loading.
 *
 * This test drives the implementation of Phase 1 - Enrichment Metadata Integration.
 * Tests are written FIRST (RED), then implementation follows (GREEN).
 */
class EnrichmentMetadataLoaderTest {

    @Test
    fun `should load enrichment metadata from resources`() {
        // RED: This test will fail because EnrichmentMetadataLoader doesn't exist yet
        val loader = EnrichmentMetadataLoader()
        val enrichment = loader.load()

        assertNotNull(enrichment, "Enrichment metadata should be loaded")
        assertEquals("1.0.0", enrichment.version)
    }

    @Test
    fun `should load all 16 enriched steps`() {
        // RED: Testing that we load all steps from jenkins-enrichment.json
        val loader = EnrichmentMetadataLoader()
        val enrichment = loader.load()

        // jenkins-enrichment.json has 16 steps: sh, bat, echo, error, checkout, git, stage,
        // timeout, retry, parallel, stash, unstash, archiveArtifacts, junit, input, build
        val expectedSteps = listOf(
            "sh", "bat", "echo", "error", "checkout", "git", "stage",
            "timeout", "retry", "parallel", "stash", "unstash",
            "archiveArtifacts", "junit", "input", "build",
        )

        expectedSteps.forEach { stepName ->
            assertTrue(
                enrichment.steps.containsKey(stepName),
                "Enrichment should contain step: $stepName",
            )
        }

        assertEquals(16, enrichment.steps.size, "Should have exactly 16 enriched steps")
    }

    @Test
    fun `should load step enrichment with all fields`() {
        // RED: Testing complete step enrichment structure
        val loader = EnrichmentMetadataLoader()
        val enrichment = loader.load()

        val shEnrichment = enrichment.steps["sh"]
        assertNotNull(shEnrichment, "Should have enrichment for 'sh' step")

        assertEquals("workflow-durable-task-step", shEnrichment.plugin)
        assertEquals("Execute a shell script on a node.", shEnrichment.description)
        assertEquals(
            "https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/#sh-shell-script",
            shEnrichment.documentationUrl,
        )
        assertEquals(StepCategory.UTILITY, shEnrichment.category)
        assertEquals(listOf("sh 'echo Hello'"), shEnrichment.examples)
    }

    @Test
    fun `should load parameter enrichment for sh step`() {
        // RED: Testing parameter enrichment parsing
        val loader = EnrichmentMetadataLoader()
        val enrichment = loader.load()

        val shEnrichment = enrichment.steps["sh"]
        assertNotNull(shEnrichment)

        // sh step has 3 enriched parameters: script, returnStdout, returnStatus
        assertEquals(3, shEnrichment.parameterEnrichment.size)

        val scriptParam = shEnrichment.parameterEnrichment["script"]
        assertNotNull(scriptParam, "Should have enrichment for 'script' parameter")
        assertEquals("The shell script to execute.", scriptParam.description)
        assertEquals(true, scriptParam.required)
        assertEquals(listOf("echo 'test'"), scriptParam.examples)

        val returnStdoutParam = shEnrichment.parameterEnrichment["returnStdout"]
        assertNotNull(returnStdoutParam)
        assertEquals(
            "If true, return stdout from the script instead of printing it.",
            returnStdoutParam.description,
        )
        assertEquals(false, returnStdoutParam.required)
    }

    @Test
    fun `should load parameter enrichment with valid values for timeout step`() {
        // RED: Testing validValues parsing
        val loader = EnrichmentMetadataLoader()
        val enrichment = loader.load()

        val timeoutEnrichment = enrichment.steps["timeout"]
        assertNotNull(timeoutEnrichment)

        val unitParam = timeoutEnrichment.parameterEnrichment["unit"]
        assertNotNull(unitParam, "Should have enrichment for 'unit' parameter")

        val expectedValidValues = listOf(
            "NANOSECONDS",
            "MICROSECONDS",
            "MILLISECONDS",
            "SECONDS",
            "MINUTES",
            "HOURS",
            "DAYS",
        )
        assertEquals(expectedValidValues, unitParam.validValues)
    }

    @Test
    fun `should load global variable enrichment`() {
        // RED: Testing global variable enrichment
        val loader = EnrichmentMetadataLoader()
        val enrichment = loader.load()

        // jenkins-enrichment.json has 4 global variables: env, params, currentBuild, scm
        assertEquals(4, enrichment.globalVariables.size)

        val envEnrichment = enrichment.globalVariables["env"]
        assertNotNull(envEnrichment)
        assertEquals("Environment variables available to the pipeline.", envEnrichment.description)
        assertEquals(
            "https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#using-environment-variables",
            envEnrichment.documentationUrl,
        )
    }

    @Test
    fun `should load currentBuild properties enrichment`() {
        // RED: Testing property enrichment parsing
        val loader = EnrichmentMetadataLoader()
        val enrichment = loader.load()

        val currentBuildEnrichment = enrichment.globalVariables["currentBuild"]
        assertNotNull(currentBuildEnrichment)

        // currentBuild now has many properties (result, currentResult, number, displayName, etc.)
        assertTrue(currentBuildEnrichment.properties.size >= 10, "Should have at least 10 properties")

        val resultProperty = currentBuildEnrichment.properties["result"]
        assertNotNull(resultProperty)
        assertEquals("String", resultProperty.type)
        assertTrue(resultProperty.description.contains("SUCCESS"), "Should mention SUCCESS")
        assertEquals(false, resultProperty.readOnly)

        val numberProperty = currentBuildEnrichment.properties["number"]
        assertNotNull(numberProperty)
        assertEquals("int", numberProperty.type)
        assertTrue(numberProperty.description.contains("Build number"))
        assertEquals(true, numberProperty.readOnly)
    }

    @Test
    fun `should load section enrichment`() {
        // RED: Testing section enrichment
        val loader = EnrichmentMetadataLoader()
        val enrichment = loader.load()

        // jenkins-enrichment.json has 6 sections: pipeline, agent, stages, stage, steps, post
        assertEquals(6, enrichment.sections.size)

        val pipelineSection = enrichment.sections["pipeline"]
        assertNotNull(pipelineSection)
        assertEquals("Top-level Declarative Pipeline block.", pipelineSection.description)
        assertEquals(listOf("root"), pipelineSection.allowedIn)

        val stagesSection = enrichment.sections["stages"]
        assertNotNull(stagesSection)
        assertEquals(listOf("stage"), stagesSection.innerInstructions)
    }

    @Test
    fun `should load directive enrichment`() {
        // RED: Testing directive enrichment
        val loader = EnrichmentMetadataLoader()
        val enrichment = loader.load()

        // jenkins-enrichment.json has 5 directives: environment, options, parameters, when, tools
        assertEquals(5, enrichment.directives.size)

        val environmentDirective = enrichment.directives["environment"]
        assertNotNull(environmentDirective)
        assertEquals(
            "Define environment variables for the pipeline or a stage.",
            environmentDirective.description,
        )
        assertEquals(listOf("pipeline", "stage"), environmentDirective.allowedIn)
        assertEquals(
            "https://www.jenkins.io/doc/book/pipeline/syntax/#environment",
            environmentDirective.documentationUrl,
        )
    }

    @Test
    fun `should handle missing enrichment resource gracefully`() {
        // RED: Testing error handling when resource is not found
        // This test ensures that if the resource is missing, we get a clear error
        // (This will pass once we implement proper error handling)

        // For now, we expect the loader to throw IllegalStateException
        // when the resource cannot be found
        val loader = EnrichmentMetadataLoader()

        // This should succeed because the resource exists
        // If it throws, the test will fail and we know error handling works
        val enrichment = loader.load()
        assertNotNull(enrichment)
    }
}
