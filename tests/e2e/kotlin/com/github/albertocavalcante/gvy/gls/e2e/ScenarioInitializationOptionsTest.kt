// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards scenario settings (`initializationOptions` and `workspace/didChangeConfiguration`) against keys the
 * server never reads.
 *
 * The server silently ignores unknown options, so a typo such as `groovy.engine.type` (the real key is
 * `groovy.languageServer.engine`) leaves a scenario running on the default engine while claiming otherwise.
 *
 * Accepted keys come from the real configuration readers, including Jenkins and plugin options.
 * Their current flat option reads are unconditional on the default path. Conditional or iterative readers
 * must extend this probe explicitly; iterating over supplied keys does not establish support for them.
 */
class ScenarioInitializationOptionsTest {
    @Test
    fun `scenarios only use option keys the server reads`() {
        val knownKeys = readConfigurationKeys()
        val unknown = unknownSettingsKeys(settingsByScenario(), knownKeys)

        val listing = unknown.joinToString("\n")
        assertTrue(
            unknown.isEmpty(),
            "Scenarios use initializationOptions/didChangeConfiguration keys the server never reads " +
                "(they are silently ignored):\n$listing\nKnown keys: ${knownKeys.sorted()}",
        )
    }

    @Test
    fun `scenarios only select engines the server knows`() {
        // EngineType.fromString silently falls back to Core, so a typo such as "nativ" would go unnoticed.
        val accepted = EngineType.entries.map { it.id }
        val bad = settingsByScenario().mapNotNull { (scenario, settings) ->
            engineProblem(scenario, settings)
        }

        assertTrue(bad.isEmpty(), "Unknown engine values (accepted: $accepted):\n${bad.joinToString("\n")}")
    }

    @Test
    fun `unknown option names are rejected with their scenario`() {
        val settings = listOf("typo.yaml" to JsonObject(mapOf("groovy.engine.type" to JsonPrimitive("native"))))

        assertEquals(listOf("  typo.yaml: groovy.engine.type"), unknownSettingsKeys(settings))
    }

    @Test
    fun `server Jenkins and plugin options are read through the real server reader`() {
        val settings = JsonObject(
            mapOf(
                "groovy.java.home" to JsonPrimitive("configured-java"),
                "jenkins.filePatterns" to JsonArray(listOf(JsonPrimitive("Pipeline.custom"))),
                "jenkins.plugins" to JsonArray(listOf(JsonPrimitive("sample-plugin"))),
            ),
        )

        assertTrue(unknownSettingsKeys(listOf("valid.yaml" to settings)).isEmpty())
    }

    @Test
    fun `omitted engine is accepted`() {
        assertNull(engineProblem("default.yaml", JsonObject(emptyMap())))
    }

    @Test
    fun `recognized engine strings are accepted case insensitively`() {
        EngineType.entries.flatMap { listOf(it.id, it.id.uppercase()) }.forEach { engine ->
            assertNull(engineProblem("valid.yaml", JsonObject(mapOf(ENGINE_KEY to JsonPrimitive(engine)))))
        }
    }

    @Test
    fun `unknown and non-string engine values are rejected with context`() {
        val invalid = listOf<JsonElement>(
            JsonPrimitive("nativ"),
            JsonPrimitive(1),
            JsonPrimitive(true),
            JsonObject(emptyMap()),
            JsonArray(emptyList()),
            JsonNull,
        )
        invalid.forEach { value ->
            assertEquals(
                "  invalid.yaml: $ENGINE_KEY=$value",
                engineProblem("invalid.yaml", JsonObject(mapOf(ENGINE_KEY to value))),
            )
        }
    }

    @Test
    fun `initialization and configuration notifications share option validation`() {
        val definition = ScenarioDefinition(
            scenario = Scenario(
                name = "configuration",
                steps = listOf(
                    ScenarioStep.Initialize(
                        initializationOptions = JsonObject(mapOf("unknown.init" to JsonPrimitive(true))),
                    ),
                    ScenarioStep.SendNotification(
                        method = "workspace/didChangeConfiguration",
                        params = JsonObject(
                            mapOf("settings" to JsonObject(mapOf("unknown.changed" to JsonPrimitive(true)))),
                        ),
                    ),
                    ScenarioStep.SendNotification(
                        method = "custom/notice",
                        params = JsonObject(
                            mapOf("settings" to JsonObject(mapOf("ignored" to JsonPrimitive(true)))),
                        ),
                    ),
                ),
            ),
            source = "nested/configuration.yaml",
        )

        assertEquals(
            listOf("  configuration.yaml: unknown.init", "  configuration.yaml: unknown.changed"),
            unknownSettingsKeys(settingsIn(listOf(definition))),
        )
    }

    @Test
    fun `recording map preserves ordinary configuration lookup values`() {
        val values: Map<String, Any> = mapOf(
            "groovy.java.home" to "configured-java",
            "jenkins.filePatterns" to listOf("Pipeline.custom"),
            "jenkins.plugins" to listOf("sample-plugin"),
            "jenkins.includeDefaultPlugins" to false,
        )
        val recording = ReadRecordingMap(values)
        val actual = ServerConfiguration.fromMap(recording)

        assertEquals(ServerConfiguration.fromMap(values), actual)
        assertEquals("configured-java", actual.javaHome)
        assertEquals(listOf("Pipeline.custom"), actual.jenkinsConfig.filePatterns)
        assertEquals(listOf("sample-plugin"), actual.jenkinsConfig.pluginConfig.plugins)
        assertFalse(actual.jenkinsConfig.pluginConfig.includeDefaultPlugins)
        assertTrue(recording.readKeys.containsAll(values.keys))
    }

    @Test
    fun `recording map counts keyed reads without accepting input iteration as support`() {
        val values: Map<String, Any> = mapOf("present" to "value", "unread" to "other")
        val recording = ReadRecordingMap(values)

        assertEquals(values.entries, recording.entries)
        assertTrue(recording.readKeys.isEmpty())
        assertEquals("value", recording["present"])
        assertFalse(recording.containsKey("missing"))
        assertEquals(setOf("present", "missing"), recording.readKeys)
    }

    private fun readConfigurationKeys(): Set<String> {
        val recording = ReadRecordingMap()
        ServerConfiguration.fromMap(recording)
        return recording.readKeys
    }

    private fun unknownSettingsKeys(
        settings: List<Pair<String, JsonObject>>,
        knownKeys: Set<String> = readConfigurationKeys(),
    ): List<String> = settings.flatMap { (scenario, options) ->
        options.keys.filterNot { it in knownKeys }.map { "  $scenario: $it" }
    }

    private fun engineProblem(scenario: String, settings: JsonObject): String? {
        val value = settings[ENGINE_KEY]
        return when {
            value == null -> null
            value is JsonPrimitive && value.isString &&
                EngineType.entries.any { it.id.equals(value.content, ignoreCase = true) } -> null
            else -> "  $scenario: $ENGINE_KEY=$value"
        }
    }

    /** Every settings object a scenario sends: `initialize` options and `workspace/didChangeConfiguration` settings. */
    private fun settingsByScenario(): List<Pair<String, JsonObject>> =
        settingsIn(ScenarioLoader().loadAll(Path.of(resolveScenarioDir())))

    private fun settingsIn(definitions: List<ScenarioDefinition>): List<Pair<String, JsonObject>> =
        definitions.flatMap { definition ->
            val name = definition.source.substringAfterLast('/')
            definition.scenario.steps.mapNotNull { step ->
                when (step) {
                    is ScenarioStep.Initialize -> step.initializationOptions as? JsonObject
                    is ScenarioStep.SendNotification ->
                        if (step.method == "workspace/didChangeConfiguration") {
                            (step.params as? JsonObject)?.get("settings") as? JsonObject
                        } else {
                            null
                        }
                    else -> null
                }?.let { name to it }
            }
        }

    private companion object {
        const val ENGINE_KEY = "groovy.languageServer.engine"
    }
}

private class ReadRecordingMap(private val delegate: Map<String, Any> = emptyMap()) : Map<String, Any> by delegate {
    private val reads = linkedSetOf<String>()

    val readKeys: Set<String>
        get() = reads.toSet()

    override fun get(key: String): Any? {
        reads += key
        return delegate[key]
    }

    override fun containsKey(key: String): Boolean {
        reads += key
        return delegate.containsKey(key)
    }
}
