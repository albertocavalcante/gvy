// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.telemetry

import kotlinx.coroutines.CancellationException

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
 * A phase records its first run only: a later [begin] returns null and a skipped phase never begins. The server start
 * is the moment the recorder is created. Calls do not block and never call out, so they are safe under any lock.
 */
class StartupPhaseRecorder(
    private val nanoTime: () -> Long = System::nanoTime,
    /** How long the JVM had run when the recorder was created, or null when the JVM does not say. */
    private val serverStartJvmUptimeMs: Long? = null,
) {
    /** Starts [phase], or returns null when it already began or was skipped. */
    fun begin(phase: StartupPhase): PhaseRun? = TODO("record the start of $phase")

    /** Marks each phase that has not begun as skipped. */
    fun skip(vararg phases: StartupPhase): Unit = TODO("record the skip of ${phases.toList()}")

    internal fun snapshot(): StartupSnapshot = TODO("snapshot at ${nanoTime()} with $serverStartJvmUptimeMs")

    internal fun end(phase: StartupPhase, started: PhaseRecord, status: PhaseStatus): Unit =
        TODO("record the end of $phase as $status after $started")
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
