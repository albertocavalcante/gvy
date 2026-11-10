package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Guards scenario settings (`initializationOptions` and `workspace/didChangeConfiguration`) against keys the
 * server never reads.
 *
 * The server silently ignores unknown options, so a typo such as `groovy.engine.type` (the real key is
 * `groovy.languageServer.engine`) leaves a scenario running on the default engine while claiming otherwise.
 *
 * [KNOWN_SERVER_KEYS] mirrors the keys read by `ServerConfiguration.fromMap` and `JenkinsConfiguration.fromMap`.
 * When the server starts reading a new key, add it here.
 */
class ScenarioInitializationOptionsTest {
    @Test
    fun `scenarios only use option keys the server reads`() {
        val unknown = settingsByScenario().flatMap { (scenario, settings) ->
            settings.keys.filterNot { it in KNOWN_SERVER_KEYS }.map { "  $scenario: $it" }
        }

        val listing = unknown.joinToString("\n")
        assertTrue(
            unknown.isEmpty(),
            "Scenarios use initializationOptions/didChangeConfiguration keys the server never reads " +
                "(they are silently ignored):\n$listing\nKnown keys: ${KNOWN_SERVER_KEYS.sorted()}",
        )
    }

    @Test
    fun `scenarios only select engines the server knows`() {
        // EngineType.fromString silently falls back to Core, so a typo such as "nativ" would go unnoticed.
        val accepted = EngineType.entries.map { it.id.lowercase() }
        val bad = settingsByScenario().mapNotNull { (scenario, settings) ->
            val value = settings[ENGINE_KEY] ?: return@mapNotNull null
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text?.lowercase() in accepted) null else "  $scenario: $ENGINE_KEY=$value"
        }

        assertTrue(bad.isEmpty(), "Unknown engine values (accepted: $accepted):\n${bad.joinToString("\n")}")
    }

    /** Every settings object a scenario sends: `initialize` options and `workspace/didChangeConfiguration` settings. */
    private fun settingsByScenario(): List<Pair<String, JsonObject>> {
        val definitions = ScenarioLoader().loadAll(Path.of(resolveScenarioDir()))
        return definitions.flatMap { definition ->
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
    }

    private companion object {
        const val ENGINE_KEY = "groovy.languageServer.engine"

        val KNOWN_SERVER_KEYS = setOf(
            // ServerConfiguration.fromMap
            "groovy.languageServer.engine",
            "groovy.compilation.mode",
            "groovy.compilation.incrementalThreshold",
            "groovy.compilation.maxWorkspaceFiles",
            "groovy.compilation.cacheMaxEntries",
            "groovy.server.maxNumberOfProblems",
            "groovy.server.logLevel",
            "groovy.java.home",
            "groovy.language.version",
            "groovy.repl.enabled",
            "groovy.repl.maxSessions",
            "groovy.repl.sessionTimeoutMinutes",
            "groovy.workers",
            "groovy.codenarc.enabled",
            "groovy.codenarc.propertiesFile",
            "groovy.codenarc.autoDetect",
            "groovy.diagnostics.providers.enabled",
            "groovy.diagnostics.providers.disabled",
            "groovy.diagnostics.rules.enabled",
            "groovy.diagnostics.rules.disabled",
            "groovy.diagnostics.rules.analysisTypes.enabled",
            "groovy.diagnostics.rules.analysisTypes.disabled",
            "groovy.gradle.buildStrategy",
            "groovy.gradle.targetClasspaths.enabled",
            "groovy.mode",
            "groovy.trace.server",
            // JenkinsConfiguration.fromMap
            "jenkins.plugins.file",
            "jenkins.metadata.file",
            "jenkins.filePatterns",
            "jenkins.sharedLibraries",
            "jenkins.gdslPaths",
            "jenkins.gdslExecution.enabled",
            // PluginConfiguration.fromMap (via JenkinsConfiguration.fromMap)
            "jenkins.pluginsTxtPath",
            "jenkins.plugins",
            "jenkins.includeDefaultPlugins",
        )
    }
}
