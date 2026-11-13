// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

@Isolated
class ScenarioValidationTest {
    @Test
    fun `snapshot check does not require a regex pattern`() {
        val check = JsonCheck("$", JsonExpectation(type = ExpectationType.MATCHES_REGEX, snapshot = "snapshot.json"))
        ScenarioCheckValidation.validateStatic(
            Scenario(
                name = "snapshot",
                steps = listOf(ScenarioStep.Assert(source = "workspace", checks = listOf(check))),
            ),
        )
    }

    @Test
    fun `invalid static JSONPath fails before workspace or server startup`() =
        assertInvalidBeforeStartup(JsonCheck("$[?(", JsonExpectation()))

    @Test
    fun `invalid static regex fails before workspace or server startup`() = assertInvalidBeforeStartup(
        JsonCheck("$", JsonExpectation(type = ExpectationType.MATCHES_REGEX, value = JsonPrimitive("("))),
    )

    @Test
    fun `required and optional waits reject static invalid checks before startup`() {
        val factory = mockk<LanguageServerSessionFactory>()
        mockkObject(WorkspaceFixture)
        try {
            every { WorkspaceFixture.materialize(any(), any()) } throws AssertionError("workspace started")
            val checks = listOf(
                JsonCheck("$[?(", JsonExpectation()),
                JsonCheck("$", JsonExpectation(type = ExpectationType.MATCHES_REGEX, value = JsonPrimitive("("))),
            )
            for (optional in listOf(false, true)) {
                for (check in checks) {
                    val step = ScenarioStep.WaitNotification(
                        method = "test/notification",
                        optional = optional,
                        checks = listOf(check),
                    )
                    val definition = ScenarioDefinition(
                        Scenario(name = "invalid-wait", steps = listOf(step)),
                        source = "scenario.yaml",
                    )
                    assertThatThrownBy { ScenarioExecutor(factory).execute(definition) }
                        .isInstanceOf(IllegalArgumentException::class.java)
                    assertThatThrownBy { ScenarioExecutor(factory).executeWithOutcome(definition) }
                        .isInstanceOf(IllegalArgumentException::class.java)
                }
            }
            verify(exactly = 0) { WorkspaceFixture.materialize(any(), any()) }
            verify(exactly = 0) { factory.start(any(), any()) }
        } finally {
            unmockkObject(WorkspaceFixture)
        }
    }

    private fun assertInvalidBeforeStartup(check: JsonCheck) {
        val factory = mockk<LanguageServerSessionFactory>()
        mockkObject(WorkspaceFixture)
        try {
            every { WorkspaceFixture.materialize(any(), any()) } throws AssertionError("workspace started")
            val definition = ScenarioDefinition(
                Scenario(
                    name = "invalid-check",
                    steps = listOf(ScenarioStep.Assert(source = "workspace", checks = listOf(check))),
                ),
                source = "scenario.yaml",
            )
            assertThatThrownBy { ScenarioExecutor(factory).execute(definition) }
                .isInstanceOf(IllegalArgumentException::class.java)
            val named = definition.copy(
                scenario = definition.scenario.copy(
                    steps = listOf(
                        ScenarioStep.Assert(source = "workspace", checks = listOf(check.copy(id = "named"))),
                    ),
                ),
            )
            assertThatThrownBy { ScenarioExecutor(factory).executeWithOutcome(named) }
                .isInstanceOf(IllegalArgumentException::class.java)
            verify(exactly = 0) { WorkspaceFixture.materialize(any(), any()) }
            verify(exactly = 0) { factory.start(any(), any()) }
        } finally {
            unmockkObject(WorkspaceFixture)
        }
    }
}
