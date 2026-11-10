package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * The compile context for [path] from this model alone. A selected target compiles against its
     * own ordered classpath, keyed by a fingerprint of exactly those inputs and their observed stamps;
     * every other selection uses the workspace classpath and [fingerprint], unchanged from before target
     * evidence existed.
     */
    fun compileContextFor(path: Path?): DocumentCompileContext = when (val selection = selector.select(path)) {
        is TargetSelection.Selected -> {
            val artifacts = selection.snapshot.artifacts
            val targetClasspath = artifacts.map { it.path }
            val key = targetFingerprints.getOrPut(selection.snapshot) {
                val base = computeFingerprint(targetClasspath, sourceRoots)
                ConfigurationFingerprint.withStamps(base, artifacts.map { it.stamp })
            }
            DocumentCompileContext(targetClasspath, key, selection)
        }
        else -> DocumentCompileContext(classpath, fingerprint, selection)
    }
}
