package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ScenarioParserTest {

    private val parser = JacksonScenarioParser()

    @Test
    fun `workspace files map is parsed in declaration order`() {
        val definition = parser.parseScenarioDefinition(
            """
            name: inline
            workspace:
              fixture: empty-workspace
              files:
                src/b/Greeter.groovy: |
                  class Greeter {}
                src/a/Main.groovy: |
                  new Greeter()
            steps:
              - initialize: {}
            """.trimIndent(),
        )

        val workspace = definition.scenario.workspace!!
        assertThat(workspace.fixture).isEqualTo("empty-workspace")
        assertThat(workspace.files.keys).containsExactly("src/b/Greeter.groovy", "src/a/Main.groovy")
        assertThat(workspace.files["src/a/Main.groovy"]).isEqualTo("new Greeter()\n")
    }

    @Test
    fun `workspace without files defaults to an empty map`() {
        val definition = parser.parseScenarioDefinition(
            """
            name: fixture-only
            workspace:
              fixture: empty-workspace
            steps: []
            """.trimIndent(),
        )

        assertThat(definition.scenario.workspace!!.files).isEmpty()
    }

    @Test
    fun `duplicate keys are rejected`() {
        assertThatThrownBy {
            parser.parseScenarioDefinition(
                """
                name: dup
                steps:
                  - completion:
                      path: a.groovy
                      line: 1
                      line: 2
                """.trimIndent(),
            )
        }.isInstanceOf(ScenarioParseException::class.java)
            .hasStackTraceContaining("Duplicate field 'line'")
    }

    @Test
    fun `duplicate inline file paths are rejected`() {
        assertThatThrownBy {
            parser.parseScenarioDefinition(
                """
                name: dup-files
                workspace:
                  files:
                    A.groovy: "class A {}"
                    A.groovy: "class B {}"
                steps: []
                """.trimIndent(),
            )
        }.isInstanceOf(ScenarioParseException::class.java)
            .hasStackTraceContaining("Duplicate field 'A.groovy'")
    }

    @Test
    fun `unknown keys in workspace are rejected`() {
        assertThatThrownBy {
            parser.parseScenarioDefinition(
                """
                name: typo
                workspace:
                  fixture: empty-workspace
                  file:
                    A.groovy: "class A {}"
                steps: []
                """.trimIndent(),
            )
        }.isInstanceOf(ScenarioParseException::class.java)
            .hasStackTraceContaining("\"file\"")
    }

    @Test
    fun `unknown keys in steps are rejected`() {
        assertThatThrownBy {
            parser.parseScenarioDefinition(
                """
                name: typo
                steps:
                  - completion:
                      path: a.groovy
                      postion: "{{cursor}}"
                """.trimIndent(),
            )
        }.isInstanceOf(ScenarioParseException::class.java)
            .hasStackTraceContaining("\"postion\"")
    }

    @Test
    fun `completion and rename accept a position`() {
        val definition = parser.parseScenarioDefinition(
            """
            name: positions
            steps:
              - completion:
                  path: a.groovy
                  position: "{{cursor.dot}}"
              - rename:
                  path: a.groovy
                  position: { line: 3, character: 4 }
                  newName: renamed
            """.trimIndent(),
        )

        val (completion, rename) = definition.scenario.steps
        assertThat((completion as ScenarioStep.Completion).position).isEqualTo(JsonPrimitive("{{cursor.dot}}"))
        assertThat((rename as ScenarioStep.Rename).position).isEqualTo(
            buildJsonObject {
                put("line", 3)
                put("character", 4)
            },
        )
    }
}
