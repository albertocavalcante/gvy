package com.github.albertocavalcante.gvy.gls.transport.lsp4j

import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigurationPayloadTest {
    @Test
    fun `JSON settings retain numbers nested workers and string or list settings`() {
        val json = JsonParser.parseString(
            """
            {
              "groovy.compilation.cacheMaxEntries": 12.9,
              "groovy.gradle.daemon.idleTimeoutMinutes": 999,
              "groovy.repl.enabled": false,
              "groovy.diagnostics.providers.disabled": "parser, codenarc",
              "groovy.diagnostics.rules.enabled": ["one", "two"],
              "groovy.workers": [{"id":"worker", "minVersion":"1", "features":["a", "b"]}],
              "jenkins.sharedLibraries": [{"name":"shared", "jar":"library.jar"}]
            }
            """.trimIndent(),
        )
        val expected = ServerConfiguration.fromMap(
            mapOf(
                "groovy.compilation.cacheMaxEntries" to 12.9,
                "groovy.gradle.daemon.idleTimeoutMinutes" to 999.0,
                "groovy.repl.enabled" to false,
                "groovy.diagnostics.providers.disabled" to "parser, codenarc",
                "groovy.diagnostics.rules.enabled" to listOf("one", "two"),
                "groovy.workers" to listOf(
                    mapOf("id" to "worker", "minVersion" to "1", "features" to listOf("a", "b")),
                ),
                "jenkins.sharedLibraries" to listOf(mapOf("name" to "shared", "jar" to "library.jar")),
            ),
        )
        assertEquals(expected, ServerConfiguration.fromMap(configurationPayload(json)))
    }

    @Test
    fun `direct maps retain supported settings`() {
        val input = mapOf("groovy.compilation.cacheMaxEntries" to 42, "groovy.repl.enabled" to false)
        assertEquals(ServerConfiguration.fromMap(input), ServerConfiguration.fromMap(configurationPayload(input)))
    }

    @Test
    fun `kotlinx JSON objects are converted before generic maps`() {
        val input = Json.parseToJsonElement(
            """{"groovy.compilation.cacheMaxEntries":42,"groovy.repl.enabled":false}""",
        )
        val expected = ServerConfiguration.fromMap(
            mapOf("groovy.compilation.cacheMaxEntries" to 42, "groovy.repl.enabled" to false),
        )
        assertEquals(expected, ServerConfiguration.fromMap(configurationPayload(input)))
    }

    @Test
    fun `non object payloads are rejected`() {
        for (json in listOf("null", "[]", "17", "\"invalid\"")) {
            assertNull(configurationPayload(JsonParser.parseString(json)), json)
        }
        assertNull(configurationPayload(null))
    }
}
