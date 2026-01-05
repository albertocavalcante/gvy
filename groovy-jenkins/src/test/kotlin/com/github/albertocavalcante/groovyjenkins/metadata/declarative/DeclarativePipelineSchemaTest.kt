package com.github.albertocavalcante.groovyjenkins.metadata.declarative

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeclarativePipelineSchemaTest {
    private val schema = DeclarativePipelineSchema

    @Test
    fun `agent block exposes agent type completions`() {
        val categories = schema.getCompletionCategories("agent")
        assertTrue(categories.contains(DeclarativePipelineSchema.CompletionCategory.AGENT_TYPE))
        assertTrue(schema.getInnerInstructions("agent").contains("docker"))
    }

    @Test
    fun `options block exposes declarative option completions`() {
        val categories = schema.getCompletionCategories("options")
        assertEquals(setOf(DeclarativePipelineSchema.CompletionCategory.DECLARATIVE_OPTION), categories)
    }

    @Test
    fun `steps block exposes pipeline steps`() {
        val categories = schema.getCompletionCategories("steps")
        assertEquals(setOf(DeclarativePipelineSchema.CompletionCategory.STEP), categories)
    }

    @Test
    fun `post block exposes only post condition completions`() {
        val categories = schema.getCompletionCategories("post")
        assertEquals(setOf(DeclarativePipelineSchema.CompletionCategory.POST_CONDITION), categories)
        assertTrue(schema.getInnerInstructions("post").contains("always"))
    }

    @Test
    fun `schema exposes version information`() {
        assertEquals("1.0.0", schema.schemaVersion)
        assertEquals("2.2214.v00573e73ddf1", schema.sourcePluginVersion)
        assertEquals("2.479", schema.jenkinsBaseline)
    }

    @Test
    fun `when block contains added conditions`() {
        val instructions = schema.getInnerInstructions("when")
        assertTrue(instructions.contains("equals"))
        assertTrue(instructions.contains("triggeredBy"))
        assertTrue(instructions.contains("isRestartedRun"))
        assertTrue(instructions.contains("beforeInput"))
        assertTrue(instructions.contains("beforeOptions"))
    }

    @Test
    fun `unknown block returns empty categories`() {
        assertTrue(schema.getCompletionCategories("not-a-block").isEmpty())
    }
}
