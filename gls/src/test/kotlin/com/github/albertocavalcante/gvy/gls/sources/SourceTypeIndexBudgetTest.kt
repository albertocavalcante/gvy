// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SourceTypeIndexBudgetTest {
    @Test
    fun `names retain their exact spelling within the native lookup limit`() {
        val value = "x".repeat(SourceTypeIndexBudget.MAX_NAME_CHARACTERS)
        assertSame(value, SourceTypeIndexBudget().name(value))
        assertFailsWith<SourceTypeIndexLimitException> { SourceTypeIndexBudget().name(value + "x") }
    }

    @Test
    fun `candidate expansion is bounded across many small individually valid tiers`() {
        val budget = SourceTypeIndexBudget()
        val tier = listOf(listOf("x".repeat(4096)))
        repeat(512) { assertSame(tier, budget.candidates(tier)) }
        assertFailsWith<SourceTypeIndexLimitException> { budget.candidates(listOf(listOf("last.Type"))) }
    }

    @Test
    fun `short values cannot bypass the aggregate value count limit`() {
        val budget = SourceTypeIndexBudget()
        repeat(SourceTypeIndexBudget.MAX_VALUES.toInt()) { budget.name("") }
        assertFailsWith<SourceTypeIndexLimitException> { budget.name("a") }
        assertEquals(65_536L, SourceTypeIndexBudget.MAX_VALUES)
    }
}
