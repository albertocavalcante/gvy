package com.github.albertocavalcante.gvy.jenkins.metadata.declarative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `a stage can hold sequential stages`() {
        assertTrue("stages" in schema.getInnerInstructions("stage"), "stage { stages { stage('a') {} } } is valid")
    }

    @Test
    fun `a matrix holds stages, not a bare stage`() {
        val instructions = schema.getInnerInstructions("matrix")

        assertTrue("stages" in instructions, "matrix { axes {} stages { stage('x') {} } }")
        assertTrue("stage" !in instructions, "a stage inside matrix sits under stages")
        assertTrue("axes" in instructions)
    }

    @Test
    fun `a matrix offers no steps, only its sections and directives`() {
        assertTrue(DeclarativePipelineSchema.CompletionCategory.STEP !in schema.getCompletionCategories("matrix"))
        val instructions = schema.getInnerInstructions("matrix")
        listOf("axes", "excludes", "stages", "agent", "environment", "options", "post", "tools", "when").forEach {
            assertTrue(it in instructions, "matrix accepts $it: $instructions")
        }
    }

    @Test
    fun `a stage is allowed in stages and parallel, not directly in matrix`() {
        val resource = requireNotNull(javaClass.classLoader.getResource("schemas/declarative-pipeline-schema.json"))
        val stage = Json.parseToJsonElement(resource.readText()).jsonObject.getValue("sections").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "stage" }
        val allowedIn = stage.getValue("allowedIn").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("stages", "parallel"), allowedIn, "a stage inside matrix sits under its stages")
    }
}
