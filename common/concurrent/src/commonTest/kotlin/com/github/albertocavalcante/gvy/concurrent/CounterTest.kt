// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.concurrent

import kotlin.test.Test
import kotlin.test.assertEquals

class CounterTest {

    @Test
    fun `a counter starts at zero`() {
        assertEquals(0L, Counter().value)
    }

    @Test
    fun `increment returns the new count`() {
        val counter = Counter()

        assertEquals(1L, counter.increment())
        assertEquals(2L, counter.increment())
        assertEquals(2L, counter.value)
    }
}
