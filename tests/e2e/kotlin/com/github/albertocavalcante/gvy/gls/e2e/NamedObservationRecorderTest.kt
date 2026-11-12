// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NamedObservationRecorderTest {
    @Test
    fun `known cleanup failure does not claim other cleanup finished`() {
        val recorder = NamedObservationRecorder(emptyList(), "scenario.yaml")
        val failure = assertThrows<IOException> { recorder.closeSession { throw IOException("close failed") } }

        val outcome = recorder.finish(failure)

        val cleanup = outcome.cleanup as CleanupOutcome.Failed
        assertThat(cleanup.detail).contains("close failed")
        assertThat(cleanup.confirmed).isFalse()
    }

    @Test
    fun `failed executor close leaves thread termination unconfirmed`() {
        val recorder = NamedObservationRecorder(emptyList(), "scenario.yaml")
        recorder.closeSession { }
        recorder.closeWorkspace { }
        recorder.workerFinished()
        val failure = IOException("worker did not exit")
        recorder.executorClosed(failure)

        val cleanup = recorder.finish(failure).cleanup as CleanupOutcome.Failed

        assertThat(cleanup.confirmed).isFalse()
        assertThat(cleanup.detail).contains("worker did not exit")
    }

    @Test
    fun `same throwable from execution and cleanup retains both origins`() {
        val recorder = NamedObservationRecorder(emptyList(), "scenario.yaml")
        val shared = AssertionError("shared failure")
        recorder.executionStarted()
        recorder.primaryFailure(shared)
        assertThrows<AssertionError> { recorder.closeSession { throw shared } }
        recorder.closeWorkspace { }
        recorder.workerFinished()
        recorder.executorClosed(null)

        val outcome = recorder.finish(shared)

        val completion = outcome.completion as ScenarioCompletion.Failed
        assertThat(completion.kind).isEqualTo(ScenarioFailureKind.Product)
        assertThat(completion.detail).contains("shared failure")
        assertThat(outcome.cleanup).isInstanceOf(CleanupOutcome.Failed::class.java)
    }

    @Test
    fun `unclassified failure retains its nested cause`() {
        val recorder = NamedObservationRecorder(emptyList(), "scenario.yaml")
        val failure = IllegalStateException("outer", IOException("nested cause"))

        val completion = recorder.finish(failure).completion as ScenarioCompletion.Failed

        assertThat(completion.kind).isEqualTo(ScenarioFailureKind.Unclassified)
        assertThat(completion.detail).contains("outer", "nested cause")
    }

    @Test
    fun `failure evidence is byte bounded and marks truncation`() {
        val recorder = NamedObservationRecorder(emptyList(), "scenario.yaml")
        val failure = IllegalStateException("😀".repeat(20_000))

        val completion = recorder.finish(failure).completion as ScenarioCompletion.Failed

        assertThat(completion.kind).isEqualTo(ScenarioFailureKind.Unclassified)
        assertThat(completion.detail.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(16_384)
        assertThat(completion.detail).contains("[truncated]")
    }

    @Test
    fun `aggregate cleanup evidence has one bounded budget`() {
        val recorder = NamedObservationRecorder(emptyList(), "scenario.yaml")
        repeat(100) { index ->
            assertThrows<IOException> {
                recorder.closeSession { throw IOException("close-$index " + "x".repeat(2_000)) }
            }
        }

        val cleanup = recorder.finish(null).cleanup as CleanupOutcome.Failed

        assertThat(cleanup.detail.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(16_384)
        assertThat(cleanup.detail).contains("[truncated]")
    }

    @Test
    fun `late observation cannot change a published incomplete outcome`() {
        val recorder = NamedObservationRecorder(listOf("late"), "scenario.yaml")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            recorder.observe(JsonCheck("$", JsonExpectation(), id = "late")) {
                entered.countDown()
                release.await()
            }
        }
        try {
            worker.start()
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            val outcome = recorder.finish(ScenarioDeadlineExceeded("late", Duration.ofSeconds(1)))
            assertThat(outcome.blocked).containsExactly("late")
            assertThat(outcome.cleanup).isInstanceOf(CleanupOutcome.Unconfirmed::class.java)
            release.countDown()
            worker.join(5000)
            assertThat(worker.isAlive).isFalse()
            assertThat(outcome.observed).isEmpty()
            assertThat(outcome.blocked).containsExactly("late")
        } finally {
            release.countDown()
            worker.join(5000)
        }
    }
}
