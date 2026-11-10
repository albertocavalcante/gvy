package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.build.ResolvedDependencyArtifact
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** What one `stat` of a classpath entry found, without walking a directory. */
internal sealed interface EntryStat {
    /** A file entry (a jar) and its current stamp. */
    data class File(val stamp: ArtifactStamp) : EntryStat

    /** A directory: its stamp needs a tree walk, which a periodic tick never does. */
    data object Directory : EntryStat

    /** Gone or unreadable. */
    data object Missing : EntryStat
}

/**
 * Re-stamps the classpath entries of observed target evidence (#1364), so an entry replaced between dependency
 * observations changes the target's compile key. Pure apart from the injected `stat` and `walk`.
 *
 * - [restampFiles], for the periodic tick, stats each distinct file entry once and never touches a directory entry:
 *   walking a large class directory every few seconds would cost too much.
 * - [restampDirectories], for a watcher event, walks only the directory entries the changed paths lie under.
 *
 * A directory entry is one whose observed stamp counts files ([ArtifactStamp.files]). An entry with no observed
 * stamp is stat'ed on the tick: it gains a stamp if it is now a file, and stays unstamped if it is a directory. A
 * directory that vanished (its walk found nothing) is no longer known to be one, so later events under it are
 * missed until the next dependency observation; so are changes in directories outside the workspace, which get no
 * watcher events at all.
 */
internal object ClasspathRestamp {
    /** One `stat` of [path]; never walks a directory. */
    fun statEntry(path: Path): EntryStat = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        if (attributes.isDirectory) {
            EntryStat.Directory
        } else {
            EntryStat.File(ArtifactStamp(attributes.size(), attributes.lastModifiedTime().toMillis()))
        }
    } catch (_: IOException) {
        EntryStat.Missing
    } catch (_: SecurityException) {
        EntryStat.Missing
    }

    /** The evidence with every file entry re-stamped, or null when no stamp changed. */
    fun restampFiles(
        evidence: TargetDependencyEvidence.Scoped,
        stat: (Path) -> EntryStat,
    ): TargetDependencyEvidence.Scoped? {
        val stats = HashMap<Path, EntryStat>()
        return rewrite(evidence) { artifact ->
            if (artifact.isDirectory()) {
                artifact.stamp
            } else {
                when (val found = stats.getOrPut(artifact.path) { stat(artifact.path) }) {
                    is EntryStat.File -> found.stamp
                    EntryStat.Directory -> artifact.stamp
                    EntryStat.Missing -> null
                }
            }
        }
    }

    /** The directory entries [paths] lie under (or are), in classpath order; no I/O. */
    fun directoriesContaining(evidence: TargetDependencyEvidence.Scoped, paths: Collection<Path>): Set<Path> {
        if (paths.isEmpty()) return emptySet()
        val changed = paths.map { it.toAbsolutePath().normalize() }
        return evidence.snapshots.asSequence()
            .flatMap { it.artifacts }
            .filter { it.isDirectory() }
            .map { it.path.toAbsolutePath().normalize() }
            .filterTo(LinkedHashSet()) { directory -> changed.any { it.startsWith(directory) } }
    }

    /** The evidence with each of [directories] re-walked once, or null when no stamp changed. */
    fun restampDirectories(
        evidence: TargetDependencyEvidence.Scoped,
        directories: Set<Path>,
        walk: (Path) -> ArtifactStamp?,
    ): TargetDependencyEvidence.Scoped? {
        if (directories.isEmpty()) return null
        val walked = HashMap<Path, ArtifactStamp?>()
        return rewrite(evidence) { artifact ->
            val path = artifact.path.toAbsolutePath().normalize()
            if (artifact.isDirectory() && path in directories) walked.getOrPut(path) { walk(path) } else artifact.stamp
        }
    }

    private fun ResolvedDependencyArtifact.isDirectory(): Boolean = stamp?.files != null

    /** Rebuilds [evidence] with [stampOf] applied to every artifact; null when every stamp is unchanged. */
    private inline fun rewrite(
        evidence: TargetDependencyEvidence.Scoped,
        stampOf: (ResolvedDependencyArtifact) -> ArtifactStamp?,
    ): TargetDependencyEvidence.Scoped? {
        var changed = false
        val snapshots = evidence.snapshots.map { snapshot ->
            val artifacts = snapshot.artifacts.map { artifact ->
                val stamp = stampOf(artifact)
                if (stamp == artifact.stamp) {
                    artifact
                } else {
                    changed = true
                    artifact.copy(stamp = stamp)
                }
            }
            if (artifacts == snapshot.artifacts) snapshot else snapshot.withArtifacts(artifacts)
        }
        return if (changed) TargetDependencyEvidence.Scoped(snapshots) else null
    }

    private fun TargetDependencySnapshot.withArtifacts(artifacts: List<ResolvedDependencyArtifact>) =
        TargetDependencySnapshot(target, role, provenance, state, artifacts, sourceRoots)
}
