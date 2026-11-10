package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Guards scenario `initializationOptions` against keys the server never reads.
 *
 * The server silently ignores unknown options, so a typo such as `groovy.engine.type` (the real key is
 * `groovy.languageServer.engine`) leaves a scenario running on the default engine while claiming otherwise.
 *
 * [KNOWN_SERVER_KEYS] mirrors the keys read by `ServerConfiguration.fromMap` and `JenkinsConfiguration.fromMap`.
 * When the server starts reading a new key, add it here.
 */
class ScenarioInitializationOptionsTest {
    @Test
    fun `scenarios only use initialization option keys the server reads`() {
        val definitions = ScenarioLoader().loadAll(Path.of(resolveScenarioDir()))
        val unknown = unknownKeysByScenario(definitions)

        assertTrue(
            unknown.isEmpty(),
            "Scenarios use initializationOptions keys the server never reads (they are silently ignored):\n" +
                unknown.joinToString("\n") { "  ${it.first}: ${it.second}" } +
                "\nKnown keys: ${KNOWN_SERVER_KEYS.sorted()}",
        )
    }

    private fun unknownKeysByScenario(definitions: List<ScenarioDefinition>): List<Pair<String, String>> =
        definitions.flatMap { definition ->
            definition.scenario.steps
                .filterIsInstance<ScenarioStep.Initialize>()
                .flatMap { (it.initializationOptions as? JsonObject)?.keys.orEmpty() }
                .filterNot { it in KNOWN_SERVER_KEYS }
                .map { key -> definition.source.substringAfterLast('/') to key }
        }

    private companion object {
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
