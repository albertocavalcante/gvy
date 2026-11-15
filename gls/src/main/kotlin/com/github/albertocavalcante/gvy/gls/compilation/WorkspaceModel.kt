// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private const val MAX_CACHED_SOURCE_SCOPES = 4096

/** Compilation membership and source-lookup visibility are distinct observations. */
enum class SourceMode { SELECTED, INDEPENDENT, LEGACY_UNKNOWN }
enum class SourceCandidate { VISIBLE, EXCLUDED, UNKNOWN }

internal data class SourceBoundary(
    val own: Set<Path>,
    val allMembers: Set<Path>,
    val languageRoots: List<Path>,
    val exclusionsProven: Boolean,
)

class SourceScope internal constructor(
    val mode: SourceMode,
    val visibilityKnown: Boolean,
    private val self: Path?,
    private val boundary: SourceBoundary,
    private val canonicalise: (Path) -> Path,
) {
    val exclusionsProven: Boolean get() = boundary.exclusionsProven

    fun classify(path: Path): SourceCandidate {
        val candidate = canonicalise(path)
        return when {
            candidate == self || candidate in boundary.own -> SourceCandidate.VISIBLE
            mode == SourceMode.INDEPENDENT -> SourceCandidate.EXCLUDED
            candidate in boundary.allMembers -> SourceCandidate.UNKNOWN
            exclusionsProven && boundary.languageRoots.any(candidate::startsWith) -> SourceCandidate.EXCLUDED
            else -> SourceCandidate.UNKNOWN
        }
    }
}

/**
 * One immutable workspace configuration. A compile reads it once ([WorkspaceManager.current]) and uses that
 * snapshot for its classpath, source roots and sources, so a concurrent update can never hand it a mixture
 * of two configurations or a list that changes afterwards.
 *
 * @property root The workspace root, if one was initialized
 * @property classpath The dependency classpath, in resolution order
 * @property sourceRoots Distinct source root directories, in discovery order
 * @property sources The Groovy sources found under [sourceRoots], sorted
 * @property targets Build-target dependency evidence resolved with this classpath (#1235)
 */
// Five data fields plus two internal IO seams (canonicalise, computeFingerprint) that tests inject; grouping
// them into a holder would only move the same parameters behind another type.
@Suppress("LongParameterList")
class WorkspaceModel internal constructor(
    val root: Path?,
    classpath: List<Path>,
    sourceRoots: List<Path>,
    sources: List<Path>,
    val targets: TargetDependencyEvidence = TargetDependencyEvidence.Legacy,
    /** How documents and roots are canonicalised (filesystem I/O); injectable for tests. */
    private val canonicalise: (Path) -> Path = DocumentTargetSelector::canonical,
    private val computeFingerprint: (dependencies: List<Path>, sourceRoots: List<Path>) -> String,
) {
    val classpath: List<Path> = Collections.unmodifiableList(classpath.toList())
    val sourceRoots: List<Path> = Collections.unmodifiableList(sourceRoots.distinct())
    val sources: List<Path> = Collections.unmodifiableList(sources.toList())

    /** The configuration fingerprint of this model's classpath and source roots (Issue #743), computed once. */
    val fingerprint: String by lazy { computeFingerprint(this.classpath, this.sourceRoots) }

    // Canonical roots are computed once per model, not per request.
    private val selector by lazy { DocumentTargetSelector(targets, canonicalise) }
    private val targetFingerprints = ConcurrentHashMap<TargetDependencySnapshot, String>()
    private val targetSources = ConcurrentHashMap<TargetDependencySnapshot, Set<Path>>()
    private val membershipFingerprints = ConcurrentHashMap<Pair<String, String>, String>()
    private val scopes = ConcurrentHashMap<Path, SourceScope>()
    private val scopedEvidence = targets as? TargetDependencyEvidence.Scoped
    private val allMembers by lazy {
        Collections.unmodifiableSet(
            scopedEvidence?.snapshots.orEmpty().flatMap { snapshot ->
                canonicalMembers(snapshot).orEmpty()
            }.toSet(),
        )
    }
    private val languageRoots by lazy {
        scopedEvidence?.snapshots.orEmpty().flatMap { snapshot ->
            snapshot.sourceRoots.map { root ->
                canonicalise(if (root.isAbsolute) root else snapshot.target.projectDirectory.resolve(root))
            }
        }.distinct()
    }

    private fun canonicalMembers(target: TargetDependencySnapshot): Set<Path>? {
        val files = target.sourceFiles ?: return null
        return targetSources.getOrPut(target) {
            Collections.unmodifiableSet(
                files.mapTo(mutableSetOf()) { file ->
                    canonicalise(if (file.isAbsolute) file else target.target.projectDirectory.resolve(file))
                },
            )
        }
    }

    /** A non-Gradle strategy retains unrestricted legacy source recovery. */
    fun legacySourceScopeFor(path: Path?): SourceScope = SourceScope(
        SourceMode.LEGACY_UNKNOWN,
        false,
        path?.let(canonicalise),
        SourceBoundary(emptySet(), emptySet(), emptyList(), false),
        canonicalise,
    )

    fun sourceScopeFor(path: Path?): SourceScope {
        if (path == null) return createScope(null)
        val key = canonicalise(path)
        scopes[key]?.let { return it }
        val scope = createScope(key)
        if (scopes.size < MAX_CACHED_SOURCE_SCOPES) scopes.putIfAbsent(key, scope)
        return scope
    }

    private fun createScope(path: Path?): SourceScope {
        val selection = selector.select(path)
        val selected = when (selection) {
            is TargetSelection.Selected -> selection.snapshot
            is TargetSelection.NotComplete -> selection.snapshot
            else -> null
        }
        val own = selected?.let(::canonicalMembers)
        val exclusionsProven = scopedEvidence?.enumerationComplete == true &&
            scopedEvidence.snapshots.all { it.sourceFiles != null }
        val mode = when {
            own != null -> SourceMode.SELECTED
            selection == TargetSelection.Unowned && exclusionsProven -> SourceMode.INDEPENDENT
            else -> SourceMode.LEGACY_UNKNOWN
        }
        return SourceScope(
            mode,
            mode == SourceMode.INDEPENDENT,
            path,
            SourceBoundary(own.orEmpty(), allMembers, languageRoots, exclusionsProven),
            canonicalise,
        )
    }

    /**
     * The compile context for [path] from this model alone. A selected target compiles against its
     * own ordered classpath, keyed by a fingerprint of exactly those inputs and their observed stamps;
     * every other selection uses the workspace classpath and [fingerprint], unchanged from before target
     * evidence existed.
     */

    /** Null preserves legacy recovery; selected sets are eager inputs, not a dependency-visibility proof. */
    fun sourceMembershipFor(path: Path?): Set<Path>? {
        val scope = sourceScopeFor(path)
        return when (scope.mode) {
            SourceMode.LEGACY_UNKNOWN -> null
            SourceMode.INDEPENDENT -> emptySet()
            SourceMode.SELECTED -> {
                val selection = selector.select(path)
                val target = when (selection) {
                    is TargetSelection.Selected -> selection.snapshot
                    is TargetSelection.NotComplete -> selection.snapshot
                    else -> return null
                }
                canonicalMembers(target)
            }
        }
    }

    fun sourceFilterFor(path: Path?): ((Path) -> Boolean)? {
        val scope = sourceScopeFor(path)
        return if (scope.mode == SourceMode.LEGACY_UNKNOWN && !scope.exclusionsProven) {
            null
        } else {
            { candidate ->
                scope.classify(candidate) != SourceCandidate.EXCLUDED
            }
        }
    }

    fun sourcesFor(path: Path?, candidates: List<Path>): List<Path> {
        val scope = sourceScopeFor(path)
        val membership = sourceMembershipFor(path)
        return if (membership == null) {
            candidates.filter { scope.classify(it) != SourceCandidate.EXCLUDED }
        } else {
            candidates.filter { canonicalise(it) in membership }
        }
    }

    fun rootsFor(path: Path?): List<Path> = when (sourceScopeFor(path).mode) {
        SourceMode.INDEPENDENT -> emptyList()
        SourceMode.SELECTED, SourceMode.LEGACY_UNKNOWN -> sourceRoots
    }

    fun membershipFingerprint(base: String, path: Path?): String {
        val boundary = when (val selection = selector.select(path)) {
            is TargetSelection.Selected -> "target:${selection.snapshot.target.targetId}"
            is TargetSelection.NotComplete -> "incomplete:${selection.snapshot.target.targetId}"
            is TargetSelection.Ambiguous -> "ambiguous"
            TargetSelection.Unowned -> "independent"
            else -> "unknown"
        }
        val scope = sourceScopeFor(path)
        val membership = sourceMembershipFor(path) ?: if (scope.exclusionsProven) emptySet() else return base
        return membershipFingerprints.getOrPut(base to boundary) {
            val memberKey = ConfigurationFingerprint.withMembership(base, membership, boundary)
            val visibilityState = scopedEvidence?.snapshots.orEmpty().joinToString("|") {
                "${it.target.targetId}:${it.sourceFiles != null}"
            }
            ConfigurationFingerprint.withMembership(
                memberKey,
                allMembers + languageRoots,
                "visibility:${scopedEvidence?.enumerationComplete}:$visibilityState",
            )
        }
    }

    fun compileContextFor(path: Path?): DocumentCompileContext {
        val selection = selector.select(path)
        val membership = sourceMembershipFor(path)
        val known = when {
            targets is TargetDependencyEvidence.Legacy -> null
            // Self-only analysis also covers files created after the build observation.
            sourceScopeFor(path).mode == SourceMode.INDEPENDENT -> false
            else -> membership != null
        }
        val visibility = if (targets is TargetDependencyEvidence.Legacy) null else sourceScopeFor(path).visibilityKnown
        if (selection !is TargetSelection.Selected ||
            (targets is TargetDependencyEvidence.Scoped && !targets.useTargetClasspaths)
        ) {
            return DocumentCompileContext(
                classpath,
                membershipFingerprint(fingerprint, path),
                selection,
                known,
                visibility,
            )
        }
        val artifacts = selection.snapshot.artifacts
        val targetClasspath = artifacts.map { it.path }
        val key = targetFingerprints.getOrPut(selection.snapshot) {
            val base = computeFingerprint(targetClasspath, sourceRoots)
            ConfigurationFingerprint.withStamps(base, artifacts.map { it.stamp })
        }
        return DocumentCompileContext(targetClasspath, membershipFingerprint(key, path), selection, known, visibility)
    }
}
