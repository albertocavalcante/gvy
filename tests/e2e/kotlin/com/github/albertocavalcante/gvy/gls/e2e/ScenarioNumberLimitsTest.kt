package com.github.albertocavalcante.gvy.gls.e2e

import com.google.gson.JsonSyntaxException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class ScenarioNumberLimitsTest {
    @TestFactory
    fun `unsupported numeric comparison scales fail explicitly rather than silently mismatch`(): List<DynamicTest> =
        listOf(ExpectationType.GTE, ExpectationType.LTE).flatMap { type ->
            listOf("1e2147483648" to "1e2147483648", "1e2147483648" to "1", "1" to "1e2147483648")
                .map { (actual, expected) ->
                    dynamicTest("$actual $type $expected") {
                        assertThatThrownBy { matches(actual, expected, type) }
                            .isInstanceOf(UnsupportedOperationException::class.java)
                            .hasMessageContaining("BigDecimal")
                    }
                }
        }

    @TestFactory
    fun `nonnumeric operands reject the comparison before checking the other operand scale`(): List<DynamicTest> =
        listOf(ExpectationType.GTE, ExpectationType.LTE).flatMap { type ->
            listOf("\"7\"", "true", "null").flatMap { nonnumeric ->
                listOf(nonnumeric to "1e2147483648", "1e2147483648" to nonnumeric).map { (actual, expected) ->
                    dynamicTest("$actual $type $expected") { assertThat(matches(actual, expected, type)).isFalse() }
                }
            }
        }

    @TestFactory
    fun `invalid NaN is rejected as invalid JSON before numeric comparison`(): List<DynamicTest> =
        listOf(ExpectationType.GTE, ExpectationType.LTE).map { type ->
            dynamicTest(type.name) {
                assertThatThrownBy {
                    JsonExpectationEvaluator.matches(
                        JsonExpectation(type, JsonPrimitive(1)),
                        true,
                        JsonPrimitive(Double.NaN),
                    )
                }.isInstanceOf(JsonSyntaxException::class.java)
            }
        }

    @Test
    fun `YAML decimals outside the supported scale range fail parsing without rounding`() {
        assertThatThrownBy {
            JacksonScenarioParser().parseScenarioDefinition(
                "name: huge-exponent\nsteps:\n  - request: {method: test/numeric, params: 1e2147483648}",
            )
        }.hasMessageContaining("Failed to parse").hasStackTraceContaining("BigDecimal")
    }

    private fun matches(actual: String, expected: String, type: ExpectationType): Boolean =
        JsonExpectationEvaluator.matches(
            JsonExpectation(type, Json.parseToJsonElement(expected)),
            true,
            Json.parseToJsonElement(actual),
        )
}
