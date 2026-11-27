// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.telemetry

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StartupPhaseRecorderTest {
    private var nowNanos = ORIGIN_NANOS
    private val recorder = StartupPhaseRecorder(nanoTime = { nowNanos }, serverStartJvmUptimeMs = 250)

    private fun advanceMillis(millis: Long) {
        nowNanos += millis * NANOS_PER_MILLI
    }

    @Test
    fun `every phase is pending until it begins`() {
        val snapshot = recorder.snapshot()

        assertTrue(snapshot.phases.isEmpty())
        assertEquals(250, snapshot.serverStartJvmUptimeMs)
    }

    @Test
    fun `a completed phase reports its offset and duration from the server start`() {
        advanceMillis(20)
        val run = assertNotNull(recorder.begin(StartupPhase.WORKSPACE_SETUP))
        advanceMillis(30)
        run.complete()
        advanceMillis(5)

        val snapshot = recorder.snapshot()
        assertEquals(
            PhaseRecord(PhaseStatus.COMPLETED, startNanos = 20 * NANOS_PER_MILLI, endNanos = 50 * NANOS_PER_MILLI),
            snapshot.phases[StartupPhase.WORKSPACE_SETUP],
        )
        assertEquals(55 * NANOS_PER_MILLI, snapshot.observedNanos)
    }

    @Test
    fun `a running phase has a start but no end`() {
        advanceMillis(7)
        recorder.begin(StartupPhase.DEPENDENCIES_RESOLVE)

        assertEquals(
            PhaseRecord(PhaseStatus.RUNNING, startNanos = 7 * NANOS_PER_MILLI, endNanos = null),
            recorder.snapshot().phases[StartupPhase.DEPENDENCIES_RESOLVE],
        )
    }

    @Test
    fun `a phase begins only once and keeps its first record`() {
        val first = assertNotNull(recorder.begin(StartupPhase.WORKSPACE_INDEX))
        advanceMillis(10)
        first.complete()
        val before = recorder.snapshot().phases[StartupPhase.WORKSPACE_INDEX]

        assertNull(recorder.begin(StartupPhase.WORKSPACE_INDEX))

        assertSame(before, recorder.snapshot().phases[StartupPhase.WORKSPACE_INDEX])
    }

    @Test
    fun `the first end wins`() {
        val run = assertNotNull(recorder.begin(StartupPhase.DEPENDENCIES_RESOLVE))
        advanceMillis(10)
        run.complete()
        advanceMillis(10)
        run.fail()
        run.cancel()

        assertEquals(
            PhaseRecord(PhaseStatus.COMPLETED, startNanos = 0, endNanos = 10 * NANOS_PER_MILLI),
            recorder.snapshot().phases[StartupPhase.DEPENDENCIES_RESOLVE],
        )
    }

    @Test
    fun `measure records a failure and rethrows the same exception`() {
        val failure = IllegalStateException("boom")

        val thrown = assertFailsWith<IllegalStateException> {
            recorder.measure(StartupPhase.WORKSPACE_COMPILE) { throw failure }
        }

        assertSame(failure, thrown)
        assertEquals(PhaseStatus.FAILED, recorder.snapshot().phases[StartupPhase.WORKSPACE_COMPILE]?.status)
    }

    @Test
    fun `measure records a cancellation and rethrows it`() {
        val cancellation = CancellationException("stopped")

        val thrown = assertFailsWith<CancellationException> {
            recorder.measure(StartupPhase.WORKSPACE_INDEX) { throw cancellation }
        }

        assertSame(cancellation, thrown)
        assertEquals(PhaseStatus.CANCELLED, recorder.snapshot().phases[StartupPhase.WORKSPACE_INDEX]?.status)
    }

    @Test
    fun `measure records an Error as a failure and rethrows the same instance`() {
        val error = SimulatedError()

        val thrown = assertFailsWith<SimulatedError> {
            recorder.measure(StartupPhase.WORKSPACE_INDEX) { throw error }
        }

        assertSame(error, thrown)
        assertEquals(PhaseStatus.FAILED, recorder.snapshot().phases[StartupPhase.WORKSPACE_INDEX]?.status)
    }

    @Test
    fun `measure returns the block result and records completion`() {
        val result = recorder.measure(StartupPhase.WORKSPACE_SETUP) {
            advanceMillis(4)
            "done"
        }

        assertEquals("done", result)
        assertEquals(
            PhaseRecord(PhaseStatus.COMPLETED, startNanos = 0, endNanos = 4 * NANOS_PER_MILLI),
            recorder.snapshot().phases[StartupPhase.WORKSPACE_SETUP],
        )
    }

    @Test
    fun `measure still runs the block when the phase already ran`() {
        recorder.measure(StartupPhase.WORKSPACE_SETUP) {}
        var ranAgain = false

        recorder.measure(StartupPhase.WORKSPACE_SETUP) { ranAgain = true }

        assertTrue(ranAgain)
    }

    @Test
    fun `skip marks a phase that never ran and leaves a phase that began alone`() {
        recorder.begin(StartupPhase.WORKSPACE_SETUP)

        recorder.skip(listOf(StartupPhase.WORKSPACE_SETUP, StartupPhase.WORKSPACE_INDEX))

        val phases = recorder.snapshot().phases
        assertEquals(PhaseStatus.RUNNING, phases[StartupPhase.WORKSPACE_SETUP]?.status)
        assertEquals(
            PhaseRecord(PhaseStatus.SKIPPED, startNanos = null, endNanos = null),
            phases[StartupPhase.WORKSPACE_INDEX],
        )
    }

    @Test
    fun `a skipped phase that later runs reports that run`() {
        recorder.skip(listOf(StartupPhase.WORKSPACE_INDEX))
        advanceMillis(6)

        val run = assertNotNull(recorder.begin(StartupPhase.WORKSPACE_INDEX))
        advanceMillis(4)
        run.complete()

        assertEquals(
            PhaseRecord(PhaseStatus.COMPLETED, startNanos = 6 * NANOS_PER_MILLI, endNanos = 10 * NANOS_PER_MILLI),
            recorder.snapshot().phases[StartupPhase.WORKSPACE_INDEX],
        )
    }

    @Test
    fun `a phase that began is never replaced by a later skip or begin`() {
        val run = assertNotNull(recorder.begin(StartupPhase.WORKSPACE_COMPILE))
        run.complete()
        val before = recorder.snapshot().phases[StartupPhase.WORKSPACE_COMPILE]

        recorder.skip(listOf(StartupPhase.WORKSPACE_COMPILE))
        assertNull(recorder.begin(StartupPhase.WORKSPACE_COMPILE))

        assertSame(before, recorder.snapshot().phases[StartupPhase.WORKSPACE_COMPILE])
    }

    @Test
    fun `a snapshot is never observed before any offset it holds`() {
        var ticks = 0L
        val ticking = StartupPhaseRecorder(nanoTime = { ++ticks }, serverStartJvmUptimeMs = null)
        ticking.begin(StartupPhase.LSP_INITIALIZE)?.complete()
        ticking.begin(StartupPhase.WORKSPACE_SETUP)
        ticking.skip(listOf(StartupPhase.WORKSPACE_COMPILE))

        val snapshot = ticking.snapshot()

        val offsets = snapshot.phases.values.flatMap { listOfNotNull(it.startNanos, it.endNanos) }
        assertTrue(offsets.isNotEmpty())
        assertTrue(offsets.all { it <= snapshot.observedNanos }, "$offsets after ${snapshot.observedNanos}")
    }

    @Test
    fun `racing threads get exactly one run for a phase`() {
        val threads = 8
        val executor = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        try {
            val results = (1..threads).map {
                executor.submit<PhaseRun?> {
                    gate.await()
                    recorder.begin(StartupPhase.WORKSPACE_MODEL_PUBLISH)
                }
            }
            gate.countDown()

            val runs = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, runs.count { it != null })
        } finally {
            executor.shutdownNow()
        }
    }

    private class SimulatedError : Error("a stand-in for a JVM error")

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val ORIGIN_NANOS = 5_000_000_000L
    }
}
