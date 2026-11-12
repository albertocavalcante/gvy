// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The buffer-version rule inside [ArtifactOwnership.commit] (#1383). */
class ArtifactOwnershipOrderTest {
    private val uri = URI.create("file:///Order.groovy")
    private val ownership = ArtifactOwnership()
    private val lifecycle = Any()

    private fun order(version: Int, current: Boolean = false, incarnation: Any = lifecycle) =
        BufferOrder(incarnation, version) { current }

    private fun commits(order: BufferOrder?) = ownership.commit(ownership.acquire(uri, order) {}.first) {}

    @Test
    fun `an older version of the same lifecycle is refused once a newer one committed`() {
        assertTrue(commits(order(2)))
        assertFalse(commits(order(1)))
    }

    @Test
    fun `an equal version commits again`() {
        assertTrue(commits(order(2)))
        assertTrue(commits(order(2)))
    }

    @Test
    fun `the current buffer always commits, even if its version went backwards`() {
        assertTrue(commits(order(3)))
        assertTrue(commits(order(2, current = true)), "a client with non-monotonic versions must not stall")
    }

    @Test
    fun `a new lifecycle and unordered work always commit`() {
        assertTrue(commits(order(5)))
        assertTrue(commits(order(1, incarnation = Any())))
        assertTrue(commits(null), "unmanaged and disk work carry no order")
    }

    @Test
    fun `retiring the file resets its order`() {
        assertTrue(commits(order(5)))
        ownership.retire(uri)
        assertTrue(commits(order(1)))
    }
}
