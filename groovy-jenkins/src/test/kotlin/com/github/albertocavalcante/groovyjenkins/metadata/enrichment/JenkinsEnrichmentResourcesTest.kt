package com.github.albertocavalcante.groovyjenkins.metadata.enrichment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JenkinsEnrichmentResourcesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `jenkins enrichment json resource exists and deserializes`() {
        val content = requireNotNull(javaClass.getResource("/jenkins-enrichment.json")) {
            "Expected jenkins-enrichment.json to be present on the classpath"
        }.readText()

        val enrichment = json.decodeFromString(JenkinsEnrichment.serializer(), content)

        assertThat(enrichment.schema).isEqualTo("https://groovy-lsp.dev/schemas/jenkins-enrichment-2025-12-21.json")
        assertThat(enrichment.version).isNotBlank()

        // A small, stable contract for PR #3:
        // - we curate a baseline set of core steps
        // - and the most common global variables
        assertThat(enrichment.steps.keys).contains("sh", "echo", "checkout", "git")
        assertThat(enrichment.globalVariables.keys).contains("env", "params", "currentBuild", "scm")
    }

    @Test
    fun `enrichment schema resource exists and has expected id`() {
        val schemaContent = requireNotNull(javaClass.getResource("/schemas/jenkins-enrichment-2025-12-21.json")) {
            "Expected schemas/jenkins-enrichment-2025-12-21.json to be present on the classpath"
        }.readText()

        val schemaElement = json.parseToJsonElement(schemaContent)
        val schemaObject = schemaElement as? JsonObject

        assertThat(schemaObject).isNotNull
        assertThat(schemaObject?.get("\$id")?.jsonPrimitive?.content)
            .isEqualTo("https://groovy-lsp.dev/schemas/jenkins-enrichment-2025-12-21.json")
    }
}
