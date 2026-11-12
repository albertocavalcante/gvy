// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.StepParameter
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
    fun `getAllMetadata should merge stable parameters when overlaying dynamic metadata`() {
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

        // 5. Assert
        assertNotNull(mergedSh, "Should resolve 'sh' step")

        // Dynamic overrides
        assertEquals("Dynamic docs", mergedSh.extractedDocumentation, "Should use dynamic documentation")

        // Check Parameters
        val params = mergedSh.namedParams
        assertTrue(params.containsKey("script"), "Should contain 'script'")
        assertTrue(params.containsKey("customDynamicParam"), "Should contain 'customDynamicParam'")

        // CRITICAL CHECK: Does it assume "returnStatus" or "returnStdout" from stable/bundled definitions?
        // Note: StableStepDefinitions for "sh" usually includes "returnStdout".
        // We verify that this parameter is PRESERVED even though dynamicSh didn't have it.
        assertTrue(params.containsKey("returnStdout"), "Should preserve 'returnStdout' from stable definitions")
        assertTrue(params.containsKey("returnStatus"), "Should preserve 'returnStatus' from stable definitions")
    }
}
