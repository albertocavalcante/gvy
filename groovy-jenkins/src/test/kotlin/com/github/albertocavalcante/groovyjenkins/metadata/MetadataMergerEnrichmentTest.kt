package com.github.albertocavalcante.groovyjenkins.metadata

import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.DirectiveEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.GlobalVariableEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.JenkinsEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.ParameterEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.PropertyEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.SectionEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.StepCategory
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.StepEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.StepScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * TDD: Tests for merging bundled metadata with enrichment metadata.
 *
 * This test drives the implementation of Phase 1, Day 2 - MetadataMerger.mergeWithEnrichment().
 * Tests are written FIRST (RED), then implementation follows (GREEN).
 */
class MetadataMergerEnrichmentTest {

    @Test
    fun `should merge bundled metadata with enrichment metadata`() {
        // RED: This test will fail because mergeWithEnrichment doesn't exist yet
        val bundled = createBundledMetadata()
        val enrichment = createEnrichment()

        val merged = MetadataMerger.mergeWithEnrichment(bundled, enrichment)

        assertNotNull(merged, "Merged metadata should not be null")
        assertEquals("2.426.3", merged.jenkinsVersion)
    }

    @Test
    fun `should create MergedStepMetadata with enrichment overlay`() {
        // RED: Testing that step metadata combines extracted + enrichment data
        val bundled = createBundledMetadata()
        val enrichment = createEnrichment()

        val merged = MetadataMerger.mergeWithEnrichment(bundled, enrichment)

        val shStep = merged.getStep("sh")
        assertNotNull(shStep, "Should have merged 'sh' step")

        // From extracted metadata
        assertEquals("sh", shStep.name)
        // NOTE: BundledJenkinsMetadata doesn't include scope/positionalParams,
        // so these are populated with defaults until we integrate ExtractedMetadata
        assertEquals(StepScope.GLOBAL, shStep.scope)
        assertEquals(emptyList(), shStep.positionalParams)
        assertEquals("Executes a shell script.", shStep.extractedDocumentation)

        // From enrichment metadata
        assertEquals("workflow-durable-task-step", shStep.plugin)
        assertEquals("Execute a shell script on a node.", shStep.enrichedDescription)
        assertEquals(
            "https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/#sh-shell-script",
            shStep.documentationUrl,
        )
        assertEquals(StepCategory.UTILITY, shStep.category)
        assertEquals(listOf("sh 'echo Hello'"), shStep.examples)
        assertNull(shStep.deprecation)

        // Best available documentation should be enriched description
        assertEquals("Execute a shell script on a node.", shStep.documentation)
    }

    @Test
    fun `should merge step parameters with enrichment`() {
        // RED: Testing parameter merging
        val bundled = createBundledMetadata()
        val enrichment = createEnrichment()

        val merged = MetadataMerger.mergeWithEnrichment(bundled, enrichment)

        val shStep = merged.getStep("sh")
        assertNotNull(shStep)

        // Should have 3 parameters: script, returnStdout, returnStatus
        assertEquals(3, shStep.namedParams.size)

        val scriptParam = shStep.namedParams["script"]
        assertNotNull(scriptParam, "Should have 'script' parameter")

        // From extracted metadata
        assertEquals("script", scriptParam.name)
        assertEquals("String", scriptParam.type)
        assertNull(scriptParam.defaultValue)

        // From enrichment metadata
        assertEquals("The shell script to execute.", scriptParam.description)
        assertEquals(true, scriptParam.required)
        assertEquals(listOf("echo 'test'"), scriptParam.examples)
        assertNull(scriptParam.validValues)

        val returnStdoutParam = shStep.namedParams["returnStdout"]
        assertNotNull(returnStdoutParam)
        assertEquals(false, returnStdoutParam.required)
    }

    @Test
    fun `should handle missing enrichment gracefully`() {
        // RED: Testing that steps without enrichment still work
        val bundled = BundledJenkinsMetadata(
            steps = mapOf(
                "customStep" to JenkinsStepMetadata(
                    name = "customStep",
                    plugin = "custom-plugin",
                    parameters = mapOf(
                        "param1" to StepParameter(
                            name = "param1",
                            type = "String",
                            required = false,
                            default = null,
                            documentation = "A parameter",
                        ),
                    ),
                    documentation = "A custom step",
                ),
            ),
            globalVariables = emptyMap(),
            postConditions = emptyMap(),
            declarativeOptions = emptyMap(),
            agentTypes = emptyMap(),
        )

        val enrichment = JenkinsEnrichment(
            steps = emptyMap(),
            globalVariables = emptyMap(),
            sections = emptyMap(),
            directives = emptyMap(),
        )

        val merged = MetadataMerger.mergeWithEnrichment(bundled, enrichment)

        val customStep = merged.getStep("customStep")
        assertNotNull(customStep)

        // Should have extracted data
        assertEquals("customStep", customStep.name)
        assertEquals("A custom step", customStep.extractedDocumentation)

        // Should have null enrichment fields
        assertNull(customStep.enrichedDescription)
        assertNull(customStep.documentationUrl)
        assertNull(customStep.category)
        assertEquals(emptyList(), customStep.examples)

        // Documentation should fall back to extracted
        assertEquals("A custom step", customStep.documentation)

        // Plugin should fall back to bundled plugin (not null)
        assertEquals("custom-plugin", customStep.plugin)

        // Parameters should still work
        val param1 = customStep.namedParams["param1"]
        assertNotNull(param1)
        assertEquals("String", param1.type)
        // Description should fall back to bundled documentation
        assertEquals("A parameter", param1.description)
        assertEquals(false, param1.required)
    }

    @Test
    fun `should merge global variables with enrichment`() {
        // RED: Testing global variable merging
        val bundled = createBundledMetadata()
        val enrichment = createEnrichment()

        val merged = MetadataMerger.mergeWithEnrichment(bundled, enrichment)

        val envVar = merged.getGlobalVariable("env")
        assertNotNull(envVar)

        // From extracted metadata
        assertEquals("env", envVar.name)
        assertEquals("Map", envVar.type)
        assertEquals("Environment variables", envVar.extractedDocumentation)

        // From enrichment metadata
        assertEquals("Environment variables available to the pipeline.", envVar.enrichedDescription)
        assertEquals(
            "https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#using-environment-variables",
            envVar.documentationUrl,
        )

        // Best available documentation should be enriched
        assertEquals("Environment variables available to the pipeline.", envVar.documentation)
    }

    @Test
    fun `should merge global variable properties enrichment`() {
        // RED: Testing property enrichment for global variables
        val bundled = createBundledMetadata()
        val enrichment = createEnrichment()

        val merged = MetadataMerger.mergeWithEnrichment(bundled, enrichment)

        val currentBuildVar = merged.getGlobalVariable("currentBuild")
        assertNotNull(currentBuildVar)

        // Should have properties from enrichment
        assertEquals(2, currentBuildVar.properties.size)

        val resultProperty = currentBuildVar.properties["result"]
        assertNotNull(resultProperty)
        assertEquals("String", resultProperty.type)
        assertEquals("Build result.", resultProperty.description)
        assertEquals(false, resultProperty.readOnly)

        val numberProperty = currentBuildVar.properties["number"]
        assertNotNull(numberProperty)
        assertEquals("int", numberProperty.type)
        assertEquals("Build number.", numberProperty.description)
        assertEquals(true, numberProperty.readOnly)
    }

    @Test
    fun `should include sections from enrichment`() {
        // RED: Testing section enrichment inclusion
        val bundled = createBundledMetadata()
        val enrichment = createEnrichment()

        val merged = MetadataMerger.mergeWithEnrichment(bundled, enrichment)

        assertEquals(2, merged.sections.size)

        val pipelineSection = merged.sections["pipeline"]
        assertNotNull(pipelineSection)
        assertEquals("Top-level Declarative Pipeline block.", pipelineSection.description)

        val stagesSection = merged.sections["stages"]
        assertNotNull(stagesSection)
        assertEquals(listOf("stage"), stagesSection.innerInstructions)
    }

    @Test
    fun `should include directives from enrichment`() {
        // RED: Testing directive enrichment inclusion
        val bundled = createBundledMetadata()
        val enrichment = createEnrichment()

        val merged = MetadataMerger.mergeWithEnrichment(bundled, enrichment)

        assertEquals(1, merged.directives.size)

        val environmentDirective = merged.directives["environment"]
        assertNotNull(environmentDirective)
        assertEquals(
            "Define environment variables for the pipeline or a stage.",
            environmentDirective.description,
        )
    }

    @Test
    fun `should handle parameter enrichment with valid values`() {
        // RED: Testing validValues from enrichment
        val bundled = BundledJenkinsMetadata(
            steps = mapOf(
                "timeout" to JenkinsStepMetadata(
                    name = "timeout",
                    plugin = "workflow-basic-steps",
                    parameters = mapOf(
                        "time" to StepParameter(
                            name = "time",
                            type = "int",
                            required = true,
                            default = null,
                            documentation = null,
                        ),
                        "unit" to StepParameter(
                            name = "unit",
                            type = "String",
                            required = false,
                            default = "MINUTES",
                            documentation = null,
                        ),
                    ),
                    documentation = "Enforce time limit",
                ),
            ),
            globalVariables = emptyMap(),
            postConditions = emptyMap(),
            declarativeOptions = emptyMap(),
            agentTypes = emptyMap(),
        )

        val enrichment = JenkinsEnrichment(
            steps = mapOf(
                "timeout" to StepEnrichment(
                    plugin = "workflow-basic-steps",
                    parameterEnrichment = mapOf(
                        "unit" to ParameterEnrichment(
                            description = "Time unit.",
                            validValues = listOf("SECONDS", "MINUTES", "HOURS"),
                        ),
                    ),
                ),
            ),
            globalVariables = emptyMap(),
            sections = emptyMap(),
            directives = emptyMap(),
        )

        val merged = MetadataMerger.mergeWithEnrichment(bundled, enrichment)

        val timeoutStep = merged.getStep("timeout")
        assertNotNull(timeoutStep)

        val unitParam = timeoutStep.namedParams["unit"]
        assertNotNull(unitParam)
        assertEquals("Time unit.", unitParam.description)
        assertEquals(listOf("SECONDS", "MINUTES", "HOURS"), unitParam.validValues)
    }

    // === Helper functions to create test data ===

    private fun createBundledMetadata(): BundledJenkinsMetadata = BundledJenkinsMetadata(
        steps = mapOf(
            "sh" to JenkinsStepMetadata(
                name = "sh",
                plugin = "workflow-durable-task-step",
                parameters = mapOf(
                    "script" to StepParameter(
                        name = "script",
                        type = "String",
                        required = true,
                        default = null,
                        documentation = null,
                    ),
                    "returnStdout" to StepParameter(
                        name = "returnStdout",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = null,
                    ),
                    "returnStatus" to StepParameter(
                        name = "returnStatus",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = null,
                    ),
                ),
                documentation = "Executes a shell script.",
            ),
        ),
        globalVariables = mapOf(
            "env" to GlobalVariableMetadata(
                name = "env",
                type = "Map",
                documentation = "Environment variables",
            ),
            "currentBuild" to GlobalVariableMetadata(
                name = "currentBuild",
                type = "RunWrapper",
                documentation = "Current build info",
            ),
        ),
        postConditions = emptyMap(),
        declarativeOptions = emptyMap(),
        agentTypes = emptyMap(),
    )

    @Suppress("LongMethod")
    private fun createEnrichment(): JenkinsEnrichment = JenkinsEnrichment(
        steps = mapOf(
            "sh" to StepEnrichment(
                plugin = "workflow-durable-task-step",
                description = "Execute a shell script on a node.",
                documentationUrl =
                "https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/#sh-shell-script",
                category = StepCategory.UTILITY,
                examples = listOf("sh 'echo Hello'"),
                parameterEnrichment = mapOf(
                    "script" to ParameterEnrichment(
                        description = "The shell script to execute.",
                        required = true,
                        examples = listOf("echo 'test'"),
                    ),
                    "returnStdout" to ParameterEnrichment(
                        description = "If true, return stdout from the script instead of printing it.",
                        required = false,
                    ),
                ),
            ),
        ),
        globalVariables = mapOf(
            "env" to GlobalVariableEnrichment(
                description = "Environment variables available to the pipeline.",
                documentationUrl =
                "https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#using-environment-variables",
            ),
            "currentBuild" to GlobalVariableEnrichment(
                description = "Information about the currently running build.",
                properties = mapOf(
                    "result" to PropertyEnrichment(
                        type = "String",
                        description = "Build result.",
                    ),
                    "number" to PropertyEnrichment(
                        type = "int",
                        description = "Build number.",
                        readOnly = true,
                    ),
                ),
            ),
        ),
        sections = mapOf(
            "pipeline" to SectionEnrichment(
                description = "Top-level Declarative Pipeline block.",
                allowedIn = listOf("root"),
            ),
            "stages" to SectionEnrichment(
                description = "Group of one or more stages.",
                allowedIn = listOf("pipeline"),
                innerInstructions = listOf("stage"),
            ),
        ),
        directives = mapOf(
            "environment" to DirectiveEnrichment(
                description = "Define environment variables for the pipeline or a stage.",
                allowedIn = listOf("pipeline", "stage"),
            ),
        ),
    )
}
