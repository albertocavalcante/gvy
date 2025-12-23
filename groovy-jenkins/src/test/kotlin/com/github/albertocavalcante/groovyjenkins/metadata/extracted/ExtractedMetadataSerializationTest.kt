package com.github.albertocavalcante.groovyjenkins.metadata.extracted

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Test serialization and deserialization of extracted Jenkins metadata.
 * Following TDD: tests written first to define the API contract.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class ExtractedMetadataSerializationTest {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    @Test
    fun `serializes PluginMetadata with all fields`() {
        val pluginMetadata = PluginMetadata(
            plugin = PluginInfo(
                id = "workflow-basic-steps",
                version = "1058.vcb_fc1e3a_21a_9",
                displayName = "Pipeline: Basic Steps",
            ),
            extraction = ExtractionInfo(
                jenkinsVersion = "2.426.3",
                extractedAt = "2024-01-15T10:30:00Z",
                pluginsManifestSha256 = "abc123...",
                gdslSha256 = "def456...",
                extractorVersion = "1.0.0",
            ),
            steps = mapOf(
                "echo" to ExtractedStep(
                    scope = StepScope.GLOBAL,
                    positionalParams = listOf("message"),
                    namedParams = mapOf(
                        "message" to ExtractedParameter(
                            type = "String",
                            defaultValue = null,
                        ),
                    ),
                    documentation = "Print message",
                    returnType = "void",
                ),
            ),
            globalVariables = mapOf(
                "env" to ExtractedGlobalVariable(
                    type = "EnvActionImpl",
                    documentation = "Environment variables",
                ),
            ),
        )

        val serialized = json.encodeToString(pluginMetadata)

        assertThat(serialized).contains("\"plugin\"")
        assertThat(serialized).contains("\"workflow-basic-steps\"")
        assertThat(serialized).contains("\"echo\"")
        assertThat(serialized).contains("\"scope\": \"global\"")
    }

    @Test
    fun `deserializes PluginMetadata from JSON`() {
        val jsonString = """
            {
              "${'$'}schema": "https://groovy-lsp.dev/schemas/jenkins-plugin-metadata-v1.json",
              "plugin": {
                "id": "workflow-durable-task-step",
                "version": "1371.vb_7cec8f3b_95e",
                "displayName": "Pipeline: Durable Task Step"
              },
              "extraction": {
                "jenkinsVersion": "2.426.3",
                "extractedAt": "2024-01-15T10:30:00Z",
                "pluginsManifestSha256": "abc123",
                "gdslSha256": "def456",
                "extractorVersion": "1.0.0"
              },
              "steps": {
                "sh": {
                  "scope": "node",
                  "positionalParams": ["script"],
                  "namedParams": {
                    "script": {
                      "type": "String"
                    },
                    "returnStdout": {
                      "type": "boolean",
                      "defaultValue": "false"
                    }
                  },
                  "documentation": "Shell Script",
                  "returnType": "Object"
                }
              },
              "globalVariables": {}
            }
        """.trimIndent()

        val deserialized = json.decodeFromString<PluginMetadata>(jsonString)

        assertThat(deserialized.plugin.id).isEqualTo("workflow-durable-task-step")
        assertThat(deserialized.plugin.version).isEqualTo("1371.vb_7cec8f3b_95e")
        assertThat(deserialized.extraction.jenkinsVersion).isEqualTo("2.426.3")
        assertThat(deserialized.steps).containsKey("sh")

        val shStep = deserialized.steps["sh"]!!
        assertThat(shStep.scope).isEqualTo(StepScope.NODE)
        assertThat(shStep.positionalParams).containsExactly("script")
        assertThat(shStep.namedParams).containsKeys("script", "returnStdout")
        assertThat(shStep.namedParams["returnStdout"]?.defaultValue).isEqualTo("false")
    }

    @Test
    fun `round-trip serialization preserves data`() {
        val original = PluginMetadata(
            plugin = PluginInfo(
                id = "core",
                version = "2.426.3",
                displayName = null,
            ),
            extraction = ExtractionInfo(
                jenkinsVersion = "2.426.3",
                extractedAt = "2024-01-15T10:30:00Z",
                pluginsManifestSha256 = "sha256hash",
                gdslSha256 = "gdslhash",
                extractorVersion = "1.0.0",
            ),
            steps = mapOf(
                "node" to ExtractedStep(
                    scope = StepScope.GLOBAL,
                    positionalParams = emptyList(),
                    namedParams = mapOf(
                        "label" to ExtractedParameter(
                            type = "String",
                            defaultValue = null,
                        ),
                    ),
                    documentation = null,
                    returnType = null,
                ),
            ),
            globalVariables = emptyMap(),
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<PluginMetadata>(serialized)

        assertThat(deserialized).isEqualTo(original)
    }

    @Test
    fun `StepScope serializes to lowercase strings`() {
        val step = ExtractedStep(
            scope = StepScope.GLOBAL,
            positionalParams = emptyList(),
            namedParams = emptyMap(),
            documentation = null,
            returnType = null,
        )

        val serialized = json.encodeToString(step)

        assertThat(serialized).contains("\"scope\": \"global\"")
    }

    @Test
    fun `handles missing optional fields gracefully`() {
        val minimalJson = """
            {
              "plugin": {
                "id": "test-plugin",
                "version": "1.0.0"
              },
              "extraction": {
                "jenkinsVersion": "2.426.3",
                "extractedAt": "2024-01-15T10:30:00Z",
                "pluginsManifestSha256": "hash",
                "gdslSha256": "hash"
              },
              "steps": {}
            }
        """.trimIndent()

        val deserialized = json.decodeFromString<PluginMetadata>(minimalJson)

        assertThat(deserialized.plugin.displayName).isNull()
        assertThat(deserialized.steps).isEmpty()
        assertThat(deserialized.globalVariables).isEmpty()
        assertThat(deserialized.extraction.extractorVersion).isEqualTo("1.0.0") // default
    }

    @Test
    fun `ExtractedStep with positional and named parameters`() {
        val step = ExtractedStep(
            scope = StepScope.STAGE,
            positionalParams = listOf("arg1", "arg2"),
            namedParams = mapOf(
                "option1" to ExtractedParameter("String", "defaultValue"),
                "option2" to ExtractedParameter("boolean", null),
            ),
            documentation = "Test step",
            returnType = "String",
        )

        val serialized = json.encodeToString(step)
        val deserialized = json.decodeFromString<ExtractedStep>(serialized)

        assertThat(deserialized.positionalParams).containsExactly("arg1", "arg2")
        assertThat(deserialized.namedParams["option1"]?.defaultValue).isEqualTo("defaultValue")
        assertThat(deserialized.namedParams["option2"]?.defaultValue).isNull()
    }

    @Test
    fun `ExtractedGlobalVariable serialization`() {
        val globalVar = ExtractedGlobalVariable(
            type = "org.jenkinsci.plugins.workflow.cps.EnvActionImpl",
            documentation = "Environment variables accessible throughout the pipeline",
        )

        val serialized = json.encodeToString(globalVar)
        val deserialized = json.decodeFromString<ExtractedGlobalVariable>(serialized)

        assertThat(deserialized.type).isEqualTo(globalVar.type)
        assertThat(deserialized.documentation).isEqualTo(globalVar.documentation)
    }
}
