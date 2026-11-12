// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class IdleReleasingCacheTest {

    private var nowNanos = 0L
    private val created = mutableListOf<String>()
    private val closed = mutableListOf<String>()

    private val failingKeys = mutableSetOf<String>()

    private val cache = IdleReleasingCache<String, StringBuilder>(
        create = { key ->
            created += key
            StringBuilder(key)
        },
        close = { key, _ ->
            closed += key
            check(key !in failingKeys) { "close failed: $key" }
        },
        clockNanos = { nowNanos },
        idleTimeout = { 5.minutes },
    )

    private fun advance(duration: kotlin.time.Duration) {
        nowNanos += duration.inWholeNanoseconds
    }

    @Test
    fun `selectIdleKeys picks only unleased entries idle for the full timeout`() {
        val activity = mapOf(
            "fresh" to EntryActivity(lastUsedNanos = 290.seconds.inWholeNanoseconds, leases = 0),
            "exactly-idle" to EntryActivity(lastUsedNanos = 0, leases = 0),
            "leased" to EntryActivity(lastUsedNanos = 0, leases = 1),
        )

        val idle = selectIdleKeys(activity, nowNanos = 5.minutes.inWholeNanoseconds, idleTimeout = 5.minutes)

        assertEquals(listOf("exactly-idle"), idle)
    }

    @Test
    fun `get returns the same value and creates it once`() {
        assertSame(cache.get("a"), cache.get("a"))
        assertEquals(listOf("a"), created)
    }

    @Test
    fun `releaseIdle closes entries unused for the timeout and keeps recent ones`() {
        cache.get("old")
        advance(4.minutes)
        cache.get("recent")
        advance(2.minutes)

        assertEquals(1, cache.releaseIdle())

        assertEquals(listOf("old"), closed)
        assertEquals(listOf("recent"), cache.keys)
    }

    @Test
    fun `using an entry again restarts its idle clock`() {
        cache.get("a")
        advance(4.minutes)
        cache.get("a")
        advance(4.minutes)

        assertEquals(0, cache.releaseIdle())
        assertEquals(emptyList<String>(), closed)
    }

    @Test
    fun `a leased entry is never released and its clock restarts when the lease ends`() {
        cache.lease("a") {
            advance(30.minutes)
            assertEquals(0, cache.releaseIdle())
        }
        assertEquals(emptyList<String>(), closed)

        advance(4.minutes)
        assertEquals(0, cache.releaseIdle())
        advance(2.minutes)
        assertEquals(1, cache.releaseIdle())
        assertEquals(listOf("a"), closed)
    }

    @Test
    fun `a released entry is recreated on next use`() {
        cache.get("a")
        advance(6.minutes)
        cache.releaseIdle()

        cache.get("a")

        assertEquals(listOf("a", "a"), created)
        assertEquals(1, cache.size)
    }

    @Test
    fun `lease releases its hold when the block throws`() {
        runCatching { cache.lease("a") { error("boom") } }

        advance(6.minutes)
        assertEquals(1, cache.releaseIdle())
    }

    @Test
    fun `clear closes everything including leased entries`() {
        cache.get("a")
        cache.get("b")

        cache.clear()

        assertEquals(setOf("a", "b"), closed.toSet())
        assertEquals(0, cache.size)
    }

    @Test
    fun `remove closes only matching keys`() {
        cache.get("keep")
        cache.get("drop")

        cache.remove { it == "drop" }

        assertEquals(listOf("drop"), closed)
        assertEquals(listOf("keep"), cache.keys)
    }

    @Test
    fun `acquire protects the entry until the lease is closed, even across suspension points`() {
        val lease = cache.acquire("a")
        advance(30.minutes)
        assertEquals(0, cache.releaseIdle())

        lease.close()
        lease.close() // closing twice must not corrupt the count
        advance(6.minutes)

        assertEquals(1, cache.releaseIdle())
    }

    @Test
    fun `a failing close does not stop the remaining entries from being closed`() {
        failingKeys += "bad"
        cache.get("bad")
        cache.get("good1")
        cache.get("good2")
        advance(6.minutes)

        assertEquals(3, cache.releaseIdle())

        assertEquals(setOf("bad", "good1", "good2"), closed.toSet())
        assertEquals(0, cache.size)
    }

    @Test
    fun `clear closes every entry even when one close throws`() {
        failingKeys += "a"
        cache.get("a")
        cache.get("b")

        cache.clear()

        assertEquals(setOf("a", "b"), closed.toSet())
    }
}
