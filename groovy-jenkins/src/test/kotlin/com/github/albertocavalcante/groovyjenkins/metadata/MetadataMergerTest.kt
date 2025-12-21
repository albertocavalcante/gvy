package com.github.albertocavalcante.groovyjenkins.metadata

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for MetadataMerger - merges multiple metadata sources with priority ordering.
 *
 * Priority order (highest to lowest):
 * 1. User overrides
 * 2. Dynamic classpath scan
 * 3. Stable step definitions
 * 4. Versioned metadata
 * 5. Bundled metadata (fallback)
 */
class MetadataMergerTest {

    @Test
    fun `stable steps override bundled when available`() {
        val bundled = createBundledMetadata(
            steps = mapOf(
                "sh" to JenkinsStepMetadata(
                    name = "sh",
                    plugin = "bundled-plugin",
                    parameters = mapOf(
                        "script" to StepParameter(
                            name = "script",
                            type = "String",
                            required = true,
                        ),
                    ),
                    documentation = "Bundled sh",
                ),
            ),
        )

        val merged = MetadataMerger.merge(bundled, StableStepDefinitions.all())

        val sh = merged.steps["sh"]
        assertNotNull(sh)
        // Stable should have more parameters than bundled
        assertTrue(sh.parameters.size > 1, "Stable sh should have more parameters")
        assertTrue(sh.parameters.containsKey("returnStdout"))
        assertEquals("workflow-durable-task-step", sh.plugin)
    }

    @Test
    fun `bundled steps are preserved when not in stable`() {
        val bundled = createBundledMetadata(
            steps = mapOf(
                "customStep" to JenkinsStepMetadata(
                    name = "customStep",
                    plugin = "custom-plugin",
                    parameters = mapOf(
                        "param1" to StepParameter(
                            name = "param1",
                            type = "String",
                            required = true,
                        ),
                    ),
                    documentation = "Custom step from bundled",
                ),
            ),
        )

        val merged = MetadataMerger.merge(bundled, StableStepDefinitions.all())

        val customStep = merged.steps["customStep"]
        assertNotNull(customStep)
        assertEquals("custom-plugin", customStep.plugin)
        assertEquals("Custom step from bundled", customStep.documentation)
    }

    @Test
    fun `merges stable and bundled steps together`() {
        val bundled = createBundledMetadata(
            steps = mapOf(
                "customStep" to JenkinsStepMetadata(
                    name = "customStep",
                    plugin = "custom-plugin",
                    parameters = emptyMap(),
                    documentation = "Custom step",
                ),
            ),
        )

        val merged = MetadataMerger.merge(bundled, StableStepDefinitions.all())

        // Should have both stable and bundled steps
        assertTrue(merged.steps.containsKey("sh"), "Should have stable step 'sh'")
        assertTrue(merged.steps.containsKey("echo"), "Should have stable step 'echo'")
        assertTrue(merged.steps.containsKey("customStep"), "Should have bundled step 'customStep'")
    }

    @Test
    fun `empty bundled metadata still includes stable steps`() {
        val bundled = createBundledMetadata(steps = emptyMap())

        val merged = MetadataMerger.merge(bundled, StableStepDefinitions.all())

        assertTrue(merged.steps.isNotEmpty())
        assertTrue(merged.steps.containsKey("sh"))
        assertTrue(merged.steps.containsKey("echo"))
    }

    @Test
    fun `merge with user overrides takes highest priority`() {
        val bundled = createBundledMetadata(
            steps = mapOf(
                "sh" to JenkinsStepMetadata(
                    name = "sh",
                    plugin = "bundled-plugin",
                    parameters = emptyMap(),
                    documentation = "Bundled sh",
                ),
            ),
        )

        val userOverrides = mapOf(
            "sh" to JenkinsStepMetadata(
                name = "sh",
                plugin = "user-override",
                parameters = mapOf(
                    "customParam" to StepParameter(
                        name = "customParam",
                        type = "String",
                        required = true,
                    ),
                ),
                documentation = "User override sh",
            ),
        )

        val merged = MetadataMerger.mergeWithPriority(
            bundled = bundled,
            stable = StableStepDefinitions.all(),
            userOverrides = userOverrides,
        )

        val sh = merged.steps["sh"]
        assertNotNull(sh)
        assertEquals("user-override", sh.plugin)
        assertEquals("User override sh", sh.documentation)
        assertTrue(sh.parameters.containsKey("customParam"))
    }

    @Test
    fun `preserves global variables from bundled`() {
        val bundled = createBundledMetadata(
            globalVariables = mapOf(
                "env" to GlobalVariableMetadata(
                    name = "env",
                    type = "EnvActionImpl",
                    documentation = "Environment variables",
                ),
                "params" to GlobalVariableMetadata(
                    name = "params",
                    type = "ParamsVariable",
                    documentation = "Build parameters",
                ),
            ),
        )

        val merged = MetadataMerger.merge(bundled, StableStepDefinitions.all())

        assertEquals(2, merged.globalVariables.size)
        assertTrue(merged.globalVariables.containsKey("env"))
        assertTrue(merged.globalVariables.containsKey("params"))
    }

    @Test
    fun `preserves post conditions from bundled`() {
        val bundled = createBundledMetadata(
            postConditions = mapOf(
                "always" to PostConditionMetadata(
                    name = "always",
                    description = "Run regardless of result",
                    executionOrder = 1,
                ),
                "success" to PostConditionMetadata(
                    name = "success",
                    description = "Run on success",
                    executionOrder = 2,
                ),
            ),
        )

        val merged = MetadataMerger.merge(bundled, StableStepDefinitions.all())

        assertEquals(2, merged.postConditions.size)
        assertTrue(merged.postConditions.containsKey("always"))
        assertTrue(merged.postConditions.containsKey("success"))
    }

    @Test
    fun `preserves declarative options from bundled`() {
        val bundled = createBundledMetadata(
            declarativeOptions = mapOf(
                "timestamps" to DeclarativeOptionMetadata(
                    name = "timestamps",
                    plugin = "timestamper",
                    parameters = emptyMap(),
                    documentation = "Add timestamps to console output",
                ),
            ),
        )

        val merged = MetadataMerger.merge(bundled, StableStepDefinitions.all())

        assertEquals(1, merged.declarativeOptions.size)
        assertTrue(merged.declarativeOptions.containsKey("timestamps"))
    }

    @Test
    fun `preserves agent types from bundled`() {
        val bundled = createBundledMetadata(
            agentTypes = mapOf(
                "any" to AgentTypeMetadata(
                    name = "any",
                    parameters = emptyMap(),
                    documentation = "Run on any available agent",
                ),
                "docker" to AgentTypeMetadata(
                    name = "docker",
                    parameters = mapOf(
                        "image" to StepParameter(
                            name = "image",
                            type = "String",
                            required = true,
                        ),
                    ),
                    documentation = "Run in Docker container",
                ),
            ),
        )

        val merged = MetadataMerger.merge(bundled, StableStepDefinitions.all())

        assertEquals(2, merged.agentTypes.size)
        assertTrue(merged.agentTypes.containsKey("any"))
        assertTrue(merged.agentTypes.containsKey("docker"))
    }

    // Helper to create test metadata
    private fun createBundledMetadata(
        steps: Map<String, JenkinsStepMetadata> = emptyMap(),
        globalVariables: Map<String, GlobalVariableMetadata> = emptyMap(),
        postConditions: Map<String, PostConditionMetadata> = emptyMap(),
        declarativeOptions: Map<String, DeclarativeOptionMetadata> = emptyMap(),
        agentTypes: Map<String, AgentTypeMetadata> = emptyMap(),
    ) = BundledJenkinsMetadata(
        steps = steps,
        globalVariables = globalVariables,
        postConditions = postConditions,
        declarativeOptions = declarativeOptions,
        agentTypes = agentTypes,
    )
}
