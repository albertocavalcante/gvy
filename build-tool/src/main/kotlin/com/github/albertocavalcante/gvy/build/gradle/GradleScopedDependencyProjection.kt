package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.DependencyTarget
import com.github.albertocavalcante.gvy.build.ResolvedArtifactCoordinates
import com.github.albertocavalcante.gvy.build.ResolvedDependencyArtifact
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import java.net.URI
import java.nio.file.Path

/**
 * Validates the entire observation; malformed records must not masquerade as missing targets.
 * Artifact stamps come from [stamp], the caller's IO boundary; the projection itself does no I/O.
 */
internal object GradleScopedDependencyProjection {
    fun project(
        model: GradleScopedDependencyModel,
        provenance: DependencyProvenance,
        stamp: (Path) -> ArtifactStamp? = { null },
    ): TargetDependencyEvidence.Scoped {
        val buildDirectory = absolutePath(model.buildDirectory)
        val stamps = HashMap<Path, ArtifactStamp?>()
        val stampOnce: (Path) -> ArtifactStamp? = { path -> stamps.getOrPut(path) { stamp(path) } }
        val snapshots = requireNotNull(model.targets).map { raw ->
            snapshot(requireNotNull(raw), buildDirectory, provenance, stampOnce)
        }
        require(snapshots.map { it.target.targetId }.distinct().size == snapshots.size) { "Duplicate Gradle target" }
        return TargetDependencyEvidence.Scoped(snapshots)
    }

    private fun snapshot(
        raw: GradleSourceSetModel,
        buildDirectory: Path,
        provenance: DependencyProvenance,
        stamp: (Path) -> ArtifactStamp?,
    ): TargetDependencySnapshot {
        val projectPath = requireNotNull(raw.projectPath).also { require(it.startsWith(':')) }
        val sourceSet = requireNotNull(raw.sourceSet).also { require(it.isNotBlank()) }
        val configuration = raw.configuration
        val enrichment = raw.enrichmentConfiguration
        require(configuration == null || (configuration.isNotBlank() && configuration == enrichment))
        val state = when (raw.state) {
            "COMPLETE" -> TargetDependencyState.COMPLETE
            "UNRESOLVED" -> TargetDependencyState.UNRESOLVED
            else -> throw IllegalArgumentException("Invalid Gradle enumeration state")
        }
        return TargetDependencySnapshot(
            target = DependencyTarget(
                identity(buildDirectory, projectPath, sourceSet),
                absolutePath(raw.projectDirectory),
                sourceSet,
                configuration,
            ),
            role = DependencyRole.COMPILE,
            provenance = provenance,
            state = state,
            artifacts = requireNotNull(raw.artifacts).map { artifact(requireNotNull(it), enrichment, stamp) },
            sourceRoots = requireNotNull(raw.sourceRoots).map(::absolutePath),
        )
    }

    private fun artifact(
        raw: GradleClasspathEntryModel,
        enrichment: String?,
        stamp: (Path) -> ArtifactStamp?,
    ): ResolvedDependencyArtifact {
        val candidates = requireNotNull(raw.coordinateCandidates).map { candidate ->
            val coordinates = requireNotNull(candidate)
            require(!enrichment.isNullOrBlank()) { "Coordinates require their nominal enrichment query" }
            ResolvedArtifactCoordinates(
                requireNotNull(coordinates.group),
                requireNotNull(coordinates.module),
                requireNotNull(coordinates.version),
            )
        }
        val path = absolutePath(raw.path)
        return ResolvedDependencyArtifact(path, candidates.distinct().singleOrNull(), stamp(path))
    }

    private fun absolutePath(value: String?): Path = Path.of(requireNotNull(value)).also {
        require(it.isAbsolute) { "Gradle paths must be absolute" }
    }

    private fun identity(buildDirectory: Path, projectPath: String, sourceSet: String): URI {
        // Length prefixes keep separators inside names from colliding; URI performs escaping only.
        val components = listOf(buildDirectory.toString(), projectPath, sourceSet)
        return URI("gvy-gradle", components.joinToString("") { "${it.length}:$it" }, null)
    }
}
