package com.github.albertocavalcante.gvy.jenkins.stubs

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
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
    private val moveDirectory: (Path, Path) -> Unit = { from, to -> moveDirectoryAtomically(from, to) },
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
     * The published directory for [files]: the existing one for its key, or a new one written and renamed into
     * place. If another writer publishes the same key first, its directory is used.
     *
     * @throws IOException when the set cannot be written and no directory for its key exists
     */
    fun publish(files: Map<String, String>): Path {
        val key = keyOf(files)
        val dir = setsRoot.resolve(key)
        if (!Files.isDirectory(dir)) {
            setsRoot.createDirectories()
            val tmp = setsRoot.resolve(".$key.${UUID.randomUUID()}$TMP_SUFFIX")
            try {
                generator.writeSet(files, tmp)
                moveDirectory(tmp, dir)
            } catch (e: IOException) {
                // Lost the race to another writer of the same key: its complete directory serves just as well.
                if (!Files.isDirectory(dir)) throw e
            } finally {
                deleteRecursively(tmp)
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
        val cutoff = clock().minus(grace)
        val entries = try {
            Files.list(setsRoot).use { stream -> stream.filter { Files.isDirectory(it) }.toList() }
        } catch (e: IOException) {
            logger.debug(e) { "Cannot list Jenkins stub sets in $setsRoot" }
            return
        }
        val (temporary, sets) = entries.partition { it.name.startsWith(".") && it.name.endsWith(TMP_SUFFIX) }
        temporary.filter { lastUsed(it) < cutoff }.forEach(::deleteRecursively)

        val excess = sets.size - maxSets
        if (excess <= 0) return
        sets.sortedBy(::lastUsed)
            .filter { it.name !in liveKeys && lastUsed(it) < cutoff }
            .take(excess)
            .forEach(::deleteRecursively)
    }

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

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        try {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        } catch (e: IOException) {
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
    }
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
