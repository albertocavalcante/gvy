// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.equivalence

import com.github.albertocavalcante.gvy.build.ResolutionStatus
import com.github.albertocavalcante.gvy.build.ResolvedArtifactCoordinates
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString

/**
 * A machine-independent view of a [WorkspaceResolution], written field by field so every compared fact is visible.
 *
 * `Scoped` evidence and `TargetDependencySnapshot` use reference equality, so they cannot be compared directly;
 * this projection copies the facts they carry into plain values. The flat lists keep the order each import
 * reported; [sorted] removes it where a comparison has no order contract.
 */
internal data class CanonicalResolution(
    /** Status kind and code, for example `Warning(DEPENDENCY_RESOLUTION_FAILED)`. */
    val status: String,
    val dependencies: List<String>,
    val sourceDirectories: List<String>,
    val targetEvidence: CanonicalEvidence,
) {
    /** The flat lists are a union over targets on the protocol path and have no order contract on either path. */
    fun sorted(): CanonicalResolution =
        copy(dependencies = dependencies.sorted(), sourceDirectories = sourceDirectories.sorted())

    fun render(): String = buildString {
        appendLine("status: $status")
        appendList("dependencies", dependencies)
        appendList("sourceDirectories", sourceDirectories)
        append("targetEvidence: $targetEvidence")
    }
}

/** Target-scoped evidence; [Legacy] means the import produced none. */
internal sealed interface CanonicalEvidence {
    data object Legacy : CanonicalEvidence

    data class Scoped(
        val useTargetClasspaths: Boolean,
        val enumerationComplete: Boolean,
        val snapshots: List<CanonicalSnapshot>,
    ) : CanonicalEvidence
}

/**
 * One target snapshot. The provenance generation and artifact stamps are left out: a generation counts the
 * refreshes of a single producer, and a stamp records this machine's file sizes and modification times.
 */
internal data class CanonicalSnapshot(
    val targetId: String,
    val projectDirectory: String,
    val sourceSet: String?,
    val configuration: String?,
    val role: String,
    val state: String,
    val producer: String,
    /** Classpath order is significant within a target, so it is kept. */
    val artifacts: List<CanonicalArtifact>,
    val sourceRoots: List<String>,
    val sourceFiles: List<String>?,
)

internal data class CanonicalArtifact(val path: String, val coordinates: String?)

/**
 * Rewrites absolute paths under known roots into placeholders such as `<repo>/junit/junit/...`, so a projection
 * is the same on every machine. Paths are compared by real location: on macOS the temporary directory is reached
 * through a symbolic link, and the two imports may report either spelling.
 */
internal class PathNormalizer(roots: Map<String, Path>) {
    private val roots: List<Pair<String, Path>> = roots
        .map { (placeholder, root) -> placeholder to realLocation(root) }
        .sortedByDescending { (_, root) -> root.nameCount }

    fun normalize(path: Path): String {
        val location = realLocation(path)
        for ((placeholder, root) in roots) {
            if (location.startsWith(root)) {
                val relative = root.relativize(location).invariantSeparatorsPathString
                return if (relative.isEmpty()) placeholder else "$placeholder/$relative"
            }
        }
        // An unmapped path is a finding in itself; keep it readable rather than hiding it.
        return "<unmapped>${location.invariantSeparatorsPathString}"
    }

    fun normalizeUri(uri: String): String {
        val path = try {
            Paths.get(URI.create(uri))
        } catch (e: IllegalArgumentException) {
            return "<not a file URI: $uri (${e.message})>"
        }
        return normalize(path)
    }

    private fun realLocation(path: Path): Path = try {
        path.toRealPath()
    } catch (_: IOException) {
        path.toAbsolutePath().normalize()
    }
}

internal fun WorkspaceResolution.toCanonical(paths: PathNormalizer): CanonicalResolution = CanonicalResolution(
    status = canonicalStatus(status),
    dependencies = dependencies.map(paths::normalize),
    sourceDirectories = sourceDirectories.map(paths::normalize),
    targetEvidence = canonicalEvidence(targetDependencies, paths),
)

/**
 * Field-by-field differences between two projections, one line per differing field; empty when they agree.
 * List order counts, so pass [CanonicalResolution.sorted] values where order has no contract.
 */
internal fun differences(
    left: CanonicalResolution,
    right: CanonicalResolution,
    leftName: String,
    rightName: String,
): List<String> {
    val lines = mutableListOf<String>()
    if (left.status != right.status) {
        lines += "status: $leftName=${left.status}, $rightName=${right.status}"
    }
    lines += listDifferences("dependencies", left.dependencies, right.dependencies, leftName, rightName)
    lines += listDifferences("sourceDirectories", left.sourceDirectories, right.sourceDirectories, leftName, rightName)
    if (left.targetEvidence != right.targetEvidence) {
        lines += "targetEvidence: $leftName=${left.targetEvidence}, $rightName=${right.targetEvidence}"
    }
    return lines
}

/** Kind and code only: messages are prose that names the server, and `Failed.details`/`cause` are being retyped. */
private fun canonicalStatus(status: ResolutionStatus): String = when (status) {
    ResolutionStatus.Success -> "Success"
    is ResolutionStatus.Warning -> "Warning(${status.code})"
    is ResolutionStatus.Failed -> "Failed(${status.code})"
}

private fun canonicalEvidence(evidence: TargetDependencyEvidence, paths: PathNormalizer): CanonicalEvidence =
    when (evidence) {
        TargetDependencyEvidence.Legacy -> CanonicalEvidence.Legacy
        is TargetDependencyEvidence.Scoped -> CanonicalEvidence.Scoped(
            useTargetClasspaths = evidence.useTargetClasspaths,
            enumerationComplete = evidence.enumerationComplete,
            snapshots = evidence.snapshots
                .map { snapshot -> canonicalSnapshot(snapshot, paths) }
                .sortedWith(compareBy({ it.targetId }, { it.role })),
        )
    }

private fun canonicalSnapshot(snapshot: TargetDependencySnapshot, paths: PathNormalizer): CanonicalSnapshot =
    CanonicalSnapshot(
        targetId = snapshot.target.targetId.toString(),
        projectDirectory = paths.normalize(snapshot.target.projectDirectory),
        sourceSet = snapshot.target.sourceSet,
        configuration = snapshot.target.configuration,
        role = snapshot.role.name,
        state = snapshot.state.name,
        producer = snapshot.provenance.producer,
        artifacts = snapshot.artifacts.map { artifact ->
            CanonicalArtifact(paths.normalize(artifact.path), artifact.coordinates?.let(::canonicalCoordinates))
        },
        sourceRoots = snapshot.sourceRoots.map(paths::normalize),
        sourceFiles = snapshot.sourceFiles?.map(paths::normalize),
    )

private fun canonicalCoordinates(coordinates: ResolvedArtifactCoordinates): String = buildString {
    append("${coordinates.group}:${coordinates.module}:${coordinates.version}")
    coordinates.classifier?.let { append(":$it") }
    coordinates.extension?.let { append("@$it") }
}

private fun listDifferences(
    field: String,
    left: List<String>,
    right: List<String>,
    leftName: String,
    rightName: String,
): List<String> {
    if (left == right) return emptyList()

    val onlyLeft = left.filterNot { it in right }
    val onlyRight = right.filterNot { it in left }
    val lines = mutableListOf<String>()
    if (onlyLeft.isNotEmpty()) lines += "$field only in $leftName: $onlyLeft"
    if (onlyRight.isNotEmpty()) lines += "$field only in $rightName: $onlyRight"
    if (lines.isEmpty()) lines += "$field differ in order or duplicates: $leftName=$left, $rightName=$right"
    return lines
}

private fun StringBuilder.appendList(name: String, values: List<String>) {
    appendLine("$name (${values.size}):")
    values.forEach { appendLine("  $it") }
}
