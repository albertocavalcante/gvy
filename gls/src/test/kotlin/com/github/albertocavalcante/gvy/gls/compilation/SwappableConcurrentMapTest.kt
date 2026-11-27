// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SwappableConcurrentMapTest {
    @Test
    fun `a swap retires the whole map and leaves an empty one`() {
        val holder = SwappableConcurrentMap { ConcurrentHashMap<String, Int>() }
        val before = holder.map
        before["a"] = 1

        val retired = holder.swapEmpty()

        assertSame(before, retired, "the swap returns the map it retired, with what it held")
        assertEquals(mapOf("a" to 1), retired)
        assertNotSame(retired, holder.map)
        assertTrue(holder.map.isEmpty())
    }

    @Test
    fun `a caller that kept the map across a swap writes to the retired map`() {
        val holder = SwappableConcurrentMap { ConcurrentHashMap<String, Int>() }
        val kept = holder.map

        holder.swapEmpty()
        kept["late"] = 1

        assertTrue(holder.map.isEmpty(), "the late write is dropped with the retired map, as the KDoc says")
    }
}
