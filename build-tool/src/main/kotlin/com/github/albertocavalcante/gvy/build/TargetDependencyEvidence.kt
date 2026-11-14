// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections

/**
 * Target evidence supplements the legacy flat workspace result; it never replaces its classpath.
 * The Gradle scoped producer supplies membership by default; target classpaths remain opt-in (#1235).
 * Producers must preserve the identity and
 * order they actually resolved instead of reconstructing them from folders or artifact names.
 */
sealed interface TargetDependencyEvidence {
    /** No target evidence was produced, including for an empty successful legacy resolution. */
    data object Legacy : TargetDependencyEvidence

    /**
     * Producer records. [enumerationComplete] applies only to source sets of the observed build;
     * it does not establish artifact absence or enumerate unsupported/included builds. An empty list
     * or a missing target remains unknown unless a consumer has the specific completeness proof it needs.
     * This container uses reference equality; no cache-key or freshness semantics are implied.
     */
    class Scoped @JvmOverloads constructor(
        snapshots: List<TargetDependencySnapshot>,
        val useTargetClasspaths: Boolean = true,
        /** Every source set in the observed build was enumerated, without asserting other builds. */
        val enumerationComplete: Boolean = false,
    ) : TargetDependencyEvidence {
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

/**
 * A reported classpath entry, including local files or class directories with no coordinates.
 * [stamp] is the entry's size and modification time when the producer observed it; null when the
 * producer did not stat it or the entry could not be read. It is part of equality, so the same path
 * observed with different content is a different fact.
 */
data class ResolvedDependencyArtifact(
    val path: Path,
    val coordinates: ResolvedArtifactCoordinates? = null,
    val stamp: ArtifactStamp? = null,
)

/**
 * A classpath entry's size and modification time at observation. A jar or other file is stamped by its own
 * attributes. A class directory is stamped by what it contains (#1364): the total size and count of its regular files
 * and the newest modification time in its tree, so rewriting or deleting a class inside it changes the stamp, where
 * the directory's own attributes would not. Equal stamps still do not prove equal content.
 *
 * @property size the file's size, or the total size of a directory's regular files
 * @property lastModifiedMillis the file's modification time, or the newest in a directory's tree
 * @property files the number of regular files in a directory; null for a file entry
 */
data class ArtifactStamp(val size: Long, val lastModifiedMillis: Long, val files: Long? = null) {
    companion object {
        /**
         * More files than any class directory should hold. Past it a directory is not walked to the end and has no
         * stamp, which reads as "not observed": its changes are not detected this way.
         */
        const val MAX_DIRECTORY_FILES = 100_000

        /**
         * Reads the stamp of [path] (filesystem I/O, a tree walk for a directory); null when it does not exist,
         * cannot be read, or is a directory holding more than [maxDirectoryFiles] files.
         */
        fun of(path: Path, maxDirectoryFiles: Int = MAX_DIRECTORY_FILES): ArtifactStamp? = try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (attributes.isDirectory) {
                directory(path, maxDirectoryFiles)
            } else {
                ArtifactStamp(attributes.size(), attributes.lastModifiedTime().toMillis())
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        /** Links are not followed, so a cycle through a link cannot loop the walk. */
        private fun directory(root: Path, maxFiles: Int): ArtifactStamp? {
            val walk = DirectoryWalk(maxFiles)
            Files.walkFileTree(root, walk)
            return if (walk.files > maxFiles) null else ArtifactStamp(walk.bytes, walk.newest, walk.files)
        }
    }

    private class DirectoryWalk(private val maxFiles: Int) : SimpleFileVisitor<Path>() {
        var files = 0L
        var bytes = 0L
        var newest = Long.MIN_VALUE

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isRegularFile) {
                files++
                bytes += attrs.size()
                newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
            }
            return if (files > maxFiles) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
        }

        // An unreadable part of the tree makes the whole stamp unknown rather than silently partial.
        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
    }
}

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
class TargetDependencySnapshot
@Suppress("LongParameterList") // Preserve the existing six-argument JVM constructor when adding optional membership.
@JvmOverloads
constructor(
    val target: DependencyTarget,
    val role: DependencyRole,
    val provenance: DependencyProvenance,
    val state: TargetDependencyState,
    artifacts: List<ResolvedDependencyArtifact>,
    sourceRoots: List<Path>,
    sourceFiles: List<Path>? = null,
) {
    /** Authoritative source membership, or null when the producer cannot establish it. */
    val sourceFiles: List<Path>? = sourceFiles?.let(::immutableCopy)

    val artifacts: List<ResolvedDependencyArtifact> = immutableCopy(artifacts)

    /** Producer-reported roots; [sourceFiles], when present, establishes actual membership. */
    val sourceRoots: List<Path> = immutableCopy(sourceRoots)
}

private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
