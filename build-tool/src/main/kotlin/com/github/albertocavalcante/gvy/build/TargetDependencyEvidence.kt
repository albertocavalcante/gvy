package com.github.albertocavalcante.gvy.build

import java.net.URI
import java.nio.file.Path
import java.util.Collections

/**
 * Target evidence supplements the legacy flat workspace result; it never replaces its classpath.
 * No current resolver produces scoped evidence. Future producers must preserve the identity and
 * order they actually resolved instead of reconstructing them from folders or artifact names.
 */
sealed interface TargetDependencyEvidence {
    /** No target evidence was produced, including for an empty successful legacy resolution. */
    data object Legacy : TargetDependencyEvidence

    /**
     * Producer records, not a claim that every workspace target was enumerated. An empty list or
     * a missing target remains unknown; neither establishes the absence of an artifact.
     * This container uses reference equality; no cache-key or freshness semantics are implied.
     */
    class Scoped(snapshots: List<TargetDependencySnapshot>) : TargetDependencyEvidence {
        val snapshots: List<TargetDependencySnapshot> = immutableCopy(snapshots)
    }
}

/**
 * A producer's opaque target ID and the project/source-set/configuration facts it can establish.
 * The absolute URI may be opaque, such as BSP's `maven:group:artifact:test`; no scheme or URI
 * structure is prescribed. Keep the producer's build/target namespace rather than guessing IDs.
 * Source set and configuration are null when the producer does not expose those concepts.
 */
data class DependencyTarget(
    val targetId: URI,
    val projectDirectory: Path,
    val sourceSet: String? = null,
    val configuration: String? = null,
) {
    init {
        require(targetId.isAbsolute) { "Target identity must be an absolute URI" }
        require(projectDirectory.isAbsolute) { "Project directory must be absolute" }
    }
}

/** The role of this exact classpath, independent of source-set/configuration naming conventions. */
enum class DependencyRole {
    /** Compilation of main, test or custom source sets. */
    COMPILE,

    /** One test-runtime configuration; does not imply support for every task or engine. */
    TEST_RUNTIME,
}

/** Enumeration state supplied by a producer; none of these values establishes freshness alone. */
enum class TargetDependencyState {
    /** Exact target/configuration/role enumeration; some artifacts may still lack coordinates. */
    COMPLETE,

    /** Only a subset of the classpath is known, possibly an empty subset. */
    INCOMPLETE,

    /** The producer could not resolve the classpath; retained facts are non-authoritative. */
    UNRESOLVED,

    /** The producer cannot establish a single classpath; never pick a first or highest version. */
    AMBIGUOUS,

    /** Facts are known to be out of date; retain their original producer and generation. */
    STALE,
}

/**
 * Exact resolved coordinates, when supplied by the build model. Do not derive them from filenames
 * or [DependencyMetadata], whose display-oriented fields permit unknown versions and scope loss.
 * Optional classifier/extension preserve reported facts without assuming a JAR or classifier.
 */
data class ResolvedArtifactCoordinates(
    val group: String,
    val module: String,
    val version: String,
    val classifier: String? = null,
    val extension: String? = null,
)

/** A reported classpath entry, including local files or class directories with no coordinates. */
data class ResolvedDependencyArtifact(val path: Path, val coordinates: ResolvedArtifactCoordinates? = null)

/**
 * Origin of one build-refresh observation. Generation is meaningful only within the corresponding
 * producer/build-refresh scope; an equal number from another producer or build is not the same fact.
 */
data class DependencyProvenance(val producer: String, val generation: Long) {
    init {
        require(generation >= 0) { "Resolution generation must be nonnegative" }
        require(producer.isNotBlank()) { "Evidence producer must be identified" }
    }
}

/**
 * Facts for one producer/project/target/configuration/role and build-refresh generation.
 *
 * The producer is its actual resolver/server name, not a guessed build-tool category. Generation
 * is comparable only within the corresponding producer/build-refresh scope, not across producers.
 * This record does not introduce a refresh counter, cache, target selector or freshness probe.
 *
 * COMPLETE means exact enumeration, not proof of source ownership, freshness or the identity of
 * every artifact. Only after a consumer proves the selected target and matching current generation
 * can COMPLETE with no artifacts establish no-artifact evidence for this exact classpath. Missing
 * coordinates in a nonempty classpath cannot prove that a particular framework is absent.
 *
 * Artifact order and duplicates are significant and retained. Collection inputs are copied and
 * wrapped as unmodifiable lists so later producer/consumer mutations cannot change this record.
 * No filesystem access, path normalization, dependency execution or test capability inference occurs.
 * This container uses reference equality; equality is not a classpath or freshness comparison.
 */
class TargetDependencySnapshot(
    val target: DependencyTarget,
    val role: DependencyRole,
    val provenance: DependencyProvenance,
    val state: TargetDependencyState,
    artifacts: List<ResolvedDependencyArtifact>,
    sourceRoots: List<Path>,
) {
    val artifacts: List<ResolvedDependencyArtifact> = immutableCopy(artifacts)

    /**
     * Producer-reported roots, not exclusive document-membership or compilation-coverage proof.
     * A consumer may attribute a document to this target only when this is the sole COMPLETE
     * target whose roots contain it (the gls `DocumentTargetSelector`); a document under several
     * targets' roots stays ambiguous. Include/exclude filters, resource/language roots and unsaved
     * files outside every root are not modelled, so such an attribution is still a root-based
     * inference rather than proof that the build compiles the document in this target.
     */
    val sourceRoots: List<Path> = immutableCopy(sourceRoots)
}

private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
