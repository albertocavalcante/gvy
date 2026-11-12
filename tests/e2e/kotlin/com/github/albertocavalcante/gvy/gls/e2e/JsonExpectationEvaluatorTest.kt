// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JsonExpectationEvaluatorTest {
    @Test
    fun `contains null matches a null array element without treating it as a wildcard`() {
        val expectation = JsonExpectation(type = ExpectationType.CONTAINS, value = JsonNull)

        assertThat(matches("[null]", expectation)).isTrue()
        listOf("[1]", "[\"null\"]", "[{}]", "[]", "\"null\"").forEach { actual ->
            assertThat(matches(actual, expectation)).describedAs(actual).isFalse()
        }
    }

    @Test
    fun `structural containment distinguishes null fields from missing and nonnull fields`() {
        val expectation = JsonExpectation(
            type = ExpectationType.CONTAINS,
            value = Json.parseToJsonElement("""{"detail":null}"""),
        )

        assertThat(matches("""[{"detail":null,"label":"known"}]""", expectation)).isTrue()
        listOf("""[{"detail":"wrong"}]""", """[{"detail":{}}]""", "[{}]", "[null]").forEach { actual ->
            assertThat(matches(actual, expectation)).describedAs(actual).isFalse()
        }
    }

    @Test
    fun `nested array containment requires the expected null element`() {
        val expectation = JsonExpectation(
            type = ExpectationType.CONTAINS,
            value = Json.parseToJsonElement("""{"data":[null]}"""),
        )

        assertThat(matches("""[{"data":[1,null]}]""", expectation)).isTrue()
        assertThat(matches("""[{"data":[1]}]""", expectation)).isFalse()
    }

    private fun matches(actual: String, expectation: JsonExpectation): Boolean = JsonExpectationEvaluator.matches(
        expectation = expectation,
        pathExists = true,
        actualNode = Json.parseToJsonElement(actual),
    )
}
