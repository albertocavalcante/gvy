// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ScenarioDslTest {

    @Test
    fun `workspaceFile adds inline files next to the fixture`() {
        val definition = scenario(source = "scenarios/_dsl.yaml") {
            workspace("empty-workspace")
            workspaceFile("src/A.groovy", "class |^a|A {}")
            workspaceFile("src/B.groovy", "new A()")
        }

        assertThat(definition.scenario.workspace).isEqualTo(
            WorkspaceConfig(
                fixture = "empty-workspace",
                files = mapOf("src/A.groovy" to "class |^a|A {}", "src/B.groovy" to "new A()"),
            ),
        )
    }

    @Test
    fun `workspaceFile works without a fixture`() {
        val definition = scenario(source = "scenarios/_dsl.yaml") {
            workspaceFile("A.groovy", "class A {}")
        }

        assertThat(definition.scenario.workspace).isEqualTo(WorkspaceConfig(files = mapOf("A.groovy" to "class A {}")))
    }

    @Test
    fun `workspaceFile rejects the same path twice`() {
        assertThatThrownBy {
            scenario(source = "scenarios/_dsl.yaml") {
                workspaceFile("A.groovy", "class A {}")
                workspaceFile("A.groovy", "class B {}")
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("A.groovy")
    }

    @Test
    fun `no workspace call leaves the workspace unset`() {
        assertThat(scenario(source = "scenarios/_dsl.yaml") {}.scenario.workspace).isNull()
    }

    @Test
    fun `openDocument without text matches the YAML fixture opening contract`() {
        val kotlinStep = scenario(source = "scenarios/_dsl.yaml") { openDocument("A.groovy") }
            .scenario.steps.single()
        val yamlStep = JacksonScenarioParser().parseScenarioDefinition(
            """
            name: fixture-open
            steps:
              - openDocument: { path: A.groovy }
            """.trimIndent(),
        ).scenario.steps.single()

        assertThat(kotlinStep).isEqualTo(yamlStep)
    }

    @Test
    fun `openDocument preserves explicit empty text as a buffer override`() {
        val step = scenario(source = "scenarios/_dsl.yaml") { openDocument("A.groovy", text = "") }
            .scenario.steps.single()

        assertThat(step).isEqualTo(ScenarioStep.OpenDocument(path = "A.groovy", text = ""))
    }

    @Test
    fun `building a scenario snapshots its steps before the builder is reused`() {
        val builder = ScenarioBuilder(source = "scenarios/_dsl.yaml")
        builder.initialize()
        val first = builder.build()

        builder.shutdown()

        assertThat(first.scenario.steps).containsExactly(ScenarioStep.Initialize())
        assertThat(builder.build().scenario.steps).containsExactly(ScenarioStep.Initialize(), ScenarioStep.Shutdown)
    }

    @Test
    fun `building a request snapshots extractions before the builder is reused`() {
        val builder = RequestBuilder("textDocument/hover")
        builder.extract("first", "$.contents")
        val first = builder.build()

        builder.extract("second", "$.range")

        assertThat(first.extract).containsExactly(JsonExtraction("first", "$.contents"))
        assertThat(builder.build().extract).hasSize(2)
    }

    @Test
    fun `building checks snapshots assertions before the builder is reused`() {
        val builder = AssertionBuilder()
        builder.jsonPath("$.contents") { exists() }
        val first = builder.buildChecks()

        builder.jsonPath("$.error") { notExists() }

        assertThat(first).containsExactly(JsonCheck("$.contents", JsonExpectation(type = ExpectationType.EXISTS)))
        assertThat(builder.buildChecks()).hasSize(2)
    }
}
