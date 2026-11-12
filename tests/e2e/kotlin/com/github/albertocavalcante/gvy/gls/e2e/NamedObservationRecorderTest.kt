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
