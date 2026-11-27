// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class Sha256Test {
    @Test
    fun `equal digests are equal and hash alike while different digests differ`() {
        val first = sampleSha(seed = 1)
        val sameBytes = sampleSha(seed = 1)
        val other = sampleSha(seed = 2)

        assertEquals(first, sameBytes)
        assertEquals(first.hashCode(), sameBytes.hashCode())
        assertNotEquals(first, other)
        assertNotEquals<Any?>(first, null)
        assertNotEquals<Any?>(first, "not a digest")
        assertEquals(setOf(first), setOf(sameBytes))
    }

    @Test
    fun `a digest never changes because the array it was made from or read into changes`() {
        val source = ByteArray(Sha256.SIZE_BYTES) { 7 }
        val digest = Sha256.of(source)

        source[0] = 0
        digest.toByteArray()[1] = 0

        assertContentEquals(ByteArray(Sha256.SIZE_BYTES) { 7 }, digest.toByteArray())
    }

    @Test
    fun `a digest renders as 64 lowercase hex digits`() {
        val digest = Sha256.of(ByteArray(Sha256.SIZE_BYTES) { index -> index.toByte() })

        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", digest.toString())
    }

    @Test
    fun `a digest of the wrong size is a programmer error`() {
        assertFailsWith<IllegalArgumentException> { Sha256.of(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { Sha256.of(ByteArray(33)) }
    }
}
