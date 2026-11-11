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

    private fun snapshotScenario(expectLines: List<String>) = (
        listOf(
            "name: snap",
            "steps:",
            "  - assert:",
            "      source: r",
            "      checks:",
            "        - jsonPath: \$.a",
            "          expect:",
        ) + expectLines.map { "            $it" }
        ).joinToString("\n")

    private fun parsedExpectation(expect: String): JsonExpectation {
        val step = parser.parseScenarioDefinition(snapshotScenario(expect.lines())).scenario.steps.single()
        return (step as ScenarioStep.Assert).checks.single().expect
    }

    @Test
    fun `snapshot expectation is parsed`() {
        val expectation = parsedExpectation("snapshot: hover/println.md")

        assertThat(expectation.snapshot).isEqualTo("hover/println.md")
    }

    @Test
    fun `unknown key next to a snapshot is rejected`() {
        assertThatThrownBy { parsedExpectation("snapshto: x\nsnapshot: a.md") }
            .isInstanceOf(ScenarioParseException::class.java)
            .hasRootCauseMessage(
                "A snapshot expectation takes the single key 'snapshot'; found keys [snapshto, snapshot]",
            )
    }

    @Test
    fun `misspelled snapshot key on its own is rejected instead of defaulting to EXISTS`() {
        assertThatThrownBy { parsedExpectation("snapshto: a.md") }
            .isInstanceOf(ScenarioParseException::class.java)
            .hasRootCauseMessage("A snapshot expectation takes the single key 'snapshot'; found keys [snapshto]")
    }

    @Test
    fun `type next to a snapshot is rejected`() {
        assertThatThrownBy { parsedExpectation("type: EQUALS\nsnapshot: a.md") }
            .isInstanceOf(ScenarioParseException::class.java)
    }

    @Test
    fun `other expectations still tolerate unknown keys`() {
        assertThat(parsedExpectation("type: EXISTS\nmessage: hi").snapshot).isNull()
    }

    @Test
    fun `snapshot combined with value is rejected at parse time`() {
        assertThatThrownBy { parsedExpectation("snapshot: a.md\nvalue: x") }
            .isInstanceOf(ScenarioParseException::class.java)
    }
}
