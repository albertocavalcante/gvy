// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MetadataProvenance
import com.github.albertocavalcante.gvy.jenkins.metadata.OwnerEvidence
import com.github.albertocavalcante.gvy.jenkins.metadata.SourceKind
import com.github.albertocavalcante.gvy.jenkins.metadata.StepParameter
import com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind
import com.github.albertocavalcante.gvy.jenkins.metadata.VersionedMetadataLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JenkinsContextMergingTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `unknown scanner owner remains distinct from bundled metadata`() {
        // 2. Define a dynamic step for "sh" (which exists in StableStepDefinitions)
        // Stable "sh" has parameters like "script", "returnStdout", etc.
        // Our dynamic version only defines "script" and a NEW "customDynamicParam".
        // It notably MISSES "returnStdout".
        val dynamicSh = JenkinsStepMetadata(
            name = "sh",
            plugin = "workflow-durable-task",
            parameters = mapOf(
                "script" to StepParameter("script", "String", required = true), // Overrides stable
                "customDynamicParam" to StepParameter("customDynamicParam", "String", documentation = "Dynamic param"),
            ),
            documentation = "Dynamic docs",
        )

        val dynamicMetadata = BundledJenkinsMetadata(
            steps = mapOf("sh" to dynamicSh),
            globalVariables = emptyMap(),
            postConditions = emptyMap(),
            declarativeOptions = emptyMap(),
            agentTypes = emptyMap(),
        )

        // 3. Publish the dynamic metadata through a classpath scan
        val context = JenkinsContext(
            configuration = JenkinsConfiguration(),
            workspaceRoot = tempDir,
            pluginManager = JenkinsPluginManager(),
            bundledMetadataLoader = { VersionedMetadataLoader().loadMerged() },
            classpathScanner = { dynamicMetadata },
        )
        val classpath = listOf(tempDir.resolve("workflow-durable-task-step.jar"))
        context.scanClasspath(classpath)

        // 4. Act
        val result = context.getAllMetadata(classpath)
        val mergedSh = result.steps["sh"]

        // The scanner label is approximate and differs from the bundled owner. It must not
        // be treated as proof that the two records are one symbol.
        assertEquals(null, mergedSh)
        val candidates = result.catalogue.symbols.filter { it.kind == SymbolKind.EXECUTABLE_STEP && it.name == "sh" }
        assertEquals(2, candidates.size)
    }

    @Test
    fun `matching evidenced owner preserves legacy and contributed parameters`() {
        val dynamic = JenkinsStepMetadata(
            name = "sh",
            plugin = "workflow-durable-task-step",
            ownerPlugin = "workflow-durable-task-step",
            ownerEvidence = OwnerEvidence.CURATED,
            provenance = MetadataProvenance(SourceKind.CURATED, plugin = "workflow-durable-task-step"),
            parameters = mapOf("extra" to StepParameter("extra", "String")),
        )
        val context = JenkinsContext(
            configuration = JenkinsConfiguration(),
            workspaceRoot = tempDir,
            pluginManager = JenkinsPluginManager(),
            bundledMetadataLoader = { VersionedMetadataLoader().loadMerged() },
            classpathScanner = { BundledJenkinsMetadata(steps = mapOf("sh" to dynamic), globalVariables = emptyMap()) },
        )
        val classpath = listOf(tempDir.resolve("owner-evidence.jar"))
        context.scanClasspath(classpath)
        val metadata = context.getAllMetadata(classpath)
        val sh = assertNotNull(metadata.getStep("sh"))
        assertTrue("script" in sh.namedParams)
        assertTrue("extra" in sh.namedParams)
        assertEquals(1, metadata.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "sh").size)
    }
}
