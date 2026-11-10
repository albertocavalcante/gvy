package com.github.albertocavalcante.gvy.jenkins.stubs

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.name

/**
 * Generated plugin stub sets, one immutable directory per distinct set of stub sources (#1367).
 *
 * Each set lives in `<setsRoot>/<key>/`, where the key hashes the rendered sources, so files whose libraries
 * contribute the same steps share a directory and a compile only ever sees its own classpath's steps. A set
 * is written into a temporary sibling and published with one directory rename, so a reader sees either no
 * directory or a complete one, never a partial or changing set. A directory is never rewritten.
 *
 * At most [maxSets] directories are kept at steady state. [evict] deletes the least recently used beyond
 * that, except keys still in use and anything used within [grace] (a compile may still be reading it), and
 * temporary directories that crashed writers left behind, once older than [grace].
 */
internal class JenkinsStubSets(
    private val setsRoot: Path,
    private val generator: JenkinsStubGenerator,
    private val clock: () -> Instant = Instant::now,
    private val fileOps: StubSetFileOps = StubSetFileOps(),
    private val maxSets: Int = MAX_SETS,
    private val grace: Duration = GRACE,
) {
    private val logger = KotlinLogging.logger {}

    /** Directory key for rendered [files]; stable across processes and runs. */
    fun keyOf(files: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(GENERATOR_VERSION.toByteArray(Charsets.UTF_8))
        files.toSortedMap().forEach { (name, content) ->
            digest.update(0)
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(content.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(KEY_HEX_LENGTH)
    }

    /**
     * The published directory for [files]: the existing complete one for its key, or a new one written and renamed
     * into place. If another writer publishes the same key first, its directory is used. A directory under the
     * key's name without the completion marker (an interrupted delete) counts as absent: it is moved aside and
     * replaced.
     *
     * @throws IOException when the set cannot be written and no complete directory for its key exists
     */
    fun publish(files: Map<String, String>): Path {
        val key = keyOf(files)
        val dir = setsRoot.resolve(key)
        if (!isComplete(dir)) {
            setsRoot.createDirectories()
            // Another writer may have published meanwhile; use its set.
            if (clearIncomplete(dir)) return dir.also(::touch)
            val tmp = temporaryFor(key)
            try {
                generator.writeSet(files, tmp)
                // Written last: a set under its final name is complete exactly when it has the marker.
                Files.writeString(tmp.resolve(COMPLETE_MARKER), key)
                fileOps.moveDirectory(tmp, dir)
            } catch (e: IOException) {
                // Lost the race to another writer of the same key: its complete directory serves just as well.
                if (!isComplete(dir)) throw e
            } finally {
                deleteQuietly(tmp)
            }
        }
        touch(dir)
        return dir
    }

    /**
     * The first of [candidates] that publishes, or null when none does. Each candidate renders the stub
     * sources to try; a failure is logged and the next one is tried.
     */
    fun publishFirst(vararg candidates: () -> Map<String, String>): Path? {
        for (candidate in candidates) {
            try {
                return publish(candidate())
            } catch (e: IOException) {
                logger.warn(e) { "Failed to write Jenkins stub set under $setsRoot" }
            }
        }
        return null
    }

    /**
     * Deletes crashed writers' temporary directories older than [grace], then the least recently used sets
     * beyond [maxSets] that are neither in [liveKeys] nor used within [grace]. Best-effort: a directory that
     * cannot be deleted (a file held open on Windows) is left for a later call.
     */
    fun evict(liveKeys: Set<String>) {
        if (!Files.isDirectory(setsRoot)) return
        try {
            evictUnchecked(liveKeys)
        } catch (e: IOException) {
            logger.debug(e) { "Cannot evict Jenkins stub sets in $setsRoot" }
        } catch (e: UncheckedIOException) {
            // Another process evicting the same directory makes Files.list fail mid-stream.
            logger.debug(e) { "Cannot evict Jenkins stub sets in $setsRoot" }
        }
    }

    private fun evictUnchecked(liveKeys: Set<String>) {
        val cutoff = clock().minus(grace)
        val entries = Files.list(setsRoot).use { stream -> stream.filter { Files.isDirectory(it) }.toList() }
        val (temporary, sets) = entries.partition(::isTemporary)
        temporary.filter { lastUsed(it) < cutoff }.forEach(::deleteQuietly)

        val excess = sets.size - maxSets
        if (excess <= 0) return
        sets.sortedBy(::lastUsed)
            .filter { it.name !in liveKeys && lastUsed(it) < cutoff }
            .take(excess)
            // Renamed aside first, so a failed or interrupted delete never leaves a partial set under its name.
            .forEach { set -> retire(set)?.let(::deleteQuietly) }
    }

    /**
     * Makes way for a fresh set under [dir]'s name: moves an incomplete directory there aside and deletes it.
     * Returns true when a complete set is (or is again) under the name, so the caller uses it instead.
     *
     * Writers race here. A set another writer published after our first check is complete, so it is never
     * moved aside on purpose; if one is moved aside anyway (it was published between the check and the move),
     * it is put back, or the set now under the name is used.
     *
     * @throws IOException when an incomplete directory stays under the name
     */
    private fun clearIncomplete(dir: Path): Boolean {
        if (isComplete(dir)) return true
        if (!Files.exists(dir)) return false
        val aside = retire(dir)
        if (aside != null && isComplete(aside)) {
            try {
                fileOps.moveDirectory(aside, dir)
            } catch (e: IOException) {
                logger.debug(e) { "Another stub set took $dir's place" }
            }
        }
        aside?.let(::deleteQuietly)
        return when {
            isComplete(dir) -> true
            Files.exists(dir) -> throw IOException("Cannot move incomplete stub set $dir aside")
            else -> false // retired, or removed by another writer: write a fresh set
        }
    }

    /**
     * Moves [dir] to a temporary name (which [evict] later removes if this delete fails), or null when it
     * cannot be moved; a set still in use on Windows then stays under its name, complete.
     */
    private fun retire(dir: Path): Path? {
        val aside = temporaryFor(dir.name)
        return try {
            fileOps.moveDirectory(dir, aside)
            aside
        } catch (e: IOException) {
            logger.debug(e) { "Cannot move $dir aside; a later eviction retries" }
            null
        }
    }

    private fun temporaryFor(key: String): Path = setsRoot.resolve(".$key.${UUID.randomUUID()}$TMP_SUFFIX")

    private fun isTemporary(dir: Path) = dir.name.startsWith(".") && dir.name.endsWith(TMP_SUFFIX)

    private fun isComplete(dir: Path) = Files.isRegularFile(dir.resolve(COMPLETE_MARKER))

    private fun lastUsed(dir: Path): Instant = try {
        Files.getLastModifiedTime(dir).toInstant()
    } catch (_: IOException) {
        Instant.EPOCH
    }

    /** Marks [dir] as used now; eviction keeps recently used sets. */
    private fun touch(dir: Path) {
        try {
            Files.setLastModifiedTime(dir, FileTime.from(clock()))
        } catch (e: IOException) {
            logger.debug(e) { "Cannot mark Jenkins stub set $dir as used" }
        }
    }

    private fun deleteQuietly(dir: Path) {
        if (!Files.exists(dir)) return
        try {
            fileOps.deleteTree(dir)
        } catch (e: IOException) {
            logger.debug(e) { "Cannot delete $dir yet; a later eviction retries" }
        } catch (e: UncheckedIOException) {
            logger.debug(e) { "Cannot delete $dir yet; a later eviction retries" }
        }
    }

    companion object {
        /** Stub set directories kept at steady state, per workspace. */
        const val MAX_SETS = 8

        /** How long a set (or a temporary directory) is kept after its last use, whatever the bound. */
        val GRACE: Duration = Duration.ofMinutes(10)

        /** Part of every key: bump it when the generator's output format changes. */
        private const val GENERATOR_VERSION = "jenkins-stubs/1"
        private const val KEY_HEX_LENGTH = 16
        internal const val TMP_SUFFIX = ".tmp"

        /** Written last into a set before it is renamed into place; a set without it is incomplete. */
        internal const val COMPLETE_MARKER = ".complete"
    }
}

/** The directory operations [JenkinsStubSets] uses (test seam): one-step rename and recursive delete. */
internal class StubSetFileOps(
    val moveDirectory: (Path, Path) -> Unit = { from, to -> moveDirectoryAtomically(from, to) },
    val deleteTree: (Path) -> Unit = ::deleteTreeBottomUp,
)

/** Deletes [dir] and everything under it, children first. */
internal fun deleteTreeBottomUp(dir: Path) {
    Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

/**
 * Renames [source] to [target] in one step. Where the file system cannot do that atomically (some Windows or
 * network file systems), a plain move; a directory still only appears under its final name once complete.
 * Neither replaces an existing [target].
 */
internal fun moveDirectoryAtomically(
    source: Path,
    target: Path,
    atomicMove: (Path, Path) -> Unit = { from, to -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) },
) {
    try {
        atomicMove(source, target)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}
