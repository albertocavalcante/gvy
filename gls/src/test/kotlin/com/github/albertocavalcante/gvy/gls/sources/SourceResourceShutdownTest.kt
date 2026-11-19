// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SourceResourceShutdownTest {
    @Test
    fun `compiler resources are released even when source cleanup fails`() {
        val calls = mutableListOf<String>()
        val sources = AutoCloseable {
            calls.add("sources")
            error("source cleanup failed")
        }

        assertFailsWith<IllegalStateException> {
            shutdownResources(sources::close, { calls.add("compilation") })
        }

        assertEquals(listOf("sources", "compilation"), calls)
    }

    @Test
    fun `earlier cleanup failure still stops requests and closes sources preserving the original failure`() {
        val calls = mutableListOf<String>()
        val original = IllegalStateException("startup shutdown failed")
        val later = IllegalArgumentException("source shutdown failed")

        val thrown = assertFailsWith<IllegalStateException> {
            shutdownResources(
                { throw original },
                { calls.add("pool") },
                { calls.add("requests") },
                {
                    calls.add("sources")
                    throw later
                },
                { calls.add("compilation") },
            )
        }

        assertSame(original, thrown)
        assertEquals(listOf(later), thrown.suppressed.toList())
        assertEquals(listOf("pool", "requests", "sources", "compilation"), calls)
    }
}
