// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.InvalidPathException
import com.jayway.jsonpath.JsonPath
import kotlinx.serialization.json.JsonPrimitive

/** Validates authored checks before execution, and dynamic checks after their variables are available. */
internal object ScenarioCheckValidation {
    fun validateStatic(scenario: Scenario) {
        scenario.steps.forEach { step ->
            val checks = when (step) {
                is ScenarioStep.Assert -> step.checks
                is ScenarioStep.WaitNotification -> step.checks
                is ScenarioStep.Completion -> step.checks
                is ScenarioStep.CodeAction -> step.checks
                is ScenarioStep.Formatting -> step.checks
                is ScenarioStep.Rename -> step.checks
                is ScenarioStep.CliCommand -> step.checks
                else -> emptyList()
            }
            checks.forEach { validate(it, null) }
        }
    }

    fun validate(check: JsonCheck, context: ScenarioContext?) {
        val path = context?.interpolateString(check.jsonPath) ?: check.jsonPath
        if (context != null || !path.contains("{{")) {
            try {
                JsonPath.compile(path)
            } catch (failure: InvalidPathException) {
                throw IllegalArgumentException("Invalid JSONPath '$path'", failure)
            }
        }
        if (check.expect.snapshot == null && check.expect.type == ExpectationType.MATCHES_REGEX) {
            val value = context?.interpolateNode(check.expect.value) ?: check.expect.value
            require(value is JsonPrimitive && value.isString) { "MATCHES_REGEX requires a string pattern" }
            val pattern = value.content
            if (context != null || !pattern.contains("{{")) {
                try {
                    Regex(pattern)
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid regex '$pattern'", failure)
                }
            }
        }
    }
}
