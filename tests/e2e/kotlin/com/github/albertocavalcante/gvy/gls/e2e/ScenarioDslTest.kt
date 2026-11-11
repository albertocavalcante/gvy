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
}
