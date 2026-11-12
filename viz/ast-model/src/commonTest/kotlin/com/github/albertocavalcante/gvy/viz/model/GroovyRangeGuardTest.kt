// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroovyRangeGuardTest {
    @Test
    fun `guard accepts only valid positions before the first surrogate on each line`() {
        val guard = GroovyRangeGuard("abc😀z\r\nsafe\n")
        val safe = RangeDto(1, 1, 1, 3)
        assertEquals(safe, guard.validRange(safe))
        assertNull(guard.validRange(RangeDto(1, 1, 1, 4)))
        assertNull(guard.validRange(RangeDto(1, 5, 1, 6)))
        val later = RangeDto(2, 1, 2, 5)
        assertEquals(later, guard.validRange(later))
        val spanning = RangeDto(1, 1, 2, 5)
        assertEquals(spanning, guard.validRange(spanning))
        assertNull(guard.validRange(RangeDto(2, 1, 2, 6)))
        assertNull(guard.validRange(RangeDto(1, 1, 4, 1)))
        assertNull(guard.validRange(RangeDto(2, 3, 2, 2)))
    }

    @Test
    fun `many ranges on a long line preserve the safe prefix`() {
        val guard = GroovyRangeGuard("x".repeat(50_000) + "😀")
        repeat(1_000) { index ->
            val range = RangeDto(1, index + 1, 1, index + 2)
            assertEquals(range, guard.validRange(range))
        }
        assertNull(guard.validRange(RangeDto(1, 1, 1, 50_001)))
    }
}
