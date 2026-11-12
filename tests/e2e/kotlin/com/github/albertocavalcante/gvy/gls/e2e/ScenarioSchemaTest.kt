// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class ScenarioSchemaTest {
    private val parser = JacksonScenarioParser()

    @TestFactory
    fun `unknown typed fields are rejected throughout the scenario`(): List<DynamicTest> = listOf(
        "scenario" to "unexpected: true\nsteps: []",
        "server" to "server: { unexpected: true }\nsteps: []",
        "request" to "steps: [{ request: { method: test, unexpected: true } }]",
        "notification" to "steps: [{ notification: { method: test, unexpected: true } }]",
        "initialized" to "steps: [{ initialized: { unexpected: true } }]",
        "shutdown" to "steps: [{ shutdown: { unexpected: true } }]",
        "exit" to "steps: [{ exit: { unexpected: true } }]",
        "assert" to "steps: [{ assert: { unexpected: true } }]",
        "check" to "steps: [{ assert: { checks: [{ jsonPath: '$', expect: {}, unexpected: true }] } }]",
        "expectation" to "steps: [{ assert: { checks: [{ jsonPath: '$', expect: { unexpected: true } }] } }]",
        "extraction" to """
            steps:
              - request:
                  method: test
                  extract: [{ variable: r, jsonPath: '$', unexpected: true }]
        """.trimIndent(),
        "range" to """
            steps:
              - codeAction:
                  range:
                    start: { line: 0, character: 0 }
                    end: { line: 0, character: 1 }
                    unexpected: true
        """.trimIndent(),
        "position" to """
            steps:
              - codeAction:
                  range:
                    start: { line: 0, character: 0, unexpected: true }
                    end: { line: 0, character: 1 }
        """.trimIndent(),
    ).map { (name, body) ->
        dynamicTest(name) {
            assertThatThrownBy { parser.parseScenarioDefinition("name: invalid\n$body\n") }
                .isInstanceOf(ScenarioParseException::class.java)
                .hasStackTraceContaining("\"unexpected\"")
        }
    }

    @Test
    fun `raw protocol payloads and map entries retain arbitrary keys`() {
        val definition = parser.parseScenarioDefinition(
            """
            name: raw-payloads
            server:
              env: { unexpected: kept }
            workspace:
              files: { unexpected: source }
            steps:
              - initialize:
                  initializationOptions: { expect: { snapshto: application-data } }
              - request:
                  method: custom/request
                  params: { expect: { snapshot: data, unexpected: kept } }
              - notification:
                  method: custom/notification
                  params: { unexpected: kept }
              - assert:
                  checks:
                    - jsonPath: '$'
                      expect:
                        type: EQUALS
                        value: { expect: { snapshot: data, unexpected: kept } }
            """.trimIndent(),
        )

        assertThat(definition.scenario.server.env).containsEntry("unexpected", "kept")
        assertThat(definition.scenario.workspace?.files).containsEntry("unexpected", "source")
        val initialize = definition.scenario.steps.filterIsInstance<ScenarioStep.Initialize>().single()
        assertThat(initialize.initializationOptions).isEqualTo(
            Json.parseToJsonElement("""{"expect":{"snapshto":"application-data"}}"""),
        )
        val request = definition.scenario.steps.filterIsInstance<ScenarioStep.SendRequest>().single()
        val applicationData = Json.parseToJsonElement("""{"expect":{"snapshot":"data","unexpected":"kept"}}""")
        assertThat(request.params).isEqualTo(applicationData)
        val notification = definition.scenario.steps.filterIsInstance<ScenarioStep.SendNotification>().single()
        assertThat(notification.params).isEqualTo(Json.parseToJsonElement("""{"unexpected":"kept"}"""))
        val check = definition.scenario.steps.filterIsInstance<ScenarioStep.Assert>().single().checks.single()
        assertThat(check.expect.type).isEqualTo(ExpectationType.EQUALS)
        assertThat(check.expect.value).isEqualTo(applicationData)
    }

    @Test
    fun `an empty expectation keeps the documented existence default`() {
        val step = parser.parseScenarioDefinition(
            "name: exists\nsteps: [{ assert: { checks: [{ jsonPath: '$', expect: {} }] } }]\n",
        ).scenario.steps.single() as ScenarioStep.Assert

        assertThat(step.checks.single().expect.type).isEqualTo(ExpectationType.EXISTS)
    }

    @TestFactory
    fun `equality requires a value but accepts an explicit JSON null`(): List<DynamicTest> =
        listOf(ExpectationType.EQUALS, ExpectationType.NOT_EQUALS).flatMap { type ->
            listOf(
                dynamicTest("$type without value") {
                    assertThatThrownBy {
                        parser.parseScenarioDefinition(
                            assertionScenario("{ type: $type }"),
                        )
                    }.isInstanceOf(ScenarioParseException::class.java)
                        .hasStackTraceContaining("value")
                },
                dynamicTest("$type with explicit null") {
                    val definition = parser.parseScenarioDefinition(
                        assertionScenario("{ type: $type, value: null }"),
                    )
                    val step = definition.scenario.steps.single() as ScenarioStep.Assert
                    assertThat(step.checks.single().expect.value).isSameAs(JsonNull)
                },
            )
        }

    @TestFactory
    fun `snapshot paths must be nonblank strings`(): List<DynamicTest> = listOf("null", "''", "42").map { value ->
        dynamicTest("snapshot: $value") {
            assertThatThrownBy {
                parser.parseScenarioDefinition(
                    assertionScenario("{ snapshot: $value }"),
                )
            }.isInstanceOf(ScenarioParseException::class.java)
                .hasStackTraceContaining("snapshot")
        }
    }

    @TestFactory
    fun `nonobject scenario roots are reported as parse failures`(): List<DynamicTest> =
        listOf("", "# comment only", "null", "[]", "42").mapIndexed { index, yaml ->
            dynamicTest("invalid root $index") {
                assertThatThrownBy { parser.parseScenarioDefinition(yaml) }
                    .isInstanceOf(ScenarioParseException::class.java)
            }
        }

    private fun assertionScenario(expectation: String): String = """
        name: assertion
        steps:
          - assert:
              checks:
                - jsonPath: '$'
                  expect: $expectation
    """.trimIndent()
}
