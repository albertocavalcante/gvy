package com.github.albertocavalcante.gvy.gls.compilation

import java.nio.file.Path
import java.util.Collections

/**
 * One immutable workspace configuration. A compile reads it once ([WorkspaceManager.current]) and uses that
 * snapshot for its classpath, source roots and sources, so a concurrent update can never hand it a mixture
 * of two configurations or a list that changes afterwards.
 *
 * @property root The workspace root, if one was initialized
 * @property classpath The dependency classpath, in resolution order
 * @property sourceRoots Distinct source root directories, in discovery order
 * @property sources The Groovy sources found under [sourceRoots], sorted
 */
class WorkspaceModel internal constructor(
    val root: Path?,
    classpath: List<Path>,
    sourceRoots: List<Path>,
    sources: List<Path>,
    computeFingerprint: (dependencies: List<Path>, sourceRoots: List<Path>) -> String,
) {
    val classpath: List<Path> = Collections.unmodifiableList(classpath.toList())
    val sourceRoots: List<Path> = Collections.unmodifiableList(sourceRoots.distinct())
    val sources: List<Path> = Collections.unmodifiableList(sources.toList())

    /** The configuration fingerprint of this model's classpath and source roots (Issue #743), computed once. */
    val fingerprint: String by lazy { computeFingerprint(this.classpath, this.sourceRoots) }
}
