// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.e2e.JsonBridge.toJavaObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.math.BigInteger

class ScenarioNumberFidelityTest {
    @TestFactory
    fun `YAML JSON bindings retain exact numeric values`(): List<DynamicTest> = numericLiterals.map { literal ->
        dynamicTest(literal) {
            val scenario = parser.parseScenarioDefinition(
                """
                name: numeric-bindings
                steps:
                  - initialize:
                      initializationOptions: { number: $literal }
                  - request:
                      method: test/numeric
                      params: { number: $literal }
                  - assert:
                      checks:
                        - jsonPath: $.number
                          expect: { type: EQUALS, value: $literal }
                """.trimIndent(),
            ).scenario
            val options = (scenario.steps[0] as ScenarioStep.Initialize).initializationOptions
            val request = scenario.steps.filterIsInstance<ScenarioStep.SendRequest>().single()
            val check = scenario.steps.filterIsInstance<ScenarioStep.Assert>().single().checks.single()

            listOf(options?.jsonObject?.get("number"), request.params?.jsonObject?.get("number"), check.expect.value)
                .forEach { value ->
                    assertThat(value?.jsonPrimitive?.isString).isFalse()
                    assertThat(value?.jsonPrimitive?.content?.toBigDecimal())
                        .isEqualByComparingTo(literal.toBigDecimal())
                }
        }
    }

    @TestFactory
    fun `JSON bridge retains exact Java numeric values`(): List<DynamicTest> = numericLiterals.map { literal ->
        dynamicTest(literal) {
            val value = Json.parseToJsonElement(literal).toJavaObject()

            assertThat(value).isInstanceOf(Number::class.java)
            assertThat(value.toString().toBigDecimal()).isEqualByComparingTo(literal.toBigDecimal())
        }
    }

    @Test
    fun `ordinary scalar bridge types stay unchanged and numeric strings stay strings`() {
        listOf("7", "2147483648").forEach { literal ->
            val actual = Json.parseToJsonElement(literal).toJavaObject()
            assertThat(actual).isInstanceOf(Long::class.javaObjectType)
            assertThat(actual.toString()).isEqualTo(literal)
        }
        assertThat(JsonPrimitive("2147483648").toJavaObject()).isEqualTo("2147483648")
        assertThat(JsonPrimitive(true).toJavaObject()).isEqualTo(true)
        assertThat(JsonNull.toJavaObject()).isNull()
        assertThat(Json.parseToJsonElement("123456789012345678901234567890").toJavaObject())
            .isInstanceOf(BigInteger::class.java)
        assertThat(Json.parseToJsonElement("0.123456789012345678901234567890").toJavaObject())
            .isInstanceOf(BigDecimal::class.java)
    }

    @TestFactory
    fun `ordering rejects adjacent values that round to the same Double`(): List<DynamicTest> = listOf(
        "9007199254740992" to "9007199254740993",
        "0.123456789012345678901234567889" to "0.123456789012345678901234567890",
    ).flatMap { (smaller, larger) ->
        listOf(
            dynamicTest("$smaller GTE $larger") { assertThat(matches(smaller, larger, ExpectationType.GTE)).isFalse() },
            dynamicTest("$larger LTE $smaller") { assertThat(matches(larger, smaller, ExpectationType.LTE)).isFalse() },
            dynamicTest("$larger GTE $smaller") { assertThat(matches(larger, smaller, ExpectationType.GTE)).isTrue() },
            dynamicTest("$smaller LTE $larger") { assertThat(matches(smaller, larger, ExpectationType.LTE)).isTrue() },
        )
    }

    @TestFactory
    fun `ordering accepts equivalent numeric values and rejects numeric strings`(): List<DynamicTest> =
        listOf(ExpectationType.GTE, ExpectationType.LTE).flatMap { type ->
            listOf(
                dynamicTest("$type exponent") { assertThat(matches("1e2", "100.0", type)).isTrue() },
                dynamicTest("$type signed zero") { assertThat(matches("-0.0", "0", type)).isTrue() },
                dynamicTest("$type actual string") { assertThat(matches("\"7\"", "7", type)).isFalse() },
                dynamicTest("$type expected string") { assertThat(matches("7", "\"7\"", type)).isFalse() },
            )
        }

    @TestFactory
    fun `nonfinite YAML numbers are rejected explicitly`(): List<DynamicTest> = listOf(".nan", ".inf", "-.inf").map {
        dynamicTest(it) {
            assertThatThrownBy {
                parser.parseScenarioDefinition(
                    "name: nonfinite\nsteps:\n  - request: {method: test/numeric, params: $it}",
                )
            }.hasMessageContaining("Failed to parse")
        }
    }

    private fun matches(actual: String, expected: String, type: ExpectationType) = JsonExpectationEvaluator.matches(
        JsonExpectation(type = type, value = Json.parseToJsonElement(expected)),
        true,
        Json.parseToJsonElement(actual),
    )

    private val parser = JacksonScenarioParser()

    private val numericLiterals = listOf(
        "0", "7", "2147483648", "9007199254740993", "123456789012345678901234567890",
        "0.123456789012345678901234567890", "1.234567890123456789e50", "-1.234567890123456789e-50",
        "1e400", "-0", "-0.0", "1.0",
    )
}
