package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.services.Health
import com.github.albertocavalcante.gvy.gls.services.StatusNotification
import com.github.albertocavalcante.gvy.gls.testing.client.HarnessLanguageClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HarnessLanguageClientTest {
    @Test
    fun `readiness completes only after the server reports a quiescent healthy state`() {
        val client = HarnessLanguageClient()
        client.groovyStatus(StatusNotification(health = Health.Ok, quiescent = false))
        assertThat(client.readyFuture).isNotDone()
        client.groovyStatus(StatusNotification(health = Health.Ok, quiescent = true))
        assertThat(client.readyFuture.join()).isEqualTo(Unit)
    }

    @Test
    fun `readiness preserves the server error`() {
        val client = HarnessLanguageClient()
        client.groovyStatus(StatusNotification(health = Health.Error, message = "dependency resolution failed"))
        assertThatThrownBy { client.readyFuture.join() }.hasRootCauseMessage("dependency resolution failed")
    }

    @Test
    fun `a matched notification is consumed once`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("match")
        val first = client.awaitNotificationDetailed("telemetry/event", 0, { it == "match" })
        val second = client.awaitNotificationDetailed("telemetry/event", 0, { it == "match" })
        assertThat(first.envelope?.payload).isEqualTo("match")
        assertThat(second.envelope).isNull()
    }

    @Test
    fun `each candidate is evaluated only once per wait`() {
        val client = HarnessLanguageClient()
        repeat(QUEUE_SIZE) { client.telemetryEvent(it) }
        var evaluations = 0
        val result = client.awaitNotificationDetailed("telemetry/event", 0, {
            evaluations++
            false
        })
        assertThat(result.envelope).isNull()
        assertThat(result.matchedMethodButFailed).hasSize(QUEUE_SIZE)
        assertThat(evaluations).isEqualTo(QUEUE_SIZE)
    }

    @Test
    fun `old mismatches do not hide new arrivals in timeout evidence`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("old")
        var skipChecks = 0
        val result = client.awaitNotificationDetailed("telemetry/event", CALLBACK_WAIT_MS, { false }, {
            skipChecks++
            if (skipChecks == 1) client.telemetryEvent("new")
            skipChecks > 1
        })
        assertThat(result.receivedDuringWait.map { it.payload }).contains("old", "new")
    }

    @Test
    fun `arrivals during a predicate are retained even when the deadline has expired`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("old")
        val result = client.awaitNotificationDetailed("telemetry/event", 0, {
            client.telemetryEvent("new")
            false
        })
        assertThat(result.receivedDuringWait.map { it.payload }).contains("old", "new")
    }

    @Test
    fun `predicate exceptions are retained as mismatch reasons`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val result = client.awaitNotificationDetailed("telemetry/event", 0, {
            throw IllegalArgumentException("invalid payload")
        })
        assertThat(result.envelope).isNull()
        assertThat(result.matchedMethodButFailed).singleElement()
            .extracting("reason").isEqualTo("invalid payload")
    }

    @Test
    fun `predicate evaluation does not prevent the server publishing another event`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val publisher = Executors.newSingleThreadExecutor()
        try {
            val result = client.awaitNotificationDetailed("telemetry/event", 0, {
                publisher.submit { client.telemetryEvent("arrived") }.get(1, TimeUnit.SECONDS)
                true
            })
            assertThat(result.envelope?.payload).isEqualTo("candidate")
        } finally {
            publisher.shutdownNow()
            assertThat(publisher.awaitTermination(2, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `checkpoint waits ignore historical matches and keep events arriving before the wait`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("old")
        val checkpoint = client.notificationCheckpoint()
        client.telemetryEvent("new")
        val result = client.awaitNotificationDetailed("telemetry/event", 0, { true }, afterSequence = checkpoint)
        assertThat(result.envelope?.payload).isEqualTo("new")
        assertThat(client.notificationCheckpoint()).isGreaterThan(checkpoint)
        assertThat(client.awaitNotificationDetailed("telemetry/event", 0, { true }).envelope?.payload).isEqualTo("old")
    }

    @Test
    fun `a checkpoint cannot be satisfied by a historical notification alone`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("old")
        val result = client.awaitNotificationDetailed(
            "telemetry/event",
            0,
            { true },
            afterSequence = client.notificationCheckpoint(),
        )
        assertThat(result.envelope).isNull()
        assertThat(result.matchedMethodButFailed).isEmpty()
    }

    @Test
    fun `a cancelled predicate cancels the wait`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        assertThatThrownBy {
            client.awaitNotificationDetailed("telemetry/event", 0, { throw CancellationException("cancelled") })
        }.isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `an interrupted predicate stops the wait and preserves the interrupt`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        try {
            assertThatThrownBy {
                client.awaitNotificationDetailed("telemetry/event", 0, {
                    Thread.currentThread().interrupt()
                    CountDownLatch(1).await()
                    false
                })
            }.isInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a checkpoint from outside this journal sequence is rejected`() {
        val client = HarnessLanguageClient()
        assertThatThrownBy {
            client.awaitNotificationDetailed("telemetry/event", 0, { true }, afterSequence = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            client.awaitNotificationDetailed("telemetry/event", 0, { true }, afterSequence = 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `competing waits cannot consume the same envelope twice`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val evaluated = CountDownLatch(2)
        val waiters = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                waiters.submit<Any?> {
                    client.awaitNotificationDetailed("telemetry/event", 0, {
                        evaluated.countDown()
                        check(evaluated.await(2, TimeUnit.SECONDS)) {
                            "The other waiter did not evaluate its candidate"
                        }
                        true
                    }).envelope?.payload
                }
            }.map { it.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            assertThat(results).containsExactlyInAnyOrder("candidate", null)
        } finally {
            waiters.shutdownNow()
            assertThat(waiters.awaitTermination(2, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `peeking does not hold the publisher lock or consume a match`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val publisher = Executors.newSingleThreadExecutor()
        try {
            assertThat(
                client.peekNotification("telemetry/event") {
                    publisher.submit { client.telemetryEvent("arrived") }.get(1, TimeUnit.SECONDS)
                    true
                },
            ).isTrue()
            assertThat(client.awaitNotificationDetailed("telemetry/event", 0, { true }).envelope?.payload)
                .isEqualTo("candidate")
        } finally {
            publisher.shutdownNow()
            assertThat(publisher.awaitTermination(2, TimeUnit.SECONDS)).isTrue()
        }
    }

    private companion object {
        const val QUEUE_SIZE = 32
        const val CALLBACK_WAIT_MS = 20L
        const val FUTURE_TIMEOUT_SECONDS = 3L
    }
}
