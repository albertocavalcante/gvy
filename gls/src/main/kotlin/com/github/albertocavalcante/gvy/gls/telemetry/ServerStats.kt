// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.telemetry

import com.github.albertocavalcante.gvy.gls.BuildInfo
import com.github.albertocavalcante.gvy.gls.verification.EmbeddedBuildStamp
import com.github.albertocavalcante.gvy.gls.verification.embeddedBuildStamp

/**
 * The `groovy/serverStats` result. Every field is a number or a fixed vocabulary word, so it never carries a path,
 * a URI or a project name. Optional values are left out of the JSON when they are not known.
 */
data class ServerStats(val schemaVersion: Int, val build: EmbeddedBuildStamp, val startup: StartupStats)

data class StartupStats(
    val serverStartJvmUptimeMs: Long?,
    val observedOffsetMs: Long,
    /** Every phase, in [StartupPhase] order. */
    val phases: List<StartupPhaseStats>,
)

data class StartupPhaseStats(val name: String, val status: String, val startOffsetMs: Long?, val durationMs: Long?)

private const val SCHEMA_VERSION = 1
private const val NANOS_PER_MILLI = 1_000_000L

/** Pure conversion from a recorder snapshot, so every phase state can be tested without running a server. */
internal fun serverStats(build: BuildInfo, snapshot: StartupSnapshot): ServerStats = ServerStats(
    schemaVersion = SCHEMA_VERSION,
    build = embeddedBuildStamp(build),
    startup = StartupStats(
        serverStartJvmUptimeMs = snapshot.serverStartJvmUptimeMs,
        observedOffsetMs = snapshot.observedNanos / NANOS_PER_MILLI,
        phases = StartupPhase.entries.map { phase -> phaseStats(phase, snapshot.phases[phase]) },
    ),
)

private fun phaseStats(phase: StartupPhase, record: PhaseRecord?): StartupPhaseStats {
    val status = record?.status ?: PhaseStatus.PENDING
    val start = record?.startNanos
    val end = record?.endNanos
    return StartupPhaseStats(
        name = phase.wireName,
        status = status.wireName,
        startOffsetMs = start?.let { it / NANOS_PER_MILLI },
        durationMs = if (start != null && end != null) (end - start) / NANOS_PER_MILLI else null,
    )
}
