// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.telemetry

import com.github.albertocavalcante.gvy.concurrent.SharedValue
import kotlinx.coroutines.CancellationException
import java.lang.management.ManagementFactory

/** The startup phases the server reports, in the order a normal startup runs them. */
enum class StartupPhase(val wireName: String) {
    LSP_INITIALIZE("lsp.initialize"),
    WORKSPACE_SETUP("workspace.setup"),
    DEPENDENCIES_RESOLVE("dependencies.resolve"),
    WORKSPACE_MODEL_PUBLISH("workspace.model.publish"),
    WORKSPACE_INDEX("workspace.index"),
    WORKSPACE_COMPILE("workspace.compile"),
}

enum class PhaseStatus(val wireName: String) {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    SKIPPED("skipped"),
}

/** One phase's recorded run. The times are nanoseconds after the recorder was created; absent until they happen. */
internal data class PhaseRecord(val status: PhaseStatus, val startNanos: Long?, val endNanos: Long?)

internal data class StartupSnapshot(
    val serverStartJvmUptimeMs: Long?,
    val observedNanos: Long,
    /** Only phases that began or were skipped; every other phase is pending. */
    val phases: Map<StartupPhase, PhaseRecord>,
)

/**
 * Records when each [StartupPhase] began and how it ended, using a monotonic clock.
 *
 * A phase records its first real run only: once a phase has begun, a later [begin] returns null and [skip] changes
 * nothing. A phase that was only skipped is replaced by its first real run. The server start is the moment the
 * recorder is created. Calls do not block and never call out, so they are safe under any lock.
 */
class StartupPhaseRecorder(
    private val nanoTime: () -> Long = System::nanoTime,
    /** How long the JVM had run when the recorder was created, or null when the JVM does not say. */
    private val serverStartJvmUptimeMs: Long? = observeJvmUptimeMs(),
) {
    private val originNanos = nanoTime()
    private val records = SharedValue<Map<StartupPhase, PhaseRecord>>(emptyMap())

    /** Starts [phase], or returns null when it already began. A skipped phase starts and replaces its skip. */
    fun begin(phase: StartupPhase): PhaseRun? {
        val started = PhaseRecord(PhaseStatus.RUNNING, startNanos = elapsedNanos(), endNanos = null)
        val updated = records.update { current ->
            if (current[phase].canBegin()) current + (phase to started) else current
        }
        return if (updated[phase] === started) PhaseRun(this, phase, started) else null
    }

    /** Marks each phase that has not begun as skipped. */
    fun skip(phases: Iterable<StartupPhase>) {
        val skipped = PhaseRecord(PhaseStatus.SKIPPED, startNanos = null, endNanos = null)
        records.update { current -> current + phases.filter { it !in current }.associateWith { skipped } }
    }

    internal fun snapshot() = StartupSnapshot(serverStartJvmUptimeMs, elapsedNanos(), records.value)

    internal fun end(phase: StartupPhase, started: PhaseRecord, status: PhaseStatus) {
        val ended = started.copy(status = status, endNanos = elapsedNanos())
        records.update { current -> if (current[phase] === started) current + (phase to ended) else current }
    }

    private fun elapsedNanos() = nanoTime() - originNanos
}

/** One started phase. Only the first of [complete], [fail] and [cancel] has an effect. */
class PhaseRun internal constructor(
    private val recorder: StartupPhaseRecorder,
    private val phase: StartupPhase,
    private val started: PhaseRecord,
) {
    fun complete() = end(PhaseStatus.COMPLETED)

    fun fail() = end(PhaseStatus.FAILED)

    fun cancel() = end(PhaseStatus.CANCELLED)

    internal fun end(status: PhaseStatus) = recorder.end(phase, started, status)
}

/**
 * Runs [block] as [phase]: completed when it returns, cancelled when it throws a [CancellationException], failed on
 * any other throw. The exception propagates. The block runs even when the phase already ran.
 */
internal inline fun <T> StartupPhaseRecorder.measure(phase: StartupPhase, block: () -> T): T {
    val run = begin(phase)
    var outcome = PhaseStatus.FAILED
    try {
        val result = block()
        outcome = PhaseStatus.COMPLETED
        return result
    } catch (cancellation: CancellationException) {
        outcome = PhaseStatus.CANCELLED
        throw cancellation
    } finally {
        run?.end(outcome)
    }
}

private fun PhaseRecord?.canBegin() = this == null || status == PhaseStatus.SKIPPED

private fun observeJvmUptimeMs(): Long? = try {
    ManagementFactory.getRuntimeMXBean().uptime
} catch (_: SecurityException) {
    null
}
