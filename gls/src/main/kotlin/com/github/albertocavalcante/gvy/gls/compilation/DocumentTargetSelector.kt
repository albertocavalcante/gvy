// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Which build target, if any, a document compiles against. */
sealed interface TargetSelection {
    /** Exactly one completely enumerated target reports a source root containing the document. */
    data class Selected(val snapshot: TargetDependencySnapshot) : TargetSelection

    /** More than one target's reported roots contain the document; no first match or highest version is chosen. */
    data class Ambiguous(val candidates: List<TargetDependencySnapshot>) : TargetSelection

    /** The only owning target was not completely enumerated (UNRESOLVED, INCOMPLETE, STALE, AMBIGUOUS). */
    data class NotComplete(val snapshot: TargetDependencySnapshot) : TargetSelection

    /** Target evidence exists but no target's roots contain the document (or it has no file path). */
    data object Unowned : TargetSelection

    /** No target evidence was produced; only the legacy workspace classpath exists. */
    data object LegacyOnly : TargetSelection

    /** A project strategy (for example Jenkins) supplies the classpath. */
    data object StrategyOverride : TargetSelection
}

/**
 * Attributes a document to a build target from reported source roots. Roots are not exclusive
 * membership proof (#1213): a document is attributed only when no other target's roots also contain
 * it; everything else stays explicit so callers fall back to the legacy workspace classpath.
 *
 * Documents and roots are compared in one canonical form: the real path when it exists (resolving
 * symbolic links and, on case-insensitive file systems, spelling), else the absolute normalised path.
 * Relative roots resolve against the target's project directory.
 *
 * A document's canonical form is cached for this selector's lifetime (one workspace model): a path that
 * changes on disk afterwards (a new symbolic link, a renamed directory) stays stale until the next model.
 */
internal class DocumentTargetSelector(
    evidence: TargetDependencyEvidence,
    private val canonicalise: (Path) -> Path = ::canonical,
) {
    private val scoped = evidence as? TargetDependencyEvidence.Scoped
    private val owners: List<Pair<TargetDependencySnapshot, List<Path>>> = scoped?.snapshots.orEmpty()
        .filter { it.role == DependencyRole.COMPILE }
        .map { snapshot ->
            val project = snapshot.target.projectDirectory
            snapshot to snapshot.sourceRoots.map { root ->
                canonicalise(if (root.isAbsolute) root else project.resolve(root))
            }
        }

    fun select(path: Path?): TargetSelection {
        if (scoped == null) return TargetSelection.LegacyOnly
        val document = path?.let(::canonicalDocument) ?: return TargetSelection.Unowned
        val matches = owners.filter { (_, roots) -> roots.any(document::startsWith) }.map { it.first }
        return when {
            matches.isEmpty() -> TargetSelection.Unowned
            matches.size > 1 -> TargetSelection.Ambiguous(matches)
            matches.single().state != TargetDependencyState.COMPLETE -> TargetSelection.NotComplete(matches.single())
            else -> TargetSelection.Selected(matches.single())
        }
    }

    // Canonicalisation is filesystem I/O; each document is canonicalised once per selector, i.e. once per
    // workspace model (a new model has a new selector). Bounded: past the cap, lookups canonicalise directly.
    private val canonicalDocuments = ConcurrentHashMap<Path, Path>()

    private fun canonicalDocument(path: Path): Path = canonicalDocuments[path] ?: canonicalise(path).also { canonical ->
        if (canonicalDocuments.size < MAX_CACHED_DOCUMENTS) canonicalDocuments.putIfAbsent(path, canonical)
    }

    companion object {
        private const val MAX_CACHED_DOCUMENTS = 4096

        fun select(path: Path?, evidence: TargetDependencyEvidence): TargetSelection =
            DocumentTargetSelector(evidence).select(path)

        /**
         * The real path of the deepest existing ancestor with the rest appended, so a new or unsaved file
         * under a symlinked directory compares equal to its real root; never throws for a bad path.
         *
         * Limitation: the path is normalised lexically before the real-path lookup, so `a/link/../b`
         * drops `link/..` without following `link`. When `link` points elsewhere this can differ from
         * the file system's own resolution; such a spelling may then be attributed by its lexical form.
         */
        fun canonical(path: Path): Path {
            val absolute = path.toAbsolutePath().normalize()
            var existing: Path? = absolute
            while (existing != null && !exists(existing)) existing = existing.parent
            val base = existing ?: return absolute
            return try {
                base.toRealPath().resolve(base.relativize(absolute))
            } catch (_: IOException) {
                absolute
            } catch (_: InvalidPathException) {
                absolute
            } catch (_: SecurityException) {
                absolute
            }
        }

        private fun exists(path: Path): Boolean = try {
            Files.exists(path)
        } catch (_: SecurityException) {
            false
        }
    }
}

/**
 * The classpath, cache fingerprint and target selection for one document, all derived from a single
 * [WorkspaceModel] snapshot, so a compile can never pair one configuration's classpath with another's
 * fingerprint or target facts.
 */
data class DocumentCompileContext(val classpath: List<Path>, val fingerprint: String, val selection: TargetSelection)
