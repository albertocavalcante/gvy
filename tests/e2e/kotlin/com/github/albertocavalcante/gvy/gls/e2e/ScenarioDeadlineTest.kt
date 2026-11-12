// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ScenarioDeadlineTest {
    @Test
    fun `scenario completion waits for its owned worker thread to actually exit`() {
        val runnableFinished = CountDownLatch(1)
        val allowExit = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val factory = ThreadFactory { task ->
            Thread {
                task.run()
                runnableFinished.countDown()
                awaitIgnoringInterruptions(allowExit)
            }.apply {
                isDaemon = true
                worker.set(this)
            }
        }
        val execution = ScenarioExecution(NORMAL_TIMEOUT, NORMAL_TIMEOUT, factory)
        val completed = FutureTask<Unit> { execution.run("actual-worker-exit") {} }
        val caller = Thread(completed).apply { isDaemon = true }

        try {
            caller.start()
            assertThat(runnableFinished.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            assertThat(worker.get().isAlive).isTrue()
            assertThatThrownBy { completed.get(EARLY_EXIT_WAIT_MS, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)
            allowExit.countDown()
            completed.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertThat(worker.get().isAlive).isFalse()
        } finally {
            allowExit.countDown()
            worker.get()?.join(JOIN_TIMEOUT_MS)
            caller.join(JOIN_TIMEOUT_MS)
        }
    }

    @Test
    fun `an already interrupted caller starts no scenario work`() {
        val started = AtomicBoolean()
        try {
            Thread.currentThread().interrupt()

            assertThatThrownBy {
                ScenarioExecution(NORMAL_TIMEOUT).run("already-cancelled") { started.set(true) }
            }.isInstanceOf(InterruptedException::class.java)

            assertThat(started).isFalse()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a real deadline before the scenario action starts never runs that action`() {
        val started = AtomicBoolean()
        val execution = ScenarioExecution(
            SHORT_TIMEOUT,
            NORMAL_TIMEOUT,
            beforeAction = {
                try {
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    // Hold before business action until cancellation, if dispatched.
                    // Cancellation before dispatch is valid too.
                }
            },
        )

        assertThatThrownBy { execution.run("before-action") { started.set(true) } }
            .isInstanceOf(ScenarioDeadlineExceeded::class.java)

        assertThat(started).isFalse()
    }

    @Test
    fun `an overflowing timeout fails before any scenario work starts`() {
        val started = AtomicBoolean()

        assertThatThrownBy {
            ScenarioExecution(Duration.ofMillis(Long.MAX_VALUE)).run("overflow") { started.set(true) }
        }.isInstanceOf(ArithmeticException::class.java)

        assertThat(started).isFalse()
    }

    @Test
    fun `an ordinary scenario failure is preserved and its worker terminates`() {
        val failure = IOException("scenario failed")
        lateinit var worker: Thread

        assertThatThrownBy {
            ScenarioExecution(NORMAL_TIMEOUT).run("failure") {
                worker = Thread.currentThread()
                throw failure
            }
        }.isSameAs(failure)

        assertThat(worker.isAlive).isFalse()
    }

    @Test
    fun `a step timeout before the whole deadline keeps its original failure`() {
        val failure = TimeoutException("step request timed out")

        assertThatThrownBy { ScenarioExecution(NORMAL_TIMEOUT).run("step-timeout") { throw failure } }
            .isSameAs(failure)
    }

    @Test
    fun `deadline cancellation allows resource cleanup to finish without another interrupt`() {
        val cleanupStarted = CountDownLatch(1)
        val actionStarted = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleanupInterrupted = AtomicBoolean()
        val execution = expiringAfter(actionStarted)
        val completed = FutureTask<Throwable?> {
            catchThrowable {
                execution.run("one-cancellation") {
                    actionStarted.countDown()
                    awaitCancellationThenCleanup(cleanupStarted, allowCleanup, cleanupInterrupted)
                }
            }
        }
        val caller = Thread(completed).apply { isDaemon = true }

        try {
            caller.start()
            assertThat(cleanupStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { completed.get(EARLY_EXIT_WAIT_MS, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)
            allowCleanup.countDown()
            assertThat(completed.get(WAIT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(ScenarioDeadlineExceeded::class.java)
            assertThat(cleanupInterrupted).isFalse()
        } finally {
            allowCleanup.countDown()
            caller.join(JOIN_TIMEOUT_MS)
        }
    }

    @Test
    fun `deadline cancellation preserves a failure from worker cleanup`() {
        val cleanupFailure = IOException("cleanup failed")
        val entered = CountDownLatch(1)
        val execution = expiringAfter(entered)

        val observed = checkNotNull(
            catchThrowable {
                execution.run("cleanup") {
                    entered.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (_: InterruptedException) {
                        // Model resource cleanup failing after the blocking action is cancelled.
                        throw cleanupFailure
                    }
                }
            },
        )

        assertThat(entered.count).isZero()
        assertThat(observed).isInstanceOf(ScenarioDeadlineExceeded::class.java)
        assertThat(observed.suppressed).containsExactly(cleanupFailure)
    }

    @Test
    fun `an interruption raised during cleanup is retained under the whole deadline failure`() {
        val cleanupEntered = CountDownLatch(1)
        val execution = expiringAfter(cleanupEntered)

        val observed = checkNotNull(
            catchThrowable {
                execution.run("interrupted-cleanup") {
                    AutoCloseable {
                        cleanupEntered.countDown()
                        CountDownLatch(1).await()
                    }.use { }
                }
            },
        )

        assertThat(observed).isInstanceOf(ScenarioDeadlineExceeded::class.java)
        assertThat(observed.suppressed).hasSize(1)
        assertThat(observed.suppressed.single()).isInstanceOf(InterruptedException::class.java)
    }

    @Test
    fun `a worker which ignores interruption reports cleanup unconfirmed and can still be released`() {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val execution = expiringAfter(entered, CLEANUP_TIMEOUT)
        val worker = AtomicReference<Thread>()

        try {
            val observed = checkNotNull(
                catchThrowable {
                    execution.run("uncooperative") {
                        worker.set(Thread.currentThread())
                        entered.countDown()
                        try {
                            awaitIgnoringInterruptions(release)
                        } finally {
                            finished.countDown()
                        }
                    }
                },
            )

            assertThat(entered.count).isZero()
            assertThat(observed).isInstanceOf(ScenarioDeadlineExceeded::class.java)
            assertThat(observed.suppressed).hasSize(1)
            assertThat(observed.suppressed.single()).hasMessageContaining("resource cleanup is not confirmed")
            assertThat(worker.get().isAlive).isTrue()
        } finally {
            release.countDown()
            worker.get()?.join(JOIN_TIMEOUT_MS)
        }
        assertThat(finished.count).isZero()
        assertThat(worker.get().isAlive).isFalse()
    }

    @Test
    fun `external interruption cancels the worker and preserves the caller interrupt signal`() {
        val entered = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val caller = Thread {
            try {
                ScenarioExecution(EXTERNAL_TIMEOUT).run("external-cancel") {
                    entered.countDown()
                    try {
                        CountDownLatch(1).await()
                    } finally {
                        cleaned.countDown()
                    }
                }
            } catch (observed: InterruptedException) {
                failure.set(observed)
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }.apply { isDaemon = true }

        try {
            caller.start()
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            caller.interrupt()
            caller.join(JOIN_TIMEOUT_MS)

            assertThat(caller.isAlive).isFalse()
            assertThat(failure.get()).isInstanceOf(InterruptedException::class.java)
            assertThat(interrupted).isTrue()
            assertThat(cleaned.count).isZero()
        } finally {
            caller.interrupt()
            caller.join(JOIN_TIMEOUT_MS)
        }
    }

    private fun awaitIgnoringInterruptions(release: CountDownLatch) {
        while (release.count > 0) {
            try {
                release.await()
            } catch (_: InterruptedException) {
                // These controls deliberately model a worker which refuses cancellation.
            }
        }
    }

    private fun expiringAfter(entered: CountDownLatch, cleanupTimeout: Duration = NORMAL_TIMEOUT) = ScenarioExecution(
        NORMAL_TIMEOUT,
        cleanupTimeout = cleanupTimeout,
        awaitOutcome = { _, _ ->
            check(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "Fixture action did not start" }
            throw TimeoutException("Controlled expiration after action entry")
        },
    )

    private fun awaitCancellationThenCleanup(
        cleanupStarted: CountDownLatch,
        allowCleanup: CountDownLatch,
        cleanupInterrupted: AtomicBoolean,
    ) {
        try {
            CountDownLatch(1).await()
        } finally {
            cleanupStarted.countDown()
            try {
                allowCleanup.await()
            } catch (_: InterruptedException) {
                cleanupInterrupted.set(true)
            }
        }
    }

    private companion object {
        const val WAIT_SECONDS = 2L
        const val JOIN_TIMEOUT_MS = 2_000L
        const val EARLY_EXIT_WAIT_MS = 200L
        val NORMAL_TIMEOUT: Duration = Duration.ofSeconds(WAIT_SECONDS)
        val SHORT_TIMEOUT: Duration = Duration.ofMillis(200)
        val CLEANUP_TIMEOUT: Duration = Duration.ofMillis(100)
        val EXTERNAL_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
