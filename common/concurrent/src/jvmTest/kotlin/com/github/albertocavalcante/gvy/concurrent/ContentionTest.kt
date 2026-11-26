// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.concurrent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

private const val THREADS = 8
private const val ITERATIONS = 10_000
private const val TIMEOUT_SECONDS = 30L

/**
 * Races real threads against each primitive. Common code has no thread API, so these tests live in `jvmTest`; each
 * fails if a racing update is lost.
 */
@Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
class ContentionTest {

    @Test
    fun `exactly one of many racing callers sets a OnceFlag`() {
        val flag = OnceFlag()
        val winners = ConcurrentLinkedQueue<String>()

        race { if (flag.trySet()) winners.add(Thread.currentThread().name) }

        assertEquals(1, winners.size, "Winners: $winners")
    }

    @Test
    fun `racing updates of a SharedValue lose no update`() {
        // Boxed values past the small-integer cache are distinct objects, so this also checks that update compares
        // against the instance it read rather than an equal one.
        val shared = SharedValue(0)

        race { repeat(ITERATIONS) { shared.update { it + 1 } } }

        assertEquals(THREADS * ITERATIONS, shared.value)
    }

    @Test
    fun `racing increments of a Counter lose no count`() {
        val counter = Counter()

        race { repeat(ITERATIONS) { counter.increment() } }

        assertEquals(THREADS.toLong() * ITERATIONS, counter.value)
    }

    /** Runs [action] on [THREADS] threads released together, then rethrows the first failure. */
    private fun race(action: () -> Unit) {
        val startGate = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val threads = List(THREADS) { index ->
            val racer = Thread(
                {
                    startGate.await()
                    runCatching(action).onFailure(failures::add)
                },
                "racer-$index",
            )
            racer.start()
            racer
        }

        startGate.countDown()
        threads.forEach(Thread::join)

        failures.firstOrNull()?.let { throw AssertionError("A racing thread failed", it) }
    }
}
