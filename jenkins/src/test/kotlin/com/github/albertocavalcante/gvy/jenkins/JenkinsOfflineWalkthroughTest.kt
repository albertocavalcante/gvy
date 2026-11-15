// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedAstParser
import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedParseLimits
import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedParseResult
import com.github.albertocavalcante.gvy.jenkins.plugins.PluginDiscoveryService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JenkinsOfflineWalkthroughTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `documented settings and saved metadata work without a controller or plugin downloads`() {
        val settingsText = fixture(".vscode/settings.json").lineSequence()
            .filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val settings = Json.parseToJsonElement(settingsText).jsonObject.map { (key, value) ->
            key.removePrefix("groovy.") to when (value) {
                is JsonArray -> value.map { it.jsonPrimitive.content }
                is JsonPrimitive -> value.content
                else -> error("Unexpected example setting: $key")
            }
        }.toMap()
        Files.writeString(workspace.resolve("plugins.txt"), fixture("plugins.txt"))
        val gdsl = workspace.resolve("pipeline.gdsl")
        Files.writeString(gdsl, fixture("pipeline.gdsl"))
        // These are the machine-scoped user settings documented beside the workspace settings.
        val configuration = JenkinsConfiguration.fromMap(
            settings + mapOf("jenkins.gdslPaths" to listOf(gdsl.toString()), "jenkins.gdslExecution.enabled" to true),
        )
        val manager = JenkinsWorkspaceManager(configuration, workspace)
        val loaded = manager.loadGdslMetadata()

        assertTrue(loaded.failed.isEmpty())
        assertEquals(1, loaded.successful.size)
        val metadata = assertNotNull(manager.getAllMetadata(workspace.resolve("Jenkinsfile").toUri()))
        assertNotNull(metadata.getStep("publishArtifact"))
        assertTrue(manager.isJenkinsFile(workspace.resolve("Jenkinsfile-scripted").toUri()))
        assertTrue(manager.isJenkinsFile(workspace.resolve("Jenkinsfile").toUri()))
        val plugins = PluginDiscoveryService(workspace, configuration.pluginConfig).discoverPlugins()
        assertTrue(plugins.isNotEmpty())
        assertTrue(plugins.all { it.version == null && it.jarPath == null })
    }

    @Test
    fun `both pipeline styles parse without executing pipeline or shared library code`() {
        for (name in listOf("Jenkinsfile", "Jenkinsfile-scripted")) {
            assertIs<UntrustedParseResult.Parsed>(
                UntrustedAstParser.parse(fixture(name), name, UntrustedParseLimits(maxNesting = 32)),
                name,
            )
        }
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResourceAsStream("/examples/jenkins-offline/$name"),
    ) { "Missing documented fixture: $name" }.bufferedReader().use { it.readText() }
}
