// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.config

import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadataLoader
import com.github.albertocavalcante.gvy.jenkins.metadata.MetadataMerger
import com.github.albertocavalcante.gvy.jenkins.metadata.StableStepDefinitions
import com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind
import com.github.albertocavalcante.gvy.jenkins.metadata.VersionedMetadataLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for UserMetadataLoader - loads user-provided GDSL and metadata overrides.
 *
 * TDD RED phase: These tests define expected user configuration behavior.
 */
class UserMetadataLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loads user GDSL file when configured`() {
        // Create config directory and files
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)

        // Create config file
        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "gdslFile": ".jenkins/pipeline.gdsl"
            }
            """.trimIndent(),
        )

        // Create GDSL file
        val jenkinsDir = tempDir.resolve(".jenkins")
        Files.createDirectories(jenkinsDir)
        Files.writeString(
            jenkinsDir.resolve("pipeline.gdsl"),
            """
            contributor(context()) {
                method(name: 'myCustomStep', type: 'Object', params: [:], doc: 'Custom step')
            }
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val metadata = loader.load()

        assertNotNull(metadata)
        val step = assertNotNull(metadata.getStep("myCustomStep"))
        assertEquals("user-gdsl", step.plugin)
        val symbol = assertNotNull(metadata.typedCatalogue).unique(SymbolKind.EXECUTABLE_STEP, "myCustomStep")
        assertEquals(jenkinsDir.resolve("pipeline.gdsl").toString(), assertNotNull(symbol).provenance.origin)
        assertNull(symbol.ownerPlugin)
    }

    @Test
    fun `returns null when no config file exists`() {
        val loader = UserMetadataLoader(tempDir)
        val metadata = loader.load()

        assertNull(metadata)
    }

    @Test
    fun `loads metadata overrides from config`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)

        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "metadataOverrides": {
                    "myInternalStep": {
                        "plugin": "internal-plugin",
                        "documentation": "Our internal deployment step",
                        "parameters": {
                            "environment": { "type": "String", "required": true },
                            "version": { "type": "String", "required": false }
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val metadata = loader.load()

        assertNotNull(metadata)
        val step = metadata.steps["myInternalStep"]
        assertNotNull(step)
        assertEquals("internal-plugin", step.plugin)
        assertEquals("Our internal deployment step", step.documentation)
        assertTrue(step.parameters.containsKey("environment"))
        assertTrue(step.parameters["environment"]!!.required == true)
    }

    @Test
    fun `user metadata overrides bundled`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)

        // Override the sh step with custom config
        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "metadataOverrides": {
                    "sh": {
                        "plugin": "user-override",
                        "documentation": "User overridden sh step",
                        "parameters": {
                            "customParam": { "type": "String", "required": true }
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val userMetadata = loader.load()
        assertNotNull(userMetadata)

        val bundled = BundledJenkinsMetadataLoader().load()
        val merged = MetadataMerger.mergeWithPriority(
            bundled = bundled,
            stable = StableStepDefinitions.all(),
            userOverrides = userMetadata.steps,
        )

        val sh = merged.steps["sh"]
        assertNotNull(sh)
        assertEquals("workflow-durable-task-step", sh.plugin)
        assertEquals("User overridden sh step", sh.documentation)
        val candidate = assertNotNull(
            merged.typedCatalogue?.unique(
                com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind.EXECUTABLE_STEP,
                "sh",
            ),
        )
        assertTrue(candidate.contributors.any { it.legacyPlugin == "user-override" })
    }

    @Test
    fun `loads jenkinsVersion from config`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)

        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "jenkinsVersion": "2.479.3"
            }
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val config = loader.loadConfig()

        assertNotNull(config)
        assertEquals("2.479.3", config.jenkinsVersion)
    }

    @Test
    fun `handles malformed config gracefully`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)

        Files.writeString(
            configDir.resolve("jenkins.json"),
            "{ invalid json",
        )

        val loader = UserMetadataLoader(tempDir)
        val config = loader.loadConfig()

        // Should return null or default, not throw
        assertNull(config)
    }

    @Test
    fun `handles missing GDSL file gracefully`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)

        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "gdslFile": "nonexistent.gdsl"
            }
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val metadata = loader.load()

        // Should return metadata without the GDSL steps (or null)
        // Not throw an exception
    }

    @Test
    fun `loads plugin configuration`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)

        Files.writeString(
            configDir.resolve("jenkins.json"),
            """
            {
                "plugins": {
                    "workflow-durable-task-step": "1464.v2d3f5c68f84c",
                    "custom-plugin": "1.0.0"
                }
            }
            """.trimIndent(),
        )

        val loader = UserMetadataLoader(tempDir)
        val config = loader.loadConfig()

        assertNotNull(config)
        assertEquals(2, config.plugins.size)
        assertEquals("1464.v2d3f5c68f84c", config.plugins["workflow-durable-task-step"])
    }

    @Test
    fun `a user GDSL file larger than the GDSL size limit is not read`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)
        Files.writeString(configDir.resolve("jenkins.json"), """{ "gdslFile": "huge.gdsl" }""")
        val huge = tempDir.resolve("huge.gdsl")
        Files.writeString(huge, "contributor(context()) { method(name: 'myCustomStep', type: 'Object') }\n")
        java.io.RandomAccessFile(huge.toFile(), "rw").use {
            it.setLength(com.github.albertocavalcante.gvy.gdsl.GdslExecutor.MAX_SOURCE_SIZE + 1L)
        }

        assertNull(UserMetadataLoader(tempDir).load())
    }

    @Test
    fun `legacy partial override retains target fields and records raw plugin contribution`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)
        Files.writeString(
            configDir.resolve("jenkins.json"),
            """{"metadataOverrides":{"sh":{"plugin":"user-override",""" +
                """"parameters":{"customParam":{"type":"String","required":false}}}}}""",
        )
        val override = assertNotNull(UserMetadataLoader(tempDir).load()).steps
        val bundled = VersionedMetadataLoader().load()
        val merged = MetadataMerger.mergeWithEnrichment(
            MetadataMerger.mergeWithPriority(bundled, emptyMap(), override),
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        val sh = assertNotNull(merged.getStep("sh"))
        assertTrue("script" in sh.namedParams)
        assertEquals(false, sh.namedParams.getValue("customParam").required)
        val canonical = assertNotNull(
            merged.catalogue.unique(
                com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind.EXECUTABLE_STEP,
                "sh",
            ),
        )
        assertEquals(sh.plugin, canonical.ownerPlugin)
        assertTrue(canonical.contributors.any { it.legacyPlugin == "user-override" })
    }

    @Test
    fun `legacy override distinguishes absent false and null requiredness`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)
        Files.writeString(
            configDir.resolve("jenkins.json"),
            """{"metadataOverrides":{"sample":{"plugin":"input-label","parameters":{
                "preserved":{"type":"String"},
                "optional":{"type":"String","required":false},
                "unknown":{"type":"String","required":null}
            }}}}""",
        )
        val overrides = assertNotNull(UserMetadataLoader(tempDir).load()).steps
        val params = listOf("preserved", "optional", "unknown").associateWith {
            com.github.albertocavalcante.gvy.jenkins.metadata.StepParameter(it, "String", required = true)
        }
        val source = com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata(
            name = "sample",
            plugin = "owner",
            parameters = params,
            ownerPlugin = "owner",
            ownerEvidence = com.github.albertocavalcante.gvy.jenkins.metadata.OwnerEvidence.CURATED,
        )
        val bundled = com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata(
            steps = mapOf("sample" to source),
            globalVariables = emptyMap(),
        )
        val result = MetadataMerger.mergeWithEnrichment(
            MetadataMerger.mergeWithPriority(bundled, emptyMap(), overrides),
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        val merged = assertNotNull(result.getStep("sample")).namedParams
        assertEquals(true, merged.getValue("preserved").required)
        assertEquals(false, merged.getValue("optional").required)
        assertEquals(null, merged.getValue("unknown").required)
        assertEquals("owner", result.getStep("sample")?.plugin)
    }

    @Test
    fun `an overload-only GDSL file preserves both candidates`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)
        Files.writeString(configDir.resolve("jenkins.json"), """{"gdslFile":"overloads.gdsl"}""")
        Files.writeString(
            tempDir.resolve("overloads.gdsl"),
            """
            contributor(context()) {
                method(name: 'deploy', type: 'String', params: [name: 'String'])
                method(name: 'deploy', type: 'Integer', params: [id: 'int'])
            }
            """.trimIndent(),
        )
        val metadata = assertNotNull(UserMetadataLoader(tempDir).load())
        assertNull(metadata.steps["deploy"])
        val candidates = assertNotNull(metadata.typedCatalogue)
            .candidates(com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind.EXECUTABLE_STEP, "deploy")
        assertEquals(2, candidates.size)
        assertEquals(setOf("String", "Integer"), candidates.map { it.returnType }.toSet())
    }

    @Test
    fun `one GDSL method remains one candidate through loader merge and context`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)
        Files.writeString(configDir.resolve("jenkins.json"), """{"gdslFile":"one.gdsl"}""")
        Files.writeString(
            tempDir.resolve("one.gdsl"),
            "contributor(context()) { method(name: 'deploy', type: 'String', params: [name: 'String']) }",
        )
        val loaded = assertNotNull(UserMetadataLoader(tempDir).load())
        val merged = MetadataMerger.mergeWithEnrichment(
            loaded,
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        val context = com.github.albertocavalcante.gvy.jenkins.JenkinsContext(
            configuration = com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration(),
            workspaceRoot = tempDir,
            pluginManager = com.github.albertocavalcante.gvy.jenkins.JenkinsPluginManager(),
            bundledMetadataLoader = { merged },
            classpathScanner = {
                com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata(
                    steps = emptyMap(),
                    globalVariables = emptyMap(),
                )
            },
        )
        val actual = context.getAllMetadata(null)
        val candidates = actual.catalogue.candidates(
            com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind.EXECUTABLE_STEP,
            "deploy",
        )
        assertEquals(1, candidates.size)
        assertNotNull(actual.getStep("deploy"))
        assertEquals("String", candidates.single().returnType)
    }

    @Test
    fun `legacy override patches a single GDSL method without duplicating it`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)
        Files.writeString(
            configDir.resolve("jenkins.json"),
            """{"gdslFile":"one.gdsl","metadataOverrides":{"deploy":{"plugin":"user-label",""" +
                """"documentation":"override"}}}""",
        )
        Files.writeString(
            tempDir.resolve("one.gdsl"),
            "contributor(context()) { method(name: 'deploy', type: 'String', params: [name: 'String']) }",
        )
        val loaded = assertNotNull(UserMetadataLoader(tempDir).load())
        val merged = MetadataMerger.mergeWithEnrichment(
            loaded,
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        val candidates = merged.catalogue.candidates(
            com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind.EXECUTABLE_STEP,
            "deploy",
        )
        assertEquals(1, candidates.size)
        assertEquals("override", candidates.single().documentation)
        assertTrue(candidates.single().contributors.any { it.legacyPlugin == "user-label" })
        assertEquals("override", merged.getStep("deploy")?.documentation)
    }

    @Test
    fun `GDSL plus stable metadata retains enrichment for other step names`() {
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)
        Files.writeString(configDir.resolve("jenkins.json"), """{"gdslFile":"one.gdsl"}""")
        Files.writeString(tempDir.resolve("one.gdsl"), "contributor(context()) { method(name: 'deploy') }")
        val loaded = assertNotNull(UserMetadataLoader(tempDir).load())
        val merged = MetadataMerger.mergeWithEnrichment(
            MetadataMerger.mergeAll(loaded, stable = StableStepDefinitions.all()),
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.EnrichmentMetadataLoader().load(),
        )
        val timeout = assertNotNull(merged.getStep("timeout"))
        assertTrue(timeout.namedParams.getValue("unit").validValues.orEmpty().contains("MINUTES"))
    }
}
