// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.withSourceCacheLock
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private const val MAX_MANAGED_CACHE_PATHS = 16_384
private const val MAX_POTENTIAL_WRITE_PATHS = 16

private data class ManagedCacheUsage(var bytes: Long, var paths: Int)

/**
 * Bound persistent cache growth across restarts without deleting viewing copies or files another editor owns.
 * New writes reserve their maximum bytes and serialize publication across store instances. A reuse callback runs
 * under the same lock and must verify an existing object without writing; it reserves no new bytes or paths.
 * Reaching capacity fails explicitly; the store never evicts a dirty view or silently exceeds its write budget.
 */
internal fun <T> withSourceDiskAdmission(
    root: Path,
    maximumBytes: Long,
    reservationBytes: Long,
    operation: () -> T,
): T = withSourceDiskAdmissionOrReuse(root, maximumBytes, reservationBytes, { null }, operation)

/** Reuse an already validated content-addressed object without reserving bytes for another copy. */
internal fun <T> withSourceDiskAdmissionOrReuse(
    root: Path,
    maximumBytes: Long,
    reservationBytes: Long,
    reuse: () -> T?,
    operation: () -> T,
): T = withSourceCacheLock(root) { canonical ->
    requireAdmissionCapacity(maximumBytes, reservationBytes)
    reuse()?.let { return@withSourceCacheLock it }
    measureManagedCache(canonical, maximumBytes, reservationBytes)
    checkSourceInterruption()
    operation()
}

private fun requireAdmissionCapacity(maximumBytes: Long, reservationBytes: Long) {
    require(maximumBytes > 0 && reservationBytes > 0)
    if (reservationBytes > maximumBytes) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
}

private fun measureManagedCache(root: Path, maximumBytes: Long, reservationBytes: Long) {
    val usage = ManagedCacheUsage(reservationBytes, MAX_POTENTIAL_WRITE_PATHS)
    try {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(root).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    checkSourceInterruption()
                    addManagedPath(iterator.next(), maximumBytes, usage)
                }
            }
        }
    } catch (failure: UncheckedIOException) {
        throw failure.cause ?: failure
    }
    if (usage.bytes > maximumBytes) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
}

private fun addManagedPath(path: Path, maximumBytes: Long, usage: ManagedCacheUsage) {
    if (++usage.paths > MAX_MANAGED_CACHE_PATHS) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
    if (Files.isSymbolicLink(path)) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
    when {
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> addManagedFileSize(path, maximumBytes, usage)
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Unit
        else -> throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
    }
}

private fun addManagedFileSize(path: Path, maximumBytes: Long, usage: ManagedCacheUsage) {
    val size = Files.size(path)
    if (size > maximumBytes - usage.bytes) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
    usage.bytes += size
}
