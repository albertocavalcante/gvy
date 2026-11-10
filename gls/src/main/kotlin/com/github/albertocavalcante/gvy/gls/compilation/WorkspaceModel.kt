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
class WorkspaceModel internal constructor(
    val root: Path?,
    classpath: List<Path>,
    sourceRoots: List<Path>,
    sources: List<Path>,
    val targets: TargetDependencyEvidence = TargetDependencyEvidence.Legacy,
    private val computeFingerprint: (dependencies: List<Path>, sourceRoots: List<Path>) -> String,
) {
    val classpath: List<Path> = Collections.unmodifiableList(classpath.toList())
    val sourceRoots: List<Path> = Collections.unmodifiableList(sourceRoots.distinct())
    val sources: List<Path> = Collections.unmodifiableList(sources.toList())

    /** The configuration fingerprint of this model's classpath and source roots (Issue #743), computed once. */
    val fingerprint: String by lazy { computeFingerprint(this.classpath, this.sourceRoots) }

    // Canonical roots are computed once per model, not per request.
    private val selector by lazy { DocumentTargetSelector(targets) }
    private val targetFingerprints = ConcurrentHashMap<TargetDependencySnapshot, String>()

    /**
     * The compile context for [path] from this model alone. A selected target compiles against its
     * own ordered classpath, keyed by a fingerprint of exactly those inputs; every other selection uses
     * the workspace classpath and [fingerprint], unchanged from before target evidence existed.
     */
    fun compileContextFor(path: Path?): DocumentCompileContext = when (val selection = selector.select(path)) {
        is TargetSelection.Selected -> {
            val targetClasspath = selection.snapshot.artifacts.map { it.path }
            val key = targetFingerprints.getOrPut(selection.snapshot) {
                computeFingerprint(targetClasspath, sourceRoots)
            }
            DocumentCompileContext(targetClasspath, key, selection)
        }
        else -> DocumentCompileContext(classpath, fingerprint, selection)
    }
}
