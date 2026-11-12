// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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

    /** `allowedIn` of every section and directive in a bundled JSON resource. */
    private fun allowedIn(resource: String, blocksAreArrays: Boolean): Map<String, List<String>> {
        val text = requireNotNull(javaClass.classLoader.getResource(resource)).readText()
        val root = Json.parseToJsonElement(text).jsonObject
        return listOf("sections", "directives").flatMap { kind ->
            val blocks = root.getValue(kind)
            if (blocksAreArrays) {
                blocks.jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject }
            } else {
                blocks.jsonObject.entries.map { (name, block) -> name to block.jsonObject }
            }
        }.associate { (name, block) ->
            name to (block["allowedIn"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
        }
    }

    private val schemaAllowedIn get() = allowedIn("schemas/declarative-pipeline-schema.json", blocksAreArrays = true)

    @Test
    fun `a stage is allowed in stages and parallel, not directly in matrix`() {
        assertEquals(
            listOf("stages", "parallel"),
            schemaAllowedIn["stage"],
            "a stage inside matrix sits under its stages",
        )
    }

    @Test
    fun `steps, stages and parallel are allowed only where Jenkins allows them`() {
        assertEquals(listOf("stage"), schemaAllowedIn["steps"])
        assertEquals(listOf("pipeline", "stage", "matrix"), schemaAllowedIn["stages"])
        assertEquals(listOf("stage"), schemaAllowedIn["parallel"])
    }

    @Test
    fun `allowedIn agrees with the enrichment that drives hover`() {
        val enrichment = allowedIn("jenkins-enrichment.json", blocksAreArrays = false)
        val schema = schemaAllowedIn

        enrichment.filterValues { it.isNotEmpty() }.forEach { (name, allowed) ->
            assertEquals(allowed, schema[name], "allowedIn of $name")
        }
    }

    @Test
    fun `a stage offers input, and input and excludes are schema blocks`() {
        assertTrue("input" in schema.getInnerInstructions("stage"))
        assertTrue(schema.containsBlock("input") && "parameters" in schema.getInnerInstructions("input"))
        assertTrue(schema.containsBlock("excludes") && "exclude" in schema.getInnerInstructions("excludes"))
    }
}
