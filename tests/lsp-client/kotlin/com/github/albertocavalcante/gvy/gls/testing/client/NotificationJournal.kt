package com.github.albertocavalcante.gvy.gls.testing.client

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Owns arrival ordering and consumption; predicates run outside its lock so publishers can always proceed. */
internal class NotificationJournal {
    private val logger = KotlinLogging.logger {}
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val notifications = mutableListOf<NotificationEnvelope>()
    private val consumed = mutableSetOf<Long>()
    private var sequence = 0L

    fun record(method: String, payload: Any?) = lock.withLock {
        notifications += NotificationEnvelope(++sequence, method, payload, Instant.now())
        arrived.signalAll()
    }

    fun checkpoint(): Long = lock.withLock { sequence }

    fun peek(method: String, predicate: (Any?) -> Boolean): Boolean {
        val candidates = lock.withLock {
            notifications.filter { it.method == method && it.id !in consumed }
        }
        return candidates.any { failureReason(it.payload, predicate) == null }
    }

    fun await(
        method: String,
        timeoutMs: Long,
        predicate: (Any?) -> Boolean,
        earlySkipPredicate: (() -> Boolean)?,
        afterSequence: Long,
    ): WaitResult {
        require(timeoutMs >= 0) { "Notification timeout must not be negative" }
        val start = System.nanoTime()
        val timeout = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val initialSequence = lock.withLock {
            require(afterSequence in 0..sequence) { "Notification checkpoint is outside this journal's sequence" }
            sequence
        }
        val evidence = WaitEvidence(start, initialSequence)
        var cursor = afterSequence.toInt()
        var nextProgress = PROGRESS_INTERVAL_NS

        fun result(envelope: NotificationEnvelope? = null, skipped: Boolean = false): WaitResult {
            val tail = lock.withLock { notifications.subList(cursor, notifications.size).toList() }
            evidence.recordArrivals(tail)
            return evidence.result(envelope, skipped)
        }

        while (true) {
            val batch = lock.withLock {
                notifications.subList(cursor, notifications.size).toList().also { cursor = notifications.size }
            }
            evidence.recordArrivals(batch)
            if (earlySkipPredicate?.invoke() == true) return result(skipped = true)

            val match = findMatch(batch, method, predicate, evidence)
            if (match != null) return result(match)

            val elapsed = System.nanoTime() - start
            val remaining = timeout - elapsed
            if (remaining <= 0) return result()
            if (timeoutMs > PROGRESS_THRESHOLD_MS && elapsed >= nextProgress) {
                logger.info { "Still waiting for '$method' after ${elapsed / TimeUnit.SECONDS.toNanos(1)}s" }
                nextProgress = elapsed + PROGRESS_INTERVAL_NS
            }
            lock.withLock {
                // Recheck under the publisher's lock: an arrival during a predicate must not lose its wakeup.
                if (cursor == notifications.size) arrived.awaitNanos(minOf(remaining, PROGRESS_INTERVAL_NS))
            }
        }
    }

    private fun findMatch(
        batch: List<NotificationEnvelope>,
        method: String,
        predicate: (Any?) -> Boolean,
        evidence: WaitEvidence,
    ): NotificationEnvelope? {
        for (candidate in batch) {
            if (candidate.method != method || lock.withLock { candidate.id in consumed }) continue
            val reason = failureReason(candidate.payload, predicate)
            if (reason != null) {
                evidence.recordFailure(candidate, reason)
            } else if (lock.withLock { consumed.add(candidate.id) }) {
                return candidate
            }
        }
        return null
    }

    private class WaitEvidence(private val start: Long, private val initialSequence: Long) {
        private val received = mutableListOf<NotificationSnapshot>()
        private val failures = mutableListOf<PredicateFailure>()

        fun recordArrivals(batch: List<NotificationEnvelope>) {
            batch.filter { it.id > initialSequence }.forEach { received += it.snapshot() }
        }

        fun recordFailure(envelope: NotificationEnvelope, reason: String) {
            failures += PredicateFailure(envelope, reason)
            if (envelope.id <= initialSequence) received += envelope.snapshot()
        }

        fun result(envelope: NotificationEnvelope? = null, skipped: Boolean = false): WaitResult = WaitResult(
            envelope = envelope,
            receivedDuringWait = received.toList(),
            matchedMethodButFailed = failures.toList(),
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
            skipped = skipped,
        )

        private fun NotificationEnvelope.snapshot() = NotificationSnapshot(method, timestamp, payload)
    }

    private companion object {
        const val PROGRESS_THRESHOLD_MS = 10_000
        val PROGRESS_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5)

        // Predicates are caller-provided and may throw any Exception. Cancellation and interruption remain fatal.
        @Suppress("TooGenericExceptionCaught")
        fun failureReason(payload: Any?, predicate: (Any?) -> Boolean): String? = try {
            if (predicate(payload)) null else "Predicate returned false"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: Exception) {
            failure.message ?: failure.javaClass.name
        }
    }
}
