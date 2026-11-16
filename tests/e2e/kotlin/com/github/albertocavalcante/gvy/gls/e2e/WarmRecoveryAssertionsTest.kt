// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class WarmRecoveryAssertionsTest {
    @Test
    fun `completion must include this revision and reject a previous enum marker`() {
        val old = items("NEW", "PAID", "SHIPPED", "CANCELLED", "NEXT_V1")
        assertFailsWith<AssertionError> {
            WarmRecoveryAssertions.completion(old, "NEXT_V2", setOf("NEXT_V1"), prefix = false)
        }
        val leaked = items("NEW", "PAID", "SHIPPED", "CANCELLED", "NEXT_V1", "NEXT_V2")
        assertFailsWith<AssertionError> {
            WarmRecoveryAssertions.completion(leaked, "NEXT_V2", setOf("NEXT_V1"), prefix = false)
        }
        WarmRecoveryAssertions.completion(
            items("NEW", "PAID", "SHIPPED", "CANCELLED", "NEXT_V2"),
            "NEXT_V2",
            setOf("NEXT_V1"),
            prefix = false,
        )
    }

    @Test
    fun `prefix completion requires current N members without banning legitimate extra items`() {
        WarmRecoveryAssertions.completion(items("NEW", "NEXT_V3", "name"), "NEXT_V3", emptySet(), prefix = true)
        assertFailsWith<AssertionError> {
            WarmRecoveryAssertions.completion(items("NEW"), "NEXT_V3", emptySet(), prefix = true)
        }
    }

    @Test
    fun `successful null completion is a named product assertion`() {
        assertFailsWith<AssertionError> {
            WarmRecoveryAssertions.completion(JsonNull, "NEXT_V2", emptySet(), prefix = false)
        }
    }

    private fun items(vararg labels: String) = Json.parseToJsonElement(
        labels.joinToString(prefix = "[", postfix = "]") { """{"label":"$it"}""" },
    )
}
