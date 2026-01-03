package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.metadata.MergedDeclarativeOption
import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.MergedParameter
import com.github.albertocavalcante.groovyjenkins.metadata.MergedStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.StepScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD: Tests for JenkinsStepCompletionProvider - parameter sorting and completion behavior.
 */
class JenkinsStepCompletionProviderTest {

    private fun createTestMetadata(): MergedJenkinsMetadata {
        val shStep = MergedStepMetadata(
            name = "sh",
            scope = StepScope.GLOBAL,
            positionalParams = emptyList(),
            namedParams = mapOf(
                "script" to MergedParameter(
                    name = "script",
                    type = "String",
                    defaultValue = null,
                    description = "Shell script to execute",
                    required = true,
                    validValues = null,
                    examples = emptyList(),
                ),
                "returnStdout" to MergedParameter(
                    name = "returnStdout",
                    type = "boolean",
                    defaultValue = "false",
                    description = "Return stdout as string",
                    required = false,
                    validValues = null,
                    examples = emptyList(),
                ),
                "returnStatus" to MergedParameter(
                    name = "returnStatus",
                    type = "boolean",
                    defaultValue = "false",
                    description = "Return exit status",
                    required = false,
                    validValues = null,
                    examples = emptyList(),
                ),
            ),
            extractedDocumentation = "Execute shell script",
            returnType = null,
            plugin = "workflow-durable-task-step",
            enrichedDescription = null,
            documentationUrl = null,
            category = null,
            examples = emptyList(),
            deprecation = null,
        )

        return MergedJenkinsMetadata(
            jenkinsVersion = "2.426.3",
            steps = mapOf("sh" to shStep),
            globalVariables = emptyMap(),
            sections = emptyMap(),
            directives = emptyMap(),
        )
    }

    private fun createOptionMetadata(): MergedJenkinsMetadata {
        val disableConcurrentBuilds = MergedDeclarativeOption(
            name = "disableConcurrentBuilds",
            plugin = "workflow-job",
            parameters = mapOf(
                "abortPrevious" to MergedParameter(
                    name = "abortPrevious",
                    type = "boolean",
                    defaultValue = "false",
                    description = "Abort any currently running builds when a new one starts",
                    required = false,
                    validValues = null,
                    examples = emptyList(),
                ),
            ),
            documentation = "Disallow concurrent executions of the Pipeline.",
        )

        return MergedJenkinsMetadata(
            jenkinsVersion = "2.426.3",
            steps = emptyMap(),
            globalVariables = emptyMap(),
            sections = emptyMap(),
            directives = emptyMap(),
            declarativeOptions = mapOf("disableConcurrentBuilds" to disableConcurrentBuilds),
        )
    }

    @Test
    fun `required parameters should have sortText starting with 0`() {
        val metadata = createTestMetadata()
        val completions = JenkinsStepCompletionProvider.getParameterCompletions(
            stepName = "sh",
            existingKeys = emptySet(),
            metadata = metadata,
        )

        val scriptCompletion = completions.find { it.label == "script:" }
        assertNotNull(scriptCompletion, "Should have script parameter")
        assertNotNull(scriptCompletion.sortText, "Required param should have sortText")
        assertTrue(
            scriptCompletion.sortText!!.startsWith("0"),
            "Required param sortText should start with 0, got: ${scriptCompletion.sortText}",
        )
    }

    @Test
    fun `optional parameters should have sortText starting with 1`() {
        val metadata = createTestMetadata()
        val completions = JenkinsStepCompletionProvider.getParameterCompletions(
            stepName = "sh",
            existingKeys = emptySet(),
            metadata = metadata,
        )

        val returnStdoutCompletion = completions.find { it.label == "returnStdout:" }
        assertNotNull(returnStdoutCompletion, "Should have returnStdout parameter")
        assertNotNull(returnStdoutCompletion.sortText, "Optional param should have sortText")
        assertTrue(
            returnStdoutCompletion.sortText!!.startsWith("1"),
            "Optional param sortText should start with 1, got: ${returnStdoutCompletion.sortText}",
        )
    }

    @Test
    fun `required parameters should sort before optional when sorted by sortText`() {
        val metadata = createTestMetadata()
        val completions = JenkinsStepCompletionProvider.getParameterCompletions(
            stepName = "sh",
            existingKeys = emptySet(),
            metadata = metadata,
        )

        // Sort by sortText (simulating what LSP client does)
        val sorted = completions.sortedBy { it.sortText }

        // First should be required (script)
        assertEquals("script:", sorted.first().label, "Required 'script' should be first after sorting")

        // Required should come before all optionals
        val requiredIndices = sorted.mapIndexedNotNull { i, c -> if (c.sortText?.startsWith("0") == true) i else null }
        val optionalIndices = sorted.mapIndexedNotNull { i, c -> if (c.sortText?.startsWith("1") == true) i else null }

        if (requiredIndices.isNotEmpty() && optionalIndices.isNotEmpty()) {
            assertTrue(
                requiredIndices.max() < optionalIndices.min(),
                "All required params should come before all optional params",
            )
        }
    }

    @Test
    fun `step completions should include plugin info in detail`() {
        val metadata = createTestMetadata()
        val completions = JenkinsStepCompletionProvider.getStepCompletions(metadata)

        val shCompletion = completions.find { it.label == "sh" }
        assertNotNull(shCompletion, "Should have sh step")
        assertTrue(
            shCompletion.detail?.contains("workflow-durable-task-step") == true,
            "Detail should include plugin name",
        )
    }

    @Test
    fun `parameter completions should filter out existing keys`() {
        val metadata = createTestMetadata()
        val completions = JenkinsStepCompletionProvider.getParameterCompletions(
            stepName = "sh",
            existingKeys = setOf("script"),
            metadata = metadata,
        )

        val scriptCompletion = completions.find { it.label == "script:" }
        assertEquals(null, scriptCompletion, "script should be filtered out since it already exists")

        // But other params should still be present
        val returnStdoutCompletion = completions.find { it.label == "returnStdout:" }
        assertNotNull(returnStdoutCompletion, "returnStdout should still be present")
    }

    @Test
    fun `declarative option parameters should be suggested when step is missing`() {
        val metadata = createOptionMetadata()
        val completions = JenkinsStepCompletionProvider.getParameterCompletions(
            stepName = "disableConcurrentBuilds",
            existingKeys = emptySet(),
            metadata = metadata,
        )

        val abortPrevious = completions.find { it.label == "abortPrevious:" }
        assertNotNull(abortPrevious, "Should have abortPrevious parameter for disableConcurrentBuilds")
        assertEquals("boolean", abortPrevious.detail)
    }

    @Test
    fun `declarative option parameters should filter existing keys`() {
        val metadata = createOptionMetadata()
        val completions = JenkinsStepCompletionProvider.getParameterCompletions(
            stepName = "disableConcurrentBuilds",
            existingKeys = setOf("abortPrevious"),
            metadata = metadata,
        )

        val abortPrevious = completions.find { it.label == "abortPrevious:" }
        assertEquals(null, abortPrevious, "abortPrevious should be filtered out when already present")
    }

    @Test
    fun `parameter completions should be empty for unknown step or option`() {
        val metadata = createOptionMetadata()
        val completions = JenkinsStepCompletionProvider.getParameterCompletions(
            stepName = "unknownStep",
            existingKeys = emptySet(),
            metadata = metadata,
        )

        assertTrue(completions.isEmpty(), "Unknown step/option should return no completions")
    }

    @Test
    fun `command expression parameter completions include comma prefix`() {
        val metadata = createTestMetadata()
        val completions = JenkinsStepCompletionProvider.getParameterCompletions(
            stepName = "sh",
            existingKeys = emptySet(),
            metadata = metadata,
            useCommandExpression = true,
        )

        val returnStatusCompletion = completions.find { it.label == "returnStatus:" }
        assertNotNull(returnStatusCompletion, "returnStatus parameter should still be suggested")
        assertTrue(
            returnStatusCompletion.insertText?.startsWith(", returnStatus:") == true,
            "Command expression snippet should start with a comma prefix",
        )
    }
}
