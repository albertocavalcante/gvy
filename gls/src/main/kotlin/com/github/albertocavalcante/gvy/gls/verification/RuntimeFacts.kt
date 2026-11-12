// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.verification

import com.github.albertocavalcante.gvy.gls.BuildInfo

/** Narrow producer metadata, not a build attestation or a claim about every language feature. */
data class RuntimeFacts(
    val schemaVersion: Int,
    val runtime: RuntimeIdentity,
    val build: EmbeddedBuildStamp,
    val hoverEngine: HoverEngineObservation,
)

data class RuntimeIdentity(
    val processState: String,
    val processId: Long?,
    val javaState: String,
    val javaFeature: Int?,
    val javaVersion: String?,
    val heapState: String,
    val maxHeapBytes: Long?,
)

data class EmbeddedBuildStamp(
    val versionState: String,
    val version: String?,
    val commitState: String,
    val commit: String?,
    val dirtyState: String,
    val dirty: Boolean?,
)

/** Configured engine selection and the actual instance, scoped only to the hover engine path. */
data class HoverEngineObservation(val configured: String, val state: String, val instantiated: String?)

internal data class RuntimeObservation(
    val processId: Long?,
    val javaFeature: Int?,
    val javaVersion: String?,
    val maxHeapBytes: Long?,
)

private const val MAX_EXACT_JSON_INTEGER = 9_007_199_254_740_991L
private const val MAX_VERSION_LENGTH = 64
private const val MAX_JAVA_FEATURE = 999
private val safeJavaVersion = Regex("[0-9][A-Za-z0-9.+_-]*")
private val safeBuildVersion =
    Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-SNAPSHOT|-dev\\+(?:unknown|g[0-9a-fA-F]{7,64}(?:\\.dirty)?))?")
private val safeCommit = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")

/** Pure conversion so legacy, malformed and unavailable observations can be tested without touching the JVM. */
internal fun runtimeFacts(
    build: BuildInfo,
    observed: RuntimeObservation,
    hoverEngine: HoverEngineObservation,
): RuntimeFacts {
    val versionState = textState(build.displayVersion, safeBuildVersion, MAX_VERSION_LENGTH)
    val commitState = textState(build.gitSha, safeCommit, MAX_VERSION_LENGTH)
    val processState = positiveIntegerState(observed.processId)
    val heapState = positiveIntegerState(observed.maxHeapBytes)
    val javaState = when {
        observed.javaFeature == null || observed.javaVersion == null -> "unknown"
        observed.javaFeature !in 1..MAX_JAVA_FEATURE -> "malformed"
        textState(observed.javaVersion, safeJavaVersion, MAX_VERSION_LENGTH) != "known" -> "malformed"
        else -> "known"
    }
    return RuntimeFacts(
        schemaVersion = 1,
        runtime = RuntimeIdentity(
            processState,
            observed.processId.takeIf { processState == "known" },
            javaState,
            observed.javaFeature.takeIf { javaState == "known" },
            observed.javaVersion.takeIf { javaState == "known" },
            heapState,
            observed.maxHeapBytes.takeIf { heapState == "known" },
        ),
        build = EmbeddedBuildStamp(
            versionState,
            build.displayVersion.takeIf { versionState == "known" },
            commitState,
            if (commitState == "known") build.gitSha.lowercase() else null,
            if (build.gitDirty == null) "unknown" else "known",
            build.gitDirty,
        ),
        hoverEngine = hoverEngine,
    )
}

private fun textState(value: String, pattern: Regex, maximum: Int): String = when {
    value.isEmpty() || value == BuildInfo.UNKNOWN -> "unknown"
    value.length > maximum || !pattern.matches(value) -> "malformed"
    else -> "known"
}

private fun positiveIntegerState(value: Long?): String = when {
    value == null -> "unknown"
    value <= 0 || value > MAX_EXACT_JSON_INTEGER -> "malformed"
    else -> "known"
}

/** Reads only the running process identity and runtime; it does not access files or force an engine. */
internal fun observeRuntime(): RuntimeObservation {
    val processId = try {
        ProcessHandle.current().pid()
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }
    val javaVersion = Runtime.version()
    return RuntimeObservation(
        processId,
        javaVersion.feature(),
        javaVersion.toString(),
        Runtime.getRuntime().maxMemory(),
    )
}
