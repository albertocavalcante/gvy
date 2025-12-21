package com.github.albertocavalcante.groovyjenkins.metadata

import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.DeprecationInfo
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.PropertyEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.StepCategory
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.StepScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Test the merged runtime metadata model.
 * This model combines extracted (Tier 1) and enriched (Tier 2) data.
 */
class MergedJenkinsMetadataTest {

    @Test
    fun `creates merged metadata with steps and global variables`() {
        val metadata = MergedJenkinsMetadata(
            jenkinsVersion = "2.426.3",
            steps = mapOf(
                "sh" to MergedStepMetadata(
                    name = "sh",
                    scope = StepScope.NODE,
                    positionalParams = listOf("script"),
                    namedParams = mapOf(
                        "script" to MergedParameter(
                            name = "script",
                            type = "String",
                            defaultValue = null,
                            description = "The shell script to execute",
                            required = true,
                            validValues = null,
                            examples = emptyList(),
                        ),
                    ),
                    extractedDocumentation = "Shell Script",
                    returnType = "Object",
                    plugin = "workflow-durable-task-step",
                    enrichedDescription = "Execute a shell script on Unix-like systems",
                    documentationUrl = "https://jenkins.io/sh",
                    category = StepCategory.UTILITY,
                    examples = listOf("sh 'echo Hello'"),
                    deprecation = null,
                ),
            ),
            globalVariables = mapOf(
                "env" to MergedGlobalVariable(
                    name = "env",
                    type = "EnvActionImpl",
                    extractedDocumentation = "Environment Variables",
                    enrichedDescription = "Environment variables accessible throughout the pipeline",
                    documentationUrl = "https://jenkins.io/env",
                    properties = mapOf(
                        "BUILD_ID" to PropertyEnrichment(
                            type = "String",
                            description = "Current build ID",
                            readOnly = true,
                        ),
                    ),
                ),
            ),
            sections = emptyMap(),
            directives = emptyMap(),
        )

        assertThat(metadata.jenkinsVersion).isEqualTo("2.426.3")
        assertThat(metadata.steps).hasSize(1)
        assertThat(metadata.globalVariables).hasSize(1)
    }

    @Test
    fun `getStep returns step by name`() {
        val metadata = createTestMetadata()

        val step = metadata.getStep("sh")

        assertThat(step).isNotNull
        assertThat(step?.name).isEqualTo("sh")
    }

    @Test
    fun `getStep returns null for unknown step`() {
        val metadata = createTestMetadata()

        val step = metadata.getStep("unknownStep")

        assertThat(step).isNull()
    }

    @Test
    fun `getGlobalVariable returns variable by name`() {
        val metadata = createTestMetadata()

        val variable = metadata.getGlobalVariable("env")

        assertThat(variable).isNotNull
        assertThat(variable?.name).isEqualTo("env")
    }

    @Test
    fun `getGlobalVariable returns null for unknown variable`() {
        val metadata = createTestMetadata()

        val variable = metadata.getGlobalVariable("unknownVar")

        assertThat(variable).isNull()
    }

    @Test
    fun `MergedStepMetadata prefers enriched documentation over extracted`() {
        val stepWithBoth = MergedStepMetadata(
            name = "test",
            scope = StepScope.GLOBAL,
            positionalParams = emptyList(),
            namedParams = emptyMap(),
            extractedDocumentation = "Basic doc",
            returnType = null,
            plugin = "test-plugin",
            enrichedDescription = "Enhanced documentation",
            documentationUrl = null,
            category = null,
            examples = emptyList(),
            deprecation = null,
        )

        assertThat(stepWithBoth.documentation).isEqualTo("Enhanced documentation")
    }

    @Test
    fun `MergedStepMetadata falls back to extracted documentation when enrichment is null`() {
        val stepExtractedOnly = MergedStepMetadata(
            name = "test",
            scope = StepScope.GLOBAL,
            positionalParams = emptyList(),
            namedParams = emptyMap(),
            extractedDocumentation = "Basic doc",
            returnType = null,
            plugin = null,
            enrichedDescription = null,
            documentationUrl = null,
            category = null,
            examples = emptyList(),
            deprecation = null,
        )

        assertThat(stepExtractedOnly.documentation).isEqualTo("Basic doc")
    }

    @Test
    fun `MergedStepMetadata with deprecation info`() {
        val deprecatedStep = MergedStepMetadata(
            name = "oldStep",
            scope = StepScope.GLOBAL,
            positionalParams = emptyList(),
            namedParams = emptyMap(),
            extractedDocumentation = "Old step",
            returnType = null,
            plugin = "old-plugin",
            enrichedDescription = null,
            documentationUrl = null,
            category = null,
            examples = emptyList(),
            deprecation = DeprecationInfo(
                since = "2.0.0",
                replacement = "newStep",
                message = "Use newStep instead",
            ),
        )

        assertThat(deprecatedStep.deprecation).isNotNull
        assertThat(deprecatedStep.deprecation?.since).isEqualTo("2.0.0")
        assertThat(deprecatedStep.deprecation?.replacement).isEqualTo("newStep")
    }

    @Test
    fun `MergedParameter combines extracted and enrichment data`() {
        val parameter = MergedParameter(
            name = "script",
            type = "String",
            defaultValue = null,
            description = "The shell script to execute",
            required = true,
            validValues = null,
            examples = listOf("echo 'test'", "ls -la"),
        )

        assertThat(parameter.name).isEqualTo("script")
        assertThat(parameter.type).isEqualTo("String")
        assertThat(parameter.required).isTrue()
        assertThat(parameter.examples).hasSize(2)
    }

    @Test
    fun `MergedParameter with validation rules`() {
        val parameter = MergedParameter(
            name = "result",
            type = "String",
            defaultValue = "SUCCESS",
            description = "Build result",
            required = false,
            validValues = listOf("SUCCESS", "FAILURE", "UNSTABLE"),
            examples = emptyList(),
        )

        assertThat(parameter.validValues).containsExactly("SUCCESS", "FAILURE", "UNSTABLE")
        assertThat(parameter.defaultValue).isEqualTo("SUCCESS")
    }

    @Test
    fun `MergedGlobalVariable prefers enriched description`() {
        val variable = MergedGlobalVariable(
            name = "params",
            type = "ParamsVariable",
            extractedDocumentation = "Parameters",
            enrichedDescription = "Build parameters defined in the pipeline",
            documentationUrl = "https://jenkins.io/params",
            properties = emptyMap(),
        )

        assertThat(variable.documentation).isEqualTo("Build parameters defined in the pipeline")
    }

    @Test
    fun `MergedGlobalVariable falls back to extracted documentation`() {
        val variable = MergedGlobalVariable(
            name = "params",
            type = "ParamsVariable",
            extractedDocumentation = "Parameters",
            enrichedDescription = null,
            documentationUrl = null,
            properties = emptyMap(),
        )

        assertThat(variable.documentation).isEqualTo("Parameters")
    }

    @Test
    fun `MergedGlobalVariable with properties`() {
        val variable = MergedGlobalVariable(
            name = "currentBuild",
            type = "RunWrapper",
            extractedDocumentation = "Current build",
            enrichedDescription = "Information about the current build",
            documentationUrl = "https://jenkins.io/currentBuild",
            properties = mapOf(
                "result" to PropertyEnrichment(
                    type = "String",
                    description = "Build result",
                    readOnly = false,
                ),
                "number" to PropertyEnrichment(
                    type = "int",
                    description = "Build number",
                    readOnly = true,
                ),
            ),
        )

        assertThat(variable.properties).hasSize(2)
        assertThat(variable.properties["result"]?.readOnly).isFalse()
        assertThat(variable.properties["number"]?.readOnly).isTrue()
    }

    private fun createTestMetadata(): MergedJenkinsMetadata = MergedJenkinsMetadata(
        jenkinsVersion = "2.426.3",
        steps = mapOf(
            "sh" to MergedStepMetadata(
                name = "sh",
                scope = StepScope.NODE,
                positionalParams = listOf("script"),
                namedParams = emptyMap(),
                extractedDocumentation = "Shell Script",
                returnType = "Object",
                plugin = "workflow-durable-task-step",
                enrichedDescription = "Execute a shell script",
                documentationUrl = "https://jenkins.io/sh",
                category = StepCategory.UTILITY,
                examples = emptyList(),
                deprecation = null,
            ),
        ),
        globalVariables = mapOf(
            "env" to MergedGlobalVariable(
                name = "env",
                type = "EnvActionImpl",
                extractedDocumentation = "Environment Variables",
                enrichedDescription = "Environment variables",
                documentationUrl = "https://jenkins.io/env",
                properties = emptyMap(),
            ),
        ),
        sections = emptyMap(),
        directives = emptyMap(),
    )
}
