// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.withSourceCacheLock
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private const val MAX_MANAGED_CACHE_PATHS = 16_384

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
    require(maximumBytes > 0 && reservationBytes > 0)
    if (reservationBytes > maximumBytes) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
    reuse()?.let { return@withSourceCacheLock it }
    var bytes = reservationBytes
    // Every operation creates at most a digest directory, entry path and staging file.
    var paths = 16
    try {
        if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(canonical).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    checkSourceInterruption()
                    val path = iterator.next()
                    if (++paths > MAX_MANAGED_CACHE_PATHS) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
                    if (Files.isSymbolicLink(path)) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        val size = Files.size(path)
                        if (size > maximumBytes - bytes) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
                        bytes += size
                    } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
                    }
                    if (bytes > maximumBytes) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
                }
            }
        }
    } catch (failure: UncheckedIOException) {
        throw failure.cause ?: failure
    }
    if (bytes > maximumBytes) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
    checkSourceInterruption()
    operation()
}
