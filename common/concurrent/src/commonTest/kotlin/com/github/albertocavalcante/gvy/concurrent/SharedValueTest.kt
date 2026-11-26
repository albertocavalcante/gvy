// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.concurrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedValueTest {

    @Test
    fun `value starts as the initial value`() {
        assertEquals("initial", SharedValue("initial").value)
    }

    @Test
    fun `set replaces the value`() {
        val shared = SharedValue("old")

        shared.set("new")

        assertEquals("new", shared.value)
    }

    @Test
    fun `exchange stores the new value and returns the previous one`() {
        val shared = SharedValue("old")

        val previous = shared.exchange("new")

        assertEquals("old", previous)
        assertEquals("new", shared.value)
    }

    @Test
    fun `update stores and returns the transformed current value`() {
        val shared = SharedValue(listOf("a"))

        val updated = shared.update { current -> current + "b" }

        assertEquals(listOf("a", "b"), updated)
        assertEquals(listOf("a", "b"), shared.value)
    }

    @Test
    fun `a nullable value can be cleared and set again`() {
        val shared = SharedValue<String?>("first")

        assertEquals("first", shared.exchange(null))
        assertNull(shared.value)

        shared.set("second")
        assertEquals("second", shared.value)
    }
}
