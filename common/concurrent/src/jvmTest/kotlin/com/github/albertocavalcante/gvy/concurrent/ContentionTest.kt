// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.concurrent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val THREADS = 8
private const val ITERATIONS = 10_000
private const val TOTAL = THREADS * ITERATIONS
private const val ROUNDS = 10
private const val TIMEOUT_SECONDS = 30L

/** How long [ContentionTest] waits for the racing threads to start, and then for each to finish. */
private const val RACER_TIMEOUT_SECONDS = 10L

/** How many offending values a failure message lists. */
private const val SAMPLE_SIZE = 10

/**
 * Races real threads against each primitive. Common code has no thread API, so these tests live in `jvmTest`.
 *
 * Each test checks the results callers rely on, not only a final total: one winner per flag, and a distinct result
 * from every call. An implementation that reads and then writes in two steps can keep the total right and still hand
 * two callers the same result. Even threads released together overlap on any one call only rarely, so every thread
 * repeats the operation many times, and each test repeats the whole race on fresh state, to make such an overlap near
 * certain.
 */
@Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
class ContentionTest {

    @Test
    fun `exactly one racing caller sets each OnceFlag`() {
        repeat(ROUNDS) { round ->
            // Every thread walks the same flags in the same order, so threads keep meeting on the same flag.
            val flags = List(ITERATIONS) { OnceFlag() }

            val won = race { flags.indices.filter { index -> flags[index].trySet() } }

            assertEachExactlyOnce(flags.indices, won.flatten(), "flag won in round $round")
        }
    }

    @Test
    fun `racing increments of a Counter return each count exactly once`() {
        repeat(ROUNDS) { round ->
            val counter = Counter()

            val counts = race { LongArray(ITERATIONS) { counter.increment() } }

            assertEachExactlyOnce(1L..TOTAL, counts.flatMap { it.asList() }, "count returned in round $round")
            assertEquals(TOTAL.toLong(), counter.value)
        }
    }

    @Test
    fun `racing updates of a SharedValue return each updated value exactly once`() {
        repeat(ROUNDS) { round ->
            // Boxed values past the small-integer cache are distinct objects, so this also checks that update
            // compares against the instance it read rather than an equal one.
            val shared = SharedValue(0)

            val updated = race { IntArray(ITERATIONS) { shared.update { it + 1 } } }

            assertEachExactlyOnce(1..TOTAL, updated.flatMap { it.asList() }, "updated value returned in round $round")
            assertEquals(TOTAL, shared.value)
        }
    }

    @Test
    fun `racing exchanges of a SharedValue return each replaced value exactly once`() {
        repeat(ROUNDS) { round ->
            val shared = SharedValue(0)

            // Racer r stores r * ITERATIONS + 1 through (r + 1) * ITERATIONS, so no two exchanges store the same value.
            val replaced = race { racer -> IntArray(ITERATIONS) { shared.exchange(racer * ITERATIONS + it + 1) } }

            // Every value stored, the initial one included, is replaced by exactly one exchange or is still current.
            val handedBack = replaced.flatMap { it.asList() } + shared.value
            assertEachExactlyOnce(0..TOTAL, handedBack, "replaced value returned in round $round")
        }
    }

    /**
     * Runs [action] on [THREADS] threads released together, passing each its index, and returns what each thread's
     * action returned, in no particular order. Rethrows the first failure.
     *
     * The release waits until every thread is ready, and the threads spin on a flag rather than park on a latch, which
     * would wake them one after another. The flag is released even when starting the threads fails, so a thread already
     * spinning runs its finite action and exits instead of spinning for the rest of the JVM.
     */
    private fun <R : Any> race(action: (racer: Int) -> R): List<R> {
        val ready = CountDownLatch(THREADS)
        val released = AtomicBoolean(false)
        val results = ConcurrentLinkedQueue<R>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val threads = mutableListOf<Thread>()
        try {
            repeat(THREADS) { index ->
                val racer = Thread(
                    {
                        ready.countDown()
                        while (!released.get()) Thread.onSpinWait()
                        runCatching { action(index) }.onSuccess(results::add).onFailure(failures::add)
                    },
                    "racer-$index",
                )
                racer.isDaemon = true
                racer.start()
                threads += racer
            }
            check(ready.await(RACER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "The racing threads did not start" }
        } finally {
            released.set(true)
        }

        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(RACER_TIMEOUT_SECONDS)) }
        check(threads.none(Thread::isAlive)) { "A racing thread did not finish" }

        failures.firstOrNull()?.let { throw AssertionError("A racing thread failed", it) }
        return results.toList()
    }

    /** Asserts that [actual] holds every value of [expected] exactly once, and no other value. */
    private fun <T> assertEachExactlyOnce(expected: Iterable<T>, actual: List<T>, what: String) {
        val counts = actual.groupingBy { it }.eachCount()
        val repeated = counts.filterValues { it > 1 }.keys
        val missing = expected.filterNot { it in counts }
        val unexpected = counts.keys - expected.toSet()

        assertTrue(
            repeated.isEmpty() && missing.isEmpty() && unexpected.isEmpty(),
            "Expected each $what exactly once. " +
                "Repeated: ${repeated.size}, such as ${repeated.take(SAMPLE_SIZE)}. " +
                "Missing: ${missing.size}, such as ${missing.take(SAMPLE_SIZE)}. " +
                "Unexpected: ${unexpected.size}, such as ${unexpected.take(SAMPLE_SIZE)}.",
        )
    }
}
