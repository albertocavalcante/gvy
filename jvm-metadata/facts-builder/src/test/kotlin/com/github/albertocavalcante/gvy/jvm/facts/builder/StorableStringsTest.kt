// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.FactsFormat
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorableStringsTest {
    private val limit = FactsFormat.MAX_STRING_BYTES

    @Test
    fun `accepts strings that need at most the limit in UTF-8 and rejects longer ones`() {
        assertTrue("a".repeat(limit).isStorable())
        assertFalse("a".repeat(limit + 1).isStorable())
        assertTrue("\u20AC".repeat(limit / 3).isStorable())
        assertFalse("\u20AC".repeat(limit / 3 + 1).isStorable())
        assertTrue(("a" + "\u00E9".repeat(limit / 2)).isStorable())
        assertFalse(("aa" + "\u00E9".repeat(limit / 2)).isStorable())
        assertTrue("\uD83D\uDE00".repeat(limit / 4).isStorable())
        assertFalse("\uD83D\uDE00".repeat(limit / 4 + 1).isStorable())
    }

    @Test
    fun `counts NUL as one byte and accepts the empty string`() {
        assertTrue("\u0000".repeat(limit).isStorable())
        assertFalse("\u0000".repeat(limit + 1).isStorable())
        assertTrue("".isStorable())
    }

    @Test
    fun `rejects unpaired surrogates anywhere`() {
        assertFalse("\uD800".isStorable())
        assertFalse("\uDC00".isStorable())
        assertFalse("a\uD800b".isStorable())
        assertFalse("\uDC00\uD800".isStorable())
        assertFalse("ab\uD800".isStorable())
    }
}
