package com.github.albertocavalcante.groovyjenkins.metadata.enrichment

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Test serialization and deserialization of enrichment metadata.
 * Tier 2 metadata is hand-curated and version-controlled.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class EnrichmentMetadataSerializationTest {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    @Test
    fun `serializes complete JenkinsEnrichment`() {
        val enrichment = JenkinsEnrichment(
            version = "1.0.0",
            steps = mapOf(
                "sh" to StepEnrichment(
                    plugin = "workflow-durable-task-step",
                    description = "Execute a shell script",
                    documentationUrl = "https://www.jenkins.io/doc/pipeline/steps/sh",
                    category = StepCategory.UTILITY,
                    examples = listOf("sh 'echo Hello'"),
                    parameterEnrichment = mapOf(
                        "script" to ParameterEnrichment(
                            description = "The shell script to execute",
                            required = true,
                            validValues = null,
                            examples = listOf("echo 'test'"),
                        ),
                    ),
                    deprecation = null,
                ),
            ),
            globalVariables = mapOf(
                "env" to GlobalVariableEnrichment(
                    description = "Environment variables",
                    documentationUrl = "https://jenkins.io/doc/env",
                    properties = mapOf(
                        "BUILD_ID" to PropertyEnrichment(
                            type = "String",
                            description = "Build ID",
                            readOnly = true,
                        ),
                    ),
                ),
            ),
            sections = mapOf(
                "agent" to SectionEnrichment(
                    description = "Specifies where to execute",
                    allowedIn = listOf("pipeline", "stage"),
                    innerInstructions = listOf("any", "none", "label"),
                    documentationUrl = "https://jenkins.io/doc/agent",
                ),
            ),
            directives = mapOf(
                "environment" to DirectiveEnrichment(
                    description = "Defines environment variables",
                    allowedIn = listOf("pipeline", "stage"),
                    documentationUrl = "https://jenkins.io/doc/environment",
                ),
            ),
        )

        val serialized = json.encodeToString(enrichment)

        assertThat(serialized).contains("\"sh\"")
        assertThat(serialized).contains("\"workflow-durable-task-step\"")
        assertThat(serialized).contains("\"category\": \"utility\"")
    }

    @Test
    fun `deserializes JenkinsEnrichment from JSON`() {
        val jsonString = """
            {
              "${'$'}schema": "https://groovy-lsp.dev/schemas/jenkins-enrichment-2025-12-21.json",
              "version": "1.0.0",
              "steps": {
                "echo": {
                  "plugin": "workflow-basic-steps",
                  "description": "Print message to console",
                  "documentationUrl": "https://jenkins.io/doc/echo",
                  "category": "utility",
                  "examples": ["echo 'Hello World'"]
                }
              },
              "globalVariables": {},
              "sections": {},
              "directives": {}
            }
        """.trimIndent()

        val deserialized = json.decodeFromString<JenkinsEnrichment>(jsonString)

        assertThat(deserialized.version).isEqualTo("1.0.0")
        assertThat(deserialized.steps).containsKey("echo")
        assertThat(deserialized.steps["echo"]?.plugin).isEqualTo("workflow-basic-steps")
        assertThat(deserialized.steps["echo"]?.category).isEqualTo(StepCategory.UTILITY)
    }

    @Test
    fun `round-trip serialization preserves enrichment data`() {
        val original = JenkinsEnrichment(
            version = "1.0.0",
            steps = mapOf(
                "git" to StepEnrichment(
                    plugin = "git",
                    description = "Check out from Git",
                    documentationUrl = "https://jenkins.io/doc/git",
                    category = StepCategory.SCM,
                    examples = listOf("git 'https://github.com/user/repo.git'"),
                    parameterEnrichment = emptyMap(),
                    deprecation = null,
                ),
            ),
            globalVariables = emptyMap(),
            sections = emptyMap(),
            directives = emptyMap(),
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<JenkinsEnrichment>(serialized)

        assertThat(deserialized).isEqualTo(original)
    }

    @Test
    fun `StepCategory serializes to lowercase`() {
        val enrichment = StepEnrichment(
            plugin = "test",
            category = StepCategory.BUILD,
        )

        val serialized = json.encodeToString(enrichment)

        assertThat(serialized).contains("\"category\": \"build\"")
    }

    @Test
    fun `handles deprecated steps with deprecation info`() {
        val jsonString = """
            {
              "plugin": "old-plugin",
              "deprecation": {
                "since": "2.0.0",
                "replacement": "newStep",
                "message": "Use newStep instead"
              }
            }
        """.trimIndent()

        val deserialized = json.decodeFromString<StepEnrichment>(jsonString)

        assertThat(deserialized.deprecation).isNotNull
        assertThat(deserialized.deprecation?.since).isEqualTo("2.0.0")
        assertThat(deserialized.deprecation?.replacement).isEqualTo("newStep")
        assertThat(deserialized.deprecation?.message).isEqualTo("Use newStep instead")
    }

    @Test
    fun `handles missing optional fields in enrichment`() {
        val minimalJson = """
            {
              "plugin": "minimal-plugin"
            }
        """.trimIndent()

        val deserialized = json.decodeFromString<StepEnrichment>(minimalJson)

        assertThat(deserialized.plugin).isEqualTo("minimal-plugin")
        assertThat(deserialized.description).isNull()
        assertThat(deserialized.documentationUrl).isNull()
        assertThat(deserialized.category).isNull()
        assertThat(deserialized.examples).isEmpty()
        assertThat(deserialized.parameterEnrichment).isEmpty()
        assertThat(deserialized.deprecation).isNull()
    }

    @Test
    fun `ParameterEnrichment with validation rules`() {
        val param = ParameterEnrichment(
            description = "Build action",
            required = true,
            validValues = listOf("SUCCESS", "FAILURE", "UNSTABLE"),
            examples = listOf("SUCCESS"),
        )

        val serialized = json.encodeToString(param)
        val deserialized = json.decodeFromString<ParameterEnrichment>(serialized)

        assertThat(deserialized.required).isTrue()
        assertThat(deserialized.validValues).containsExactly("SUCCESS", "FAILURE", "UNSTABLE")
    }

    @Test
    fun `GlobalVariableEnrichment with properties`() {
        val globalVar = GlobalVariableEnrichment(
            description = "Current build information",
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

        val serialized = json.encodeToString(globalVar)
        val deserialized = json.decodeFromString<GlobalVariableEnrichment>(serialized)

        assertThat(deserialized.properties).hasSize(2)
        assertThat(deserialized.properties["result"]?.readOnly).isFalse()
        assertThat(deserialized.properties["number"]?.readOnly).isTrue()
    }

    @Test
    fun `SectionEnrichment with nested instructions`() {
        val section = SectionEnrichment(
            description = "Post-build actions",
            allowedIn = listOf("pipeline", "stage"),
            innerInstructions = listOf("always", "success", "failure", "unstable"),
            documentationUrl = "https://jenkins.io/post",
        )

        val serialized = json.encodeToString(section)
        val deserialized = json.decodeFromString<SectionEnrichment>(serialized)

        assertThat(deserialized.allowedIn).containsExactly("pipeline", "stage")
        assertThat(deserialized.innerInstructions).hasSize(4)
    }

    @Test
    fun `all StepCategory values serialize correctly`() {
        val categories = listOf(
            StepCategory.SCM to "scm",
            StepCategory.BUILD to "build",
            StepCategory.TEST to "test",
            StepCategory.DEPLOY to "deploy",
            StepCategory.NOTIFICATION to "notification",
            StepCategory.UTILITY to "utility",
        )

        categories.forEach { (category, expectedJson) ->
            val enrichment = StepEnrichment(plugin = "test", category = category)
            val serialized = json.encodeToString(enrichment)
            assertThat(serialized).contains("\"category\": \"$expectedJson\"")
        }
    }
}
