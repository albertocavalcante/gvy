// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

private const val MAX_CACHED_ARCHIVES = 8
private const val MAX_CACHED_ENTRIES = 128
private const val MAX_PENDING_SOURCES = 32
private const val MAX_PARALLEL_SOURCES = 2

data class MaterializedSource(
    val archive: VerifiedSourceArchive,
    val entryName: String,
    val contentPath: Path,
    val contentDigest: String,
)

/** Bounded archive indexes and requested-entry materialization; no parser AST or classloader is retained. */
class SourceContentStore(root: Path, private val limits: SourceStoreLimits = SourceStoreLimits()) : AutoCloseable {
    private val root = sourceStoreRoot(root)
    private val archives = SourceArchiveStore(this.root.resolve("archives"), limits)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(MAX_PARALLEL_SOURCES)
    private val monitor = Any()
    private val indexes = linkedMapOf<String, SourceArchiveIndex>()
    private val importedArchives = linkedMapOf<String, VerifiedSourceArchive>()
    private val entries = linkedMapOf<EntryKey, MaterializedSource>()
    private val pendingIndexes = mutableMapOf<String, Pending<SourceArchiveIndex>>()
    private val pendingImports = mutableMapOf<String, Pending<VerifiedSourceArchive>>()
    private val pending = mutableMapOf<EntryKey, Pending<MaterializedSource>>()
    private val indexCount = AtomicLong()
    private val entryCount = AtomicLong()
    private val importCount = AtomicLong()
    private var closed = false

    suspend fun importArchive(input: SourceArchiveInput): VerifiedSourceArchive {
        synchronized(monitor) {
            if (closed) throw CancellationException("Source store is closed")
        }
        // A local attachment has no publisher digest. Observe its actual bytes for each later lookup,
        // then share only the immutable content identity. A change during copying fails verification.
        val pinned = if (input is SourceArchiveInput.Local) {
            permits.withPermit {
                runInterruptible(Dispatchers.IO) {
                    val path = input.path.toRealPath()
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
                    }
                    if (Files.size(path) > limits.maxArchiveBytes) {
                        throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
                    }
                    val digest = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { source ->
                        copyAndDigest(source, limits.maxArchiveBytes)
                    }
                    SourceArchiveInput.Verified(path, digest)
                }
            }
        } else {
            input
        }
        val expected = when (pinned) {
            is SourceArchiveInput.Local -> error("Local source input must have a content pin")
            is SourceArchiveInput.Verified -> pinned.expectedSha256.lowercase()
            is SourceArchiveInput.VerifiedResource -> pinned.expectedSha256.lowercase()
        }
        return share(pendingImports, expected) {
            synchronized(monitor) { importedArchives[expected] }?.let { cached ->
                if (runInterruptible(Dispatchers.IO) { Files.isRegularFile(cached.path, LinkOption.NOFOLLOW_LINKS) }) {
                    return@share cached
                }
            }
            importUnshared(pinned)
        }
    }

    private suspend fun importUnshared(input: SourceArchiveInput): VerifiedSourceArchive =
        runInterruptible(Dispatchers.IO) { archives.importArchive(input) }.also { archive ->
            importCount.incrementAndGet()
            synchronized(monitor) {
                if (!closed) putBounded(importedArchives, archive.sha256, archive, MAX_CACHED_ARCHIVES)
            }
        }

    suspend fun sourceFor(
        archive: VerifiedSourceArchive,
        binaryName: String,
        prefix: String = "",
    ): MaterializedSource? {
        val index = indexFor(archive)
        val entryName = index.entryFor(binaryName, prefix) ?: return null
        val key = EntryKey(archive.sha256, entryName)
        return share(pending, key) {
            permits.withPermit {
                runInterruptible {
                    val cached = synchronized(monitor) { entries[key] }
                    if (cached != null) {
                        verifyFileDigest(cached.contentPath, cached.contentDigest, limits.maxEntryBytes)
                        cached
                    } else {
                        val source = materializeEntry(root.resolve("content"), archive, entryName, limits)
                        entryCount.incrementAndGet()
                        synchronized(monitor) {
                            if (!closed) putBounded(entries, key, source, MAX_CACHED_ENTRIES)
                        }
                        source
                    }
                }
            }
        }
    }

    private suspend fun indexFor(archive: VerifiedSourceArchive): SourceArchiveIndex {
        synchronized(monitor) { indexes[archive.sha256] }?.let { return it }
        return share(pendingIndexes, archive.sha256) {
            synchronized(monitor) { indexes[archive.sha256] }?.let { return@share it }
            permits.withPermit {
                runInterruptible {
                    indexSourceArchive(archive, limits).also { index ->
                        indexCount.incrementAndGet()
                        synchronized(monitor) {
                            if (!closed) putBounded(indexes, archive.sha256, index, MAX_CACHED_ARCHIVES)
                        }
                    }
                }
            }
        }
    }

    private suspend fun <K, T> share(map: MutableMap<K, Pending<T>>, key: K, acquire: suspend () -> T): T {
        val acquisition = synchronized(monitor) {
            if (closed) throw CancellationException("Source store is closed")
            map[key]?.takeUnless { it.result.isCancelled }?.also { it.readers++ } ?: run {
                if (map.size >= MAX_PENDING_SOURCES) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
                val task = scope.async(start = CoroutineStart.LAZY) { acquire() }
                Pending(task, readers = 1).also { map[key] = it }
            }
        }
        acquisition.result.start()
        return try {
            acquisition.result.await()
        } finally {
            synchronized(monitor) {
                acquisition.readers--
                if (acquisition.readers == 0) {
                    if (map[key] === acquisition) map.remove(key)
                    if (!acquisition.result.isCompleted) acquisition.result.cancel()
                }
            }
        }
    }

    fun workCounts(): SourceStoreWorkCounts = SourceStoreWorkCounts(
        archiveIndexes = indexCount.get(),
        sourceMaterializations = entryCount.get(),
        archiveImports = importCount.get(),
    )

    override fun close() {
        synchronized(monitor) {
            closed = true
            indexes.clear()
            importedArchives.clear()
            entries.clear()
            pendingIndexes.clear()
            pendingImports.clear()
            pending.clear()
        }
        scope.cancel()
    }

    private data class EntryKey(val archive: String, val entryName: String)
    private class Pending<T>(val result: Deferred<T>, var readers: Int)
}

data class SourceStoreWorkCounts(
    val archiveIndexes: Long,
    val sourceMaterializations: Long,
    val archiveImports: Long = 0,
)

private fun <K, V> putBounded(values: LinkedHashMap<K, V>, key: K, value: V, limit: Int) {
    values.remove(key)
    values[key] = value
    while (values.size > limit) values.remove(values.keys.first())
}

internal fun materializeEntry(
    root: Path,
    archive: VerifiedSourceArchive,
    entryName: String,
    limits: SourceStoreLimits,
): MaterializedSource {
    val entryId = sourceDigest(entryName)
    val directory = root.resolve(archive.sha256).resolve(entryId)
    val destination = directory.resolve(entryName.substringAfterLast('/'))
    return withSourceDiskAdmissionOrReuse(
        root,
        limits.maxContentStoreBytes,
        limits.maxEntryBytes,
        reuse = {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return@withSourceDiskAdmissionOrReuse null
            ensureManagedDirectory(directory)
            if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return@withSourceDiskAdmissionOrReuse null
            verifyFileDigest(archive.path, archive.sha256, limits.maxArchiveBytes)
            val digest = archiveEntryDigest(archive, entryName, limits)
            verifyFileDigest(archive.path, archive.sha256, limits.maxArchiveBytes)
            verifyFileDigest(destination, digest, limits.maxEntryBytes)
            MaterializedSource(archive, entryName, destination, digest)
        },
    ) {
        verifyFileDigest(archive.path, archive.sha256, limits.maxArchiveBytes)
        ensureManagedDirectory(directory)
        val temporary = Files.createTempFile(directory, ".extracting-", ".source")
        try {
            val digest = Files.newOutputStream(temporary).use { output ->
                copyArchiveEntry(archive, entryName, limits, output::write)
            }
            verifyFileDigest(archive.path, archive.sha256, limits.maxArchiveBytes)
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                verifyFileDigest(destination, digest, limits.maxEntryBytes)
            } else {
                publishNewFile(temporary, destination)
            }
            MaterializedSource(archive, entryName, destination, digest)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun archiveEntryDigest(
    archive: VerifiedSourceArchive,
    entryName: String,
    limits: SourceStoreLimits,
): String = copyArchiveEntry(archive, entryName, limits)

private fun copyArchiveEntry(
    archive: VerifiedSourceArchive,
    entryName: String,
    limits: SourceStoreLimits,
    consume: (ByteArray, Int, Int) -> Unit = { _, _, _ -> },
): String = ZipFile(archive.path.toFile()).use { zip ->
    val entry = zip.getEntry(entryName) ?: throw SourceStoreException(SourceFailure.IO_FAILURE)
    if (entry.isDirectory || entry.size > limits.maxEntryBytes) {
        throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
    }
    zip.getInputStream(entry).use { input -> copyAndDigest(input, limits.maxEntryBytes, consume) }
}

internal fun sourceDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
